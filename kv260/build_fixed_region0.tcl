# Patch and build a Vitis 2022.2 standalone application generated from the
# KV260 XSA. Invoke with:
#   xsct build_fixed_region0.tcl <generated_app_directory>

if {$argc != 1} {
    error "usage: xsct build_fixed_region0.tcl <generated_app_directory>"
}

set script_dir [file dirname [file normalize [info script]]]
set app_dir [file normalize [lindex $argv 0]]
set makefile_path [file join $app_dir Makefile]
set lscript_path [file join $app_dir lscript.ld]
set source_path [file join $script_dir kv260_sdk.c]

foreach required [list $makefile_path $lscript_path $source_path] {
    if {![file exists $required]} {
        error "required file does not exist: $required"
    }
}

file copy -force $source_path [file join $app_dir helloworld.c]

set fd [open $makefile_path r]
set text [read $fd]
close $fd
if {![regsub -line {^CFLAGS :=.*$} $text {CFLAGS := -fcommon} text]} {
    error "CFLAGS assignment was not found in $makefile_path"
}
set fd [open $makefile_path w]
puts -nonewline $fd $text
close $fd

set fd [open $lscript_path r]
set text [read $fd]
close $fd

if {[string first ".model_region_0 0x00036000" $text] < 0} {
    set original_bss {
.bss (NOLOAD) : {
   . = ALIGN(64);
   __bss_start__ = .;
   *(.bss)
   *(.bss.*)
   *(.gnu.linkonce.b.*)
   *(COMMON)
   . = ALIGN(64);
   __bss_end__ = .;
} > psu_ddr_0_MEM_0
}
    set fixed_bss {
.bss (NOLOAD) : {
   . = ALIGN(64);
   __bss_start__ = .;
   *(.bss)
   *(.bss.*)
   *(.gnu.linkonce.b.*)
} > psu_ddr_0_MEM_0

/* DataPath_xN.v uses 0x00036000 as the fixed second-bank base. */
.model_region_0 0x00036000 (NOLOAD) : {
   KEEP(*(.model_region_0))
} > psu_ddr_0_MEM_0

/* Tentative application globals follow the complete model region. */
.common_bss (NOLOAD) : {
   *(COMMON)
   . = ALIGN(64);
   __bss_end__ = .;
} > psu_ddr_0_MEM_0

ASSERT(ADDR(.model_region_0) == 0x00036000,
       "region_0 must match the PL base address 0x00036000")
ASSERT(SIZEOF(.model_region_0) == 0x722B4000,
       "region_0 size must match llama1.bin")
}
    if {[string first $original_bss $text] < 0} {
        error "generated BSS block was not found in $lscript_path"
    }
    set text [string map [list $original_bss $fixed_bss] $text]
    set fd [open $lscript_path w]
    puts -nonewline $fd $text
    close $fd
}

puts [exec make -C $app_dir 2>@1]

set elf_path [file join $app_dir executable.elf]
set nm_output [exec aarch64-none-elf-nm -n -S $elf_path]
if {![regexp -line {^0000000000036000 00000000722b4000 B region_0$} $nm_output]} {
    error "ELF validation failed: region_0 is not at 0x00036000"
}

puts "KV260_BUILD_OK"
puts "ELF=$elf_path"
puts "REGION_0=0x00036000"
