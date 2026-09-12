`timescale 1ns/1ps
module p7g_perf_counters_tb;
  logic clk=0,reset=1;always #1.667 clk=~clk;
  logic io_clear,io_active,io_weightBeat,io_kvReadBeat,io_kvWriteBeat,io_memoryStall,io_resultStall;
  wire[63:0]io_weightBytes,io_kvReadBytes,io_kvWriteBytes,io_verifyCycles,io_memoryStallCycles,io_resultStallCycles;
  integer errors;
  SpeculativePerfCounters dut(.*);
  initial begin
    errors=0;io_clear=0;io_active=0;io_weightBeat=0;io_kvReadBeat=0;io_kvWriteBeat=0;io_memoryStall=0;io_resultStall=0;
    repeat(4)@(posedge clk);reset=0;io_clear=1;@(posedge clk);io_clear=0;io_active=1;
    repeat(10)begin
      @(negedge clk);io_weightBeat=1;io_kvReadBeat=1;io_kvWriteBeat=0;io_memoryStall=0;io_resultStall=0;
      @(posedge clk);
    end
    @(negedge clk);io_weightBeat=0;io_kvReadBeat=0;io_kvWriteBeat=1;io_memoryStall=1;io_resultStall=1;
    repeat(2)@(posedge clk);@(negedge clk);io_active=0;io_kvWriteBeat=0;io_memoryStall=0;io_resultStall=0;
    repeat(2)@(posedge clk);
    if(io_weightBytes!=640||io_kvReadBytes!=640||io_kvWriteBytes!=128||io_verifyCycles!=12||io_memoryStallCycles!=2||io_resultStallCycles!=2)errors++;
    io_clear=1;@(posedge clk);io_clear=0;@(posedge clk);
    if(io_weightBytes||io_kvReadBytes||io_kvWriteBytes||io_verifyCycles||io_memoryStallCycles||io_resultStallCycles)errors++;
    if(errors)$fatal(1,"P7-G counter failure errors=%0d",errors);
    $display("P7G_PERF_COUNTERS_GO WEIGHT_BYTES=640 KV_READ_BYTES=640 KV_WRITE_BYTES=128 VERIFY_CYCLES=12 MEMORY_STALLS=2 RESULT_STALLS=2 PASSIVE=1");$finish;
  end
endmodule
