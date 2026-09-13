`timescale 1ns/1ps
module p3e_axilite_auto_sequence_tb;
  logic clk=0,reset=1;always #1.667 clk=~clk;
  logic io_ctrl_aw_valid;wire io_ctrl_aw_ready;logic[31:0]io_ctrl_aw_payload_addr;logic[2:0]io_ctrl_aw_payload_prot;
  logic io_ctrl_w_valid;wire io_ctrl_w_ready;logic[31:0]io_ctrl_w_payload_data;logic[3:0]io_ctrl_w_payload_strb;
  wire io_ctrl_b_valid;logic io_ctrl_b_ready;wire[1:0]io_ctrl_b_payload_resp;
  logic io_ctrl_ar_valid;wire io_ctrl_ar_ready;logic[31:0]io_ctrl_ar_payload_addr;logic[2:0]io_ctrl_ar_payload_prot;
  wire io_ctrl_r_valid;logic io_ctrl_r_ready;wire[31:0]io_ctrl_r_payload_data;wire[1:0]io_ctrl_r_payload_resp;
  wire io_tokenIndex_valid;wire[15:0]io_tokenIndex_tdata;wire[5:0]io_tokenIndex_tuser;wire[1:0]io_cmdSel;
  wire io_speculativeTokenIndex_valid;wire[15:0]io_speculativeTokenIndex_tdata;wire[5:0]io_speculativeTokenIndex_tuser;logic io_speculativeTokenIndex_ready;
  wire[4:0]io_presetLayer;wire[9:0]io_presetToken;wire io_speculativeEnable,io_speculativeActive;wire[1:0]io_speculativeQuery;
  wire[10:0]io_speculativeCommitted,io_speculativeBase;wire[2:0]io_speculativeK;wire[7:0]io_speculativeEpoch;
  wire io_perfWindowActive,io_perfWindowClear;wire resetOut;
  wire io_speculativeBatch_valid;logic io_speculativeBatch_ready;
  wire io_speculativeBatch_payload_mode;wire[2:0]io_speculativeBatch_payload_k;
  wire[5:0]io_speculativeBatch_payload_projectionTag;wire[7:0]io_speculativeBatch_payload_layerId;
  wire[15:0]io_speculativeBatch_payload_rows,io_speculativeBatch_payload_beatsPerRow;
  logic[15:0]status_tokenCnt;logic[7:0]status_layerCnt;logic status_argMaxVld;logic[15:0]status_argMaxIndex;
  logic status_prefill,status_projectionDone,status_projectionError;logic[5:0]status_projectionDoneTag;
  logic[7:0]status_projectionDoneLayer;logic status_attentionDone,status_mlpActivationDone;
  logic[63:0]status_perfWeightBytes,status_perfKvReadBytes,status_perfKvWriteBytes,status_perfVerifyCycles,status_perfMemoryStallCycles;
  integer errors,k,layer,slot,seen;longint unsigned beats,reference_beats;integer tags[0:6];
  AxiLiteCtrl dut(.*);

  task wr(input[31:0]a,input[31:0]d);begin
    @(negedge clk);io_ctrl_aw_payload_addr=a;io_ctrl_aw_valid=1;io_ctrl_w_payload_data=d;io_ctrl_w_payload_strb=15;io_ctrl_w_valid=1;
    while(!(io_ctrl_aw_ready&&io_ctrl_w_ready))@(negedge clk);@(negedge clk);io_ctrl_aw_valid=0;io_ctrl_w_valid=0;
    while(!io_ctrl_b_valid)@(negedge clk);@(negedge clk);
  end endtask

  task run_case(input integer kval);integer exp_tag,exp_rows,exp_beats;begin
    wr(32'h104,kval);wr(32'h100,1);seen=0;beats=0;
    for(layer=0;layer<32;layer=layer+1)begin
      for(slot=0;slot<7;slot=slot+1)begin
        while(!io_speculativeBatch_valid)@(negedge clk);
        exp_tag=tags[slot];exp_rows=(slot==4||slot==5)?11008:4096;
        exp_beats=(slot==6)?86:32;
        if(io_speculativeBatch_payload_k!=kval||!io_speculativeBatch_payload_mode||
          io_speculativeBatch_payload_projectionTag!=exp_tag||io_speculativeBatch_payload_layerId!=layer||
          io_speculativeBatch_payload_rows!=exp_rows||io_speculativeBatch_payload_beatsPerRow!=exp_beats)errors=errors+1;
        beats=beats+exp_rows*exp_beats;seen=seen+1;
        @(negedge clk);io_speculativeBatch_ready=1;@(posedge clk);@(negedge clk);io_speculativeBatch_ready=0;
        status_projectionDoneTag=exp_tag;status_projectionDoneLayer=layer;status_projectionDone=1;
        @(negedge clk);status_projectionDone=0;
        if(slot==2)begin @(negedge clk);status_attentionDone=1;@(negedge clk);status_attentionDone=0;end
        if(slot==5)begin @(negedge clk);status_mlpActivationDone=1;@(negedge clk);status_mlpActivationDone=0;end
      end
    end
    while(!io_speculativeBatch_valid)@(negedge clk);
    if(io_speculativeBatch_payload_projectionTag!=19||io_speculativeBatch_payload_layerId!=31||
      io_speculativeBatch_payload_rows!=32000||io_speculativeBatch_payload_beatsPerRow!=32)errors=errors+1;
    beats=beats+32000*32;seen=seen+1;
    @(negedge clk);io_speculativeBatch_ready=1;@(posedge clk);@(negedge clk);io_speculativeBatch_ready=0;
    status_projectionDoneTag=19;status_projectionDoneLayer=31;status_projectionDone=1;
    @(negedge clk);status_projectionDone=0;repeat(3)@(posedge clk);
    if(seen!=225)errors=errors+1;
    if(k==1)reference_beats=beats;else if(beats!=reference_beats)errors=errors+1;
    $display("P3E_AUTO_SEQUENCE_K%0d projections=%0d weight_beats=%0d",kval,seen,beats);
    wr(32'h124,1);wr(32'h154,1);
  end endtask

  initial begin
    errors=0;reference_beats=0;tags[0]=4;tags[1]=5;tags[2]=7;tags[3]=11;
    tags[4]=15;tags[5]=16;tags[6]=17;
    io_ctrl_aw_valid=0;io_ctrl_w_valid=0;io_ctrl_b_ready=1;io_ctrl_ar_valid=0;io_ctrl_r_ready=1;
    io_ctrl_aw_payload_prot=0;io_ctrl_ar_payload_prot=0;io_speculativeTokenIndex_ready=1;io_speculativeBatch_ready=0;
    status_tokenCnt=0;status_layerCnt=0;status_argMaxVld=0;status_argMaxIndex=0;status_prefill=0;
    status_projectionDone=0;status_projectionError=0;status_projectionDoneTag=0;status_projectionDoneLayer=0;
    status_attentionDone=0;status_mlpActivationDone=0;status_perfWeightBytes=0;status_perfKvReadBytes=0;
    status_perfKvWriteBytes=0;status_perfVerifyCycles=0;status_perfMemoryStallCycles=0;
    repeat(5)@(posedge clk);reset=0;wr(32'h28,1);wr(32'h108,100);
    for(k=1;k<=4;k=k+1)run_case(k);
    if(errors)$fatal(1,"P3-E automatic production sequence failed with %0d errors",errors);
    $display("P3E_AXILITE_AUTO_SEQUENCE_GO K1_TO_K4=1 PROJECTIONS=225 BARRIERS_REAL_INPUTS=1 WEIGHT_PASS_K_INDEPENDENT=1");
    $finish;
  end
endmodule
