$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$script:RequiredAbis = @("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
$script:RequiredCExports = @(
    "awgGetConfig",
    "awgGetSocketV4",
    "awgGetSocketV6",
    "awgTurnOff",
    "awgTurnOn",
    "awgVersion"
)
$script:RequiredJniSymbols = @(
    "Java_org_amnezia_awg_GoBackend_awgGetConfig",
    "Java_org_amnezia_awg_GoBackend_awgGetSocketV4",
    "Java_org_amnezia_awg_GoBackend_awgGetSocketV6",
    "Java_org_amnezia_awg_GoBackend_awgTurnOff",
    "Java_org_amnezia_awg_GoBackend_awgTurnOn",
    "Java_org_amnezia_awg_GoBackend_awgVersion"
)
$script:RequiredClasses = @(
    "org.amnezia.awg.GoBackend",
    "com.th3web.lean.awg.AmneziaWgNative",
    "com.th3web.lean.awg.JniAmneziaWgNative"
)

function Assert-ExactProperties {
    param(
        [Parameter(Mandatory = $true)]
        [object]$Value,

        [Parameter(Mandatory = $true)]
        [string[]]$Expected,

        [Parameter(Mandatory = $true)]
        [string]$Label
    )

    $actual = @($Value.PSObject.Properties.Name | Sort-Object)
    $wanted = @($Expected | Sort-Object)
    if (($actual -join "`n") -cne ($wanted -join "`n")) {
        throw "$Label fields are not exact. Expected [$($wanted -join ', ')], got [$($actual -join ', ')]."
    }
}

function Assert-NoDuplicateJsonProperties {
    param(
        [Parameter(Mandatory = $true)]
        [System.Text.Json.JsonElement]$Element,

        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    if ($Element.ValueKind -eq [System.Text.Json.JsonValueKind]::Object) {
        $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        foreach ($property in $Element.EnumerateObject()) {
            if (-not $seen.Add($property.Name)) {
                throw "Duplicate JSON field at $Path.$($property.Name)."
            }
            Assert-NoDuplicateJsonProperties -Element $property.Value -Path "$Path.$($property.Name)"
        }
    } elseif ($Element.ValueKind -eq [System.Text.Json.JsonValueKind]::Array) {
        $index = 0
        foreach ($item in $Element.EnumerateArray()) {
            Assert-NoDuplicateJsonProperties -Element $item -Path "$Path[$index]"
            $index++
        }
    }
}

function Read-StrictJsonDocument {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "JSON file does not exist: $Path"
    }
    $raw = Get-Content -LiteralPath $Path -Raw
    $document = [System.Text.Json.JsonDocument]::Parse($raw)
    try {
        Assert-NoDuplicateJsonProperties -Element $document.RootElement -Path '$'
    } finally {
        $document.Dispose()
    }
    return $raw | ConvertFrom-Json
}

function Read-AmneziaWgLock {
    param(
        [Parameter(Mandatory = $true)]
        [string]$LockPath
    )

    $root = Read-StrictJsonDocument -Path $LockPath
    # "plugins" pins the external protocol helpers (naive, mieru) that native/plugins
    # vendor.ps1 fetches. Listed here because the check is exact by design — an unknown
    # top-level key means the lock and the build have drifted — so a genuinely new
    # section has to be admitted deliberately, which is what this line is.
    Assert-ExactProperties `
        -Value $root `
        -Expected @("schema", "toolchain", "build", "components", "moduleFiles", "patches", "amneziawg", "plugins") `
        -Label "native lock"
    if ($root.schema -ne 1) {
        throw "Unsupported native lock schema: $($root.schema)"
    }

    $lock = $root.amneziawg
    Assert-ExactProperties `
        -Value $lock `
        -Expected @(
            "goVersion",
            "ndkVersion",
            "androidApi",
            "abis",
            "goDistributions",
            "androidSource",
            "goSource",
            "glueFiles",
            "patch",
            "outputs"
        ) `
        -Label "amneziawg"
    Assert-ExactProperties `
        -Value $lock.goDistributions `
        -Expected @("linux-amd64", "windows-amd64") `
        -Label "amneziawg.goDistributions"
    foreach ($platform in @("linux-amd64", "windows-amd64")) {
        Assert-ExactProperties `
            -Value $lock.goDistributions.$platform `
            -Expected @("file", "url", "sha256") `
            -Label "amneziawg.goDistributions.$platform"
    }
    Assert-ExactProperties `
        -Value $lock.androidSource `
        -Expected @("repository", "archive", "revision", "archiveSha256") `
        -Label "amneziawg.androidSource"
    Assert-ExactProperties `
        -Value $lock.goSource `
        -Expected @(
            "repository",
            "archive",
            "tag",
            "revision",
            "archiveSha256",
            "module",
            "moduleVersion",
            "moduleSum"
        ) `
        -Label "amneziawg.goSource"
    Assert-ExactProperties `
        -Value $lock.glueFiles `
        -Expected @("api-android.go", "jni.c", "go.mod", "go.sum") `
        -Label "amneziawg.glueFiles"
    Assert-ExactProperties `
        -Value $lock.patch `
        -Expected @("file", "sha256", "target", "patchedSha256") `
        -Label "amneziawg.patch"
    Assert-ExactProperties `
        -Value $lock.outputs `
        -Expected @("libraryName", "soname", "jniOwner") `
        -Label "amneziawg.outputs"

    if ($lock.goVersion -cne "1.24.4") {
        throw "Unexpected AmneziaWG Go version: $($lock.goVersion)"
    }
    if ($lock.ndkVersion -cne "26.1.10909125") {
        throw "Unexpected AmneziaWG NDK version: $($lock.ndkVersion)"
    }
    if ($lock.androidApi -ne 24) {
        throw "Unexpected AmneziaWG Android API: $($lock.androidApi)"
    }
    if ((@($lock.abis) -join "`n") -cne ($script:RequiredAbis -join "`n")) {
        throw "Unexpected AmneziaWG ABI list."
    }

    $androidRevision = "4116c836241f737badb99dcd4e990600d46e4c65"
    $goRevision = "730d6c39d0c4e348a3d080bebe496664215e5c99"
    if ($lock.androidSource.repository -cne "https://github.com/amnezia-vpn/amneziawg-android.git" -or
        $lock.androidSource.archive -cne "https://codeload.github.com/amnezia-vpn/amneziawg-android/tar.gz/$androidRevision" -or
        $lock.androidSource.revision -cne $androidRevision) {
        throw "The Android source URL/revision mapping is not exact."
    }
    if ($lock.goSource.repository -cne "https://github.com/amnezia-vpn/amneziawg-go.git" -or
        $lock.goSource.archive -cne "https://codeload.github.com/amnezia-vpn/amneziawg-go/tar.gz/$goRevision" -or
        $lock.goSource.tag -cne "v0.2.16" -or
        $lock.goSource.revision -cne $goRevision -or
        $lock.goSource.module -cne "github.com/amnezia-vpn/amneziawg-go" -or
        $lock.goSource.moduleVersion -cne "v0.2.16" -or
        $lock.goSource.moduleSum -cne "h1:XY6HOq/xtqH8ZXMncRWkjFs85EKdN10NLNnw23kTpE0=") {
        throw "The Go tag/revision/module mapping is not exact."
    }
    if ($lock.outputs.libraryName -cne "libwg-go.so" -or
        $lock.outputs.soname -cne "libwg-go.so" -or
        $lock.outputs.jniOwner -cne "org.amnezia.awg.GoBackend") {
        throw "The AmneziaWG output contract is not exact."
    }

    return [pscustomobject]@{
        goVersion = $lock.goVersion
        ndkVersion = $lock.ndkVersion
        androidApi = [int]$lock.androidApi
        abis = @($lock.abis)
        goDistributions = $lock.goDistributions
        androidSource = $lock.androidSource
        goSource = $lock.goSource
        glueFiles = $lock.glueFiles
        patch = $lock.patch
        outputs = $lock.outputs
    }
}

function Assert-VerifiedSha256 {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,

        [Parameter(Mandatory = $true)]
        [ValidatePattern('^[0-9a-fA-F]{64}$')]
        [string]$ExpectedSha256,

        [Parameter(Mandatory = $true)]
        [string]$Label
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "$Label is missing: $Path"
    }
    $actual = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -cne $ExpectedSha256.ToLowerInvariant()) {
        throw "$Label SHA-256 mismatch. Expected $ExpectedSha256, got $actual."
    }
}

function Assert-SafeArchiveEntries {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Entries,

        [Parameter(Mandatory = $true)]
        [string]$Label
    )

    foreach ($entry in $Entries) {
        if ([string]::IsNullOrWhiteSpace($entry)) {
            continue
        }
        $normalized = $entry.Replace('\', '/')
        if ($normalized.StartsWith("/", [StringComparison]::Ordinal) -or
            $normalized -match '^[A-Za-z]:' -or
            $normalized.IndexOf([char]0) -ge 0) {
            throw "$Label contains an absolute or invalid entry: $entry"
        }
        $depth = 0
        foreach ($segment in $normalized.Split('/')) {
            if ($segment -eq "" -or $segment -eq ".") {
                continue
            }
            if ($segment -eq "..") {
                $depth--
                if ($depth -lt 0) {
                    throw "$Label contains traversal: $entry"
                }
            } else {
                $depth++
            }
        }
        if ($normalized.Split('/') -contains "..") {
            throw "$Label contains traversal: $entry"
        }
    }
}

function Test-ArchiveModeExecutable {
    param(
        [Parameter(Mandatory = $true)]
        [IO.UnixFileMode]$Mode
    )

    $executeMask = [int][IO.UnixFileMode]::UserExecute -bor
        [int][IO.UnixFileMode]::GroupExecute -bor
        [int][IO.UnixFileMode]::OtherExecute
    return (([int]$Mode -band $executeMask) -ne 0)
}

function Write-ArchiveRegularFile {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,

        [Parameter(Mandatory = $true)]
        [AllowNull()]
        [IO.Stream]$DataStream,

        [switch]$Executable
    )

    $output = [IO.File]::Open(
        $Path,
        [IO.FileMode]::CreateNew,
        [IO.FileAccess]::Write,
        [IO.FileShare]::None
    )
    try {
        if ($null -ne $DataStream) {
            $DataStream.CopyTo($output)
        }
    } finally {
        $output.Dispose()
    }
    if (-not $IsWindows) {
        $modeBits = [int][IO.UnixFileMode]::UserRead -bor [int][IO.UnixFileMode]::UserWrite
        if ($Executable) {
            $modeBits = $modeBits -bor [int][IO.UnixFileMode]::UserExecute
        }
        [IO.File]::SetUnixFileMode($Path, [IO.UnixFileMode]$modeBits)
    }
}

function Test-PathWithin {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,

        [Parameter(Mandatory = $true)]
        [string]$Parent
    )

    $fullPath = [IO.Path]::GetFullPath($Path).TrimEnd(
        [IO.Path]::DirectorySeparatorChar,
        [IO.Path]::AltDirectorySeparatorChar
    )
    $fullParent = [IO.Path]::GetFullPath($Parent).TrimEnd(
        [IO.Path]::DirectorySeparatorChar,
        [IO.Path]::AltDirectorySeparatorChar
    )
    $comparison = if ($IsWindows) { [StringComparison]::OrdinalIgnoreCase } else { [StringComparison]::Ordinal }
    return $fullPath.Equals($fullParent, $comparison) -or
        $fullPath.StartsWith("$fullParent$([IO.Path]::DirectorySeparatorChar)", $comparison)
}

function Assert-NoReparseAncestors {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    $candidate = [IO.Path]::GetFullPath($Path)
    while (-not [string]::IsNullOrEmpty($candidate)) {
        if (Test-Path -LiteralPath $candidate) {
            $item = Get-Item -LiteralPath $candidate -Force
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw "Reparse, junction, or symlink path is forbidden: $candidate"
            }
        }
        $parent = [IO.Directory]::GetParent($candidate)
        if ($null -eq $parent) {
            break
        }
        $candidate = $parent.FullName
    }
}

function Assert-SafeTaskPath {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,

        [Parameter(Mandatory = $true)]
        [string]$TaskRoot,

        [Parameter(Mandatory = $true)]
        [string]$RepositoryRoot
    )

    $fullPath = [IO.Path]::GetFullPath($Path)
    $fullTaskRoot = [IO.Path]::GetFullPath($TaskRoot)
    $fullRepositoryRoot = [IO.Path]::GetFullPath($RepositoryRoot)
    if (-not (Test-PathWithin -Path $fullPath -Parent $fullTaskRoot) -or
        $fullPath -eq $fullTaskRoot) {
        throw "Task path must be a child of the task-owned root: $fullPath"
    }
    if ((Test-PathWithin -Path $fullPath -Parent $fullRepositoryRoot) -or
        (Test-PathWithin -Path $fullTaskRoot -Parent $fullRepositoryRoot) -or
        (Test-PathWithin -Path $fullRepositoryRoot -Parent $fullTaskRoot)) {
        throw "Task path must stay outside the repository and tracked output: $fullPath"
    }
    if ($fullPath -eq [IO.Path]::GetPathRoot($fullPath)) {
        throw "Filesystem root is forbidden: $fullPath"
    }
    Assert-NoReparseAncestors -Path $fullPath
}

function Assert-SafeGeneratedOutputPath {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,

        [Parameter(Mandatory = $true)]
        [string]$ExpectedOutputRoot,

        [Parameter(Mandatory = $true)]
        [string]$RepositoryRoot
    )

    $fullPath = [IO.Path]::GetFullPath($Path)
    $fullExpected = [IO.Path]::GetFullPath($ExpectedOutputRoot)
    if ($fullPath -cne $fullExpected) {
        throw "Generated output must use the one repository-owned ignored path: $fullExpected"
    }
    if (-not (Test-PathWithin -Path $fullPath -Parent $RepositoryRoot)) {
        throw "Generated output is not inside the repository."
    }
    Assert-NoReparseAncestors -Path $fullPath
}

function Assert-PinnedExecutable {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ActualPath,

        [Parameter(Mandatory = $true)]
        [string]$ExpectedPath,

        [Parameter(Mandatory = $true)]
        [string]$Label
    )

    $actual = [IO.Path]::GetFullPath($ActualPath)
    $expected = [IO.Path]::GetFullPath($ExpectedPath)
    $comparison = if ($IsWindows) { [StringComparison]::OrdinalIgnoreCase } else { [StringComparison]::Ordinal }
    if (-not $actual.Equals($expected, $comparison)) {
        throw "$Label executable is not the verified pinned executable. Expected $expected, got $actual."
    }
}

function Get-DeterministicModuleReplacePath {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ModuleDirectory,

        [Parameter(Mandatory = $true)]
        [string]$DependencyDirectory
    )

    $relativePath = [IO.Path]::GetRelativePath(
        [IO.Path]::GetFullPath($ModuleDirectory),
        [IO.Path]::GetFullPath($DependencyDirectory)
    ).Replace('\', '/')
    if ([string]::IsNullOrWhiteSpace($relativePath) -or
        [IO.Path]::IsPathRooted($relativePath) -or
        $relativePath -match '^[A-Za-z]:') {
        throw "The local Go module replacement must be a deterministic relative path."
    }
    return $relativePath
}

function Assert-ExactStringSet {
    param(
        [Parameter(Mandatory = $true)]
        [AllowEmptyCollection()]
        [object[]]$Actual,

        [Parameter(Mandatory = $true)]
        [AllowEmptyCollection()]
        [object[]]$Expected,

        [Parameter(Mandatory = $true)]
        [string]$Label
    )

    $actualSorted = @($Actual | ForEach-Object { [string]$_ } | Sort-Object -CaseSensitive -Unique)
    $expectedSorted = @($Expected | ForEach-Object { [string]$_ } | Sort-Object -CaseSensitive -Unique)
    if (($actualSorted -join "`n") -cne ($expectedSorted -join "`n")) {
        throw "$Label mismatch. Expected [$($expectedSorted -join ', ')], got [$($actualSorted -join ', ')]."
    }
}

function Assert-ElfContract {
    param(
        [Parameter(Mandatory = $true)]
        [Collections.IDictionary]$ElfInfo,

        [Parameter(Mandatory = $true)]
        [string]$ExpectedArchitecture,

        [Parameter(Mandatory = $true)]
        [int]$ExpectedAndroidApi
    )

    foreach ($field in @("architecture", "androidApi", "soname", "cExports", "jniSymbols")) {
        if (-not $ElfInfo.Contains($field)) {
            throw "ELF contract is missing '$field'."
        }
    }
    if ([string]$ElfInfo.architecture -cne $ExpectedArchitecture) {
        throw "ELF architecture mismatch."
    }
    if ([int]$ElfInfo.androidApi -ne $ExpectedAndroidApi) {
        throw "ELF Android API mismatch."
    }
    if ([string]$ElfInfo.soname -cne "libwg-go.so") {
        throw "ELF SONAME mismatch."
    }
    Assert-ExactStringSet -Actual @($ElfInfo.cExports) -Expected $script:RequiredCExports -Label "ELF C exports"
    Assert-ExactStringSet -Actual @($ElfInfo.jniSymbols) -Expected $script:RequiredJniSymbols -Label "ELF JNI symbols"
}

function Assert-OutputHashContract {
    param(
        [Parameter(Mandatory = $true)]
        [Collections.IDictionary]$Expected,

        [Parameter(Mandatory = $true)]
        [Collections.IDictionary]$Actual
    )

    Assert-ExactStringSet -Actual @($Actual.Keys) -Expected @($Expected.Keys) -Label "output hash paths"
    foreach ($key in $Expected.Keys) {
        if ([string]$Actual[$key] -cne [string]$Expected[$key]) {
            throw "Output SHA-256 mismatch for $key."
        }
    }
}

function Assert-AarContractFixture {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Entries,

        [Parameter(Mandatory = $true)]
        [string[]]$ClassNames,

        [Parameter(Mandatory = $true)]
        [string]$ManifestXml
    )

    $seenEntries = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($entry in $Entries) {
        if (-not $seenEntries.Add($entry)) {
            throw "AAR contains a duplicate or case-colliding entry: $entry"
        }
    }

    $expectedNative = @($script:RequiredAbis | ForEach-Object { "jni/$_/libwg-go.so" })
    $actualNative = @($Entries | Where-Object { $_ -match '\.so$' })
    Assert-ExactStringSet -Actual $actualNative -Expected $expectedNative -Label "AAR native entries"
    Assert-ExactStringSet -Actual $ClassNames -Expected $script:RequiredClasses -Label "AAR classes"

    $allowedEntries = @(
        "AndroidManifest.xml",
        "classes.jar",
        "R.txt",
        "proguard.txt",
        "META-INF/com/android/build/gradle/aar-metadata.properties",
        "jni/"
    ) + @($script:RequiredAbis | ForEach-Object { "jni/$_/" }) + $expectedNative
    $unexpectedEntries = @($Entries | Where-Object { $_ -notin $allowedEntries })
    if ($unexpectedEntries.Count -ne 0) {
        throw "AAR contains unexpected payloads: $($unexpectedEntries -join ', ')"
    }
    if (@($Entries | Where-Object { $_ -match '(^|/)go/Seq\.class$' }).Count -ne 0 -or
        @($ClassNames | Where-Object { $_ -eq "go.Seq" }).Count -ne 0) {
        throw "AAR contains a duplicate go.Seq runtime."
    }

    [xml]$manifest = $ManifestXml
    $components = @($manifest.SelectNodes("//*[local-name()='service' or local-name()='activity' or local-name()='provider' or local-name()='receiver']"))
    if ($components.Count -ne 0) {
        throw "AAR manifest declares forbidden Android components."
    }
}

function Assert-AmneziaWgAarContract {
    param(
        [Parameter(Mandatory = $true)]
        [string]$AarPath
    )

    if (-not (Test-Path -LiteralPath $AarPath -PathType Leaf)) {
        throw "AAR does not exist: $AarPath"
    }
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $aar = [IO.Compression.ZipFile]::OpenRead([IO.Path]::GetFullPath($AarPath))
    try {
        $entries = @($aar.Entries | ForEach-Object { $_.FullName })
        $manifestEntry = $aar.GetEntry("AndroidManifest.xml")
        $classesEntry = $aar.GetEntry("classes.jar")
        if ($null -eq $manifestEntry -or $null -eq $classesEntry) {
            throw "AAR is missing AndroidManifest.xml or classes.jar."
        }
        $manifestReader = [IO.StreamReader]::new($manifestEntry.Open())
        try {
            $manifestXml = $manifestReader.ReadToEnd()
        } finally {
            $manifestReader.Dispose()
        }

        $memory = [IO.MemoryStream]::new()
        try {
            $classesStream = $classesEntry.Open()
            try {
                $classesStream.CopyTo($memory)
            } finally {
                $classesStream.Dispose()
            }
            $memory.Position = 0
            $jar = [IO.Compression.ZipArchive]::new($memory, [IO.Compression.ZipArchiveMode]::Read, $true)
            try {
                $classNames = @(
                    $jar.Entries |
                        Where-Object { $_.FullName.EndsWith(".class", [StringComparison]::Ordinal) } |
                        ForEach-Object {
                            $_.FullName.Substring(0, $_.FullName.Length - 6).Replace("/", ".")
                        }
                )
            } finally {
                $jar.Dispose()
            }
        } finally {
            $memory.Dispose()
        }
        Assert-AarContractFixture -Entries $entries -ClassNames $classNames -ManifestXml $manifestXml
    } finally {
        $aar.Dispose()
    }
}
