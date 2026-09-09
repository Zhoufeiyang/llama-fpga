set script_dir [file dirname [file normalize [info script]]]
set rtl [file join $script_dir p2_w4_weight_reuse_scheduler.sv]
set output_dir [file normalize [file join $script_dir .. build p2_scheduler_ooc]]
file mkdir $output_dir

read_verilog -sv [list $rtl]
synth_design -mode out_of_context -flatten_hierarchy rebuilt \
  -top p2_w4_weight_reuse_scheduler -part xck26-sfvc784-2LV-c
create_clock -name p2_clk -period 3.333 [get_ports clk]

report_utilization -file [file join $output_dir utilization.rpt]
report_timing_summary -delay_type min_max -max_paths 10 \
  -file [file join $output_dir timing_summary.rpt]
report_drc -file [file join $output_dir drc.rpt]
write_checkpoint -force [file join $output_dir p2_w4_weight_reuse_scheduler_synth.dcp]

set timing_paths [get_timing_paths -quiet -max_paths 1 -nworst 1 -setup]
if {[llength $timing_paths] == 0} {
  set wns "N/A"
} else {
  set wns [get_property SLACK [lindex $timing_paths 0]]
}
set summary [open [file join $output_dir gate_summary.txt] w]
puts $summary "P2_SCHEDULER_SYNTH_STATUS=complete"
puts $summary "P2_SCHEDULER_TARGET_PERIOD_NS=3.333"
puts $summary "P2_SCHEDULER_POST_SYNTH_WNS_NS=$wns"
close $summary
puts "P2_SCHEDULER_OOC_SYNTH_GO WNS=$wns"
