param([string]$Vivado='F:\Xilinx2022\Vivado\2022.2\bin\vivado.bat')
$ErrorActionPreference='Stop'
& $Vivado -mode batch -source (Join-Path $PSScriptRoot 'synth_p3_scheduler_ooc.tcl') -notrace
if($LASTEXITCODE-ne 0){throw "P3 scheduler OOC synthesis failed: $LASTEXITCODE"}
