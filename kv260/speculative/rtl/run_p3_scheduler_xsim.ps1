param([string]$VivadoBin='F:\Xilinx2022\Vivado\2022.2\bin',[string]$BuildDir=(Join-Path $PSScriptRoot '..\build\p3_scheduler_xsim'))
$ErrorActionPreference='Stop'; New-Item -ItemType Directory -Force $BuildDir|Out-Null
Push-Location $BuildDir
try {
  & (Join-Path $VivadoBin 'xvlog.bat') -sv (Join-Path $PSScriptRoot 'p3_transformer_projection_scheduler.sv') (Join-Path $PSScriptRoot 'p3_transformer_projection_scheduler_tb.sv')
  if($LASTEXITCODE-ne 0){throw 'P3 xvlog failed'}
  & (Join-Path $VivadoBin 'xelab.bat') p3_transformer_projection_scheduler_tb -s p3_scheduler_sim
  if($LASTEXITCODE-ne 0){throw 'P3 xelab failed'}
  $out=& (Join-Path $VivadoBin 'xsim.bat') p3_scheduler_sim -runall 2>&1; $out|Write-Output
  if($LASTEXITCODE-ne 0 -or ($out-join "`n")-match '(?m)^Fatal:'){throw 'P3 xsim failed'}
  if(($out-join "`n")-notmatch 'P3_TRANSFORMER_PROJECTION_SCHEDULER_GO'){throw 'P3 GO marker missing'}
  & (Join-Path $VivadoBin 'xvlog.bat') -sv (Join-Path $PSScriptRoot 'p3_p2_projection_adapter.sv') (Join-Path $PSScriptRoot 'p3_p2_projection_adapter_tb.sv')
  if($LASTEXITCODE-ne 0){throw 'P3 adapter xvlog failed'}
  & (Join-Path $VivadoBin 'xelab.bat') p3_p2_projection_adapter_tb -s p3_adapter_sim
  if($LASTEXITCODE-ne 0){throw 'P3 adapter xelab failed'}
  $adapterOut=& (Join-Path $VivadoBin 'xsim.bat') p3_adapter_sim -runall 2>&1; $adapterOut|Write-Output
  if($LASTEXITCODE-ne 0 -or ($adapterOut-join "`n")-match '(?m)^Fatal:'){throw 'P3 adapter xsim failed'}
  if(($adapterOut-join "`n")-notmatch 'P3_P2_PROJECTION_ADAPTER_GO'){throw 'P3 adapter GO marker missing'}
} finally { Pop-Location }
