`timescale 1ns/1ps
module p3d_projection_command_gate_tb;
  logic io_speculativeActive,io_descriptorActive;
  logic[5:0]io_descriptorTag,io_commandTag;
  wire io_allow,io_weightCommand;
  integer errors,i;
  integer weights[0:7];
  SpeculativeProjectionCommandGate dut(.*);
  task check(input integer active,input integer desc_active,input integer desc,input integer cmd,input integer allowed,input integer weight);
    begin
      io_speculativeActive=active;io_descriptorActive=desc_active;
      io_descriptorTag=desc;io_commandTag=cmd;#1;
      if(io_allow!==allowed[0]||io_weightCommand!==weight[0])errors=errors+1;
    end
  endtask
  initial begin
    errors=0;weights[0]=4;weights[1]=5;weights[2]=7;weights[3]=11;
    weights[4]=15;weights[5]=16;weights[6]=17;weights[7]=19;
    // Legacy mode never acquires a descriptor dependency.
    for(i=0;i<8;i=i+1)check(0,0,0,weights[i],1,1);
    // Auxiliary token/LN/KV tags remain live between projection grants.
    check(1,0,0,2,1,0);check(1,0,0,3,1,0);check(1,0,0,6,1,0);
    // Every target weight segment is held without a matching descriptor.
    for(i=0;i<8;i=i+1)begin
      check(1,0,weights[i],weights[i],0,1);
      check(1,1,weights[(i+1)%8],weights[i],0,1);
      check(1,1,weights[i],weights[i],1,1);
    end
    if(errors)$fatal(1,"P3-D projection command gate failed with %0d errors",errors);
    $display("P3D_PROJECTION_COMMAND_GATE_GO WEIGHT_TAGS=8 MATCH_REQUIRED=1 AUXILIARY_PROGRESS=1 LEGACY_BYPASS=1");
    $finish;
  end
endmodule
