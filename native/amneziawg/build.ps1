[CmdletBinding()]
param(
    [string]$NativeWorkRoot,
    [Parameter(Mandatory = $true)]
    [string]$AndroidSdkRoot,
    [string]$OutputPath,
    [switch]$ValidateOnly
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

. (Join-Path $PSScriptRoot "lib\NativeBuildPolicy.ps1")

function Invoke-Checked {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Executable,
        [Parameter(ValueFromRemainingArguments = $true)]
        [string[]]$Arguments
    )

    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed with exit code ${LASTEXITCODE}: $Executable $($Arguments -join ' ')"
    }
}

function Remove-OwnedTree {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,
        [Parameter(Mandatory = $true)]
        [string]$TaskRoot,
        [Parameter(Mandatory = $true)]
        [string]$RepositoryRoot
    )

    Assert-SafeTaskPath -Path $Path -TaskRoot $TaskRoot -RepositoryRoot $RepositoryRoot
    if (Test-Path -LiteralPath $Path) {
        $ownedItems = @(Get-ChildItem -LiteralPath $Path -Force -Recurse)
        foreach ($item in $ownedItems) {
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw "Task-owned tree contains a forbidden reparse point: $($item.FullName)"
            }
            if (($item.Attributes -band [IO.FileAttributes]::ReadOnly) -ne 0) {
                $item.Attributes = $item.Attributes -band (-bnot [IO.FileAttributes]::ReadOnly)
            }
        }
        $rootItem = Get-Item -LiteralPath $Path -Force
        if (($rootItem.Attributes -band [IO.FileAttributes]::ReadOnly) -ne 0) {
            $rootItem.Attributes = $rootItem.Attributes -band (-bnot [IO.FileAttributes]::ReadOnly)
        }
        [IO.Directory]::Delete([IO.Path]::GetFullPath($Path), $true)
    }
}

function New-OwnedDirectory {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,
        [Parameter(Mandatory = $true)]
        [string]$TaskRoot,
        [Parameter(Mandatory = $true)]
        [string]$RepositoryRoot
    )

    Assert-SafeTaskPath -Path $Path -TaskRoot $TaskRoot -RepositoryRoot $RepositoryRoot
    New-Item -ItemType Directory -Path $Path -Force | Out-Null
    Assert-SafeTaskPath -Path $Path -TaskRoot $TaskRoot -RepositoryRoot $RepositoryRoot
}

function Get-VerifiedDownload {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Url,
        [Parameter(Mandatory = $true)]
        [string]$Sha256,
        [Parameter(Mandatory = $true)]
        [string]$Destination,
        [Parameter(Mandatory = $true)]
        [string]$ArchiveRoot,
        [Parameter(Mandatory = $true)]
        [string]$TaskRoot,
        [Parameter(Mandatory = $true)]
        [string]$RepositoryRoot,
        [Parameter(Mandatory = $true)]
        [string]$Label
    )

    $uri = [Uri]$Url
    if ($uri.Scheme -cne "https") {
        throw "$Label URL must use HTTPS."
    }
    Assert-SafeTaskPath -Path $Destination -TaskRoot $TaskRoot -RepositoryRoot $RepositoryRoot
    if (-not (Test-Path -LiteralPath $Destination -PathType Leaf)) {
        $download = Join-Path $ArchiveRoot ".$([IO.Path]::GetFileName($Destination)).$PID.download"
        Assert-SafeTaskPath -Path $download -TaskRoot $TaskRoot -RepositoryRoot $RepositoryRoot
        try {
            Write-Host "Downloading pinned $Label..."
            Invoke-WebRequest -Uri $uri -OutFile $download -UseBasicParsing
            Assert-VerifiedSha256 -Path $download -ExpectedSha256 $Sha256 -Label "downloaded $Label"
            Move-Item -LiteralPath $download -Destination $Destination
        } finally {
            if (Test-Path -LiteralPath $download) {
                Remove-Item -LiteralPath $download -Force
            }
        }
    }
    Assert-VerifiedSha256 -Path $Destination -ExpectedSha256 $Sha256 -Label $Label
    return [IO.Path]::GetFullPath($Destination)
}

