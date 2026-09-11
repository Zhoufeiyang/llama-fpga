`timescale 1ns/1ps
module p3_transformer_projection_scheduler_tb;
  logic clk=0,reset=1; always #1.667 clk=~clk;
  logic start_valid,start_ready,start_mode; logic [2:0] start_k; logic [5:0] start_layers;
  logic projection_valid,projection_ready,projection_mode,projection_last;
  logic [2:0] projection_k; logic [5:0] projection_tag,projection_layer;
  logic [15:0] projection_rows,projection_beats_per_row;
  logic projection_done; logic [5:0] projection_done_tag,projection_done_layer;
  logic attention_request,attention_done,mlp_activation_request,mlp_activation_done;
  logic busy,done,error; logic [3:0] error_code; logic [31:0] launched_projections;
  integer errors,kcase,count,idx,barriers; logic [5:0] tags[0:14];
  p3_transformer_projection_scheduler dut(.*);

  task run_case(input integer kval);
    begin
      reset=1; start_valid=0; projection_ready=0; projection_done=0; attention_done=0; mlp_activation_done=0;
      repeat(4) @(posedge clk); reset=0;
      @(negedge clk); start_mode=(kval!=1); start_k=kval; start_layers=2; start_valid=1;
      do @(posedge clk); while(!start_ready); @(negedge clk); start_valid=0;
      count=0; barriers=0;
      while(!done && !error && count<40) begin
        @(negedge clk);
        projection_ready=~projection_ready; // exercise descriptor backpressure
        attention_done=0; mlp_activation_done=0; projection_done=0;
        if(attention_request) begin attention_done=1; barriers=barriers+1; end
        if(mlp_activation_request) begin mlp_activation_done=1; barriers=barriers+1; end
        if(projection_valid && projection_ready) begin
          if(projection_tag!==tags[count] || projection_k!==kval || projection_mode!==(kval!=1)) errors=errors+1;
          if(projection_layer!==(count<7?0:(count<14?1:1))) errors=errors+1;
          if(projection_tag==6'd31 && projection_beats_per_row!=86) errors=errors+1;
          else if(projection_tag!=6'd31 && projection_beats_per_row!=32) errors=errors+1;
          if(projection_tag==6'd22 || projection_tag==6'd24) begin if(projection_rows!=11008) errors=errors+1; end
          else if(projection_tag==6'd35) begin if(projection_rows!=32000 || !projection_last) errors=errors+1; end
          else if(projection_rows!=4096) errors=errors+1;
          projection_done_tag=projection_tag; projection_done_layer=projection_layer;
          @(posedge clk); @(negedge clk); projection_done=1; projection_ready=0; count=count+1;
        end
        @(posedge clk);
      end
      if(error || count!=15 || launched_projections!=15 || barriers!=4) begin
        $display("P3_FAIL K=%0d count=%0d launches=%0d barriers=%0d error=%0d code=%0d",kval,count,launched_projections,barriers,error,error_code); errors=errors+1;
      end else $display("P3_K%0d descriptors=%0d barriers=%0d",kval,count,barriers);
    end
  endtask

  initial begin
    tags[0]=5; tags[1]=6; tags[2]=7; tags[3]=16; tags[4]=22; tags[5]=24; tags[6]=31;
    tags[7]=5; tags[8]=6; tags[9]=7; tags[10]=16; tags[11]=22; tags[12]=24; tags[13]=31; tags[14]=35;
    errors=0;
    for(kcase=1;kcase<=4;kcase=kcase+1) run_case(kcase);
    // Legacy decode is deliberately restricted to K=1.
    reset=1; repeat(3) @(posedge clk); reset=0;
    @(negedge clk); start_mode=0; start_k=2; start_layers=1; start_valid=1;
    @(posedge clk); @(negedge clk); start_valid=0; @(posedge clk);
    if(!error || error_code!=3) begin $display("P3_INVALID_LEGACY_K_NOT_CAUGHT"); errors=errors+1; end
    // A completion for the wrong tensor must never advance the layer graph.
    reset=1; repeat(3) @(posedge clk); reset=0;
    @(negedge clk); start_mode=1; start_k=2; start_layers=1; start_valid=1;
    @(posedge clk); @(negedge clk); start_valid=0; projection_ready=1;
    do @(posedge clk); while(!projection_valid); @(negedge clk); projection_ready=0;
    projection_done_tag=6'd6; projection_done_layer=0; projection_done=1;
    @(posedge clk); @(negedge clk); projection_done=0; @(posedge clk);
    if(!error || error_code!=4) begin $display("P3_WRONG_DONE_TAG_NOT_CAUGHT"); errors=errors+1; end
    if(errors) $fatal(1,"P3 failed with %0d errors",errors);
    $display("P3_TRANSFORMER_PROJECTION_SCHEDULER_GO K1_TO_K4=1"); $finish;
  end
endmodule
