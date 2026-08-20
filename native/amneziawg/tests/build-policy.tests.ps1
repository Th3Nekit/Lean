[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\..\.."))
$policyPath = Join-Path $repoRoot "native\amneziawg\lib\NativeBuildPolicy.ps1"
$lockPath = Join-Path $repoRoot "native\versions.lock"

. $policyPath

function Assert-Throws {
    param(
        [Parameter(Mandatory = $true)]
        [scriptblock]$Action,

        [Parameter(Mandatory = $true)]
        [string]$Because
    )

    try {
        & $Action
    } catch {
        return
    }
    throw "Expected failure: $Because"
}

function Assert-Equal {
    param(
        [Parameter(Mandatory = $true)]
        [AllowNull()]
        [object]$Actual,

        [Parameter(Mandatory = $true)]
        [AllowNull()]
        [object]$Expected,

        [Parameter(Mandatory = $true)]
        [string]$Because
    )

    if ($Actual -cne $Expected) {
        throw "$Because. Expected '$Expected', got '$Actual'."
    }
}

$lock = Read-AmneziaWgLock -LockPath $lockPath
Assert-Equal $lock.goVersion "1.24.4" "the pinned Go version must remain exact"
Assert-Equal $lock.ndkVersion "26.1.10909125" "the pinned NDK version must remain exact"
Assert-Equal $lock.androidApi 24 "the Android API floor must remain exact"
Assert-Equal ($lock.abis -join ",") "armeabi-v7a,arm64-v8a,x86,x86_64" "the ABI set must remain exact"
Assert-Equal $lock.androidSource.revision "4116c836241f737badb99dcd4e990600d46e4c65" "the Android revision must remain exact"
Assert-Equal $lock.goSource.tag "v0.2.16" "the Go tag must remain exact"
Assert-Equal $lock.goSource.revision "730d6c39d0c4e348a3d080bebe496664215e5c99" "the tag mapping must remain exact"

$tempRoot = Join-Path ([IO.Path]::GetTempPath()) ("lean-awg-policy-" + [Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $tempRoot | Out-Null
try {
    $hashFile = Join-Path $tempRoot "hash.txt"
    Set-Content -LiteralPath $hashFile -Value "pinned" -NoNewline
    $correctHash = (Get-FileHash -LiteralPath $hashFile -Algorithm SHA256).Hash.ToLowerInvariant()
    Assert-VerifiedSha256 -Path $hashFile -ExpectedSha256 $correctHash -Label "fixture"
    Assert-Throws {
        Assert-VerifiedSha256 -Path $hashFile -ExpectedSha256 ("0" * 64) -Label "fixture"
    } "a wrong source, toolchain, module, patch, or output hash must be rejected"

    $strictLock = Join-Path $tempRoot "strict-lock.json"
    Set-Content -LiteralPath $strictLock -Value @'
{
  "schema": 1,
  "amneziawg": {
    "goVersion": "1.24.4",
    "goVersion": "system",
    "unexpected": true
  }
}
'@
    Assert-Throws {
        Read-StrictJsonDocument -Path $strictLock | Out-Null
    } "duplicate JSON fields must be rejected before parsing"

    $identityCases = @(
        [ordered]@{
            label = "a wrong Android revision mapping"
            mutate = {
                param($fixture)
                $fixture.amneziawg.androidSource.revision = "0" * 40
            }
        },
        [ordered]@{
            label = "a missing Go tag mapping"
            mutate = {
                param($fixture)
                $fixture.amneziawg.goSource.PSObject.Properties.Remove("tag")
            }
        },
        [ordered]@{
            label = "a wrong Go tag-to-revision mapping"
            mutate = {
                param($fixture)
                $fixture.amneziawg.goSource.revision = "f" * 40
            }
        }
    )
    foreach ($case in $identityCases) {
        $fixture = (Get-Content -LiteralPath $lockPath -Raw) | ConvertFrom-Json
        & $case.mutate $fixture
        $identityLock = Join-Path $tempRoot "identity-lock.json"
        $fixture | ConvertTo-Json -Depth 16 | Set-Content -LiteralPath $identityLock -Encoding utf8NoBOM
        Assert-Throws {
            Read-AmneziaWgLock -LockPath $identityLock | Out-Null
        } $case.label
    }

    foreach ($entries in @(
        @("../escape"),
        @("safe/../../escape"),
        @("/absolute"),
        @("C:/absolute"),
        @("safe", "safe/../escape")
    )) {
        Assert-Throws {
            Assert-SafeArchiveEntries -Entries $entries -Label "fixture archive"
        } "archive traversal and absolute entries must be rejected"
    }
    Assert-SafeArchiveEntries -Entries @("root/", "root/file.txt", "root/sub/file.txt") -Label "fixture archive"

    $emptyArchiveFile = Join-Path $tempRoot "empty-archive-file"
    Write-ArchiveRegularFile -Path $emptyArchiveFile -DataStream $null
    Assert-Equal (Get-Item -LiteralPath $emptyArchiveFile).Length 0 "an empty regular archive entry must extract as an empty file"
    Assert-Equal `
        (Test-ArchiveModeExecutable -Mode ([IO.UnixFileMode]::UserRead -bor [IO.UnixFileMode]::UserWrite)) `
        $false `
        "a non-executable archive mode must remain non-executable"
    Assert-Equal `
        (Test-ArchiveModeExecutable -Mode ([IO.UnixFileMode]::UserRead -bor [IO.UnixFileMode]::GroupExecute)) `
        $true `
        "any executable archive mode must produce an owner-executable file"

    $taskRoot = Join-Path $tempRoot "owned"
    $outsideTaskRoot = Join-Path $tempRoot "outside"
    New-Item -ItemType Directory -Path $taskRoot | Out-Null
    New-Item -ItemType Directory -Path $outsideTaskRoot | Out-Null

    Assert-SafeTaskPath -Path (Join-Path $taskRoot "cache") -TaskRoot $taskRoot -RepositoryRoot $repoRoot
    foreach ($unsafePath in @(
        [IO.Path]::GetPathRoot($repoRoot),
        $repoRoot,
        (Join-Path $repoRoot "native\versions.lock"),
        $outsideTaskRoot
    )) {
        Assert-Throws {
            Assert-SafeTaskPath -Path $unsafePath -TaskRoot $taskRoot -RepositoryRoot $repoRoot
        } "root, repository, tracked output, and paths outside the task root must be rejected"
    }

    $linkTarget = Join-Path $outsideTaskRoot "link-target"
    $linkParent = Join-Path $taskRoot "link-parent"
    New-Item -ItemType Directory -Path $linkTarget | Out-Null
    if ($IsWindows) {
        New-Item -ItemType Junction -Path $linkParent -Target $linkTarget | Out-Null
    } else {
        New-Item -ItemType SymbolicLink -Path $linkParent -Target $linkTarget | Out-Null
    }
    try {
        Assert-Throws {
            Assert-SafeTaskPath -Path (Join-Path $linkParent "derived") -TaskRoot $taskRoot -RepositoryRoot $repoRoot
        } "every task directory must reject a reparse, junction, or symlink ancestor"
    } finally {
        Remove-Item -LiteralPath $linkParent -Force
    }

    $pinnedGo = Join-Path $taskRoot "toolchain\go\bin\go.exe"
    $systemGo = Join-Path $outsideTaskRoot "go.exe"
    Assert-PinnedExecutable -ActualPath $pinnedGo -ExpectedPath $pinnedGo -Label "Go"
    Assert-Throws {
        Assert-PinnedExecutable -ActualPath $systemGo -ExpectedPath $pinnedGo -Label "Go"
    } "a different PATH/system Go must never be accepted"

    $firstRelativeReplace = Get-DeterministicModuleReplacePath `
        -ModuleDirectory (Join-Path $taskRoot "first\sources\amneziawg-android") `
        -DependencyDirectory (Join-Path $taskRoot "first\sources\amneziawg-go")
    $secondRelativeReplace = Get-DeterministicModuleReplacePath `
        -ModuleDirectory (Join-Path $taskRoot "second-root-with-a-different-length\sources\amneziawg-android") `
        -DependencyDirectory (Join-Path $taskRoot "second-root-with-a-different-length\sources\amneziawg-go")
    Assert-Equal $firstRelativeReplace "../amneziawg-go" "the local Go replace must not embed the task root"
    Assert-Equal $secondRelativeReplace $firstRelativeReplace "independent task roots must use byte-identical Go replacement metadata"

    $validElf = [ordered]@{
        architecture = "AArch64"
        androidApi = 24
        soname = "libwg-go.so"
        cExports = @("awgGetConfig", "awgGetSocketV4", "awgGetSocketV6", "awgTurnOff", "awgTurnOn", "awgVersion")
        jniSymbols = @(
            "Java_org_amnezia_awg_GoBackend_awgGetConfig",
            "Java_org_amnezia_awg_GoBackend_awgGetSocketV4",
            "Java_org_amnezia_awg_GoBackend_awgGetSocketV6",
            "Java_org_amnezia_awg_GoBackend_awgTurnOff",
            "Java_org_amnezia_awg_GoBackend_awgTurnOn",
            "Java_org_amnezia_awg_GoBackend_awgVersion"
        )
    }
    Assert-ElfContract -ElfInfo $validElf -ExpectedArchitecture "AArch64" -ExpectedAndroidApi 24
    foreach ($field in @("architecture", "androidApi", "soname", "cExports", "jniSymbols")) {
        $invalid = [ordered]@{}
        foreach ($key in $validElf.Keys) {
            $invalid[$key] = $validElf[$key]
        }
        switch ($field) {
            "architecture" { $invalid[$field] = "ARM" }
            "androidApi" { $invalid[$field] = 23 }
            "soname" { $invalid[$field] = "libwrong.so" }
            "cExports" { $invalid[$field] = @($validElf.cExports | Where-Object { $_ -cne "awgVersion" }) }
            "jniSymbols" { $invalid[$field] = @($validElf.jniSymbols | Where-Object { $_ -cne "Java_org_amnezia_awg_GoBackend_awgVersion" }) }
        }
        Assert-Throws {
            Assert-ElfContract -ElfInfo $invalid -ExpectedArchitecture "AArch64" -ExpectedAndroidApi 24
        } "a wrong or missing ELF $field must be rejected"
    }

    Assert-OutputHashContract `
        -Expected ([ordered]@{ "arm64-v8a/libwg-go.so" = $correctHash }) `
        -Actual ([ordered]@{ "arm64-v8a/libwg-go.so" = $correctHash })
    Assert-Throws {
        Assert-OutputHashContract `
            -Expected ([ordered]@{ "arm64-v8a/libwg-go.so" = $correctHash }) `
            -Actual ([ordered]@{ "arm64-v8a/libwg-go.so" = ("f" * 64) })
    } "a wrong output hash must be rejected"
} finally {
    if (Test-Path -LiteralPath $tempRoot) {
        Remove-Item -LiteralPath $tempRoot -Recurse -Force
    }
}

Write-Host "AmneziaWG native build policy tests passed."
