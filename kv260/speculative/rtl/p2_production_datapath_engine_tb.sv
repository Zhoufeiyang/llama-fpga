`timescale 1ns/1ps

// Small vendor-FP16 check for the real shared MulEngine/AddEngine path.  The
// replay-front-end XSim test checks traffic/order; this test checks that the
// production reduction emits K independent dot products and scalarOut_tlast
// remains aligned to the B-beat boundary.
module p2_production_datapath_engine_tb;
  localparam integer B = 2;
  localparam integer ROWS = 2;
  localparam integer MAX_K = 4;

  logic clk = 0;
  always #5 clk = ~clk;
  logic reset = 1;
  logic speculativeMode = 1;
  logic [2:0] speculativeK = 1;

  logic wkvIn_tvalid = 0;
  wire wkvIn_tready;
  logic [127:0] wkvIn_tdata = 0;
  logic dotIn_tvalid = 0;
  wire dotIn_tready;
  logic [127:0] dotIn_tdata = 0;
  logic axpyIn_tvalid = 0;
  wire axpyIn_tready;
  logic [15:0] axpyIn_tdata = 0;
  logic preScale_tvalid = 0;
  wire preScale_tready;
  logic [15:0] preScale_tdata = 0;
  logic resAdd_tvalid = 0;
  wire resAdd_tready;
  logic [127:0] resAdd_tdata = 0;
  wire vecOut_tvalid;
  wire [127:0] vecOut_tdata;
  wire [5:0] vecOut_tuser;
  wire scalarOut_tvalid;
  wire scalarOut_tlast;
  wire [15:0] scalarOut_tdata;
  wire [5:0] scalarOut_tuser;
  logic cfg_tvalid = 0;
  wire cfg_tready;
  logic [31:0] cfg_tdata = 32'h0001_0001;
  wire [5:0] preCfgTag;
  wire [5:0] postCfgTag;

  integer errors = 0;
  integer out_count;

  localparam logic [127:0] ONE_VEC = 128'h3c00_3c00_3c00_3c00_3c00_3c00_3c00_3c00;
  localparam logic [127:0] TWO_VEC = 128'h4000_4000_4000_4000_4000_4000_4000_4000;

  MulAddEngineNew dut (
    .wkvIn_tvalid(wkvIn_tvalid), .wkvIn_tready(wkvIn_tready), .wkvIn_tdata(wkvIn_tdata),
    .dotIn_tvalid(dotIn_tvalid), .dotIn_tready(dotIn_tready), .dotIn_tdata(dotIn_tdata),
    .axpyIn_tvalid(axpyIn_tvalid), .axpyIn_tready(axpyIn_tready), .axpyIn_tdata(axpyIn_tdata),
    .preScale_tvalid(preScale_tvalid), .preScale_tready(preScale_tready), .preScale_tdata(preScale_tdata),
    .resAdd_tvalid(resAdd_tvalid), .resAdd_tready(resAdd_tready), .resAdd_tdata(resAdd_tdata),
    .vecOut_tvalid(vecOut_tvalid), .vecOut_tdata(vecOut_tdata), .vecOut_tuser(vecOut_tuser),
    .scalarOut_tvalid(scalarOut_tvalid), .scalarOut_tlast(scalarOut_tlast),
    .scalarOut_tdata(scalarOut_tdata), .scalarOut_tuser(scalarOut_tuser),
    .cfg_tvalid(cfg_tvalid), .cfg_tready(cfg_tready), .cfg_tdata(cfg_tdata),
    .speculativeMode(speculativeMode), .speculativeK(speculativeK),
    .preCfgTag(preCfgTag), .postCfgTag(postCfgTag), .clk(clk), .reset(reset)
  );

  task automatic send_dot(input integer count);
    integer n, ready_cycles;
    begin
      for (n = 0; n < count; n = n + 1) begin
        @(negedge clk); dotIn_tdata = ONE_VEC; dotIn_tvalid = 1;
        ready_cycles = 0;
        @(posedge clk);
        while (!dotIn_tready && ready_cycles < 3000) begin
          @(posedge clk); ready_cycles = ready_cycles + 1;
        end
        if (!dotIn_tready) $fatal(1, "DOT_INPUT_TIMEOUT index=%0d", n);
        @(negedge clk); dotIn_tvalid = 0;
      end
    end
  endtask

  task automatic send_weight(input logic [127:0] value, input integer count);
    integer n, ready_cycles;
    begin
      for (n = 0; n < count; n = n + 1) begin
        @(negedge clk); wkvIn_tdata = value; wkvIn_tvalid = 1;
        ready_cycles = 0;
        @(posedge clk);
        while (!wkvIn_tready && ready_cycles < 3000) begin
          @(posedge clk); ready_cycles = ready_cycles + 1;
        end
        if (!wkvIn_tready) $fatal(1, "WEIGHT_INPUT_TIMEOUT index=%0d", n);
        @(negedge clk); wkvIn_tvalid = 0;
      end
    end
  endtask

  task automatic collect_scalar_outputs(input integer kval);
    logic [15:0] expected_scalar;
    integer scalar_wait_cycles;
    begin
      out_count = 0;
      while (out_count < ROWS * kval * B) begin
        scalar_wait_cycles = 0;
        while (!scalarOut_tvalid && scalar_wait_cycles < 3000) begin
          @(negedge clk); scalar_wait_cycles = scalar_wait_cycles + 1;
        end
        if (!scalarOut_tvalid) begin
          $display("SCALAR_TIMEOUT K=%0d index=%0d", kval, out_count);
          errors = errors + 1;
          out_count = ROWS * kval * B;
        end else begin
          // 8 lanes of 1.0 times 2.0 gives 16.0 per reduction beat.
          expected_scalar = 16'h4c00;
          if (scalarOut_tdata !== expected_scalar) begin
            $display("SCALAR_MISMATCH K=%0d index=%0d got=%h exp=%h",
                     kval, out_count, scalarOut_tdata, expected_scalar);
            errors = errors + 1;
          end
          if (scalarOut_tlast !== ((out_count % B) == B - 1)) begin
            $display("LAST_MISMATCH K=%0d index=%0d got=%0d exp=%0d",
                     kval, out_count, scalarOut_tlast, (out_count % B) == B - 1);
            errors = errors + 1;
          end
          @(negedge clk);
          out_count = out_count + 1;
        end
      end
    end
  endtask

  task automatic run_case(input integer kval);
    integer row_idx, token_idx, beat_idx;
    logic [15:0] expected_scalar;
    begin
      cfg_tvalid = 0; dotIn_tvalid = 0; wkvIn_tvalid = 0;
      speculativeK = kval;
      repeat (16) @(posedge clk);

      // The fork's two m2sPipe inputs accept the descriptor and retain it
      // while their downstream engines run to completion.
      @(negedge clk); cfg_tdata = 32'h0001_0001; cfg_tvalid = 1;
      @(posedge clk);
      while (!cfg_tready) @(posedge clk);
      @(negedge clk); cfg_tvalid = 0;

      // The production front-end loads all K activation positions before the
      // shared MulEngine starts output, with deliberate source-side gaps.
      send_dot(kval * B);
      // scalarOut is a Flow and therefore cannot be back-pressured.  Collect
      // it concurrently with the weight producer so no production outputs
      // are lost while the source task is still active.
      fork
        begin
          for (row_idx = 0; row_idx < ROWS; row_idx = row_idx + 1) begin
            for (token_idx = 0; token_idx < kval; token_idx = token_idx + 1) begin
              send_weight(TWO_VEC, B);
            end
          end
        end
        collect_scalar_outputs(kval);
      join
      $display("P2_PROD_DATAPATH_K%0d_GO scalar_outputs=%0d independent_results=%0d last_every_B=1",
               kval, ROWS * kval * B, ROWS * kval);
      // The Xilinx floating-point blocks have no reset input.  Leave enough
      // idle cycles for every fixed-latency stage to drain before launching
      // the next descriptor rather than resetting only the Spinal logic.
      repeat (96) @(posedge clk);
    end
  endtask

  integer case_idx;
  initial begin
    repeat (8) @(posedge clk);
    reset = 0;
    for (case_idx = 1; case_idx <= MAX_K; case_idx = case_idx + 1) run_case(case_idx);
    if (errors != 0) $fatal(1, "P2 production datapath scalar/last failed: %0d", errors);
    $display("P2_PRODUCTION_DATAPATH_ENGINE_GO K1_TO_K4_NUMERICAL=1 LAST_BOUNDARY=1");
    $finish;
  end
endmodule
