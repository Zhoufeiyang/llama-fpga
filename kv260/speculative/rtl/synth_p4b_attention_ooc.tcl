set script_dir [file dirname [file normalize [info script]]]
set build_dir [file normalize [file join $script_dir .. build p4b_attention_ooc]]
file mkdir $build_dir
read_verilog -sv [list [file join $script_dir p4_attention_phase_controller.sv]]
synth_design -top p4_attention_phase_controller -part xck26-sfvc784-2LV-c -mode out_of_context
create_clock -name clk -period 3.333 [get_ports clk]
report_timing_summary -file [file join $build_dir timing_summary.rpt]
report_utilization -file [file join $build_dir utilization.rpt]
write_checkpoint -force [file join $build_dir p4_attention_phase_controller_synth.dcp]
set paths [get_timing_paths -max_paths 1 -setup]
set wns [get_property SLACK $paths]
puts "P4B_ATTENTION_OOC_WNS_NS=$wns"
puts "P4B_ATTENTION_PHASE_CONTROLLER_OOC_GO"
