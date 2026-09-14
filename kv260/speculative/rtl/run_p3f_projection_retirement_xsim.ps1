$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$rtl = Join-Path $repo 'kv260\speculative\build\p3f_projection_retirement_elab\SpeculativeProjectionRetirement.v'
$tb = Join-Path $PSScriptRoot 'p3f_projection_retirement_tb.sv'
$vivadoBin = 'F:\Xilinx2022\Vivado\2022.2\bin'
if (!(Test-Path $rtl)) { throw "Missing generated RTL: $rtl" }
& (Join-Path $vivadoBin 'xvlog.bat') -sv $rtl $tb
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& (Join-Path $vivadoBin 'xelab.bat') p3f_projection_retirement_tb -s p3f_projection_retirement_sim
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& (Join-Path $vivadoBin 'xsim.bat') p3f_projection_retirement_sim -runall
exit $LASTEXITCODE
