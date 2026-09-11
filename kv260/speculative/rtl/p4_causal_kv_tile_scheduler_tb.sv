`timescale 1ns/1ps
module p4_causal_kv_tile_scheduler_tb;
  logic clk=0,reset=1; always #1.667 clk=~clk;
  logic start_valid,start_ready; logic [2:0] start_k; logic [15:0] start_committed_tokens;
  logic request_valid,request_ready,request_source,request_buffer; logic [2:0] request_query;
  logic [15:0] request_start_token,request_token_count; logic request_last_for_query;
  logic response_done,response_source; logic [2:0] response_query; logic [15:0] response_start_token;
  logic query_done; logic [2:0] query_done_index; logic busy,done,error; logic [3:0] error_code;
  logic [31:0] ddr_tile_requests,tentative_requests;
  integer errors,kcase,total,ddr,ten,qdone; logic expected_buffer;
  p4_causal_kv_tile_scheduler dut(.*);
  task run_case(input integer kval,input integer committed);
    begin
      reset=1;start_valid=0;request_ready=0;response_done=0;repeat(4)@(posedge clk);reset=0;
      @(negedge clk);start_k=kval;start_committed_tokens=committed;start_valid=1;
      @(posedge clk);@(negedge clk);start_valid=0;total=0;ddr=0;ten=0;qdone=0;expected_buffer=0;
      while(!done&&!error&&total<40) begin
        @(negedge clk);request_ready=~request_ready;response_done=0;
        if(request_valid&&request_ready) begin
          if(request_buffer!==expected_buffer)errors=errors+1;
          if(!request_source) begin
            if(request_start_token!=(ddr%3)*64 || request_token_count!=((ddr%3==2)?2:64))errors=errors+1;
            ddr=ddr+1;
          end else begin
            if(request_start_token!=0||request_token_count!=request_query+1||!request_last_for_query)errors=errors+1;
            ten=ten+1;qdone=qdone+1;
          end
          response_source=request_source;response_query=request_query;response_start_token=request_start_token;
          @(posedge clk);@(negedge clk);response_done=1;request_ready=0;total=total+1;expected_buffer=~expected_buffer;
        end
        @(posedge clk);
      end
      if(error||ten!=kval||qdone!=kval||ddr!=((committed==0)?0:3*kval)||ddr_tile_requests!=ddr||tentative_requests!=ten) begin
        $display("P4_FAIL K=%0d ddr=%0d ten=%0d qdone=%0d code=%0d",kval,ddr,ten,qdone,error_code);errors=errors+1;
      end else $display("P4_K%0d committed=%0d ddr_tiles=%0d tentative=%0d",kval,committed,ddr,ten);
    end
  endtask
  initial begin
    errors=0;for(kcase=1;kcase<=4;kcase=kcase+1)run_case(kcase,130);run_case(4,0);
    if(errors)$fatal(1,"P4-A failed with %0d errors",errors);
    $display("P4_CAUSAL_KV_TILE_SCHEDULER_GO K1_TO_K4=1");$finish;
  end
endmodule
