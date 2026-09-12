param([string]$VivadoBin='F:\Xilinx2022\Vivado\2022.2\bin',[string]$BuildDir=(Join-Path $PSScriptRoot '..\build\p5c_position_xsim'))
$ErrorActionPreference='Stop'
$generated=Join-Path (Split-Path $PSScriptRoot -Parent) 'build\p5c_position_elab\SpeculativeKvPosition.v'
if(-not(Test-Path -LiteralPath $generated)){throw 'Missing generated SpeculativeKvPosition.v; elaborate cfgGen.SpeculativeKvPositionP5CTest first'}
New-Item -ItemType Directory -Force $BuildDir|Out-Null;Push-Location $BuildDir
try{
  & (Join-Path $VivadoBin 'xvlog.bat') $generated -sv (Join-Path $PSScriptRoot 'p5c_position_tb.sv');if($LASTEXITCODE-ne 0){throw 'P5-C xvlog failed'}
  & (Join-Path $VivadoBin 'xelab.bat') p5c_position_tb -s p5c_position_sim;if($LASTEXITCODE-ne 0){throw 'P5-C xelab failed'}
  $out=& (Join-Path $VivadoBin 'xsim.bat') p5c_position_sim -runall 2>&1;$out|Write-Output
  $text=$out-join "`n";if($LASTEXITCODE-ne 0-or$text-match '(?m)^Fatal:'){throw 'P5-C xsim failed'}
  if($text-notmatch 'P5C_POSITION_SELECTOR_GO'){throw 'P5-C GO marker missing'}
  $text|Set-Content -LiteralPath (Join-Path $BuildDir 'p5c_position_xsim.log')
}finally{Pop-Location}
