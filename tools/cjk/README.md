# FrostKeys CJK engine build inputs

This directory never provides a runtime download path. It contains the reproducible source lock,
provenance verification, native bridges, builders and strict packagers for the optional native/data
artifacts bundled into a FrostKeys APK. FrostKeys remains fully offline at runtime.

## Verified upstream source identities

| Engine | Approved ref | Actual checked-out source tree |
| --- | --- | --- |
| Rime | annotated `1.16.1` tag object `5d7467d037938a17abb394f560f016adc9f76e14` | peeled commit `de4700e9f6b75b109910613df907965e3cbe0567` |
| Mozc | commit `851c3fe33060d2a6090363e4d7ec44fafde2c03d` | the same commit |

The distinction in the Rime row matters: `5d7467d…` is a Git tag object, not the commit whose
files are compiled. `verify-pinned-cjk-sources.py` proves both values, the remote URL, clean
checkout status, and (for Rime) every initialized recursive submodule.

Acquire source outside the app checkout:

```powershell
./tools/cjk/fetch-pinned-cjk-sources.ps1 -Destination D:\src\frostkeys-cjk
python ./tools/cjk/verify-pinned-cjk-sources.py --source-root D:\src\frostkeys-cjk --json
```

The acquisition script never deletes an existing checkout and rejects an existing dirty one.

The Linux NDK input is separately pinned in `toolchains.json`: Android's repository metadata gives
the archive SHA-1, and this project lock also records its independently calculated SHA-256. Verify
the downloaded ZIP and extracted toolchain before native compilation:

```powershell
python ./tools/cjk/verify-toolchain.py `
  --archive D:\toolchains\android-ndk-r28-linux.zip `
  --ndk-root D:\toolchains\android-ndk-r28
```

## Artifact gate

`package-engine-bundle.py` does not build a fake CJK engine. It accepts only a real, already-built
native `.so` and already-generated offline data. Before producing an asset tree it verifies:

- locked clean source provenance;
- a 64-bit AArch64 shared ELF with every `PT_LOAD` segment aligned for 16 KiB pages;
- one ARM64 library, no other ABI payload;
- regular files only, safe paths, runtime file/count/size limits, and SHA-256 for every file;
- atomic output containing the exact `EngineBundleInstaller` manifest plus hashed build provenance;
- a required `BUILD_INPUTS.json` proof whose source commit, NDK 28/Bazel identities, library hash,
  and exhaustive offline-data hashes match the files being packaged.

The resulting directory is an intermediate asset tree, not an APK. Gradle accepts it only through
`prepare_mozc_apk_inputs.py` or `prepare_rime_apk_inputs.py`, which reverify the source/toolchain,
owned JNI bridge, data hashes and ARM64/16 KiB ELF contract before staging assets and JNI entries.

See [BUILDING.md](BUILDING.md) for the reproducible Mozc/Rime builds and package commands. The Rime
section also documents the external ARM64 core-build helpers:
`prepare-boost-headers.py`, `rime-support/`, and `assemble-rime-arm64-candidate.py`. Their output is
used to construct the verified payload, including the host-generated OpenCC `.ocd2` data and
FrostKeys-owned JNI adapter.

The repository contains the FrostKeys-owned Mozc JNI bridge in
[`mozc_bridge/`](mozc_bridge/). The reproducible builder injects it into a temporary locked source
snapshot; Gradle packages only the resulting owned `libfrostkeys_mozc.so`, never upstream's Google
JNI library.

## Current status

When `FROSTKEYS_RIME_BUNDLE_DIR` and `FROSTKEYS_MOZC_BUNDLE_DIR` point to verified local bundles,
the app stages offline Chinese Pinyin and Japanese Mozc payloads, advertises their subtypes, and
lazy-loads only the selected engine. A build without a corresponding verified bundle does not
advertise that subtype. Device-native golden tests and ARM64 performance benchmarks remain required
before accepting a signed personal release.
