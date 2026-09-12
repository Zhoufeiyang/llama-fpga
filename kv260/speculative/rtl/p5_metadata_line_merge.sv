`timescale 1ns/1ps

// Merge up to four candidate metadata entries into one 64-byte DDR line.
// Entries outside this line are ignored, preserving committed neighbours for
// read-modify-write at token boundaries 15/16, 31/32, and so on.
module p5_metadata_line_merge #(
  parameter integer ITEM_W=32,
  parameter integer ITEMS_PER_LINE=16,
  parameter integer MAX_K=4
) (
  input logic [15:0] line_base_token,
  input logic [ITEM_W*ITEMS_PER_LINE-1:0] old_line,
  input logic [MAX_K-1:0] update_valid,
  input logic [15:0] update_token [0:MAX_K-1],
  input logic [ITEM_W-1:0] update_metadata [0:MAX_K-1],
  output logic [ITEM_W*ITEMS_PER_LINE-1:0] merged_line,
  output logic [MAX_K-1:0] matched
);
  integer i;
  logic [15:0] index;
  always_comb begin
    merged_line=old_line;matched='0;
    for(i=0;i<MAX_K;i=i+1) begin
      index=update_token[i]-line_base_token;
      if(update_valid[i]&&update_token[i]>=line_base_token&&index<ITEMS_PER_LINE) begin
        merged_line[index*ITEM_W+:ITEM_W]=update_metadata[i];
        matched[i]=1'b1;
      end
    end
  end
endmodule
