`timescale 1ns/1ps

// Focused xsim smoke test for the generated Spinal P4-B controller.  The
// arithmetic streams are represented by their real completion boundaries:
// each accepted QK tile returns after token_count score events, and each
// softmax request returns on its output last event.  Metadata is echoed from
// the request, including the ping/pong buffer bit.
module p4_attention_phase_controller_spinal_tb;
  logic clk=0, reset=1;
  logic start_valid, start_ready;
  logic [2:0] start_k;
  logic [12:0] start_committedTokens;
  logic tile_valid, tile_ready;
  logic tile_phase, tile_source, tile_fetch, tile_buffer;
  logic [1:0] tile_query;
  logic [12:0] tile_startToken, tile_tokenCount;
  logic tile_last;
  logic tileDone_valid, tileDone_phase, tileDone_source, tileDone_buffer;
  logic [1:0] tileDone_query;
  logic [12:0] tileDone_startToken;
  logic softmax_valid, softmax_ready;
  logic [1:0] softmax_query;
  logic [12:0] softmax_tokenCount;
  logic softmaxDone_valid;
  logic [1:0] softmaxDone_query;
  logic completionError, queryDone_valid, vBatchDone, vBatchPending;
  logic auto_complete;
  logic [1:0] queryDone_payload;
  logic busy, done, error;
  logic [3:0] errorCode;
  integer qk_tiles, v_tiles, qk_ddr_reads, v_ddr_reads, softmaxes, queries, timeout;

  always #5 clk = ~clk;

  P4AttentionPhaseController dut (
    .io_start_valid(start_valid), .io_start_ready(start_ready),
    .io_start_payload_k(start_k),
    .io_start_payload_committedTokens(start_committedTokens),
    .io_tile_valid(tile_valid), .io_tile_ready(tile_ready),
    .io_tile_payload_phase(tile_phase), .io_tile_payload_source(tile_source),
    .io_tile_payload_fetch(tile_fetch),
    .io_tile_payload_buffer(tile_buffer), .io_tile_payload_query(tile_query),
    .io_tile_payload_startToken(tile_startToken),
    .io_tile_payload_tokenCount(tile_tokenCount), .io_tile_payload_last(tile_last),
    .io_tileDone_valid(tileDone_valid), .io_tileDone_payload_phase(tileDone_phase),
    .io_tileDone_payload_source(tileDone_source), .io_tileDone_payload_buffer(tileDone_buffer),
    .io_tileDone_payload_query(tileDone_query),
    .io_tileDone_payload_startToken(tileDone_startToken),
    .io_softmax_valid(softmax_valid), .io_softmax_ready(softmax_ready),
    .io_softmax_payload_query(softmax_query),
    .io_softmax_payload_tokenCount(softmax_tokenCount),
    .io_softmaxDone_valid(softmaxDone_valid),
    .io_softmaxDone_payload_query(softmaxDone_query),
    .io_vBatchDone(vBatchDone),
    .io_completionError(completionError), .io_queryDone_valid(queryDone_valid),
    .io_queryDone_payload(queryDone_payload), .io_busy(busy), .io_done(done),
    .io_error(error), .io_errorCode(errorCode), .clk(clk), .reset(reset)
  );

  always @(posedge clk) begin
    tileDone_valid <= 0;
    softmaxDone_valid <= 0;
    vBatchDone <= vBatchPending;
    vBatchPending <= 0;
    if(auto_complete && tile_valid && tile_ready) begin
      if(tile_source && tile_startToken != start_committedTokens)
        $fatal(1, "tentative tile is not based at committed prefix");
      tileDone_valid <= 1;
      tileDone_phase <= tile_phase;
      tileDone_source <= tile_source;
      tileDone_buffer <= tile_buffer;
      tileDone_query <= tile_query;
      tileDone_startToken <= tile_startToken;
      if(tile_phase) v_tiles <= v_tiles + 1; else qk_tiles <= qk_tiles + 1;
      if(tile_phase && tile_source && tile_query == start_k-1)
        vBatchPending <= 1;
      if(!tile_source && tile_fetch) begin
        if(tile_phase) v_ddr_reads <= v_ddr_reads + 1;
        else qk_ddr_reads <= qk_ddr_reads + 1;
      end
    end
    if(auto_complete && softmax_valid && softmax_ready) begin
      softmaxDone_valid <= 1;
      softmaxDone_query <= softmax_query;
      softmaxes <= softmaxes + 1;
      if(softmax_tokenCount != 13'd131 + softmax_query)
        $fatal(1, "wrong inclusive softmax length");
    end
    if(queryDone_valid) queries <= queries + 1;
  end

  task run_case(input integer kval);
    begin
      qk_tiles=0; v_tiles=0; qk_ddr_reads=0; v_ddr_reads=0;
      softmaxes=0; queries=0; timeout=0;
      @(posedge clk); start_k=kval; start_committedTokens=130; start_valid=1;
      @(posedge clk); start_valid=0;
      while(!done && !error && timeout < 10000) begin @(posedge clk); timeout=timeout+1; end
      if(error || timeout >= 10000)
        $fatal(1, "P4 Spinal case fault K=%0d code=%0d busy=%0d qk=%0d v=%0d sm=%0d q=%0d tile_v=%0d sm_v=%0d",
          kval, errorCode, busy, qk_tiles, v_tiles, softmaxes, queries, tile_valid, softmax_valid);
      if(qk_tiles != 4*kval || v_tiles != 4*kval || softmaxes != kval || queries != kval)
        $fatal(1, "P4 Spinal count mismatch K=%0d qk=%0d v=%0d sm=%0d q=%0d", kval, qk_tiles, v_tiles, softmaxes, queries);
      if(qk_ddr_reads != 3 || v_ddr_reads != 3)
        $fatal(1, "P4 DDR tile reuse failed K=%0d qk_reads=%0d v_reads=%0d", kval, qk_ddr_reads, v_ddr_reads);
      $display("P4B_TILE_REUSE_K%0d qk_ops=%0d v_ops=%0d qk_ddr=%0d v_ddr=%0d", kval, qk_tiles, v_tiles, qk_ddr_reads, v_ddr_reads);
      @(posedge clk);
    end
  endtask

  initial begin
    start_valid=0; start_k=0; start_committedTokens=0; tile_ready=1; softmax_ready=1;
    tileDone_valid=0; softmaxDone_valid=0; completionError=0; vBatchDone=0; vBatchPending=0;
    qk_tiles=0; v_tiles=0; qk_ddr_reads=0; v_ddr_reads=0;
    softmaxes=0; queries=0;auto_complete=1;
    repeat(3) @(posedge clk); reset=0;
    run_case(1); run_case(2); run_case(3); run_case(4);
    // Wrong buffer identity must be rejected by the real metadata checker.
    auto_complete=0;
    @(posedge clk); start_k=1; start_committedTokens=1; start_valid=1;
    @(posedge clk); start_valid=0; wait(tile_valid);
    @(posedge clk); // accept the tile and enter waitTile before completing it
    @(negedge clk); tileDone_valid=1; tileDone_phase=tile_phase;
    tileDone_source=tile_source; tileDone_buffer=~tile_buffer;
    tileDone_query=tile_query; tileDone_startToken=tile_startToken;
    @(posedge clk); #1; tileDone_valid=0;
    if(!error || errorCode != 3) $fatal(1, "wrong buffer was not rejected");
    $display("P4_SPINAL_CONTROLLER_GO K1_TO_K4=1 DDR_TILE_READS_K_INDEPENDENT=1 V_BATCH_TERMINAL=1 BUFFER_FAULT=1");
    $finish;
  end
endmodule
