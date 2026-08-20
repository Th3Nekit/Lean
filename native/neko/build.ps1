[CmdletBinding()]
param(
    [string]$NativeWorkRoot,
    [string]$WorkDirectory,
    [string]$OutputPath,
    [string]$ValidatorOutputPath,
    [switch]$ValidateOnly,
    [switch]$VerifySourcesOnly
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

. (Join-Path $PSScriptRoot "elf-contract.ps1")

function Get-AbsolutePath {
    param(
        [Parameter(Mandatory)]
        [string]$PathValue,
        [Parameter(Mandatory)]
        [string]$BasePath
    )

    if ([string]::IsNullOrWhiteSpace($PathValue)) {
        throw "A required path is empty."
    }
    if ([System.Management.Automation.WildcardPattern]::ContainsWildcardCharacters($PathValue)) {
        throw "Wildcards are not allowed in native build paths: $PathValue"
    }

    $candidate = if ([IO.Path]::IsPathFullyQualified($PathValue)) {
        $PathValue
    } else {
        Join-Path $BasePath $PathValue
    }
    return [IO.Path]::GetFullPath($candidate).TrimEnd(
        [IO.Path]::DirectorySeparatorChar,
        [IO.Path]::AltDirectorySeparatorChar
    )
}

function Get-PathComparison {
    if ($IsWindows) {
        return [StringComparison]::OrdinalIgnoreCase
    }
    return [StringComparison]::Ordinal
}

function Test-PathEqual {
    param([string]$Left, [string]$Right)
    return $Left.Equals($Right, (Get-PathComparison))
}

function Test-StrictDescendant {
    param([string]$Candidate, [string]$Root)
    $prefix = $Root + [IO.Path]::DirectorySeparatorChar
    return $Candidate.StartsWith($prefix, (Get-PathComparison))
}

function Assert-NoReparsePoint {
    param([string]$PathValue)

    $cursor = $PathValue
    while (-not [string]::IsNullOrEmpty($cursor)) {
        if (Test-Path -LiteralPath $cursor) {
            $item = Get-Item -LiteralPath $cursor -Force
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw "Reparse points are not accepted in native build paths: $cursor"
            }
        }
        $parent = [IO.Directory]::GetParent($cursor)
        if ($null -eq $parent) {
            break
        }
        $cursor = $parent.FullName
    }
}

function Assert-SafeRoot {
    param([string]$PathValue, [string]$RepositoryRoot)

    $pathRoot = [IO.Path]::GetPathRoot($PathValue).TrimEnd(
        [IO.Path]::DirectorySeparatorChar,
        [IO.Path]::AltDirectorySeparatorChar
    )
    $trimmed = $PathValue.TrimEnd(
        [IO.Path]::DirectorySeparatorChar,
        [IO.Path]::AltDirectorySeparatorChar
    )
    if ([string]::IsNullOrWhiteSpace($trimmed) -or (Test-PathEqual $trimmed $pathRoot)) {
        throw "The native work root cannot be a filesystem root: $PathValue"
    }
    if (Test-PathEqual $trimmed $RepositoryRoot) {
        throw "The native work root cannot be the repository root."
    }
    Assert-NoReparsePoint $PathValue
}

function Assert-SafeDirectoryPath {
    param(
        [string]$PathValue,
        [string]$DesignatedRoot,
        [switch]$AllowRoot
    )

    if (
        -not ($AllowRoot -and (Test-PathEqual $PathValue $DesignatedRoot)) -and
        -not (Test-StrictDescendant $PathValue $DesignatedRoot)
    ) {
        throw "Directory must remain inside its designated root: $PathValue"
    }
    Assert-NoReparsePoint $PathValue
    if (
        (Test-Path -LiteralPath $PathValue) -and
        -not (Test-Path -LiteralPath $PathValue -PathType Container)
    ) {
        throw "Expected a directory path, found another item type: $PathValue"
    }
}

function Assert-SafeFilePath {
    param(
        [string]$PathValue,
        [string]$DesignatedRoot
    )

    if (-not (Test-StrictDescendant $PathValue $DesignatedRoot)) {
        throw "File must remain inside its designated root: $PathValue"
    }
    Assert-NoReparsePoint $PathValue
    if (
        (Test-Path -LiteralPath $PathValue) -and
        -not (Test-Path -LiteralPath $PathValue -PathType Leaf)
    ) {
        throw "Expected a file path, found another item type: $PathValue"
    }
}

function New-SafeDirectory {
    param(
        [string]$PathValue,
        [string]$DesignatedRoot,
        [switch]$AllowRoot
    )

    Assert-SafeDirectoryPath $PathValue $DesignatedRoot -AllowRoot:$AllowRoot
    New-Item -ItemType Directory -Path $PathValue -Force | Out-Null
    Assert-SafeDirectoryPath $PathValue $DesignatedRoot -AllowRoot:$AllowRoot
}

function Remove-SafeTree {
    param([string]$Target, [string]$DesignatedRoot)

    if (-not (Test-StrictDescendant $Target $DesignatedRoot)) {
        throw "Refusing to remove a path outside the designated native work root: $Target"
    }
    if (Test-Path -LiteralPath $Target) {
        Assert-NoReparsePoint $Target
        Remove-Item -LiteralPath $Target -Recurse -Force
    }
}

function Invoke-Checked {
    param(
        [Parameter(Mandatory)]
        [string]$Executable,
        [Parameter(ValueFromRemainingArguments)]
        [string[]]$Arguments
    )

    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed with exit code ${LASTEXITCODE}: $Executable $($Arguments -join ' ')"
    }
}

function Assert-Value {
    param([object]$Actual, [object]$Expected, [string]$Label)
    if ([string]$Actual -cne [string]$Expected) {
        throw "$Label must be '$Expected', found '$Actual'."
    }
}

function Assert-StringSet {
    param([object[]]$Actual, [string[]]$Expected, [string]$Label)
    $actualText = @($Actual | ForEach-Object { [string]$_ }) -join ","
    $expectedText = $Expected -join ","
    if ($actualText -cne $expectedText) {
        throw "$Label must be '$expectedText', found '$actualText'."
    }
}

