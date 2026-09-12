param([string]$VivadoBin='F:\Xilinx2022\Vivado\2022.2\bin',[string]$BuildDir=(Join-Path $PSScriptRoot '..\build\p4_spinal_controller_xsim'))
$ErrorActionPreference='Stop'
$repo=[System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..'))
$generated=Join-Path $repo 'scala\P4AttentionPhaseController.v'
if(-not(Test-Path -LiteralPath $generated)){throw 'Elaborate attn.P4AttentionPhaseControllerTest first'}
New-Item -ItemType Directory -Force $BuildDir|Out-Null;Push-Location $BuildDir
try{
  & (Join-Path $VivadoBin 'xvlog.bat') $generated -sv (Join-Path $PSScriptRoot 'p4_attention_phase_controller_spinal_tb.sv');if($LASTEXITCODE-ne 0){throw 'P4 controller xvlog failed'}
  & (Join-Path $VivadoBin 'xelab.bat') p4_attention_phase_controller_spinal_tb -s p4_spinal_controller_sim;if($LASTEXITCODE-ne 0){throw 'P4 controller xelab failed'}
  $out=& (Join-Path $VivadoBin 'xsim.bat') p4_spinal_controller_sim -runall 2>&1;$out|Write-Output
  $text=$out-join "`n";if($LASTEXITCODE-ne 0-or$text-match '(?m)^Fatal:'){throw 'P4 controller xsim failed'}
  if($text-notmatch 'P4_SPINAL_CONTROLLER_GO'){throw 'P4 controller GO marker missing'}
  $text|Set-Content -LiteralPath (Join-Path $BuildDir 'p4_spinal_controller_xsim.log')
}finally{Pop-Location}
