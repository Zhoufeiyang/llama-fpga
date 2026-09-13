`timescale 1ns/1ps
module speculative_token_ingress_tb;
  logic clk=0, reset=1;
  always #5 clk=~clk;

  logic io_enable, io_start;
  logic [2:0] io_k;
  logic [15:0] io_candidateIds_0, io_candidateIds_1, io_candidateIds_2, io_candidateIds_3;
  wire io_tokens_valid;
  logic io_tokens_ready;
  wire [15:0] io_tokens_tdata;
  wire [5:0] io_tokens_tuser;
  wire io_busy, io_fault;
  integer errors, seen;

  SpeculativeTokenIngress dut(.*);

  task automatic launch(input [2:0] k);
    begin
      @(negedge clk); io_k=k; io_start=1;
      @(negedge clk); io_start=0;
    end
  endtask

  initial begin
    errors=0; seen=0; io_enable=0; io_start=0; io_k=0; io_tokens_ready=1;
    io_candidateIds_0=16'h1111; io_candidateIds_1=16'h2222;
    io_candidateIds_2=16'h3333; io_candidateIds_3=16'h4444;
    repeat(3) @(posedge clk); reset=0;
    if(io_tokens_valid || io_busy) errors=errors+1;

    // K=3: data must remain stable while ready is low, then drain in order.
    io_enable=1; io_tokens_ready=0; launch(3);
    repeat(2) @(posedge clk);
    if(!io_tokens_valid || io_tokens_tdata!==16'h1111 || io_tokens_tuser!==6'h02) errors=errors+1;
    repeat(2) @(posedge clk);
    if(!io_tokens_valid || io_tokens_tdata!==16'h1111) errors=errors+1;
    io_tokens_ready=1;
    while(seen<3) begin
      @(posedge clk);
      if(io_tokens_valid && io_tokens_ready) begin
        case(seen)
          0: if(io_tokens_tdata!==16'h1111) errors=errors+1;
          1: if(io_tokens_tdata!==16'h2222) errors=errors+1;
          2: if(io_tokens_tdata!==16'h3333) errors=errors+1;
        endcase
        if(io_tokens_tuser !== ({seen[1:0],4'h2})) errors=errors+1;
        seen=seen+1;
      end
    end
    @(posedge clk); if(io_tokens_valid || io_busy) errors=errors+1;

    // Disable is quiet and cancels a partially-drained batch.
    io_tokens_ready=0; launch(2); repeat(1) @(posedge clk); io_enable=0;
    repeat(2) @(posedge clk); if(io_tokens_valid || io_busy) errors=errors+1;

    // Invalid K is rejected without producing a token.
    io_enable=1; launch(0); repeat(2) @(posedge clk);
    if(io_tokens_valid || io_busy || !io_fault) errors=errors+1;
    if(errors) $fatal(1,"SpeculativeTokenIngress failed with %0d errors",errors);
    $display("SPECULATIVE_TOKEN_INGRESS_GO ordered=3 q_metadata=1 backpressure=1 disable_quiet=1 invalid_k=1");
    $finish;
  end
endmodule
