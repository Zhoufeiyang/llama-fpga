param([string]$Vivado='F:\Xilinx2022\Vivado\2022.2\bin\vivado.bat')
$ErrorActionPreference='Stop'
$output=& $Vivado -mode batch -source (Join-Path $PSScriptRoot 'run_p4a_vendor_softmax.tcl') -notrace 2>&1
$output|Write-Output
if($LASTEXITCODE-ne 0){throw "P4-A vendor softmax xsim failed: $LASTEXITCODE"}
$text=$output-join "`n"
if($text-match '(?m)^Fatal:'){throw 'P4-A vendor softmax fatal assertion'}
if($text-notmatch 'P4A_VENDOR_SOFTMAX_GO'){throw 'P4-A vendor softmax GO marker missing'}
