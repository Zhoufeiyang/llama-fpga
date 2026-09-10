set script_dir [file dirname [file normalize [info script]]]
set spec_dir [file dirname $script_dir]
set kv260_dir [file dirname $spec_dir]
set ip_dir [file join $kv260_dir kv260_vivado kv260_vivado.gen sources_1 ip]
set output_dir [file normalize [file join $spec_dir build p2b_ooc]]
file mkdir $output_dir
read_verilog -sv [glob [file join $script_dir p2_w4_weight_reuse_scheduler.sv]]
read_verilog -sv [glob [file join $script_dir p2_w4_fp16_backend.sv]]
read_verilog -sv [glob [file join $script_dir p2_w4_verification_engine.sv]]
foreach ip {fp16int9d4 fp16mul6 fp16add6 fp16toFp32 fp32add11 fp32mul8 fp32acc22 fp32toFp16} {
  read_verilog [file join $ip_dir $ip "${ip}_stub.v"]
}
synth_design -mode out_of_context -flatten_hierarchy rebuilt \
  -top p2_w4_verification_engine -part xck26-sfvc784-2LV-c
create_clock -name p2b_clk -period 3.333 [get_ports clk]
report_utilization -file [file join $output_dir utilization.rpt]
report_timing_summary -delay_type min_max -max_paths 10 -file [file join $output_dir timing_summary.rpt]
report_drc -file [file join $output_dir drc.rpt]
write_checkpoint -force [file join $output_dir p2_w4_verification_engine_synth.dcp]
set paths [get_timing_paths -quiet -max_paths 1 -nworst 1 -setup]
set wns [expr {[llength $paths] ? [get_property SLACK [lindex $paths 0]] : "N/A"}]
set summary [open [file join $output_dir gate_summary.txt] w]
puts $summary "P2B_SYNTH_STATUS=complete"
puts $summary "P2B_PRODUCTION_LANES=128"
puts $summary "P2B_BANKS=4"
puts $summary "P2B_TARGET_PERIOD_NS=3.333"
puts $summary "P2B_POST_SYNTH_WNS_NS=$wns"
close $summary
puts "P2B_OOC_SYNTH_GO WNS=$wns"
