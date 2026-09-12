`timescale 1ns/1ps
module p4a_qk_v_vendor_tb;
  logic clk=0,reset=1;always #1.667 clk=~clk;
  logic io_input_valid;logic[15:0]io_input_tdata;logic[5:0]io_input_tuser;
  wire io_output_valid;wire[15:0]io_output_tdata;wire[5:0]io_output_tuser;
  wire qProbe_valid;wire[15:0]qProbe_payload;
  logic input_valid,input_last;logic[15:0]probability,value;
  wire output_valid;wire[15:0]output_data;
  integer q,i,timeout,errors;
  QKMul qk(.*);
  p4a_v_weighted_accum v_accum(.*);

  task send_qk_vector(input [5:0] tag);
    begin
      for(i=0;i<128;i=i+1) begin
        @(negedge clk);io_input_valid=1;io_input_tuser=tag;
        io_input_tdata=(i==0)?16'h3c00:16'h0000;
      end
      @(negedge clk);io_input_valid=0;
    end
  endtask

  task run_qk(input integer query);
    begin
      send_qk_vector(6'd1);repeat(10)@(posedge clk);send_qk_vector(6'd2);
      timeout=0;while(!io_output_valid&&timeout<500)begin @(posedge clk);timeout=timeout+1;end
      if(!io_output_valid||io_output_tdata!==16'h3c00||io_output_tuser!==6'd3)begin
        $display("QK_FAIL q=%0d valid=%0d data=%h tag=%0d",query,io_output_valid,io_output_tdata,io_output_tuser);errors=errors+1;
      end else $display("P4A_QK_Q%0d score=%h",query,io_output_tdata);
    end
  endtask

  task run_v(input integer query);
    integer count;
    begin
      count=query+1;
      for(i=0;i<count;i=i+1)begin
        @(negedge clk);input_valid=1;input_last=(i==count-1);
        value=16'h3c00;
        case(count) 1:probability=16'h3c00;2:probability=16'h3800;3:probability=16'h3555;default:probability=16'h3400;endcase
      end
      @(negedge clk);input_valid=0;input_last=0;
      timeout=0;while(!output_valid&&timeout<500)begin @(posedge clk);timeout=timeout+1;end
      if(!output_valid||output_data[14:10]==5'h1f)begin
        $display("V_FAIL q=%0d valid=%0d data=%h",query,output_valid,output_data);errors=errors+1;
      end else if(query!=2&&output_data!==16'h3c00)begin
        $display("V_VALUE q=%0d got=%h expected=3c00",query,output_data);errors=errors+1;
      end else if(query==2&&(output_data<16'h3bfe||output_data>16'h3c01))begin
        $display("V_TOL q=2 got=%h",output_data);errors=errors+1;
      end else $display("P4A_V_Q%0d weighted_sum=%h",query,output_data);
      repeat(8)@(posedge clk);
    end
  endtask

  initial begin
    io_input_valid=0;io_input_tdata=0;io_input_tuser=0;
    input_valid=0;input_last=0;probability=0;value=0;errors=0;
    repeat(8)@(posedge clk);reset=0;
    for(q=0;q<4;q=q+1)begin run_qk(q);run_v(q);end
    if(errors)$fatal(1,"P4-A QK/V vendor test failed with %0d errors",errors);
    $display("P4A_VENDOR_QK_V_GO K1_TO_K4=1 FINITE=1");$finish;
  end
endmodule
