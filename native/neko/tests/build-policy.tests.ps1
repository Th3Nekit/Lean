[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\..\.."))
$buildScript = Join-Path $repoRoot "native\neko\build.ps1"
$lockFile = Join-Path $repoRoot "native\versions.lock"
$artifactPath = Join-Path $repoRoot "artifacts\sing-box"
$contractPath = Join-Path $repoRoot "native\neko\aar-contract.txt"
$testRoot = Join-Path $repoRoot ".native-work\policy-tests"
$targetRoot = Join-Path $repoRoot ".native-work\policy-targets"

function Invoke-BuildValidation {
    param(
        [Parameter(Mandatory = $true)]
        [string]$NativeWorkRoot,

        [Parameter(Mandatory = $true)]
        [string]$ValidatorOutputPath
    )

    & pwsh -NoLogo -NoProfile -File $buildScript `
        -NativeWorkRoot $NativeWorkRoot `
        -ValidatorOutputPath $ValidatorOutputPath `
        -ValidateOnly *> $null

    return $LASTEXITCODE
}

function Assert-Fails {
    param(
        [Parameter(Mandatory = $true)]
        [scriptblock]$Action,

        [Parameter(Mandatory = $true)]
        [string]$Because
    )

    $exitCode = & $Action
    if ($exitCode -eq 0) {
        throw "Expected failure: $Because"
    }
}

function Assert-Succeeds {
    param(
        [Parameter(Mandatory = $true)]
        [scriptblock]$Action,

        [Parameter(Mandatory = $true)]
        [string]$Because
    )

    $exitCode = & $Action
    if ($exitCode -ne 0) {
        throw "Expected success: $Because"
    }
}

function Remove-TestLink {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    if (Test-Path -LiteralPath $Path) {
        Remove-Item -LiteralPath $Path -Force
    }
}

function Remove-TestTree {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,

        [Parameter(Mandatory = $true)]
        [string]$AllowedRoot
    )

    $absolutePath = [IO.Path]::GetFullPath($Path)
    $absoluteRoot = [IO.Path]::GetFullPath($AllowedRoot).TrimEnd(
        [IO.Path]::DirectorySeparatorChar,
        [IO.Path]::AltDirectorySeparatorChar
    )
    $prefix = "$absoluteRoot$([IO.Path]::DirectorySeparatorChar)"
    if (-not $absolutePath.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to clean test path outside owned root: $absolutePath"
    }

    if (Test-Path -LiteralPath $absolutePath) {
        Remove-Item -LiteralPath $absolutePath -Recurse -Force
    }
}

$caseId = [Guid]::NewGuid().ToString("N")
$caseRoot = Join-Path $testRoot $caseId
$caseTarget = Join-Path $targetRoot $caseId

try {
    Assert-Succeeds `
        -Action { Invoke-BuildValidation -NativeWorkRoot $caseRoot -ValidatorOutputPath $artifactPath } `
        -Because "the exact ignored validator artifact path must be accepted"

    Assert-Fails `
        -Action { Invoke-BuildValidation -NativeWorkRoot $caseRoot -ValidatorOutputPath $lockFile } `
        -Because "a tracked repository file must never be accepted as validator output"

    Assert-Fails `
        -Action {
            Invoke-BuildValidation `
                -NativeWorkRoot $caseRoot `
                -ValidatorOutputPath (Join-Path $repoRoot "artifacts\other")
        } `
        -Because "only artifacts/sing-box is an allowed validator output"

    foreach ($derivedPath in @("archives", "go-archives", "go-toolchains", "gopath", "gomodcache", "gocache")) {
        $linkCaseRoot = Join-Path $caseRoot $derivedPath
        $linkTarget = Join-Path $caseTarget $derivedPath
        $linkPath = Join-Path $linkCaseRoot $derivedPath

        New-Item -ItemType Directory -Path $linkCaseRoot -Force | Out-Null
        New-Item -ItemType Directory -Path $linkTarget -Force | Out-Null

        if ($IsWindows) {
            New-Item -ItemType Junction -Path $linkPath -Target $linkTarget | Out-Null
        } else {
            New-Item -ItemType SymbolicLink -Path $linkPath -Target $linkTarget | Out-Null
        }

        try {
            Assert-Fails `
                -Action {
                    Invoke-BuildValidation `
                        -NativeWorkRoot $linkCaseRoot `
                        -ValidatorOutputPath $artifactPath
                } `
                -Because "$derivedPath must reject a reparse-point or symlink write target"
        } finally {
            Remove-TestLink -Path $linkPath
        }
    }

    $buildSource = Get-Content -LiteralPath $buildScript -Raw
    foreach ($forbiddenToken in @("LEAN_CORE_GO", "LEAN_GOMOBILE_GO", "Resolve-GoTool")) {
        if ($buildSource.Contains($forbiddenToken, [StringComparison]::Ordinal)) {
            throw "Untrusted host-toolchain escape hatch remains in build.ps1: $forbiddenToken"
        }
    }
    foreach ($requiredToken in @(
        "Get-VerifiedGoArchive",
        "Get-VerifiedGoToolchain",
        "GOTOOLCHAIN",
        "Apply-NekoDnsCompletionPatch",
        "targetBeforeSha256",
        "targetAfterSha256",
        "TestExchangeContextSuccessCompletesLookupWait"
    )) {
        if (-not $buildSource.Contains($requiredToken, [StringComparison]::Ordinal)) {
            throw "Pinned Go toolchain enforcement is missing from build.ps1: $requiredToken"
        }
    }

    $contractClasses = @(
        Get-Content -LiteralPath $contractPath |
            ForEach-Object { ($_ -split "\|", 2)[0] } |
            Sort-Object -Unique
    )
    $expectedClasses = @(
        "libcore.BoxInstance",
        "libcore.BoxPlatformInterface",
        "libcore.ExchangeContext",
        "libcore.Func",
        "libcore.Libcore",
        "libcore.LocalDNSTransport",
        "libcore.NB4AInterface"
    )
    if (($contractClasses -join "`n") -cne ($expectedClasses -join "`n")) {
        throw "AAR contract does not cover the complete claimed bridge-class set."
    }
} finally {
    foreach ($derivedPath in @("archives", "go-archives", "go-toolchains", "gopath", "gomodcache", "gocache")) {
        Remove-TestLink -Path (Join-Path (Join-Path $caseRoot $derivedPath) $derivedPath)
    }
    Remove-TestTree -Path $caseRoot -AllowedRoot $testRoot
    Remove-TestTree -Path $caseTarget -AllowedRoot $targetRoot
}

Write-Host "Native build policy tests passed."
$global:LASTEXITCODE = 0