function Expand-SafeTarGz {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ArchivePath,
        [Parameter(Mandatory = $true)]
        [string]$Destination,
        [Parameter(Mandatory = $true)]
        [string]$TaskRoot,
        [Parameter(Mandatory = $true)]
        [string]$RepositoryRoot,
        [Parameter(Mandatory = $true)]
        [string]$Label
    )

    Add-Type -AssemblyName System.Formats.Tar
    $staging = "$Destination.extract-$PID"
    Assert-SafeTaskPath -Path $Destination -TaskRoot $TaskRoot -RepositoryRoot $RepositoryRoot
    Assert-SafeTaskPath -Path $staging -TaskRoot $TaskRoot -RepositoryRoot $RepositoryRoot
    Remove-OwnedTree -Path $Destination -TaskRoot $TaskRoot -RepositoryRoot $RepositoryRoot
    Remove-OwnedTree -Path $staging -TaskRoot $TaskRoot -RepositoryRoot $RepositoryRoot
    New-OwnedDirectory -Path $staging -TaskRoot $TaskRoot -RepositoryRoot $RepositoryRoot

    $archiveRoot = $null
    $seen = [Collections.Generic.HashSet[string]]::new(
        $(if ($IsWindows) { [StringComparer]::OrdinalIgnoreCase } else { [StringComparer]::Ordinal })
    )
    $fileCount = 0
    $fileStream = $null
    $gzipStream = $null
    $reader = $null
    try {
        $fileStream = [IO.File]::OpenRead($ArchivePath)
        $gzipStream = [IO.Compression.GZipStream]::new(
            $fileStream,
            [IO.Compression.CompressionMode]::Decompress,
            $false
        )
        $reader = [System.Formats.Tar.TarReader]::new($gzipStream, $false)
        while ($null -ne ($entry = $reader.GetNextEntry())) {
            if ($entry.EntryType -eq [System.Formats.Tar.TarEntryType]::GlobalExtendedAttributes) {
                continue
            }
            if ($entry.EntryType -ne [System.Formats.Tar.TarEntryType]::Directory -and
                $entry.EntryType -ne [System.Formats.Tar.TarEntryType]::RegularFile) {
                throw "$Label contains a forbidden link or entry type $($entry.EntryType): $($entry.Name)"
            }
            $entryName = ([string]$entry.Name).Replace('\', '/').TrimEnd('/')
            Assert-SafeArchiveEntries -Entries @($entryName) -Label $Label
            $parts = @($entryName.Split('/'))
            if ($parts.Count -eq 0 -or [string]::IsNullOrWhiteSpace($parts[0])) {
                throw "$Label contains an empty root."
            }
            if ($null -eq $archiveRoot) {
                $archiveRoot = $parts[0]
            } elseif ($parts[0] -cne $archiveRoot) {
                throw "$Label contains more than one archive root."
            }
            if ($parts.Count -eq 1) {
                if ($entry.EntryType -ne [System.Formats.Tar.TarEntryType]::Directory) {
                    throw "$Label root must be a directory."
                }
                continue
            }

            $relative = $parts[1..($parts.Count - 1)] -join '/'
            if (-not $seen.Add($relative)) {
                throw "$Label contains a duplicate or case-colliding path: $relative"
            }
            $relativeSystem = $relative.Replace('/', [IO.Path]::DirectorySeparatorChar)
            $target = [IO.Path]::GetFullPath((Join-Path $staging $relativeSystem))
            Assert-SafeTaskPath -Path $target -TaskRoot $TaskRoot -RepositoryRoot $RepositoryRoot
            if (-not (Test-PathWithin -Path $target -Parent $staging) -or $target -eq $staging) {
                throw "$Label entry escaped extraction: $relative"
            }
            if ($entry.EntryType -eq [System.Formats.Tar.TarEntryType]::Directory) {
                New-OwnedDirectory -Path $target -TaskRoot $TaskRoot -RepositoryRoot $RepositoryRoot
                continue
            }
            $parent = [IO.Path]::GetDirectoryName($target)
            if (-not (Test-Path -LiteralPath $parent -PathType Container)) {
                New-OwnedDirectory -Path $parent -TaskRoot $TaskRoot -RepositoryRoot $RepositoryRoot
            }
            $executable = Test-ArchiveModeExecutable -Mode $entry.Mode
            Write-ArchiveRegularFile `
                -Path $target `
                -DataStream $entry.DataStream `
                -Executable:$executable
            $fileCount++
        }
    } finally {
        if ($null -ne $reader) {
            $reader.Dispose()
        }
        if ($null -ne $gzipStream) {
            $gzipStream.Dispose()
        }
        if ($null -ne $fileStream) {
            $fileStream.Dispose()
        }
    }
    if ($fileCount -eq 0 -or $null -eq $archiveRoot) {
        throw "$Label did not contain a source tree."
    }
    Move-Item -LiteralPath $staging -Destination $Destination
    Assert-SafeTaskPath -Path $Destination -TaskRoot $TaskRoot -RepositoryRoot $RepositoryRoot
}

function Expand-SafeGoZip {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ArchivePath,
        [Parameter(Mandatory = $true)]
        [string]$Destination,
        [Parameter(Mandatory = $true)]
        [string]$TaskRoot,
        [Parameter(Mandatory = $true)]
        [string]$RepositoryRoot
    )

    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $staging = "$Destination.extract-$PID"
    Remove-OwnedTree -Path $Destination -TaskRoot $TaskRoot -RepositoryRoot $RepositoryRoot
    Remove-OwnedTree -Path $staging -TaskRoot $TaskRoot -RepositoryRoot $RepositoryRoot
    New-OwnedDirectory -Path $staging -TaskRoot $TaskRoot -RepositoryRoot $RepositoryRoot
    $archive = [IO.Compression.ZipFile]::OpenRead($ArchivePath)
    try {
        $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
        foreach ($entry in $archive.Entries) {
            $name = $entry.FullName.Replace('\', '/')
            Assert-SafeArchiveEntries -Entries @($name) -Label "Go toolchain ZIP"
            if (-not $name.StartsWith("go/", [StringComparison]::Ordinal) -and $name -cne "go/") {
                throw "Go toolchain ZIP contains an unexpected root: $name"
            }
            if (-not $seen.Add($name)) {
                throw "Go toolchain ZIP contains a duplicate or case-colliding path: $name"
            }
            if ($name.EndsWith("/", [StringComparison]::Ordinal)) {
                continue
            }
            $relative = $name.Substring(3)
            $target = [IO.Path]::GetFullPath(
                (Join-Path $staging $relative.Replace('/', [IO.Path]::DirectorySeparatorChar))
            )
            if (-not (Test-PathWithin -Path $target -Parent $staging) -or $target -eq $staging) {
                throw "Go toolchain ZIP entry escaped extraction: $name"
            }
            $parent = [IO.Path]::GetDirectoryName($target)
            if (-not (Test-Path -LiteralPath $parent -PathType Container)) {
                New-OwnedDirectory `
                    -Path $parent `
                    -TaskRoot $TaskRoot `
                    -RepositoryRoot $RepositoryRoot
            }
            $input = $entry.Open()
            $output = [IO.File]::Open(
                $target,
                [IO.FileMode]::CreateNew,
                [IO.FileAccess]::Write,
                [IO.FileShare]::None
            )
            try {
                $input.CopyTo($output)
            } finally {
                $output.Dispose()
                $input.Dispose()
            }
        }
    } finally {
        $archive.Dispose()
    }
    Move-Item -LiteralPath $staging -Destination $Destination
    Assert-SafeTaskPath -Path $Destination -TaskRoot $TaskRoot -RepositoryRoot $RepositoryRoot
}

