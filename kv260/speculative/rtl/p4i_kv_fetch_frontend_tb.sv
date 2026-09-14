`timescale 1ns/1ps
module p4i_kv_fetch_frontend_tb;
  logic clk=0, reset=1, req_valid, req_ready;
  logic req_phase, req_source, req_fetch, req_buffer, req_last;
  logic [1:0] req_query; logic [10:0] req_start, req_count;
  logic [31:0] metadata_base=32'h90000000, value_base=32'ha0000000;
  logic [5:0] value_tag=6'd6;
  logic cmd_valid, cmd_ready=1; logic [71:0] cmd;
  logic ddr_valid, ddr_ready, ddr_last; logic [511:0] ddr_data; logic [63:0] ddr_keep='1;
  logic metadata_valid, metadata_ready; logic [31:0] metadata;
  logic value_valid, value_ready, value_last; logic [511:0] value_data; logic [5:0] value_dest;
  logic done_valid, done_phase, done_source, done_buffer; logic [1:0] done_query; logic [10:0] done_start;
  logic busy,error; logic [3:0] error_code;
  integer cmds=0, metas=0, values=0, dones=0, cycles=0; logic [71:0] command[0:3];
  logic [511:0] expected_value[0:1];
  always #5 clk=~clk;
  P4KvFetchFrontend dut(
    .io_request_valid(req_valid),.io_request_ready(req_ready),.io_request_payload_phase(req_phase),
    .io_request_payload_source(req_source),.io_request_payload_fetch(req_fetch),.io_request_payload_buffer(req_buffer),
    .io_request_payload_query(req_query),.io_request_payload_startToken(req_start),.io_request_payload_tokenCount(req_count),
    .io_request_payload_last(req_last),.io_metadataBaseAddress(metadata_base),.io_valueBaseAddress(value_base),
    .io_valueOutputTag(value_tag),.io_ddrCmd_valid(cmd_valid),.io_ddrCmd_ready(cmd_ready),.io_ddrCmd_payload(cmd),
    .io_ddrData_valid(ddr_valid),.io_ddrData_ready(ddr_ready),.io_ddrData_payload_data(ddr_data),
    .io_ddrData_payload_keep(ddr_keep),.io_ddrData_payload_last(ddr_last),
    .io_metadata_valid(metadata_valid),.io_metadata_ready(metadata_ready),.io_metadata_payload(metadata),
    .io_value_valid(value_valid),.io_value_ready(value_ready),.io_value_payload_data(value_data),
    .io_value_payload_last(value_last),.io_value_payload_dest(value_dest),.io_done_valid(done_valid),
    .io_done_payload_phase(done_phase),.io_done_payload_source(done_source),.io_done_payload_buffer(done_buffer),
    .io_done_payload_query(done_query),.io_done_payload_startToken(done_start),.io_busy(busy),.io_error(error),
    .io_errorCode(error_code),.clk(clk),.reset(reset));
  always @(posedge clk) begin
    cycles<=cycles+1;
    if(!reset) begin metadata_ready<=cycles%3!=0; value_ready<=cycles%4!=1; end
    if(cmd_valid&&cmd_ready) begin command[cmds]<=cmd; cmds<=cmds+1; end
    if(metadata_valid&&metadata_ready) begin
      if(metadata!==32'hbb000082) $fatal(1,"metadata mismatch %h",metadata); metas<=metas+1; end
    if(value_valid&&value_ready) begin
      if(value_data!==expected_value[values]) $fatal(1,"value mismatch %0d",values);
      if(value_last!==(values%2==1)||value_dest!==value_tag) $fatal(1,"value framing mismatch"); values<=values+1; end
    if(done_valid) begin
      if(done_query!==req_query||done_start!==req_start||done_buffer!==req_buffer) $fatal(1,"done identity mismatch"); dones<=dones+1; end
  end
  task launch(input logic fetch_i,input logic[1:0] query_i); begin
    wait(req_ready); @(negedge clk); req_fetch=fetch_i;req_query=query_i;req_valid=1; @(negedge clk);req_valid=0; end endtask
  task send_beat(input logic[511:0] data_i,input logic last_i); begin
    wait(ddr_ready); @(negedge clk);ddr_data=data_i;ddr_last=last_i;ddr_valid=1;
    do @(posedge clk); while(!ddr_ready); @(negedge clk);ddr_valid=0; end endtask
  task wait_done(input integer n); integer t; begin t=0;while(dones<n&&t<200)begin @(negedge clk);t=t+1;end if(dones<n)$fatal(1,"timeout");end endtask
  initial begin integer i;
    fork begin #20000;$fatal(1,"global timeout");end join_none
    req_valid=0;req_phase=0;req_source=0;req_fetch=0;req_buffer=0;req_last=1;req_query=0;req_start=130;req_count=1;
    ddr_valid=0;ddr_data=0;ddr_last=0;metadata_ready=1;value_ready=1;
    expected_value[0]=512'h1234;expected_value[1]=512'h5678;
    repeat(5)@(negedge clk);reset=0;
    launch(1,0);wait(cmds==1);
    if(command[0][67:64]!==4'd2||command[0][63:32]!==32'h90000200||command[0][22:0]!==64)$fatal(1,"metadata command %h",command[0]);
    ddr_data=0;for(i=0;i<16;i=i+1)ddr_data[i*32+:32]=32'hbb000000+128+i;send_beat(ddr_data,1);
    wait(cmds==2);
    if(command[1][67:64]!==4'd1||command[1][63:32]!==32'ha0004100||command[1][22:0]!==128)$fatal(1,"value command %h",command[1]);
    send_beat(expected_value[0],0);send_beat(expected_value[1],1);wait_done(1);
    if(metas!=1||values!=2||error)$fatal(1,"fetch failed m=%0d v=%0d e=%0d",metas,values,error);
    $display("P4I_FETCH_GO COMMAND_ORDER=TAG2_TAG1 METADATA=1 VALUE_BEATS=2");
    metas=0;values=0;launch(0,1);wait_done(2);
    if(cmds!=2||metas!=1||values!=2||error)$fatal(1,"replay failed");
    $display("P4I_KV_FETCH_FRONTEND_GO OWNER_SERIAL=1 REPLAY_NO_DDR=1 BACKPRESSURE=1 DONE_IDENTITY=1");$finish;
  end
endmodule
