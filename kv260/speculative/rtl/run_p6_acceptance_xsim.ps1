param([string]$VivadoBin='F:\Xilinx2022\Vivado\2022.2\bin',[string]$BuildDir=(Join-Path $PSScriptRoot '..\build\p6_acceptance_xsim'))
$ErrorActionPreference='Stop';New-Item -ItemType Directory -Force $BuildDir|Out-Null;Push-Location $BuildDir
try{
  & (Join-Path $VivadoBin 'xvlog.bat') -sv (Join-Path $PSScriptRoot 'p6_greedy_acceptance.sv') (Join-Path $PSScriptRoot 'p6_greedy_acceptance_tb.sv');if($LASTEXITCODE-ne 0){throw 'P6 xvlog failed'}
  & (Join-Path $VivadoBin 'xelab.bat') p6_greedy_acceptance_tb -s p6_acceptance_sim;if($LASTEXITCODE-ne 0){throw 'P6 xelab failed'}
  $out=& (Join-Path $VivadoBin 'xsim.bat') p6_acceptance_sim -runall 2>&1;$out|Write-Output
  $text=$out-join "`n";if($LASTEXITCODE-ne 0-or$text-match '(?m)^Fatal:'){throw 'P6 xsim failed'}
  if($text-notmatch 'P6_GREEDY_ACCEPTANCE_GO'){throw 'P6 GO marker missing'}
  $text|Set-Content -LiteralPath (Join-Path $BuildDir 'p6_acceptance_xsim.log')
}finally{Pop-Location}
