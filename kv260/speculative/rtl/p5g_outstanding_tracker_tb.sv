`timescale 1ns/1ps
module p5g_outstanding_tracker_tb;
  logic clk=0,reset=1;
  logic io_active,io_issue,io_retire,io_clearError;
  logic[7:0]io_epoch;
  wire[7:0]io_outstanding;
  wire io_drained,io_error;
  always #5 clk=~clk;
  SpeculativeOutstandingTracker dut(.*);

  task pulse_issue;
    begin @(negedge clk);io_issue=1;@(negedge clk);io_issue=0; end
  endtask
  task pulse_retire;
    begin @(negedge clk);io_retire=1;@(negedge clk);io_retire=0; end
  endtask

  initial begin
    io_active=0;io_epoch=0;io_issue=0;io_retire=0;io_clearError=0;
    repeat(3)@(posedge clk);reset=0;
    io_active=1;io_epoch=8'h11;
    pulse_issue();pulse_issue();pulse_issue();
    if(io_outstanding!=3||io_drained||io_error)$fatal(1,"issue count failed");
    // Rollback does not erase irreversible DMA traffic.
    io_active=0; pulse_retire();
    if(io_outstanding!=2||io_drained||io_error)$fatal(1,"rollback drain failed");
    // A new epoch before the old one drains is rejected.
    io_active=1;io_epoch=8'h12;repeat(2)@(posedge clk);
    if(!io_error)$fatal(1,"epoch overlap not detected");
    io_active=0;pulse_retire();pulse_retire();
    if(io_outstanding!=0||!io_drained)$fatal(1,"final drain failed");
    @(negedge clk);io_clearError=1;@(negedge clk);io_clearError=0;
    if(io_error)$fatal(1,"error clear failed");
    io_active=1;io_epoch=8'h12;pulse_issue();pulse_retire();
    if(!io_drained||io_error)$fatal(1,"fresh epoch failed");
    $display("P5G_OUTSTANDING_TRACKER_GO ROLLBACK_DRAINS=1 EPOCH_OVERLAP_FAULT=1 NEW_EPOCH_AFTER_ZERO=1");
    $finish;
  end
endmodule
