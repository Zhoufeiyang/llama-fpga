`timescale 1ns/1ps
module p5b_axilite_ctrl_tb;
  logic clk=0,reset=1;always #1.667 clk=~clk;
  logic io_ctrl_aw_valid;wire io_ctrl_aw_ready;logic[31:0]io_ctrl_aw_payload_addr;logic[2:0]io_ctrl_aw_payload_prot;
  logic io_ctrl_w_valid;wire io_ctrl_w_ready;logic[31:0]io_ctrl_w_payload_data;logic[3:0]io_ctrl_w_payload_strb;
  wire io_ctrl_b_valid;logic io_ctrl_b_ready;wire[1:0]io_ctrl_b_payload_resp;
  logic io_ctrl_ar_valid;wire io_ctrl_ar_ready;logic[31:0]io_ctrl_ar_payload_addr;logic[2:0]io_ctrl_ar_payload_prot;
  wire io_ctrl_r_valid;logic io_ctrl_r_ready;wire[31:0]io_ctrl_r_payload_data;wire[1:0]io_ctrl_r_payload_resp;
  wire io_tokenIndex_valid;wire[15:0]io_tokenIndex_tdata;wire[5:0]io_tokenIndex_tuser;wire[1:0]io_cmdSel;
  wire[4:0]io_presetLayer;wire[9:0]io_presetToken;wire io_speculativeEnable;wire[1:0]io_speculativeQuery;
  wire[9:0]io_speculativeCommitted;logic[15:0]status_tokenCnt;logic[7:0]status_layerCnt;
  logic status_argMaxVld;logic[15:0]status_argMaxIndex;logic status_prefill;wire resetOut;
  wire io_speculativeBatch_valid;logic io_speculativeBatch_ready;
  wire io_speculativeBatch_payload_mode;wire[2:0]io_speculativeBatch_payload_k;
  wire[5:0]io_speculativeBatch_payload_projectionTag;wire[7:0]io_speculativeBatch_payload_layerId;
  wire[15:0]io_speculativeBatch_payload_rows,io_speculativeBatch_payload_beatsPerRow;
  logic status_projectionDone,status_projectionError;
  integer errors;logic[31:0]rd;
  AxiLiteCtrl dut(.*);

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
  initial begin
    errors=0;io_ctrl_aw_valid=0;io_ctrl_w_valid=0;io_ctrl_b_ready=1;io_ctrl_ar_valid=0;io_ctrl_r_ready=1;
    io_ctrl_aw_payload_prot=0;io_ctrl_ar_payload_prot=0;status_tokenCnt=0;status_layerCnt=0;
    status_argMaxVld=0;status_argMaxIndex=0;status_prefill=0;
    io_speculativeBatch_ready=1;status_projectionDone=0;status_projectionError=0;
    repeat(5)@(posedge clk);reset=0;
    axil_write(32'h108,100);axil_write(32'h104,4);axil_write(32'h100,1);repeat(3)@(posedge clk);
    axil_read(32'h10c,rd);if(rd[9:0]!=100)errors=errors+1;
    axil_read(32'h130,rd);if(!rd[1]||rd[25:16]!=104||io_speculativeCommitted!=100)errors=errors+1;
    axil_write(32'h120,32'h00000102);repeat(3)@(posedge clk);
    axil_read(32'h108,rd);if(rd[9:0]!=102||io_speculativeCommitted!=102)errors=errors+1;
    axil_read(32'h130,rd);if(!rd[0]||rd[1]||rd[25:16]!=102)errors=errors+1;
    $display("P5B_COMMIT base=100 accepted=2 committed=%0d",io_speculativeCommitted);
    axil_write(32'h100,1);repeat(3)@(posedge clk);axil_write(32'h124,1);repeat(3)@(posedge clk);
    axil_read(32'h108,rd);if(rd[9:0]!=102)errors=errors+1;
    axil_read(32'h130,rd);if(!rd[0]||rd[1]||rd[25:16]!=102)errors=errors+1;
    $display("P5B_ROLLBACK committed=%0d",io_speculativeCommitted);
    axil_write(32'h104,0);axil_write(32'h100,1);repeat(3)@(posedge clk);axil_read(32'h130,rd);
    if(!rd[2]||rd[1])errors=errors+1;
    if(errors)$fatal(1,"P5-B AXI-Lite control failed with %0d errors",errors);
    $display("P5B_AXILITE_POINTER_CONTROL_GO REGISTERS=1 COMMIT=1 ROLLBACK=1 FAULT=1");$finish;
  end
endmodule
