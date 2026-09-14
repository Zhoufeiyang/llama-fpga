`timescale 1ns/1ps
module p5h_split_status_join_tb;
  logic[3:0] valid;
  wire[3:0] ready;
  logic[7:0] payload[0:3];
  wire logical_valid;logic logical_ready;wire[7:0]logical_payload;
  SplitDmaStatusJoin dut(
    .io_physical_0_valid(valid[0]),.io_physical_0_ready(ready[0]),.io_physical_0_payload(payload[0]),
    .io_physical_1_valid(valid[1]),.io_physical_1_ready(ready[1]),.io_physical_1_payload(payload[1]),
    .io_physical_2_valid(valid[2]),.io_physical_2_ready(ready[2]),.io_physical_2_payload(payload[2]),
    .io_physical_3_valid(valid[3]),.io_physical_3_ready(ready[3]),.io_physical_3_payload(payload[3]),
    .io_logical_valid(logical_valid),.io_logical_ready(logical_ready),.io_logical_payload(logical_payload));
  initial begin
    valid=0;logical_ready=0;payload[0]=8'h00;payload[1]=8'h02;payload[2]=8'h00;payload[3]=8'h08;
    #10;valid=4'b0111;#10;
    if(logical_valid||ready!=0)$fatal(1,"retired before all four lanes");
    valid=4'b1111;#10;
    if(!logical_valid||ready!=0||logical_payload!=8'h0a)$fatal(1,"join/payload failed under backpressure");
    logical_ready=1;#1;
    if(ready!=4'hf)$fatal(1,"physical statuses not retired atomically");
    #9;valid=0;
    $display("P5H_SPLIT_STATUS_JOIN_GO LANES=4 ALL_REQUIRED=1 BACKPRESSURE=1 ERROR_OR=1 ATOMIC_RETIRE=1");
    $finish;
  end
endmodule
