# Building genuine CJK engine artifacts

This document defines the reproducible build path for the real native engines used by the optional
offline Chinese Pinyin and Japanese Romaji/Kana subtypes. A successful APK still needs the release
verification and on-device golden/benchmark gates described at the end of this document.

## Common non-negotiable gates

1. Fetch source with `fetch-pinned-cjk-sources.ps1`, then run
   `verify-pinned-cjk-sources.py`. The verifier requires the exact remote, clean detached `HEAD`,
   and the Rime tag-object/peeled-commit pair.
2. Build only `arm64-v8a` with the FrostKeys toolchain policy: NDK `28.0.13004108`.
3. Build output must pass the ELF 16 KiB page-size gate before it is copied into an APK asset tree.
4. Add every native/data/license/provenance file through `package-engine-bundle.py`. The app runtime
   will independently verify the hash manifest before atomically installing the bundle.
5. No engine or schema may download data at first run or at any later runtime point.

## Mozc

The locked Mozc source contains a genuine Android native target:

```text
//android/jni:mozc.arm64
//data_manager/oss:mozc_dataset_for_oss
```

The upstream target already links with `-Wl,-z,max-page-size=16384`. Unlike upstream's `package`
target, which produces four ABIs, the FrostKeys helper intentionally builds only `mozc.arm64`.
Its portable `mozc.data` generator is built in the Linux host configuration because upstream marks
that generator incompatible with Android; the resulting data file is then paired with the ARM64
library.

Run `build-mozc-arm64.sh` inside a Linux builder with the pinned Bazel 9.0.2 binary, Python 3, Git, a host GCC/G++ toolchain, and a **Linux** NDK
whose `source.properties` reports `28.0.13004108`:

```bash
bash tools/cjk/build-mozc-arm64.sh \
  --source-root /sources/mozc \
  --ndk-root /toolchains/android-ndk-r28 \
  --ndk-archive /toolchains/android-ndk-r28-linux.zip \
  --bazel /toolchains/bazel-9.0.2-linux-x86_64 \
  --output /out/mozc-arm64
```

### Reproducible Docker builder

`Dockerfile.mozc-builder` installs only the pinned Linux NDK archive and Bazel binary, and checks
both SHA-256 values while the image is built. It deliberately does not copy source into the image:
the already verified checkout is mounted read-only, so the helper's own `git archive` snapshot is
the only mutable build input. On Windows PowerShell, after running the source-acquisition command
from the README:

```powershell
docker build -f tools/cjk/Dockerfile.mozc-builder `
  -t frostkeys-mozc-builder:ndk28-bazel9 tools/cjk

New-Item -ItemType Directory -Force D:\out\frostkeys-mozc | Out-Null
docker run --rm `
  -v D:\src\frostkeys-cjk:/sources:ro `
  -v ${PWD}:/workspace:ro `
  -v D:\out\frostkeys-mozc:/out `
  frostkeys-mozc-builder:ndk28-bazel9 `
  bash /workspace/tools/cjk/build-mozc-arm64.sh `
    --source-root /sources/mozc `
    --ndk-root /opt/frostkeys/toolchain/android-ndk-r28 `
    --ndk-archive /opt/frostkeys/toolchain/android-ndk-r28-linux.zip `
    --bazel /opt/frostkeys/toolchain/bazel-9.0.2-linux-x86_64 `
    --output /out/mozc-arm64
```

The Docker image contains build tools only. It is not a runtime dependency and does not make an
APK CJK-capable by itself.

The script first verifies the original Git checkout, archives its locked source tree to a fresh
temporary directory, and only changes that temporary copy. Mozc's own `MODULE.bazel` currently
looks for a directory named `android-ndk-r29`; the helper creates a temporary symlink with that
name to the explicitly checked NDK 28 directory. It does not patch upstream source. A successful
build is therefore evidence that the locked source works with the FrostKeys NDK; a failure is a
real incompatibility that must be fixed or consciously revisited, not hidden by silently using r29.

The helper produces a candidate library and actual Mozc data file, not an APK asset bundle. Package
them only after native verification:

```bash
python3 tools/cjk/package-engine-bundle.py \
  --engine mozc \
  --source-root /sources/mozc \
  --native-lib /out/mozc-arm64/native/libmozc.so \
  --data-root /out/mozc-arm64/data \
  --build-inputs /out/mozc-arm64/BUILD_INPUTS.json \
  --asset-prefix cjk/mozc/commit-851c3fe \
  --output /out/mozc-assets
