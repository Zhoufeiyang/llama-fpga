param(
    [string]$Model0Path = 'D:\JSA paper\work\llama0.bin',
    [string]$Model1Path = 'D:\JSA paper\work\llama1.bin',
    [string]$TokenizerPath = 'D:\JSA paper\work\tkz.bin',
    [string]$ElfPath = 'D:\JSA paper\outputs\kv260_upstream_fixed\llama_upstream_region0_fixed.elf',
    [string]$XsaPath = 'D:\JSA paper\llama-fpga\kv260\kv260bd_wrapper.xsa',
    [string]$BitstreamPath = 'D:\JSA paper\outputs\kv260_upstream_fixed\kv260bd_wrapper.bit',
    [string]$NmPath = 'F:\Xilinx2022\Vitis\2022.2\gnu\aarch64\nt\aarch64-none\bin\aarch64-none-elf-nm.exe',
    [string]$GeneratorPath = 'D:\JSA paper\llama-fpga\scala\src\main\scala\top\EdgeLLMKv260Config.scala',
    [string]$GeneratorEntrypointPath = 'D:\JSA paper\llama-fpga\scala\src\main\scala\top\EdgeLLMInst.scala',
    [string]$GeneratorBuildPath = 'D:\JSA paper\llama-fpga\scala\build.sbt',
    [string]$ImportedRtlPath = 'D:\JSA paper\llama-fpga\kv260\kv260_vivado\kv260_vivado.srcs\sources_1\imports\EdgeLLM\DataPath_xN.v',
    [string]$ExpectedModel0Sha256 = '45bb125d50787badcc6df85fd99ce499ea3a43e160dcee4d736f6fa1b5c2c093',
    [string]$ExpectedModel1Sha256 = '7e947c152ef71de1128248c25a5bda18c652e9356a3ed00c0953ea17ad294afe'
)

$ErrorActionPreference = 'Stop'

$expectedModel0Bytes = 2109472768L
$expectedModel1Bytes = 1915437056L
$expectedRegion0Address = '0000000000036000'
$expectedRegion0Size = '00000000722b4000'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$errors = [System.Collections.Generic.List[string]]::new()
$warnings = [System.Collections.Generic.List[string]]::new()

function Get-ArtifactRecord {
    param(
        [string]$Path,
        [long]$ExpectedBytes = -1,
        [string]$ExpectedSha256,
        [bool]$Required = $true
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        $message = "Missing artifact: $Path"
        if ($Required) {
            $script:errors.Add($message)
        } else {
            $script:warnings.Add($message)
        }
        return [ordered]@{
            path = $Path
            present = $false
        }
    }

    $item = Get-Item -LiteralPath $Path
    $hash = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
    $sizeMatches = $null
    $hashMatches = $null

    if ($ExpectedBytes -ge 0) {
        $sizeMatches = $item.Length -eq $ExpectedBytes
        if (-not $sizeMatches) {
            $script:errors.Add("Unexpected byte count for ${Path}: $($item.Length)")
        }
    }

    if (-not [string]::IsNullOrWhiteSpace($ExpectedSha256)) {
        $hashMatches = $hash -eq $ExpectedSha256.ToLowerInvariant()
        if (-not $hashMatches) {
            $script:errors.Add("Unexpected SHA256 for ${Path}: $hash")
        }
    }

    return [ordered]@{
        path = $item.FullName
        present = $true
        bytes = $item.Length
        sha256 = $hash
        size_matches = $sizeMatches
        hash_matches = $hashMatches
        last_write_time = $item.LastWriteTime.ToString('o')
    }
}

Push-Location $repoRoot
try {
    $repoCommit = (git rev-parse HEAD).Trim()
    $repoBranch = (git branch --show-current).Trim()
    $repoStatus = @(git status --short)
} finally {
    Pop-Location
}

$artifacts = [ordered]@{}
$artifacts.model0 = Get-ArtifactRecord -Path $Model0Path -ExpectedBytes $expectedModel0Bytes -ExpectedSha256 $ExpectedModel0Sha256
$artifacts.model1 = Get-ArtifactRecord -Path $Model1Path -ExpectedBytes $expectedModel1Bytes -ExpectedSha256 $ExpectedModel1Sha256
$artifacts.tokenizer = Get-ArtifactRecord -Path $TokenizerPath -ExpectedBytes -1 -ExpectedSha256 '' -Required $false
$artifacts.xsa = Get-ArtifactRecord -Path $XsaPath -ExpectedBytes -1 -ExpectedSha256 ''
$artifacts.bitstream = Get-ArtifactRecord -Path $BitstreamPath -ExpectedBytes -1 -ExpectedSha256 ''
$artifacts.elf = Get-ArtifactRecord -Path $ElfPath -ExpectedBytes -1 -ExpectedSha256 ''

