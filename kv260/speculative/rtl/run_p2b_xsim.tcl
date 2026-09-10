set rtl_dir [file dirname [file normalize [info script]]]
set spec_dir [file dirname $rtl_dir]
set kv260_dir [file dirname $spec_dir]
set build_dir [file normalize [file join $spec_dir build p2b_xsim]]
set ip_dir [file join $kv260_dir kv260_vivado kv260_vivado.srcs sources_1 ip]
create_project -force p2b_xsim $build_dir -part xck26-sfvc784-2LV-c
add_files [glob [file join $rtl_dir p2_w4_weight_reuse_scheduler.sv]]
add_files [glob [file join $rtl_dir p2_w4_fp16_backend.sv]]
add_files [glob [file join $rtl_dir p2_w4_verification_engine.sv]]
add_files -fileset sim_1 [glob [file join $rtl_dir p2_w4_verification_engine_tb.sv]]
foreach ip {fp16int9d4 fp16mul6 fp16add6 fp16toFp32 fp32add11 fp32mul8 fp32acc22 fp32toFp16} {
  read_ip [file join $ip_dir $ip "$ip.xci"]
}
set_property top p2_w4_verification_engine_tb [get_filesets sim_1]
launch_simulation -step compile
launch_simulation -step elaborate
set sim_dir [file join $build_dir p2b_xsim.sim sim_1 behav xsim]
foreach vector {activation.mem weight.mem golden.mem} {
  file copy -force [file join $build_dir $vector] [file join $sim_dir $vector]
}
set old_dir [pwd]
cd $sim_dir
puts [exec xsim p2_w4_verification_engine_tb_behav -tclbatch [file join $rtl_dir p2b_run_no_wave.tcl] -log simulate.log]
cd $old_dir
close_project
