[CmdletBinding()]
param(
    [string]$DownloadRoot,
    [string]$OutputRoot
)

# Lays the external protocol helpers (naive, mieru, xray) into jniLibs so they ride
# along inside the APK.
#
# None of these are in the core. sing-box has no mieru at all and only a
# naive INBOUND (a server), while what a client needs is the outbound side — and naive's
# is Chromium's network stack in C++, which cannot be linked into a Go core at all. The
# reference client solves it the same way: run the upstream binary as its own process,
# have it listen on a local SOCKS port, and point a plain `socks` outbound at that port.
# So these are executables that happen to be named lib*.so, not libraries anything links.
#
# The .so naming is load-bearing, not cosmetic: an APK's `lib/<abi>/` entries are the only
# files the installer will extract to a real path with the execute bit, and only names
# matching lib*.so are extracted at all. Anything shipped under assets/ lands inside the
# APK with no executable path to exec. This is also why the app sets
# jniLibs.useLegacyPackaging = true — from targetSdk 30 the default is to store native
# libs UNCOMPRESSED in the APK and map them straight from it, extracting nothing, which is
# fine for a library the loader maps but leaves no file on disk to exec. Legacy packaging
# puts them back to compressed-and-extracted-at-install, which is what gives these four a
# real path. It also means the APK grows by each binary's COMPRESSED size while the
# installed footprint grows by the full one.
#
# Everything is pinned by sha256 in native/versions.lock and verified after download,
# same contract the core build already follows: a release asset can be re-uploaded, and a
# proxy binary is the last thing that should change silently under us.

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

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

function Get-Asset {
    param([string]$Url, [string]$Destination, [string]$Sha256, [string]$Label)

    if (Test-Path -LiteralPath $Destination -PathType Leaf) {
        # A cached copy is only usable if it still matches the lock; a stale one from an
        # earlier pin must be re-fetched rather than silently reused.
        $cached = (Get-FileHash -LiteralPath $Destination -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($cached -ceq $Sha256.ToLowerInvariant()) {
            Write-Host "cached   $Label"
            return
        }
        Remove-Item -LiteralPath $Destination -Force
    }
    Write-Host "download $Label"
    Invoke-WebRequest -Uri $Url -OutFile $Destination -UseBasicParsing
    Assert-FileHash $Destination $Sha256 $Label
}

$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot "../.."))
$lock = Get-Content -LiteralPath (Join-Path $repoRoot "native/versions.lock") -Raw |
    ConvertFrom-Json -Depth 16

if (-not $DownloadRoot) { $DownloadRoot = Join-Path $repoRoot ".native-work/plugins" }
if (-not $OutputRoot) { $OutputRoot = Join-Path $repoRoot "app/src/main/jniLibs" }
# NaiveProxy goes to the `full` flavour's own source set rather than to main: it is a
# Chromium fork and cannot be built from source on a build server, so the `foss` flavour
# ships without it. Everything else is buildable and stays in main, where both flavours
# package it.
$naiveOutputRoot = Join-Path $repoRoot "app/src/full/jniLibs"

New-Item -ItemType Directory -Force -Path $DownloadRoot | Out-Null

# --- naive: a plugin APK per ABI; the binary is the lib/<abi>/libnaive.so inside it ---
$naive = $lock.plugins.naive
foreach ($abi in $naive.abis.PSObject.Properties) {
    $entry = $abi.Value
    $apk = Join-Path $DownloadRoot "naive-$($naive.version)-$($abi.Name).apk"
    Get-Asset $entry.url $apk $entry.sha256 "naive $($naive.version) $($abi.Name)"

    $targetDir = Join-Path $naiveOutputRoot $abi.Name
    New-Item -ItemType Directory -Force -Path $targetDir | Out-Null
    $target = Join-Path $targetDir $naive.soName

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($apk)
    try {
        $wanted = $naive.entryInApk.Replace('%(abi)s', $abi.Name)
        $item = $zip.Entries | Where-Object { $_.FullName -ceq $wanted }
        if (-not $item) { throw "naive plugin APK has no $wanted" }
        if (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target -Force }
        [IO.Compression.ZipFileExtensions]::ExtractToFile($item, $target, $true)
    } finally {
        $zip.Dispose()
    }
    Write-Host "  -> $target"
}

