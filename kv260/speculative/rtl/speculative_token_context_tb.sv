`timescale 1ns/1ps
module speculative_token_context_tb;
  logic clk=0, reset=1;
  always #5 clk=~clk;
  logic io_accepted, io_speculativeEnable;
  logic [5:0] io_tokenUser;
  wire [5:0] io_routeTag;
  wire [1:0] io_acceptedQuery, io_activeQuery;
  integer errors;

  SpeculativeTokenContext dut(.*);

  initial begin
    errors=0; io_accepted=0; io_speculativeEnable=0; io_tokenUser=0;
    repeat(3) @(posedge clk); reset=0;
    // q=2 decode token: route tag must remain legacy value 2.
    @(negedge clk); io_speculativeEnable=1; io_tokenUser=6'h22; #1;
    if(io_routeTag!==6'h02 || io_acceptedQuery!==2) begin $display("CTX_FAIL_DECODE tag=%h accepted_q=%0d",io_routeTag,io_acceptedQuery); errors=errors+1; end
    io_accepted=1; @(posedge clk); #1; io_accepted=0;
    if(io_activeQuery!==2 || io_routeTag!==6'h02) begin $display("CTX_FAIL_LATCH active=%0d tag=%h",io_activeQuery,io_routeTag); errors=errors+1; end
    // Upstream may already present q=3, but active q cannot move without the
    // command-generator acceptance boundary.
    @(negedge clk); io_tokenUser=6'h32; repeat(3) @(posedge clk);
    if(io_activeQuery!==2 || io_acceptedQuery!==3 || io_routeTag!==6'h02) begin $display("CTX_FAIL_HOLD active=%0d accepted=%0d tag=%h",io_activeQuery,io_acceptedQuery,io_routeTag); errors=errors+1; end
    @(negedge clk); io_accepted=1; @(posedge clk); #1; io_accepted=0;
    if(io_activeQuery!==3) begin $display("CTX_FAIL_ADVANCE active=%0d",io_activeQuery); errors=errors+1; end
    // A legacy token never overwrites speculative q state.
    @(negedge clk); io_speculativeEnable=0; io_tokenUser=6'h01; io_accepted=1;
    @(posedge clk); #1; io_accepted=0;
    if(io_activeQuery!==3 || io_routeTag!==6'h01) begin $display("CTX_FAIL_LEGACY active=%0d tag=%h",io_activeQuery,io_routeTag); errors=errors+1; end
    if(errors) $fatal(1,"SpeculativeTokenContext failed errors=%0d",errors);
    $display("SPECULATIVE_TOKEN_CONTEXT_GO Q_HELD_UNTIL_ACCEPT=1 ROUTE_TAG_COMPATIBLE=1 LEGACY_NONDESTRUCTIVE=1");
    $finish;
  end
endmodule
