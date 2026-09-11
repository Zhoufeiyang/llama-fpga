set script_dir [file dirname [file normalize [info script]]]
set output_dir [file normalize [file join $script_dir .. build p3_scheduler_ooc]]
file mkdir $output_dir
read_verilog -sv [list [file join $script_dir p3_transformer_projection_scheduler.sv]]
read_verilog -sv [list [file join $script_dir p3_p2_projection_adapter.sv]]
read_verilog -sv [list [file join $script_dir p3_batched_projection_controller.sv]]
synth_design -mode out_of_context -flatten_hierarchy rebuilt \
  -top p3_batched_projection_controller -part xck26-sfvc784-2LV-c
create_clock -name p3_clk -period 3.333 [get_ports clk]
report_utilization -file [file join $output_dir utilization.rpt]
report_timing_summary -delay_type min_max -max_paths 10 -file [file join $output_dir timing_summary.rpt]
report_drc -file [file join $output_dir drc.rpt]
write_checkpoint -force [file join $output_dir p3_transformer_projection_scheduler_synth.dcp]
set paths [get_timing_paths -quiet -max_paths 1 -nworst 1 -setup]
set wns [expr {[llength $paths] ? [get_property SLACK [lindex $paths 0]] : "N/A"}]
set f [open [file join $output_dir gate_summary.txt] w]
puts $f "P3_SCHEDULER_SYNTH_STATUS=complete"
puts $f "P3_SCHEDULER_TARGET_PERIOD_NS=3.333"
puts $f "P3_SCHEDULER_POST_SYNTH_WNS_NS=$wns"
close $f
puts "P3_SCHEDULER_OOC_SYNTH_GO WNS=$wns"
