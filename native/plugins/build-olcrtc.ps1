[CmdletBinding()]
param(
    [string]$WorkRoot,
    [string]$RepositoryRoot
)

# Builds the olcRTC helper from pinned source and lays it into jniLibs.
#
# Unlike naive and mieru this one cannot be downloaded: upstream publishes no releases at
# all, so there is no asset to pin — only a commit. The source tarball is fetched by
# commit SHA and verified against native/versions.lock, and the Go toolchain that compiles
# it is the same pinned-and-hashed kind the core build already uses. Nothing here is
# resolved "latest".
#
# ARM64 ONLY, and that is a property of the platform rather than a shortcut: Go can link
# android/arm, android/386 and android/amd64 only through an external (cgo) linker, and
# with CGO disabled it refuses outright —
#   "android/arm requires external (cgo) linking, but cgo is not enabled"
# arm64 is the one Android target that links internally. mieru ships arm64-only for its
# own reasons and the app already handles a helper that has no build for the running ABI
# ([NativePlugin.isAvailable]), so this reuses that path rather than inventing one.
#
# -checklinkname=0 is required. github.com/wlynxg/anet reaches net.zoneCache through
# //go:linkname to enumerate interfaces on Android (the platform blocks the normal API),
# and since Go 1.23 the linker rejects linknames to private std symbols by default:
#   link: github.com/wlynxg/anet: invalid reference to net.zoneCache
# Without the flag the build fails at the link step, having compiled cleanly.

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

if (-not $RepositoryRoot) {
    $RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
}
if (-not $WorkRoot) {
    $WorkRoot = Join-Path $RepositoryRoot ".native-work/olcrtc"
}

$lock = Get-Content -LiteralPath (Join-Path $RepositoryRoot "native/versions.lock") -Raw |
    ConvertFrom-Json
$spec = $lock.plugins.olcrtc
if (-not $spec) { throw "native/versions.lock has no plugins.olcrtc entry." }

$archives = Join-Path $WorkRoot "archives"
$sources = Join-Path $WorkRoot "sources"
$goRoot = Join-Path $WorkRoot "go"
foreach ($dir in @($archives, $sources, $goRoot)) {
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
}

# ---- Go toolchain ---------------------------------------------------------------
# Pinned and hashed like every other toolchain here. GOTOOLCHAIN=local afterwards, so a
# go.mod asking for a newer release cannot make the build quietly download one.
$goSpec = $spec.go
# Per host OS, because this script runs on both: CI is Linux, a developer machine is
# usually Windows, and a Linux tarball unpacked on Windows leaves a `go` that cannot be
# executed. The failure is opaque when it happens -- the call does not start, so
# $LASTEXITCODE is never even set.
$goHost = if ($IsWindows -or $env:OS -eq "Windows_NT") { $goSpec.windowsAmd64 } else { $goSpec.linuxAmd64 }
if (-not $goHost) { throw "versions.lock has no Go distribution pinned for this host" }
$goArchive = Join-Path $archives $goHost.file
Get-Asset $goHost.url $goArchive $goHost.sha256 "go $($goSpec.version)"
$goExe = if ($IsWindows -or $env:OS -eq "Windows_NT") { "go/bin/go.exe" } else { "go/bin/go" }
if (-not (Test-Path -LiteralPath (Join-Path $goRoot $goExe) -PathType Leaf)) {
    if ($goArchive.EndsWith(".zip")) {
        Expand-Archive -LiteralPath $goArchive -DestinationPath $goRoot -Force
    } else {
        tar -xzf $goArchive -C $goRoot
        if ($LASTEXITCODE -ne 0) { throw "failed to unpack the Go toolchain" }
    }
}
$go = Join-Path $goRoot $goExe
if (-not (Test-Path -LiteralPath $go -PathType Leaf)) { throw "go binary missing at $go" }

# ---- source ---------------------------------------------------------------------
$srcArchive = Join-Path $archives "olcrtc-$($spec.commit).tar.gz"
Get-Asset $spec.archive $srcArchive $spec.sha256 "olcrtc $($spec.commit.Substring(0,12))"
$srcDir = Join-Path $sources "olcrtc-$($spec.commit)"
if (-not (Test-Path -LiteralPath $srcDir -PathType Container)) {
    tar -xzf $srcArchive -C $sources
    if ($LASTEXITCODE -ne 0) { throw "failed to unpack the olcrtc source" }
}
if (-not (Test-Path -LiteralPath (Join-Path $srcDir "cmd/olcrtc") -PathType Container)) {
    throw "olcrtc source does not contain cmd/olcrtc"
}

# ---- build ----------------------------------------------------------------------
$outDir = Join-Path $RepositoryRoot "app/src/main/jniLibs/arm64-v8a"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$output = Join-Path $outDir "libolcrtc.so"

$env:GOTOOLCHAIN = "local"
$env:CGO_ENABLED = "0"
$env:GOOS = "android"
$env:GOARCH = "arm64"
$env:GOFLAGS = "-mod=mod"
$env:GOPATH = Join-Path $WorkRoot "gopath"
$env:GOCACHE = Join-Path $WorkRoot "gocache"
$env:GOMODCACHE = Join-Path $WorkRoot "gomodcache"

Push-Location $srcDir
try {
    Write-Host "build    olcrtc android/arm64"
    & $go build -trimpath -ldflags "-s -w -checklinkname=0" -o $output ./cmd/olcrtc
    if ($LASTEXITCODE -ne 0) { throw "olcrtc build failed" }
} finally {
    Pop-Location
}

if (-not (Test-Path -LiteralPath $output -PathType Leaf)) {
    throw "olcrtc build produced no binary at $output"
}
$size = (Get-Item -LiteralPath $output).Length
Write-Host "ok       $output ($([math]::Round($size / 1MB, 1)) MB)"
