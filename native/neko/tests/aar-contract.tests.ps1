[CmdletBinding()]
param(
    [string]$AarPath
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\..\.."))
. (Join-Path $repoRoot "native\neko\elf-contract.ps1")
if ([string]::IsNullOrWhiteSpace($AarPath)) {
    $AarPath = Join-Path $repoRoot "app\libs\libcore.aar"
}
$aar = [IO.Path]::GetFullPath($AarPath)
$contractPath = Join-Path $repoRoot "native\neko\aar-contract.txt"
$scratchParent = Join-Path ([IO.Path]::GetTempPath()) "lean-aar-contract-tests"
$scratch = Join-Path $scratchParent ([Guid]::NewGuid().ToString("N"))

if (-not (Test-Path -LiteralPath $aar -PathType Leaf)) {
    throw "AAR is missing: $aar"
}
if (-not (Test-Path -LiteralPath $contractPath -PathType Leaf)) {
    throw "AAR contract is missing: $contractPath"
}

New-Item -ItemType Directory -Path $scratch -Force | Out-Null
try {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($aar)
    try {
        $entryNames = @($archive.Entries | ForEach-Object { $_.FullName })
        $abiContracts = [ordered]@{
            "armeabi-v7a" = @{ expectedClass = 1; expectedMachine = 40 }
            "arm64-v8a" = @{ expectedClass = 2; expectedMachine = 183 }
            "x86" = @{ expectedClass = 1; expectedMachine = 3 }
            "x86_64" = @{ expectedClass = 2; expectedMachine = 62 }
        }
        $expectedJniEntries = @(
            $abiContracts.Keys |
                ForEach-Object { "jni/$_/libgojni.so" } |
                Sort-Object
        )
        $actualJniEntries = @(
            $archive.Entries |
                Where-Object {
                    $_.FullName.StartsWith("jni/", [StringComparison]::Ordinal) -and
                    -not $_.FullName.EndsWith("/", [StringComparison]::Ordinal)
                } |
                ForEach-Object { $_.FullName } |
                Sort-Object
        )
        if (
            $actualJniEntries.Count -ne $expectedJniEntries.Count -or
            ($actualJniEntries -join "`n") -cne ($expectedJniEntries -join "`n")
        ) {
            throw "AAR JNI entry set is not exact: $($actualJniEntries -join ', ')"
        }
        foreach ($abi in $abiContracts.Keys) {
            $entryName = "jni/$abi/libgojni.so"
            $entry = $archive.GetEntry($entryName)
            if ($null -eq $entry -or $entry.Length -eq 0) {
                throw "AAR has an empty or missing $entryName."
            }
            $memory = [IO.MemoryStream]::new()
            $input = $entry.Open()
            try {
                $input.CopyTo($memory)
            } finally {
                $input.Dispose()
            }
            try {
                $bytes = $memory.ToArray()
                Assert-JniElfContract -Bytes $bytes -Abi $abi -EntryName $entryName
                $expectedClass = [int]$abiContracts[$abi].expectedClass
                $expectedMachine = [int]$abiContracts[$abi].expectedMachine
                $actualMachine = [int](
                    [uint16]$bytes[18] -bor
                    ([uint16]$bytes[19] -shl 8)
                )
                if ([int]$bytes[4] -ne $expectedClass -or $actualMachine -ne $expectedMachine) {
                    throw "$entryName failed the independent ELF class/machine check."
                }
            } finally {
                $memory.Dispose()
            }
        }
        foreach ($entryName in @("classes.jar", "AndroidManifest.xml")) {
            $entry = $archive.GetEntry($entryName)
            if ($null -eq $entry) {
                throw "AAR is missing $entryName."
            }
            [IO.Compression.ZipFileExtensions]::ExtractToFile(
                $entry,
                (Join-Path $scratch $entryName)
            )
        }
    } finally {
        $archive.Dispose()
    }

    $manifestPath = Join-Path $scratch "AndroidManifest.xml"
    $manifest = [Text.Encoding]::UTF8.GetString(
        [IO.File]::ReadAllBytes($manifestPath)
    )
    if ($manifest -notmatch 'minSdkVersion\s*=\s*["'']21["'']') {
        throw "AAR manifest does not declare minSdkVersion 21."
    }

    $classesPath = Join-Path $scratch "classes.jar"
    $javap = (Get-Command javap -ErrorAction Stop).Source
    $classApis = @{}
    foreach ($line in Get-Content -LiteralPath $contractPath) {
        if ([string]::IsNullOrWhiteSpace($line)) {
            continue
        }
        $parts = $line -split "\|", 2
        $className = $parts[0]
        $signature = $parts[1]
        if (-not $classApis.ContainsKey($className)) {
            $api = @(& $javap -public -classpath $classesPath $className 2>&1)
            if ($LASTEXITCODE -ne 0) {
                throw "javap failed for $className.`n$($api -join "`n")"
            }
            $classApis[$className] = @(
                $api |
                    ForEach-Object { ([string]$_).Trim() } |
                    Where-Object {
                        $_.StartsWith("public ", [StringComparison]::Ordinal)
                    }
            )
        }
        if ($signature -cnotin $classApis[$className]) {
            throw "$className is missing exact signature: $signature"
        }
    }
} finally {
    $absoluteScratch = [IO.Path]::GetFullPath($scratch)
    $absoluteParent = [IO.Path]::GetFullPath($scratchParent).TrimEnd(
        [IO.Path]::DirectorySeparatorChar,
        [IO.Path]::AltDirectorySeparatorChar
    )
    $ownedPrefix = "$absoluteParent$([IO.Path]::DirectorySeparatorChar)"
    if (-not $absoluteScratch.StartsWith($ownedPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to clean an unowned AAR contract test path."
    }
    if (Test-Path -LiteralPath $absoluteScratch) {
        Remove-Item -LiteralPath $absoluteScratch -Recurse -Force
    }
}

Write-Host "Independent AAR ABI, manifest, and exact API contract tests passed."
