param(
  [string]$Source='D:\JSA paper\work\llama0.bin',
  [string]$Python='python',
  [string]$VitisRoot='F:\Xilinx2022\Vitis\2022.2',
  [string]$BspProject='D:\JSA paper\work\hsi_app',
  [string]$BuildDir=(Join-Path $PSScriptRoot '..\build\p7j_target_derived_draft')
)
$ErrorActionPreference='Stop'
New-Item -ItemType Directory -Force -Path $BuildDir | Out-Null
$model=Join-Path $BuildDir 'target_derived_draft_int8.bin'
$reference=Join-Path $BuildDir 'target_derived_draft_reference.json'
$logits=Join-Path $BuildDir 'c_logits.f32'
& $Python (Join-Path $PSScriptRoot 'tiny_draft_from_target.py') --source $Source --model $model --reference $reference
if($LASTEXITCODE-ne 0){throw 'P7-J target-derived exporter failed'}
$linuxRuntime=(wsl.exe wslpath -a $PSScriptRoot).Trim()
$linuxBuild=(wsl.exe wslpath -a $BuildDir).Trim()
$exe="$linuxBuild/test_tiny_draft"
wsl.exe gcc -std=c99 -O2 -Wall -Wextra -Werror -pedantic "-I$linuxRuntime" `
  "$linuxRuntime/tiny_draft.c" "$linuxRuntime/test_tiny_draft.c" -o $exe
if($LASTEXITCODE-ne 0){throw 'P7-J host compile failed'}
$linuxModel=(wsl.exe wslpath -a $model).Trim()
$linuxLogits=(wsl.exe wslpath -a $logits).Trim()
wsl.exe $exe $linuxModel $linuxLogits | Tee-Object -FilePath (Join-Path $BuildDir 'p7j_host.log')
if($LASTEXITCODE-ne 0){throw 'P7-J host inference failed'}
& $Python (Join-Path $PSScriptRoot 'verify_tiny_draft.py') --model $model --reference $reference --c-logits $logits |
  Tee-Object -FilePath (Join-Path $BuildDir 'p7j_numpy.log')
if($LASTEXITCODE-ne 0){throw 'P7-J NumPy/C equivalence failed'}
$sha=(Get-FileHash -Algorithm SHA256 $model).Hash.ToLowerInvariant()
$bytes=(Get-Item -LiteralPath $model).Length
"P7J_TARGET_DERIVED_RUNTIME_GO MODEL_BYTES=$bytes MODEL_SHA256=$sha C_NUMPY_EQUIVALENT=1" |
  Tee-Object -FilePath (Join-Path $BuildDir 'p7j_summary.log')

function Get-ShortPath([string]$Path){
  $query='for %I in ("'+$Path+'") do @echo %~sI'
  return (& cmd.exe /d /c $query).Trim()
}
$tool=Join-Path $VitisRoot 'gnu\aarch64\nt\aarch64-none\bin'
$cc=Join-Path $tool 'aarch64-none-elf-gcc.exe'
$readelf=Join-Path $tool 'aarch64-none-elf-readelf.exe'
$bsp=Join-Path $BspProject 'hello_world_bsp\psu_cortexa53_0'
$include=Join-Path $bsp 'include';$lib=Join-Path $bsp 'lib';$linker=Join-Path $BspProject 'lscript.ld'
foreach($path in @($cc,$readelf,$include,(Join-Path $lib 'libxil.a'),$linker)){
  if(-not(Test-Path -LiteralPath $path)){throw "Missing P7-J ARM input: $path"}
}
$shortRuntime=Get-ShortPath $PSScriptRoot;$shortInclude=Get-ShortPath $include
$shortLib=Get-ShortPath $lib;$shortLinker=Get-ShortPath $linker;$shortBuild=Get-ShortPath $BuildDir
$sources=@('spec_runtime.c','spec_runtime_xilinx.c','tiny_draft.c','p7j_baremetal_smoke.c')
$objects=@()
foreach($sourceFile in $sources){
  $obj=Join-Path $shortBuild ($sourceFile -replace '\.c$','.o')
  & $cc -x c -std=c99 -O2 -Wall -Wextra -Werror -ffunction-sections -fdata-sections `
    "-I$shortRuntime" "-I$shortInclude" -c (Join-Path $shortRuntime $sourceFile) -o $obj
  if($LASTEXITCODE-ne 0){throw "P7-J ARM compile failed: $sourceFile"};$objects+=$obj
}
$elf=Join-Path $shortBuild 'p7j_target_derived_draft.elf';$map=Join-Path $shortBuild 'p7j_target_derived_draft.map'
& $cc -o $elf @objects "-L$shortLib" "-T$shortLinker" "-Wl,-Map,$map" '-Wl,--gc-sections' `
  '-Wl,--start-group' '-lxil' '-lgcc' '-lc' '-Wl,--end-group'
if($LASTEXITCODE-ne 0){throw 'P7-J ARM link failed'}
$audit=(& $readelf -h -s $elf 2>&1)-join "`n"
if($audit-notmatch 'AArch64' -or $audit-notmatch 'tiny_draft_callback' -or $audit-notmatch 'spec_xilinx_runtime_init'){
  throw 'P7-J ELF audit failed'
}
$elfHash=(Get-FileHash -Algorithm SHA256 $elf).Hash.ToLowerInvariant();$elfBytes=(Get-Item $elf).Length
"P7J_A53_BINDING_GO ARCH=AARCH64 DRAFT_ADDR=0x722F6000 DRAFT_BYTES=$bytes ELF_BYTES=$elfBytes ELF_SHA256=$elfHash" |
  Tee-Object -FilePath (Join-Path $BuildDir 'p7j_arm.log')
