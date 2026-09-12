`timescale 1ns/1ps

// P5 transaction boundary for speculative KV writes. Candidate data is written
// directly behind the frozen committed pointer. Commit and rollback only move
// pointers; rejected bytes remain outside visible_tokens and may be overwritten.
module p5_tentative_kv_commit #(
  parameter integer MAX_K=4,
  parameter integer MAX_CONTEXT=4096,
  parameter integer DATA_W=128,
  parameter integer ADDR_W=40
) (
  input logic clk,input logic reset,
  input logic start_valid,output logic start_ready,
  input logic [2:0] start_k,input logic [15:0] start_committed_tokens,
  input logic [ADDR_W-1:0] kv_region_base,input logic [ADDR_W-1:0] kv_region_bytes,
  input logic [31:0] token_stride_bytes,

  input logic write_valid,output logic write_ready,input logic [2:0] write_slot,
  input logic [31:0] write_byte_offset,input logic [DATA_W-1:0] write_data,
  input logic [DATA_W/8-1:0] write_strobe,input logic write_last,
  output logic ddr_write_valid,input logic ddr_write_ready,
  output logic [ADDR_W-1:0] ddr_write_address,
  output logic [DATA_W-1:0] ddr_write_data,
  output logic [DATA_W/8-1:0] ddr_write_strobe,

  input logic resolve_valid,input logic [2:0] accepted_count,
  input logic abort,input logic timeout,
  output logic busy,output logic done,output logic rolled_back,
  output logic [15:0] spec_base_token,output logic [15:0] speculative_pointer,
  output logic [15:0] committed_tokens,output logic [15:0] visible_tokens,
  output logic error,output logic [3:0] error_code
);
  localparam integer BEAT_BYTES=DATA_W/8;
  localparam logic [3:0] ERR_K=1,ERR_CONTEXT=2,ERR_REGION=3,ERR_SLOT=4,
    ERR_OFFSET=5,ERR_RESOLVE=6,ERR_UNEXPECTED=7;
  typedef enum logic [1:0] {IDLE,ACTIVE,COMPLETE,FAULT} state_t;
  state_t state;
  logic [2:0] k_reg,next_slot;
  logic [ADDR_W:0] token_address_wide,beat_end_wide,region_end_wide;
  logic [ADDR_W:0] token_index_wide,start_end_token_wide;
  logic [ADDR_W+32:0] token_offset_full,beat_offset_full,start_span_full;
  logic address_valid;

  always_comb begin
    token_index_wide=spec_base_token+write_slot;
    start_end_token_wide=start_committed_tokens+start_k;
    token_offset_full=token_index_wide*token_stride_bytes;
    beat_offset_full=token_offset_full+write_byte_offset+BEAT_BYTES;
    start_span_full=start_end_token_wide*token_stride_bytes;
    token_address_wide={1'b0,kv_region_base}+token_offset_full+write_byte_offset;
    beat_end_wide=token_address_wide+BEAT_BYTES;
    region_end_wide={1'b0,kv_region_base}+kv_region_bytes;
    address_valid=(write_byte_offset+BEAT_BYTES<=token_stride_bytes)&&
      (beat_offset_full<={{33{1'b0}},kv_region_bytes})&&
      (token_address_wide>={1'b0,kv_region_base})&&(beat_end_wide<=region_end_wide);
  end

  assign start_ready=(state==IDLE);
  assign busy=(state==ACTIVE);
  assign done=(state==COMPLETE);
  assign error=(state==FAULT);
  assign visible_tokens=committed_tokens;
  assign ddr_write_valid=(state==ACTIVE)&&write_valid&&(write_slot==next_slot)&&address_valid;
  assign write_ready=(state==ACTIVE)&&(write_slot==next_slot)&&address_valid&&ddr_write_ready;
  assign ddr_write_address=token_address_wide[ADDR_W-1:0];
  assign ddr_write_data=write_data;
  assign ddr_write_strobe=write_strobe;

  always_ff @(posedge clk) begin
    if(reset) begin
      state<=IDLE;k_reg<=0;next_slot<=0;spec_base_token<=0;
      speculative_pointer<=0;committed_tokens<=0;rolled_back<=0;error_code<=0;
    end else case(state)
      IDLE: begin
        rolled_back<=0;
        if(start_valid) begin
          if(start_k<1||start_k>MAX_K) begin state<=FAULT;error_code<=ERR_K;end
          else if(({1'b0,start_committed_tokens}+start_k)>MAX_CONTEXT) begin state<=FAULT;error_code<=ERR_CONTEXT;end
          else if(token_stride_bytes<BEAT_BYTES||
            start_span_full>{{33{1'b0}},kv_region_bytes}) begin
            state<=FAULT;error_code<=ERR_REGION;
          end else begin
            state<=ACTIVE;k_reg<=start_k;next_slot<=0;
            spec_base_token<=start_committed_tokens;
            speculative_pointer<=start_committed_tokens;
            committed_tokens<=start_committed_tokens;error_code<=0;
          end
        end
      end
      ACTIVE: begin
        if(abort||timeout) begin
          speculative_pointer<=committed_tokens;rolled_back<=1;state<=COMPLETE;
        end else if(resolve_valid) begin
          if(next_slot!=k_reg||accepted_count>k_reg) begin state<=FAULT;error_code<=ERR_RESOLVE;end
          else begin
            committed_tokens<=spec_base_token+accepted_count;
            speculative_pointer<=spec_base_token+accepted_count;
            rolled_back<=(accepted_count!=k_reg);state<=COMPLETE;
          end
        end else if(write_valid&&(write_slot!=next_slot)) begin state<=FAULT;error_code<=ERR_SLOT;
        end else if(write_valid&&!address_valid) begin state<=FAULT;error_code<=ERR_OFFSET;
        end else if(write_valid&&write_ready&&write_last) begin
          next_slot<=next_slot+1'b1;speculative_pointer<=spec_base_token+next_slot+1'b1;
        end
      end
      COMPLETE: state<=IDLE;
      FAULT: begin
        speculative_pointer<=committed_tokens;
        if(abort||timeout) begin rolled_back<=1;state<=COMPLETE;end
      end
      default: begin state<=FAULT;error_code<=ERR_UNEXPECTED;end
    endcase
  end
endmodule
