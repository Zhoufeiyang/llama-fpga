`timescale 1ns/1ps

module p4h_kv_metadata_requester_tb;
  logic clk=0, reset=1;
  logic req_valid, req_ready, req_phase, req_source, req_fetch, req_buffer, req_last;
  logic [1:0] req_query;
  logic [10:0] req_start, req_count;
  logic [31:0] base;
  logic [3:0] command_tag;
  logic cmd_valid, cmd_ready;
  logic [71:0] cmd;
  logic ddr_valid, ddr_ready, ddr_last;
  logic [511:0] ddr_data;
  logic [63:0] ddr_keep;
  logic metadata_valid, metadata_ready;
  logic [31:0] metadata;
  logic done_valid, done_phase, done_source, done_buffer;
  logic [1:0] done_query;
  logic [10:0] done_start;
  logic busy, error;
  integer cmd_count, metadata_count, done_count, cycle_count;
  logic [71:0] captured_cmd [0:3];
  logic [31:0] expected [0:15];

  always #5 clk = ~clk;

  P4KvMetadataRequester dut(
    .io_request_valid(req_valid), .io_request_ready(req_ready),
    .io_request_payload_phase(req_phase), .io_request_payload_source(req_source),
    .io_request_payload_fetch(req_fetch), .io_request_payload_buffer(req_buffer),
    .io_request_payload_query(req_query), .io_request_payload_startToken(req_start),
    .io_request_payload_tokenCount(req_count), .io_request_payload_last(req_last),
    .io_baseAddress(base), .io_commandTag(command_tag),
    .io_ddrCmd_valid(cmd_valid), .io_ddrCmd_ready(cmd_ready), .io_ddrCmd_payload(cmd),
    .io_ddrData_valid(ddr_valid), .io_ddrData_ready(ddr_ready),
    .io_ddrData_payload_data(ddr_data), .io_ddrData_payload_keep(ddr_keep),
    .io_ddrData_payload_last(ddr_last),
    .io_metadata_valid(metadata_valid), .io_metadata_ready(metadata_ready),
    .io_metadata_payload(metadata), .io_done_valid(done_valid),
    .io_done_payload_phase(done_phase), .io_done_payload_source(done_source),
    .io_done_payload_buffer(done_buffer), .io_done_payload_query(done_query),
    .io_done_payload_startToken(done_start), .io_busy(busy), .io_error(error),
    .clk(clk), .reset(reset)
  );

  always @(posedge clk) begin
    cycle_count <= cycle_count + 1;
    if(!reset) metadata_ready <= ((cycle_count % 4) != 1);
    if(cmd_valid && cmd_ready) begin
      captured_cmd[cmd_count] <= cmd;
      cmd_count <= cmd_count + 1;
    end
    if(metadata_valid && metadata_ready) begin
      if(metadata !== expected[metadata_count])
        $fatal(1, "metadata mismatch item=%0d got=%h expected=%h", metadata_count, metadata, expected[metadata_count]);
      metadata_count <= metadata_count + 1;
    end
    if(done_valid) begin
      done_count <= done_count + 1;
      if(done_query !== req_query || done_start !== req_start || done_buffer !== req_buffer)
        $fatal(1, "completion identity mismatch");
    end
  end

  task automatic launch(input logic fetch_i, input logic buffer_i,
                        input logic [1:0] query_i, input logic [10:0] start_i,
                        input logic [10:0] count_i);
    begin
      wait(req_ready);
      @(negedge clk);
      req_fetch=fetch_i; req_buffer=buffer_i; req_query=query_i;
      req_start=start_i; req_count=count_i; req_valid=1;
      @(negedge clk);
      req_valid=0;
    end
  endtask

  task automatic feed_line(input integer first_token, input logic final_line);
    integer lane;
    begin
      wait(ddr_ready);
      @(negedge clk);
      for(lane=0; lane<16; lane=lane+1)
        ddr_data[lane*32 +: 32] = 32'hca000000 + first_token + lane;
      ddr_last=final_line; ddr_valid=1;
      do @(posedge clk); while(!ddr_ready);
      @(negedge clk);
      ddr_valid=0;
    end
  endtask

  task automatic await_done(input integer expected_done);
    integer timeout;
    begin
      timeout=0;
      while(done_count<expected_done && timeout<200) begin @(negedge clk); timeout=timeout+1; end
      if(done_count<expected_done) $fatal(1, "timeout waiting for completion %0d", expected_done);
    end
  endtask

  initial begin
    integer i;
    fork begin #20000; $fatal(1, "global timeout busy=%0d cmd=%0d metadata=%0d done=%0d", busy, cmd_count, metadata_count, done_count); end join_none
    req_valid=0; req_phase=1; req_source=1; req_fetch=0; req_buffer=0;
    req_query=0; req_start=0; req_count=0; req_last=0;
    base=32'h90000000; command_tag=4'd2; cmd_ready=1;
    ddr_valid=0; ddr_data=0; ddr_keep='1; ddr_last=0;
    metadata_ready=1; cmd_count=0; metadata_count=0; done_count=0; cycle_count=0;
    repeat(5) @(negedge clk); reset=0;

    for(i=0;i<3;i=i+1) expected[i]=32'hca000000 + 130 + i;
    launch(1, 0, 0, 130, 3);
    wait(cmd_count == 1);
    if(captured_cmd[0][67:64] !== command_tag || captured_cmd[0][63:32] !== 32'h90000200 || captured_cmd[0][22:0] !== 23'd64)
      $fatal(1, "unaligned command mismatch %h", captured_cmd[0]);
    feed_line(128, 1);
    await_done(1);
    if(metadata_count != 3 || cmd_count != 1 || error) $fatal(1, "unaligned fetch failed");
    $display("P4H_UNALIGNED_FETCH_GO START=130 COUNT=3 ALIGNED=128 BYTES=64");

    metadata_count=0;
    launch(0, 0, 1, 130, 3);
    await_done(2);
    if(metadata_count != 3 || cmd_count != 1 || error) $fatal(1, "replay failed");
    $display("P4H_REPLAY_GO ITEMS=3 DDR_COMMANDS=1");

    metadata_count=0;
    for(i=0;i<4;i=i+1) expected[i]=32'hca000000 + 143 + i;
    launch(1, 1, 2, 143, 4);
    wait(cmd_count == 2);
    if(captured_cmd[1][63:32] !== 32'h90000200 || captured_cmd[1][22:0] !== 23'd128)
      $fatal(1, "cross-line command mismatch %h", captured_cmd[1]);
    feed_line(128, 0);
    feed_line(144, 1);
    await_done(3);
    if(metadata_count != 4 || cmd_count != 2 || error)
      $fatal(1, "cross-line fetch failed metadata=%0d cmd=%0d error=%0d", metadata_count, cmd_count, error);
    $display("P4H_CROSS_LINE_GO START=143 COUNT=4 DDR_LINES=2");
    $display("P4H_KV_METADATA_REQUESTER_GO PACKED32=1 UNALIGNED=1 CROSS_LINE=1 REPLAY_NO_DDR=1 BACKPRESSURE_SAFE=1 TAG2=1");
    $finish;
  end
endmodule
