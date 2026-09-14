param(
  [string]$VivadoRoot = 'F:\Xilinx2022\Vivado\2022.2'
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$rtl = Join-Path $repoRoot 'kv260\speculative\build\p2_row_major_lane_router_elab\P2RowMajorLaneRouter.v'
$tb = Join-Path $repoRoot 'kv260\speculative\rtl\p2_row_major_lane_router_tb.sv'
$runDir = Join-Path $repoRoot 'kv260\speculative\build\p2_row_major_lane_router_xsim'
New-Item -ItemType Directory -Force -Path $runDir | Out-Null
Push-Location $runDir

if (-not (Test-Path $rtl)) {
  throw "Missing generated RTL: $rtl. Run 'sbt runMain top.P2RowMajorLaneRouterGen' first."
}
& (Join-Path $VivadoRoot 'bin\xvlog.bat') '-sv' '-work' 'xsim' $rtl $tb
if ($LASTEXITCODE -ne 0) { throw "xvlog failed ($LASTEXITCODE)" }
& (Join-Path $VivadoRoot 'bin\xelab.bat') 'xsim.p2_row_major_lane_router_tb' '-s' 'p2_row_major_lane_router_tb' '-debug' 'typical' '-L' 'xsim'
if ($LASTEXITCODE -ne 0) { throw "xelab failed ($LASTEXITCODE)" }
& (Join-Path $VivadoRoot 'bin\xsim.bat') 'p2_row_major_lane_router_tb' '-runall' '-nolog'
if ($LASTEXITCODE -ne 0) { throw "xsim failed ($LASTEXITCODE)" }
Pop-Location
