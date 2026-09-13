`timescale 1ns/1ps
module p5c_position_tb;
  logic[9:0]io_legacyPosition;logic[10:0]io_committedPosition;logic io_speculativeEnable;logic[1:0]io_candidatePosition;
  wire[9:0]io_selectedPosition;wire io_overflow;integer errors,q;
  SpeculativeKvPosition dut(.*);
  initial begin
    errors=0;io_legacyPosition=77;io_committedPosition=100;io_speculativeEnable=0;io_candidatePosition=3;#1;
    if(io_selectedPosition!=77||io_overflow)errors=errors+1;
    io_speculativeEnable=1;
    for(q=0;q<4;q=q+1)begin io_candidatePosition=q;#1;
      if(io_selectedPosition!=100+q||io_overflow)errors=errors+1;
      $display("P5C_Q%0d physical_kv_slot=%0d",q,io_selectedPosition);
    end
    io_committedPosition=1020;io_candidatePosition=3;#1;
    if(io_selectedPosition!=1023||io_overflow)errors=errors+1;
    io_committedPosition=1021;io_candidatePosition=3;#1;
    if(!io_overflow)errors=errors+1;
    if(errors)$fatal(1,"P5-C position selector failed with %0d errors",errors);
    $display("P5C_POSITION_SELECTOR_GO LEGACY=1 K1_TO_K4=1 LAST_SLOT_1023=1 OVERFLOW=1");$finish;
  end
endmodule
