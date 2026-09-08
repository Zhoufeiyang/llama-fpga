param(
    [string]$VivadoBin = 'F:\Xilinx2022\Vivado\2022.2\bin',
    [string]$WorkDirectory = 'D:\JSA paper\work\p1_w4_layout_xsim'
)

$ErrorActionPreference = 'Stop'
$source = Join-Path $PSScriptRoot 'p1_w4_layout_tb.sv'
New-Item -ItemType Directory -Force -Path $WorkDirectory | Out-Null

Push-Location $WorkDirectory
try {
    & (Join-Path $VivadoBin 'xvlog.bat') --sv $source
    if ($LASTEXITCODE -ne 0) { throw "xvlog failed with exit code $LASTEXITCODE" }
    & (Join-Path $VivadoBin 'xelab.bat') p1_w4_layout_tb -s p1_w4_layout_sim
    if ($LASTEXITCODE -ne 0) { throw "xelab failed with exit code $LASTEXITCODE" }
    & (Join-Path $VivadoBin 'xsim.bat') p1_w4_layout_sim -runall
    if ($LASTEXITCODE -ne 0) { throw "xsim failed with exit code $LASTEXITCODE" }
}
finally {
    Pop-Location
}
