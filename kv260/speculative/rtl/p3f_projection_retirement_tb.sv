`timescale 1ns/1ps
module p3f_projection_retirement_tb;
  logic clk=0,reset=1;always #1.667 clk=~clk;
  logic io_launch_valid,io_launch_payload_mode;logic[2:0]io_launch_payload_k;
  logic[5:0]io_launch_payload_projectionTag;logic[7:0]io_launch_payload_layerId;
  logic[15:0]io_launch_payload_rows,io_launch_payload_beatsPerRow;
  logic io_scalarValid;logic[5:0]io_scalarTag;logic io_vectorValid;logic[5:0]io_vectorTag;
  wire io_done_valid;wire[5:0]io_done_payload_projectionTag;wire[7:0]io_done_payload_layerId;
  wire io_active,io_error;integer errors,k,n;
  SpeculativeProjectionRetirement dut(.*);
  task launch(input integer kval,input integer tag,input integer rows);begin
    @(negedge clk);io_launch_payload_mode=1;io_launch_payload_k=kval;io_launch_payload_projectionTag=tag;
    io_launch_payload_layerId=7;io_launch_payload_rows=rows;io_launch_payload_beatsPerRow=2;io_launch_valid=1;
    @(negedge clk);io_launch_valid=0;
  end endtask
  task scalar(input integer tag);begin @(negedge clk);io_scalarTag=tag;io_scalarValid=1;@(negedge clk);io_scalarValid=0;end endtask
  task vector(input integer tag);begin @(negedge clk);io_vectorTag=tag;io_vectorValid=1;@(negedge clk);io_vectorValid=0;end endtask
  initial begin
    errors=0;io_launch_valid=0;io_scalarValid=0;io_vectorValid=0;io_scalarTag=0;io_vectorTag=0;
    io_launch_payload_mode=0;io_launch_payload_k=1;io_launch_payload_projectionTag=0;io_launch_payload_layerId=0;
    io_launch_payload_rows=0;io_launch_payload_beatsPerRow=0;repeat(4)@(posedge clk);reset=0;
    for(k=1;k<=4;k=k+1)begin
      launch(k,4,3);scalar(6); // wrong output tag cannot retire Q
      if(!io_active||io_done_valid)errors=errors+1;
      for(n=0;n<3*k;n=n+1)begin
        @(negedge clk);io_scalarTag=5;io_scalarValid=1;#0.1;
        if(io_done_valid!==(n==3*k-1))errors=errors+1;
        @(posedge clk);@(negedge clk);io_scalarValid=0;
      end
      if(io_active||io_error||io_done_payload_projectionTag!=4||io_done_payload_layerId!=7)errors=errors+1;
      $display("P3F_SCALAR_RETIRE_K%0d outputs=%0d",k,3*k);
    end
    launch(4,17,8); // D uses 8/4 * K = 8 vector beats
    for(n=0;n<8;n=n+1)begin
      @(negedge clk);io_vectorTag=31;io_vectorValid=1;#0.1;
      if(io_done_valid!==(n==7))errors=errors+1;
      @(posedge clk);@(negedge clk);io_vectorValid=0;
    end
    if(io_active||io_error)errors=errors+1;
    if(errors)$fatal(1,"P3-F projection retirement failed with %0d errors",errors);
    $display("P3F_PROJECTION_RETIREMENT_GO SCALAR_K1_TO_K4=1 VECTOR_D=1 WRONG_TAG_IGNORED=1 ARITHMETIC_TERMINAL=1");$finish;
  end
endmodule
