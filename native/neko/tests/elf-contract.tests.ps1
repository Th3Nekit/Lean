[CmdletBinding()]
param(
    [string]$AarPath
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot "../../.."))
. (Join-Path $repoRoot "native/neko/elf-contract.ps1")

if ([string]::IsNullOrWhiteSpace($AarPath)) {
    $AarPath = Join-Path $repoRoot "app/libs/libcore.aar"
}
$aar = [IO.Path]::GetFullPath($AarPath)
if (-not (Test-Path -LiteralPath $aar -PathType Leaf)) {
    throw "AAR is missing: $aar"
}

$contracts = [ordered]@{
    "armeabi-v7a" = @{ expectedClass = 1; expectedMachine = 40 }
    "arm64-v8a" = @{ expectedClass = 2; expectedMachine = 183 }
    "x86" = @{ expectedClass = 1; expectedMachine = 3 }
    "x86_64" = @{ expectedClass = 2; expectedMachine = 62 }
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($aar)
try {
    $expectedEntries = @(
        $contracts.Keys |
            ForEach-Object { "jni/$_/libgojni.so" } |
            Sort-Object
    )
    $actualEntries = @(
        $archive.Entries |
            Where-Object {
                $_.FullName.StartsWith("jni/", [StringComparison]::Ordinal) -and
                -not $_.FullName.EndsWith("/", [StringComparison]::Ordinal)
            } |
            ForEach-Object { $_.FullName } |
            Sort-Object
    )
    if (
        $actualEntries.Count -ne $expectedEntries.Count -or
        ($actualEntries -join "`n") -cne ($expectedEntries -join "`n")
    ) {
        throw "AAR JNI entries are not the exact four-ABI contract."
    }

    foreach ($abi in $contracts.Keys) {
        $entryName = "jni/$abi/libgojni.so"
        $entry = $archive.GetEntry($entryName)
        $memory = [IO.MemoryStream]::new()
        $stream = $entry.Open()
        try {
            $stream.CopyTo($memory)
        } finally {
            $stream.Dispose()
        }
        try {
            $bytes = $memory.ToArray()
            Assert-JniElfContract -Bytes $bytes -Abi $abi -EntryName $entryName

            $corruptMachine = [byte[]]$bytes.Clone()
            $corruptMachine[18] = 0
            $corruptMachine[19] = 0
            $rejected = $false
            try {
                Assert-JniElfContract `
                    -Bytes $corruptMachine `
                    -Abi $abi `
                    -EntryName "$entryName (corrupt test)"
            } catch {
                $rejected = $true
            }
            if (-not $rejected) {
                throw "ELF gate accepted a corrupted machine field for $abi."
            }
        } finally {
            $memory.Dispose()
        }
    }
} finally {
    $archive.Dispose()
}

Write-Host "ELF class and machine contracts passed for all four JNI ABIs."
