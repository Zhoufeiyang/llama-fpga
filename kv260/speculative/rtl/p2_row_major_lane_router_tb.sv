`timescale 1ns/1ps

module p2_row_major_lane_router_tb;
  localparam integer MAX_K = 4;
  localparam integer ROWS = 2;
  localparam integer BEATS = 3;

  logic clk = 0;
  always #5 clk = ~clk;
  logic reset = 1;
  logic [2:0] k = 1;

  logic input_tvalid = 0;
  wire input_tready;
  logic [31:0] input_tdata = 0;
  logic [1:0] input_tdata_laneId = 0;
  logic [7:0] input_tdata_rowIndex = 0;
  logic input_tdata_rowLast = 0;
  logic input_tdata_batchLast = 0;

  wire [MAX_K-1:0] outputs_tvalid;
  logic [MAX_K-1:0] outputs_tready = '0;
  wire [31:0] outputs_0_tdata_data, outputs_1_tdata_data,
              outputs_2_tdata_data, outputs_3_tdata_data;
  wire [1:0] outputs_0_tdata_laneId, outputs_1_tdata_laneId,
             outputs_2_tdata_laneId, outputs_3_tdata_laneId;
  wire [7:0] outputs_0_tdata_rowIndex, outputs_1_tdata_rowIndex,
             outputs_2_tdata_rowIndex, outputs_3_tdata_rowIndex;
  wire outputs_0_tdata_rowLast, outputs_1_tdata_rowLast,
       outputs_2_tdata_rowLast, outputs_3_tdata_rowLast;
  wire outputs_0_tdata_batchLast, outputs_1_tdata_batchLast,
       outputs_2_tdata_batchLast, outputs_3_tdata_batchLast;

  wire fault_tvalid;
  logic fault_tready = 1;
  wire [31:0] fault_tdata_data;
  wire [1:0] fault_tdata_laneId;
  wire [7:0] fault_tdata_rowIndex;
  wire fault_tdata_rowLast, fault_tdata_batchLast;
  wire batchActive, batchDone, error;
  wire [3:0] errorCode;

  assign outputs_0_tready = outputs_tready[0];
  assign outputs_1_tready = outputs_tready[1];
  assign outputs_2_tready = outputs_tready[2];
  assign outputs_3_tready = outputs_tready[3];
  assign outputs_tvalid = {outputs_3_tvalid, outputs_2_tvalid,
                           outputs_1_tvalid, outputs_0_tvalid};

  P2RowMajorLaneRouter dut (
    .k(k),
    .input_tvalid(input_tvalid), .input_tready(input_tready),
    .input_tdata_data(input_tdata), .input_tdata_laneId(input_tdata_laneId),
    .input_tdata_rowIndex(input_tdata_rowIndex),
    .input_tdata_rowLast(input_tdata_rowLast),
    .input_tdata_batchLast(input_tdata_batchLast),
    .outputs_0_tvalid(outputs_0_tvalid), .outputs_0_tready(outputs_0_tready),
    .outputs_0_tdata_data(outputs_0_tdata_data),
    .outputs_0_tdata_laneId(outputs_0_tdata_laneId),
    .outputs_0_tdata_rowIndex(outputs_0_tdata_rowIndex),
    .outputs_0_tdata_rowLast(outputs_0_tdata_rowLast),
    .outputs_0_tdata_batchLast(outputs_0_tdata_batchLast),
    .outputs_1_tvalid(outputs_1_tvalid), .outputs_1_tready(outputs_1_tready),
    .outputs_1_tdata_data(outputs_1_tdata_data),
    .outputs_1_tdata_laneId(outputs_1_tdata_laneId),
    .outputs_1_tdata_rowIndex(outputs_1_tdata_rowIndex),
    .outputs_1_tdata_rowLast(outputs_1_tdata_rowLast),
    .outputs_1_tdata_batchLast(outputs_1_tdata_batchLast),
    .outputs_2_tvalid(outputs_2_tvalid), .outputs_2_tready(outputs_2_tready),
    .outputs_2_tdata_data(outputs_2_tdata_data),
    .outputs_2_tdata_laneId(outputs_2_tdata_laneId),
    .outputs_2_tdata_rowIndex(outputs_2_tdata_rowIndex),
    .outputs_2_tdata_rowLast(outputs_2_tdata_rowLast),
    .outputs_2_tdata_batchLast(outputs_2_tdata_batchLast),
    .outputs_3_tvalid(outputs_3_tvalid), .outputs_3_tready(outputs_3_tready),
    .outputs_3_tdata_data(outputs_3_tdata_data),
    .outputs_3_tdata_laneId(outputs_3_tdata_laneId),
    .outputs_3_tdata_rowIndex(outputs_3_tdata_rowIndex),
    .outputs_3_tdata_rowLast(outputs_3_tdata_rowLast),
    .outputs_3_tdata_batchLast(outputs_3_tdata_batchLast),
    .fault_tvalid(fault_tvalid), .fault_tready(fault_tready),
    .fault_tdata_data(fault_tdata_data), .fault_tdata_laneId(fault_tdata_laneId),
    .fault_tdata_rowIndex(fault_tdata_rowIndex),
    .fault_tdata_rowLast(fault_tdata_rowLast),
    .fault_tdata_batchLast(fault_tdata_batchLast),
    .batchActive(batchActive), .batchDone(batchDone),
    .error(error), .errorCode(errorCode), .clk(clk), .reset(reset)
  );

  integer errors = 0;
  integer accepted;
  logic done_seen;
  always @(posedge clk) if (batchDone) done_seen <= 1;

  task automatic check_output(input integer lane, input integer row,
                              input integer beat, input integer value,
                              input integer expect_row_last,
                              input integer expect_batch_last);
    begin
      case (lane)
        0: begin
          if (!outputs_0_tvalid || outputs_0_tdata_data !== value ||
              outputs_0_tdata_laneId !== lane || outputs_0_tdata_rowIndex !== row ||
              outputs_0_tdata_rowLast !== expect_row_last ||
              outputs_0_tdata_batchLast !== expect_batch_last) begin
            errors = errors + 1;
          end
        end
        1: begin
          if (!outputs_1_tvalid || outputs_1_tdata_data !== value ||
              outputs_1_tdata_laneId !== lane || outputs_1_tdata_rowIndex !== row ||
              outputs_1_tdata_rowLast !== expect_row_last ||
              outputs_1_tdata_batchLast !== expect_batch_last) begin
            errors = errors + 1;
          end
        end
        2: begin
          if (!outputs_2_tvalid || outputs_2_tdata_data !== value ||
              outputs_2_tdata_laneId !== lane || outputs_2_tdata_rowIndex !== row ||
              outputs_2_tdata_rowLast !== expect_row_last ||
              outputs_2_tdata_batchLast !== expect_batch_last) begin
            errors = errors + 1;
          end
        end
        3: begin
          if (!outputs_3_tvalid || outputs_3_tdata_data !== value ||
              outputs_3_tdata_laneId !== lane || outputs_3_tdata_rowIndex !== row ||
              outputs_3_tdata_rowLast !== expect_row_last ||
              outputs_3_tdata_batchLast !== expect_batch_last) begin
            errors = errors + 1;
          end
        end
      endcase
      accepted = accepted + 1;
    end
  endtask

  task automatic send_beat(input integer kval, input integer lane,
                           input integer row, input integer beat,
                           input integer row_last, input integer batch_last);
    integer wait_cycles;
    begin
      input_tdata = (row * 1000) + (lane * 100) + beat;
      input_tdata_laneId = lane;
      input_tdata_rowIndex = row;
      input_tdata_rowLast = row_last;
      input_tdata_batchLast = batch_last;
      input_tvalid = 1;
      outputs_tready = '0;
      repeat (2) @(posedge clk);
      @(negedge clk);
      outputs_tready[lane] = 1;
      wait_cycles = 0;
      while (wait_cycles < 20) begin
        @(posedge clk);
        if (input_tready) begin
          check_output(lane, row, beat, input_tdata, row_last, batch_last);
          wait_cycles = 20;
        end else begin
          wait_cycles = wait_cycles + 1;
        end
      end
      if (!input_tready) begin
        errors = errors + 1;
        @(negedge clk);
      end else begin
        @(negedge clk);
      end
      input_tvalid = 0;
      outputs_tready = '0;
      @(posedge clk);
    end
  endtask

  task automatic run_case(input integer kval);
    integer row, lane, beat;
    begin
      reset = 1;
      input_tvalid = 0;
      outputs_tready = '0;
      accepted = 0;
      done_seen = 0;
      repeat (3) @(posedge clk);
      reset = 0;
      k = kval;
      for (row = 0; row < ROWS; row = row + 1) begin
        for (lane = 0; lane < kval; lane = lane + 1) begin
          for (beat = 0; beat < BEATS; beat = beat + 1) begin
            send_beat(kval, lane, row, beat, beat == BEATS - 1,
                      row == ROWS - 1 && lane == kval - 1 && beat == BEATS - 1);
          end
        end
      end
      if (accepted != ROWS * kval * BEATS) errors = errors + 1;
      @(negedge clk);
      if (!done_seen || batchActive || error) begin
        $display("P2_STATUS_FAIL K%0d done_seen=%b active=%b error=%b code=%0d", kval, done_seen, batchActive, error, errorCode);
        errors = errors + 1;
      end
      $display("P2_ROW_ROUTER_K%0d_GO accepted=%0d rows=%0d lanes=%0d backpressure=1",
               kval, accepted, ROWS, kval);
    end
  endtask

  initial begin
    for (integer kval = 1; kval <= MAX_K; kval = kval + 1) run_case(kval);

    // Invalid lane is drainable through fault and becomes a sticky error.
    reset = 1; input_tvalid = 0; outputs_tready = '0;
    repeat (3) @(posedge clk);
    reset = 0; k = 2;
    input_tdata = 32'hdead_beef; input_tdata_laneId = 3;
    input_tdata_rowIndex = 0; input_tdata_rowLast = 1; input_tdata_batchLast = 1;
    input_tvalid = 1;
    @(negedge clk);
    if (!fault_tvalid || !input_tready) errors = errors + 1;
    @(negedge clk);
    input_tvalid = 0;
    if (!error || errorCode !== 4'd2) errors = errors + 1;
    if (errors != 0) $fatal(1, "P2 row-major lane router failed: %0d", errors);
    $display("P2_ROW_MAJOR_LANE_ROUTER_GO K1_TO_K4=1 SIDEBAND=1 FAULT_DRAINABLE=1");
    $finish;
  end
endmodule
