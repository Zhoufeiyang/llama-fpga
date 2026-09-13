`timescale 1ns/1ps

// Focused RTL check for the production front-end.  It exercises the actual
// generated Spinal module, not a duplicate scheduler model: one B-beat row is
// accepted from the input and the exact row is observed K times at wkvOut.
module p2_production_replay_frontend_tb;
  localparam integer B = 2;
  localparam integer ROWS = 2;
  localparam integer MAX_CASE_K = 4;

  logic clk = 0;
  always #5 clk = ~clk;
  logic reset = 1;
  logic mode = 1;
  logic [2:0] k = 1;
  logic wkvIn_tvalid = 0;
  wire wkvIn_tready;
  logic [127:0] wkvIn_tdata = 0;
  logic dotIn_tvalid = 0;
  wire dotIn_tready;
  logic [127:0] dotIn_tdata = 0;
  logic postScaleIn_tvalid = 0;
  wire postScaleIn_tready;
  logic [31:0] postScaleIn_tdata = 0;
  logic cfgIn_tvalid = 0;
  wire cfgIn_tready;
  logic [31:0] cfgIn_tdata = 32'h0001_0001;
  wire wkvOut_tvalid;
  logic wkvOut_tready = 1;
  wire [127:0] wkvOut_tdata;
  wire dotOut_tvalid;
  logic dotOut_tready = 1;
  wire [127:0] dotOut_tdata;
  wire postScaleOut_tvalid;
  logic postScaleOut_tready = 1;
  wire [31:0] postScaleOut_tdata;
  wire cfgOut_tvalid;
  logic cfgOut_tready = 1;
  wire [31:0] cfgOut_tdata;
  wire [31:0] inputWeightBeats;
  wire [31:0] logicalOperandReplays;
  wire active, replayDone, error;
  wire [3:0] errorCode;
  integer errors = 0;
  integer i, j, got, wait_cycles;
  reg [127:0] expected_row [0:B-1];
  reg [31:0] expected_scale_row [0:B-1];

  SpeculativeGemmReplay dut (
    .mode(mode), .k(k), .wkvIn_tvalid(wkvIn_tvalid),
    .wkvIn_tready(wkvIn_tready), .wkvIn_tdata(wkvIn_tdata),
    .dotIn_tvalid(dotIn_tvalid), .dotIn_tready(dotIn_tready),
    .dotIn_tdata(dotIn_tdata),
    .postScaleIn_tvalid(postScaleIn_tvalid),
    .postScaleIn_tready(postScaleIn_tready),
    .postScaleIn_tdata(postScaleIn_tdata),
    .cfgIn_tvalid(cfgIn_tvalid),
    .cfgIn_tready(cfgIn_tready), .cfgIn_tdata(cfgIn_tdata),
    .wkvOut_tvalid(wkvOut_tvalid), .wkvOut_tready(wkvOut_tready),
    .wkvOut_tdata(wkvOut_tdata), .dotOut_tvalid(dotOut_tvalid),
    .dotOut_tready(dotOut_tready), .dotOut_tdata(dotOut_tdata),
    .postScaleOut_tvalid(postScaleOut_tvalid),
    .postScaleOut_tready(postScaleOut_tready),
    .postScaleOut_tdata(postScaleOut_tdata),
    .cfgOut_tvalid(cfgOut_tvalid), .cfgOut_tready(cfgOut_tready),
    .cfgOut_tdata(cfgOut_tdata), .inputWeightBeats(inputWeightBeats),
    .logicalOperandReplays(logicalOperandReplays), .active(active),
    .replayDone(replayDone), .error(error), .errorCode(errorCode),
    .clk(clk), .reset(reset)
  );

  task automatic send_dot(input integer count);
    integer n;
    begin
      for (n = 0; n < count; n = n + 1) begin
        @(negedge clk);
        dotIn_tdata = 128'h1000 + n;
        dotIn_tvalid = 1;
        @(posedge clk);
        while (!dotIn_tready) @(posedge clk);
        @(negedge clk);
        dotIn_tvalid = 0;
      end
    end
  endtask

  task automatic run_case(input integer kval);
    integer row_idx, beat_idx;
    begin
      reset = 1; cfgIn_tvalid = 0; dotIn_tvalid = 0; wkvIn_tvalid = 0;
      postScaleIn_tvalid = 0;
      repeat (3) @(posedge clk);
      reset = 0; k = kval;
      @(negedge clk); cfgIn_tvalid = 1; cfgIn_tdata = 32'h0001_0001;
      @(posedge clk);
      while (!cfgIn_tready) @(posedge clk);
      @(negedge clk); cfgIn_tvalid = 0;

      send_dot(kval * B);
      got = 0;
      for (row_idx = 0; row_idx < ROWS; row_idx = row_idx + 1) begin
        for (beat_idx = 0; beat_idx < B; beat_idx = beat_idx + 1) begin
          expected_row[beat_idx] = 128'h8000 + row_idx * 16 + beat_idx;
          expected_scale_row[beat_idx] = 32'h3f800000 + row_idx * 16 + beat_idx;
          @(negedge clk);
          wkvIn_tdata = expected_row[beat_idx];
          postScaleIn_tdata = expected_scale_row[beat_idx];
          wkvIn_tvalid = 1;
          postScaleIn_tvalid = 1;
          @(posedge clk);
          while (!wkvIn_tready || !postScaleIn_tready) @(posedge clk);
          @(negedge clk);
          wkvIn_tvalid = 0;
          postScaleIn_tvalid = 0;
        end
        // Row is emitted token-major: token 0 B beats, then token 1 B beats.
        for (beat_idx = 0; beat_idx < kval * B; beat_idx = beat_idx + 1) begin
          wait_cycles = 0;
          while ((!wkvOut_tvalid || !postScaleOut_tvalid) && wait_cycles < 100) begin
            @(negedge clk); wait_cycles = wait_cycles + 1;
          end
          if (!wkvOut_tvalid) begin
            $display("TIMEOUT K=%0d row=%0d replay=%0d", kval, row_idx, beat_idx);
            errors = errors + 1;
          end else begin
            if (wkvOut_tdata !== expected_row[beat_idx % B]) begin
              $display("REPLAY_MISMATCH K=%0d row=%0d index=%0d got=%h exp=%h",
                       kval, row_idx, beat_idx, wkvOut_tdata, expected_row[beat_idx % B]);
              errors = errors + 1;
            end
            if (postScaleOut_tdata !== expected_scale_row[beat_idx % B]) begin
              $display("SCALE_REPLAY_MISMATCH K=%0d row=%0d index=%0d got=%h exp=%h",
                       kval, row_idx, beat_idx, postScaleOut_tdata,
                       expected_scale_row[beat_idx % B]);
              errors = errors + 1;
            end
            // Sample once before the accepting rising edge; this avoids
            // racing the nonblocking replay-index update in the DUT.
            @(posedge clk); got = got + 1;
            // Allow the DUT's replay index update to settle before checking
            // the next combinational memory read.
            @(negedge clk);
          end
        end
      end
      repeat (2) @(posedge clk);
      if (inputWeightBeats !== ROWS * B) begin
        $display("INPUT_COUNT_MISMATCH K=%0d got=%0d exp=%0d", kval,
                 inputWeightBeats, ROWS * B); errors = errors + 1;
      end
      if (logicalOperandReplays !== ROWS * B * kval) begin
        $display("REPLAY_COUNT_MISMATCH K=%0d got=%0d exp=%0d", kval,
                 logicalOperandReplays, ROWS * B * kval); errors = errors + 1;
      end
      if (error) begin
        $display("DUT_ERROR K=%0d code=%0d", kval, errorCode); errors = errors + 1;
      end
      $display("P2_PROD_REPLAY_K%0d_GO input_weight_beats=%0d logical_replays=%0d outputs=%0d",
               kval, inputWeightBeats, logicalOperandReplays, got);
    end
  endtask

  initial begin
    for (i = 1; i <= MAX_CASE_K; i = i + 1) run_case(i);
    if (errors != 0) $fatal(1, "P2 production replay front-end failed: %0d", errors);
    $display("P2_PRODUCTION_REPLAY_FRONTEND_GO K1_TO_K4_WEIGHT_INDEPENDENT=1 SCALE_REPLAY=1 TOKEN_ORDER=1");
    $finish;
  end
endmodule
