$ErrorActionPreference='Stop'
$repo=(Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$rtl=Join-Path $repo 'kv260\speculative\build\p4i_kv_fetch_frontend_elab'
$tb=Join-Path $PSScriptRoot 'p4i_kv_fetch_frontend_tb.sv'
$v='F:\Xilinx2022\Vivado\2022.2\bin'
& (Join-Path $v 'xvlog.bat') -sv (Join-Path $rtl 'P4KvMetadataRequester.v') (Join-Path $rtl 'P4KvTileRequester.v') (Join-Path $rtl 'P4KvFetchFrontend.v') $tb
if($LASTEXITCODE-ne 0){exit $LASTEXITCODE}
& (Join-Path $v 'xelab.bat') p4i_kv_fetch_frontend_tb -s p4i_kv_fetch_frontend_sim
if($LASTEXITCODE-ne 0){exit $LASTEXITCODE}
& (Join-Path $v 'xsim.bat') p4i_kv_fetch_frontend_sim -runall
exit $LASTEXITCODE
