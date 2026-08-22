#!/bin/sh
# Builds the native cores from source on Linux.
#
# Written for build servers that will not run PowerShell: F-Droid's, and any
# contributor's. It is a straight-line port of what native/neko/build.ps1 and
# native/amneziawg/build.ps1 do, minus the Windows-specific plumbing.
#
# Every source archive and toolchain download is pinned by sha256 in
# native/versions.lock and verified before use; the script refuses to proceed on
# a mismatch, so a substituted upstream tarball stops the build rather than
# reaching the APK.
#
#   ./native/build-linux.sh            # everything below
#   ./native/build-linux.sh neko       # libcore.aar
#   ./native/build-linux.sh awg        # libwg-go.so
#   ./native/build-linux.sh plugins    # mieru, xray, olcrtc
#
# Needs: sh, curl, tar, unzip, git, python3 (reads the lock file), and an
# Android NDK. Point ANDROID_NDK_HOME at it, or let the script find it under
# ANDROID_HOME/ndk/<version from the lock>.
#
# NaiveProxy is the one helper this cannot build. It is a Chromium fork, not a
# Go program: building it needs depot_tools, tens of gigabytes and hours, which
# no ordinary build server will spend. The `foss` flavour therefore ships without
# NaiveProxy and with everything else, and the app reports that one protocol as
# unavailable per server.

set -eu

here=$(CD=$(dirname "$0") && cd "$CD" && pwd)
root=$(cd "$here/.." && pwd)
lock="$here/versions.lock"
work="${LEAN_NATIVE_WORK:-$root/.native-work}"
target="${1:-all}"

log() { printf '\n== %s\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

command -v python3 >/dev/null 2>&1 || die "python3 is required to read versions.lock"
command -v curl >/dev/null 2>&1 || die "curl is required"

# Reads one value out of the lock file. Each argument is ONE key, never a dotted
# path: a Go version is itself a key ("1.23.6") and splitting on dots would look
# for a key named "1".
pin() {
    python3 - "$lock" "$@" <<'PY'
import json, sys
node = json.load(open(sys.argv[1]))
for key in sys.argv[2:]:
    node = node[key]
print(node)
PY
}

# The helpers below prefix their variables with an underscore because POSIX sh has
# no `local`: plain `name=$1` inside unpack() overwrites the caller's `name`, and the
# plugin loop then went looking for a lock entry called "mieru-src".
sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | cut -d' ' -f1
    else
        shasum -a 256 "$1" | cut -d' ' -f1
    fi
}

# Downloads to $2 and refuses to continue unless the digest matches $3.
fetch() {
    _url=$1; _out=$2; _want=$3
    if [ -f "$_out" ] && [ "$(sha256_of "$_out")" = "$_want" ]; then
        return 0
    fi
    printf '   fetching %s\n' "$(basename "$_out")"
    curl -fsSL --retry 3 -o "$_out" "$_url"
    _got=$(sha256_of "$_out")
    [ "$_got" = "$_want" ] || die "sha256 mismatch for $_url
  expected $_want
  got      $_got"
}

mkdir -p "$work/downloads" "$work/src" "$work/toolchain"

# ---------------------------------------------------------------- toolchain --

install_go() {
    _version=$1
    _dest="$work/toolchain/go$_version"
    if [ -x "$_dest/go/bin/go" ]; then
        GOROOT="$_dest/go"
        return 0
    fi
    _file=$(pin toolchain goDistributions "$_version" linux-amd64 file)
    _url=$(pin toolchain goDistributions "$_version" linux-amd64 url)
    _want=$(pin toolchain goDistributions "$_version" linux-amd64 sha256)
    fetch "$_url" "$work/downloads/$_file" "$_want"
    mkdir -p "$_dest"
    tar -xzf "$work/downloads/$_file" -C "$_dest"
    GOROOT="$_dest/go"
}

# The build needs TWO different NDKs: the core is built against the one the pinned
# gomobile expects, AmneziaWG against its own. A build server that provides exactly one
# (F-Droid names a single `ndk:` in the recipe) is therefore not enough, so a missing one
# is installed through sdkmanager, which verifies Google's own checksums.
#
# ANDROID_NDK_HOME is honoured only when it IS the version being asked for; otherwise a
# machine with one NDK exported would silently build the other half against the wrong one.
find_ndk() {
    _wanted=$1
    if [ -n "${ANDROID_NDK_HOME:-}" ] && [ -d "$ANDROID_NDK_HOME" ]; then
        case "$ANDROID_NDK_HOME" in
            *"$_wanted"*) printf '%s' "$ANDROID_NDK_HOME"; return 0 ;;
        esac
    fi
    for base in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Android/Sdk"; do
        [ -n "$base" ] || continue
        if [ -d "$base/ndk/$_wanted" ]; then
            printf '%s' "$base/ndk/$_wanted"
            return 0
        fi
    done
    install_ndk "$_wanted"
}

