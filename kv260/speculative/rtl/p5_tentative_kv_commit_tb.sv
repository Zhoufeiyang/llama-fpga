`timescale 1ns/1ps
module p5_tentative_kv_commit_tb;
  localparam BASE=40'h0200000000,REGION_BYTES=40'h0000100000,STRIDE=32'd256;
  logic clk=0,reset=1;always #1.667 clk=~clk;
  logic start_valid,start_ready;logic[2:0]start_k;logic[15:0]start_committed_tokens;
  logic[39:0]kv_region_base,kv_region_bytes;logic[31:0]token_stride_bytes;
  logic write_valid,write_ready;logic[2:0]write_slot;logic[31:0]write_byte_offset;
  logic[127:0]write_data;logic[15:0]write_strobe;logic write_last;
  logic ddr_write_valid,ddr_write_ready;logic[39:0]ddr_write_address;logic[127:0]ddr_write_data;logic[15:0]ddr_write_strobe;
  logic resolve_valid;logic[2:0]accepted_count;logic abort,timeout,busy,done,rolled_back;
  logic[15:0]spec_base_token,speculative_pointer,committed_tokens,visible_tokens;
  logic error;logic[3:0]error_code;
  integer errors,a,i;logic[15:0]old_commit;
  p5_tentative_kv_commit dut(.*);

  task init;
    begin
      reset=1;start_valid=0;write_valid=0;resolve_valid=0;abort=0;timeout=0;ddr_write_ready=1;
      kv_region_base=BASE;kv_region_bytes=REGION_BYTES;token_stride_bytes=STRIDE;
      write_data='h55aa;write_strobe='1;write_byte_offset=0;write_last=1;
      repeat(3)@(posedge clk);reset=0;
    end
  endtask
  task start_tx(input integer kval,input integer committed);
    begin
      @(negedge clk);start_k=kval;start_committed_tokens=committed;start_valid=1;
      @(posedge clk);@(negedge clk);start_valid=0;
      if(spec_base_token!=committed||visible_tokens!=committed)errors=errors+1;
    end
  endtask
  task write_tokens(input integer kval);
    begin
      for(i=0;i<kval;i=i+1)begin
        @(negedge clk);write_slot=i;write_valid=1;
        @(posedge clk);
        if(!ddr_write_valid||ddr_write_address!=BASE+(start_committed_tokens+i)*STRIDE)errors=errors+1;
        @(negedge clk);write_valid=0;
      end
    end
  endtask
  task resolve_tx(input integer accepted,input integer expected);
    begin
      @(negedge clk);accepted_count=accepted;resolve_valid=1;
      @(posedge clk);@(negedge clk);resolve_valid=0;
      if(!done||committed_tokens!=expected||visible_tokens!=expected||speculative_pointer!=expected)errors=errors+1;
      if((accepted!=start_k)!==rolled_back)errors=errors+1;
      @(posedge clk);
    end
  endtask
  initial begin
    errors=0;init();
    for(a=0;a<=4;a=a+1)begin
      start_tx(4,100);write_tokens(4);resolve_tx(a,100+a);
      $display("P5_ACCEPT_%0d committed=%0d",a,committed_tokens);
    end
    start_tx(3,200);write_tokens(1);old_commit=committed_tokens;
    @(negedge clk);abort=1;@(posedge clk);@(negedge clk);abort=0;
    if(!done||committed_tokens!=old_commit||speculative_pointer!=old_commit||!rolled_back)errors=errors+1;
    @(posedge clk);$display("P5_ABORT committed=%0d",committed_tokens);
    start_tx(2,300);write_tokens(1);old_commit=committed_tokens;
    @(negedge clk);timeout=1;@(posedge clk);@(negedge clk);timeout=0;
    if(!done||committed_tokens!=old_commit||speculative_pointer!=old_commit||!rolled_back)errors=errors+1;
    @(posedge clk);$display("P5_TIMEOUT committed=%0d",committed_tokens);
    start_tx(1,4095);write_tokens(1);resolve_tx(1,4096);
    @(negedge clk);start_k=1;start_committed_tokens=4096;start_valid=1;
    @(posedge clk);@(negedge clk);start_valid=0;
    if(!error||error_code!=2)errors=errors+1;
    if(errors)$fatal(1,"P5 pointer manager failed with %0d errors",errors);
    $display("P5_POINTER_COMMIT_GO ACCEPT_0_TO_4=1 ABORT_TIMEOUT=1 REGION_BOUNDS=1");$finish;
  end
endmodule
