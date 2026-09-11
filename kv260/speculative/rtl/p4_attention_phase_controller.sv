`timescale 1ns/1ps

// P4-B controller around the existing QKMul, SerialSafeSoftmax and V-AXPY
// kernels.  For each speculative query it performs a causal QK tile sweep,
// launches softmax for exactly committed+q+1 scores, then repeats the same
// causal tile sweep for V accumulation.
module p4_attention_phase_controller #(
  parameter integer TILE_TOKENS=64,
  parameter integer MAX_CONTEXT=4096,
  parameter integer MAX_K=4
) (
  input logic clk,input logic reset,
  input logic start_valid,output logic start_ready,input logic [2:0] start_k,
  input logic [15:0] start_committed_tokens,
  output logic tile_valid,input logic tile_ready,
  output logic tile_phase, // 0: QK, 1: V weighted accumulation
  output logic tile_source,output logic tile_buffer,
  output logic [2:0] tile_query,output logic [15:0] tile_start_token,
  output logic [15:0] tile_token_count,output logic tile_last,
  input logic tile_done,input logic tile_done_phase,input logic tile_done_source,
  input logic [2:0] tile_done_query,input logic [15:0] tile_done_start_token,
  output logic softmax_valid,input logic softmax_ready,
  output logic [2:0] softmax_query,output logic [15:0] softmax_token_count,
  input logic softmax_done,input logic [2:0] softmax_done_query,
  output logic query_done,output logic [2:0] query_done_index,
  output logic busy,output logic done,output logic error,output logic [3:0] error_code
);
  localparam logic [3:0] ERR_K=1,ERR_CONTEXT=2,ERR_TILE=3,ERR_SOFTMAX=4,ERR_UNEXPECTED=5;
  typedef enum logic [2:0] {IDLE,ISSUE_TILE,WAIT_TILE,ISSUE_SOFTMAX,WAIT_SOFTMAX,COMPLETE,FAULT} state_t;
  state_t state;
  logic [2:0] k_reg,query_reg;
  logic [15:0] committed_reg,offset_reg;
  logic phase_reg,source_reg,buffer_reg;
  logic inflight_phase,inflight_source;
  logic [2:0] inflight_query;
  logic [15:0] inflight_start;
  wire [15:0] remaining=committed_reg-offset_reg;
  wire [15:0] committed_count=(remaining>TILE_TOKENS)?TILE_TOKENS:remaining;
  wire committed_last=(remaining<=TILE_TOKENS);
  wire [16:0] final_tokens={1'b0,start_committed_tokens}+start_k;

  assign start_ready=(state==IDLE);
  assign busy=(state!=IDLE)&&(state!=COMPLETE)&&(state!=FAULT);
  assign done=(state==COMPLETE); assign error=(state==FAULT);
  assign tile_valid=(state==ISSUE_TILE);
  assign tile_phase=phase_reg; assign tile_source=source_reg; assign tile_buffer=buffer_reg;
  assign tile_query=query_reg; assign tile_start_token=source_reg?16'd0:offset_reg;
  assign tile_token_count=source_reg?({13'd0,query_reg}+1'b1):committed_count;
  assign tile_last=source_reg;
  assign softmax_valid=(state==ISSUE_SOFTMAX); assign softmax_query=query_reg;
  assign softmax_token_count=committed_reg+query_reg+1'b1;
  assign query_done=(state==WAIT_TILE)&&tile_done&&inflight_phase&&inflight_source&&
    tile_done_phase==inflight_phase&&tile_done_source==inflight_source&&
    tile_done_query==inflight_query&&tile_done_start_token==inflight_start;
  assign query_done_index=query_reg;

  always_ff @(posedge clk) begin
    if(reset) begin
      state<=IDLE;k_reg<=0;query_reg<=0;committed_reg<=0;offset_reg<=0;
      phase_reg<=0;source_reg<=0;buffer_reg<=0;inflight_phase<=0;
      inflight_source<=0;inflight_query<=0;inflight_start<=0;error_code<=0;
    end else if((tile_done&&state!=WAIT_TILE)||(softmax_done&&state!=WAIT_SOFTMAX)) begin
      state<=FAULT;error_code<=ERR_UNEXPECTED;
    end else case(state)
      IDLE: if(start_valid) begin
        if(start_k<1||start_k>MAX_K) begin state<=FAULT;error_code<=ERR_K;end
        else if(final_tokens>MAX_CONTEXT) begin state<=FAULT;error_code<=ERR_CONTEXT;end
        else begin
          k_reg<=start_k;query_reg<=0;committed_reg<=start_committed_tokens;
          offset_reg<=0;phase_reg<=0;source_reg<=(start_committed_tokens==0);buffer_reg<=0;
          state<=ISSUE_TILE;
        end
      end
      ISSUE_TILE: if(tile_valid&&tile_ready) begin
        inflight_phase<=phase_reg;inflight_source<=source_reg;
        inflight_query<=query_reg;inflight_start<=source_reg?16'd0:offset_reg;
        state<=WAIT_TILE;
      end
      WAIT_TILE: if(tile_done) begin
        if(tile_done_phase!=inflight_phase||tile_done_source!=inflight_source||
           tile_done_query!=inflight_query||tile_done_start_token!=inflight_start) begin
          state<=FAULT;error_code<=ERR_TILE;
        end else begin
          buffer_reg<=~buffer_reg;
          if(!inflight_source) begin
            if(committed_last) begin source_reg<=1;offset_reg<=0;end
            else offset_reg<=offset_reg+TILE_TOKENS;
            state<=ISSUE_TILE;
          end else if(!inflight_phase) state<=ISSUE_SOFTMAX;
          else if(query_reg==k_reg-1'b1) state<=COMPLETE;
          else begin
            query_reg<=query_reg+1'b1;phase_reg<=0;offset_reg<=0;
            source_reg<=(committed_reg==0);state<=ISSUE_TILE;
          end
        end
      end
      ISSUE_SOFTMAX: if(softmax_valid&&softmax_ready) state<=WAIT_SOFTMAX;
      WAIT_SOFTMAX: if(softmax_done) begin
        if(softmax_done_query!=query_reg) begin state<=FAULT;error_code<=ERR_SOFTMAX;end
        else begin phase_reg<=1;offset_reg<=0;source_reg<=(committed_reg==0);state<=ISSUE_TILE;end
      end
      COMPLETE: state<=IDLE;
      FAULT: state<=FAULT;
      default: begin state<=FAULT;error_code<=ERR_UNEXPECTED;end
    endcase
  end
endmodule