$region0Record = [ordered]@{
    checked = $false
    address = $null
    size = $null
    matches = $false
}

$rtlSourceAlignment = [ordered]@{
    checked = $false
    generator_core_count = $null
    generator_dma_split = $null
    generator_cmd_addr_width = $null
    imported_rtl_core_count = $null
    imported_rtl_hp_port_count = $null
    imported_rtl_addr_width = $null
    generator_spinal_version = $null
    imported_rtl_spinal_version = $null
    entrypoint_uses_config = $false
    topology_matches = $false
    address_width_matches = $false
    version_matches = $false
    matches = $false
}

if ((Test-Path -LiteralPath $GeneratorPath -PathType Leaf) -and
    (Test-Path -LiteralPath $GeneratorEntrypointPath -PathType Leaf) -and
    (Test-Path -LiteralPath $GeneratorBuildPath -PathType Leaf) -and
    (Test-Path -LiteralPath $ImportedRtlPath -PathType Leaf)) {
    $activeGeneratorText = (Get-Content -LiteralPath $GeneratorPath | Where-Object {
        -not $_.TrimStart().StartsWith('//')
    }) -join "`n"
    $generatorCoreMatch = [regex]::Match(
        $activeGeneratorText, 'val\s+numOfCore\s*=\s*(\d+)'
    )
    $generatorDmaMatch = [regex]::Match(
        $activeGeneratorText, 'val\s+DMA_SPLIT\s*=\s*List\(([^)]*)\)'
    )
    $generatorAddressMatch = [regex]::Match(
        $activeGeneratorText, 'val\s+cmdAddrWidth\s*=\s*List\(([^)]*)\)'
    )
    $generatorEntrypointText = Get-Content -LiteralPath $GeneratorEntrypointPath -Raw
    $importedRtlText = Get-Content -LiteralPath $ImportedRtlPath -Raw
    $generatorBuildText = (Get-Content -LiteralPath $GeneratorBuildPath | Where-Object {
        -not $_.TrimStart().StartsWith('//')
    }) -join "`n"
    $importedCoreMatches = [regex]::Matches(
        $importedRtlText, 'DataPath\s+coreArea_(\d+)_core\s*\('
    )
    $importedHpMatches = [regex]::Matches(
        $importedRtlText, 'output\s+wire\s+m_axi_hp_0_(\d+)_arvalid'
    )
    $importedAddressMatches = [regex]::Matches(
        $importedRtlText, 'output\s+wire\s+\[(\d+):0\]\s+m_axi_hp_0_\d+_araddr'
    )
    $generatorVersionMatch = [regex]::Match(
        $generatorBuildText, 'val\s+spinalVersion\s*=\s*"([^"]+)"'
    )
    $importedVersionMatch = [regex]::Match(
        $importedRtlText, 'Generator\s*:\s*SpinalHDL\s+v([^\s]+)'
    )

    $generatorCoreCount = if ($generatorCoreMatch.Success) {
        [int]$generatorCoreMatch.Groups[1].Value
    } else {
        $null
    }
    [int[]]$generatorDmaSplit = if ($generatorDmaMatch.Success) {
        $generatorDmaMatch.Groups[1].Value -split ',' | ForEach-Object {
            [int]$_.Trim()
        }
    } else {
        @()
    }
    [int[]]$generatorAddressWidths = if ($generatorAddressMatch.Success) {
        $generatorAddressMatch.Groups[1].Value -split ',' | ForEach-Object {
            [int]$_.Trim()
        }
    } else {
        @()
    }
    $importedCoreIds = @($importedCoreMatches | ForEach-Object {
        [int]$_.Groups[1].Value
    } | Sort-Object -Unique)
    $importedHpIds = @($importedHpMatches | ForEach-Object {
        [int]$_.Groups[1].Value
    } | Sort-Object -Unique)
    $importedAddressWidths = @($importedAddressMatches | ForEach-Object {
        [int]$_.Groups[1].Value + 1
    } | Sort-Object -Unique)

    $rtlSourceAlignment.checked = $true
    $rtlSourceAlignment.generator_core_count = $generatorCoreCount
    $rtlSourceAlignment.generator_dma_split = $generatorDmaSplit
    $rtlSourceAlignment.generator_cmd_addr_width = $generatorAddressWidths
    $rtlSourceAlignment.imported_rtl_core_count = $importedCoreIds.Count
    $rtlSourceAlignment.imported_rtl_hp_port_count = $importedHpIds.Count
    $rtlSourceAlignment.imported_rtl_addr_width = $importedAddressWidths
    $rtlSourceAlignment.generator_spinal_version = if ($generatorVersionMatch.Success) {
        $generatorVersionMatch.Groups[1].Value
    } else {
        $null
    }
    $rtlSourceAlignment.imported_rtl_spinal_version = if ($importedVersionMatch.Success) {
        $importedVersionMatch.Groups[1].Value
    } else {
        $null
    }
    $rtlSourceAlignment.entrypoint_uses_config = [regex]::IsMatch(
        $generatorEntrypointText, 'import\s+EdgeLLMKv260Config\._'
    )
    $rtlSourceAlignment.topology_matches =
        ($generatorCoreCount -eq $importedCoreIds.Count) -and
        (($generatorDmaSplit | Measure-Object -Sum).Sum -eq $importedHpIds.Count)
    $rtlSourceAlignment.address_width_matches =
        ($generatorAddressWidths.Count -eq $generatorCoreCount) -and
        ($importedAddressWidths.Count -eq 1) -and
        (($generatorAddressWidths | Where-Object {
            $_ -ne $importedAddressWidths[0]
        }).Count -eq 0)
    $rtlSourceAlignment.version_matches =
        $generatorVersionMatch.Success -and
        $importedVersionMatch.Success -and
        ($generatorVersionMatch.Groups[1].Value -eq $importedVersionMatch.Groups[1].Value)
    $rtlSourceAlignment.matches =
        $rtlSourceAlignment.entrypoint_uses_config -and
        $rtlSourceAlignment.topology_matches -and
        $rtlSourceAlignment.address_width_matches -and
        $rtlSourceAlignment.version_matches

    if (-not $rtlSourceAlignment.entrypoint_uses_config) {
        $errors.Add('SpinalHDL entry point does not use EdgeLLMKv260Config')
    }
    if (-not $rtlSourceAlignment.topology_matches) {
        $errors.Add(
            'Active SpinalHDL generator topology does not reproduce the imported KV260 RTL'
        )
    }
    if (-not $rtlSourceAlignment.version_matches) {
        $errors.Add(
            'Configured SpinalHDL version does not match the imported KV260 RTL generator version'
        )
    }
    if (-not $rtlSourceAlignment.address_width_matches) {
        $errors.Add(
            'Configured command address width does not match the imported KV260 RTL'
        )
    }
} else {
    $errors.Add('Unable to compare the SpinalHDL generator with the imported KV260 RTL')
}

