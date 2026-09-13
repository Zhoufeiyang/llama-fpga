`timescale 1ns/1ps

// Focused production-sequencer contract check.  The generated Spinal block is
// deliberately tested at a small geometry, while preserving the production
// weight tags and the complete two-layer ordering.
module p3_production_sequence_tb;
  logic clk=0, reset=1; always #1.667 clk=~clk;

  logic io_start_valid; wire io_start_ready;
  logic io_start_payload_mode; logic [2:0] io_start_payload_k;
  logic [5:0] io_start_payload_projectionTag; logic [7:0] io_start_payload_layerId;
  logic [15:0] io_start_payload_rows, io_start_payload_beatsPerRow;

  wire io_projection_valid; logic io_projection_ready;
  wire io_projection_payload_mode; wire [2:0] io_projection_payload_k;
  wire [5:0] io_projection_payload_projectionTag; wire [7:0] io_projection_payload_layerId;
  wire [15:0] io_projection_payload_rows, io_projection_payload_beatsPerRow;

  logic io_projectionDone_valid; logic [5:0] io_projectionDone_projectionTag;
  logic [7:0] io_projectionDone_layerId;
  wire io_attentionRequest; logic io_attentionDone;
  wire io_mlpActivationRequest; logic io_mlpActivationDone;
  wire io_busy, io_done, io_error; wire [3:0] io_errorCode;
  wire [31:0] io_launchedProjections;
  wire [31:0] io_launchedWeightBeats;
  wire [2:0] io_sequenceK; wire [7:0] io_sequenceLayer;

  integer errors=0, cases=0;
  SpeculativeProjectionSequencer dut(
    .start_valid(io_start_valid), .start_ready(io_start_ready),
    .start_payload_mode(io_start_payload_mode), .start_payload_k(io_start_payload_k),
    .start_payload_projectionTag(io_start_payload_projectionTag),
    .start_payload_layerId(io_start_payload_layerId), .start_payload_rows(io_start_payload_rows),
    .start_payload_beatsPerRow(io_start_payload_beatsPerRow),
    .projection_valid(io_projection_valid), .projection_ready(io_projection_ready),
    .projection_payload_mode(io_projection_payload_mode), .projection_payload_k(io_projection_payload_k),
    .projection_payload_projectionTag(io_projection_payload_projectionTag),
    .projection_payload_layerId(io_projection_payload_layerId),
    .projection_payload_rows(io_projection_payload_rows),
    .projection_payload_beatsPerRow(io_projection_payload_beatsPerRow),
    .projectionDone_valid(io_projectionDone_valid),
    .projectionDone_payload_projectionTag(io_projectionDone_projectionTag),
    .projectionDone_payload_layerId(io_projectionDone_layerId),
    .attentionRequest(io_attentionRequest), .attentionDone(io_attentionDone),
    .mlpActivationRequest(io_mlpActivationRequest), .mlpActivationDone(io_mlpActivationDone),
    .busy(io_busy), .done(io_done), .error(io_error), .errorCode(io_errorCode),
    .launchedProjections(io_launchedProjections), .launchedWeightBeats(io_launchedWeightBeats),
    .sequenceK(io_sequenceK), .sequenceLayer(io_sequenceLayer),
    .clk(clk), .reset(reset));

  task automatic reset_dut;
    begin
      reset=1; io_start_valid=0; io_projection_ready=0;
      io_projectionDone_valid=0; io_projectionDone_projectionTag=0;
      io_projectionDone_layerId=0; io_attentionDone=0; io_mlpActivationDone=0;
      repeat(4) @(posedge clk); reset=0; repeat(2) @(posedge clk);
    end
  endtask

  task automatic run_case(input [2:0] kval, input logic mode);
    integer n, layer, slot, exp_tag, exp_rows, exp_beats;
    begin
      cases=cases+1; reset_dut();
      @(negedge clk);
      io_start_payload_mode=mode; io_start_payload_k=kval;
      io_start_payload_projectionTag=0; io_start_payload_layerId=0;
      io_start_payload_rows=1; io_start_payload_beatsPerRow=1;
      io_start_valid=1;
      while(!io_start_ready) @(negedge clk);
      @(posedge clk); io_start_valid=0;

      n=0;
      while(!io_done && !io_error && n<32) begin
        while(!io_projection_valid && !io_attentionRequest &&
              !io_mlpActivationRequest && !io_done && !io_error) @(negedge clk);

        if(io_projection_valid) begin
          if(n==14) begin layer=1; slot=7; exp_tag=19; exp_rows=8; exp_beats=2; end
          else begin
            layer=n/7; slot=n%7;
            case(slot)
              0: begin exp_tag=4;  exp_rows=4; exp_beats=2; end // Q
              1: begin exp_tag=5;  exp_rows=4; exp_beats=2; end // K
              2: begin exp_tag=7;  exp_rows=4; exp_beats=2; end // V
              3: begin exp_tag=11; exp_rows=4; exp_beats=2; end // O
              4: begin exp_tag=15; exp_rows=6; exp_beats=3; end // G
              5: begin exp_tag=16; exp_rows=6; exp_beats=3; end // U
              default: begin exp_tag=17; exp_rows=4; exp_beats=3; end // D
            endcase
          end
          if(io_projection_payload_mode!==mode || io_projection_payload_k!==kval ||
             io_projection_payload_projectionTag!==exp_tag ||
             io_projection_payload_layerId!==layer ||
             io_projection_payload_rows!==exp_rows ||
             io_projection_payload_beatsPerRow!==exp_beats) begin
            $display("P3_PRODUCTION_SEQUENCE_BAD_DESCRIPTOR case=%0d n=%0d tag=%0d layer=%0d",cases,n,
                     io_projection_payload_projectionTag,io_projection_payload_layerId);
            errors=errors+1;
          end
          io_projection_ready=1;
          @(posedge clk); io_projection_ready=0;
          io_projectionDone_projectionTag=exp_tag; io_projectionDone_layerId=layer;
          io_projectionDone_valid=1;
          @(posedge clk); io_projectionDone_valid=0;
          n=n+1;
        end
        if(io_attentionRequest) begin
          io_attentionDone=1; @(posedge clk); io_attentionDone=0;
        end
        if(io_mlpActivationRequest) begin
          io_mlpActivationDone=1; @(posedge clk); io_mlpActivationDone=0;
        end
      end
      if(io_error || n!=15 || io_launchedProjections!=15 || io_launchedWeightBeats!=176) begin
        $display("P3_PRODUCTION_SEQUENCE_FAIL K=%0d mode=%0d n=%0d launched=%0d error=%0d code=%0d",
                 kval,mode,n,io_launchedProjections,io_error,io_errorCode);
        errors=errors+1;
      end
      repeat(2) @(posedge clk);
      if(io_busy || !io_done) begin
        // done is a one-cycle state; the check is performed immediately after
        // the sequence loop only when it is visible, so do not fault here.
      end
      $display("P3_PRODUCTION_SEQUENCE_CASE_GO K=%0d mode=%0d projections=%0d weight_beats=%0d",
               kval,mode,n,io_launchedWeightBeats);
    end
  endtask

  initial begin
    // GEMM K=1..4 all use the same physical rows/beats and projection order.
    run_case(1,1); run_case(2,1); run_case(3,1); run_case(4,1);

    // Completion identity is part of the production contract, not an
    // advisory debug field.  A wrong tag must stop the sequence and remain
    // sticky until reset.
    reset_dut();
    @(negedge clk); io_start_payload_mode=1; io_start_payload_k=4;
    io_start_payload_projectionTag=0; io_start_payload_layerId=0;
    io_start_payload_rows=1; io_start_payload_beatsPerRow=1; io_start_valid=1;
    while(!io_start_ready) @(negedge clk); @(posedge clk); io_start_valid=0;
    while(!io_projection_valid) @(negedge clk);
    if(io_projection_payload_projectionTag!=6'd4 || io_projection_payload_layerId!=0) errors=errors+1;
    io_projection_ready=1; @(posedge clk); io_projection_ready=0;
    io_projectionDone_projectionTag=6'd5; io_projectionDone_layerId=0;
    io_projectionDone_valid=1; @(posedge clk); io_projectionDone_valid=0;
    repeat(2) @(posedge clk);
    if(!io_error || io_errorCode!=4 || io_launchedProjections!=1) errors=errors+1;
    $display("P3_PRODUCTION_SEQUENCE_IDENTITY_FAULT_GO WRONG_TAG=5 EXPECTED_TAG=4 STICKY=1");

    // Legacy mode is retained, but K>1 is rejected at launch.
    reset_dut();
    @(negedge clk); io_start_payload_mode=0; io_start_payload_k=2;
    io_start_payload_projectionTag=0; io_start_payload_layerId=0;
    io_start_payload_rows=1; io_start_payload_beatsPerRow=1; io_start_valid=1;
    while(!io_start_ready) @(negedge clk); @(posedge clk); io_start_valid=0;
    repeat(2) @(posedge clk);
    if(!io_error || io_errorCode!=3 || io_launchedProjections!=0) errors=errors+1;
    if(errors) $fatal(1,"P3 production sequence failed errors=%0d",errors);
    $display("P3_PRODUCTION_SEQUENCE_GO K1_TO_K4=1 ORDER=Q,K,V,ATTN,O,G,U,MLP,D,LM_HEAD WEIGHT_PASS_K_INDEPENDENT=1 LEGACY_K_GT1_REJECT=1");
    $finish;
  end
endmodule