# Prints the path to sdkmanager, or nothing when the SDK has none.
find_sdkmanager() {
    _sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
    [ -n "$_sdk" ] || return 1
    for _candidate in \
        "$_sdk/cmdline-tools/latest/bin/sdkmanager" \
        "$_sdk/cmdline-tools/bin/sdkmanager" \
        "$_sdk/tools/bin/sdkmanager"
    do
        [ -x "$_candidate" ] && printf '%s' "$_candidate" && return 0
    done
    return 1
}

# gomobile needs an android.jar to compile against, and a build server carries only
# the platforms its own recipes asked for: F-Droid's had no android-35. Installed the
# same way as the NDK, through sdkmanager, which checks Google's own signatures.
ensure_platform() {
    _api=$1
    _sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
    _jar="$_sdk/platforms/android-$_api/android.jar"
    [ -f "$_jar" ] && { printf '%s' "$_jar"; return 0; }

    # Ask for it, and let sdkmanager say why if it refuses: the reason used to go to
    # /dev/null, which left "could not install" as the only thing in the log.
    if _manager=$(find_sdkmanager); then
        echo "   installing platform android-$_api" >&2
        yes 2>/dev/null | "$_manager" --sdk_root="$_sdk" "platforms;android-$_api" >&2 || true
    fi
    [ -f "$_jar" ] && { printf '%s' "$_jar"; return 0; }

    # Still nothing. The bindings gobind emits are ordinary Java and compile against
    # any recent platform, so the newest one already on the machine will do rather
    # than failing a build over a download the server would not make.
    _have=$(ls -1 "$_sdk/platforms" 2>/dev/null |
        sed -n 's/^android-\([0-9][0-9]*\)$/\1/p' | sort -n | tail -1)
    [ -n "$_have" ] || die "no Android platform under $_sdk/platforms and android-$_api could not be installed"
    echo "   android-$_api is unavailable; using android-$_have instead" >&2
    printf '%s' "$_sdk/platforms/android-$_have/android.jar"
}

install_ndk() {
    _wanted=$1
    sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
    [ -n "$sdk" ] || die "NDK $_wanted is missing and ANDROID_HOME is not set to install it"
    manager=$(find_sdkmanager) ||
        die "NDK $_wanted is missing and sdkmanager was not found under $sdk"
    echo "   installing NDK $_wanted" >&2
    yes 2>/dev/null | "$manager" --sdk_root="$sdk" "ndk;$_wanted" >/dev/null 2>&1 || true
    [ -d "$sdk/ndk/$_wanted" ] || die "could not install NDK $_wanted"
    printf '%s' "$sdk/ndk/$_wanted"
}

# Unpacks a pinned source archive into $work/src/<name>, stripping the
# single directory GitHub wraps its tarballs in.
unpack() {
    _name=$1; _url=$2; _want=$3
    _dest="$work/src/$_name"
    [ -d "$_dest" ] && return 0
    fetch "$_url" "$work/downloads/$_name.tar.gz" "$_want"
    mkdir -p "$_dest"
    tar -xzf "$work/downloads/$_name.tar.gz" -C "$_dest" --strip-components=1
}

# --------------------------------------------------------------------- neko --

