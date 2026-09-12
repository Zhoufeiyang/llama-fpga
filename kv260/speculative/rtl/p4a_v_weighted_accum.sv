`timescale 1ns/1ps
// Focused numerical shell for the production FP16 multiply/accumulate ordering
// used by attention V accumulation. One fragment is one probability * V lane.
module p4a_v_weighted_accum(
  input logic clk,input logic reset,
  input logic input_valid,input logic input_last,
  input logic [15:0] probability,input logic [15:0] value,
  output logic output_valid,output logic [15:0] output_data
);
  logic product_valid;logic[15:0]product;
  logic[5:0]last_pipe;
  logic acc_valid,acc_last;logic[15:0]acc_data;
  fp16mul6 mul(
    .aclk(clk),.s_axis_a_tvalid(input_valid),.s_axis_a_tdata(probability),
    .s_axis_b_tvalid(input_valid),.s_axis_b_tdata(value),
    .m_axis_result_tvalid(product_valid),.m_axis_result_tdata(product));
  always_ff @(posedge clk) begin
    if(reset) last_pipe<='0;
    else last_pipe<={last_pipe[4:0],input_valid&&input_last};
  end
  fp16acc16 acc(
    .aclk(clk),.aresetn(~reset),.s_axis_a_tvalid(product_valid),
    .s_axis_a_tlast(last_pipe[5]),.s_axis_a_tdata(product),
    .m_axis_result_tvalid(acc_valid),.m_axis_result_tlast(acc_last),
    .m_axis_result_tdata(acc_data));
  assign output_valid=acc_valid&&acc_last;
  assign output_data=acc_data;
endmodule
