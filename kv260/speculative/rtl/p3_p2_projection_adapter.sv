`timescale 1ns/1ps

// Converts a P3 projection descriptor into the configuration handshake used by
// p2_w4_verification_engine and preserves completion identity while P2 runs.
module p3_p2_projection_adapter (
  input logic clk, input logic reset,
  input logic descriptor_valid, output logic descriptor_ready,
  input logic descriptor_mode, input logic [2:0] descriptor_k,
  input logic [5:0] descriptor_tag, input logic [5:0] descriptor_layer,
  input logic [15:0] descriptor_rows, input logic [15:0] descriptor_beats_per_row,
  output logic p2_cfg_valid, input logic p2_cfg_ready,
  output logic p2_cfg_mode, output logic [2:0] p2_cfg_k,
  output logic [15:0] p2_cfg_rows, output logic [15:0] p2_cfg_beats_per_row,
  input logic p2_done, input logic p2_error,
  output logic completion_valid, output logic [5:0] completion_tag,
  output logic [5:0] completion_layer,
  output logic busy, output logic error
);
  logic inflight,error_reg; logic [5:0] tag_reg, layer_reg;
  assign descriptor_ready=!inflight && !error_reg && p2_cfg_ready;
  assign p2_cfg_valid=descriptor_valid && !inflight && !error_reg;
  assign p2_cfg_mode=descriptor_mode; assign p2_cfg_k=descriptor_k;
  assign p2_cfg_rows=descriptor_rows; assign p2_cfg_beats_per_row=descriptor_beats_per_row;
  assign completion_valid=p2_done && inflight;
  assign completion_tag=tag_reg; assign completion_layer=layer_reg;
  assign busy=inflight; assign error=error_reg;
  always_ff @(posedge clk) begin
    if(reset) begin inflight<=0; error_reg<=0; tag_reg<=0; layer_reg<=0; end
    else begin
      if(descriptor_valid && descriptor_ready) begin
        inflight<=1; tag_reg<=descriptor_tag; layer_reg<=descriptor_layer;
      end
      if(p2_done && inflight) inflight<=0;
      if(p2_error && inflight) begin inflight<=0; error_reg<=1; end
    end
  end
endmodule
