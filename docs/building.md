# Building Lean

Lean builds through the committed Gradle wrapper. Do not substitute a system Gradle installation: wrapper validation and its distribution checksum are part of the build trust chain.

## Android application

Required tools:

- JDK 17
- Android SDK Platform 35
- Android SDK Build-Tools 35
- Gradle 8.11.1 through `gradlew` or `gradlew.bat`

Set `JAVA_HOME` to a JDK 17 installation and set either `ANDROID_HOME` or `sdk.dir` in the untracked `local.properties`.

On Windows:

```powershell
.\gradlew.bat testDebugUnitTest --stacktrace
.\gradlew.bat assembleDebug --stacktrace
```

On Linux or macOS:

```bash
./gradlew testDebugUnitTest --stacktrace
./gradlew assembleDebug --stacktrace
```

Release signing uses these environment variables:

- `ANDROID_KEYSTORE_FILE`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`

The values must remain outside the repository and build logs.

## Native cores

The Neko sing-box core uses Go 1.23.6 and the Android NDK revision recorded in `native/versions.lock`. The AmneziaWG core uses Go 1.24.4, Android NDK 26.1.10909125, and CMake 3.22.1. Each native build script verifies its pinned source revision before producing an AAR.

Release builds must include `native/versions.lock` and `THIRD_PARTY_NOTICES.md` beside the APKs so every native binary can be matched to its source and license.

## Product flavours

The distribution flavour controls bundled native helpers and default settings.

| Flavour | Native helpers | Defaults |
|---|---|---|
| `full` | Includes NaiveProxy; helper downloads are pinned in `native/versions.lock` | Project promotion and automatic update checks enabled |
| `foss` | Omits NaiveProxy; build Mieru, Xray and olcRTC from the pinned sources | Project promotion and automatic update checks disabled |

NaiveProxy is packaged from `app/src/full/jniLibs/`. Other helpers are packaged
from `app/src/main/jniLibs/`; prepare them with `native/build-linux.sh plugins`
for a source build. `NativePlugin.binary()` returns null when the requested
helper is absent, and the server row reports that it is unavailable.

The manual update check remains available in both flavours.

```bash
./gradlew assembleFullRelease
./gradlew assembleFossRelease
```

## Native cores from source, on Linux

`native/build-linux.sh` builds both cores from the pinned sources. Every archive
and toolchain download is verified against the sha256 in
`native/versions.lock`, and the script stops rather than continuing on a
mismatch.

```bash
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/26.1.10909125
./native/build-linux.sh          # cores and helpers
./native/build-linux.sh neko     # libcore.aar
./native/build-linux.sh awg      # libwg-go.so
./native/build-linux.sh plugins  # mieru, xray, olcrtc
```

It needs `sh`, `curl`, `tar`, `patch`, `git` and `python3`. Go is downloaded at
the pinned version rather than taken from the system.

The Windows equivalents are `native/neko/build.ps1` and
`native/amneziawg/build.ps1`; they do the same work with the same pins.
