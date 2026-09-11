set rtl_dir [file dirname [file normalize [info script]]]
set spec_dir [file dirname $rtl_dir]
set kv260_dir [file dirname $spec_dir]
set build_dir [file normalize [file join $spec_dir build p4a_vendor_softmax_xsim]]
set generated [file normalize [file join $spec_dir build p4a_softmax_elab SerialSafeSoftmaxTest.v]]
set ip_dir [file join $kv260_dir kv260_vivado kv260_vivado.srcs sources_1 ip]
create_project -force p4a_vendor_softmax $build_dir -part xck26-sfvc784-2LV-c
add_files [list $generated]
add_files -fileset sim_1 [list [file join $rtl_dir p4a_serial_softmax_vendor_tb.sv]]
foreach ip {fp16lt0 fp16sub8 fp16ex12 fp16acc16 fp16div12} {
  read_ip [list [file join $ip_dir $ip "$ip.xci"]]
}
set_property top p4a_serial_softmax_vendor_tb [get_filesets sim_1]
launch_simulation -step compile
launch_simulation -step elaborate
set sim_dir [file join $build_dir p4a_vendor_softmax.sim sim_1 behav xsim]
set old_dir [pwd];cd $sim_dir
puts [exec {*}[list xsim p4a_serial_softmax_vendor_tb_behav -runall -log simulate.log]]
cd $old_dir
close_project
