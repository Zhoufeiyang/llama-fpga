param(
  [string]$Python='python',
  [string]$ArmCc='F:\Xilinx2022\Vitis\2022.2\gnu\aarch64\nt\aarch64-none\bin\aarch64-none-elf-gcc.exe',
  [string]$BuildDir=(Join-Path $PSScriptRoot '..\build\p7f_tiny_draft')
)
$ErrorActionPreference='Stop'
New-Item -ItemType Directory -Force -Path $BuildDir | Out-Null
$model=Join-Path $BuildDir 'tiny_draft_int8.bin'
$reference=Join-Path $BuildDir 'tiny_draft_reference.json'
$logits=Join-Path $BuildDir 'c_logits.f32'
& $Python (Join-Path $PSScriptRoot 'tiny_draft_export.py') --model $model --reference $reference
if($LASTEXITCODE-ne 0){throw 'P7-F exporter failed'}
$linuxRuntime=(wsl.exe wslpath -a $PSScriptRoot).Trim()
$linuxBuild=(wsl.exe wslpath -a $BuildDir).Trim()
$exe="$linuxBuild/test_tiny_draft"
wsl.exe gcc -std=c99 -O2 -Wall -Wextra -Werror -pedantic "-I$linuxRuntime" `
  "$linuxRuntime/tiny_draft.c" "$linuxRuntime/test_tiny_draft.c" -o $exe
if($LASTEXITCODE-ne 0){throw 'P7-F host compile failed'}
$linuxModel=(wsl.exe wslpath -a $model).Trim()
$linuxLogits=(wsl.exe wslpath -a $logits).Trim()
wsl.exe $exe $linuxModel $linuxLogits | Tee-Object -FilePath (Join-Path $BuildDir 'p7f_host.log')
if($LASTEXITCODE-ne 0){throw 'P7-F host inference failed'}
& $Python (Join-Path $PSScriptRoot 'verify_tiny_draft.py') --model $model --reference $reference --c-logits $logits |
  Tee-Object -FilePath (Join-Path $BuildDir 'p7f_numpy.log')
if($LASTEXITCODE-ne 0){throw 'P7-F NumPy equivalence failed'}
if(Test-Path -LiteralPath $ArmCc){
  function Get-ShortPath([string]$Path){
    $query='for %I in ("'+$Path+'") do @echo %~sI'
    return (& cmd.exe /d /c $query).Trim()
  }
  $armRuntime=Get-ShortPath $PSScriptRoot
  $armBuild=Get-ShortPath $BuildDir
  $armObj=Join-Path $armBuild 'p7f_arm_entry.o'
  & $ArmCc '-std=c99' '-O2' '-Wall' '-Wextra' '-Werror' '-ffreestanding' '-fno-builtin' "-I$armRuntime" `
    '-c' (Join-Path $armRuntime 'tiny_draft.c') '-o' (Join-Path $armBuild 'tiny_draft_arm.o')
  if($LASTEXITCODE-ne 0){throw 'P7-F ARM tiny_draft compile failed'}
  & $ArmCc '-std=c99' '-O2' '-Wall' '-Wextra' '-Werror' '-ffreestanding' '-fno-builtin' "-I$armRuntime" `
    '-c' (Join-Path $armRuntime 'p7f_arm_entry.c') '-o' $armObj
  if($LASTEXITCODE-ne 0){throw 'P7-F ARM entry compile failed'}
  $armText=& (Join-Path (Split-Path $ArmCc) 'aarch64-none-elf-readelf.exe') '-h' '-s' $armObj 2>&1
  if(($armText -join "`n") -notmatch 'p7f_tiny_draft_arm_smoke'){throw 'P7-F ARM symbol audit failed'}
  'P7F_ARM_FREESTANDING_GO ARCH=AARCH64 HEAP=0 SYMBOL=p7f_tiny_draft_arm_smoke' |
    Set-Content -LiteralPath (Join-Path $BuildDir 'p7f_arm.log') -Encoding ascii
}
$sha=(Get-FileHash -Algorithm SHA256 $model).Hash.ToLowerInvariant()
$bytes=(Get-Item -LiteralPath $model).Length
"P7F_TINY_DRAFT_GO MODEL_BYTES=$bytes VOCAB=32000 DIM=8 INT8_MATRICES=2 CRC32=1 MODEL_SHA256=$sha ARM_FREESTANDING=$([int](Test-Path -LiteralPath $ArmCc))" |
  Tee-Object -FilePath (Join-Path $BuildDir 'p7f_summary.log')