function Assert-Lock {
    param([object]$Lock)

    Assert-Value $Lock.schema 1 "lock schema"
    Assert-Value $Lock.toolchain.coreGo "1.23.6" "core Go version"
    Assert-Value $Lock.toolchain.gomobileGo "1.24.4" "gomobile bootstrap Go version"
    $goArchives = @{
        "1.23.6/linux-amd64" = @(
            "go1.23.6.linux-amd64.tar.gz",
            "https://go.dev/dl/go1.23.6.linux-amd64.tar.gz",
            "9379441ea310de000f33a4dc767bd966e72ab2826270e038e78b2c53c2e7802d"
        )
        "1.23.6/windows-amd64" = @(
            "go1.23.6.windows-amd64.zip",
            "https://go.dev/dl/go1.23.6.windows-amd64.zip",
            "53fec1586850b2cf5ad6438341ff7adc5f6700dd3ec1cfa3f5e8b141df190243"
        )
        "1.24.4/linux-amd64" = @(
            "go1.24.4.linux-amd64.tar.gz",
            "https://go.dev/dl/go1.24.4.linux-amd64.tar.gz",
            "77e5da33bb72aeaef1ba4418b6fe511bc4d041873cbf82e5aa6318740df98717"
        )
        "1.24.4/windows-amd64" = @(
            "go1.24.4.windows-amd64.zip",
            "https://go.dev/dl/go1.24.4.windows-amd64.zip",
            "b751a1136cb9d8a2e7ebb22c538c4f02c09b98138c7c8bfb78a54a4566c013b1"
        )
    }
    foreach ($entry in $goArchives.GetEnumerator()) {
        $parts = $entry.Key.Split("/")
        $archive = $Lock.toolchain.goDistributions.($parts[0]).($parts[1])
        Assert-Value $archive.file $entry.Value[0] "$($entry.Key) Go archive name"
        Assert-Value $archive.url $entry.Value[1] "$($entry.Key) Go archive URL"
        Assert-Value $archive.sha256 $entry.Value[2] "$($entry.Key) Go archive SHA-256"
    }
    Assert-Value $Lock.toolchain.jdkMajor 17 "JDK major"
    Assert-Value $Lock.toolchain.ndk "25.0.8775105" "NDK version"
    Assert-Value $Lock.toolchain.androidApi 21 "Android API"
    Assert-Value $Lock.toolchain.androidCompileSdk 35 "Android compile SDK"
    Assert-StringSet $Lock.build.abis @(
        "armeabi-v7a", "arm64-v8a", "x86", "x86_64"
    ) "gomobile ABIs"
    Assert-StringSet $Lock.build.tags @(
        "with_conntrack",
        "with_gvisor",
        "with_quic",
        "with_wireguard",
        "with_utls",
        "with_clash_api"
    ) "gomobile tags"
    Assert-Value $Lock.build.gomobileInit "skip-floating-gobind-install" "gomobile initialization policy"

    $expected = @{
        neko = @(
            "https://github.com/MatsuriDayo/NekoBoxForAndroid.git",
            "https://codeload.github.com/MatsuriDayo/NekoBoxForAndroid/tar.gz/5768494d8ae3c74a057bb6d46c0f8dc071b0d821",
            "5768494d8ae3c74a057bb6d46c0f8dc071b0d821",
            "8b7a35d4d884b8b060c883753b2f884db6d7787e782f48ceca216975915feadf"
        )
        singBox = @(
            "https://github.com/MatsuriDayo/sing-box.git",
            "https://codeload.github.com/MatsuriDayo/sing-box/tar.gz/aed32ee3066cdbc7d471e3e0415c5134088962df",
            "aed32ee3066cdbc7d471e3e0415c5134088962df",
            "402e02917da475e48062dbaef76265045fcb80831a84158c8b7fd566c32567b5"
        )
        libneko = @(
            "https://github.com/MatsuriDayo/libneko.git",
            "https://codeload.github.com/MatsuriDayo/libneko/tar.gz/1c47a3af71990a7b2192e03292b4d246c308ef0b",
            "1c47a3af71990a7b2192e03292b4d246c308ef0b",
            "91b0fcc44bb71b0b61d9c50411ae244550dd66084daaddb550fed9c6280847f7"
        )
        gomobile = @(
            "https://github.com/MatsuriDayo/gomobile.git",
            "https://codeload.github.com/MatsuriDayo/gomobile/tar.gz/17d6af34f6bd6d7e1e428e0c652c8b54a46bda4f",
            "17d6af34f6bd6d7e1e428e0c652c8b54a46bda4f",
            "492d77638c5481e61115bd72c73e63b1fe465d674619a420d51bfcaaacdf53ac"
        )
    }
    foreach ($name in @("neko", "singBox", "libneko", "gomobile")) {
        $component = $Lock.components.$name
        Assert-Value $component.repository $expected[$name][0] "$name repository"
        Assert-Value $component.archive $expected[$name][1] "$name archive"
        Assert-Value $component.revision $expected[$name][2] "$name revision"
        Assert-Value $component.archiveSha256 $expected[$name][3] "$name archive SHA-256"
    }

    Assert-Value $Lock.moduleFiles."neko/libcore/go.mod" `
        "f680ca59b25a691cdbc7c1a6f7cea06d7ea3dbb57421d7fa89b23eaea380a429" `
        "Neko libcore go.mod SHA-256"
    Assert-Value $Lock.moduleFiles."neko/libcore/go.sum" `
        "0e00618b77463b8b1b431049605b48c3bd901abf0447d4f6185e46591ba38889" `
        "Neko libcore go.sum SHA-256"
    Assert-Value $Lock.moduleFiles."libneko/go.mod" `
        "c6c8261ef832c7446d1d33a890d6c6fb04959daa93f5b14bc536ca10d85c1d30" `
        "libneko go.mod SHA-256"
    Assert-Value $Lock.moduleFiles."gomobile/go.mod" `
        "76cf44a207b4cb3594a83d1c6a195abaedeaf0f0645cb93a3b324fb92e3df0b7" `
        "gomobile go.mod SHA-256"
    Assert-Value $Lock.moduleFiles."gomobile/go.sum" `
        "7d7d1a6f5f6e4ad0750e0bbaf3c8048f011132e1baff6adf7ef39d32fa5e2d0c" `
        "gomobile go.sum SHA-256"

    $dnsPatch = $Lock.patches.nekoDnsSuccessCompletion
    Assert-Value $dnsPatch.file `
        "native/neko/patches/neko-dns-success-completion.patch" `
        "Neko DNS completion patch path"
    Assert-Value $dnsPatch.sha256 `
        "5d899d46f11183602dc7469aebe9bf235f50ebc8c5ec7ce61a50d15511f4b89f" `
        "Neko DNS completion patch SHA-256"
    Assert-Value $dnsPatch.target `
        "libcore/dns_box.go" `
        "Neko DNS completion patch target"
    Assert-Value $dnsPatch.targetBeforeSha256 `
        "5bdf5983b526a6bf6efe1b5f88d925eb70d62746ce9706c27754063fc360a8c5" `
        "Neko DNS completion target pre-patch SHA-256"
    Assert-Value $dnsPatch.targetAfterSha256 `
        "1dba67718c229df8eb6d45e8cfd49cfa2f94f883d4201dba4ef28a4e76ec37ec" `
        "Neko DNS completion target post-patch SHA-256"
    Assert-Value $dnsPatch.regressionTest.file `
        "native/neko/tests/dns_box_success_test.go" `
        "Neko DNS regression test path"
    Assert-Value $dnsPatch.regressionTest.sha256 `
        "8f6aee242d484fddf9ec9bd8f5e06b4ca625efe336621d3954639a3553f1f9d6" `
        "Neko DNS regression test SHA-256"
    Assert-Value $dnsPatch.regressionTest.target `
        "libcore/dns_box_success_test.go" `
        "Neko DNS regression test target"

    $resetPatch = $Lock.patches.nekoResetNetwork
    Assert-Value $resetPatch.file `
        "native/neko/patches/neko-reset-network.patch" `
        "Neko reset-network patch path"
    Assert-Value $resetPatch.sha256 `
        "10889eadd1ed112c4fbffd1991a6a17717f18b2f936e8579773183e18b5b3039" `
        "Neko reset-network patch SHA-256"
    Assert-Value $resetPatch.target `
        "libcore/box.go" `
        "Neko reset-network patch target"
    Assert-Value $resetPatch.targetBeforeSha256 `
        "d0ae9e2d6808c8f535f86d7fef882fd1e1b5dddf61f6ba6ca34519116a69f882" `
        "Neko reset-network target pre-patch SHA-256"
    Assert-Value $resetPatch.targetAfterSha256 `
        "36cfc4edc1d95558b3aa511e4926355622719e173cce9599d4034ebccfd0f9c6" `
        "Neko reset-network target post-patch SHA-256"
}

function Assert-FileHash {
    param([string]$PathValue, [string]$ExpectedHash, [string]$Label)
    if (-not (Test-Path -LiteralPath $PathValue -PathType Leaf)) {
        throw "$Label is missing: $PathValue"
    }
    $actual = (Get-FileHash -LiteralPath $PathValue -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -cne $ExpectedHash.ToLowerInvariant()) {
        throw "$Label SHA-256 mismatch. Expected $ExpectedHash, found $actual."
    }
}

