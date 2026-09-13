`timescale 1ns/1ps
module p3c_batch_collector_tb;
  logic clk=0,reset=1; always #1.667 clk=~clk;
  logic io_active,io_commandFire; logic[2:0]io_k; logic[7:0]io_epoch;
  wire io_advance,io_error; wire[2:0]io_accepted;
  integer errors,k,n;
  SpeculativeBatchCommandCollector dut(.*);

  task fire_command(input integer expect_advance);
    begin
      @(negedge clk);io_commandFire=1;#0.1;
      if(io_advance!==expect_advance[0])errors=errors+1;
      @(posedge clk);@(negedge clk);io_commandFire=0;
    end
  endtask

  initial begin
    errors=0;io_active=0;io_commandFire=0;io_k=1;io_epoch=0;
    repeat(4)@(posedge clk);reset=0;
    for(k=1;k<=4;k=k+1)begin
      @(negedge clk);io_active=1;io_k=k;io_epoch=k;
      for(n=0;n<k;n=n+1)begin
        fire_command(n==k-1);
      end
      @(posedge clk);if(io_advance||io_accepted!=0||io_error)errors=errors+1;
      @(negedge clk);io_active=0;@(posedge clk);
      $display("P3C_BATCH_COLLECT_K%0d accepted=%0d",k,k);
    end
    // A new epoch discards an incomplete K=4 command group.
    @(negedge clk);io_active=1;io_k=4;io_epoch=8'h20;fire_command(0);fire_command(0);
    @(negedge clk);io_epoch=8'h21;fire_command(0);
    if(io_accepted!=1)errors=errors+1;
    @(negedge clk);io_active=0;@(posedge clk);#0.1;if(io_accepted!=0)errors=errors+1;
    // Invalid K is sticky for the epoch.
    @(negedge clk);io_active=1;io_k=0;io_epoch=8'h22;fire_command(0);
    if(!io_error||io_advance)errors=errors+1;
    if(errors)$fatal(1,"P3-C collector failed with %0d errors",errors);
    $display("P3C_BATCH_COMMAND_COLLECTOR_GO K1_TO_K4=1 ADVANCE_ON_ACCEPTED_KTH=1 EPOCH_ABORT=1 INVALID_K=1");
    $finish;
  end
endmodule
