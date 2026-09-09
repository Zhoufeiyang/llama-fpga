`timescale 1ns/1ps

module p2_w4_weight_reuse_scheduler_tb;
  localparam integer LANES = 8;
  localparam integer MAX_BEATS = 5;

  logic clk = 1'b0;
  logic reset = 1'b1;
  always #5 clk = ~clk;

  logic cfg_valid, cfg_ready, cfg_mode;
  logic [2:0] cfg_k;
  logic [15:0] cfg_beats_per_row;
  logic [15:0] cfg_rows;
  logic activation_valid, activation_ready, activation_last;
  logic [16*LANES-1:0] activation_data;
  logic weight_valid, weight_ready, weight_last;
  logic [4*LANES-1:0] weight_packed;
  logic [7:0] weight_zero;
  logic [15:0] weight_scale;
  logic operand_valid, operand_ready, operand_mode, operand_last;
  logic [16*LANES-1:0] operand_activation;
  logic [5*LANES-1:0] operand_weight;
  logic [15:0] operand_scale;
  logic [2:0] operand_token_index;
  logic [2:0] operand_row_index;
  logic [$clog2(MAX_BEATS)-1:0] operand_beat_index;
  logic busy, done, error;
  logic [3:0] error_code;
  logic [31:0] accepted_weight_beats;

  p2_w4_weight_reuse_scheduler #(
    .LANES(LANES),
    .MAX_BEATS_PER_ROW(MAX_BEATS),
    .MAX_K(4),
    .MAX_ROWS(8)
  ) dut (.*);

  function automatic [16*LANES-1:0] make_activation(
    input integer token, input integer beat
  );
    integer lane;
    begin
      make_activation = '0;
      for (lane = 0; lane < LANES; lane = lane + 1)
        make_activation[lane*16 +: 16] = 16'h4000 + token*16'h0100 +
                                                 beat*16'h0010 + lane;
    end
  endfunction

  function automatic [4*LANES-1:0] make_weight(input integer row, input integer beat);
    integer lane;
    begin
      make_weight = '0;
      for (lane = 0; lane < LANES; lane = lane + 1)
        make_weight[lane*4 +: 4] = (row*5 + beat*3 + lane) & 4'hf;
    end
  endfunction

  function automatic [5*LANES-1:0] make_dequant(
    input integer row, input integer beat, input integer zero
  );
    integer lane;
    logic signed [5:0] difference;
    begin
      make_dequant = '0;
      for (lane = 0; lane < LANES; lane = lane + 1) begin
        difference = $signed({1'b0, ((row*5 + beat*3 + lane) & 4'hf)}) - zero;
        make_dequant[lane*5 +: 5] = difference[4:0];
      end
    end
  endfunction

  logic stalled;
  logic [16*LANES-1:0] stalled_activation;
  logic [5*LANES-1:0] stalled_weight;
  logic [15:0] stalled_scale;
  logic [2:0] stalled_token;
  logic [2:0] stalled_row;
  logic [$clog2(MAX_BEATS)-1:0] stalled_beat;
  logic stalled_mode, stalled_last;

  always_ff @(posedge clk) begin
    if (reset) begin
      stalled <= 1'b0;
    end else begin
      if (stalled) begin
        if (!operand_valid || operand_activation !== stalled_activation ||
            operand_weight !== stalled_weight ||
            operand_scale !== stalled_scale ||
            operand_token_index !== stalled_token ||
            operand_row_index !== stalled_row ||
            operand_beat_index !== stalled_beat ||
            operand_mode !== stalled_mode || operand_last !== stalled_last) begin
          $fatal(1, "operand payload changed while stalled");
        end
      end
      stalled <= operand_valid && !operand_ready;
      if (operand_valid && !operand_ready) begin
        stalled_activation <= operand_activation;
        stalled_weight <= operand_weight;
        stalled_scale <= operand_scale;
        stalled_token <= operand_token_index;
        stalled_row <= operand_row_index;
        stalled_beat <= operand_beat_index;
        stalled_mode <= operand_mode;
        stalled_last <= operand_last;
      end
    end
  end

  task automatic send_cfg(input bit mode, input integer k, input integer beats,
                          input integer rows);
    begin
      @(negedge clk);
      cfg_mode = mode;
      cfg_k = k;
      cfg_beats_per_row = beats;
      cfg_rows = rows;
      cfg_valid = 1'b1;
      do @(posedge clk); while (!cfg_ready);
      @(negedge clk);
      cfg_valid = 1'b0;
    end
  endtask

  task automatic send_activation(input integer token, input integer beat,
                                  input bit last);
    begin
      @(negedge clk);
      activation_data = make_activation(token, beat);
      activation_last = last;
      activation_valid = 1'b1;
      do @(posedge clk); while (!activation_ready);
      @(negedge clk);
      activation_valid = 1'b0;
    end
  endtask

  task automatic send_weight(input integer row, input integer beat, input integer zero,
                              input bit last);
    begin
      @(negedge clk);
      weight_packed = make_weight(row, beat);
      weight_zero = zero;
      weight_scale = 16'h3c00 + row*16 + beat;
      weight_last = last;
      weight_valid = 1'b1;
      do @(posedge clk); while (!weight_ready);
      @(negedge clk);
      weight_valid = 1'b0;
    end
  endtask

  task automatic consume_operand(input bit mode, input integer row, input integer token,
                                 input integer beat, input integer zero,
                                 input bit last, inout logic [63:0] checksum);
    integer stalls;
    begin
      stalls = $urandom_range(0, 3);
      repeat (stalls) begin
        @(negedge clk);
        operand_ready = 1'b0;
      end
      @(negedge clk);
      operand_ready = 1'b1;
      do @(posedge clk); while (!operand_valid);
      if (operand_activation !== make_activation(token, beat))
        $fatal(1, "activation mismatch mode=%0d token=%0d beat=%0d", mode, token, beat);
      if (operand_weight !== make_dequant(row, beat, beat + 1))
        $fatal(1, "dequant mismatch mode=%0d token=%0d beat=%0d", mode, token, beat);
      if (operand_scale !== (16'h3c00 + row*16 + beat))
        $fatal(1, "scale mismatch mode=%0d token=%0d beat=%0d", mode, token, beat);
      if (operand_row_index !== row || operand_token_index !== token || operand_beat_index !== beat)
        $fatal(1, "index mismatch mode=%0d token=%0d beat=%0d", mode, token, beat);
      if (operand_mode !== mode || operand_last !== last)
        $fatal(1, "sideband mismatch mode=%0d token=%0d beat=%0d", mode, token, beat);
      checksum = checksum ^ operand_activation[63:0] ^ operand_weight ^ operand_scale ^
                 {54'd0, operand_last, operand_row_index, operand_token_index,
                  operand_beat_index};
      @(negedge clk);
      operand_ready = 1'b0;
    end
  endtask

  task automatic run_case(input bit mode, input integer k, input integer beats,
                          input integer rows,
                          output logic [63:0] checksum);
    integer token, beat;
    begin
      checksum = 64'd0;
      send_cfg(mode, k, beats, rows);
      for (token = 0; token < k; token = token + 1)
        for (beat = 0; beat < beats; beat = beat + 1)
          send_activation(token, beat, token == k-1 && beat == beats-1);

      for (integer row = 0; row < rows; row = row + 1)
        for (beat = 0; beat < beats; beat = beat + 1) begin
          send_weight(row, beat, beat + 1, row == rows-1 && beat == beats-1);
          for (token = 0; token < k; token = token + 1)
            consume_operand(mode, row, token, beat, beat + 1,
                            row == rows-1 && token == k-1 && beat == beats-1, checksum);
        end

      wait (done);
      if (error) $fatal(1, "unexpected error code=%0d", error_code);
      if (accepted_weight_beats !== beats*rows)
        $fatal(1, "weight count %0d != %0d for K=%0d", accepted_weight_beats, beats*rows, k);
      @(posedge clk);
      wait (cfg_ready);
      $display("P2_CASE_GO mode=%0d K=%0d rows=%0d beats=%0d weight_beats=%0d checksum=%016x",
               mode, k, rows, beats, accepted_weight_beats, checksum);
    end
  endtask

  logic [63:0] gemv_k1_checksum, gemm_k1_checksum, ignored_checksum;
  initial begin
    cfg_valid = 1'b0;
    cfg_mode = 1'b0;
    cfg_k = '0;
    cfg_beats_per_row = '0;
    cfg_rows = '0;
    activation_valid = 1'b0;
    activation_data = '0;
    activation_last = 1'b0;
    weight_valid = 1'b0;
    weight_packed = '0;
    weight_zero = '0;
    weight_scale = '0;
    weight_last = 1'b0;
    operand_ready = 1'b0;

    repeat (5) @(posedge clk);
    reset = 1'b0;

    run_case(1'b0, 1, 3, 2, gemv_k1_checksum);
    run_case(1'b1, 1, 3, 2, gemm_k1_checksum);
    if (gemv_k1_checksum !== gemm_k1_checksum)
      $fatal(1, "K=1 GEMV/GEMM datapath mismatch");

    run_case(1'b1, 2, 3, 3, ignored_checksum);
    run_case(1'b1, 3, 5, 2, ignored_checksum);
    run_case(1'b1, 4, 5, 3, ignored_checksum);

    // GEMV descriptors must remain single-token commands.
    send_cfg(1'b0, 2, 3, 2);
    @(posedge clk);
    if (!error || error_code != 4'd6)
      $fatal(1, "invalid GEMV K was not rejected");

    $display("P2_W4_WEIGHT_REUSE_SCHEDULER_GO");
    $finish;
  end

  initial begin
    #200000;
    $fatal(1, "simulation timeout");
  end
endmodule
