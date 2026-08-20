# Third-party notices for Lean native cores

This document records direct source provenance for Lean's pinned native cores.
It is not a substitute for the license files named below or for a complete
review of transitively linked Go modules.

## AmneziaWG for Android

- Repository: <https://github.com/amnezia-vpn/amneziawg-android>
- Revision: `4116c836241f737badb99dcd4e990600d46e4c65`
- Archive:
  <https://codeload.github.com/amnezia-vpn/amneziawg-android/tar.gz/4116c836241f737badb99dcd4e990600d46e4c65>
- Archive SHA-256:
  `4e712c32172b41ac03fad22afb67f2cc9ae56cb92ee9245b11c7453570c8ff5d`
- License: Apache-2.0, copied verbatim from repository `COPYING` to
  `native/licenses/AmneziaWG-Android-COPYING`
- License SHA-256:
  `cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30`
- Distribution: only the pinned `api-android.go`, `jni.c`, `go.mod`, and
  `go.sum` JNI glue is used. The official backend, UI, tunnel model, and
  manifests are excluded.

The tracked `native/amneziawg/patches/direct-jni-only.patch` removes the
process-local UAPI listener/storage path from the glue because Lean uses the
direct JNI TUN-file-descriptor API. Its source, patched-output, and patch
hashes are recorded in `native/versions.lock`.

## AmneziaWG-Go

- Repository: <https://github.com/amnezia-vpn/amneziawg-go>
- Tag: `v0.2.16`
- Revision: `730d6c39d0c4e348a3d080bebe496664215e5c99`
- Archive:
  <https://codeload.github.com/amnezia-vpn/amneziawg-go/tar.gz/730d6c39d0c4e348a3d080bebe496664215e5c99>
- Archive SHA-256:
  `e26d13e5229f0976353008d78d1359bbdb000c9688b17a1d065ba1f61cbd872a`
- Go module:
  `github.com/amnezia-vpn/amneziawg-go v0.2.16 h1:XY6HOq/xtqH8ZXMncRWkjFs85EKdN10NLNnw23kTpE0=`
- License: MIT, copied verbatim from repository `LICENSE` to
  `native/licenses/AmneziaWG-Go-LICENSE`
- License SHA-256:
  `91276db973f25602d1aa43491f59cbc84cb88e6f151e1d0cc82a755563ce0195`
- Distribution: built from the verified local source archive into
  `libwg-go.so` for four Android ABIs.

## NekoBoxForAndroid

- Repository: <https://github.com/MatsuriDayo/NekoBoxForAndroid>
- Revision: `5768494d8ae3c74a057bb6d46c0f8dc071b0d821`
- License source: repository `LICENSE`, copied verbatim to
  `native/licenses/NekoBox-LICENSE`
- Distribution: `libcore` source and linked code are used to build the
  distributed `libcore.aar`

## MatsuriDayo/sing-box

- Repository: <https://github.com/MatsuriDayo/sing-box>
- Revision: `aed32ee3066cdbc7d471e3e0415c5134088962df`
- License source: repository `LICENSE`, copied verbatim to
  `native/licenses/sing-box-LICENSE`
- Distribution: linked into `libcore.aar`; a host validator is built for CI
  only

## MatsuriDayo/libneko

- Repository: <https://github.com/MatsuriDayo/libneko>
- Revision: `1c47a3af71990a7b2192e03292b4d246c308ef0b`
- License source: none present in the pinned repository
- Distribution: linked into `libcore.aar`
- Status: release blocker. No license is asserted or invented. Authoritative
  upstream licensing/provenance must be obtained before distributing the AAR.

## MatsuriDayo/gomobile

- Repository: <https://github.com/MatsuriDayo/gomobile>
- Revision: `17d6af34f6bd6d7e1e428e0c652c8b54a46bda4f`
- License source: repository `LICENSE`, copied verbatim to
  `native/licenses/gomobile-LICENSE`
- Distribution: build tool only; it is not an intended Java API dependency
  in the AAR

Exact codeload archive hashes, module-file hashes, toolchain versions, Android
API level, ABIs, and build tags are recorded in `native/versions.lock`.
Release packaging must include that lock, this notice, the available license
files, and a completed license review for all linked dependencies.

## Protocol helper binaries

Four upstream programs ship inside the APK as executables under
`lib/<abi>/lib*.so` and run as their own processes. Nothing links against them:
each listens on a local SOCKS port and is driven by a config file this app
generates. They are vendored by `native/plugins/vendor.ps1` (and, for olcRTC,
compiled by `native/plugins/build-olcrtc.ps1`) from the URLs and revisions
pinned in `native/versions.lock`, each verified by SHA-256 after download.

| Component | Upstream | Pinned | License | Origin |
| --- | --- | --- | --- | --- |
| NaiveProxy | <https://github.com/klzgrad/naiveproxy> | `v150.0.7871.63-1` | BSD-3-Clause | release asset (plugin APK) |
| Mieru | <https://github.com/enfein/mieru> | `v3.35.0` | GPL-3.0 | release asset (tarball) |
| olcRTC | <https://github.com/openlibrecommunity/olcrtc> | `b33680871cdb94a7523829f8073de3eafa5b3ece` | WTFPL | built from source |
| Xray-core | <https://github.com/XTLS/Xray-core> | `v26.3.27` | MPL-2.0 | release asset (`Xray-android-arm64-v8a.zip`) |

Xray-core's license is copied verbatim to `native/licenses/Xray-core-LICENSE`.
Only the `xray` executable is taken from its archive; the `geoip.dat` and
`geosite.dat` shipped alongside it are not, because routing stays with the core
and no generated config names a geo rule.

Xray-core is used for exactly one thing: VLESS over the XHTTP transport, which
the pinned sing-box does not implement under any name. Every other VLESS node
runs in the core.

License texts for all three are copied verbatim into `native/licenses/`:

- NaiveProxy -> `native/licenses/NaiveProxy-LICENSE` (BSD-3-Clause, from the
  pinned `v150.0.7871.63-1` tag)
- Mieru -> `native/licenses/Mieru-LICENSE` (GPL-3.0, from the pinned `v3.35.0` tag)
- olcRTC -> `native/licenses/olcRTC-LICENSE` (WTFPL, from the pinned commit)

Every license text listed in this document also ships INSIDE the APK, under
`assets/licenses/`, and is readable in the app: «О программе» -> «Тексты
лицензий». GPL-3.0 requires a copy of the license to travel with the program
and BSD requires the notice to be reproduced in redistributions; a link to a
repository does not satisfy either.

## Onest

- Repository: <https://github.com/google/fonts>
- Revision: `9fab8b6c1404bc83b164b41c12427f7032b60f42`
- Source: `ofl/onest/Onest[wght].ttf`
- License: SIL Open Font License 1.1, copied verbatim to
  `third_party/fonts/onest/OFL.txt`
- Distribution: static weights 400, 500, 600, and 700 are bundled as Android
  font resources

## Unbounded

- Repository: <https://github.com/google/fonts>
- Revision: `9fab8b6c1404bc83b164b41c12427f7032b60f42`
- Source: `ofl/unbounded/Unbounded[wght].ttf`
- License: SIL Open Font License 1.1, copied verbatim to
  `third_party/fonts/unbounded/OFL.txt`
- Distribution: static weights 600, 700, and 800 are bundled as Android font
  resources

Exact source, output, and license hashes and the pinned instancing command are
recorded in `third_party/fonts/README.md`.
