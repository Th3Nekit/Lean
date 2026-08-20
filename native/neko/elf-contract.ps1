$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Get-ElfUInt16 {
    param(
        [byte[]]$Bytes,
        [int]$Offset
    )

    if ($Offset -lt 0 -or $Offset + 2 -gt $Bytes.Length) {
        throw "ELF header is truncated at offset $Offset."
    }
    return [uint16](
        [uint16]$Bytes[$Offset] -bor
        ([uint16]$Bytes[$Offset + 1] -shl 8)
    )
}

function Assert-JniElfContract {
    param(
        [Parameter(Mandatory)]
        [byte[]]$Bytes,
        [Parameter(Mandatory)]
        [string]$Abi,
        [string]$EntryName = "libgojni.so"
    )

    $contracts = @{
        "armeabi-v7a" = @{ expectedClass = 1; expectedMachine = 40 }
        "arm64-v8a" = @{ expectedClass = 2; expectedMachine = 183 }
        "x86" = @{ expectedClass = 1; expectedMachine = 3 }
        "x86_64" = @{ expectedClass = 2; expectedMachine = 62 }
    }
    if (-not $contracts.ContainsKey($Abi)) {
        throw "No locked ELF contract exists for ABI '$Abi'."
    }
    if ($Bytes.Length -lt 20) {
        throw "$EntryName for $Abi is empty or shorter than an ELF header."
    }
    if (
        $Bytes[0] -ne 0x7f -or
        $Bytes[1] -ne 0x45 -or
        $Bytes[2] -ne 0x4c -or
        $Bytes[3] -ne 0x46
    ) {
        throw "$EntryName for $Abi does not have ELF magic."
    }
    if ($Bytes[5] -ne 1) {
        throw "$EntryName for $Abi must be little-endian ELF."
    }

    $contract = $contracts[$Abi]
    $actualClass = [int]$Bytes[4]
    $expectedClass = [int]$contract.expectedClass
    if ($actualClass -ne $expectedClass) {
        throw "$EntryName for $Abi has ELF class $actualClass; expected $expectedClass."
    }

    $actualType = [int](Get-ElfUInt16 $Bytes 16)
    if ($actualType -ne 3) {
        throw "$EntryName for $Abi has ELF type $actualType; expected ET_DYN (3)."
    }

    $actualMachine = [int](Get-ElfUInt16 $Bytes 18)
    $expectedMachine = [int]$contract.expectedMachine
    if ($actualMachine -ne $expectedMachine) {
        throw "$EntryName for $Abi has ELF machine $actualMachine; expected $expectedMachine."
    }
}
