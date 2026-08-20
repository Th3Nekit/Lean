[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ClassesJar,
    [Parameter(Mandatory = $true)]
    [string]$JavapPath
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

if (-not (Test-Path -LiteralPath $ClassesJar -PathType Leaf)) {
    throw "classes.jar does not exist: $ClassesJar"
}
if (-not (Test-Path -LiteralPath $JavapPath -PathType Leaf)) {
    throw "javap does not exist: $JavapPath"
}

$output = (& $JavapPath -classpath $ClassesJar -s -p org.amnezia.awg.GoBackend 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0) {
    throw "javap failed for org.amnezia.awg.GoBackend.`n$output"
}

$expected = [ordered]@{
    "awgTurnOn" = "(Ljava/lang/String;ILjava/lang/String;)I"
    "awgTurnOff" = "(I)V"
    "awgGetSocketV4" = "(I)I"
    "awgGetSocketV6" = "(I)I"
    "awgGetConfig" = "(I)Ljava/lang/String;"
    "awgVersion" = "()Ljava/lang/String;"
}
$actual = [ordered]@{}
$lines = @($output -split "`r?`n")
for ($index = 0; $index -lt $lines.Count; $index++) {
    if ($lines[$index] -match '^\s*public static native .+ (awg[A-Za-z0-9]+)\(.*\);\s*$') {
        $name = $Matches[1]
        if ($actual.Contains($name)) {
            throw "javap reported duplicate native method: $name"
        }
        if ($index + 1 -ge $lines.Count -or
            $lines[$index + 1] -notmatch '^\s*descriptor:\s*(\S+)\s*$') {
            throw "javap did not report a descriptor for $name."
        }
        $actual[$name] = $Matches[1]
    }
}

$actualNames = @($actual.Keys | Sort-Object -CaseSensitive)
$expectedNames = @($expected.Keys | Sort-Object -CaseSensitive)
if (($actualNames -join "`n") -cne ($expectedNames -join "`n")) {
    throw "JNI native method names are not exact. Expected [$($expectedNames -join ', ')], got [$($actualNames -join ', ')]."
}
foreach ($name in $expected.Keys) {
    if ($actual[$name] -cne $expected[$name]) {
        throw "JNI descriptor mismatch for $name. Expected $($expected[$name]), got $($actual[$name])."
    }
}

Write-Host "AmneziaWG JNI javap contract tests passed."
