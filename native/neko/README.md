# Lean Neko native core

Lean's standard-protocol runtime is built from the NekoBox 1.4.2 source stack.
`native/versions.lock` is the authoritative machine-readable source and
toolchain lock. The build uses detached commits, verifies the corresponding
codeload archive bytes, and keeps Go's path-sensitive sibling layout:

```text
<work>/sources/nekobox-1.4.2/libcore
<work>/sources/sing-box
<work>/sources/libneko
<work>/sources/gomobile
```

## Build

Install JDK 17, Android SDK platforms 21 and 35, and Android NDK
25.0.8775105. The script downloads the platform-specific Go 1.23.6 and
1.24.4 archives named in the lock, verifies their SHA-256 values, extracts
fresh isolated toolchains, and invokes those binaries with
`GOTOOLCHAIN=local`. The pinned Matsuri gomobile revision declares `go
1.24.0`, so its bootstrap binaries are compiled with exact Go 1.24.4.
Neko/libcore binding and the validator use exact Go 1.23.6.

Before binding, the build applies the locked
`neko-dns-success-completion.patch` to the pinned Neko source. The patch closes
the lookup wait after `ExchangeContext.Success` stores the resolved addresses,
matching the completion behavior of the raw and error callbacks. Patch
integrity is locked by normalized SHA-256, `git apply --check` must match the
expected source context, and a focused Go regression test must pass before
gomobile runs.

The script intentionally does not run `gomobile init`. At the pinned revision
that command unconditionally installs `golang.org/x/mobile/cmd/gobind@latest`,
which is mutable and no longer builds with the locked core toolchain. Instead,
the script installs the pinned Matsuri `gobind`, creates a clean gomobile
state directory, and lets `gomobile bind` perform the same Android environment
initialization. OpenAL is not requested by this build.

Set `ANDROID_HOME` or `ANDROID_SDK_ROOT`; the script locates the locked NDK
below that SDK, or accepts an exact `ANDROID_NDK_HOME`. Host `go` binaries and
toolchain override variables are deliberately ignored.

PowerShell 7 is used on Windows and Linux:

```powershell
pwsh -NoProfile -File native/neko/build.ps1
```

The script supports Windows natively and does not invoke Neko's Linux-specific
environment shell scripts. It reproduces their effective gomobile command with
the Windows or Linux NDK host selected by gomobile. Sources, `GOPATH`,
`GOMODCACHE`, and `GOCACHE` are isolated below the validated native work root.
Use `-ValidateOnly` to validate the lock and path policy without downloading or
building, or `-VerifySourcesOnly` to verify every archive, detached revision,
and locked Go module file without requiring the native toolchain. CI can pass a
cacheable root explicitly:

```powershell
pwsh -NoProfile -File native/neko/build.ps1 `
  -NativeWorkRoot "$env:RUNNER_TEMP/lean-native" `
  -ValidatorOutputPath "$pwd/artifacts/sing-box"
```

The successful build writes only `app/libs/libcore.aar` to the application
library directory. The AAR check requires API 21 metadata,
the exact bridge signatures listed in `native/neko/aar-contract.txt`, and
`libgojni.so` for `armeabi-v7a`, `arm64-v8a`, `x86`, and `x86_64`.
Validator output is accepted only at the ignored, untracked
`artifacts/sing-box` path.

## Deliberate migration staging

Task 6 does not change `app/build.gradle.kts` from `libbox.aar` to
`libcore.aar`. Lean's Kotlin sources still import
`io.nekohasekai.libbox.*`; Neko exposes `libcore.*`. Switching the binary
before the Task 7–8 TUN and engine adapters land would make the application
unbuildable. The dependency switch and API adaptation must be one atomic
change. The ignored local `app/libs/libbox.aar` is therefore left untouched.

The native CI job builds and inspects the locked Neko AAR and Matsuri
sing-box validator. Android assembly is intentionally restored after Tasks
7–8; this stage must not be interpreted as a passing application build.

## Source correspondence and release blocker

`THIRD_PARTY_NOTICES.md`, the lock file, and the exact license files under
`native/licenses` must accompany release artifacts. The pinned libneko
repository has no `LICENSE`, `COPYING`, or `NOTICE` file. No license has been
inferred for it. Shipping the Neko AAR remains blocked until authoritative
libneko licensing and the linked Go dependency notice set are resolved.
