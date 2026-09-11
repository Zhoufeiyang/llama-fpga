set d [file dirname [file normalize [info script]]]
set o [file normalize [file join $d .. build p4_tile_ooc]];file mkdir $o
read_verilog -sv [list [file join $d p4_causal_kv_tile_scheduler.sv]]
synth_design -mode out_of_context -flatten_hierarchy rebuilt -top p4_causal_kv_tile_scheduler -part xck26-sfvc784-2LV-c
create_clock -name p4_clk -period 3.333 [get_ports clk]
report_utilization -file [file join $o utilization.rpt]
report_timing_summary -delay_type min_max -max_paths 10 -file [file join $o timing_summary.rpt]
report_drc -file [file join $o drc.rpt]
write_checkpoint -force [file join $o p4_causal_kv_tile_scheduler_synth.dcp]
set p [get_timing_paths -quiet -max_paths 1 -nworst 1 -setup]
set w [expr {[llength $p]?[get_property SLACK [lindex $p 0]]:"N/A"}]
set f [open [file join $o gate_summary.txt] w];puts $f "P4_TILE_POST_SYNTH_WNS_NS=$w";close $f
puts "P4_TILE_OOC_SYNTH_GO WNS=$w"
