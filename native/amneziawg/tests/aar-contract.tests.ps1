[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$AarPath
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\..\.."))
$policyPath = Join-Path $repoRoot "native\amneziawg\lib\NativeBuildPolicy.ps1"
. $policyPath

Assert-AmneziaWgAarContract -AarPath $AarPath
Write-Host "AmneziaWG AAR contract tests passed."
