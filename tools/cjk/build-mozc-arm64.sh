#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
#
# Build the exact locked Mozc source plus the FrostKeys-owned JNI boundary into
# an ARM64-only, 16 KiB-page-compatible candidate artifact.  Run this in Linux
# (for example a controlled Docker builder), not from the Android app Gradle
# build.

set -euo pipefail

readonly EXPECTED_NDK_REVISION="28.0.13004108"
readonly EXPECTED_BAZEL_VERSION="9.0.2"
readonly EXPECTED_MOZC_COMMIT="851c3fe33060d2a6090363e4d7ec44fafde2c03d"

usage() {
  cat <<'EOF'
Usage:
  build-mozc-arm64.sh --source-root <locked Mozc checkout> --ndk-root <Linux NDK r28> --ndk-archive <verified ZIP> --bazel <pinned binary> --output <new directory>

The script verifies the immutable FrostKeys source lock before creating a clean
temporary source snapshot. It injects the reviewed FrostKeys JNI bridge into
that snapshot, then builds only //android/jni:frostkeys_mozc.arm64 and
//data_manager/oss:mozc_dataset_for_oss, then writes:

  <output>/native/libfrostkeys_mozc.so
  <output>/data/mozc.data
  <output>/BUILD_INPUTS.json

It never writes to the verified source checkout and refuses to overwrite the
output path. BUILD_INPUTS.json records hashes of the bridge sources in addition
to the locked upstream source/toolchain/data proof. An actual successful build
is still required before this output can be passed to
package-engine-bundle.py.
EOF
}

SOURCE_ROOT=""
NDK_ROOT=""
NDK_ARCHIVE=""
BAZEL=""
OUTPUT=""
while (($#)); do
  case "$1" in
    --source-root)
      SOURCE_ROOT=${2:?missing value for --source-root}
      shift 2
      ;;
    --ndk-root)
      NDK_ROOT=${2:?missing value for --ndk-root}
      shift 2
      ;;
    --ndk-archive)
      NDK_ARCHIVE=${2:?missing value for --ndk-archive}
      shift 2
      ;;
    --bazel)
      BAZEL=${2:?missing value for --bazel}
      shift 2
      ;;
    --output)
      OUTPUT=${2:?missing value for --output}
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [[ -z "$SOURCE_ROOT" || -z "$NDK_ROOT" || -z "$NDK_ARCHIVE" || -z "$BAZEL" || -z "$OUTPUT" ]]; then
  usage >&2
  exit 2
fi

readonly SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
readonly BRIDGE_DIR="$SCRIPT_DIR/mozc_bridge"
readonly BRIDGE_SOURCE="$BRIDGE_DIR/frostkeys_mozc_jni.cc"
readonly BRIDGE_BUILD_FRAGMENT="$BRIDGE_DIR/BUILD.bazel.template"
readonly SOURCE_ROOT=$(cd -- "$SOURCE_ROOT" && pwd -P)
readonly NDK_ROOT=$(cd -- "$NDK_ROOT" && pwd -P)
readonly NDK_ARCHIVE=$(python3 -c 'import os, sys; print(os.path.abspath(sys.argv[1]))' "$NDK_ARCHIVE")
readonly BAZEL=$(cd -- "$(dirname -- "$BAZEL")" && pwd -P)/$(basename -- "$BAZEL")
readonly OUTPUT=$(python3 -c 'import os, sys; print(os.path.abspath(sys.argv[1]))' "$OUTPUT")

for command in git python3 tar find sha256sum gcc g++; do
  command -v "$command" >/dev/null || {
    echo "Required command is unavailable: $command" >&2
    exit 1
  }
done

for bridge_file in "$BRIDGE_SOURCE" "$BRIDGE_BUILD_FRAGMENT"; do
  [[ -f "$bridge_file" && ! -L "$bridge_file" ]] || {
    echo "Required Mozc bridge input is missing or unsafe: $bridge_file" >&2
    exit 1
  }
done
readonly BRIDGE_SOURCE_SHA256=$(sha256sum "$BRIDGE_SOURCE" | awk '{print $1}')
readonly BRIDGE_BUILD_FRAGMENT_SHA256=$(sha256sum "$BRIDGE_BUILD_FRAGMENT" | awk '{print $1}')

python3 "$SCRIPT_DIR/verify-toolchain.py" --archive "$NDK_ARCHIVE" --ndk-root "$NDK_ROOT" --bazel "$BAZEL" > /dev/null
readonly NDK_REVISION=$EXPECTED_NDK_REVISION
readonly NDK_ARCHIVE_SHA256=$(sha256sum "$NDK_ARCHIVE" | awk '{print $1}')
readonly BAZEL_SHA256=$(sha256sum "$BAZEL" | awk '{print $1}')

[[ ! -e "$OUTPUT" ]] || {
  echo "Refusing to overwrite output: $OUTPUT" >&2
  exit 1
}
[[ -d "$(dirname -- "$OUTPUT")" ]] || {
  echo "Output parent does not exist: $(dirname -- "$OUTPUT")" >&2
  exit 1
}

# This performs the same read-only verification used by asset packaging.  The
# input may be the Mozc checkout itself or a directory containing a mozc child.
python3 "$SCRIPT_DIR/verify-pinned-cjk-sources.py" \
  --source-root "$SOURCE_ROOT" --engine mozc --json > /dev/null

# Keep the user-facing input compatible with verify-pinned-cjk-sources.py:
# callers may pass either the Mozc checkout itself or its parent directory.
if git -C "$SOURCE_ROOT" rev-parse --is-inside-work-tree > /dev/null 2>&1; then
  readonly SOURCE_CHECKOUT=$(git -C "$SOURCE_ROOT" rev-parse --show-toplevel)
