`timescale 1ns/1ps
module p3b_batch_descriptor_tb;
  logic clk=0,reset=1;always #1.667 clk=~clk;
  logic io_ctrl_aw_valid;wire io_ctrl_aw_ready;logic[31:0]io_ctrl_aw_payload_addr;logic[2:0]io_ctrl_aw_payload_prot;
  logic io_ctrl_w_valid;wire io_ctrl_w_ready;logic[31:0]io_ctrl_w_payload_data;logic[3:0]io_ctrl_w_payload_strb;
  wire io_ctrl_b_valid;logic io_ctrl_b_ready;wire[1:0]io_ctrl_b_payload_resp;
  logic io_ctrl_ar_valid;wire io_ctrl_ar_ready;logic[31:0]io_ctrl_ar_payload_addr;logic[2:0]io_ctrl_ar_payload_prot;
  wire io_ctrl_r_valid;logic io_ctrl_r_ready;wire[31:0]io_ctrl_r_payload_data;wire[1:0]io_ctrl_r_payload_resp;
  wire io_tokenIndex_valid;wire[15:0]io_tokenIndex_tdata;wire[5:0]io_tokenIndex_tuser;wire[1:0]io_cmdSel;
  wire[4:0]io_presetLayer;wire[9:0]io_presetToken;wire io_speculativeEnable;wire[1:0]io_speculativeQuery;
  wire[9:0]io_speculativeCommitted;wire io_perfWindowActive,io_perfWindowClear;wire resetOut;
  wire io_speculativeBatch_valid;logic io_speculativeBatch_ready;
  wire io_speculativeBatch_payload_mode;wire[2:0]io_speculativeBatch_payload_k;
  wire[5:0]io_speculativeBatch_payload_projectionTag;wire[7:0]io_speculativeBatch_payload_layerId;
  wire[15:0]io_speculativeBatch_payload_rows,io_speculativeBatch_payload_beatsPerRow;
  logic[15:0]status_tokenCnt;logic[7:0]status_layerCnt;logic status_argMaxVld;
  logic[15:0]status_argMaxIndex;logic status_prefill,status_projectionDone,status_projectionError;
  logic[63:0]status_perfWeightBytes,status_perfKvReadBytes,status_perfKvWriteBytes,status_perfVerifyCycles,status_perfMemoryStallCycles;
  integer errors;logic[31:0]rd;
  AxiLiteCtrl dut(.*);
  task wr(input[31:0]a,input[31:0]d);begin
    @(negedge clk);io_ctrl_aw_payload_addr=a;io_ctrl_aw_valid=1;io_ctrl_w_payload_data=d;io_ctrl_w_payload_strb=15;io_ctrl_w_valid=1;
    while(!(io_ctrl_aw_ready&&io_ctrl_w_ready))@(negedge clk);@(negedge clk);io_ctrl_aw_valid=0;io_ctrl_w_valid=0;
    while(!io_ctrl_b_valid)@(negedge clk);@(negedge clk);
  end endtask
  task rdreg(input[31:0]a,output[31:0]d);begin
    @(negedge clk);io_ctrl_ar_payload_addr=a;io_ctrl_ar_valid=1;while(!io_ctrl_ar_ready)@(negedge clk);
    @(negedge clk);io_ctrl_ar_valid=0;while(!io_ctrl_r_valid)@(negedge clk);d=io_ctrl_r_payload_data;@(negedge clk);
  end endtask
  initial begin
    errors=0;io_ctrl_aw_valid=0;io_ctrl_w_valid=0;io_ctrl_b_ready=1;io_ctrl_ar_valid=0;io_ctrl_r_ready=1;
    io_ctrl_aw_payload_prot=0;io_ctrl_ar_payload_prot=0;status_tokenCnt=0;status_layerCnt=0;
    status_argMaxVld=0;status_argMaxIndex=0;status_prefill=0;status_projectionDone=0;status_projectionError=0;
    status_perfWeightBytes=64'h00000001_a1a2a3a4;status_perfKvReadBytes=64'h00000002_b1b2b3b4;
    status_perfKvWriteBytes=64'h00000003_c1c2c3c4;status_perfVerifyCycles=64'h00000004_d1d2d3d4;
    status_perfMemoryStallCycles=64'h00000005_e1e2e3e4;
    io_speculativeBatch_ready=0;repeat(5)@(posedge clk);reset=0;
    wr(32'h160,32'h00000401);wr(32'h164,6'h2a);wr(32'h168,8'd17);
    wr(32'h16c,16'd4096);wr(32'h170,16'd32);wr(32'h174,1);repeat(3)@(posedge clk);
    if(!io_speculativeBatch_valid||!io_speculativeBatch_payload_mode||io_speculativeBatch_payload_k!=4||
       io_speculativeBatch_payload_projectionTag!=6'h2a||io_speculativeBatch_payload_layerId!=17||
       io_speculativeBatch_payload_rows!=4096||io_speculativeBatch_payload_beatsPerRow!=32)errors++;
    rdreg(32'h17c,rd);if(rd[1:0]!=2'b01)errors++;
    repeat(4)@(posedge clk);if(!io_speculativeBatch_valid)errors++;
    io_speculativeBatch_ready=1;repeat(3)@(posedge clk);if(io_speculativeBatch_valid)errors++;
    status_projectionDone=1;@(posedge clk);status_projectionDone=0;repeat(2)@(posedge clk);
    rdreg(32'h17c,rd);if(!rd[2])errors++;
    rdreg(32'h180,rd);if(rd!=32'ha1a2a3a4)errors++;
    rdreg(32'h184,rd);if(rd!=32'hb1b2b3b4)errors++;
    rdreg(32'h188,rd);if(rd!=32'hc1c2c3c4)errors++;
    rdreg(32'h18c,rd);if(rd!=32'hd1d2d3d4)errors++;
    rdreg(32'h190,rd);if(rd!=32'he1e2e3e4)errors++;
    wr(32'h160,32'h00000001);wr(32'h174,1);repeat(2)@(posedge clk);
    rdreg(32'h17c,rd);if(!rd[3])errors++;
    if(errors)$fatal(1,"P3-B descriptor failed errors=%0d",errors);
    $display("P3B_PRODUCTION_DESCRIPTOR_GO HELD_UNTIL_READY=1 MODE=GEMM K=4 PROJECTION=42 LAYER=17 ROWS=4096 BEATS=32 DONE=1 INVALID_FAULT=1 PERF_REGS=5");$finish;
  end
endmodule
