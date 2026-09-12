param([string]$Vivado='F:\Xilinx2022\Vivado\2022.2\bin\vivado.bat')
$ErrorActionPreference='Stop'
$qkRtl=Join-Path (Split-Path $PSScriptRoot -Parent) 'build\p4a_qk_elab\QKMul.v'
if(-not(Test-Path -LiteralPath $qkRtl)){
  throw 'Missing generated QKMul.v; elaborate attn.QKMulP4Test before running vendor xsim'
}
$output=& $Vivado -mode batch -source (Join-Path $PSScriptRoot 'run_p4a_qk_v_vendor.tcl') -notrace 2>&1
$output|Write-Output
if($LASTEXITCODE-ne 0){throw "P4-A QK/V vendor xsim failed: $LASTEXITCODE"}
$text=$output-join "`n"
if($text-match '(?m)^Fatal:'){throw 'P4-A QK/V fatal assertion'}
if($text-notmatch 'P4A_VENDOR_QK_V_GO'){throw 'P4-A QK/V GO marker missing'}