function Get-TextSha256 {
    param([Parameter(Mandatory = $true)][string]$Text)

    $bytes = [Text.UTF8Encoding]::new($false).GetBytes($Text)
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        return [Convert]::ToHexString($sha.ComputeHash($bytes)).ToLowerInvariant()
    } finally {
        $sha.Dispose()
    }
}

function Apply-DirectJniPatch {
    param(
        [Parameter(Mandatory = $true)]
        [object]$Patch,
        [Parameter(Mandatory = $true)]
        [string]$GlueRoot,
        [Parameter(Mandatory = $true)]
        [string]$RepositoryRoot
    )

    $patchPath = [IO.Path]::GetFullPath((Join-Path $RepositoryRoot ([string]$Patch.file)))
    if (-not (Test-PathWithin -Path $patchPath -Parent $RepositoryRoot)) {
        throw "AmneziaWG patch escaped the repository."
    }
    Assert-NoReparseAncestors -Path $patchPath
    Assert-VerifiedSha256 -Path $patchPath -ExpectedSha256 $Patch.sha256 -Label "direct JNI patch"
    Invoke-Checked git -c core.autocrlf=false -c core.eol=lf -C $GlueRoot apply --check --no-index --whitespace=nowarn $patchPath
    Invoke-Checked git -c core.autocrlf=false -c core.eol=lf -C $GlueRoot apply --no-index --whitespace=nowarn $patchPath
    Invoke-Checked git -c core.autocrlf=false -c core.eol=lf -C $GlueRoot apply --reverse --check --no-index --whitespace=nowarn $patchPath
    $target = Join-Path $GlueRoot ([string]$Patch.target)
    Assert-VerifiedSha256 -Path $target -ExpectedSha256 $Patch.patchedSha256 -Label "patched direct JNI glue"
    $patched = Get-Content -LiteralPath $target -Raw
    foreach ($forbidden in @("ipc.UAPIOpen", "ipc.UAPIListen", "ipc.socketDirectory", "net.Listener")) {
        if ($patched.Contains($forbidden, [StringComparison]::Ordinal)) {
            throw "Direct JNI patch retained forbidden UAPI dependency: $forbidden"
        }
    }
}

