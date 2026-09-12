`timescale 1ns/1ps
module p5_metadata_line_merge_tb;
  logic[15:0]line_base_token;logic[511:0]old_line,merged_line;
  logic[3:0]update_valid,matched;logic[15:0]update_token[0:3];logic[31:0]update_metadata[0:3];
  integer errors,i;
  p5_metadata_line_merge dut(.*);
  task fill_old;
    begin for(i=0;i<16;i=i+1)old_line[i*32+:32]=32'hc0000000+i;end
  endtask
  task check_line(input integer base);
    integer slot,absolute;
    begin
      #1;
      for(slot=0;slot<16;slot=slot+1)begin
        absolute=base+slot;
        if(absolute>=update_token[0]&&absolute<=update_token[3])begin
          if(merged_line[slot*32+:32]!==32'ha5000000+absolute)errors=errors+1;
        end else if(merged_line[slot*32+:32]!==32'hc0000000+slot)errors=errors+1;
      end
    end
  endtask
  task run_boundary(input integer base_token);
    integer line0,line1;
    begin
      for(i=0;i<4;i=i+1)begin update_token[i]=base_token+i;update_metadata[i]=32'ha5000000+base_token+i;end
      update_valid='1;line0=(base_token/16)*16;line1=line0+16;
      fill_old();line_base_token=line0;#1;
      if(matched==0)errors=errors+1;check_line(line0);
      fill_old();line_base_token=line1;#1;
      if(matched==0)errors=errors+1;check_line(line1);
      $display("P5_METADATA_BOUNDARY_%0d_%0d preserved=1",line0+15,line1);
    end
  endtask
  initial begin
    errors=0;run_boundary(15);run_boundary(31);
    if(errors)$fatal(1,"P5 metadata merge failed with %0d errors",errors);
    $display("P5_METADATA_RMW_GO BOUNDARIES_15_16_31_32=1");$finish;
  end
endmodule
