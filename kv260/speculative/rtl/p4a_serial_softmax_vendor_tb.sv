`timescale 1ns/1ps
module p4a_serial_softmax_vendor_tb;
  logic clk=0,reset=1,input_tvalid;logic[15:0]input_tdata;logic[9:0]seqLen;
  wire output_tvalid,output_tlast;wire[15:0]output_tdata;
  integer q,i,got,errors,timeout;logic[15:0] expected;
  always #1.667 clk=~clk;
  SerialSafeSoftmaxTest dut(.*);

  function automatic [15:0] uniform_fp16(input integer count);
    case(count)
      1: uniform_fp16=16'h3c00;
      2: uniform_fp16=16'h3800;
      3: uniform_fp16=16'h3555;
      4: uniform_fp16=16'h3400;
      default: uniform_fp16=16'h0000;
    endcase
  endfunction

  task run_query(input integer query);
    integer count;
    begin
      count=query+1;seqLen=count-1;expected=uniform_fp16(count);got=0;
      for(i=0;i<count;i=i+1) begin
        @(negedge clk);input_tdata=16'h0000;input_tvalid=1;
        @(negedge clk);input_tvalid=0;
      end
      timeout=0;
      while(got<count&&timeout<1000) begin
        @(posedge clk);timeout=timeout+1;
        if(output_tvalid) begin
          if(output_tdata[14:10]==5'h1f) begin
            $display("NONFINITE q=%0d index=%0d data=%h",query,got,output_tdata);errors=errors+1;
          end
          if(output_tdata!==expected) begin
            $display("VALUE q=%0d index=%0d got=%h expected=%h",query,got,output_tdata,expected);errors=errors+1;
          end
          if(output_tlast!==(got==count-1)) begin
            $display("LAST q=%0d index=%0d",query,got);errors=errors+1;
          end
          got=got+1;
        end
      end
      if(got!=count) begin $display("TIMEOUT q=%0d got=%0d",query,got);errors=errors+1;end
      $display("P4A_SOFTMAX_Q%0d visible=%0d probability=%h",query,count,expected);
      repeat(8)@(posedge clk);
    end
  endtask

  initial begin
    input_tvalid=0;input_tdata=0;seqLen=0;errors=0;
    repeat(8)@(posedge clk);reset=0;
    for(q=0;q<4;q=q+1)run_query(q);
    if(errors)$fatal(1,"P4-A vendor softmax failed with %0d errors",errors);
    $display("P4A_VENDOR_SOFTMAX_GO K1_TO_K4=1 FINITE=1 NORMALIZED=1");$finish;
  end
endmodule
