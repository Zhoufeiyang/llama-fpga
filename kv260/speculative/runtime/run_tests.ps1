param([string]$BuildDir=(Join-Path $PSScriptRoot '..\build\p7a_runtime'))
$ErrorActionPreference='Stop'
New-Item -ItemType Directory -Force -Path $BuildDir | Out-Null
$source=(wsl.exe wslpath -a (Join-Path $PSScriptRoot 'spec_runtime.c')).Trim()
$test=(wsl.exe wslpath -a (Join-Path $PSScriptRoot 'test_spec_runtime.c')).Trim()
$include=(wsl.exe wslpath -a $PSScriptRoot).Trim()
$binary=(wsl.exe wslpath -a (Join-Path $BuildDir 'test_spec_runtime')).Trim()
$log=Join-Path $BuildDir 'p7a_runtime_test.log'
wsl.exe gcc -std=c99 -Wall -Wextra -Werror -pedantic -I $include $source $test -o $binary
if($LASTEXITCODE-ne 0){throw 'P7-A host compilation failed'}
$output=wsl.exe $binary 2>&1
if($LASTEXITCODE-ne 0){throw 'P7-A runtime test failed'}
$text=(($output -join "`n") -replace "`0",'')
if($text-notmatch 'P7A_PS_RUNTIME_GO'){throw 'P7-A GO marker missing'}
$goLines=$text -split "`r?`n" | Where-Object {$_ -match '^P7A_'}
$goLines | Write-Output
$goLines | Set-Content -LiteralPath $log -Encoding ascii

$draftSource=(wsl.exe wslpath -a (Join-Path $PSScriptRoot 'draft_model.c')).Trim()
$draftTest=(wsl.exe wslpath -a (Join-Path $PSScriptRoot 'test_draft_model.c')).Trim()
$draftBinary=(wsl.exe wslpath -a (Join-Path $BuildDir 'test_draft_model')).Trim()
wsl.exe gcc -std=c99 -Wall -Wextra -Werror -pedantic -I $include $draftSource $draftTest -o $draftBinary
if($LASTEXITCODE-ne 0){throw 'P7-D draft host compilation failed'}
$draftOutput=wsl.exe $draftBinary 2>&1
if($LASTEXITCODE-ne 0){throw 'P7-D draft test failed'}
$draftText=(($draftOutput -join "`n") -replace "`0",'')
if($draftText-notmatch 'P7D_NGRAM_DRAFT_GO'){throw 'P7-D draft GO marker missing'}
$draftGo=$draftText -split "`r?`n" | Where-Object {$_ -match '^P7D_'}
$draftGo | Write-Output
$draftGo | Set-Content -LiteralPath (Join-Path $BuildDir 'p7d_draft_test.log') -Encoding ascii

$armcc='F:\Xilinx2022\Vitis\2022.2\gnu\aarch32\nt\gcc-arm-none-eabi\bin\arm-none-eabi-gcc.exe'
if(Test-Path -LiteralPath $armcc){
  function Get-ShortPath([string]$Path){
    $query='for %I in ("'+$Path+'") do @echo %~sI'
    return (& cmd.exe /d /c $query).Trim()
  }
  $armInclude=Get-ShortPath $PSScriptRoot
  $armSource=Get-ShortPath (Join-Path $PSScriptRoot 'spec_runtime.c')
  $armObject=Join-Path (Get-ShortPath $BuildDir) 'spec_runtime_arm.o'
  & $armcc -x c -std=c99 -Wall -Wextra -Werror -ffreestanding "-I$armInclude" -c $armSource -o $armObject
  if($LASTEXITCODE-ne 0){throw 'P7-A Vitis ARM compilation failed'}
  $draftArmSource=Get-ShortPath (Join-Path $PSScriptRoot 'draft_model.c')
  $draftArmObject=Join-Path (Get-ShortPath $BuildDir) 'draft_model_arm.o'
  & $armcc -x c -std=c99 -Wall -Wextra -Werror -ffreestanding "-I$armInclude" -c $draftArmSource -o $draftArmObject
  if($LASTEXITCODE-ne 0){throw 'P7-D Vitis ARM compilation failed'}
}

$budget=Join-Path $PSScriptRoot 'memory_budget.py'
$budgetReport=Join-Path $BuildDir 'p7d_memory_budget.json'
$budgetOutput=python $budget --output $budgetReport 2>&1
$budgetOutput | Write-Output
if($LASTEXITCODE-ne 0 -or ($budgetOutput -join "`n") -notmatch 'P7D_MEMORY_BUDGET_GO'){
  throw 'P7-D memory budget failed'
}
$budgetOutput | Set-Content -LiteralPath (Join-Path $BuildDir 'p7d_memory_budget.log') -Encoding ascii
