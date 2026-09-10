param([string]$Vivado='F:\Xilinx2022\Vivado\2022.2\bin\vivado.bat')
$ErrorActionPreference='Stop'
$specDir=Split-Path $PSScriptRoot
$buildDir=Join-Path $specDir 'build\p2b_xsim'
& python (Join-Path $specDir 'reference\generate_p2b_vectors.py') $buildDir
if($LASTEXITCODE-ne 0){throw "P1 vector generation failed: $LASTEXITCODE"}
$workspaceRoot=(Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')).Path
& subst P: $workspaceRoot
if($LASTEXITCODE-ne 0){throw 'Could not create the temporary P: path for Vivado'}
try {
  $output=& $Vivado -mode batch -source 'P:\llama-fpga\kv260\speculative\rtl\run_p2b_xsim.tcl' -notrace 2>&1
} finally {
  & subst P: /d
}
$output|Write-Output
if($LASTEXITCODE-ne 0){throw "P2-B xsim failed: $LASTEXITCODE"}
$text=$output-join "`n"
if($text-match '(?m)^Fatal:'){throw 'xsim reported a fatal assertion'}
if($text-notmatch 'P2_W4_FP16_BACKEND_GO'){throw 'P2-B GO marker missing'}
