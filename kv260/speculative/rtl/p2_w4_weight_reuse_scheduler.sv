`timescale 1ns/1ps

// P2 W4 weight-reuse operand front-end.
//
// This module intentionally stops before FP16 conversion and MAC.  The
// downstream operand consumer should use the existing Int4Int8FP16Conv /
// fp16int9d4 and FP16 MAC datapath.  One packed weight beat is accepted once,
// then emitted with each of K activation rows.  Therefore the weight stream
// traffic is independent of K (K=1..4).
//
// packed[4*i +: 4] is the i-th W4 value (low nibble first).  weight_zero is
// one unsigned four-bit zero point for every value in this beat.  The output
// weight is a signed five-bit q-zero value, packed as op_weight[5*i +: 5].
module p2_w4_weight_reuse_scheduler #(
  parameter integer LANES = 128,
  parameter integer MAX_BEATS_PER_ROW = 32,
  parameter integer MAX_K = 4,
  parameter integer MAX_ROWS = 4096
) (
  input  logic                         clk,
  input  logic                         reset,

  input  logic                         cfg_valid,
  output logic                         cfg_ready,
  input  logic                         cfg_mode, // 0: legacy GEMV, 1: GEMM verify
  input  logic [2:0]                   cfg_k,
  input  logic [15:0]                  cfg_beats_per_row,
  input  logic [15:0]                  cfg_rows,

  input  logic                         activation_valid,
  output logic                         activation_ready,
  input  logic [16*LANES-1:0]          activation_data,
  input  logic                         activation_last,

  input  logic                         weight_valid,
  output logic                         weight_ready,
  input  logic [4*LANES-1:0]           weight_packed,
  input  logic [7:0]                   weight_zero,
  input  logic [15:0]                  weight_scale,
  input  logic                         weight_last,

  output logic                         operand_valid,
  input  logic                         operand_ready,
  output logic [16*LANES-1:0]           operand_activation,
  output logic [5*LANES-1:0]            operand_weight,
  output logic [15:0]                  operand_scale,
  output logic [2:0]                   operand_token_index,
  output logic                         operand_mode,
  output logic [((MAX_ROWS <= 1) ? 1 : $clog2(MAX_ROWS))-1:0]
                                        operand_row_index,
  output logic [((MAX_BEATS_PER_ROW <= 1) ? 1 : $clog2(MAX_BEATS_PER_ROW))-1:0]
                                        operand_beat_index,
  output logic                         operand_last,

  output logic                         busy,
  output logic                         done,
  output logic                         error,
  output logic [3:0]                   error_code,
  output logic [31:0]                  accepted_weight_beats
);

  localparam integer BEAT_W = (MAX_BEATS_PER_ROW <= 1) ? 1 : $clog2(MAX_BEATS_PER_ROW);
  localparam integer ACT_DEPTH = MAX_K * MAX_BEATS_PER_ROW;
  localparam integer ACT_ADDR_W = (ACT_DEPTH <= 1) ? 1 : $clog2(ACT_DEPTH);
  localparam integer ROW_W = (MAX_ROWS <= 1) ? 1 : $clog2(MAX_ROWS);

  localparam logic [3:0] ERR_NONE = 4'd0;
  localparam logic [3:0] ERR_INVALID_K = 4'd1;
  localparam logic [3:0] ERR_INVALID_BEATS = 4'd2;
  localparam logic [3:0] ERR_INVALID_ZERO = 4'd3;
  localparam logic [3:0] ERR_INVALID_WEIGHT_LAST = 4'd4;
  localparam logic [3:0] ERR_INVALID_ACTIVATION_LAST = 4'd5;
  localparam logic [3:0] ERR_GEMV_K = 4'd6;
  localparam logic [3:0] ERR_INVALID_ROWS = 4'd7;

  typedef enum logic [2:0] {
    ST_IDLE,
    ST_LOAD_ACT,
    ST_RUN,
    ST_DONE,
    ST_ERROR
  } state_t;

  state_t state;

  logic [2:0]  k_reg;
  logic        mode_reg;
  logic [15:0] beats_reg;
  logic [15:0] rows_reg;

  logic [2:0]  load_token;
  logic [BEAT_W-1:0] load_beat;
  logic [BEAT_W-1:0] weight_beat_index;
  logic [ROW_W-1:0]  weight_row_index;
  logic [2:0]         active_token;
  logic [4*LANES-1:0] active_weight;
  logic [7:0]         active_zero;
  logic [15:0]        active_scale;
  logic               active;

  logic               error_reg;
  logic [3:0]         error_code_reg;

  // KMAX x 4096 FP16 values occupy 32 KiB for the KV260 configuration.  A
  // registered read maps this tile buffer to BRAM instead of consuming LUTRAM.
  (* ram_style = "block" *)
  logic [16*LANES-1:0] activation_mem [0:ACT_DEPTH-1];
  logic [16*LANES-1:0] operand_activation_reg;
  wire [ACT_ADDR_W-1:0] activation_write_address =
    (load_token * MAX_BEATS_PER_ROW) + load_beat;
  logic activation_read_enable;
  logic [ACT_ADDR_W-1:0] activation_read_address;

  wire cfg_fire = cfg_valid && cfg_ready;
  wire activation_fire = activation_valid && activation_ready;
  wire weight_fire = weight_valid && weight_ready;
  wire operand_fire = operand_valid && operand_ready;

  wire cfg_k_valid = (cfg_k >= 3'd1) && (cfg_k <= MAX_K);
  wire cfg_beats_valid = (cfg_beats_per_row >= 16'd1) &&
                         (cfg_beats_per_row <= MAX_BEATS_PER_ROW);
  wire cfg_rows_valid = (cfg_rows >= 16'd1) && (cfg_rows <= MAX_ROWS);
  wire final_load_beat = (load_beat == (beats_reg - 16'd1));
  wire final_load_activation = (load_token == (k_reg - 3'd1)) &&
                               final_load_beat;
  wire final_weight_beat = (weight_beat_index == (beats_reg - 16'd1));
  wire final_weight_row = (weight_row_index == (rows_reg - 16'd1));
  wire final_weight = final_weight_beat && final_weight_row;
  wire final_operand = (active_token == (k_reg - 3'd1)) && final_weight;

  assign cfg_ready = (state == ST_IDLE) && !error_reg;
  assign activation_ready = (state == ST_LOAD_ACT) && !error_reg;
  assign weight_ready = (state == ST_RUN) && !active && !error_reg;

  assign operand_valid = active && !error_reg;
  assign operand_token_index = active_token;
  assign operand_mode = mode_reg;
  assign operand_row_index = weight_row_index;
  assign operand_beat_index = weight_beat_index;
  assign operand_last = final_operand && operand_valid;

  assign busy = (state == ST_LOAD_ACT) || (state == ST_RUN);
  assign done = (state == ST_DONE);
  assign error = error_reg || (state == ST_ERROR);
  assign error_code = error_code_reg;

  assign operand_activation = operand_activation_reg;
  assign operand_scale = active_scale;

  // Keep memory accesses in canonical simple-dual-port templates so Vivado
  // infers BRAM for the full KMAX=4, D=4096 activation tile.
  always_ff @(posedge clk) begin
    if (activation_fire)
      activation_mem[activation_write_address] <= activation_data;
  end

  always_comb begin
    activation_read_enable = 1'b0;
    activation_read_address = '0;
    if (weight_fire) begin
      activation_read_enable = 1'b1;
      activation_read_address = weight_beat_index;
    end else if (operand_fire && (active_token != (k_reg - 3'd1))) begin
      activation_read_enable = 1'b1;
      activation_read_address =
        ((active_token + 1'b1) * MAX_BEATS_PER_ROW) + weight_beat_index;
    end
  end

  always_ff @(posedge clk) begin
    if (activation_read_enable)
      operand_activation_reg <= activation_mem[activation_read_address];
  end

  integer lane;
  logic signed [5:0] dequant_lane;
  always_comb begin
    operand_weight = '0;
    for (lane = 0; lane < LANES; lane = lane + 1) begin
      // Both operands are made positive signed values before subtraction.
      // q-zero is in [-15, 15], which fits in signed five bits.
      dequant_lane = $signed({1'b0, active_weight[(lane*4) +: 4]}) -
                     $signed({1'b0, active_zero[3:0]});
      operand_weight[(lane*5) +: 5] = dequant_lane[4:0];
    end
  end

  always_ff @(posedge clk) begin
    if (reset) begin
      state <= ST_IDLE;
      k_reg <= '0;
      mode_reg <= 1'b0;
      beats_reg <= '0;
      rows_reg <= '0;
      load_token <= '0;
      load_beat <= '0;
      weight_beat_index <= '0;
      weight_row_index <= '0;
      active_token <= '0;
      active_weight <= '0;
      active_zero <= '0;
      active_scale <= '0;
      active <= 1'b0;
      error_reg <= 1'b0;
      error_code_reg <= ERR_NONE;
      accepted_weight_beats <= '0;
    end else begin
      case (state)
        ST_IDLE: begin
          if (cfg_fire) begin
            if (!cfg_k_valid) begin
              error_reg <= 1'b1;
              error_code_reg <= ERR_INVALID_K;
              state <= ST_ERROR;
            end else if (!cfg_beats_valid) begin
              error_reg <= 1'b1;
              error_code_reg <= ERR_INVALID_BEATS;
              state <= ST_ERROR;
            end else if (!cfg_mode && (cfg_k != 3'd1)) begin
              error_reg <= 1'b1;
              error_code_reg <= ERR_GEMV_K;
              state <= ST_ERROR;
            end else if (!cfg_rows_valid) begin
              error_reg <= 1'b1;
              error_code_reg <= ERR_INVALID_ROWS;
              state <= ST_ERROR;
            end else begin
              k_reg <= cfg_k;
              mode_reg <= cfg_mode;
              beats_reg <= cfg_beats_per_row;
              rows_reg <= cfg_rows;
              load_token <= '0;
              load_beat <= '0;
              weight_beat_index <= '0;
              weight_row_index <= '0;
              active_token <= '0;
              active <= 1'b0;
              accepted_weight_beats <= '0;
              state <= ST_LOAD_ACT;
            end
          end
        end

        ST_LOAD_ACT: begin
          if (activation_fire) begin
            if (activation_last != final_load_activation) begin
              error_reg <= 1'b1;
              error_code_reg <= ERR_INVALID_ACTIVATION_LAST;
              state <= ST_ERROR;
            end else if (final_load_activation) begin
              load_token <= '0;
              load_beat <= '0;
              weight_beat_index <= '0;
              weight_row_index <= '0;
              active_token <= '0;
              active <= 1'b0;
              state <= ST_RUN;
            end else begin
              if (final_load_beat) begin
                load_beat <= '0;
                load_token <= load_token + 1'b1;
              end else begin
                load_beat <= load_beat + 1'b1;
              end
            end
          end
        end

        ST_RUN: begin
          if (weight_fire) begin
            accepted_weight_beats <= accepted_weight_beats + 1'b1;
            if (weight_zero > 8'd15) begin
              error_reg <= 1'b1;
              error_code_reg <= ERR_INVALID_ZERO;
              state <= ST_ERROR;
            end else if (weight_last != final_weight) begin
              error_reg <= 1'b1;
              error_code_reg <= ERR_INVALID_WEIGHT_LAST;
              state <= ST_ERROR;
            end else begin
              active_weight <= weight_packed;
              active_zero <= weight_zero;
              active_scale <= weight_scale;
              active_token <= '0;
              active <= 1'b1;
            end
          end

          if (operand_fire) begin
            if (active_token == (k_reg - 3'd1)) begin
              active_token <= '0;
              active <= 1'b0;
              if (final_weight) begin
                state <= ST_DONE;
              end else if (final_weight_beat) begin
                weight_beat_index <= '0;
                weight_row_index <= weight_row_index + 1'b1;
              end else begin
                weight_beat_index <= weight_beat_index + 1'b1;
              end
            end else begin
              active_token <= active_token + 1'b1;
            end
          end
        end

        ST_DONE: begin
          // A one-cycle done indication permits a new descriptor without
          // requiring reset between focused simulation cases.
          state <= ST_IDLE;
        end

        ST_ERROR: begin
          // Error is sticky until reset; no input is accepted in this state.
          state <= ST_ERROR;
        end

        default: begin
          state <= ST_ERROR;
          error_reg <= 1'b1;
          error_code_reg <= ERR_INVALID_BEATS;
        end
      endcase
    end
  end

endmodule
