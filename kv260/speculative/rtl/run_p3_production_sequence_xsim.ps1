param([string]$VivadoBin='F:\Xilinx2022\Vivado\2022.2\bin',[string]$BuildDir=(Join-Path $PSScriptRoot '..\build\p3_production_sequence_xsim'))
$ErrorActionPreference='Stop'
$generated=Join-Path (Split-Path $PSScriptRoot -Parent) 'build\p3_production_sequence_elab\SpeculativeProjectionSequencer.v'
if(-not(Test-Path -LiteralPath $generated)){throw 'Generate SpeculativeProjectionSequencer.v with top.SpeculativeProjectionSequencerTest first'}
New-Item -ItemType Directory -Force $BuildDir|Out-Null; Push-Location $BuildDir
try {
  & (Join-Path $VivadoBin 'xvlog.bat') $generated -sv (Join-Path $PSScriptRoot 'p3_production_sequence_tb.sv')
  if($LASTEXITCODE-ne 0){throw 'P3 production sequence xvlog failed'}
  & (Join-Path $VivadoBin 'xelab.bat') p3_production_sequence_tb -s p3_production_sequence_sim
  if($LASTEXITCODE-ne 0){throw 'P3 production sequence xelab failed'}
  $out=& (Join-Path $VivadoBin 'xsim.bat') p3_production_sequence_sim -runall 2>&1; $out|Write-Output
  $text=$out-join "`n"
  if($LASTEXITCODE-ne 0 -or $text-match '(?m)^Fatal:'){throw 'P3 production sequence xsim failed'}
  if($text-notmatch 'P3_PRODUCTION_SEQUENCE_GO'){throw 'P3 production sequence GO marker missing'}
  $text|Set-Content -LiteralPath (Join-Path $BuildDir 'p3_production_sequence_xsim.log')
} finally {Pop-Location}
