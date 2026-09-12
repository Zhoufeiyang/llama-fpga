param([string]$VivadoBin='F:\Xilinx2022\Vivado\2022.2\bin',[string]$BuildDir=(Join-Path $PSScriptRoot '..\build\p5e_metadata_xsim'))
$ErrorActionPreference='Stop'
$generated=Join-Path (Split-Path $PSScriptRoot -Parent) 'build\p5e_metadata_elab\KvScaleZeroPacker.v'
if(-not(Test-Path -LiteralPath $generated)){throw 'Missing generated KvScaleZeroPacker.v; elaborate attn.KvScaleZeroPackerP5Test first'}
New-Item -ItemType Directory -Force $BuildDir|Out-Null;Push-Location $BuildDir
try{
  & (Join-Path $VivadoBin 'xvlog.bat') $generated -sv (Join-Path $PSScriptRoot 'p5e_metadata_replay_tb.sv');if($LASTEXITCODE-ne 0){throw 'P5-E xvlog failed'}
  & (Join-Path $VivadoBin 'xelab.bat') p5e_metadata_replay_tb -s p5e_metadata_sim;if($LASTEXITCODE-ne 0){throw 'P5-E xelab failed'}
  $out=& (Join-Path $VivadoBin 'xsim.bat') p5e_metadata_sim -runall 2>&1;$out|Write-Output
  $text=$out-join "`n";if($LASTEXITCODE-ne 0-or$text-match '(?m)^Fatal:'){throw 'P5-E xsim failed'}
  if($text-notmatch 'P5E_METADATA_RETAINED_LINE_GO'){throw 'P5-E GO marker missing'}
  $text|Set-Content -LiteralPath (Join-Path $BuildDir 'p5e_metadata_xsim.log')
}finally{Pop-Location}