function Get-ElfInfo {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Library,
        [Parameter(Mandatory = $true)]
        [string]$ReadElf,
        [Parameter(Mandatory = $true)]
        [string]$Nm
    )

    $header = (& $ReadElf --file-header $Library 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0 -or $header -notmatch '(?m)^\s*Machine:\s*(.+?)\s*$') {
        throw "Unable to read ELF architecture: $Library"
    }
    $architecture = $Matches[1]

    $dynamic = (& $ReadElf --dynamic $Library 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0 -or $dynamic -notmatch 'SONAME.*\[(.+?)\]') {
        throw "Unable to read ELF SONAME: $Library"
    }
    $soname = $Matches[1]

    $notes = (& $ReadElf --notes $Library 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to read ELF Android API note: $Library"
    }
    $androidApi = $null
    if ($notes -match '(?i)Android\s+API(?: level)?\s*[:=]\s*(\d+)') {
        $androidApi = [int]$Matches[1]
    } elseif ($notes -match '(?is)\.note\.android\.ident.*?NT_ANDROID_TYPE_IDENT.*?description data:\s*([0-9a-f]{2})\s+([0-9a-f]{2})\s+([0-9a-f]{2})\s+([0-9a-f]{2})(?:\s|$)') {
        $apiBytes = @(
            [Convert]::ToByte($Matches[1], 16),
            [Convert]::ToByte($Matches[2], 16),
            [Convert]::ToByte($Matches[3], 16),
            [Convert]::ToByte($Matches[4], 16)
        )
        $androidApi = [BitConverter]::ToUInt32([byte[]]$apiBytes, 0)
    }
    if ($null -eq $androidApi) {
        throw "ELF does not expose the required Android API note: $Library`n$notes"
    }

    $symbolsText = (& $Nm --dynamic --defined-only $Library 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to read ELF exports: $Library"
    }
    $symbols = @(
        $symbolsText -split "`r?`n" |
            ForEach-Object { if ($_ -match '\s([^\s]+)$') { $Matches[1] } } |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
    )
    return [ordered]@{
        architecture = $architecture
        androidApi = $androidApi
        soname = $soname
        cExports = @($symbols | Where-Object { $_ -match '^awg(?:Turn|Get|Version)' })
        jniSymbols = @($symbols | Where-Object { $_ -match '^Java_' })
    }
}

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
$lockPath = Join-Path $repositoryRoot "native\versions.lock"
$lock = Read-AmneziaWgLock -LockPath $lockPath