```

`BUILD_INPUTS.json` is required. The packager rejects a library or data directory that does not
match its recorded SHA-256 values, source commit, NDK 28 archive, Bazel binary, and exact Mozc
targets. It copies that proof into the hash-verified asset manifest, so a clean source checkout
cannot be used to make an unrelated binary appear reviewed.

### Mozc integration

The reproducible helper injects the reviewed bridge in [`mozc_bridge/`](mozc_bridge/) into its
temporary locked source snapshot, builds `//android/frostkeys:frostkeys_mozc.arm64`, records both
bridge-file hashes in `BUILD_INPUTS.json`, and packages the resulting owned library rather than
upstream `libmozc.so`. The app's `MozcImeRuntime`, restricted command codec and Japanese subtype
are enabled only when Gradle stages a verified bundle.

## Rime

The locked Rime source at `1.16.1` is a C++ core rather than an Android frontend. The FrostKeys
builder supplies the reviewed JNI adapter, ARM64 core and Boost dependency, pinned Luna Pinyin data,
and host-generated OpenCC JSON/`.ocd2` data. It emits the schema-1 `BUILD_INPUTS.json` contract:
source commit, NDK 28 archive SHA-256, bridge/core hashes, and an exhaustive data file/hash list.
The packager refuses a Rime bundle without that proof; the app registers Chinese only when its
second Gradle-facing verifier accepts it.

### ARM64 core build

`assemble-rime-arm64-candidate.py` now creates a reviewable *external* candidate after a real
`librime.so` has been cross-compiled. It is intentionally outside `app/`, does not add a subtype,
and labels its output `local-candidate-not-packageable` unless it receives host-generated OpenCC
data. A successful native compile must not be mistaken for a working Chinese IME.

The Boost 1.89.0 release is modular: the archive has no top-level `boost/` directory.
`prepare-boost-headers.py` builds a checked, collision-safe aggregate header tree, and
`rime-support/CMakeLists.txt` compiles its one non-header-only dependency, Boost.Regex, without
patching the locked Rime source. Link Rime with:

```text
-DBUILD_SHARED_LIBS=ON -DBUILD_STATIC=ON -DENABLE_LOGGING=OFF \
-DBUILD_TEST=OFF -DBUILD_DATA=OFF -DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384
```

The `assemble-rime-arm64-candidate.py` helper remains useful for a narrowed source/data audit, but
the release path uses `build-rime-arm64.sh` plus `package-engine-bundle.py` to create the complete
bridge/core/OpenCC payload.

```powershell
python tools/cjk/assemble-rime-arm64-candidate.py `
  --source-root D:\src\frostkeys-cjk `
  --native-lib D:\out\rime-build\lib\librime.so `
  --ndk-root $env:LOCALAPPDATA\Android\Sdk\ndk\28.0.13004108 `
  --boost-archive D:\toolchains\boost-1.89.0.tar.xz `
  --boost-source-root D:\out\boost-1.89.0 `
  --allow-pinyin-only `
  --output D:\out\rime-arm64-local
```

The candidate copies the locked `data/minimal` Pinyin schemas and checks every file, the Boost
archive SHA-256, the NDK `source.properties` revision, and each `PT_LOAD` alignment. It is still
blocked from release packaging because it has no independently verified NDK archive, no
FrostKeys-owned JNI adapter, and no OpenCC compiled data.

### OpenCC data blocker and exact host build

Rime's `simplifier` consumes OpenCC `.json` configurations plus compiled `.ocd2` dictionaries;
the raw `.txt` source files are not a replacement. Cross-compiling `opencc_dict` for Android does
not solve this because it cannot execute on the build host. Build it on a matching host first:

```bash
cmake -S /sources/rime/deps/opencc -B /out/opencc-host -G Ninja \
  -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF \
  -DBUILD_DOCUMENTATION=OFF -DENABLE_GTEST=OFF -DENABLE_BENCHMARK=OFF
cmake --build /out/opencc-host --target Dictionaries --parallel
mkdir -p /out/opencc-runtime
cp /sources/rime/deps/opencc/data/config/*.json /out/opencc-runtime/
cp /out/opencc-host/data/*.ocd2 /out/opencc-runtime/
```

Then replace `--allow-pinyin-only` with `--opencc-data-root /out/opencc-runtime`. The release
builder verifies and includes the host-generated `.ocd2` data; it never generates or downloads it
on an Android device.

## After packaging

Only a bundle whose real files pass the packager can enter the app through Gradle's second verifier.
`EngineBundleManager` then extracts its data atomically on first use, while Android loads the signed
JNI library exactly once from `lib/arm64-v8a`. Before a signed personal release, run native golden
tests for Pinyin/Mozc conversion, paging/cancel/learning, plus ARM64 latency/memory benchmarks on a
real Android 12+ device.
