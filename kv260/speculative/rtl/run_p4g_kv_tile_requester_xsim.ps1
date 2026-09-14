$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$rtl = Join-Path $repo 'kv260\speculative\build\p4g_kv_tile_requester_elab\P4KvTileRequester.v'
$tb = Join-Path $PSScriptRoot 'p4g_kv_tile_requester_tb.sv'
$vivadoBin = 'F:\Xilinx2022\Vivado\2022.2\bin'
if (!(Test-Path $rtl)) { throw "Missing generated RTL: $rtl" }
& (Join-Path $vivadoBin 'xvlog.bat') -sv $rtl $tb
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& (Join-Path $vivadoBin 'xelab.bat') p4g_kv_tile_requester_tb -s p4g_kv_tile_requester_sim
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& (Join-Path $vivadoBin 'xsim.bat') p4g_kv_tile_requester_sim -runall
exit $LASTEXITCODE
