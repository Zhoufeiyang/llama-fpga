`timescale 1ns/1ps
module p6b_axilite_results_tb;
  logic clk=0,reset=1;always #1.667 clk=~clk;
  logic io_ctrl_aw_valid;wire io_ctrl_aw_ready;logic[31:0]io_ctrl_aw_payload_addr;logic[2:0]io_ctrl_aw_payload_prot;
  logic io_ctrl_w_valid;wire io_ctrl_w_ready;logic[31:0]io_ctrl_w_payload_data;logic[3:0]io_ctrl_w_payload_strb;
  wire io_ctrl_b_valid;logic io_ctrl_b_ready;wire[1:0]io_ctrl_b_payload_resp;
  logic io_ctrl_ar_valid;wire io_ctrl_ar_ready;logic[31:0]io_ctrl_ar_payload_addr;logic[2:0]io_ctrl_ar_payload_prot;
  wire io_ctrl_r_valid;logic io_ctrl_r_ready;wire[31:0]io_ctrl_r_payload_data;wire[1:0]io_ctrl_r_payload_resp;
  wire io_tokenIndex_valid;wire[15:0]io_tokenIndex_tdata;wire[5:0]io_tokenIndex_tuser;wire[1:0]io_cmdSel;
  wire io_speculativeTokenIndex_valid;wire[15:0]io_speculativeTokenIndex_tdata;wire[5:0]io_speculativeTokenIndex_tuser;logic io_speculativeTokenIndex_ready;
  wire[4:0]io_presetLayer;wire[9:0]io_presetToken;wire io_speculativeEnable;wire[1:0]io_speculativeQuery;
  wire io_speculativeActive; wire[10:0]io_speculativeCommitted,io_speculativeBase;
  wire[2:0]io_speculativeK; wire[7:0]io_speculativeEpoch;
  logic[15:0]status_tokenCnt;logic[7:0]status_layerCnt;
  logic status_argMaxVld;logic[15:0]status_argMaxIndex;logic status_prefill;wire io_perfWindowActive,io_perfWindowClear;wire resetOut;
  wire io_speculativeBatch_valid;logic io_speculativeBatch_ready;
  wire io_speculativeBatch_payload_mode;wire[2:0]io_speculativeBatch_payload_k;
  wire[5:0]io_speculativeBatch_payload_projectionTag;wire[7:0]io_speculativeBatch_payload_layerId;
  wire[15:0]io_speculativeBatch_payload_rows,io_speculativeBatch_payload_beatsPerRow;
  logic status_projectionDone,status_projectionError;
  logic[5:0]status_projectionDoneTag;logic[7:0]status_projectionDoneLayer;
  logic status_attentionDone,status_mlpActivationDone;
  logic[63:0]status_perfWeightBytes,status_perfKvReadBytes,status_perfKvWriteBytes,status_perfVerifyCycles,status_perfMemoryStallCycles;
  integer errors,i;logic[31:0]rd;
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
  task push_argmax(input[15:0]token);begin
    @(negedge clk);status_argMaxIndex=token;status_argMaxVld=1;@(negedge clk);status_argMaxVld=0;repeat(3)@(posedge clk);
  end endtask
  initial begin
    errors=0;io_speculativeTokenIndex_ready=1;io_ctrl_aw_valid=0;io_ctrl_w_valid=0;io_ctrl_b_ready=1;io_ctrl_ar_valid=0;io_ctrl_r_ready=1;
    io_ctrl_aw_payload_prot=0;io_ctrl_ar_payload_prot=0;status_tokenCnt=0;status_layerCnt=0;status_argMaxVld=0;status_argMaxIndex=0;status_prefill=0;
    io_speculativeBatch_ready=1;status_projectionDone=0;status_projectionError=0;
    status_projectionDoneTag=0;status_projectionDoneLayer=0;status_attentionDone=0;status_mlpActivationDone=0;
    status_perfWeightBytes=0;status_perfKvReadBytes=0;status_perfKvWriteBytes=0;status_perfVerifyCycles=0;status_perfMemoryStallCycles=0;
    repeat(5)@(posedge clk);reset=0;wr(32'h108,100);wr(32'h104,4);wr(32'h138,20);
    for(i=0;i<4;i=i+1)wr(32'h110+i*4,10+i);
    wr(32'h100,1);repeat(3)@(posedge clk);
    for(i=1;i<5;i=i+1)push_argmax(20+i);
    rdreg(32'h134,rd);if(rd[2:0]!=5)errors=errors+1;
    for(i=0;i<5;i=i+1)begin rdreg(32'h140+i*4,rd);if(rd[15:0]!=20+i)errors=errors+1;end
    for(i=0;i<4;i=i+1)begin rdreg(32'h110+i*4,rd);if(rd[15:0]!=10+i)errors=errors+1;end
    wr(32'h120,32'h104);repeat(3)@(posedge clk);wr(32'h100,1);repeat(3)@(posedge clk);
    rdreg(32'h134,rd);if(rd[2:0]!=5)errors=errors+1;rdreg(32'h130,rd);if(!rd[2])errors=errors+1;
    wr(32'h154,1);repeat(3)@(posedge clk);rdreg(32'h134,rd);if(rd[2:0]!=0)errors=errors+1;
    if(errors)$fatal(1,"P6-B AXI result buffer failed with %0d errors",errors);
    $display("P6B_AXILITE_RESULTS_GO CANDIDATES=4 TARGETS=5 INITIAL_TARGET=1 HOLD_UNTIL_ACK=1 OVERWRITE_BLOCKED=1");$finish;
  end
endmodule
