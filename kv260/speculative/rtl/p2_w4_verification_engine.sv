`timescale 1ns/1ps

// P2-A scheduler + P2-B shared numerical backend.
module p2_w4_verification_engine #(
  parameter integer LANES = 128,
  parameter integer MAX_BEATS_PER_ROW = 32,
  parameter integer MAX_K = 4,
  parameter integer MAX_ROWS = 4096
) (
  input logic clk, input logic reset,
  input logic cfg_valid, output logic cfg_ready, input logic cfg_mode,
  input logic [2:0] cfg_k, input logic [15:0] cfg_beats_per_row, input logic [15:0] cfg_rows,
  input logic activation_valid, output logic activation_ready,
  input logic [16*LANES-1:0] activation_data, input logic activation_last,
  input logic weight_valid, output logic weight_ready,
  input logic [4*LANES-1:0] weight_packed, input logic [7:0] weight_zero,
  input logic [15:0] weight_scale, input logic weight_last,
  output logic result_valid, output logic [15:0] result_data,
  output logic [2:0] result_token_index,
  output logic [((MAX_ROWS<=1)?1:$clog2(MAX_ROWS))-1:0] result_row_index,
  output logic busy, output logic done, output logic error,
  output logic [3:0] error_code, output logic [31:0] accepted_weight_beats
);
  localparam integer ROW_W=(MAX_ROWS<=1)?1:$clog2(MAX_ROWS);
  localparam integer BEAT_W=(MAX_BEATS_PER_ROW<=1)?1:$clog2(MAX_BEATS_PER_ROW);
  logic operand_valid, operand_ready, operand_mode, operand_last;
  logic [16*LANES-1:0] operand_activation;
  logic [5*LANES-1:0] operand_weight;
  logic [15:0] operand_scale;
  logic [2:0] operand_token_index;
  logic [ROW_W-1:0] operand_row_index;
  logic [BEAT_W-1:0] operand_beat_index;
  logic [15:0] beats_reg;

  always_ff @(posedge clk) if (reset) beats_reg <= '0; else if (cfg_valid && cfg_ready) beats_reg <= cfg_beats_per_row;

  p2_w4_weight_reuse_scheduler #(.LANES(LANES), .MAX_BEATS_PER_ROW(MAX_BEATS_PER_ROW),
    .MAX_K(MAX_K), .MAX_ROWS(MAX_ROWS)) u_scheduler (.*);

  p2_w4_fp16_backend #(.LANES(LANES), .BANKS(4), .MAX_K(MAX_K),
    .ROW_W(ROW_W), .BEAT_W(BEAT_W)) u_backend (
    .clk, .reset, .operand_valid, .operand_ready, .operand_activation, .operand_weight,
    .operand_scale, .operand_token_index, .operand_row_index, .operand_beat_index,
    .beats_per_row(beats_reg), .result_valid, .result_data,
    .result_token_index, .result_row_index);
endmodule
