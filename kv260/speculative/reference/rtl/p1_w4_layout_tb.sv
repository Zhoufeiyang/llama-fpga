`timescale 1ns/1ps

// P1 verification-only testbench.  The 128 input bytes are the first two
// 512-bit beats of the approved layer-0/head-0 Q block as stored in four
// split-major DMA lanes.  This is not a production datapath implementation.
module p1_w4_layout_tb;
  byte unsigned physical [0:127];
  byte unsigned logical  [0:127];
  byte unsigned expected [0:127];
  integer beat;
  integer lane;
  integer index;
  integer errors;
  integer nibble;
  logic [15:0] scale_bits;

  initial begin
    physical = '{
      'h84,'h37,'ha4,'h75,'h67,'h84,'h73,'h43,'h5d,'h49,'hb7,'h9d,'h7e,'hcd,'h5a,'h56,
      'ha9,'h30,'h91,'h2a,'hf1,'h32,'hab,'h31,'hbf,'h30,'h1a,'h2a,'h61,'h2b,'h3c,'h29,
      'hdd,'ha2,'h35,'h15,'h9e,'h83,'h21,'hc2,'h6e,'h62,'h99,'h79,'h75,'h49,'h18,'h6c,
      'h84,'h29,'h05,'h30,'h5a,'h2d,'h5a,'h36,'hba,'h2f,'h64,'h31,'h83,'h31,'h04,'h2d,
      'h32,'h6a,'hcb,'hea,'h81,'h9b,'hcd,'h4e,'h51,'hed,'h84,'ha5,'h9b,'hd8,'he6,'h74,
      'h66,'h31,'h93,'h34,'h51,'h34,'he1,'h2b,'hbc,'h2d,'he9,'h30,'hdb,'h34,'h1e,'h33,
      'h43,'h5b,'hcc,'hd9,'h84,'h78,'hec,'h1e,'h72,'hcd,'h98,'h77,'h9b,'hb7,'hea,'ha2,
      'hbe,'h34,'h87,'h30,'h4b,'h31,'h4d,'h2f,'h9b,'h2a,'h22,'h32,'h0b,'h39,'hc9,'h33
    };
    expected = '{
      'h84,'h37,'ha4,'h75,'h67,'h84,'h73,'h43,'h5d,'h49,'hb7,'h9d,'h7e,'hcd,'h5a,'h56,
      'hdd,'ha2,'h35,'h15,'h9e,'h83,'h21,'hc2,'h6e,'h62,'h99,'h79,'h75,'h49,'h18,'h6c,
      'h32,'h6a,'hcb,'hea,'h81,'h9b,'hcd,'h4e,'h51,'hed,'h84,'ha5,'h9b,'hd8,'he6,'h74,
      'h43,'h5b,'hcc,'hd9,'h84,'h78,'hec,'h1e,'h72,'hcd,'h98,'h77,'h9b,'hb7,'hea,'ha2,
      'ha9,'h30,'h91,'h2a,'hf1,'h32,'hab,'h31,'hbf,'h30,'h1a,'h2a,'h61,'h2b,'h3c,'h29,
      'h84,'h29,'h05,'h30,'h5a,'h2d,'h5a,'h36,'hba,'h2f,'h64,'h31,'h83,'h31,'h04,'h2d,
      'h66,'h31,'h93,'h34,'h51,'h34,'he1,'h2b,'hbc,'h2d,'he9,'h30,'hdb,'h34,'h1e,'h33,
      'hbe,'h34,'h87,'h30,'h4b,'h31,'h4d,'h2f,'h9b,'h2a,'h22,'h32,'h0b,'h39,'hc9,'h33
    };

    // Invert two beats of the page-local four-lane DMA layout.
    for (beat = 0; beat < 2; beat = beat + 1)
      for (lane = 0; lane < 4; lane = lane + 1)
        for (index = 0; index < 16; index = index + 1)
          logical[beat*64 + lane*16 + index] =
            physical[lane*32 + beat*16 + index];

    errors = 0;
    for (index = 0; index < 128; index = index + 1)
      if (logical[index] !== expected[index]) errors = errors + 1;

    // Low nibble precedes high nibble in the packed zero-point stream.
    if ((logical[0] & 8'h0f) != 4 || ((logical[0] >> 4) & 8'h0f) != 8)
      errors = errors + 1;
    if ((logical[1] & 8'h0f) != 7 || ((logical[1] >> 4) & 8'h0f) != 3)
      errors = errors + 1;

    // The first FP16 scale follows the 64-byte zero-point burst and is little endian.
    scale_bits = {logical[65], logical[64]};
    if (scale_bits != 16'h30a9) errors = errors + 1;

    if (errors != 0) begin
      $display("P1_W4_LAYOUT_SIM_NO_GO errors=%0d", errors);
      $fatal(1);
    end
    $display("P1_W4_LAYOUT_SIM_GO bytes=128 first_zero_pair=4,8 first_scale_bits=30a9");
    $finish;
  end
endmodule
