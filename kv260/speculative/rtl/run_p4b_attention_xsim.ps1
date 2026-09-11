param([string]$VivadoBin='F:\Xilinx2022\Vivado\2022.2\bin',[string]$BuildDir=(Join-Path $PSScriptRoot '..\build\p4b_attention_xsim'))
$ErrorActionPreference='Stop';New-Item -ItemType Directory -Force $BuildDir|Out-Null;Push-Location $BuildDir
try{
 & (Join-Path $VivadoBin 'xvlog.bat') -sv (Join-Path $PSScriptRoot 'p4_attention_phase_controller.sv') (Join-Path $PSScriptRoot 'p4_attention_phase_controller_tb.sv');if($LASTEXITCODE-ne 0){throw 'P4-B xvlog failed'}
 & (Join-Path $VivadoBin 'xelab.bat') p4_attention_phase_controller_tb -s p4b_attention_sim;if($LASTEXITCODE-ne 0){throw 'P4-B xelab failed'}
 $out=& (Join-Path $VivadoBin 'xsim.bat') p4b_attention_sim -runall 2>&1;$out|Write-Output
 if($LASTEXITCODE -ne 0 -or ($out-join "`n") -match '(?m)^Fatal:'){throw 'P4-B xsim failed'}
 if(($out-join "`n")-notmatch 'P4B_ATTENTION_PHASE_CONTROLLER_GO'){throw 'P4-B GO marker missing'}
}finally{Pop-Location}
