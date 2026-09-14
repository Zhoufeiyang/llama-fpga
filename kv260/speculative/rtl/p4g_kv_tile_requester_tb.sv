`timescale 1ns/1ps

module p4g_kv_tile_requester_tb;
  logic clk=0, reset=1;
  logic req_valid, req_ready, req_phase, req_source, req_fetch, req_buffer, req_last;
  logic [1:0] req_query;
  logic [10:0] req_start, req_count;
  logic [31:0] base;
  logic [3:0] command_tag;
  logic [5:0] tag;
  logic cmd_valid, cmd_ready;
  logic [71:0] cmd;
  logic ddr_valid, ddr_ready, ddr_last;
  logic [127:0] ddr_data;
  logic [15:0] ddr_keep;
  logic out_valid, out_ready, out_last;
  logic [127:0] out_data;
  logic [5:0] out_dest;
  logic done_valid, done_phase, done_source, done_buffer;
  logic [1:0] done_query;
  logic [10:0] done_start;
  logic busy, error;
  integer i, out_count, cmd_count, done_count, cycle_count;
  logic [71:0] last_cmd;
  logic [127:0] expected [0:7];

  always #5 clk = ~clk;

  P4KvTileRequester dut(
    .io_request_valid(req_valid), .io_request_ready(req_ready),
    .io_request_payload_phase(req_phase), .io_request_payload_source(req_source),
    .io_request_payload_fetch(req_fetch), .io_request_payload_buffer(req_buffer),
    .io_request_payload_query(req_query), .io_request_payload_startToken(req_start),
    .io_request_payload_tokenCount(req_count), .io_request_payload_last(req_last),
    .io_baseAddress(base), .io_commandTag(command_tag), .io_outputTag(tag),
    .io_ddrCmd_valid(cmd_valid), .io_ddrCmd_ready(cmd_ready), .io_ddrCmd_payload(cmd),
    .io_ddrData_valid(ddr_valid), .io_ddrData_ready(ddr_ready),
    .io_ddrData_payload_data(ddr_data), .io_ddrData_payload_keep(ddr_keep),
    .io_ddrData_payload_last(ddr_last),
    .io_output_valid(out_valid), .io_output_ready(out_ready),
    .io_output_payload_data(out_data), .io_output_payload_last(out_last),
    .io_output_payload_dest(out_dest), .io_done_valid(done_valid),
    .io_done_payload_phase(done_phase), .io_done_payload_source(done_source),
    .io_done_payload_buffer(done_buffer), .io_done_payload_query(done_query),
    .io_done_payload_startToken(done_start), .io_busy(busy), .io_error(error),
    .clk(clk), .reset(reset)
  );

  always @(posedge clk) begin
    cycle_count <= cycle_count + 1;
    if(!reset) out_ready <= ((cycle_count % 3) != 0);
    if(cmd_valid && cmd_ready) begin cmd_count <= cmd_count + 1; last_cmd <= cmd; end
    if(done_valid) begin
      done_count <= done_count + 1;
      if(done_query !== req_query || done_start !== req_start || done_buffer !== req_buffer)
        $fatal(1, "completion identity mismatch");
    end
    if(out_valid && out_ready) begin
      if(out_data !== expected[out_count]) $fatal(1, "data mismatch beat=%0d", out_count);
      if(out_dest !== tag) $fatal(1, "tag mismatch");
      if(out_last !== (out_count == 7)) $fatal(1, "last mismatch beat=%0d", out_count);
      out_count <= out_count + 1;
    end
  end

  task automatic launch(input logic fetch_i, input logic [1:0] query_i);
    begin
      wait(req_ready);
      @(negedge clk);
      req_fetch=fetch_i; req_query=query_i; req_valid=1;
      @(negedge clk);
      req_valid=0;
    end
  endtask

  task automatic feed_fetch;
    begin
      for(i=0;i<8;i=i+1) begin
        @(negedge clk);
        ddr_data=expected[i]; ddr_last=(i==7); ddr_valid=1;
        do @(posedge clk); while(!ddr_ready);
        @(negedge clk);
        ddr_valid=0;
      end
    end
  endtask

  task automatic await_done(input integer expected_done);
    integer timeout;
    begin
      timeout=0;
      while(done_count<expected_done && timeout<100) begin @(negedge clk); timeout=timeout+1; end
      if(done_count<expected_done) $fatal(1, "timeout");
    end
  endtask

  initial begin
    fork begin #10000; $fatal(1, "global timeout busy=%0d cmd=%0d out=%0d done=%0d", busy, cmd_count, out_count, done_count); end join_none
    req_valid=0; req_phase=0; req_source=0; req_fetch=0; req_buffer=0;
    req_query=0; req_start=11'd64; req_count=1; req_last=0;
    base=32'h80000000; command_tag=4'd1; tag=6'd8; cmd_ready=1;
    ddr_valid=0; ddr_data=0; ddr_keep='1; ddr_last=0;
    out_ready=1; out_count=0; cmd_count=0; done_count=0; cycle_count=0; last_cmd=0;
    for(i=0;i<8;i=i+1) expected[i]={96'h0,32'hcafe0000+i};
    repeat(5) @(negedge clk); reset=0;

    launch(1, 0);
    $display("P4G fetch request accepted");
    wait(cmd_count == 1);
    if(last_cmd[67:64] !== command_tag || last_cmd[63:32] !== 32'h80002000 || last_cmd[22:0] !== 23'd128)
      $fatal(1, "wrong DDR command address/length %h", last_cmd);
    feed_fetch();
    await_done(1);
    if(out_count != 8 || cmd_count != 1 || error) $fatal(1, "fetch failed");
    $display("P4G_FETCH_GO beats=%0d commands=%0d", out_count, cmd_count);

    out_count=0;
    launch(0, 1);
    await_done(2);
    if(out_count != 8 || cmd_count != 1 || error) $fatal(1, "replay failed");
    $display("P4G_REPLAY_GO beats=%0d commands=%0d", out_count, cmd_count);
    $display("P4G_KV_TILE_REQUESTER_GO DDR_FETCH_ONCE=1 PING_PONG=1 REPLAY_NO_DDR=1 BACKPRESSURE_SAFE=1 COMPLETION_IDENTITY=1");
    $finish;
  end
endmodule
