param([string]$VivadoBin='F:\Xilinx2022\Vivado\2022.2\bin',[string]$BuildDir=(Join-Path $PSScriptRoot '..\build\p6b_axilite_xsim'))
$ErrorActionPreference='Stop'
$generated=Join-Path (Split-Path $PSScriptRoot -Parent) 'build\p5b_axilite_elab\AxiLiteCtrl.v'
if(-not(Test-Path -LiteralPath $generated)){throw 'Missing generated AxiLiteCtrl.v; elaborate top.AxiLiteCtrlP5Test first'}
New-Item -ItemType Directory -Force $BuildDir|Out-Null;Push-Location $BuildDir
try{
  & (Join-Path $VivadoBin 'xvlog.bat') $generated -sv (Join-Path $PSScriptRoot 'p6b_axilite_results_tb.sv');if($LASTEXITCODE-ne 0){throw 'P6-B xvlog failed'}
  & (Join-Path $VivadoBin 'xelab.bat') p6b_axilite_results_tb -s p6b_axilite_sim;if($LASTEXITCODE-ne 0){throw 'P6-B xelab failed'}
  $out=& (Join-Path $VivadoBin 'xsim.bat') p6b_axilite_sim -runall 2>&1;$out|Write-Output
  $text=$out-join "`n";if($LASTEXITCODE-ne 0-or$text-match '(?m)^Fatal:'){throw 'P6-B xsim failed'}
  if($text-notmatch 'P6B_AXILITE_RESULTS_GO'){throw 'P6-B GO marker missing'}
  $text|Set-Content -LiteralPath (Join-Path $BuildDir 'p6b_axilite_xsim.log')
}finally{Pop-Location}
