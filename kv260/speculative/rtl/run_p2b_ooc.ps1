param([string]$Vivado='F:\Xilinx2022\Vivado\2022.2\bin\vivado.bat')
$ErrorActionPreference='Stop'
$workspaceRoot=(Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')).Path
& subst P: $workspaceRoot
if($LASTEXITCODE-ne 0){throw 'Could not create the temporary P: path for Vivado'}
try {
  & $Vivado -mode batch -source 'P:\llama-fpga\kv260\speculative\rtl\synth_p2b_ooc.tcl' -notrace
  if($LASTEXITCODE-ne 0){throw "P2-B OOC synthesis failed: $LASTEXITCODE"}
} finally { & subst P: /d }