if ([string]::IsNullOrWhiteSpace($NativeWorkRoot)) {
    $NativeWorkRoot = Join-Path ([IO.Path]::GetTempPath()) "lean-amneziawg-native"
}
$NativeWorkRoot = [IO.Path]::GetFullPath($NativeWorkRoot).TrimEnd(
    [IO.Path]::DirectorySeparatorChar,
    [IO.Path]::AltDirectorySeparatorChar
)
$workProbe = Join-Path $NativeWorkRoot ".path-probe"
Assert-SafeTaskPath -Path $workProbe -TaskRoot $NativeWorkRoot -RepositoryRoot $repositoryRoot
if (-not (Test-Path -LiteralPath $NativeWorkRoot)) {
    New-Item -ItemType Directory -Path $NativeWorkRoot | Out-Null
}
Assert-NoReparseAncestors -Path $NativeWorkRoot

$expectedOutput = Join-Path $repositoryRoot "native\amneziawg\generated\jni"
if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $OutputPath = $expectedOutput
}
$OutputPath = [IO.Path]::GetFullPath($OutputPath)
Assert-SafeGeneratedOutputPath `
    -Path $OutputPath `
    -ExpectedOutputRoot $expectedOutput `
    -RepositoryRoot $repositoryRoot

$archiveRoot = Join-Path $NativeWorkRoot "archives"
$sourceRoot = Join-Path $NativeWorkRoot "sources"
$toolchainRoot = Join-Path $NativeWorkRoot "toolchains"
$goPath = Join-Path $NativeWorkRoot "gopath"
$goModCache = Join-Path $NativeWorkRoot "gomodcache"
$goCache = Join-Path $NativeWorkRoot "gocache"
$buildRoot = Join-Path $NativeWorkRoot "build"
$goBin = Join-Path $NativeWorkRoot "gobin"
foreach ($directory in @(
    $archiveRoot,
    $sourceRoot,
    $toolchainRoot,
    $goPath,
    $goModCache,
    $goCache,
    $buildRoot,
    $goBin
)) {
    Assert-SafeTaskPath -Path $directory -TaskRoot $NativeWorkRoot -RepositoryRoot $repositoryRoot
}

if ($ValidateOnly) {
    Write-Host "AmneziaWG native path and lock validation passed."
    exit 0
}

New-OwnedDirectory -Path $archiveRoot -TaskRoot $NativeWorkRoot -RepositoryRoot $repositoryRoot
foreach ($cleanDirectory in @($sourceRoot, $toolchainRoot, $goPath, $goModCache, $goCache, $buildRoot, $goBin)) {
    Remove-OwnedTree -Path $cleanDirectory -TaskRoot $NativeWorkRoot -RepositoryRoot $repositoryRoot
    New-OwnedDirectory -Path $cleanDirectory -TaskRoot $NativeWorkRoot -RepositoryRoot $repositoryRoot
}

$platform = if ($IsWindows) { "windows-amd64" } elseif ($IsLinux) { "linux-amd64" } else {
    throw "Pinned AmneziaWG builds support only Windows and Linux amd64 hosts."
}
if ([Runtime.InteropServices.RuntimeInformation]::ProcessArchitecture -ne [Runtime.InteropServices.Architecture]::X64) {
    throw "Pinned AmneziaWG builds support only amd64 hosts."
}
$goDistribution = $lock.goDistributions.$platform
$goArchive = Get-VerifiedDownload `
    -Url $goDistribution.url `
    -Sha256 $goDistribution.sha256 `
    -Destination (Join-Path $archiveRoot $goDistribution.file) `
    -ArchiveRoot $archiveRoot `
    -TaskRoot $NativeWorkRoot `
    -RepositoryRoot $repositoryRoot `
    -Label "Go $($lock.goVersion) archive"
$androidArchive = Get-VerifiedDownload `
    -Url $lock.androidSource.archive `
    -Sha256 $lock.androidSource.archiveSha256 `
    -Destination (Join-Path $archiveRoot "amneziawg-android-$($lock.androidSource.revision).tar.gz") `
    -ArchiveRoot $archiveRoot `
    -TaskRoot $NativeWorkRoot `
    -RepositoryRoot $repositoryRoot `
    -Label "AmneziaWG Android source"
$goSourceArchive = Get-VerifiedDownload `
    -Url $lock.goSource.archive `
    -Sha256 $lock.goSource.archiveSha256 `
    -Destination (Join-Path $archiveRoot "amneziawg-go-$($lock.goSource.revision).tar.gz") `
    -ArchiveRoot $archiveRoot `
    -TaskRoot $NativeWorkRoot `
    -RepositoryRoot $repositoryRoot `
    -Label "AmneziaWG Go source"

