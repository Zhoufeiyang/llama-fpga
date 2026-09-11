`timescale 1ns/1ps

// P3 transformer-level scheduler for the shared P2 projection engine.
// It emits one descriptor per target weight matrix, independent of K.
module p3_transformer_projection_scheduler #(
  parameter integer MAX_LAYERS = 32
) (
  input  logic        clk,
  input  logic        reset,
  input  logic        start_valid,
  output logic        start_ready,
  input  logic        start_mode,       // 0: legacy decode, 1: verification
  input  logic [2:0]  start_k,
  input  logic [5:0]  start_layers,

  output logic        projection_valid,
  input  logic        projection_ready,
  output logic        projection_mode,
  output logic [2:0]  projection_k,
  output logic [5:0]  projection_tag,
  output logic [5:0]  projection_layer,
  output logic [15:0] projection_rows,
  output logic [15:0] projection_beats_per_row,
  output logic        projection_last,

  // Completion from P2 must identify the descriptor being retired.
  input  logic        projection_done,
  input  logic [5:0]  projection_done_tag,
  input  logic [5:0]  projection_done_layer,

  // These barriers are owned by P4 and the elementwise MLP datapath.
  output logic        attention_request,
  input  logic        attention_done,
  output logic        mlp_activation_request,
  input  logic        mlp_activation_done,

  output logic        busy,
  output logic        done,
  output logic        error,
  output logic [3:0]  error_code,
  output logic [31:0] launched_projections
);
  localparam logic [5:0] TAG_Q=6'd5, TAG_K=6'd6, TAG_V=6'd7;
  localparam logic [5:0] TAG_O=6'd16, TAG_G=6'd22, TAG_U=6'd24;
  localparam logic [5:0] TAG_D=6'd31, TAG_LOGITS=6'd35;
  localparam logic [3:0] ERR_NONE=0, ERR_K=1, ERR_LAYERS=2,
                         ERR_LEGACY_K=3, ERR_DONE_TAG=4, ERR_DONE_LAYER=5;

  typedef enum logic [3:0] {
    IDLE, ISSUE_Q, ISSUE_K, ISSUE_V, WAIT_ATTN,
    ISSUE_O, ISSUE_G, ISSUE_U, WAIT_MLP, ISSUE_D,
    ISSUE_LOGITS, COMPLETE, FAULT
  } state_t;
  state_t state;
  logic mode_reg;
  logic [2:0] k_reg;
  logic [5:0] layers_reg, layer_reg;
  logic descriptor_inflight;

  wire start_fire=start_valid && start_ready;
  wire descriptor_fire=projection_valid && projection_ready;
  wire final_layer=(layer_reg == layers_reg-1'b1);

  assign start_ready=(state==IDLE);
  assign busy=(state!=IDLE)&&(state!=COMPLETE)&&(state!=FAULT);
  assign done=(state==COMPLETE);
  assign error=(state==FAULT);
  assign projection_mode=mode_reg;
  assign projection_k=k_reg;
  assign projection_layer=layer_reg;
  assign projection_valid=!descriptor_inflight &&
    ((state==ISSUE_Q)||(state==ISSUE_K)||(state==ISSUE_V)||(state==ISSUE_O)||
     (state==ISSUE_G)||(state==ISSUE_U)||(state==ISSUE_D)||(state==ISSUE_LOGITS));
  assign attention_request=(state==WAIT_ATTN);
  assign mlp_activation_request=(state==WAIT_MLP);

  always_comb begin
    projection_tag=0; projection_rows=0; projection_beats_per_row=0; projection_last=0;
    case(state)
      ISSUE_Q: begin projection_tag=TAG_Q; projection_rows=4096; projection_beats_per_row=32; end
      ISSUE_K: begin projection_tag=TAG_K; projection_rows=4096; projection_beats_per_row=32; end
      ISSUE_V: begin projection_tag=TAG_V; projection_rows=4096; projection_beats_per_row=32; end
      ISSUE_O: begin projection_tag=TAG_O; projection_rows=4096; projection_beats_per_row=32; end
      ISSUE_G: begin projection_tag=TAG_G; projection_rows=11008; projection_beats_per_row=32; end
      ISSUE_U: begin projection_tag=TAG_U; projection_rows=11008; projection_beats_per_row=32; end
      ISSUE_D: begin projection_tag=TAG_D; projection_rows=4096; projection_beats_per_row=86; end
      ISSUE_LOGITS: begin projection_tag=TAG_LOGITS; projection_rows=32000; projection_beats_per_row=32; projection_last=1; end
      default: begin end
    endcase
  end

  task automatic advance_projection;
    begin
      case(state)
        ISSUE_Q: state<=ISSUE_K;
        ISSUE_K: state<=ISSUE_V;
        ISSUE_V: state<=WAIT_ATTN;
        ISSUE_O: state<=ISSUE_G;
        ISSUE_G: state<=ISSUE_U;
        ISSUE_U: state<=WAIT_MLP;
        ISSUE_D: begin
          if(final_layer) state<=ISSUE_LOGITS;
          else begin layer_reg<=layer_reg+1'b1; state<=ISSUE_Q; end
        end
        ISSUE_LOGITS: state<=COMPLETE;
        default: state<=FAULT;
      endcase
    end
  endtask

  always_ff @(posedge clk) begin
    if(reset) begin
      state<=IDLE; mode_reg<=0; k_reg<=0; layers_reg<=0; layer_reg<=0;
      descriptor_inflight<=0; error_code<=ERR_NONE; launched_projections<=0;
    end else begin
      case(state)
        IDLE: if(start_fire) begin
          if(start_k<1 || start_k>4) begin state<=FAULT; error_code<=ERR_K; end
          else if(start_layers<1 || start_layers>MAX_LAYERS) begin state<=FAULT; error_code<=ERR_LAYERS; end
          else if(!start_mode && start_k!=1) begin state<=FAULT; error_code<=ERR_LEGACY_K; end
          else begin
            mode_reg<=start_mode; k_reg<=start_k; layers_reg<=start_layers;
            layer_reg<=0; descriptor_inflight<=0; launched_projections<=0; state<=ISSUE_Q;
          end
        end
        WAIT_ATTN: if(attention_done) state<=ISSUE_O;
        WAIT_MLP: if(mlp_activation_done) state<=ISSUE_D;
        COMPLETE: state<=IDLE;
        FAULT: state<=FAULT;
        default: begin
          if(descriptor_fire) begin descriptor_inflight<=1; launched_projections<=launched_projections+1'b1; end
          if(projection_done) begin
            if(!descriptor_inflight || projection_done_tag!=projection_tag) begin state<=FAULT; error_code<=ERR_DONE_TAG; end
            else if(projection_done_layer!=layer_reg) begin state<=FAULT; error_code<=ERR_DONE_LAYER; end
            else begin descriptor_inflight<=0; advance_projection(); end
          end
        end
      endcase
    end
  end
endmodule
