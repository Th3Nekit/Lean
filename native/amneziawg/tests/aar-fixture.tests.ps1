[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\..\.."))
$policyPath = Join-Path $repoRoot "native\amneziawg\lib\NativeBuildPolicy.ps1"
. $policyPath

function Assert-Throws {
    param([scriptblock]$Action, [string]$Because)
    try {
        & $Action
    } catch {
        return
    }
    throw "Expected failure: $Because"
}

$validEntries = @(
    "AndroidManifest.xml",
    "classes.jar",
    "jni/",
    "jni/armeabi-v7a/",
    "jni/armeabi-v7a/libwg-go.so",
    "jni/arm64-v8a/",
    "jni/arm64-v8a/libwg-go.so",
    "jni/x86/",
    "jni/x86/libwg-go.so",
    "jni/x86_64/",
    "jni/x86_64/libwg-go.so",
    "META-INF/com/android/build/gradle/aar-metadata.properties"
)
$validClasses = @(
    "org.amnezia.awg.GoBackend",
    "com.th3web.lean.awg.AmneziaWgNative",
    "com.th3web.lean.awg.JniAmneziaWgNative"
)
$emptyManifest = '<manifest xmlns:android="http://schemas.android.com/apk/res/android" />'

Assert-AarContractFixture -Entries $validEntries -ClassNames $validClasses -ManifestXml $emptyManifest

foreach ($invalidEntries in @(
    @($validEntries | Where-Object { $_ -notlike "jni/x86/*" }),
    @($validEntries + "jni/riscv64/libwg-go.so"),
    @($validEntries + "jni/arm64-v8a/libcore.so"),
    @($validEntries + "res/values/strings.xml"),
    @($validEntries + "libs/foreign-runtime.jar")
)) {
    Assert-Throws {
        Assert-AarContractFixture -Entries $invalidEntries -ClassNames $validClasses -ManifestXml $emptyManifest
    } "the AAR must contain exactly four ABI libraries and no unexpected native/resource payload"
}

foreach ($invalidClasses in @(
    @($validClasses + "go.Seq"),
    @($validClasses + "org.amnezia.awg.backend.GoBackend"),
    @($validClasses + "com.th3web.lean.awg.Unexpected")
)) {
    Assert-Throws {
        Assert-AarContractFixture -Entries $validEntries -ClassNames $invalidClasses -ManifestXml $emptyManifest
    } "unexpected AAR classes and a duplicate go.Seq runtime must be rejected"
}

foreach ($component in @("service", "activity", "provider", "receiver")) {
    $manifest = "<manifest xmlns:android=`"http://schemas.android.com/apk/res/android`"><application><$component android:name=`".Forbidden`" /></application></manifest>"
    Assert-Throws {
        Assert-AarContractFixture -Entries $validEntries -ClassNames $validClasses -ManifestXml $manifest
    } "library manifest component '$component' must be rejected"
}

Write-Host "AmneziaWG AAR fixture tests passed."
