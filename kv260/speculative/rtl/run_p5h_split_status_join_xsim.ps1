$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$rtl = Join-Path $repo 'kv260\speculative\build\p5h_split_status_join_elab\SplitDmaStatusJoin.v'
$tb = Join-Path $PSScriptRoot 'p5h_split_status_join_tb.sv'
$vivadoBin = 'F:\Xilinx2022\Vivado\2022.2\bin'
& (Join-Path $vivadoBin 'xvlog.bat') -sv $rtl $tb
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& (Join-Path $vivadoBin 'xelab.bat') p5h_split_status_join_tb -s p5h_split_status_join_sim
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& (Join-Path $vivadoBin 'xsim.bat') p5h_split_status_join_sim -runall
exit $LASTEXITCODE
