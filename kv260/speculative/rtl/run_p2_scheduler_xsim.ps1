param(
    [string]$VivadoBin = 'F:\Xilinx2022\Vivado\2022.2\bin',
    [string]$BuildDir = (Join-Path $PSScriptRoot '..\build\p2_scheduler_xsim')
)

$ErrorActionPreference = 'Stop'
$rtlDir = $PSScriptRoot
$scheduler = Join-Path $rtlDir 'p2_w4_weight_reuse_scheduler.sv'
$testbench = Join-Path $rtlDir 'p2_w4_weight_reuse_scheduler_tb.sv'
$xvlog = Join-Path $VivadoBin 'xvlog.bat'
$xelab = Join-Path $VivadoBin 'xelab.bat'
$xsim = Join-Path $VivadoBin 'xsim.bat'

New-Item -ItemType Directory -Force -Path $BuildDir | Out-Null
Push-Location $BuildDir
try {
    & $xvlog -sv $scheduler $testbench
    if ($LASTEXITCODE -ne 0) { throw "xvlog failed: $LASTEXITCODE" }
    & $xelab p2_w4_weight_reuse_scheduler_tb -s p2_scheduler_sim
    if ($LASTEXITCODE -ne 0) { throw "xelab failed: $LASTEXITCODE" }
    $simOutput = & $xsim p2_scheduler_sim -runall 2>&1
    $simOutput | Write-Output
    if ($LASTEXITCODE -ne 0) { throw "xsim failed: $LASTEXITCODE" }
    $simText = $simOutput -join "`n"
    if ($simText -match '(?m)^Fatal:') { throw 'xsim reported a fatal assertion' }
    if ($simText -notmatch 'P2_W4_WEIGHT_REUSE_SCHEDULER_GO') {
        throw 'xsim did not emit the P2 GO marker'
    }
} finally {
    Pop-Location
}