else
  readonly SOURCE_CHECKOUT=$(git -C "$SOURCE_ROOT/mozc" rev-parse --show-toplevel)
fi

readonly BUILD_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/frostkeys-mozc.XXXXXXXX")
cleanup() {
  rm -rf -- "$BUILD_ROOT"
}
trap cleanup EXIT

readonly SNAPSHOT="$BUILD_ROOT/mozc"
mkdir -p -- "$SNAPSHOT"
# ``git archive`` gives the build an exact source tree with no mutable Git
# metadata and without touching the user-owned verified checkout.  Archive the
# locked commit explicitly instead of the mutable symbolic HEAD: a concurrent
# checkout in the source directory must not be able to change the source tree
# after the provenance check above has passed.
git -C "$SOURCE_CHECKOUT" archive --format=tar "$EXPECTED_MOZC_COMMIT" | tar -xf - -C "$SNAPSHOT"

# The upstream JNI package deliberately has narrow visibility to its private
# engine/data targets.  Add the owned bridge to this *temporary* snapshot's
# existing package rather than broadening upstream visibility or compiling the
# Google-package/global-session mozcjni target.  BUILD.bazel.template is an
# append fragment; it relies only on symbols loaded by the upstream file.
install -m 0644 -- "$BRIDGE_SOURCE" "$SNAPSHOT/src/android/jni/frostkeys_mozc_jni.cc"
cat -- "$BRIDGE_BUILD_FRAGMENT" >> "$SNAPSHOT/src/android/jni/BUILD.bazel"
[[ $(sha256sum "$SNAPSHOT/src/android/jni/frostkeys_mozc_jni.cc" | awk '{print $1}') == "$BRIDGE_SOURCE_SHA256" ]] || {
  echo "FrostKeys Mozc bridge copy did not match reviewed source" >&2
  exit 1
}

# Mozc's locked MODULE.bazel names this path android-ndk-r29. The directory
# name is only the upstream lookup path; this temporary link intentionally
# supplies the plan's NDK 28.0.13004108 without patching the source tree.
mkdir -p -- "$SNAPSHOT/src/third_party/ndk"
ln -s -- "$NDK_ROOT" "$SNAPSHOT/src/third_party/ndk/android-ndk-r29"

pushd "$SNAPSHOT/src" >/dev/null
# ``mozc_dataset_for_oss`` generates the ``mozc.data`` output using host-side
# code generators and is intentionally incompatible with the Android target
# platform. Build it in the locked Linux host configuration, then build the
# owned JNI library in the Android configuration.
"$BAZEL" build --config oss_linux --config release_build \
  //data_manager/oss:mozc_dataset_for_oss
"$BAZEL" build --config oss_android --config release_build \
  //android/jni:frostkeys_mozc.arm64

resolve_cquery_file() {
  local configuration=$1
  local label=$2
  local -a files=()
  mapfile -t files < <("$BAZEL" cquery "--config=$configuration" --config release_build \
    --output=files "$label" | sed '/^[[:space:]]*$/d')
  if [[ ${#files[@]} -ne 1 ]]; then
    echo "Expected exactly one output for $label, got ${#files[@]}" >&2
    printf '  %s\n' "${files[@]}" >&2
    exit 1
  fi
  local file=${files[0]}
  if [[ "$file" != /* ]]; then
    file="$PWD/$file"
  fi
  [[ -f "$file" ]] || {
    echo "Bazel reported a missing output for $label: $file" >&2
    exit 1
  }
  printf '%s\n' "$file"
}

readonly NATIVE_LIBRARY=$(resolve_cquery_file oss_android //android/jni:frostkeys_mozc.arm64)
readonly DATA_FILE=$(resolve_cquery_file oss_linux //data_manager/oss:mozc_dataset_for_oss)
popd >/dev/null

readonly STAGE="$BUILD_ROOT/output"
mkdir -p -- "$STAGE/native" "$STAGE/data"
install -m 0644 -- "$NATIVE_LIBRARY" "$STAGE/native/libfrostkeys_mozc.so"
install -m 0644 -- "$DATA_FILE" "$STAGE/data/mozc.data"

native_sha256=$(sha256sum "$STAGE/native/libfrostkeys_mozc.so" | awk '{print $1}')
data_sha256=$(sha256sum "$STAGE/data/mozc.data" | awk '{print $1}')
cat > "$STAGE/BUILD_INPUTS.json" <<EOF
{
  "schema": 1,
  "engine": "mozc",
  "sourceCommit": "$EXPECTED_MOZC_COMMIT",
  "toolchain": {
    "ndkRevision": "$NDK_REVISION",
    "ndkArchiveSha256": "$NDK_ARCHIVE_SHA256",
    "bazelVersion": "$EXPECTED_BAZEL_VERSION",
    "bazelSha256": "$BAZEL_SHA256"
  },
  "targets": ["//android/jni:frostkeys_mozc.arm64", "//data_manager/oss:mozc_dataset_for_oss"],
  "native": {"path": "native/libfrostkeys_mozc.so", "sha256": "$native_sha256"},
  "bridge": {
    "schema": 1,
    "kind": "frostkeys-owned-jni-bridge",
    "target": "//android/jni:frostkeys_mozc.arm64",
    "inputs": [
      {"name": "frostkeys_mozc_jni.cc", "sha256": "$BRIDGE_SOURCE_SHA256"},
      {"name": "BUILD.bazel.template", "sha256": "$BRIDGE_BUILD_FRAGMENT_SHA256"}
    ]
  },
  "data": [{"path": "data/mozc.data", "sha256": "$data_sha256"}]
}
EOF

mv -- "$STAGE" "$OUTPUT"
echo "Built ARM64 Mozc candidate artifacts in $OUTPUT"
