$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$rtl = Join-Path $repo 'kv260\speculative\build\p5g_outstanding_tracker_elab\SpeculativeOutstandingTracker.v'
$tb = Join-Path $PSScriptRoot 'p5g_outstanding_tracker_tb.sv'
$vivadoBin = 'F:\Xilinx2022\Vivado\2022.2\bin'
if (!(Test-Path $rtl)) { throw "Missing generated RTL: $rtl" }
& (Join-Path $vivadoBin 'xvlog.bat') -sv $rtl $tb
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& (Join-Path $vivadoBin 'xelab.bat') p5g_outstanding_tracker_tb -s p5g_outstanding_tracker_sim
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& (Join-Path $vivadoBin 'xsim.bat') p5g_outstanding_tracker_sim -runall
exit $LASTEXITCODE
