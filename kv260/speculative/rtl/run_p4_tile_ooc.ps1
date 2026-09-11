param([string]$Vivado='F:\Xilinx2022\Vivado\2022.2\bin\vivado.bat')
$ErrorActionPreference='Stop';& $Vivado -mode batch -source (Join-Path $PSScriptRoot 'synth_p4_tile_ooc.tcl') -notrace
if($LASTEXITCODE-ne 0){throw "P4 tile OOC synthesis failed: $LASTEXITCODE"}
