`timescale 1ns/1ps
module p2_w4_verification_engine_tb;
  localparam LANES=8;
  logic clk=0, reset=1; always #1.667 clk=~clk;
  logic cfg_valid,cfg_ready,cfg_mode; logic [2:0] cfg_k;
  logic [15:0] cfg_beats_per_row,cfg_rows;
  logic activation_valid,activation_ready,activation_last;
  logic [16*LANES-1:0] activation_data;
  logic weight_valid,weight_ready,weight_last; logic [4*LANES-1:0] weight_packed;
  logic [7:0] weight_zero; logic [15:0] weight_scale;
  logic result_valid; logic [15:0] result_data; logic [2:0] result_token_index;
  logic [1:0] result_row_index; logic busy,done,error; logic [3:0] error_code; logic [31:0] accepted_weight_beats;
  logic [127:0] act_mem[0:7]; logic [31:0] weight_mem[0:3]; logic [15:0] golden[0:7];
  integer got, errors, kcase, i, timeout;

  p2_w4_verification_engine #(.LANES(LANES),.MAX_BEATS_PER_ROW(2),.MAX_ROWS(4)) dut(.*);

  task run_case(input integer kval);
    begin
      reset=1; cfg_valid=0; activation_valid=0; weight_valid=0; repeat(5) @(posedge clk); reset=0;
      @(negedge clk); cfg_mode=(kval!=1); cfg_k=kval; cfg_beats_per_row=2; cfg_rows=2; cfg_valid=1;
      do @(posedge clk); while(!cfg_ready); @(negedge clk); cfg_valid=0;
      for(i=0;i<kval*2;i=i+1) begin
        @(negedge clk); activation_data=act_mem[i]; activation_last=(i==kval*2-1); activation_valid=1;
        do @(posedge clk); while(!activation_ready); @(negedge clk); activation_valid=0;
      end
      fork
        begin
          for(i=0;i<4;i=i+1) begin
            @(negedge clk); weight_packed=weight_mem[i]; weight_zero=8; weight_scale=(i<2)?16'h3c00:16'h3800;
            weight_last=(i==3); weight_valid=1; do @(posedge clk); while(!weight_ready); @(negedge clk); weight_valid=0;
          end
        end
        begin
          got=0; timeout=0;
          while(got<kval*2 && timeout<200) begin
            @(posedge clk); timeout=timeout+1;
            if(result_valid) begin
              if(result_data !== golden[result_token_index*2+result_row_index]) begin
                $display("MISMATCH K=%0d token=%0d row=%0d got=%h expected=%h",kval,result_token_index,result_row_index,result_data,golden[result_token_index*2+result_row_index]); errors=errors+1;
              end
              got=got+1;
            end
          end
          if(got!=kval*2) begin $display("TIMEOUT K=%0d got=%0d",kval,got); errors=errors+1; end
        end
      join
      if(accepted_weight_beats!==4) begin $display("TRAFFIC K=%0d beats=%0d",kval,accepted_weight_beats); errors=errors+1; end
      if(error) begin $display("DUT_ERROR K=%0d code=%0d",kval,error_code); errors=errors+1; end
      $display("P2B_K%0d outputs=%0d weight_beats=%0d",kval,got,accepted_weight_beats);
    end
  endtask

  initial begin
    $readmemh("activation.mem",act_mem); $readmemh("weight.mem",weight_mem); $readmemh("golden.mem",golden);
    errors=0; cfg_valid=0; activation_valid=0; weight_valid=0;
    for(kcase=1;kcase<=4;kcase=kcase+1) run_case(kcase);
    if(errors) $fatal(1,"P2-B failed with %0d errors",errors);
    $display("P2_W4_FP16_BACKEND_GO bit_exact_K1_to_K4=1"); $finish;
  end
endmodule
