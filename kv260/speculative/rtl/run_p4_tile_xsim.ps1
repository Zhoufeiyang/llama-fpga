param([string]$VivadoBin='F:\Xilinx2022\Vivado\2022.2\bin',[string]$BuildDir=(Join-Path $PSScriptRoot '..\build\p4_tile_xsim'))
$ErrorActionPreference='Stop';New-Item -ItemType Directory -Force $BuildDir|Out-Null;Push-Location $BuildDir
try{
 & (Join-Path $VivadoBin 'xvlog.bat') -sv (Join-Path $PSScriptRoot 'p4_causal_kv_tile_scheduler.sv') (Join-Path $PSScriptRoot 'p4_causal_kv_tile_scheduler_tb.sv');if($LASTEXITCODE-ne 0){throw 'P4 xvlog failed'}
 & (Join-Path $VivadoBin 'xelab.bat') p4_causal_kv_tile_scheduler_tb -s p4_tile_sim;if($LASTEXITCODE-ne 0){throw 'P4 xelab failed'}
 $out=& (Join-Path $VivadoBin 'xsim.bat') p4_tile_sim -runall 2>&1;$out|Write-Output
 if($LASTEXITCODE-ne 0 -or ($out-join "`n")-match '(?m)^Fatal:'){throw 'P4 xsim failed'}
 if(($out-join "`n")-notmatch 'P4_CAUSAL_KV_TILE_SCHEDULER_GO'){throw 'P4 GO marker missing'}
}finally{Pop-Location}
