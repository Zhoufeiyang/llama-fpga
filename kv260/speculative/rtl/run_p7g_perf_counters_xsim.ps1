param([string]$VivadoBin='F:\Xilinx2022\Vivado\2022.2\bin',[string]$BuildDir=(Join-Path $PSScriptRoot '..\build\p7g_perf_counter_xsim'))
$ErrorActionPreference='Stop'
$generated=Join-Path (Split-Path $PSScriptRoot -Parent) 'build\p7g_perf_counter_elab\SpeculativePerfCounters.v'
if(-not(Test-Path -LiteralPath $generated)){throw 'Elaborate util.SpeculativePerfCountersTest first'}
New-Item -ItemType Directory -Force $BuildDir|Out-Null;Push-Location $BuildDir
try{
  & (Join-Path $VivadoBin 'xvlog.bat') $generated -sv (Join-Path $PSScriptRoot 'p7g_perf_counters_tb.sv');if($LASTEXITCODE-ne 0){throw 'P7-G xvlog failed'}
  & (Join-Path $VivadoBin 'xelab.bat') p7g_perf_counters_tb -s p7g_perf_counter_sim;if($LASTEXITCODE-ne 0){throw 'P7-G xelab failed'}
  $out=& (Join-Path $VivadoBin 'xsim.bat') p7g_perf_counter_sim -runall 2>&1;$out|Write-Output
  $text=$out-join "`n";if($LASTEXITCODE-ne 0-or$text-match '(?m)^Fatal:'){throw 'P7-G xsim failed'}
  if($text-notmatch 'P7G_PERF_COUNTERS_GO'){throw 'P7-G GO marker missing'}
  $text|Set-Content -LiteralPath (Join-Path $BuildDir 'p7g_perf_counters_xsim.log')
}finally{Pop-Location}
