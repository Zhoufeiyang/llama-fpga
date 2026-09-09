param(
    [string]$Vivado = 'F:\Xilinx2022\Vivado\2022.2\bin\vivado.bat'
)

$ErrorActionPreference = 'Stop'
$script = Join-Path $PSScriptRoot 'synth_p2_scheduler_ooc.tcl'
& $Vivado -mode batch -source $script -notrace
if ($LASTEXITCODE -ne 0) { throw "Vivado OOC synthesis failed: $LASTEXITCODE" }
