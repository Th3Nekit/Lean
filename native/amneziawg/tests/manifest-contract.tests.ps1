[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$LibraryManifest,

    [Parameter(Mandatory = $true)]
    [string]$DebugManifest,

    [Parameter(Mandatory = $true)]
    [string]$ReleaseManifest
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$androidNamespace = "http://schemas.android.com/apk/res/android"
$componentNames = @("activity", "activity-alias", "service", "receiver", "provider")

function Read-Manifest {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Manifest does not exist: $Path"
    }
    [xml]$manifest = Get-Content -LiteralPath $Path -Raw
    return $manifest
}

function Get-AndroidName {
    param(
        [Parameter(Mandatory = $true)]
        [System.Xml.XmlElement]$Element
    )

    return $Element.GetAttribute("name", $androidNamespace)
}

function Resolve-ComponentName {
    param(
        [Parameter(Mandatory = $true)]
        [string]$PackageName,

        [Parameter(Mandatory = $true)]
        [string]$Name
    )

    if ($Name.StartsWith(".", [StringComparison]::Ordinal)) {
        return "$PackageName$Name"
    }
    if ($Name.IndexOf(".") -lt 0) {
        return "$PackageName.$Name"
    }
    return $Name
}

$library = Read-Manifest -Path $LibraryManifest
foreach ($componentName in $componentNames) {
    $components = @($library.SelectNodes("//*[local-name()='$componentName']"))
    if ($components.Count -ne 0) {
        throw "The AmneziaWG library manifest declares forbidden $componentName components."
    }
}

foreach ($manifestCase in @(
    [ordered]@{ label = "debug"; path = $DebugManifest },
    [ordered]@{ label = "release"; path = $ReleaseManifest }
)) {
    $manifest = Read-Manifest -Path $manifestCase.path
    $packageName = [string]$manifest.manifest.package
    if ([string]::IsNullOrWhiteSpace($packageName)) {
        throw "The $($manifestCase.label) merged manifest has no package name."
    }

    $vpnServices = @()
    foreach ($service in @($manifest.SelectNodes("//*[local-name()='service']"))) {
        $permission = $service.GetAttribute("permission", $androidNamespace)
        $hasVpnAction = $false
        foreach ($action in @($service.SelectNodes("./*[local-name()='intent-filter']/*[local-name()='action']"))) {
            if ($action.GetAttribute("name", $androidNamespace) -ceq "android.net.VpnService") {
                $hasVpnAction = $true
            }
        }
        if ($permission -ceq "android.permission.BIND_VPN_SERVICE" -or $hasVpnAction) {
            $vpnServices += $service
        }
    }

    if ($vpnServices.Count -ne 1) {
        throw "The $($manifestCase.label) merged manifest must contain exactly one VPN service; found $($vpnServices.Count)."
    }
    $resolvedName = Resolve-ComponentName `
        -PackageName $packageName `
        -Name (Get-AndroidName -Element $vpnServices[0])
    if ($resolvedName -cne "com.th3web.lean.core.LeanVpnService") {
        throw "The $($manifestCase.label) VPN service is unexpected: $resolvedName"
    }

    foreach ($componentName in $componentNames) {
        foreach ($component in @($manifest.SelectNodes("//*[local-name()='$componentName']"))) {
            $componentNameValue = Get-AndroidName -Element $component
            if ($componentNameValue.StartsWith("org.amnezia.", [StringComparison]::Ordinal)) {
                throw "The $($manifestCase.label) merged manifest contains an official Amnezia component: $componentNameValue"
            }
        }
    }
}

Write-Host "AmneziaWG manifest contract tests passed."