build_neko() {
    log "libcore.aar (sing-box core)"
    core_go=$(pin toolchain coreGo)
    mobile_go=$(pin toolchain gomobileGo)
    api=$(pin toolchain androidApi)
    ndk_version=$(pin toolchain ndk)
    ndk=$(find_ndk "$ndk_version")

    unpack neko      "$(pin components neko archive)"     "$(pin components neko archiveSha256)"
    unpack sing-box  "$(pin components singBox archive)"  "$(pin components singBox archiveSha256)"
    unpack libneko   "$(pin components libneko archive)"  "$(pin components libneko archiveSha256)"
    unpack gomobile  "$(pin components gomobile archive)" "$(pin components gomobile archiveSha256)"

    log "applying pinned patches"
    for patch in "$here/neko/patches/"*.patch; do
        name=$(basename "$patch")
        marker="$work/src/neko/.applied-$name"
        [ -f "$marker" ] && continue
        ( cd "$work/src/neko" && patch -p1 --forward < "$patch" ) || die "failed to apply $name"
        : > "$marker"
    done
    cp "$here/neko/tests/dns_box_success_test.go" "$work/src/neko/libcore/" 2>/dev/null || true

    install_go "$mobile_go"
    mobile_root=$GOROOT
    install_go "$core_go"
    core_root=$GOROOT

    log "building the pinned gomobile"
    export PATH="$mobile_root/bin:$PATH"
    export GOROOT="$mobile_root"
    export GOPATH="$work/gopath"
    mkdir -p "$GOPATH/bin"
    ( cd "$work/src/gomobile" && go install ./cmd/gomobile ./cmd/gobind )

    log "gomobile bind"
    tags=$(python3 -c "import json;print(','.join(json.load(open('$lock'))['build']['tags']))")
    jar=$(ensure_platform "$(pin toolchain androidCompileSdk)")

    export GOROOT="$core_root"
    export PATH="$core_root/bin:$GOPATH/bin:$PATH"
    export ANDROID_NDK_HOME="$ndk"
    ( cd "$work/src/neko/libcore" \
        && "$GOPATH/bin/gomobile" bind -v \
             -androidapi "$api" \
             -bootclasspath "$jar" \
             -trimpath \
             -ldflags="-s -w" \
             -tags="$tags" \
             . )

    aar=$(find "$work/src/neko/libcore" -maxdepth 1 -name '*.aar' | head -n1)
    [ -n "$aar" ] || die "gomobile produced no .aar"
    mkdir -p "$root/app/libs"
    cp "$aar" "$root/app/libs/libcore.aar"
    printf '   -> app/libs/libcore.aar (%s)\n' "$(sha256_of "$root/app/libs/libcore.aar")"
}

# ---------------------------------------------------------------- amneziawg --

# Two archives, and only one of them is the thing being compiled. amneziawg-android
# carries the JNI glue at tunnel/tools/libwg-go — that directory holds api-android.go,
# jni.c and the go.mod, and it is what `go build` is pointed at. amneziawg-go is the
# tunnel module the glue depends on; it is pulled in by a local `replace` so the build
# uses the pinned copy on disk rather than whatever the proxy would serve.
build_awg() {
    log "libwg-go.so (AmneziaWG)"
    go_version=$(pin amneziawg goVersion)
    api=$(pin amneziawg androidApi)
    module=$(pin amneziawg goSource module)
    ndk=$(find_ndk "$(pin amneziawg ndkVersion)")

    unpack awg-android "$(pin amneziawg androidSource archive)" "$(pin amneziawg androidSource archiveSha256)"
    unpack awg-go      "$(pin amneziawg goSource archive)"      "$(pin amneziawg goSource archiveSha256)"

    glue="$work/src/awg-android/tunnel/tools/libwg-go"
    [ -f "$glue/api-android.go" ] || die "AmneziaWG glue is not where it should be: $glue"

    marker="$glue/.applied-direct-jni-only"
    if [ ! -f "$marker" ]; then
        ( cd "$glue" && patch -p1 --forward < "$here/amneziawg/patches/direct-jni-only.patch" ) \
            || die "failed to apply direct-jni-only.patch"
        : > "$marker"
    fi

    install_go "$go_version"
    export GOROOT PATH="$GOROOT/bin:$PATH"
    export ANDROID_NDK_HOME="$ndk"
    export GOTOOLCHAIN=local GOFLAGS=-mod=mod CGO_ENABLED=1 GOOS=android
    export SOURCE_DATE_EPOCH=0 TZ=UTC

    # Point the glue at the extracted tunnel module instead of the network.
    ( cd "$glue" && go mod edit "-replace=$module=$work/src/awg-go" ) \
        || die "could not redirect $module at the pinned source"

    llvm="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
    out="$root/native/amneziawg/generated/jni"
    for abi in $(python3 -c "import json;print(' '.join(json.load(open('$lock'))['amneziawg']['abis']))"); do
        case $abi in
            armeabi-v7a) goarch=arm;   goarm=7; target=armv7a-linux-androideabi ;;
            arm64-v8a)   goarch=arm64; goarm=;  target=aarch64-linux-android ;;
            x86)         goarch=386;   goarm=;  target=i686-linux-android ;;
            x86_64)      goarch=amd64; goarm=;  target=x86_64-linux-android ;;
            *) die "unknown ABI $abi" ;;
        esac
        cc="$llvm/${target}${api}-clang"
        [ -x "$cc" ] || die "NDK has no compiler for API $api on $abi: $cc"
        printf '   %s\n' "$abi"
        mkdir -p "$out/$abi"
        ( cd "$glue" && \
          GOARCH=$goarch GOARM=$goarm GOAMD64=v1 \
          CC="$cc" CXX="${cc%-clang}-clang++" \
          CGO_CFLAGS=-fPIC CGO_CXXFLAGS=-fPIC \
          CGO_LDFLAGS="-Wl,--build-id=none -Wl,-soname,libwg-go.so" \
            go build -tags=linux -trimpath -buildvcs=false -buildmode=c-shared \
                -ldflags=-buildid= -o "$out/$abi/libwg-go.so" . ) \
            || die "AmneziaWG build failed for $abi"
        # c-shared also writes a header next to the library; Gradle would package it.
        rm -f "$out/$abi/libwg-go.h"
    done
    printf '   -> native/amneziawg/generated/jni/<abi>/libwg-go.so\n'
}

