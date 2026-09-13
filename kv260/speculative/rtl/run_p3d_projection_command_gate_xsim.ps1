$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$rtl = Join-Path $repo 'kv260\speculative\build\p3d_projection_command_gate_elab\SpeculativeProjectionCommandGate.v'
$tb = Join-Path $PSScriptRoot 'p3d_projection_command_gate_tb.sv'
$vivadoBin = 'F:\Xilinx2022\Vivado\2022.2\bin'
if (!(Test-Path $rtl)) { throw "Missing generated RTL: $rtl" }
& (Join-Path $vivadoBin 'xvlog.bat') -sv $rtl $tb
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& (Join-Path $vivadoBin 'xelab.bat') p3d_projection_command_gate_tb -s p3d_projection_command_gate_sim
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& (Join-Path $vivadoBin 'xsim.bat') p3d_projection_command_gate_sim -runall
exit $LASTEXITCODE