function Assert-NormalizedTextHash {
    param([string]$PathValue, [string]$ExpectedHash, [string]$Label)

    if (-not (Test-Path -LiteralPath $PathValue -PathType Leaf)) {
        throw "$Label is missing: $PathValue"
    }
    $text = [IO.File]::ReadAllText($PathValue)
    $normalized = $text.Replace("`r`n", "`n").Replace("`r", "`n")
    $bytes = [Text.UTF8Encoding]::new($false).GetBytes($normalized)
    $sha256 = [Security.Cryptography.SHA256]::Create()
    try {
        $actual = [Convert]::ToHexString(
            $sha256.ComputeHash($bytes)
        ).ToLowerInvariant()
    } finally {
        $sha256.Dispose()
    }
    if ($actual -cne $ExpectedHash.ToLowerInvariant()) {
        throw "$Label normalized SHA-256 mismatch. Expected $ExpectedHash, found $actual."
    }
}

function Get-SourceTreeHashes {
    param([string]$SourceRoot)

    $comparison = if ($IsWindows) {
        [StringComparer]::OrdinalIgnoreCase
    } else {
        [StringComparer]::Ordinal
    }
    $hashes = [Collections.Generic.Dictionary[string, string]]::new($comparison)
    $reparsePoint = Get-ChildItem -LiteralPath $SourceRoot -Recurse -Force |
        Where-Object {
            ($_.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0
        } |
        Select-Object -First 1
    if ($null -ne $reparsePoint) {
        throw "Source tree contains a reparse point: $($reparsePoint.FullName)"
    }
    foreach ($file in Get-ChildItem -LiteralPath $SourceRoot -File -Recurse -Force) {
        $relative = [IO.Path]::GetRelativePath($SourceRoot, $file.FullName).Replace("\", "/")
        $hashes.Add(
            $relative,
            (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        )
    }
    return ,$hashes
}

function Apply-NekoDnsCompletionPatch {
    param(
        [object]$Patch,
        [string]$RepositoryRoot,
        [string]$NekoRoot
    )

    $patchPath = Get-AbsolutePath ([string]$Patch.file) $RepositoryRoot
    if (-not (Test-StrictDescendant $patchPath $RepositoryRoot)) {
        throw "Neko DNS patch must remain inside the repository."
    }
    Assert-NoReparsePoint $patchPath
    Assert-NormalizedTextHash `
        $patchPath `
        ([string]$Patch.sha256) `
        "Neko DNS completion patch"

    $targetPath = Get-AbsolutePath ([string]$Patch.target) $NekoRoot
    Assert-SafeFilePath $targetPath $NekoRoot
    Assert-FileHash `
        $targetPath `
        ([string]$Patch.targetBeforeSha256) `
        "Neko DNS completion target before patch"
    $beforeHashes = Get-SourceTreeHashes $NekoRoot
    $sourceText = [IO.File]::ReadAllText($targetPath)
    $oldBlock = (
        "`t}), func(it string) netip.Addr {`n" +
        "`t`treturn M.ParseSocksaddrHostPort(it, 0).Unwrap().Addr`n" +
        "`t})`n" +
        "}"
    )
    $newBlock = (
        "`t}), func(it string) netip.Addr {`n" +
        "`t`treturn M.ParseSocksaddrHostPort(it, 0).Unwrap().Addr`n" +
        "`t})`n" +
        "`tc.done()`n" +
        "}"
    )
    if ([regex]::Matches($sourceText, [regex]::Escape($oldBlock)).Count -ne 1) {
        throw "Locked Neko DNS patch context must occur exactly once."
    }
    $patchedText = $sourceText.Replace($oldBlock, $newBlock)
    [IO.File]::WriteAllText(
        $targetPath,
        $patchedText,
        [Text.UTF8Encoding]::new($false)
    )
    Assert-FileHash `
        $targetPath `
        ([string]$Patch.targetAfterSha256) `
        "Neko DNS completion target after patch"
    $afterHashes = Get-SourceTreeHashes $NekoRoot

    $changedFiles = [Collections.Generic.List[string]]::new()
    foreach ($path in $beforeHashes.Keys) {
        if (-not $afterHashes.ContainsKey($path) -or $afterHashes[$path] -cne $beforeHashes[$path]) {
            $changedFiles.Add($path)
        }
    }
    foreach ($path in $afterHashes.Keys) {
        if (-not $beforeHashes.ContainsKey($path)) {
            $changedFiles.Add($path)
        }
    }
    $changedFiles.Sort([StringComparer]::Ordinal)
    if (($changedFiles -join "`n") -cne ([string]$Patch.target)) {
        throw "Neko DNS patch modified unexpected files: $($changedFiles -join ', ')"
    }

    $testSource = Get-AbsolutePath ([string]$Patch.regressionTest.file) $RepositoryRoot
    if (-not (Test-StrictDescendant $testSource $RepositoryRoot)) {
        throw "Neko DNS regression test must remain inside the repository."
    }
    Assert-NoReparsePoint $testSource
    Assert-NormalizedTextHash `
        $testSource `
        ([string]$Patch.regressionTest.sha256) `
        "Neko DNS regression test"

    $testTarget = Get-AbsolutePath ([string]$Patch.regressionTest.target) $NekoRoot
    Assert-SafeFilePath $testTarget $NekoRoot
    if (Test-Path -LiteralPath $testTarget) {
        throw "Pinned Neko source unexpectedly contains the DNS regression test target."
    }
    Copy-Item -LiteralPath $testSource -Destination $testTarget
    Assert-NoReparsePoint $testTarget
    Assert-NormalizedTextHash `
        $testTarget `
        ([string]$Patch.regressionTest.sha256) `
        "Installed Neko DNS regression test"

    Write-Host "Applied locked Neko DNS completion patch and regression test."
}

function Apply-NekoResetNetworkPatch {
    param(
        [object]$Patch,
        [string]$RepositoryRoot,
        [string]$NekoRoot
    )

    $patchPath = Get-AbsolutePath ([string]$Patch.file) $RepositoryRoot
    if (-not (Test-StrictDescendant $patchPath $RepositoryRoot)) {
        throw "Neko reset-network patch must remain inside the repository."
    }
    Assert-NoReparsePoint $patchPath
    Assert-NormalizedTextHash `
        $patchPath `
        ([string]$Patch.sha256) `
        "Neko reset-network patch"

    $targetPath = Get-AbsolutePath ([string]$Patch.target) $NekoRoot
    Assert-SafeFilePath $targetPath $NekoRoot
    Assert-FileHash `
        $targetPath `
        ([string]$Patch.targetBeforeSha256) `
        "Neko reset-network target before patch"
    $beforeHashes = Get-SourceTreeHashes $NekoRoot
    $sourceText = [IO.File]::ReadAllText($targetPath)
    $oldBlock = (
        "func (b *BoxInstance) Wake() {`n" +
        "`tif b.pauseManager != nil {`n" +
        "`t`tb.pauseManager.DeviceWake()`n" +
        "`t}`n" +
        "}`n" +
        "`n" +
        "func (b *BoxInstance) SetAsMain() {"
    )
    $newBlock = (
        "func (b *BoxInstance) Wake() {`n" +
        "`tif b.pauseManager != nil {`n" +
        "`t`tb.pauseManager.DeviceWake()`n" +
        "`t}`n" +
        "}`n" +
        "`n" +
        "func (b *BoxInstance) ResetNetwork() {`n" +
        "`tb.access.Lock()`n" +
        "`tdefer b.access.Unlock()`n" +
        "`n" +
        "`tdefer device.DeferPanicToError(`"box.ResetNetwork`", func(err_ error) {`n" +
        "`t`tlog.Println(err_.Error())`n" +
        "`t})`n" +
        "`n" +
        "`tif b.state != 1 {`n" +
        "`t`treturn`n" +
        "`t}`n" +
        "`tb.Box.Router().ResetNetwork()`n" +
        "}`n" +
        "`n" +
        "func (b *BoxInstance) SetAsMain() {"
    )
    if ([regex]::Matches($sourceText, [regex]::Escape($oldBlock)).Count -ne 1) {
        throw "Locked Neko reset-network patch context must occur exactly once."
    }
    $patchedText = $sourceText.Replace($oldBlock, $newBlock)
    [IO.File]::WriteAllText(
        $targetPath,
        $patchedText,
        [Text.UTF8Encoding]::new($false)
    )
    Assert-FileHash `
        $targetPath `
        ([string]$Patch.targetAfterSha256) `
        "Neko reset-network target after patch"
    $afterHashes = Get-SourceTreeHashes $NekoRoot

    $changedFiles = [Collections.Generic.List[string]]::new()
    foreach ($path in $beforeHashes.Keys) {
        if (-not $afterHashes.ContainsKey($path) -or $afterHashes[$path] -cne $beforeHashes[$path]) {
            $changedFiles.Add($path)
        }
    }
    foreach ($path in $afterHashes.Keys) {
        if (-not $beforeHashes.ContainsKey($path)) {
            $changedFiles.Add($path)
        }
    }
    $changedFiles.Sort([StringComparer]::Ordinal)
    if (($changedFiles -join "`n") -cne ([string]$Patch.target)) {
        throw "Neko reset-network patch modified unexpected files: $($changedFiles -join ', ')"
    }

    Write-Host "Applied locked Neko reset-network patch."
}

function Get-VerifiedArchive {
    param(
        [string]$Name,
        [object]$Component,
        [string]$ArchiveRoot
    )

    $archivePath = Join-Path $ArchiveRoot "$Name-$($Component.revision).tar.gz"
    Assert-SafeFilePath $archivePath $ArchiveRoot
    if (-not (Test-Path -LiteralPath $archivePath -PathType Leaf)) {
        Write-Host "Downloading locked $Name archive..."
        $downloadPath = Join-Path $ArchiveRoot ".$Name-$PID.download"
        Assert-SafeFilePath $downloadPath $ArchiveRoot
        try {
            Invoke-WebRequest -Uri $Component.archive -OutFile $downloadPath -UseBasicParsing
            Assert-FileHash $downloadPath $Component.archiveSha256 "$Name downloaded archive"
            Move-Item -LiteralPath $downloadPath -Destination $archivePath
        } finally {
            if (Test-Path -LiteralPath $downloadPath) {
                Remove-Item -LiteralPath $downloadPath -Force
            }
        }
    }
    try {
        Assert-FileHash $archivePath $Component.archiveSha256 "$Name archive"
    } catch {
        if (Test-StrictDescendant $archivePath $ArchiveRoot) {
            Remove-Item -LiteralPath $archivePath -Force -ErrorAction SilentlyContinue
        }
        throw
    }
    return [IO.Path]::GetFullPath($archivePath)
}

function Get-LockedPlatform {
    if ([Runtime.InteropServices.RuntimeInformation]::ProcessArchitecture -ne [Runtime.InteropServices.Architecture]::X64) {
        throw "Locked native builds support only amd64 hosts."
    }
    if ($IsWindows) {
        return "windows-amd64"
    }
    if ($IsLinux) {
        return "linux-amd64"
    }
    throw "Locked native builds support only Windows and Linux hosts."
}

function Get-GoDistribution {
    param(
        [object]$Lock,
        [string]$Version,
        [string]$Platform
    )

    $versionProperty = $Lock.toolchain.goDistributions.PSObject.Properties[$Version]
    if ($null -eq $versionProperty) {
        throw "Go $Version has no locked distribution metadata."
    }
    $platformProperty = $versionProperty.Value.PSObject.Properties[$Platform]
    if ($null -eq $platformProperty) {
        throw "Go $Version has no locked $Platform distribution."
    }
    return $platformProperty.Value
}

function Get-VerifiedGoArchive {
    param(
        [object]$Lock,
        [string]$Version,
        [string]$Platform,
        [string]$ArchiveRoot
    )

    $distribution = Get-GoDistribution $Lock $Version $Platform
    $archivePath = Join-Path $ArchiveRoot ([string]$distribution.file)
    Assert-SafeFilePath $archivePath $ArchiveRoot
    if (-not (Test-Path -LiteralPath $archivePath -PathType Leaf)) {
        Write-Host "Downloading locked Go $Version for $Platform..."
        $downloadPath = Join-Path $ArchiveRoot ".$($distribution.file)-$PID.download"
        Assert-SafeFilePath $downloadPath $ArchiveRoot
        try {
            Invoke-WebRequest -Uri $distribution.url -OutFile $downloadPath -UseBasicParsing
            Assert-FileHash $downloadPath $distribution.sha256 "Go $Version downloaded archive"
            Move-Item -LiteralPath $downloadPath -Destination $archivePath
        } finally {
            if (Test-Path -LiteralPath $downloadPath) {
                Remove-Item -LiteralPath $downloadPath -Force
            }
        }
    }
    Assert-FileHash $archivePath $distribution.sha256 "Go $Version archive"
    return $archivePath
}

function Get-VerifiedGoToolchain {
    param(
        [object]$Lock,
        [string]$Version,
        [string]$Platform,
        [string]$ArchiveRoot,
        [string]$ToolchainRoot
    )

    $archivePath = Get-VerifiedGoArchive $Lock $Version $Platform $ArchiveRoot
    $destination = Join-Path $ToolchainRoot "go-$Version-$Platform"
    $staging = Join-Path $ToolchainRoot ".extract-$Version-$Platform-$PID"
    Assert-SafeDirectoryPath $destination $ToolchainRoot
    Assert-SafeDirectoryPath $staging $ToolchainRoot
    Remove-SafeTree $destination $ToolchainRoot
    Remove-SafeTree $staging $ToolchainRoot
    New-SafeDirectory $staging $ToolchainRoot

    try {
        if ($Platform -ceq "windows-amd64") {
            Add-Type -AssemblyName System.IO.Compression.FileSystem
            [IO.Compression.ZipFile]::ExtractToDirectory($archivePath, $staging)
        } else {
            $tar = Get-Command tar -ErrorAction SilentlyContinue
            if ($null -eq $tar) {
                throw "tar is required to extract the locked Linux Go toolchain."
            }
            Invoke-Checked $tar.Source -xzf $archivePath -C $staging
        }

        $extracted = Join-Path $staging "go"
        Assert-SafeDirectoryPath $extracted $staging
        if (-not (Test-Path -LiteralPath $extracted -PathType Container)) {
            throw "Locked Go archive does not contain the expected go/ root."
        }
        Move-Item -LiteralPath $extracted -Destination $destination
        Assert-SafeDirectoryPath $destination $ToolchainRoot
    } finally {
        Remove-SafeTree $staging $ToolchainRoot
    }

    $exeSuffix = if ($IsWindows) { ".exe" } else { "" }
    $goExecutable = Join-Path $destination "bin/go$exeSuffix"
    Assert-SafeFilePath $goExecutable $destination
    if (-not (Test-Path -LiteralPath $goExecutable -PathType Leaf)) {
        throw "Locked Go executable is missing after extraction: $goExecutable"
    }
    $previousGoRoot = $env:GOROOT
    $env:GOROOT = $destination
    try {
        $versionText = (& $goExecutable version 2>&1 | Out-String).Trim()
    } finally {
        $env:GOROOT = $previousGoRoot
    }
    if (
        $LASTEXITCODE -ne 0 -or
        $versionText -notmatch "\bgo$([regex]::Escape($Version))\b"
    ) {
        throw "Locked archive must provide Go $Version, found: $versionText"
    }
    Write-Host "Verified archive toolchain: $versionText"
    return [IO.Path]::GetFullPath($goExecutable)
}

function Get-ArchivePathParts {
    param(
        [string]$EntryName,
        [switch]$AllowParentSegments
    )

    if (
        [string]::IsNullOrWhiteSpace($EntryName) -or
        $EntryName.Contains("\") -or
        $EntryName.StartsWith("/", [StringComparison]::Ordinal) -or
        $EntryName -match '^[A-Za-z]:'
    ) {
        throw "Archive entry has an unsafe path: $EntryName"
    }
    $trimmed = $EntryName.TrimEnd("/")
    if ([string]::IsNullOrWhiteSpace($trimmed)) {
        throw "Archive entry path is empty."
    }
    $parts = @($trimmed.Split("/"))
    foreach ($part in $parts) {
        if (
            [string]::IsNullOrWhiteSpace($part) -or
            $part -ceq "." -or
            (-not $AllowParentSegments -and $part -ceq "..") -or
            $part.Contains(":") -or
            $part.IndexOf([char]0) -ge 0
        ) {
            throw "Archive entry has an unsafe path segment: $EntryName"
        }
    }
    return $parts
}

function Assert-SafeArchiveLink {
    param(
        [string[]]$EntryParts,
        [string]$LinkName,
        [string]$ArchiveRootName
    )

    $linkParts = Get-ArchivePathParts $LinkName -AllowParentSegments
    $resolved = [Collections.Generic.List[string]]::new()
    foreach ($part in $EntryParts[0..($EntryParts.Count - 2)]) {
        $resolved.Add($part)
    }
    foreach ($part in $linkParts) {
        if ($part -ceq "..") {
            if ($resolved.Count -le 1) {
                throw "Archive link escapes its locked root: $LinkName"
            }
            $resolved.RemoveAt($resolved.Count - 1)
        } else {
            $resolved.Add($part)
        }
    }
    if ($resolved.Count -eq 0 -or $resolved[0] -cne $ArchiveRootName) {
        throw "Archive link escapes its locked root: $LinkName"
    }
}

function Expand-VerifiedComponentArchive {
    param(
        [string]$Name,
        [string]$ArchivePath,
        [string]$Destination,
        [string]$DesignatedRoot
    )

    Add-Type -AssemblyName System.Formats.Tar
    $staging = "$Destination.extract-$PID-$([Guid]::NewGuid().ToString("N"))"
    Assert-SafeDirectoryPath $Destination $DesignatedRoot
    Assert-SafeDirectoryPath $staging $DesignatedRoot
    Remove-SafeTree $staging $DesignatedRoot
    New-SafeDirectory $staging $DesignatedRoot

    $archiveRootName = $null
    $fileCount = 0
    $pathComparer = if ($IsWindows) {
        [StringComparer]::OrdinalIgnoreCase
    } else {
        [StringComparer]::Ordinal
    }
    $seenPaths = [Collections.Generic.HashSet[string]]::new($pathComparer)
    $skippedLinks = [Collections.Generic.List[string]]::new()
    $fileStream = $null
    $gzipStream = $null
    $tarReader = $null
    try {
        $fileStream = [IO.File]::OpenRead($ArchivePath)
        $gzipStream = [IO.Compression.GZipStream]::new(
            $fileStream,
            [IO.Compression.CompressionMode]::Decompress,
            $false
        )
        $tarReader = [System.Formats.Tar.TarReader]::new($gzipStream, $false)
        while ($null -ne ($entry = $tarReader.GetNextEntry())) {
            if (
                $entry.EntryType -eq [System.Formats.Tar.TarEntryType]::GlobalExtendedAttributes
            ) {
                continue
            }
            if (
                $entry.EntryType -ne [System.Formats.Tar.TarEntryType]::Directory -and
                $entry.EntryType -ne [System.Formats.Tar.TarEntryType]::RegularFile -and
                $entry.EntryType -ne [System.Formats.Tar.TarEntryType]::SymbolicLink
            ) {
                throw "$Name archive contains unsupported entry type $($entry.EntryType): $($entry.Name)"
            }

            $parts = @(Get-ArchivePathParts ([string]$entry.Name))
            if ($null -eq $archiveRootName) {
                $archiveRootName = $parts[0]
            } elseif ($parts[0] -cne $archiveRootName) {
                throw "$Name archive contains more than one root directory."
            }
            if ($parts.Count -eq 1) {
                if ($entry.EntryType -ne [System.Formats.Tar.TarEntryType]::Directory) {
                    throw "$Name archive root must be a directory."
                }
                continue
            }

            $relativeName = $parts[1..($parts.Count - 1)] -join "/"
            if (-not $seenPaths.Add($relativeName)) {
                throw "$Name archive contains a duplicate or case-colliding entry: $relativeName"
            }
            foreach ($linkPath in $skippedLinks) {
                if ($relativeName.StartsWith("$linkPath/", [StringComparison]::Ordinal)) {
                    throw "$Name archive contains entries below a symbolic link: $linkPath"
                }
            }

            if ($entry.EntryType -eq [System.Formats.Tar.TarEntryType]::SymbolicLink) {
                Assert-SafeArchiveLink $parts ([string]$entry.LinkName) $archiveRootName
                foreach ($seenPath in $seenPaths) {
                    if (
                        $seenPath -cne $relativeName -and
                        $seenPath.StartsWith("$relativeName/", [StringComparison]::Ordinal)
                    ) {
                        throw "$Name archive declares a symbolic link above existing entries: $relativeName"
                    }
                }
                $skippedLinks.Add($relativeName)
                continue
            }

            $relativeSystemPath = $relativeName.Replace(
                "/",
                [IO.Path]::DirectorySeparatorChar
            )
            $target = [IO.Path]::GetFullPath((Join-Path $staging $relativeSystemPath))
            if (-not (Test-StrictDescendant $target $staging)) {
                throw "$Name archive entry escaped the extraction root: $relativeName"
            }
            if ($entry.EntryType -eq [System.Formats.Tar.TarEntryType]::Directory) {
                [IO.Directory]::CreateDirectory($target) | Out-Null
                continue
            }

            $parent = [IO.Path]::GetDirectoryName($target)
            [IO.Directory]::CreateDirectory($parent) | Out-Null
            if ($null -eq $entry.DataStream -and $entry.Length -ne 0) {
                throw "$Name archive file has no data stream: $relativeName"
            }
            $output = [IO.File]::Open(
                $target,
                [IO.FileMode]::CreateNew,
                [IO.FileAccess]::Write,
                [IO.FileShare]::None
            )
            try {
                if ($null -ne $entry.DataStream) {
                    $entry.DataStream.CopyTo($output)
                }
            } finally {
                $output.Dispose()
            }
            $fileCount++
        }
    } finally {
        if ($null -ne $tarReader) {
            $tarReader.Dispose()
        }
        if ($null -ne $gzipStream) {
            $gzipStream.Dispose()
        }
        if ($null -ne $fileStream) {
            $fileStream.Dispose()
        }
    }

    try {
        if ($fileCount -eq 0 -or [string]::IsNullOrWhiteSpace($archiveRootName)) {
            throw "$Name archive did not contain a source tree."
        }
        $reparsePoint = Get-ChildItem -LiteralPath $staging -Recurse -Force |
            Where-Object {
                ($_.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0
            } |
            Select-Object -First 1
        if ($null -ne $reparsePoint) {
            throw "$Name archive extraction produced a reparse point: $($reparsePoint.FullName)"
        }
        if ($skippedLinks.Count -gt 0) {
            Write-Warning (
                "$Name archive contains safe symbolic links that are not materialized: " +
                ($skippedLinks -join ", ")
            )
        }
        Remove-SafeTree $Destination $DesignatedRoot
        Move-Item -LiteralPath $staging -Destination $Destination
        Assert-SafeDirectoryPath $Destination $DesignatedRoot
        Write-Host "Extracted SHA-verified $Name codeload source archive."
    } finally {
        Remove-SafeTree $staging $DesignatedRoot
    }
}

function Set-ActiveGo {
    param(
        [string]$Executable,
        [string]$ExpectedVersion,
        [string]$OriginalPath,
        [string]$Purpose
    )

    $env:PATH = [IO.Path]::GetDirectoryName($Executable) +
        [IO.Path]::PathSeparator +
        $OriginalPath
    $env:GOROOT = [IO.Directory]::GetParent(
        [IO.Path]::GetDirectoryName($Executable)
    ).FullName
    $active = Get-Command go -ErrorAction Stop
    $activePath = [IO.Path]::GetFullPath($active.Source)
    if (-not (Test-PathEqual $activePath $Executable)) {
        throw "$Purpose activated unexpected Go executable: $activePath"
    }
    $versionText = (& $activePath version 2>&1 | Out-String).Trim()
    if (
        $LASTEXITCODE -ne 0 -or
        $versionText -notmatch "\bgo$([regex]::Escape($ExpectedVersion))\b"
    ) {
        throw "$Purpose requires active Go $ExpectedVersion, found: $versionText"
    }
    Write-Host "${Purpose}: $versionText"
}

function Set-IsolatedGoEnvironment {
    param(
        [string]$GoPath,
        [string]$GoModCache,
        [string]$GoBuildCache,
        [string]$GoBin
    )

    foreach ($hostVariable in Get-ChildItem Env:) {
        if (
            $hostVariable.Name.StartsWith("CGO_", [StringComparison]::Ordinal) -or
            $hostVariable.Name.StartsWith("CC_FOR_", [StringComparison]::Ordinal) -or
            $hostVariable.Name.StartsWith("CXX_FOR_", [StringComparison]::Ordinal)
        ) {
            Remove-Item -LiteralPath "Env:$($hostVariable.Name)"
        }
    }

    $env:GOENV = "off"
    $env:GOWORK = "off"
    $env:GOBIN = $GoBin
    $env:GOPATH = $GoPath
    $env:GOMODCACHE = $GoModCache
    $env:GOCACHE = $GoBuildCache
    $env:GOTOOLCHAIN = "local"
    $env:GOFLAGS = "-mod=readonly"
    $env:CGO_ENABLED = "1"
    $env:GO386 = "softfloat"
    $env:GOAMD64 = "v1"

    $env:GOOS = ""
    $env:GOARCH = ""
    $env:GOARM = ""
    $env:GOARM64 = ""
    $env:GOMIPS = ""
    $env:GOMIPS64 = ""
    $env:GOPPC64 = ""
    $env:GORISCV64 = ""
    $env:GOEXPERIMENT = ""
    $env:CC = ""
    $env:CXX = ""
    $env:GCCGO = ""
    $env:AR = ""
    $env:PKG_CONFIG = ""
    $env:CGO_CFLAGS = ""
    $env:CGO_CPPFLAGS = ""
    $env:CGO_CXXFLAGS = ""
    $env:CGO_FFLAGS = ""
    $env:CGO_LDFLAGS = ""
    $env:GOBIND = ""
}

function Assert-Toolchain {
    param(
        [object]$Lock,
        [string]$Platform,
        [string]$GoArchiveRoot,
        [string]$GoToolchainRoot
    )

    $java = Get-Command java -ErrorAction SilentlyContinue
    if ($null -eq $java) {
        throw "JDK 17 is required, but 'java' is not on PATH."
    }
    $javaVersion = (& $java.Source -version 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or $javaVersion -notmatch 'version "17(?:\.|")') {
        throw "JDK 17 is required, found: $javaVersion"
    }
    if ($null -eq (Get-Command javap -ErrorAction SilentlyContinue)) {
        throw "JDK 17 is required, but 'javap' is not on PATH."
    }
    if ($null -eq (Get-Command git -ErrorAction SilentlyContinue)) {
        throw "Git is required, but 'git' is not on PATH."
    }

    $sdkCandidate = if (-not [string]::IsNullOrWhiteSpace($env:ANDROID_HOME)) {
        $env:ANDROID_HOME
    } elseif (-not [string]::IsNullOrWhiteSpace($env:ANDROID_SDK_ROOT)) {
        $env:ANDROID_SDK_ROOT
    } else {
        throw "ANDROID_HOME or ANDROID_SDK_ROOT must point to the Android SDK."
    }
    $sdkRoot = Get-AbsolutePath $sdkCandidate (Get-Location).Path
    $CompileSdk = [string]$Lock.toolchain.androidCompileSdk
    $androidJar = Join-Path $sdkRoot "platforms/android-$CompileSdk/android.jar"
    if (-not (Test-Path -LiteralPath $androidJar -PathType Leaf)) {
        throw "Locked Android compile SDK $CompileSdk is missing: $androidJar"
    }

    $ndkCandidate = if (-not [string]::IsNullOrWhiteSpace($env:ANDROID_NDK_HOME)) {
        $env:ANDROID_NDK_HOME
    } elseif (-not [string]::IsNullOrWhiteSpace($env:ANDROID_NDK_ROOT)) {
        $env:ANDROID_NDK_ROOT
    } else {
        Join-Path $sdkRoot "ndk/$($Lock.toolchain.ndk)"
    }
    $ndkRoot = Get-AbsolutePath $ndkCandidate (Get-Location).Path
    $sourceProperties = Join-Path $ndkRoot "source.properties"
    if (-not (Test-Path -LiteralPath $sourceProperties -PathType Leaf)) {
        throw "Android NDK $($Lock.toolchain.ndk) is missing: $ndkRoot"
    }
    $ndkMetadata = Get-Content -LiteralPath $sourceProperties -Raw
    if ($ndkMetadata -notmatch "Pkg\.Revision\s*=\s*$([regex]::Escape([string]$Lock.toolchain.ndk))(\s|$)") {
        throw "ANDROID_NDK_HOME does not contain locked NDK $($Lock.toolchain.ndk)."
    }

    $env:ANDROID_HOME = $sdkRoot
    $env:ANDROID_SDK_ROOT = $sdkRoot
    $env:ANDROID_NDK_HOME = $ndkRoot
    $env:ANDROID_NDK_ROOT = $ndkRoot
    $env:GOTOOLCHAIN = "local"

    $coreGo = Get-VerifiedGoToolchain `
        $Lock `
        ([string]$Lock.toolchain.coreGo) `
        $Platform `
        $GoArchiveRoot `
        $GoToolchainRoot
    $gomobileGo = Get-VerifiedGoToolchain `
        $Lock `
        ([string]$Lock.toolchain.gomobileGo) `
        $Platform `
        $GoArchiveRoot `
        $GoToolchainRoot

    return [PSCustomObject]@{
        CoreGo = $coreGo
        GomobileGo = $gomobileGo
        AndroidCompileJar = [IO.Path]::GetFullPath($androidJar)
    }
}

function Assert-Aar {
    param(
        [string]$AarPath,
        [object]$Lock,
        [string]$InspectionRoot,
        [string]$ContractPath
    )

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($AarPath)
    try {
        $names = @($archive.Entries | ForEach-Object { $_.FullName })
        $expectedJniEntries = @(
            $Lock.build.abis |
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
            throw "AAR JNI entry set differs from the four locked ABI libraries: $($actualJniEntries -join ', ')"
        }
        foreach ($abi in $Lock.build.abis) {
            $jniEntry = "jni/$abi/libgojni.so"
            $entry = $archive.GetEntry($jniEntry)
            if ($null -eq $entry -or $entry.Length -eq 0) {
                throw "AAR has an empty or missing $jniEntry."
            }
            $memory = [IO.MemoryStream]::new()
            $input = $entry.Open()
            try {
                $input.CopyTo($memory)
            } finally {
                $input.Dispose()
            }
            try {
                Assert-JniElfContract `
                    -Bytes $memory.ToArray() `
                    -Abi ([string]$abi) `
                    -EntryName $jniEntry
            } finally {
                $memory.Dispose()
            }
        }
        if ("classes.jar" -cnotin $names) {
            throw "AAR is missing classes.jar."
        }
        if ("AndroidManifest.xml" -cnotin $names) {
            throw "AAR is missing AndroidManifest.xml."
        }

        $inspectionParent = [IO.Directory]::GetParent($InspectionRoot).FullName
        Remove-SafeTree $InspectionRoot $inspectionParent
        New-SafeDirectory $InspectionRoot $inspectionParent
        $classesPath = Join-Path $InspectionRoot "classes.jar"
        $manifestPath = Join-Path $InspectionRoot "AndroidManifest.xml"
        [IO.Compression.ZipFileExtensions]::ExtractToFile(
            $archive.GetEntry("classes.jar"),
            $classesPath
        )
        [IO.Compression.ZipFileExtensions]::ExtractToFile(
            $archive.GetEntry("AndroidManifest.xml"),
            $manifestPath
        )
    } finally {
        $archive.Dispose()
    }

    $manifest = [Text.Encoding]::UTF8.GetString([IO.File]::ReadAllBytes($manifestPath))
    if ($manifest -notmatch 'minSdkVersion\s*=\s*["'']21["'']') {
        throw "AAR manifest does not declare locked minSdkVersion 21."
    }

    if (-not (Test-Path -LiteralPath $ContractPath -PathType Leaf)) {
        throw "AAR API contract is missing: $ContractPath"
    }
    $contract = [ordered]@{}
    foreach ($line in Get-Content -LiteralPath $ContractPath) {
        if ([string]::IsNullOrWhiteSpace($line)) {
            continue
        }
        $parts = $line.Split("|", 2)
        if ($parts.Count -ne 2 -or [string]::IsNullOrWhiteSpace($parts[1])) {
            throw "Invalid AAR contract line: $line"
        }
        if (-not $contract.Contains($parts[0])) {
            $contract[$parts[0]] = [Collections.Generic.List[string]]::new()
        }
        $contract[$parts[0]].Add($parts[1])
    }

    $javap = (Get-Command javap -ErrorAction Stop).Source
    foreach ($className in $contract.Keys) {
        $api = @(& $javap -public -classpath $classesPath $className 2>&1)
        if ($LASTEXITCODE -ne 0) {
            throw "AAR is missing required Java API $className.`n$($api -join "`n")"
        }
        $publicLines = @(
            $api |
                ForEach-Object { ([string]$_).Trim() } |
                Where-Object { $_.StartsWith("public ", [StringComparison]::Ordinal) }
        )
        foreach ($signature in $contract[$className]) {
            if ([string]$signature -cnotin $publicLines) {
                throw "$className is missing exact signature: $signature"
            }
        }
    }
}

$scriptRoot = [IO.Path]::GetFullPath($PSScriptRoot)
$repoRoot = [IO.Path]::GetFullPath((Join-Path $scriptRoot "../..")).TrimEnd(
    [IO.Path]::DirectorySeparatorChar,
    [IO.Path]::AltDirectorySeparatorChar
)
$lockPath = Join-Path $repoRoot "native/versions.lock"
if (-not (Test-Path -LiteralPath $lockPath -PathType Leaf)) {
    throw "Native lock is missing: $lockPath"
}
$lock = Get-Content -LiteralPath $lockPath -Raw | ConvertFrom-Json -Depth 16
Assert-Lock $lock

if ([string]::IsNullOrWhiteSpace($NativeWorkRoot)) {
    $NativeWorkRoot = Join-Path $repoRoot ".native-work"
}
$nativeRoot = Get-AbsolutePath $NativeWorkRoot $repoRoot
Assert-SafeRoot $nativeRoot $repoRoot
Assert-SafeDirectoryPath $nativeRoot $nativeRoot -AllowRoot

if ([string]::IsNullOrWhiteSpace($WorkDirectory)) {
    $WorkDirectory = Join-Path $nativeRoot "work"
}
$workRoot = Get-AbsolutePath $WorkDirectory $repoRoot
if (-not (Test-StrictDescendant $workRoot $nativeRoot)) {
    throw "WorkDirectory must be a strict descendant of NativeWorkRoot."
}
Assert-SafeDirectoryPath $workRoot $nativeRoot

if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $OutputPath = Join-Path $repoRoot "app/libs/libcore.aar"
}
$aarOutput = Get-AbsolutePath $OutputPath $repoRoot
$expectedAarOutput = Get-AbsolutePath "app/libs/libcore.aar" $repoRoot
if (-not (Test-PathEqual $aarOutput $expectedAarOutput)) {
    throw "OutputPath must be the staged Lean AAR path: $expectedAarOutput"
}
Assert-SafeFilePath $aarOutput $repoRoot

$validatorOutput = $null
if (-not [string]::IsNullOrWhiteSpace($ValidatorOutputPath)) {
    $validatorOutput = Get-AbsolutePath $ValidatorOutputPath $repoRoot
    $expectedValidatorOutput = Get-AbsolutePath "artifacts/sing-box" $repoRoot
    if (-not (Test-PathEqual $validatorOutput $expectedValidatorOutput)) {
        throw "ValidatorOutputPath must be the exact ignored artifact path: $expectedValidatorOutput"
    }
    Assert-SafeFilePath $validatorOutput $repoRoot

    if ($null -eq (Get-Command git -ErrorAction SilentlyContinue)) {
        throw "Git is required to validate the validator artifact policy."
    }
    & git -C $repoRoot check-ignore --quiet -- "artifacts/sing-box"
    if ($LASTEXITCODE -ne 0) {
        throw "ValidatorOutputPath must remain covered by .gitignore."
    }
    & git -C $repoRoot ls-files --error-unmatch -- "artifacts/sing-box" *> $null
    if ($LASTEXITCODE -eq 0) {
        throw "ValidatorOutputPath must not be tracked by Git."
    }
}

$archiveRoot = Join-Path $nativeRoot "archives"
$goArchiveRoot = Join-Path $nativeRoot "go-archives"
$goToolchainRoot = Join-Path $nativeRoot "go-toolchains"
$sourceRoot = Join-Path $workRoot "sources"
$goPath = Join-Path $nativeRoot "gopath"
$goModCache = Join-Path $nativeRoot "gomodcache"
$goBuildCache = Join-Path $nativeRoot "gocache"
$goBin = Join-Path $goPath "bin"
$derivedDirectories = @(
    $archiveRoot,
    $goArchiveRoot,
    $goToolchainRoot,
    $workRoot,
    $sourceRoot,
    $goPath,
    $goModCache,
    $goBuildCache
)
foreach ($directory in $derivedDirectories) {
    Assert-SafeDirectoryPath $directory $nativeRoot
}

if ($ValidateOnly) {
    Write-Host "Lock, artifact, and native write-path policy validated."
    exit 0
}

New-SafeDirectory $nativeRoot $nativeRoot -AllowRoot
foreach ($directory in $derivedDirectories) {
    New-SafeDirectory $directory $nativeRoot
}

if (-not $VerifySourcesOnly) {
    New-SafeDirectory $goBin $nativeRoot
    Set-IsolatedGoEnvironment `
        $goPath `
        $goModCache `
        $goBuildCache `
        $goBin
    $originalPath = $env:PATH
    $toolchain = Assert-Toolchain `
        $lock `
        (Get-LockedPlatform) `
        $goArchiveRoot `
        $goToolchainRoot
}

$componentLayout = [ordered]@{
    neko = "nekobox-1.4.2"
    singBox = "sing-box"
    libneko = "libneko"
    gomobile = "gomobile"
}
foreach ($name in $componentLayout.Keys) {
    $component = $lock.components.$name
    $componentArchive = Get-VerifiedArchive $name $component $archiveRoot
    $destination = Join-Path $sourceRoot $componentLayout[$name]
    Expand-VerifiedComponentArchive `
        $name `
        $componentArchive `
        $destination `
        $nativeRoot
}

$modulePaths = @{
    "neko/libcore/go.mod" = Join-Path $sourceRoot "nekobox-1.4.2/libcore/go.mod"
    "neko/libcore/go.sum" = Join-Path $sourceRoot "nekobox-1.4.2/libcore/go.sum"
    "libneko/go.mod" = Join-Path $sourceRoot "libneko/go.mod"
    "gomobile/go.mod" = Join-Path $sourceRoot "gomobile/go.mod"
    "gomobile/go.sum" = Join-Path $sourceRoot "gomobile/go.sum"
}
foreach ($entry in $modulePaths.GetEnumerator()) {
    Assert-FileHash $entry.Value $lock.moduleFiles.($entry.Key) $entry.Key
}

$nekoRoot = Join-Path $sourceRoot "nekobox-1.4.2"
Assert-SafeDirectoryPath $nekoRoot $nativeRoot
Apply-NekoDnsCompletionPatch `
    $lock.patches.nekoDnsSuccessCompletion `
    $repoRoot `
    $nekoRoot
Apply-NekoResetNetworkPatch `
    $lock.patches.nekoResetNetwork `
    $repoRoot `
    $nekoRoot

if ($VerifySourcesOnly) {
    Write-Host "All locked sources and the Neko patches verified."
    exit 0
}

$gomobileRoot = Join-Path $sourceRoot "gomobile"
Set-ActiveGo `
    $toolchain.GomobileGo `
    ([string]$lock.toolchain.gomobileGo) `
    $originalPath `
    "gomobile bootstrap"
Push-Location $gomobileRoot
try {
    Invoke-Checked $toolchain.GomobileGo mod download
    Invoke-Checked $toolchain.GomobileGo install ./cmd/gomobile ./cmd/gobind
} finally {
    Pop-Location
}

$exeSuffix = if ($IsWindows) { ".exe" } else { "" }
$gomobile = Join-Path $goBin "gomobile$exeSuffix"
$gobind = Join-Path $goBin "gobind$exeSuffix"
$gomobileMatsuri = Join-Path $goBin "gomobile-matsuri$exeSuffix"
$gobindMatsuri = Join-Path $goBin "gobind-matsuri$exeSuffix"
foreach ($binaryPath in @($gomobile, $gobind, $gomobileMatsuri, $gobindMatsuri)) {
    Assert-SafeFilePath $binaryPath $nativeRoot
}
foreach ($sourceBinary in @($gomobile, $gobind)) {
    if (-not (Test-Path -LiteralPath $sourceBinary -PathType Leaf)) {
        throw "Pinned gomobile build did not produce $sourceBinary."
    }
}
Copy-Item -LiteralPath $gomobile -Destination $gomobileMatsuri -Force
Copy-Item -LiteralPath $gobind -Destination $gobindMatsuri -Force
$env:GOBIND = $gobindMatsuri
$goPkg = Join-Path $goPath "pkg"
New-SafeDirectory $goPkg $nativeRoot
$gomobileState = Join-Path $goPath "pkg/gomobile"
Assert-SafeDirectoryPath $gomobileState $nativeRoot
Remove-SafeTree $gomobileState $nativeRoot
New-SafeDirectory $gomobileState $nativeRoot
Set-ActiveGo `
    $toolchain.CoreGo `
    ([string]$lock.toolchain.coreGo) `
    $originalPath `
    "Neko/libcore bind"

$libcoreRoot = Join-Path $sourceRoot "nekobox-1.4.2/libcore"
$builtAar = Join-Path $libcoreRoot "libcore.aar"
$libcoreBuildCache = Join-Path $libcoreRoot ".build"
Assert-NoReparsePoint $libcoreRoot
Assert-SafeFilePath $builtAar $libcoreRoot
New-SafeDirectory $libcoreBuildCache $libcoreRoot
Push-Location $libcoreRoot
try {
    $regressionArguments = @(
        "test",
        "-run",
        "^TestExchangeContextSuccessCompletesLookupWait$",
        "-count=1",
        "dns_box.go",
        "dns_box_success_test.go"
    )
    Invoke-Checked -Executable $toolchain.CoreGo -Arguments $regressionArguments

    $tags = @($lock.build.tags) -join ","
    $bindArguments = @(
        "bind",
        "-v",
        "-androidapi",
        [string]$lock.toolchain.androidApi,
        "-bootclasspath",
        [string]$toolchain.AndroidCompileJar,
        "-cache",
        $libcoreBuildCache,
        "-trimpath",
        "-ldflags=-s -w",
        "-tags=$tags",
        "."
    )
    Invoke-Checked -Executable $gomobileMatsuri -Arguments $bindArguments
} finally {
    Pop-Location
}
if (-not (Test-Path -LiteralPath $builtAar -PathType Leaf)) {
    throw "Pinned gomobile build did not produce $builtAar."
}
$inspectionRoot = Join-Path $workRoot "aar-inspection"
Assert-SafeDirectoryPath $inspectionRoot $nativeRoot
Assert-Aar `
    $builtAar `
    $lock `
    $inspectionRoot `
    (Join-Path $scriptRoot "aar-contract.txt")

Assert-SafeFilePath $aarOutput $repoRoot
New-SafeDirectory ([IO.Path]::GetDirectoryName($aarOutput)) $repoRoot
Copy-Item -LiteralPath $builtAar -Destination $aarOutput -Force

if ($null -ne $validatorOutput) {
    $validatorRoot = Join-Path $sourceRoot "sing-box"
    Assert-SafeFilePath $validatorOutput $repoRoot
    New-SafeDirectory ([IO.Path]::GetDirectoryName($validatorOutput)) $repoRoot
    Push-Location $validatorRoot
    try {
        $tags = @($lock.build.tags) -join ","
        $validatorArguments = @(
            "build",
            "-trimpath",
            "-ldflags=-s -w",
            "-tags=$tags",
            "-o",
            $validatorOutput,
            "./cmd/sing-box"
        )
        Invoke-Checked -Executable $toolchain.CoreGo -Arguments $validatorArguments
    } finally {
        Pop-Location
    }
}

Write-Host "Verified Neko core written to $aarOutput"
