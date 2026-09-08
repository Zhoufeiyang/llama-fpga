# Rebuild the checked-in KV260 project and archive immutable P0 route evidence.
# Usage:
#   vivado -mode batch -source build_p0_baseline.tcl -tclargs <output_dir>

if {$argc != 1} {
  error "expected one argument: output directory"
}

set script_dir [file dirname [file normalize [info script]]]
set repo_root [file normalize [file join $script_dir .. .. ..]]
set project [file join $repo_root kv260 kv260_vivado kv260_vivado.xpr]
set output_dir [file normalize [lindex $argv 0]]
file mkdir $output_dir

puts "P0_BUILD_PROJECT=$project"
puts "P0_BUILD_OUTPUT=$output_dir"
open_project $project

set synth_runs [get_runs -quiet -filter {IS_SYNTHESIS == 1}]
reset_run impl_1
foreach run $synth_runs {
  reset_run $run
}

launch_runs impl_1 -to_step write_bitstream -jobs 8
wait_on_run impl_1
set run_status [get_property STATUS [get_runs impl_1]]
puts "P0_IMPL_STATUS=$run_status"
if {![string match "*Complete*" $run_status]} {
  error "implementation did not complete: $run_status"
}

open_run impl_1
report_timing_summary -delay_type min_max -max_paths 20 -check_timing_verbose \
  -file [file join $output_dir timing_summary.rpt]
report_route_status -file [file join $output_dir route_status.rpt]
report_drc -file [file join $output_dir drc.rpt]
report_utilization -file [file join $output_dir utilization.rpt]
write_checkpoint -force [file join $output_dir kv260bd_wrapper_routed.dcp]

set bitstream [get_property DIRECTORY [get_runs impl_1]]
set bitstream [file join $bitstream kv260bd_wrapper.bit]
if {![file exists $bitstream]} {
  error "bitstream not found: $bitstream"
}
file copy -force $bitstream [file join $output_dir kv260bd_wrapper.bit]

set max_path [get_timing_paths -quiet -delay_type max -max_paths 1]
set min_path [get_timing_paths -quiet -delay_type min -max_paths 1]
set wns [expr {[llength $max_path] ? [get_property SLACK $max_path] : "NA"}]
set whs [expr {[llength $min_path] ? [get_property SLACK $min_path] : "NA"}]
set unrouted [llength [get_nets -quiet -filter {ROUTE_STATUS == UNROUTED}]]
set partial [llength [get_nets -quiet -filter {ROUTE_STATUS == PARTIALLY_ROUTED}]]

set summary [open [file join $output_dir gate_summary.txt] w]
puts $summary "P0_IMPL_STATUS=$run_status"
puts $summary "P0_WNS_NS=$wns"
puts $summary "P0_WHS_NS=$whs"
puts $summary "P0_UNROUTED_NETS=$unrouted"
puts $summary "P0_PARTIALLY_ROUTED_NETS=$partial"
puts $summary "P0_BITSTREAM=$bitstream"
close $summary

puts "P0_WNS_NS=$wns"
puts "P0_WHS_NS=$whs"
puts "P0_UNROUTED_NETS=$unrouted"
puts "P0_PARTIALLY_ROUTED_NETS=$partial"
close_project
