`timescale 1ns/1ps

// P6 greedy speculative acceptance. Target predictions arrive as g[0..K].
// The first candidate mismatch emits g[j] and accepts j candidates; if all K
// candidates match, g[K] is emitted as the bonus token.
module p6_greedy_acceptance #(
  parameter integer TOKEN_W=16,
  parameter integer MAX_K=4
) (
  input logic clk,input logic reset,
  input logic start_valid,output logic start_ready,input logic [2:0]start_k,
  input logic [TOKEN_W*MAX_K-1:0]start_candidates,
  input logic target_valid,output logic target_ready,input logic[2:0]target_index,
  input logic[TOKEN_W-1:0]target_token,
  input logic abort,input logic timeout,
  output logic result_valid,input logic result_ready,
  output logic[2:0]accepted_count,output logic[TOKEN_W-1:0]emitted_token,
  output logic bonus_token,output logic[2:0]mismatch_index,
  output logic commit_valid,output logic[2:0]commit_delta,
  output logic rollback_required,output logic aborted,
  output logic busy,output logic error,output logic[3:0]error_code
);
  localparam logic[3:0]ERR_K=1,ERR_INDEX=2,ERR_UNEXPECTED=3;
  typedef enum logic[1:0]{IDLE,COLLECT,RESULT,FAULT}state_t;
  state_t state;
  logic[2:0]k_reg,expected_index;
  logic[TOKEN_W-1:0]candidates[0:MAX_K-1];
  logic[TOKEN_W-1:0]targets[0:MAX_K];
  integer i,j;
  logic mismatch_found;
  logic[2:0]first_mismatch;

  always_comb begin
    mismatch_found=0;first_mismatch=k_reg;
    for(j=0;j<MAX_K;j=j+1)begin
      if(j<k_reg&&!mismatch_found&&candidates[j]!=targets[j])begin
        mismatch_found=1;first_mismatch=j;
      end
    end
  end

  assign start_ready=(state==IDLE);
  assign target_ready=(state==COLLECT)&&(target_index==expected_index);
  assign result_valid=(state==RESULT);
  assign commit_valid=result_valid&&!aborted;
  assign commit_delta=accepted_count;
  assign busy=(state==COLLECT);
  assign error=(state==FAULT);

  always_ff @(posedge clk)begin
    if(reset)begin
      state<=IDLE;k_reg<=0;expected_index<=0;accepted_count<=0;emitted_token<=0;
      bonus_token<=0;mismatch_index<=0;rollback_required<=0;aborted<=0;error_code<=0;
      for(i=0;i<MAX_K;i=i+1)candidates[i]<=0;
      for(i=0;i<=MAX_K;i=i+1)targets[i]<=0;
    end else case(state)
      IDLE:if(start_valid)begin
        if(start_k<1||start_k>MAX_K)begin state<=FAULT;error_code<=ERR_K;end
        else begin
          k_reg<=start_k;expected_index<=0;aborted<=0;rollback_required<=0;error_code<=0;
          for(i=0;i<MAX_K;i=i+1)candidates[i]<=start_candidates[i*TOKEN_W+:TOKEN_W];
          state<=COLLECT;
        end
      end
      COLLECT:begin
        if(abort||timeout)begin
          accepted_count<=0;emitted_token<=0;bonus_token<=0;mismatch_index<=0;
          rollback_required<=1;aborted<=1;state<=RESULT;
        end else if(target_valid&&target_index!=expected_index)begin state<=FAULT;error_code<=ERR_INDEX;
        end else if(target_valid&&target_ready)begin
          targets[target_index]<=target_token;
          if(target_index==k_reg)begin
            if(mismatch_found)begin
              accepted_count<=first_mismatch;emitted_token<=targets[first_mismatch];
              bonus_token<=0;mismatch_index<=first_mismatch;rollback_required<=1;
            end else begin
              accepted_count<=k_reg;emitted_token<=target_token;
              bonus_token<=1;mismatch_index<=k_reg;rollback_required<=0;
            end
            state<=RESULT;
          end else expected_index<=expected_index+1'b1;
        end
      end
      RESULT:if(result_valid&&result_ready)state<=IDLE;
      FAULT:if(abort)begin
        accepted_count<=0;emitted_token<=0;bonus_token<=0;rollback_required<=1;aborted<=1;state<=RESULT;
      end
      default:begin state<=FAULT;error_code<=ERR_UNEXPECTED;end
    endcase
  end
endmodule
