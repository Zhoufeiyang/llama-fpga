`timescale 1ns/1ps

// P4-A causal tile sequencer. Committed KV is fetched from DDR in fixed-size
// tiles; tentative KV is read from the on-chip candidate buffer only through q.
module p4_causal_kv_tile_scheduler #(
  parameter integer TILE_TOKENS=64,
  parameter integer MAX_CONTEXT=4096,
  parameter integer MAX_K=4
) (
  input logic clk,input logic reset,
  input logic start_valid,output logic start_ready,input logic [2:0] start_k,
  input logic [15:0] start_committed_tokens,
  output logic request_valid,input logic request_ready,
  output logic request_source, // 0: DDR committed KV, 1: tentative on-chip KV
  output logic request_buffer, // ping/pong BRAM/URAM tile buffer
  output logic [2:0] request_query,
  output logic [15:0] request_start_token,
  output logic [15:0] request_token_count,
  output logic request_last_for_query,
  input logic response_done,input logic response_source,
  input logic [2:0] response_query,input logic [15:0] response_start_token,
  output logic query_done,output logic [2:0] query_done_index,
  output logic busy,output logic done,output logic error,output logic [3:0] error_code,
  output logic [31:0] ddr_tile_requests,output logic [31:0] tentative_requests
);
  localparam logic [3:0] ERR_K=1,ERR_CONTEXT=2,ERR_RESPONSE=3,ERR_UNEXPECTED=4;
  typedef enum logic [2:0] {IDLE,ISSUE_COMMITTED,ISSUE_TENTATIVE,WAIT_RESPONSE,COMPLETE,FAULT} state_t;
  state_t state,return_state;
  logic [2:0] k_reg,query_reg;
  logic [15:0] committed_reg,committed_offset;
  logic inflight_source,inflight_buffer;
  logic [2:0] inflight_query;
  logic [15:0] inflight_start;
  wire [15:0] committed_remaining=committed_reg-committed_offset;
  wire [15:0] committed_count=(committed_remaining>TILE_TOKENS)?TILE_TOKENS:committed_remaining;
  wire [16:0] requested_final_tokens={1'b0,start_committed_tokens}+start_k;

  assign start_ready=(state==IDLE);
  assign busy=(state!=IDLE)&&(state!=COMPLETE)&&(state!=FAULT);
  assign done=(state==COMPLETE); assign error=(state==FAULT);
  assign request_valid=(state==ISSUE_COMMITTED)||(state==ISSUE_TENTATIVE);
  assign request_source=(state==ISSUE_TENTATIVE);
  assign request_buffer=inflight_buffer;
  assign request_query=query_reg;
  assign request_start_token=(state==ISSUE_TENTATIVE)?16'd0:committed_offset;
  assign request_token_count=(state==ISSUE_TENTATIVE)?({13'd0,query_reg}+1'b1):committed_count;
  assign request_last_for_query=(state==ISSUE_TENTATIVE);
  assign query_done=response_done&&(state==WAIT_RESPONSE)&&inflight_source&&
    response_source==inflight_source&&response_query==inflight_query&&response_start_token==inflight_start;
  assign query_done_index=inflight_query;

  always_ff @(posedge clk) begin
    if(reset) begin
      state<=IDLE; return_state<=IDLE; k_reg<=0; query_reg<=0; committed_reg<=0;
      committed_offset<=0; inflight_source<=0; inflight_buffer<=0; inflight_query<=0;
      inflight_start<=0; error_code<=0; ddr_tile_requests<=0; tentative_requests<=0;
    end else if(response_done && state!=WAIT_RESPONSE) begin
      state<=FAULT; error_code<=ERR_UNEXPECTED;
    end else case(state)
      IDLE: if(start_valid&&start_ready) begin
        if(start_k<1||start_k>MAX_K) begin state<=FAULT; error_code<=ERR_K; end
        else if(requested_final_tokens>MAX_CONTEXT) begin state<=FAULT; error_code<=ERR_CONTEXT; end
        else begin
          k_reg<=start_k; committed_reg<=start_committed_tokens; query_reg<=0; committed_offset<=0;
          inflight_buffer<=0; ddr_tile_requests<=0; tentative_requests<=0;
          state<=(start_committed_tokens==0)?ISSUE_TENTATIVE:ISSUE_COMMITTED;
        end
      end
      ISSUE_COMMITTED: if(request_valid&&request_ready) begin
        inflight_source<=0; inflight_query<=query_reg; inflight_start<=committed_offset;
        return_state<=(committed_offset+committed_count==committed_reg)?ISSUE_TENTATIVE:ISSUE_COMMITTED;
        committed_offset<=committed_offset+committed_count; ddr_tile_requests<=ddr_tile_requests+1'b1;
        state<=WAIT_RESPONSE;
      end
      ISSUE_TENTATIVE: if(request_valid&&request_ready) begin
        inflight_source<=1; inflight_query<=query_reg; inflight_start<=0;
        return_state<=ISSUE_TENTATIVE; tentative_requests<=tentative_requests+1'b1; state<=WAIT_RESPONSE;
      end
      WAIT_RESPONSE: if(response_done) begin
        if(response_source!=inflight_source||response_query!=inflight_query||response_start_token!=inflight_start) begin
          state<=FAULT; error_code<=ERR_RESPONSE;
        end else begin
          inflight_buffer<=~inflight_buffer;
          if(inflight_source) begin
            if(query_reg==k_reg-1'b1) state<=COMPLETE;
            else begin query_reg<=query_reg+1'b1; committed_offset<=0; state<=(committed_reg==0)?ISSUE_TENTATIVE:ISSUE_COMMITTED; end
          end else state<=return_state;
        end
      end
      COMPLETE: state<=IDLE;
      FAULT: state<=FAULT;
      default: begin state<=FAULT; error_code<=ERR_UNEXPECTED; end
    endcase
  end
endmodule