if ((Test-Path -LiteralPath $NmPath -PathType Leaf) -and (Test-Path -LiteralPath $ElfPath -PathType Leaf)) {
    $nmLine = & $NmPath -n -S $ElfPath | Select-String -Pattern '\sregion_0$' | Select-Object -First 1
    $region0Record.checked = $true
    if ($null -eq $nmLine) {
        $errors.Add('ELF does not export a region_0 symbol')
    } else {
        $fields = ($nmLine.Line.Trim() -split '\s+')
        $region0Record.address = $fields[0].ToLowerInvariant()
        $region0Record.size = $fields[1].ToLowerInvariant()
        $region0Record.matches = ($region0Record.address -eq $expectedRegion0Address) -and
            ($region0Record.size -eq $expectedRegion0Size)
        if (-not $region0Record.matches) {
            $errors.Add("region_0 mismatch: address=$($region0Record.address), size=$($region0Record.size)")
        }
    }
} else {
    $errors.Add('Unable to inspect region_0 because the ELF or nm tool is missing')
}

$detectedPorts = @([System.IO.Ports.SerialPort]::GetPortNames() | Sort-Object)
$ftdiConsolePresent = ($detectedPorts -contains 'COM8') -or ($detectedPorts -contains 'COM9')
if (-not $ftdiConsolePresent) {
    $warnings.Add('Historical KV260 ports COM8/COM9 are not currently enumerated')
}

$result = [ordered]@{
    schema_version = 1
    gate = if ($errors.Count -eq 0) { 'P0_HOST_AUDIT_GO' } else { 'P0_HOST_AUDIT_NO_GO' }
    timestamp = (Get-Date).ToString('o')
    repository = [ordered]@{
        root = $repoRoot
        branch = $repoBranch
        commit = $repoCommit
        status = $repoStatus
    }
    expected = [ordered]@{
        model0_bytes = $expectedModel0Bytes
        model1_bytes = $expectedModel1Bytes
        model0_sha256 = $ExpectedModel0Sha256.ToLowerInvariant()
        model1_sha256 = $ExpectedModel1Sha256.ToLowerInvariant()
        region0_address = $expectedRegion0Address
        region0_size = $expectedRegion0Size
    }
    artifacts = $artifacts
    region0 = $region0Record
    rtl_source_alignment = $rtlSourceAlignment
    serial = [ordered]@{
        detected_ports = $detectedPorts
        historical_kv260_ports_present = $ftdiConsolePresent
    }
    errors = @($errors)
    warnings = @($warnings)
}

$result | ConvertTo-Json -Depth 8

if ($errors.Count -ne 0) {
    exit 1
}
