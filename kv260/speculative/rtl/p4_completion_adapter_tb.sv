`timescale 1ns/1ps
module p4_completion_adapter_tb;
  logic clk=0,reset=1;always #1.667 clk=~clk;
  logic io_tileAccepted,io_tile_phase,io_tile_source,io_tile_buffer;
  logic[1:0]io_tile_query;logic[12:0]io_tile_startToken,io_tile_tokenCount;logic io_tile_last;
  logic io_qkScoreValid,io_softmaxAccepted;logic[1:0]io_softmaxQuery;
  logic io_softmaxOutputValid,io_softmaxOutputLast;
  logic io_vAxpyTileOut_valid,io_vAxpyTileOut_payload_last;
  logic[15:0]io_vAxpyTileOut_payload_tdata;logic[5:0]io_vAxpyTileOut_payload_tuser;
  wire io_tileDone_valid,io_tileDone_payload_phase,io_tileDone_payload_source,io_tileDone_payload_buffer;
  wire[1:0]io_tileDone_payload_query;wire[12:0]io_tileDone_payload_startToken;
  wire io_softmaxDone_valid;wire[1:0]io_softmaxDone_payload_query;wire io_error;wire[3:0]io_errorCode;
  integer errors,i;
  P4AttentionCompletionAdapter dut(.*);
  task clear_inputs;begin
    io_tileAccepted=0;io_tile_phase=0;io_tile_source=0;io_tile_buffer=0;io_tile_query=0;
    io_tile_startToken=0;io_tile_tokenCount=0;io_tile_last=0;io_qkScoreValid=0;
    io_softmaxAccepted=0;io_softmaxQuery=0;io_softmaxOutputValid=0;io_softmaxOutputLast=0;
    io_vAxpyTileOut_valid=0;io_vAxpyTileOut_payload_last=0;io_vAxpyTileOut_payload_tdata=0;io_vAxpyTileOut_payload_tuser=0;
  end endtask
  task accept_tile(input phase,input source,input buffer,input[1:0]query,input[12:0]start,input[12:0]count);begin
    @(negedge clk);io_tile_phase=phase;io_tile_source=source;io_tile_buffer=buffer;io_tile_query=query;
    io_tile_startToken=start;io_tile_tokenCount=count;io_tileAccepted=1;@(posedge clk);#1;io_tileAccepted=0;
  end endtask
  initial begin
    errors=0;clear_inputs();repeat(4)@(posedge clk);reset=0;
    accept_tile(0,0,1,2,64,3);
    for(i=0;i<3;i=i+1)begin
      @(negedge clk);io_qkScoreValid=1;#1;
      if((i<2&&io_tileDone_valid)||(i==2&&(!io_tileDone_valid||io_tileDone_payload_phase||
         io_tileDone_payload_source||!io_tileDone_payload_buffer||io_tileDone_payload_query!=2||
         io_tileDone_payload_startToken!=64)))errors++;
      @(posedge clk);#1;io_qkScoreValid=0;
    end
    @(negedge clk);io_softmaxQuery=2;io_softmaxAccepted=1;@(posedge clk);#1;io_softmaxAccepted=0;
    io_softmaxOutputValid=1;io_softmaxOutputLast=0;#1;if(io_softmaxDone_valid)errors++;@(posedge clk);#1;
    io_softmaxOutputLast=1;#1;if(!io_softmaxDone_valid||io_softmaxDone_payload_query!=2)errors++;@(posedge clk);#1;
    io_softmaxOutputValid=0;io_softmaxOutputLast=0;
    accept_tile(1,1,0,2,0,3);
    for(i=0;i<3;i=i+1)begin
      @(negedge clk);io_vAxpyTileOut_valid=1;io_vAxpyTileOut_payload_last=(i==2);#1;
      if((i<2&&io_tileDone_valid)||(i==2&&(!io_tileDone_valid||!io_tileDone_payload_phase||
         !io_tileDone_payload_source||io_tileDone_payload_buffer||io_tileDone_payload_query!=2||
         io_tileDone_payload_startToken!=0)))errors++;
      @(posedge clk);#1;io_vAxpyTileOut_valid=0;io_vAxpyTileOut_payload_last=0;
    end
    reset=1;repeat(2)@(posedge clk);reset=0;clear_inputs();accept_tile(1,0,1,1,128,3);
    @(negedge clk);io_vAxpyTileOut_valid=1;io_vAxpyTileOut_payload_last=1;@(posedge clk);#1;
    io_vAxpyTileOut_valid=0;io_vAxpyTileOut_payload_last=0;if(!io_error||io_errorCode!=3)errors++;
    reset=1;repeat(2)@(posedge clk);reset=0;clear_inputs();
    @(negedge clk);io_softmaxOutputValid=1;io_softmaxOutputLast=1;@(posedge clk);#1;
    if(!io_error||io_errorCode!=5)errors++;
    if(errors)$fatal(1,"P4 completion adapter failed errors=%0d",errors);
    $display("P4_COMPLETION_ADAPTER_GO QK_COUNTED=1 SOFTMAX_LAST=1 V_LAST=1 METADATA=PHASE,SOURCE,BUFFER,QUERY,START EARLY_V_FAULT=1 UNSOLICITED_SOFTMAX_FAULT=1");$finish;
  end
endmodule
