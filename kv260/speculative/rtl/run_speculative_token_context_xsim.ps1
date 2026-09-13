param(
  [string]$VivadoBin='F:\Xilinx2022\Vivado\2022.2\bin',
  [string]$BuildDir=(Join-Path $PSScriptRoot '..\build\speculative_token_context_xsim')
)
$ErrorActionPreference='Stop'
$generated=Join-Path (Split-Path $PSScriptRoot -Parent) 'build\speculative_token_context_elab\SpeculativeTokenContext.v'
if(-not(Test-Path -LiteralPath $generated)){throw 'Elaborate top.SpeculativeTokenContextTest first'}
New-Item -ItemType Directory -Force $BuildDir|Out-Null
Push-Location $BuildDir
try{
  & (Join-Path $VivadoBin 'xvlog.bat') $generated -sv (Join-Path $PSScriptRoot 'speculative_token_context_tb.sv')
  if($LASTEXITCODE-ne 0){throw 'SpeculativeTokenContext xvlog failed'}
  & (Join-Path $VivadoBin 'xelab.bat') speculative_token_context_tb -s speculative_token_context_sim
  if($LASTEXITCODE-ne 0){throw 'SpeculativeTokenContext xelab failed'}
  $out=& (Join-Path $VivadoBin 'xsim.bat') speculative_token_context_sim -runall 2>&1
  $out|Write-Output
  $text=$out-join "`n"
  if($LASTEXITCODE-ne 0-or$text-match '(?m)^Fatal:'){throw 'SpeculativeTokenContext xsim failed'}
  if($text-notmatch 'SPECULATIVE_TOKEN_CONTEXT_GO'){throw 'SpeculativeTokenContext GO marker missing'}
  $text|Set-Content -LiteralPath (Join-Path $BuildDir 'speculative_token_context_xsim.log')
}finally{Pop-Location}
