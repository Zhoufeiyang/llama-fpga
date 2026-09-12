param([string]$VivadoBin='F:\Xilinx2022\Vivado\2022.2\bin',[string]$BuildDir=(Join-Path $PSScriptRoot '..\build\p3b_descriptor_xsim'))
$ErrorActionPreference='Stop'
$generated=Join-Path (Split-Path $PSScriptRoot -Parent) 'build\p5b_axilite_elab\AxiLiteCtrl.v'
if(-not(Test-Path -LiteralPath $generated)){throw 'Generate AxiLiteCtrl.v with top.AxiLiteCtrlP5Test first'}
New-Item -ItemType Directory -Force $BuildDir|Out-Null;Push-Location $BuildDir
try{
  & (Join-Path $VivadoBin 'xvlog.bat') $generated -sv (Join-Path $PSScriptRoot 'p3b_batch_descriptor_tb.sv');if($LASTEXITCODE-ne 0){throw 'P3-B xvlog failed'}
  & (Join-Path $VivadoBin 'xelab.bat') p3b_batch_descriptor_tb -s p3b_descriptor_sim;if($LASTEXITCODE-ne 0){throw 'P3-B xelab failed'}
  $out=& (Join-Path $VivadoBin 'xsim.bat') p3b_descriptor_sim -runall 2>&1;$out|Write-Output
  $text=$out-join "`n";if($LASTEXITCODE-ne 0-or$text-match '(?m)^Fatal:'){throw 'P3-B xsim failed'}
  if($text-notmatch 'P3B_PRODUCTION_DESCRIPTOR_GO'){throw 'P3-B GO marker missing'}
  $text|Set-Content -LiteralPath (Join-Path $BuildDir 'p3b_descriptor_xsim.log')
}finally{Pop-Location}
