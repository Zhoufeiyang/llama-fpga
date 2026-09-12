`timescale 1ns/1ps
module p6_greedy_acceptance_tb;
  logic clk=0,reset=1;always #1.667 clk=~clk;
  logic start_valid,start_ready;logic[2:0]start_k;logic[63:0]start_candidates;
  logic target_valid,target_ready;logic[2:0]target_index;logic[15:0]target_token;
  logic abort,timeout,result_valid,result_ready;logic[2:0]accepted_count;logic[15:0]emitted_token;
  logic bonus_token;logic[2:0]mismatch_index;logic commit_valid;logic[2:0]commit_delta;
  logic rollback_required,aborted,busy,error;logic[3:0]error_code;
  integer errors,k,j,i;logic[15:0]held_token;logic[2:0]held_accept;
  p6_greedy_acceptance dut(.*);

  task start_case(input integer kval);
    begin
      @(negedge clk);start_k=kval;start_candidates={16'd13,16'd12,16'd11,16'd10};start_valid=1;
      @(posedge clk);@(negedge clk);start_valid=0;
    end
  endtask
  task send_targets(input integer kval,input integer mismatch);
    begin
      for(i=0;i<=kval;i=i+1)begin
        @(negedge clk);target_index=i;target_token=(i<kval)?(16'd10+i):16'd99;
        if(mismatch<kval&&i==mismatch)target_token=16'd200+i;
        target_valid=1;@(posedge clk);@(negedge clk);target_valid=0;
      end
    end
  endtask
  task check_case(input integer kval,input integer mismatch);
    integer expected_accept;logic[15:0]expected_token;
    begin
      expected_accept=(mismatch<kval)?mismatch:kval;
      expected_token=(mismatch<kval)?(200+mismatch):99;
      repeat(2)@(posedge clk);
      if(!result_valid||accepted_count!=expected_accept||commit_delta!=expected_accept||emitted_token!=expected_token)errors=errors+1;
      if(bonus_token!==(mismatch>=kval)||rollback_required!==(mismatch<kval)||!commit_valid)errors=errors+1;
      held_token=emitted_token;held_accept=accepted_count;repeat(4)@(posedge clk);
      if(!result_valid||emitted_token!=held_token||accepted_count!=held_accept||start_ready)errors=errors+1;
      @(negedge clk);result_ready=1;@(posedge clk);@(negedge clk);result_ready=0;@(posedge clk);
    end
  endtask
  initial begin
    errors=0;start_valid=0;target_valid=0;result_ready=0;abort=0;timeout=0;
    repeat(5)@(posedge clk);reset=0;
    for(k=1;k<=4;k=k+1)begin
      for(j=0;j<=k;j=j+1)begin start_case(k);send_targets(k,j);check_case(k,j);end
      $display("P6_K%0d mismatch_0_to_%0d_and_all_match=passed",k,k-1);
    end
    start_case(4);@(negedge clk);timeout=1;@(posedge clk);@(negedge clk);timeout=0;repeat(2)@(posedge clk);
    if(!result_valid||!aborted||!rollback_required||commit_valid||accepted_count!=0)errors=errors+1;
    @(negedge clk);result_ready=1;@(posedge clk);@(negedge clk);result_ready=0;@(posedge clk);
    start_case(2);@(negedge clk);target_index=1;target_token=1;target_valid=1;@(posedge clk);@(negedge clk);target_valid=0;
    if(!error||error_code!=2)errors=errors+1;
    @(negedge clk);abort=1;@(posedge clk);@(negedge clk);abort=0;repeat(2)@(posedge clk);
    if(errors)$fatal(1,"P6 greedy acceptance failed with %0d errors",errors);
    $display("P6_GREEDY_ACCEPTANCE_GO K1_TO_K4=1 ALL_MISMATCHES=1 BONUS=1 BACKPRESSURE=1 TIMEOUT=1");$finish;
  end
endmodule
