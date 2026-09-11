`timescale 1ns/1ps
module p4_attention_phase_controller_tb;
  logic clk=0,reset=1,start_valid;logic start_ready;logic[2:0]start_k;logic[15:0]start_committed_tokens;
  logic tile_valid,tile_ready,tile_phase,tile_source,tile_buffer;logic[2:0]tile_query;
  logic[15:0]tile_start_token,tile_token_count;logic tile_last,tile_done,tile_done_phase,tile_done_source;
  logic[2:0]tile_done_query;logic[15:0]tile_done_start_token;logic softmax_valid,softmax_ready;
  logic[2:0]softmax_query;logic[15:0]softmax_token_count;logic softmax_done;logic[2:0]softmax_done_query;
  logic query_done;logic[2:0]query_done_index;logic busy,done,error;logic[3:0]error_code;
  integer tiles,softmaxes,queries,qk_tiles,v_tiles;
  always #5 clk=~clk;
  p4_attention_phase_controller dut(.*);
  always @(posedge clk) begin
    tile_done<=0;softmax_done<=0;
    if(tile_valid&&tile_ready) begin
      tile_done<=1;tile_done_phase<=tile_phase;tile_done_source<=tile_source;
      tile_done_query<=tile_query;tile_done_start_token<=tile_start_token;
      tiles<=tiles+1;if(tile_phase) v_tiles<=v_tiles+1;else qk_tiles<=qk_tiles+1;
      if(tile_source && tile_token_count!=tile_query+1) $fatal(1,"causal tentative count");
    end
    if(softmax_valid&&softmax_ready) begin
      softmax_done<=1;softmax_done_query<=softmax_query;softmaxes<=softmaxes+1;
      if(softmax_token_count!=16'd131+softmax_query) $fatal(1,"softmax visible length");
    end
    if(query_done) queries<=queries+1;
  end
  task run_case(input integer kval);
    begin
      tiles=0;softmaxes=0;queries=0;qk_tiles=0;v_tiles=0;
      @(posedge clk);start_k=kval;start_committed_tokens=130;start_valid=1;
      @(posedge clk);start_valid=0;
      wait(done||error);if(error)$fatal(1,"unexpected error %0d",error_code);
      if(qk_tiles!=4*kval||v_tiles!=4*kval||softmaxes!=kval||queries!=kval)$fatal(1,"count mismatch K=%0d",kval);
      $display("P4B_K%0d qk_tiles=%0d softmax=%0d v_tiles=%0d",kval,qk_tiles,softmaxes,v_tiles);
      @(posedge clk);
    end
  endtask
  initial begin
    start_valid=0;tile_ready=1;softmax_ready=1;tile_done=0;softmax_done=0;
    repeat(3)@(posedge clk);reset=0;
    run_case(1);run_case(2);run_case(3);run_case(4);
    @(negedge clk);tile_done=1;
    @(posedge clk);#1;tile_done=0;
    if(!error||error_code!=5)$fatal(1,"unsolicited completion not rejected");
    $display("P4B_ATTENTION_PHASE_CONTROLLER_GO K1_TO_K4=1");$finish;
  end
endmodule
