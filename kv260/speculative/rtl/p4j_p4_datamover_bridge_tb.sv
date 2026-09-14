`timescale 1ns/1ps

// Focused xsim test for P4DataMoverBridge.  It checks P4-first command
// arbitration, command stability under stall, independent data/status owner
// retirement, and response backpressure without touching DataPath.
module p4j_p4_datamover_bridge_tb;
  logic clk = 0, reset = 1;
  logic legacyCmd_valid, legacyCmd_ready;
  logic p4Cmd_valid, p4Cmd_ready;
  logic [71:0] legacyCmd_payload, p4Cmd_payload;
  logic outCmd_valid, outCmd_ready;
  logic [71:0] outCmd_payload;

  logic dmaResponse_valid, dmaResponse_ready;
  logic [511:0] dmaResponse_data;
  logic [63:0] dmaResponse_keep;
  logic dmaResponse_last;
  logic legacyResponse_valid, legacyResponse_ready;
  logic [511:0] legacyResponse_data;
  logic [63:0] legacyResponse_keep;
  logic legacyResponse_last;
  logic p4Response_valid, p4Response_ready;
  logic [511:0] p4Response_data;
  logic [63:0] p4Response_keep;
  logic p4Response_last;

  logic dmaStatus_valid, dmaStatus_ready;
  logic [7:0] dmaStatus_payload;
  logic legacyStatus_valid, legacyStatus_ready;
  logic [7:0] legacyStatus_payload;
  logic p4Status_valid, p4Status_ready;
  logic [7:0] p4Status_payload;
  logic error;

  integer cmd_count = 0, legacy_data_count = 0, p4_data_count = 0;
  integer legacy_status_count = 0, p4_status_count = 0;
  logic [71:0] observed_cmd [0:1];
  logic [71:0] held_cmd_payload;
  logic held_cmd_seen = 0;

  always #5 clk = ~clk;

  P4DataMoverBridge dut (
    .io_legacyCmd_valid(legacyCmd_valid), .io_legacyCmd_ready(legacyCmd_ready),
    .io_legacyCmd_payload(legacyCmd_payload),
    .io_p4Cmd_valid(p4Cmd_valid), .io_p4Cmd_ready(p4Cmd_ready),
    .io_p4Cmd_payload(p4Cmd_payload),
    .io_outCmd_valid(outCmd_valid), .io_outCmd_ready(outCmd_ready),
    .io_outCmd_payload(outCmd_payload),
    .io_dmaResponse_valid(dmaResponse_valid), .io_dmaResponse_ready(dmaResponse_ready),
    .io_dmaResponse_payload_data(dmaResponse_data),
    .io_dmaResponse_payload_keep(dmaResponse_keep),
    .io_dmaResponse_payload_last(dmaResponse_last),
    .io_legacyResponse_valid(legacyResponse_valid), .io_legacyResponse_ready(legacyResponse_ready),
    .io_legacyResponse_payload_data(legacyResponse_data),
    .io_legacyResponse_payload_keep(legacyResponse_keep),
    .io_legacyResponse_payload_last(legacyResponse_last),
    .io_p4Response_valid(p4Response_valid), .io_p4Response_ready(p4Response_ready),
    .io_p4Response_payload_data(p4Response_data),
    .io_p4Response_payload_keep(p4Response_keep),
    .io_p4Response_payload_last(p4Response_last),
    .io_dmaStatus_valid(dmaStatus_valid), .io_dmaStatus_ready(dmaStatus_ready),
    .io_dmaStatus_payload(dmaStatus_payload),
    .io_legacyStatus_valid(legacyStatus_valid), .io_legacyStatus_ready(legacyStatus_ready),
    .io_legacyStatus_payload(legacyStatus_payload),
    .io_p4Status_valid(p4Status_valid), .io_p4Status_ready(p4Status_ready),
    .io_p4Status_payload(p4Status_payload),
    .io_error(error), .clk(clk), .reset(reset)
  );

  always @(posedge clk) begin
    if (!reset) begin
      if (outCmd_valid && outCmd_ready) begin
        observed_cmd[cmd_count] <= outCmd_payload;
        cmd_count <= cmd_count + 1;
      end
      if (legacyResponse_valid && legacyResponse_ready) legacy_data_count <= legacy_data_count + 1;
      if (p4Response_valid && p4Response_ready) p4_data_count <= p4_data_count + 1;
      if (legacyStatus_valid && legacyStatus_ready) begin
        if (legacyStatus_payload !== 8'hb2) $fatal(1, "legacy status mismatch");
        legacy_status_count <= legacy_status_count + 1;
      end
      if (p4Status_valid && p4Status_ready) begin
        if (p4Status_payload !== 8'ha1) $fatal(1, "P4 status mismatch");
        p4_status_count <= p4_status_count + 1;
      end
      if (outCmd_valid && !outCmd_ready) begin
        if (!held_cmd_seen) begin
          held_cmd_payload <= outCmd_payload;
          held_cmd_seen <= 1;
        end else if (held_cmd_payload !== outCmd_payload) begin
          $fatal(1, "command changed while stalled");
        end
      end else if (outCmd_valid && outCmd_ready) begin
        held_cmd_seen <= 0;
      end
    end
  end

  task automatic drive_response(input logic [511:0] data_i, input logic last_i);
    begin
      @(negedge clk);
      dmaResponse_data = data_i;
      dmaResponse_last = last_i;
      dmaResponse_valid = 1;
      do @(posedge clk); while (!dmaResponse_ready);
      @(negedge clk);
      dmaResponse_valid = 0;
    end
  endtask

  task automatic drive_status(input logic [7:0] status_i);
    begin
      @(negedge clk);
      dmaStatus_payload = status_i;
      dmaStatus_valid = 1;
      do @(posedge clk); while (!dmaStatus_ready);
      @(negedge clk);
      dmaStatus_valid = 0;
    end
  endtask

  initial begin
    legacyCmd_valid = 0; legacyCmd_payload = 0;
    p4Cmd_valid = 0; p4Cmd_payload = 0;
    outCmd_ready = 0;
    dmaResponse_valid = 0; dmaResponse_data = 0; dmaResponse_keep = '1; dmaResponse_last = 0;
    legacyResponse_ready = 1; p4Response_ready = 0;
    dmaStatus_valid = 0; dmaStatus_payload = 0;
    legacyStatus_ready = 1; p4Status_ready = 0;

    repeat (4) @(negedge clk);
    reset = 0;

    // Both clients request simultaneously.  P4 is accepted first and the
    // selected command is deliberately stalled for several cycles.
    @(negedge clk);
    legacyCmd_payload = 72'h1111;
    p4Cmd_payload = 72'h2222;
    legacyCmd_valid = 1;
    p4Cmd_valid = 1;
    @(posedge clk);
    if (!p4Cmd_ready || legacyCmd_ready) $fatal(1, "P4 priority arbitration failed");
    @(negedge clk);
    p4Cmd_valid = 0;
    repeat (3) @(posedge clk);
    if (!outCmd_valid || outCmd_payload !== 72'h2222) $fatal(1, "P4 command was not held");
    outCmd_ready = 1;
    @(posedge clk);
    @(negedge clk);
    legacyCmd_valid = 0;
    if (cmd_count != 1 || observed_cmd[0] !== 72'h2222)
      $fatal(1, "wrong first command");

    // P4 owns a two-beat frame.  Backpressure the P4 sink on the first beat;
    // the DMA response must remain stalled and must not leak to legacy.
    fork
      drive_response(512'h0000_0001, 0);
      begin
        repeat (2) @(negedge clk);
        if (!p4Response_valid || legacyResponse_valid) $fatal(1, "P4 response route failed");
        p4Response_ready = 1;
        @(posedge clk);
      end
    join
    drive_response(512'h0000_0002, 1);
    p4Response_ready = 0;

    // Status is independently backpressured and retires the same P4 owner.
    fork
      drive_status(8'ha1);
      begin
        repeat (2) @(negedge clk);
        if (!p4Status_valid || legacyStatus_valid) $fatal(1, "P4 status route failed");
        p4Status_ready = 1;
        @(posedge clk);
      end
    join
    p4Status_ready = 0;

    // The previously held legacy command is now released and must be second.
    wait (legacyCmd_ready);
    @(negedge clk);
    legacyCmd_payload = 72'h3333;
    legacyCmd_valid = 1;
    wait (legacyCmd_ready);
    @(posedge clk);
    @(negedge clk);
    legacyCmd_valid = 0;
    wait (cmd_count == 2);
    if (observed_cmd[1] !== 72'h3333) $fatal(1, "wrong legacy command");

    drive_response(512'h0000_0003, 1);
    drive_status(8'hb2);
    repeat (4) @(posedge clk);
    if (error || p4_data_count != 2 || legacy_data_count != 1 ||
        p4_status_count != 1 || legacy_status_count != 1)
      $fatal(1, "bridge failed cmd=%0d p4d=%0d ld=%0d p4s=%0d ls=%0d e=%0d",
        cmd_count, p4_data_count, legacy_data_count,
        p4_status_count, legacy_status_count, error);
    $display("P4J_DATAMOVER_BRIDGE_GO P4_PRIORITY=1 FRAME_STABLE=1 OWNER_DEMUX=1 BACKPRESSURE=1");
    $finish;
  end

  initial begin
    #20000;
    $fatal(1, "global timeout");
  end
endmodule