$goToolchain = Join-Path $toolchainRoot "go-$($lock.goVersion)-$platform"
if ($IsWindows) {
    Expand-SafeGoZip `
        -ArchivePath $goArchive `
        -Destination $goToolchain `
        -TaskRoot $NativeWorkRoot `
        -RepositoryRoot $repositoryRoot
} else {
    Expand-SafeTarGz `
        -ArchivePath $goArchive `
        -Destination $goToolchain `
        -TaskRoot $NativeWorkRoot `
        -RepositoryRoot $repositoryRoot `
        -Label "Go toolchain archive"
}
$goExe = Join-Path $goToolchain $(if ($IsWindows) { "bin\go.exe" } else { "bin/go" })
Assert-PinnedExecutable -ActualPath $goExe -ExpectedPath $goExe -Label "Go"
if (-not (Test-Path -LiteralPath $goExe -PathType Leaf)) {
    throw "Pinned Go executable is missing: $goExe"
}
$env:GOROOT = $goToolchain
$goVersionText = (& $goExe version 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $goVersionText -notmatch "\bgo$([regex]::Escape($lock.goVersion))\b") {
    throw "Pinned Go archive did not provide Go $($lock.goVersion): $goVersionText"
}

$androidSource = Join-Path $sourceRoot "amneziawg-android"
$goSource = Join-Path $sourceRoot "amneziawg-go"
Expand-SafeTarGz `
    -ArchivePath $androidArchive `
    -Destination $androidSource `
    -TaskRoot $NativeWorkRoot `
    -RepositoryRoot $repositoryRoot `
    -Label "AmneziaWG Android archive"
Expand-SafeTarGz `
    -ArchivePath $goSourceArchive `
    -Destination $goSource `
    -TaskRoot $NativeWorkRoot `
    -RepositoryRoot $repositoryRoot `
    -Label "AmneziaWG Go archive"

$glueRoot = Join-Path $androidSource "tunnel\tools\libwg-go"
foreach ($file in @("api-android.go", "jni.c", "go.mod", "go.sum")) {
    Assert-VerifiedSha256 `
        -Path (Join-Path $glueRoot $file) `
        -ExpectedSha256 $lock.glueFiles.$file `
        -Label "upstream $file"
}
$moduleSumLine = "$($lock.goSource.module) $($lock.goSource.moduleVersion) $($lock.goSource.moduleSum)"
$sumLines = @(Get-Content -LiteralPath (Join-Path $glueRoot "go.sum"))
if (@($sumLines | Where-Object { $_ -ceq $moduleSumLine }).Count -ne 1) {
    throw "Pinned AmneziaWG-Go module sum is missing or duplicated."
}
$goModuleText = Get-Content -LiteralPath (Join-Path $goSource "go.mod") -Raw
if ($goModuleText -notmatch '(?m)^module github\.com/amnezia-vpn/amneziawg-go\s*$') {
    throw "Extracted AmneziaWG-Go source has the wrong module path."
}
Apply-DirectJniPatch `
    -Patch $lock.patch `
    -GlueRoot $glueRoot `
    -RepositoryRoot $repositoryRoot

$androidSdkRoot = [IO.Path]::GetFullPath($AndroidSdkRoot)
Assert-NoReparseAncestors -Path $androidSdkRoot
$ndkRoot = Join-Path $androidSdkRoot "ndk\$($lock.ndkVersion)"
$sourceProperties = Join-Path $ndkRoot "source.properties"
if (-not (Test-Path -LiteralPath $sourceProperties -PathType Leaf)) {
    throw "Pinned Android NDK is missing: $ndkRoot"
}
$ndkProperties = Get-Content -LiteralPath $sourceProperties -Raw
if ($ndkProperties -notmatch "(?m)^Pkg\.Revision\s*=\s*$([regex]::Escape($lock.ndkVersion))\s*$") {
    throw "Android NDK source.properties does not match $($lock.ndkVersion)."
}
$hostTag = if ($IsWindows) { "windows-x86_64" } else { "linux-x86_64" }
$llvmBin = Join-Path $ndkRoot "toolchains\llvm\prebuilt\$hostTag\bin"
$clang = Join-Path $llvmBin $(if ($IsWindows) { "clang.exe" } else { "clang" })
$clangxx = Join-Path $llvmBin $(if ($IsWindows) { "clang++.exe" } else { "clang++" })
$readElf = Join-Path $llvmBin $(if ($IsWindows) { "llvm-readelf.exe" } else { "llvm-readelf" })
$nm = Join-Path $llvmBin $(if ($IsWindows) { "llvm-nm.exe" } else { "llvm-nm" })
foreach ($tool in @($clang, $clangxx, $readElf, $nm)) {
    if (-not (Test-Path -LiteralPath $tool -PathType Leaf)) {
        throw "Pinned NDK tool is missing: $tool"
    }
}

$env:PATH = [IO.Path]::GetDirectoryName($goExe) + [IO.Path]::PathSeparator + $env:PATH
$activeGo = (Get-Command go -ErrorAction Stop).Source
Assert-PinnedExecutable -ActualPath $activeGo -ExpectedPath $goExe -Label "Go"
$env:GOENV = "off"
$env:GOWORK = "off"
$env:GOBIN = $goBin
$env:GOPATH = $goPath
$env:GOMODCACHE = $goModCache
$env:GOCACHE = $goCache
$env:GOTOOLCHAIN = "local"
$env:GOPROXY = "https://proxy.golang.org,direct"
$env:GOSUMDB = "sum.golang.org"
$env:GOFLAGS = "-mod=readonly"
$env:CGO_ENABLED = "1"
$env:GOOS = "android"
$env:CC = $clang
$env:CXX = $clangxx
$env:SOURCE_DATE_EPOCH = "0"
$env:TZ = "UTC"

Push-Location $glueRoot
try {
    $relativeGoSource = Get-DeterministicModuleReplacePath `
        -ModuleDirectory $glueRoot `
        -DependencyDirectory $goSource
    Invoke-Checked $goExe mod edit "-replace=$($lock.goSource.module)=$relativeGoSource"
    $moduleJson = (& $goExe list -mod=readonly -m -json $lock.goSource.module 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0) {
        throw "Go did not resolve AmneziaWG-Go through the pinned local replace."
    }
    $resolvedModule = $moduleJson | ConvertFrom-Json
    $resolvedSource = if ($null -ne $resolvedModule.Replace) {
        [IO.Path]::GetFullPath([string]$resolvedModule.Replace.Dir)
    } else {
        ""
    }
    $pathComparison = if ($IsWindows) {
        [StringComparison]::OrdinalIgnoreCase
    } else {
        [StringComparison]::Ordinal
    }
    if ($resolvedModule.Path -cne $lock.goSource.module -or
        $resolvedModule.Version -cne $lock.goSource.moduleVersion -or
        -not $resolvedSource.Equals([IO.Path]::GetFullPath($goSource), $pathComparison)) {
        throw "Go did not resolve AmneziaWG-Go through the pinned local replace."
    }

    $abiMatrix = [ordered]@{
        "armeabi-v7a" = [ordered]@{ goarch = "arm"; goarm = "7"; target = "armv7a-linux-androideabi24"; machine = "ARM" }
        "arm64-v8a" = [ordered]@{ goarch = "arm64"; goarm = ""; target = "aarch64-linux-android24"; machine = "AArch64" }
        "x86" = [ordered]@{ goarch = "386"; goarm = ""; target = "i686-linux-android24"; machine = "Intel 80386" }
        "x86_64" = [ordered]@{ goarch = "amd64"; goarm = ""; target = "x86_64-linux-android24"; machine = "Advanced Micro Devices X86-64" }
    }
    $outputs = [ordered]@{}
    $elfReports = [ordered]@{}
    foreach ($abi in $lock.abis) {
        $config = $abiMatrix[$abi]
        if ($null -eq $config) {
            throw "No compiler mapping exists for ABI $abi."
        }
        $abiBuild = Join-Path $buildRoot $abi
        New-OwnedDirectory -Path $abiBuild -TaskRoot $NativeWorkRoot -RepositoryRoot $repositoryRoot
        $library = Join-Path $abiBuild "libwg-go.so"
        $env:GOARCH = $config.goarch
        $env:GOARM = $config.goarm
        $env:GOAMD64 = "v1"
        $compilerSuffix = if ($IsWindows) { ".cmd" } else { "" }
        $env:CC = Join-Path $llvmBin "$($config.target)-clang$compilerSuffix"
        $env:CXX = Join-Path $llvmBin "$($config.target)-clang++$compilerSuffix"
        foreach ($compiler in @($env:CC, $env:CXX)) {
            if (-not (Test-Path -LiteralPath $compiler -PathType Leaf)) {
                throw "Pinned NDK API compiler is missing: $compiler"
            }
        }
        $env:CGO_CFLAGS = "-fPIC"
        $env:CGO_CPPFLAGS = ""
        $env:CGO_CXXFLAGS = "-fPIC"
        $env:CGO_LDFLAGS = "-Wl,--build-id=none -Wl,-soname,libwg-go.so"

        Write-Host "Building pinned AmneziaWG-Go for $abi..."
        Invoke-Checked `
            -Executable $goExe `
            -Arguments @(
                "build",
                "-tags=linux",
                "-trimpath",
                "-buildvcs=false",
                "-buildmode=c-shared",
                "-ldflags=-buildid=",
                "-o",
                $library,
                "."
            )
        if (-not (Test-Path -LiteralPath $library -PathType Leaf)) {
            throw "Go did not produce $library."
        }
        $elfInfo = Get-ElfInfo -Library $library -ReadElf $readElf -Nm $nm
        Assert-ElfContract `
            -ElfInfo $elfInfo `
            -ExpectedArchitecture $config.machine `
            -ExpectedAndroidApi $lock.androidApi
        $outputs["$abi/libwg-go.so"] = (
            Get-FileHash -LiteralPath $library -Algorithm SHA256
        ).Hash.ToLowerInvariant()
        $elfReports[$abi] = $elfInfo
    }
} finally {
    Pop-Location
}

