`timescale 1ns/1ps
module p3_batched_projection_controller (
  input logic clk,input logic reset,
  input logic start_valid,output logic start_ready,input logic start_mode,
  input logic [2:0] start_k,input logic [5:0] start_layers,
  output logic p2_cfg_valid,input logic p2_cfg_ready,output logic p2_cfg_mode,
  output logic [2:0] p2_cfg_k,output logic [15:0] p2_cfg_rows,
  output logic [15:0] p2_cfg_beats_per_row,input logic p2_done,input logic p2_error,
  output logic attention_request,input logic attention_done,
  output logic mlp_activation_request,input logic mlp_activation_done,
  output logic busy,output logic done,output logic error,output logic [3:0] error_code,
  output logic [31:0] launched_projections
);
  logic d_valid,d_ready,d_mode,d_last,completion_valid,adapter_busy,adapter_error;
  logic [2:0] d_k; logic [5:0] d_tag,d_layer,completion_tag,completion_layer;
  logic [15:0] d_rows,d_beats;
  logic scheduler_busy,scheduler_error; logic [3:0] scheduler_error_code;
  p3_transformer_projection_scheduler u_scheduler(
    .clk,.reset,.start_valid,.start_ready,.start_mode,.start_k,.start_layers,
    .projection_valid(d_valid),.projection_ready(d_ready),.projection_mode(d_mode),
    .projection_k(d_k),.projection_tag(d_tag),.projection_layer(d_layer),
    .projection_rows(d_rows),.projection_beats_per_row(d_beats),.projection_last(d_last),
    .projection_done(completion_valid),.projection_done_tag(completion_tag),
    .projection_done_layer(completion_layer),.attention_request,.attention_done,
    .mlp_activation_request,.mlp_activation_done,.busy(scheduler_busy),.done,
    .error(scheduler_error),.error_code(scheduler_error_code),.launched_projections);
  p3_p2_projection_adapter u_adapter(
    .clk,.reset,.descriptor_valid(d_valid),.descriptor_ready(d_ready),
    .descriptor_mode(d_mode),.descriptor_k(d_k),.descriptor_tag(d_tag),
    .descriptor_layer(d_layer),.descriptor_rows(d_rows),.descriptor_beats_per_row(d_beats),
    .p2_cfg_valid,.p2_cfg_ready,.p2_cfg_mode,.p2_cfg_k,.p2_cfg_rows,
    .p2_cfg_beats_per_row,.p2_done,.p2_error,.completion_valid,.completion_tag,
    .completion_layer,.busy(adapter_busy),.error(adapter_error));
  assign busy=scheduler_busy|adapter_busy;
  assign error=scheduler_error|adapter_error;
  assign error_code=adapter_error?4'd6:scheduler_error_code;
endmodule
