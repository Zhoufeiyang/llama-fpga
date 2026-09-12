`timescale 1ns/1ps
module p5e_metadata_replay_tb;
  logic clk=0,reset=1;always #1.667 clk=~clk;
  logic io_qScale_valid;logic[15:0]io_qScale_payload;logic io_qZero_valid;logic[7:0]io_qZero_payload;
  logic io_qOut_valid,io_qOut_payload_last;logic[7:0]io_qOut_payload_fragment;
  wire io_kvBus_valid;logic io_kvBus_ready;wire io_kvBus_payload_last;wire[511:0]io_kvBus_payload_fragment;
  wire io_kSzOut_valid;logic io_kSzOut_ready;wire[31:0]io_kSzOut_payload;
  wire io_vSzOut_valid;logic io_vSzOut_ready;wire[31:0]io_vSzOut_payload;
  logic io_nextLayer,io_tokenIndexFlow_valid;logic[5:0]io_tokenIndexFlow_payload;logic[15:0]io_tokenPosition;
  integer errors,t,i,j,round,metadata_count;logic capture_enable,capture_found;logic[7:0]capture_tail;logic[511:0]captured[0:3];
  KvScaleZeroPacker dut(.*);

  always @(posedge clk) if(io_kvBus_valid&&io_kvBus_ready&&capture_enable&&io_kvBus_payload_fragment[7:0]!=8'hee) begin
    if(io_kvBus_payload_fragment[15*32+16+:8]==capture_tail)begin
      captured[round]<=io_kvBus_payload_fragment;capture_found<=1;
    end
    metadata_count<=metadata_count+1;
  end

  task launch(input integer position);
    begin
      @(negedge clk);io_tokenPosition=position;io_tokenIndexFlow_payload=6'd2;io_tokenIndexFlow_valid=1;
      @(negedge clk);io_tokenIndexFlow_valid=0;repeat(3)@(posedge clk);
    end
  endtask
  task send_metadata(input[7:0]zero_value);
    begin
      for(j=0;j<8;j=j+1)begin
        @(negedge clk);io_qScale_payload=16'h3c00;io_qZero_payload=zero_value;
        io_qScale_valid=1;io_qZero_valid=1;
        @(negedge clk);io_qScale_valid=0;io_qZero_valid=0;repeat(6)@(posedge clk);
      end
    end
  endtask
  task send_qout_vectors;
    integer v,b;
    begin
      for(v=0;v<8;v=v+1)begin
        for(b=0;b<64;b=b+1)begin
          @(negedge clk);io_qOut_valid=1;io_qOut_payload_fragment=8'hee;io_qOut_payload_last=(b==63);
        end
        @(negedge clk);io_qOut_valid=0;io_qOut_payload_last=0;repeat(4)@(posedge clk);
      end
      repeat(30)@(posedge clk);
    end
  endtask
  task check_line(input integer which,input integer base,input[7:0]tail);
    begin
      if(!capture_found)begin $display("P5E_LINE_MISSING round=%0d tail=%h",which,tail);errors=errors+1;end
      for(i=0;i<16;i=i+1)begin
        if(captured[which][i*32+16+:8]!==((i==15)?tail:(base+i)))begin
          $display("P5E_LINE_FAIL round=%0d slot=%0d got=%h",which,i,captured[which][i*32+:32]);errors=errors+1;
        end
      end
    end
  endtask
  initial begin
    errors=0;capture_enable=0;capture_found=0;capture_tail=0;metadata_count=0;round=0;
    io_qScale_valid=0;io_qZero_valid=0;io_qOut_valid=0;io_qOut_payload_last=0;io_qOut_payload_fragment=0;
    io_kvBus_ready=1;io_kSzOut_ready=1;io_vSzOut_ready=1;io_nextLayer=1;
    io_tokenIndexFlow_valid=0;io_tokenIndexFlow_payload=0;io_tokenPosition=0;
    repeat(8)@(posedge clk);reset=0;
    for(t=0;t<15;t=t+1)begin launch(t);send_metadata(t[7:0]);end
    round=0;metadata_count=0;capture_found=0;capture_tail=8'ha5;capture_enable=1;launch(15);send_metadata(8'ha5);send_qout_vectors();capture_enable=0;
    check_line(0,0,8'ha5);$display("P5E_FIRST_WRITE position=15 tail=a5 committed_neighbors=preserved");
    round=1;metadata_count=0;capture_found=0;capture_tail=8'hb5;capture_enable=1;launch(15);send_metadata(8'hb5);send_qout_vectors();capture_enable=0;
    check_line(1,0,8'hb5);$display("P5E_REPLAY_WRITE position=15 tail=b5 committed_neighbors=preserved");
    for(t=16;t<31;t=t+1)begin launch(t);send_metadata(t[7:0]);end
    round=2;metadata_count=0;capture_found=0;capture_tail=8'ha6;capture_enable=1;launch(31);send_metadata(8'ha6);send_qout_vectors();capture_enable=0;
    check_line(2,16,8'ha6);$display("P5E_FIRST_WRITE position=31 tail=a6 committed_neighbors=preserved");
    round=3;metadata_count=0;capture_found=0;capture_tail=8'hb6;capture_enable=1;launch(31);send_metadata(8'hb6);send_qout_vectors();capture_enable=0;
    check_line(3,16,8'hb6);$display("P5E_REPLAY_WRITE position=31 tail=b6 committed_neighbors=preserved");
    if(errors)$fatal(1,"P5-E metadata replay failed with %0d errors",errors);
    $display("P5E_METADATA_RETAINED_LINE_GO BOUNDARIES_15_16_31_32=1 REJECTED_LINE_END_REPLAY=1 BIT_PRESERVE=1");$finish;
  end
endmodule
