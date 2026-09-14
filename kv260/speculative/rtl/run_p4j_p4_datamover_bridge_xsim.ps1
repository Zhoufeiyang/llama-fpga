$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$rtl = Join-Path $repo 'kv260\speculative\build\p4j_p4_datamover_bridge_elab\P4DataMoverBridge.v'
$tb = Join-Path $PSScriptRoot 'p4j_p4_datamover_bridge_tb.sv'
$vivadoBin = 'F:\Xilinx2022\Vivado\2022.2\bin'
if (!(Test-Path $rtl)) { throw "Missing generated RTL: $rtl" }
& (Join-Path $vivadoBin 'xvlog.bat') -sv (Join-Path (Split-Path $rtl) 'StreamFifo.v') $rtl $tb
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& (Join-Path $vivadoBin 'xelab.bat') p4j_p4_datamover_bridge_tb -s p4j_p4_datamover_bridge_sim
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& (Join-Path $vivadoBin 'xsim.bat') p4j_p4_datamover_bridge_sim -runall
exit $LASTEXITCODE
