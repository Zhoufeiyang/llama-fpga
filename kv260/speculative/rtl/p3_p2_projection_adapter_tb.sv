`timescale 1ns/1ps
module p3_p2_projection_adapter_tb;
  logic clk=0,reset=1; always #1.667 clk=~clk;
  logic descriptor_valid,descriptor_ready,descriptor_mode; logic [2:0] descriptor_k;
  logic [5:0] descriptor_tag,descriptor_layer; logic [15:0] descriptor_rows,descriptor_beats_per_row;
  logic p2_cfg_valid,p2_cfg_ready,p2_cfg_mode; logic [2:0] p2_cfg_k;
  logic [15:0] p2_cfg_rows,p2_cfg_beats_per_row; logic p2_done,p2_error;
  logic completion_valid; logic [5:0] completion_tag,completion_layer; logic busy,error;
  p3_p2_projection_adapter dut(.*);
  initial begin
    descriptor_valid=0; p2_cfg_ready=0; p2_done=0; p2_error=0;
    repeat(4) @(posedge clk); reset=0;
    @(negedge clk); descriptor_mode=1; descriptor_k=4; descriptor_tag=24; descriptor_layer=7;
    descriptor_rows=11008; descriptor_beats_per_row=32; descriptor_valid=1;
    repeat(2) begin @(posedge clk); if(descriptor_ready) $fatal(1,"descriptor bypassed P2 backpressure"); end
    @(negedge clk); p2_cfg_ready=1; @(posedge clk);
    if(!p2_cfg_valid || p2_cfg_k!=4 || p2_cfg_rows!=11008) $fatal(1,"P2 config mismatch");
    @(negedge clk); descriptor_valid=0; p2_cfg_ready=0;
    repeat(3) begin @(posedge clk); if(!busy || completion_valid) $fatal(1,"inflight state lost"); end
    @(negedge clk); p2_done=1; @(posedge clk);
    if(!completion_valid || completion_tag!=24 || completion_layer!=7) $fatal(1,"completion identity mismatch");
    @(negedge clk); p2_done=0; @(posedge clk); if(busy) $fatal(1,"adapter did not retire");
    reset=1;repeat(2)@(posedge clk);reset=0;
    @(negedge clk);descriptor_valid=1;p2_cfg_ready=1;descriptor_tag=5;descriptor_layer=1;
    @(posedge clk);@(negedge clk);descriptor_valid=0;p2_cfg_ready=0;p2_error=1;
    @(posedge clk);@(negedge clk);p2_error=0;repeat(2)@(posedge clk);
    if(!error||descriptor_ready) $fatal(1,"P2 error was not retained");
    $display("P3_P2_PROJECTION_ADAPTER_GO"); $finish;
  end
endmodule