# --- mieru: a plain tarball, arm64 only ---
#
# Upstream publishes no other Android ABI, so mieru is genuinely arm64-only here. The app
# must therefore treat "this build has no mieru binary" as a real, user-visible state
# rather than assume the file is present — see NativePlugin.
$mieru = $lock.plugins.mieru
foreach ($abi in $mieru.abis.PSObject.Properties) {
    $entry = $abi.Value
    $tar = Join-Path $DownloadRoot "mieru-$($mieru.version)-$($abi.Name).tar.gz"
    Get-Asset $entry.url $tar $entry.sha256 "mieru $($mieru.version) $($abi.Name)"

    $extractDir = Join-Path $DownloadRoot "mieru-$($mieru.version)-$($abi.Name)"
    if (Test-Path -LiteralPath $extractDir) {
        Remove-Item -LiteralPath $extractDir -Recurse -Force
    }
    New-Item -ItemType Directory -Force -Path $extractDir | Out-Null
    tar -xzf $tar -C $extractDir
    if ($LASTEXITCODE -ne 0) { throw "failed to extract $tar" }

    $source = Join-Path $extractDir $mieru.entryInArchive
    if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
        throw "mieru archive has no $($mieru.entryInArchive)"
    }

    $targetDir = Join-Path $OutputRoot $abi.Name
    New-Item -ItemType Directory -Force -Path $targetDir | Out-Null
    $target = Join-Path $targetDir $mieru.soName
    Copy-Item -LiteralPath $source -Destination $target -Force
    Write-Host "  -> $target"
}

# --- xray: upstream's own Android build, taken whole ---
#
# XHTTP is Xray's transport and exists nowhere else: the pinned sing-box carries
# v2ray{grpc,http,httpupgrade,quic,websocket} and nothing named xhttp or splithttp, so a
# VLESS node behind a CDN cannot be spoken to by the core at any config. Xray therefore
# runs as a helper like naive and mieru do.
#
# The upstream RELEASE binary is used rather than a trimmed build of our own. A cut-down
# distro would save perhaps ten megabytes, and cost the one guarantee that matters here:
# every registration Xray's own config parser expects is present. A missing one does not
# fail at build time — it fails on a user's phone as "unknown transport", which is exactly
# the class of bug a locked, hash-pinned dependency is supposed to prevent.
#
# The archive's geoip.dat / geosite.dat are deliberately NOT shipped: routing stays with
# the core, and the helper's config never names a geo rule. That is 29 MB not carried.
$xray = $lock.plugins.xray
foreach ($abi in $xray.abis.PSObject.Properties) {
    $entry = $abi.Value
    $zipPath = Join-Path $DownloadRoot "xray-$($xray.version)-$($abi.Name).zip"
    Get-Asset $entry.url $zipPath $entry.sha256 "xray $($xray.version) $($abi.Name)"

    $targetDir = Join-Path $OutputRoot $abi.Name
    New-Item -ItemType Directory -Force -Path $targetDir | Out-Null
    $target = Join-Path $targetDir $xray.soName

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($zipPath)
    try {
        $item = $zip.Entries | Where-Object { $_.FullName -ceq $xray.entryInArchive }
        if (-not $item) { throw "xray archive has no $($xray.entryInArchive)" }
        if (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target -Force }
        [IO.Compression.ZipFileExtensions]::ExtractToFile($item, $target, $true)
    } finally {
        $zip.Dispose()
    }
    Write-Host "  -> $target"
}

Write-Host "Plugin binaries vendored into $OutputRoot"
