# AmneziaWG-Go native core

This directory builds Lean's service-free AmneziaWG-Go JNI core. It reuses
only the pinned official Android JNI glue and builds the pinned Go
implementation from source. The official Android backend, UI, tunnel model,
and manifests are never copied or packaged.

## Rebuild

Required host tools:

- PowerShell 7 (`pwsh`);
- Android SDK containing NDK `26.1.10909125`;
- network access for the first verified source/toolchain download.

From the repository root:

```powershell
pwsh -NoLogo -NoProfile -File native/amneziawg/build.ps1 `
  -NativeWorkRoot D:\LeanVpnBuild\amneziawg `
  -AndroidSdkRoot D:\AndroidSDK
```

The task root must be a dedicated, ordinary directory outside the repository.
Repository, root, tracked-output, reparse-point, junction, and symlink targets
are rejected. Downloads, extracted sources, Go caches, and the toolchain stay
under that task root. The only repository output is the ignored
`native/amneziawg/generated/` directory.

The script verifies all lock-pinned HTTPS archive hashes before extraction,
uses only the verified Go `1.24.4` executable with `GOTOOLCHAIN=local`, replaces
the Go module with the verified local `amneziawg-go` commit, and builds with
NDK `26.1.10909125`, API 24, and four Android ABIs.

## Source correspondence

`native/versions.lock` records the exact source URLs, revisions, tag mapping,
module sum, toolchain archives, glue hashes, patch hash, and output contract.
The unmodified Android glue is:

- `tunnel/tools/libwg-go/api-android.go`;
- `tunnel/tools/libwg-go/jni.c`;
- `tunnel/tools/libwg-go/go.mod`;
- `tunnel/tools/libwg-go/go.sum`.

`patches/direct-jni-only.patch` deterministically removes the UAPI listener
and storage path from `api-android.go`. Lean calls the direct JNI API with its
already-established TUN file descriptor, so the process-local UAPI socket is
unneeded and would create a brittle package/cache-path dependency. The patch
does not add a service, context, `VpnService.Builder`, or tunnel lifecycle.

Exact upstream licenses are preserved in:

- `native/licenses/AmneziaWG-Android-COPYING` (SHA-256
  `cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30`);
- `native/licenses/AmneziaWG-Go-LICENSE` (SHA-256
  `91276db973f25602d1aa43491f59cbc84cb88e6f151e1d0cc82a755563ce0195`).

## Verification

Run the policy and fixture tests, build the AAR, then run the AAR, JNI, ELF,
and merged-manifest contract checks under `native/amneziawg/tests/`.
`awgVersion()` and `System.loadLibrary("wg-go")` on physical/emulated Android
remain device gates and must be exercised for every available ABI before a
release.
