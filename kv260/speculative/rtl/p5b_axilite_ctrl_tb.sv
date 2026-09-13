`timescale 1ns/1ps
module p5b_axilite_ctrl_tb;
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
  logic[63:0]status_perfWeightBytes,status_perfKvReadBytes,status_perfKvWriteBytes,status_perfVerifyCycles,status_perfMemoryStallCycles;
  integer errors,clearPulses,i,tokenSeen;logic[31:0]rd;
  AxiLiteCtrl dut(.*);
  always @(posedge clk) if(!reset&&io_perfWindowClear) clearPulses=clearPulses+1;

  task axil_write(input[31:0]addr,input[31:0]data);
    begin
      @(negedge clk);io_ctrl_aw_payload_addr=addr;io_ctrl_aw_valid=1;
      io_ctrl_w_payload_data=data;io_ctrl_w_payload_strb=4'hf;io_ctrl_w_valid=1;
      while(!(io_ctrl_aw_ready&&io_ctrl_w_ready))@(negedge clk);
      @(negedge clk);io_ctrl_aw_valid=0;io_ctrl_w_valid=0;
      while(!io_ctrl_b_valid)@(negedge clk);@(negedge clk);
    end
  endtask
  task axil_read(input[31:0]addr,output[31:0]data);
    begin
      @(negedge clk);io_ctrl_ar_payload_addr=addr;io_ctrl_ar_valid=1;
      while(!io_ctrl_ar_ready)@(negedge clk);@(negedge clk);io_ctrl_ar_valid=0;
      while(!io_ctrl_r_valid)@(negedge clk);data=io_ctrl_r_payload_data;@(negedge clk);
    end
  endtask
  task push_argmax(input[15:0]value);
    begin
      @(negedge clk);status_argMaxIndex=value;status_argMaxVld=1;
      @(negedge clk);status_argMaxVld=0;repeat(3)@(posedge clk);
    end
  endtask
  initial begin
    errors=0;clearPulses=0;io_speculativeTokenIndex_ready=1;io_ctrl_aw_valid=0;io_ctrl_w_valid=0;io_ctrl_b_ready=1;io_ctrl_ar_valid=0;io_ctrl_r_ready=1;
    io_ctrl_aw_payload_prot=0;io_ctrl_ar_payload_prot=0;status_tokenCnt=0;status_layerCnt=0;
    status_argMaxVld=0;status_argMaxIndex=0;status_prefill=0;
    io_speculativeBatch_ready=1;status_projectionDone=0;status_projectionError=0;
    status_perfWeightBytes=0;status_perfKvReadBytes=0;status_perfKvWriteBytes=0;status_perfVerifyCycles=0;status_perfMemoryStallCycles=0;
    repeat(5)@(posedge clk);reset=0;
    // Exercise the AXI-Lite candidate window through the integrated ingress.
    axil_write(32'h28,1);axil_write(32'h110,16'h1111);axil_write(32'h114,16'h2222);
    axil_write(32'h118,16'h3333);axil_write(32'h11c,16'h4444);
    io_speculativeTokenIndex_ready=0;
    axil_write(32'h108,100);axil_write(32'h104,4);axil_write(32'h100,1);repeat(3)@(posedge clk);
    io_speculativeTokenIndex_ready=1;tokenSeen=0;
    while(tokenSeen<4) begin
      @(posedge clk);
      if(io_speculativeTokenIndex_valid&&io_speculativeTokenIndex_ready) begin
        case(tokenSeen)
          0: if(io_speculativeTokenIndex_tdata!==16'h1111)errors=errors+1;
          1: if(io_speculativeTokenIndex_tdata!==16'h2222)errors=errors+1;
          2: if(io_speculativeTokenIndex_tdata!==16'h3333)errors=errors+1;
          3: if(io_speculativeTokenIndex_tdata!==16'h4444)errors=errors+1;
        endcase
        if(io_speculativeTokenIndex_tuser!==({tokenSeen[1:0],4'h2}))errors=errors+1;
        tokenSeen=tokenSeen+1;
      end
    end
    repeat(1)@(posedge clk);
    axil_read(32'h10c,rd);if(rd[10:0]!=100)errors=errors+1;
    axil_read(32'h130,rd);if(!rd[1]||rd[3]||rd[26:16]!=104||io_speculativeCommitted!=100||
      !io_speculativeActive||io_speculativeBase!=100||io_speculativeK!=4||io_speculativeEpoch!=1||!io_perfWindowActive)errors=errors+1;
    for(i=0;i<4;i=i+1)push_argmax(16'h5000+i);
    axil_read(32'h130,rd);if(!rd[3])errors=errors+1;
    axil_write(32'h120,32'h00000102);repeat(3)@(posedge clk);
    axil_read(32'h108,rd);if(rd[10:0]!=102||io_speculativeCommitted!=102)errors=errors+1;
    axil_read(32'h130,rd);if(!rd[0]||rd[1]||rd[26:16]!=102||io_perfWindowActive)errors=errors+1;
    $display("P5B_COMMIT base=100 accepted=2 committed=%0d",io_speculativeCommitted);
    axil_write(32'h154,1);repeat(2)@(posedge clk);
    axil_write(32'h100,1);repeat(3)@(posedge clk);axil_write(32'h124,1);repeat(3)@(posedge clk);
    axil_read(32'h108,rd);if(rd[10:0]!=102)errors=errors+1;
    axil_read(32'h130,rd);if(!rd[0]||rd[1]||rd[26:16]!=102||io_speculativeEpoch!=2)errors=errors+1;
    $display("P5B_ROLLBACK committed=%0d",io_speculativeCommitted);
    // The final four physical slots 1020..1023 are legal and produce the
    // architected committed length 1024 after an all-accepted resolution.
    axil_write(32'h154,1);axil_write(32'h108,1020);axil_write(32'h104,4);
    axil_write(32'h100,1);repeat(3)@(posedge clk);
    for(i=0;i<4;i=i+1)push_argmax(16'h6000+i);
    axil_write(32'h120,32'h00000104);repeat(3)@(posedge clk);
    axil_read(32'h108,rd);if(rd[10:0]!=1024||io_speculativeCommitted!=1024)errors=errors+1;
    axil_read(32'h130,rd);if(rd[26:16]!=1024||io_speculativeEpoch!=3)errors=errors+1;
    $display("P5B_LAST_CONTEXT committed=%0d",io_speculativeCommitted);
    axil_write(32'h154,1);
    axil_write(32'h104,0);axil_write(32'h100,1);repeat(3)@(posedge clk);axil_read(32'h130,rd);
    if(!rd[2]||rd[1]||clearPulses!=3)errors=errors+1;
    if(errors)$fatal(1,"P5-B AXI-Lite control failed with %0d errors",errors);
    $display("P5B_AXILITE_POINTER_CONTROL_GO REGISTERS=1 COMMIT_AFTER_RESULTS=1 ROLLBACK=1 LAST_CONTEXT_1024=1 TXN_EPOCH=1 FAULT=1 PERF_WINDOW=1 CLEAR_PULSES=%0d",clearPulses);$finish;
  end
endmodule
