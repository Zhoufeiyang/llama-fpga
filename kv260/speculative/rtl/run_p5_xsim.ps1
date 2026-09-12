param([string]$VivadoBin='F:\Xilinx2022\Vivado\2022.2\bin',[string]$BuildDir=(Join-Path $PSScriptRoot '..\build\p5_xsim'))
$ErrorActionPreference='Stop';New-Item -ItemType Directory -Force $BuildDir|Out-Null;Push-Location $BuildDir
try{
  $sources=@((Join-Path $PSScriptRoot 'p5_tentative_kv_commit.sv'),(Join-Path $PSScriptRoot 'p5_tentative_kv_commit_tb.sv'),(Join-Path $PSScriptRoot 'p5_metadata_line_merge.sv'),(Join-Path $PSScriptRoot 'p5_metadata_line_merge_tb.sv'))
  & (Join-Path $VivadoBin 'xvlog.bat') -sv @sources;if($LASTEXITCODE-ne 0){throw 'P5 xvlog failed'}
  $allOutput=''
  foreach($top in @('p5_tentative_kv_commit_tb','p5_metadata_line_merge_tb')){
    $snap="${top}_sim";& (Join-Path $VivadoBin 'xelab.bat') $top -s $snap;if($LASTEXITCODE-ne 0){throw "P5 xelab failed: $top"}
    $out=& (Join-Path $VivadoBin 'xsim.bat') $snap -runall 2>&1;$out|Write-Output
    if($LASTEXITCODE-ne 0-or($out-join "`n")-match '(?m)^Fatal:'){throw "P5 xsim failed: $top"}
    $allOutput+=$out-join "`n"
  }
  if($allOutput-notmatch 'P5_POINTER_COMMIT_GO'){throw 'P5 pointer GO marker missing'}
  if($allOutput-notmatch 'P5_METADATA_RMW_GO'){throw 'P5 metadata GO marker missing'}
  $allOutput|Set-Content -LiteralPath (Join-Path $BuildDir 'p5_xsim_combined.log')
}finally{Pop-Location}
