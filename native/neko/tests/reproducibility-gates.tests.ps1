$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "../../..")).Path
$buildScript = Get-Content -LiteralPath (Join-Path $repoRoot "native/neko/build.ps1") -Raw
$aarContract = Get-Content -LiteralPath (Join-Path $repoRoot "native/neko/tests/aar-contract.tests.ps1") -Raw
$workflow = Get-Content -LiteralPath (Join-Path $repoRoot ".github/workflows/android.yml") -Raw

$failures = [System.Collections.Generic.List[string]]::new()

if (
    $buildScript -notmatch 'platforms/android-\$CompileSdk/android\.jar' -or
    $buildScript -notmatch '"-bootclasspath"'
) {
    $failures.Add("compile-sdk: gomobile must receive the pinned Android 35 android.jar through -bootclasspath")
}

if (
    $buildScript -match '(?im)(?:Invoke-Checked\s+git|&\s+git|git\s+-C)[^\r\n]*\b(?:fetch|clone|checkout)\b' -or
    $buildScript -match '\bCheckout-VerifiedComponent\b' -or
    $buildScript -notmatch '\bExpand-VerifiedComponentArchive\b'
) {
    $failures.Add("source-provenance: compilation must use the SHA-verified codeload archive, without a second git fetch/checkout")
}

$requiredEnvironmentTokens = @(
    '$env:GOENV = "off"',
    '$env:GOWORK = "off"',
    '$env:GOBIN = $GoBin',
    '$env:GOEXPERIMENT = ""',
    '$env:CC = ""',
    '$env:CXX = ""',
    '$env:CGO_CFLAGS = ""',
    '$env:CGO_CPPFLAGS = ""',
    '$env:CGO_CXXFLAGS = ""',
    '$env:CGO_LDFLAGS = ""',
    '$env:GO386 = "softfloat"',
    '$env:GOAMD64 = "v1"'
)

foreach ($token in $requiredEnvironmentTokens) {
    if (-not $buildScript.Contains($token)) {
        $failures.Add("go-environment: missing deterministic assignment $token")
    }
}
if ($buildScript -notmatch 'StartsWith\("CGO_"') {
    $failures.Add("go-environment: all inherited CGO_* variables must be cleared before locked values are assigned")
}

if (
    $buildScript -notmatch '\bAssert-JniElfContract\b' -or
    $aarContract -notmatch '\bAssert-JniElfContract\b' -or
    $aarContract -notmatch 'expectedMachine'
) {
    $failures.Add("elf-contract: build and independent AAR gate must validate ELF class/machine for all four ABIs")
}

if ($workflow -notmatch 'aar-contract\.tests\.ps1') {
    $failures.Add("elf-contract: CI must execute the independent PowerShell AAR/ELF contract gate")
}

if ($failures.Count -gt 0) {
    throw "Reproducibility gates failed:`n - $($failures -join "`n - ")"
}

Write-Host "Neko reproducibility gates passed."
