`timescale 1ns/1ps

// P2-B shared numerical backend.  The arithmetic boundaries intentionally
// mirror MulAddSGNew: FP16 lane multiply and per-bank reduction, followed by
// FP32 cross-bank reduction, scale, accumulation, and FP16 conversion.
module p2_w4_fp16_backend #(
  parameter integer LANES = 128,
  parameter integer BANKS = 4,
  parameter integer MAX_K = 4,
  parameter integer ROW_W = 12,
  parameter integer BEAT_W = 5
) (
  input  logic clk,
  input  logic reset,
  input  logic operand_valid,
  output logic operand_ready,
  input  logic [16*LANES-1:0] operand_activation,
  input  logic [5*LANES-1:0] operand_weight,
  input  logic [15:0] operand_scale,
  input  logic [2:0] operand_token_index,
  input  logic [ROW_W-1:0] operand_row_index,
  input  logic [BEAT_W-1:0] operand_beat_index,
  input  logic [15:0] beats_per_row,
  output logic result_valid,
  output logic [15:0] result_data,
  output logic [2:0] result_token_index,
  output logic [ROW_W-1:0] result_row_index
);
  localparam integer BANK_LANES = LANES / BANKS;
  localparam integer BANK_LEVELS = $clog2(BANK_LANES);
  localparam integer BANK_REDUCE_LAT = 6 * BANK_LEVELS;
  localparam integer CROSS_LEVELS = $clog2(BANKS);
  localparam integer CROSS_REDUCE_LAT = 11 * CROSS_LEVELS;
  localparam integer PRE_ACC_LAT = 4 + 6 + BANK_REDUCE_LAT + 2 + CROSS_REDUCE_LAT + 8;
  localparam integer SCALE_ALIGN_LAT = PRE_ACC_LAT - 2 - 8;
  localparam integer POST_ACC_LAT = 22 + 3;

  initial begin
    if ((LANES % BANKS) != 0 || (BANK_LANES & (BANK_LANES-1)) != 0 ||
        (BANKS & (BANKS-1)) != 0)
      $error("LANES/BANKS and BANKS must be powers of two");
  end

  assign operand_ready = 1'b1;
  wire fire = operand_valid && operand_ready;

  logic [15:0] weight_fp16 [0:LANES-1];
  logic weight_fp16_valid [0:LANES-1];
  logic [15:0] weight_scaled [0:LANES-1];
  logic [15:0] activation_dly [0:LANES-1][0:3];
  logic [15:0] product [0:LANES-1];
  logic product_valid [0:LANES-1];

  genvar lane;
  generate for (lane=0; lane<LANES; lane=lane+1) begin : g_lane
    wire [15:0] signed_weight = {{11{operand_weight[lane*5+4]}}, operand_weight[lane*5 +: 5]};
    fp16int9d4 u_int_to_fp16 (
      .aclk(clk), .s_axis_a_tvalid(fire), .s_axis_a_tdata(signed_weight),
      .m_axis_result_tvalid(weight_fp16_valid[lane]), .m_axis_result_tdata(weight_fp16[lane]));
    always_comb begin
      weight_scaled[lane] = {weight_fp16[lane][15],
        (weight_fp16[lane][14:10] > 5'd2) ? weight_fp16[lane][14:10]-5'd2 : 5'd0,
        weight_fp16[lane][9:0]};
    end
    integer ad;
    always_ff @(posedge clk) begin
      activation_dly[lane][0] <= operand_activation[lane*16 +: 16];
      for (ad=1; ad<4; ad=ad+1) activation_dly[lane][ad] <= activation_dly[lane][ad-1];
    end
    fp16mul6 u_mul (
      .aclk(clk),
      .s_axis_a_tvalid(weight_fp16_valid[lane]), .s_axis_a_tdata(weight_scaled[lane]),
      .s_axis_b_tvalid(weight_fp16_valid[lane]), .s_axis_b_tdata(activation_dly[lane][3]),
      .m_axis_result_tvalid(product_valid[lane]), .m_axis_result_tdata(product[lane]));
  end endgenerate

  // Rectangular arrays avoid tool-dependent unpacked-array ports.
  logic [15:0] bank_tree [0:BANKS-1][0:BANK_LEVELS][0:BANK_LANES-1];
  logic bank_valid [0:BANKS-1][0:BANK_LEVELS];
  genvar b, n, l;
  generate
    for (b=0; b<BANKS; b=b+1) begin : g_bank_seed
      for (n=0; n<BANK_LANES; n=n+1)
        always_comb bank_tree[b][0][n] = product[b*BANK_LANES+n];
      always_comb bank_valid[b][0] = product_valid[b*BANK_LANES];
    end
    for (l=0; l<BANK_LEVELS; l=l+1) begin : g_bank_level
      for (b=0; b<BANKS; b=b+1) begin : g_bank
        for (n=0; n<(BANK_LANES>>(l+1)); n=n+1) begin : g_node
          fp16add6 u_add (
            .aclk(clk),
            .s_axis_a_tvalid(bank_valid[b][l]), .s_axis_a_tdata(bank_tree[b][l][2*n]),
            .s_axis_b_tvalid(bank_valid[b][l]), .s_axis_b_tdata(bank_tree[b][l][2*n+1]),
            .m_axis_result_tvalid(), .m_axis_result_tdata(bank_tree[b][l+1][n]));
        end
        logic [5:0] valid_pipe;
        always_ff @(posedge clk) begin
          if (reset) valid_pipe <= '0;
          else valid_pipe <= {valid_pipe[4:0], bank_valid[b][l]};
        end
        always_comb bank_valid[b][l+1] = valid_pipe[5];
      end
    end
  endgenerate

  logic [31:0] bank_fp32 [0:BANKS-1];
  logic bank_fp32_valid [0:BANKS-1];
  generate for (b=0; b<BANKS; b=b+1) begin : g_bank_fp32
    fp16toFp32 u_to_fp32(
      .aclk(clk), .s_axis_a_tvalid(bank_valid[b][BANK_LEVELS]),
      .s_axis_a_tdata(bank_tree[b][BANK_LEVELS][0]),
      .m_axis_result_tvalid(bank_fp32_valid[b]), .m_axis_result_tdata(bank_fp32[b]));
  end endgenerate

  logic [31:0] cross_tree [0:CROSS_LEVELS][0:BANKS-1];
  logic cross_valid [0:CROSS_LEVELS];
  generate
    for (b=0; b<BANKS; b=b+1) always_comb cross_tree[0][b] = bank_fp32[b];
    always_comb cross_valid[0] = bank_fp32_valid[0];
    for (l=0; l<CROSS_LEVELS; l=l+1) begin : g_cross_level
      for (n=0; n<(BANKS>>(l+1)); n=n+1) begin : g_node
        fp32add11 u_add(
          .aclk(clk),
          .s_axis_a_tvalid(cross_valid[l]), .s_axis_a_tdata(cross_tree[l][2*n]),
          .s_axis_b_tvalid(cross_valid[l]), .s_axis_b_tdata(cross_tree[l][2*n+1]),
          .m_axis_result_tvalid(), .m_axis_result_tdata(cross_tree[l+1][n]));
      end
      logic [10:0] valid_pipe;
      always_ff @(posedge clk) begin
        if (reset) valid_pipe <= '0;
        else valid_pipe <= {valid_pipe[9:0], cross_valid[l]};
      end
      always_comb cross_valid[l+1] = valid_pipe[10];
    end
  endgenerate

  logic scale_fp32_valid;
  logic [31:0] scale_fp32;
  fp16toFp32 u_scale_to_fp32(
    .aclk(clk), .s_axis_a_tvalid(fire), .s_axis_a_tdata(operand_scale),
    .m_axis_result_tvalid(scale_fp32_valid), .m_axis_result_tdata(scale_fp32));
  logic [31:0] scale_pipe [0:SCALE_ALIGN_LAT-1];
  integer sp;
  integer rp;
  always_ff @(posedge clk) begin
    scale_pipe[0] <= scale_fp32;
    for (sp=1; sp<SCALE_ALIGN_LAT; sp=sp+1) scale_pipe[sp] <= scale_pipe[sp-1];
  end

  logic scaled_valid;
  logic [31:0] scaled_data;
  fp32mul8 u_group_scale(
    .aclk(clk),
    .s_axis_a_tvalid(cross_valid[CROSS_LEVELS]), .s_axis_a_tdata(cross_tree[CROSS_LEVELS][0]),
    .s_axis_b_tvalid(cross_valid[CROSS_LEVELS]), .s_axis_b_tdata(scale_pipe[SCALE_ALIGN_LAT-1]),
    .m_axis_result_tvalid(scaled_valid), .m_axis_result_tdata(scaled_data));

  logic [2:0] token_pipe [0:PRE_ACC_LAT-1];
  logic [ROW_W-1:0] row_pipe [0:PRE_ACC_LAT-1];
  logic last_pipe [0:PRE_ACC_LAT-1];
  integer mp;
  always_ff @(posedge clk) begin
    token_pipe[0] <= operand_token_index;
    row_pipe[0] <= operand_row_index;
    last_pipe[0] <= fire && (operand_beat_index == beats_per_row-1'b1);
    for (mp=1; mp<PRE_ACC_LAT; mp=mp+1) begin
      token_pipe[mp] <= token_pipe[mp-1]; row_pipe[mp] <= row_pipe[mp-1]; last_pipe[mp] <= last_pipe[mp-1];
    end
  end

  logic acc_valid [0:MAX_K-1], acc_last [0:MAX_K-1];
  logic [31:0] acc_data [0:MAX_K-1];
  logic out_valid [0:MAX_K-1];
  logic [15:0] out_data [0:MAX_K-1];
  generate for (b=0; b<MAX_K; b=b+1) begin : g_token_acc
    fp32acc22 u_acc(
      .aclk(clk), .aresetn(~reset),
      .s_axis_a_tvalid(scaled_valid && token_pipe[PRE_ACC_LAT-1] == b),
      .s_axis_a_tdata(scaled_data), .s_axis_a_tlast(last_pipe[PRE_ACC_LAT-1]),
      .m_axis_result_tvalid(acc_valid[b]), .m_axis_result_tdata(acc_data[b]),
      .m_axis_result_tlast(acc_last[b]));
    fp32toFp16 u_to_fp16(
      .aclk(clk), .s_axis_a_tvalid(acc_valid[b] && acc_last[b]), .s_axis_a_tdata(acc_data[b]),
      .m_axis_result_tvalid(out_valid[b]), .m_axis_result_tdata(out_data[b]));
  end endgenerate

  logic [2:0] result_token_pipe [0:POST_ACC_LAT-1];
  logic [ROW_W-1:0] result_row_pipe [0:POST_ACC_LAT-1];
  always_ff @(posedge clk) begin
    result_token_pipe[0] <= token_pipe[PRE_ACC_LAT-1];
    result_row_pipe[0] <= row_pipe[PRE_ACC_LAT-1];
    for (rp=1; rp<POST_ACC_LAT; rp=rp+1) begin
      result_token_pipe[rp] <= result_token_pipe[rp-1];
      result_row_pipe[rp] <= result_row_pipe[rp-1];
    end
  end

  integer oi;
  always_comb begin
    result_valid = 1'b0; result_data = '0;
    for (oi=0; oi<MAX_K; oi=oi+1) begin
      result_valid = result_valid | out_valid[oi];
      if (out_valid[oi]) result_data = out_data[oi];
    end
    result_token_index = result_token_pipe[POST_ACC_LAT-1];
    result_row_index = result_row_pipe[POST_ACC_LAT-1];
  end
endmodule
