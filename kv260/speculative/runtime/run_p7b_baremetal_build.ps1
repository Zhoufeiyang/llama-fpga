param(
  [string]$VitisRoot='F:\Xilinx2022\Vitis\2022.2',
  [string]$BspProject='D:\JSA paper\work\hsi_app',
  [string]$BuildDir=(Join-Path $PSScriptRoot '..\build\p7b_baremetal')
)
$ErrorActionPreference='Stop'

function Get-ShortPath([string]$Path){
  $query='for %I in ("'+$Path+'") do @echo %~sI'
  return (& cmd.exe /d /c $query).Trim()
}

$tool=Join-Path $VitisRoot 'gnu\aarch64\nt\aarch64-none\bin'
$cc=Join-Path $tool 'aarch64-none-elf-gcc.exe'
$readelf=Join-Path $tool 'aarch64-none-elf-readelf.exe'
$bsp=Join-Path $BspProject 'hello_world_bsp\psu_cortexa53_0'
$include=Join-Path $bsp 'include'
$lib=Join-Path $bsp 'lib'
$linker=Join-Path $BspProject 'lscript.ld'
foreach($path in @($cc,$readelf,$include,(Join-Path $lib 'libxil.a'),$linker)){
  if(-not(Test-Path -LiteralPath $path)){throw "Missing P7-B build input: $path"}
}
New-Item -ItemType Directory -Force -Path $BuildDir | Out-Null

$shortRuntime=Get-ShortPath $PSScriptRoot
$shortInclude=Get-ShortPath $include
$shortLib=Get-ShortPath $lib
$shortLinker=Get-ShortPath $linker
$shortBuild=Get-ShortPath $BuildDir
$sources=@('spec_runtime.c','spec_runtime_xilinx.c','draft_model.c','p7b_baremetal_smoke.c')
$objects=@()
foreach($source in $sources){
  $src=Join-Path $shortRuntime $source
  $obj=Join-Path $shortBuild ($source -replace '\.c$','.o')
  & $cc -x c -std=c99 -O2 -Wall -Wextra -Werror -ffunction-sections -fdata-sections "-I$shortRuntime" "-I$shortInclude" -c $src -o $obj
  if($LASTEXITCODE-ne 0){throw "P7-B compile failed: $source"}
  $objects += $obj
}
$elf=Join-Path $shortBuild 'p7b_baremetal_smoke.elf'
$map=Join-Path $shortBuild 'p7b_baremetal_smoke.map'
& $cc -o $elf @objects "-L$shortLib" "-T$shortLinker" "-Wl,-Map,$map" '-Wl,--gc-sections' '-Wl,--start-group' '-lxil' '-lgcc' '-lc' '-Wl,--end-group'
if($LASTEXITCODE-ne 0){throw 'P7-B ELF link failed'}
$report=& $readelf -h -S -s $elf 2>&1
$text=($report -join "`n")
if($LASTEXITCODE-ne 0 -or $text-notmatch 'AArch64' -or
   $text-notmatch 'spec_xilinx_runtime_init'){throw 'P7-B ELF audit failed'}
$summary=@(
  'P7B_BAREMETAL_BUILD_GO ARCH=AARCH64 MMIO_ADAPTER=1 INITIAL_TARGET=1 SEQUENTIAL_SAFE_LAUNCH=1',
  (& $cc --version | Select-Object -First 1),
  ($report | Where-Object {$_ -match 'Class:|Machine:|spec_xilinx_runtime_init'} | ForEach-Object {$_.Trim()})
)
$summary | Write-Output
$summary | Set-Content -LiteralPath (Join-Path $BuildDir 'p7b_baremetal_build.log') -Encoding ascii
