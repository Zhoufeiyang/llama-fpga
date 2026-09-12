param([string]$VivadoBin='F:\Xilinx2022\Vivado\2022.2\bin',[string]$BuildDir=(Join-Path $PSScriptRoot '..\build\p4_completion_adapter_xsim'))
$ErrorActionPreference='Stop'
$generated=Join-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) '..\scala\P4AttentionCompletionAdapter.v'
$generated=[System.IO.Path]::GetFullPath($generated)
if(-not(Test-Path -LiteralPath $generated)){throw 'Elaborate attn.P4AttentionPhaseControllerTest first'}
New-Item -ItemType Directory -Force $BuildDir|Out-Null;Push-Location $BuildDir
try{
  & (Join-Path $VivadoBin 'xvlog.bat') $generated -sv (Join-Path $PSScriptRoot 'p4_completion_adapter_tb.sv');if($LASTEXITCODE-ne 0){throw 'P4 completion xvlog failed'}
  & (Join-Path $VivadoBin 'xelab.bat') p4_completion_adapter_tb -s p4_completion_adapter_sim;if($LASTEXITCODE-ne 0){throw 'P4 completion xelab failed'}
  $out=& (Join-Path $VivadoBin 'xsim.bat') p4_completion_adapter_sim -runall 2>&1;$out|Write-Output
  $text=$out-join "`n";if($LASTEXITCODE-ne 0-or$text-match '(?m)^Fatal:'){throw 'P4 completion xsim failed'}
  if($text-notmatch 'P4_COMPLETION_ADAPTER_GO'){throw 'P4 completion GO marker missing'}
  $text|Set-Content -LiteralPath (Join-Path $BuildDir 'p4_completion_adapter_xsim.log')
}finally{Pop-Location}
