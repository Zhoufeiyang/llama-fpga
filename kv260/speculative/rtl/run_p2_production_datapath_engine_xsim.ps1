param(
  [string]$VivadoRoot = 'F:\Xilinx2022\Vivado\2022.2'
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$rtl = Join-Path $repoRoot 'kv260\speculative\build\p2_production_engine_elab\MulAddEngineNew.v'
$tcl = Join-Path $repoRoot 'kv260\speculative\build\p2_production_datapath_engine_xsim.tcl'
if (-not (Test-Path $rtl)) {
  throw "Missing generated RTL: $rtl. Run the focused P2 production elaboration first."
}

$vivado = Join-Path $VivadoRoot 'bin\vivado.bat'
$workspaceRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')).Path
& subst P: $workspaceRoot
if ($LASTEXITCODE -ne 0) { throw 'Could not create temporary P: path for vendor simulation' }
try {
  $output = & $vivado '-mode' 'batch' '-source' 'P:\llama-fpga\kv260\speculative\build\p2_production_datapath_engine_xsim.tcl' '-notrace' 2>&1
} finally {
  & subst P: /d
}
$output | Write-Output
if ($LASTEXITCODE -ne 0) { throw "Vivado vendor xsim failed ($LASTEXITCODE)" }
$text = $output -join "`n"
if ($text -match '(?m)^Fatal:') { throw 'P2 production datapath XSim reported a fatal assertion' }
if ($text -notmatch 'P2_PRODUCTION_DATAPATH_ENGINE_GO') {
  throw 'P2 production datapath scalar/last GO marker missing'
}