Assert-SafeGeneratedOutputPath `
    -Path $OutputPath `
    -ExpectedOutputRoot $expectedOutput `
    -RepositoryRoot $repositoryRoot
if (Test-Path -LiteralPath $OutputPath) {
    Remove-Item -LiteralPath $OutputPath -Recurse -Force
}
New-Item -ItemType Directory -Path $OutputPath -Force | Out-Null
foreach ($abi in $lock.abis) {
    $abiOutput = Join-Path $OutputPath $abi
    New-Item -ItemType Directory -Path $abiOutput | Out-Null
    Copy-Item `
        -LiteralPath (Join-Path $buildRoot "$abi\libwg-go.so") `
        -Destination (Join-Path $abiOutput "libwg-go.so")
    Assert-VerifiedSha256 `
        -Path (Join-Path $abiOutput "libwg-go.so") `
        -ExpectedSha256 $outputs["$abi/libwg-go.so"] `
        -Label "generated $abi output"
}

$report = [ordered]@{
    schema = 1
    sources = [ordered]@{
        androidRepository = $lock.androidSource.repository
        androidRevision = $lock.androidSource.revision
        androidArchiveSha256 = $lock.androidSource.archiveSha256
        goRepository = $lock.goSource.repository
        goTag = $lock.goSource.tag
        goRevision = $lock.goSource.revision
        goArchiveSha256 = $lock.goSource.archiveSha256
        moduleSum = $moduleSumLine
    }
    toolchain = [ordered]@{
        go = $lock.goVersion
        goArchiveSha256 = $goDistribution.sha256
        goVersionOutput = $goVersionText
        ndk = $lock.ndkVersion
        androidApi = $lock.androidApi
    }
    glue = [ordered]@{
        files = $lock.glueFiles
        patchSha256 = $lock.patch.sha256
        patchedSha256 = $lock.patch.patchedSha256
    }
    outputs = $outputs
    elf = $elfReports
}
$reportPath = Join-Path ([IO.Path]::GetDirectoryName($OutputPath)) "build-report.json"
$report | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $reportPath -Encoding utf8NoBOM
Write-Host "Built and verified service-free AmneziaWG-Go for four ABIs."
Write-Host "Native report: $reportPath"