# ------------------------------------------------------------------ plugins --

# Installs the Go a plugin pins for itself, which is not the version the cores
# use: Xray's go.mod already asks for a newer language version than gomobile
# builds against.
install_plugin_go() {
    _version=$1; _file=$2; _url=$3; _want=$4
    _dest="$work/toolchain/go$_version"
    if [ ! -x "$_dest/go/bin/go" ]; then
        fetch "$_url" "$work/downloads/$_file" "$_want"
        mkdir -p "$_dest"
        tar -xzf "$work/downloads/$_file" -C "$_dest"
    fi
    GOROOT="$_dest/go"
}

# One Go helper, cross-compiled for Android. CGO stays off: these are ordinary
# executables shipped as lib*.so, and a pure-Go binary needs no NDK toolchain
# and links nothing from the platform.
build_go_plugin() {
    name=$1
    printf '   %s\n' "$name"
    go_version=$(pin plugins "$name" source go version)
    install_plugin_go "$go_version" \
        "$(pin plugins "$name" source go linuxAmd64 file)" \
        "$(pin plugins "$name" source go linuxAmd64 url)" \
        "$(pin plugins "$name" source go linuxAmd64 sha256)"

    unpack "$name-src" "$(pin plugins "$name" source archive)" "$(pin plugins "$name" source archiveSha256)"
    package=$(pin plugins "$name" source package)
    so=$(pin plugins "$name" soName)

    out="$root/app/src/main/jniLibs/arm64-v8a"
    mkdir -p "$out"
    (
        cd "$work/src/$name-src"
        GOTOOLCHAIN=local CGO_ENABLED=0 GOOS=android GOARCH=arm64 \
        GOFLAGS=-mod=mod \
        GOPATH="$work/gopath" GOCACHE="$work/gocache" GOMODCACHE="$work/gomodcache" \
        PATH="$GOROOT/bin:$PATH" GOROOT="$GOROOT" \
            "$GOROOT/bin/go" build -trimpath \
                -ldflags "-s -w -checklinkname=0" \
                -o "$out/$so" "$package"
    )
    [ -s "$out/$so" ] || die "$name produced no binary"
}

build_plugins() {
    log "protocol helpers (mieru, xray, olcrtc)"
    build_go_plugin mieru
    build_go_plugin xray
    build_olcrtc
    printf '   -> app/src/main/jniLibs/arm64-v8a/\n'
    printf '   note: NaiveProxy is not built here; it is a Chromium fork\n'
}

build_olcrtc() {
    printf '   olcrtc\n'
    go_version=$(pin plugins olcrtc go version)
    install_plugin_go "$go_version" \
        "$(pin plugins olcrtc go linuxAmd64 file)" \
        "$(pin plugins olcrtc go linuxAmd64 url)" \
        "$(pin plugins olcrtc go linuxAmd64 sha256)"
    unpack olcrtc-src "$(pin plugins olcrtc archive)" "$(pin plugins olcrtc sha256)"
    out="$root/app/src/main/jniLibs/arm64-v8a"
    mkdir -p "$out"
    (
        cd "$work/src/olcrtc-src"
        GOTOOLCHAIN=local CGO_ENABLED=0 GOOS=android GOARCH=arm64 \
        GOFLAGS=-mod=mod \
        GOPATH="$work/gopath" GOCACHE="$work/gocache" GOMODCACHE="$work/gomodcache" \
        PATH="$GOROOT/bin:$PATH" GOROOT="$GOROOT" \
            "$GOROOT/bin/go" build -trimpath \
                -ldflags "-s -w -checklinkname=0" \
                -o "$out/$(pin plugins olcrtc soName)" ./cmd/olcrtc
    )
}

case "$target" in
    all)     build_neko; build_awg; build_plugins ;;
    neko)    build_neko ;;
    awg)     build_awg ;;
    plugins) build_plugins ;;
    *)       die "unknown target '$target' (use: all | neko | awg | plugins)" ;;
esac

log "done"
