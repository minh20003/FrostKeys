#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
#
# Build the locked librime 1.16.1 core plus FrostKeys' narrow JNI bridge as an ARM64-only,
# 16 KiB-page-compatible offline input bundle candidate. The source checkout is never mutated:
# Git archives of the locked commit/submodules are built in a fresh temporary directory.

set -euo pipefail

readonly EXPECTED_RIME_CHECKOUT_COMMIT="de4700e9f6b75b109910613df907965e3cbe0567"
readonly EXPECTED_RIME_TAG_OBJECT="5d7467d037938a17abb394f560f016adc9f76e14"
readonly EXPECTED_RIME_ORIGIN="https://github.com/rime/librime.git"
readonly EXPECTED_NDK_REVISION="28.0.13004108"
readonly BOOST_ARCHIVE_SHA256="67acec02d0d118b5de9eb441f5fb707b3a1cdd884be00ca24b9a73c995511f74"
readonly RIME_VERSION="1.16.1"
readonly MAX_DATA_FILE_BYTES=$((128 * 1024 * 1024))

usage() {
  cat <<'EOF'
Usage:
  build-rime-arm64.sh \
    --source-root <locked Rime checkout-or-parent> \
    --boost-archive <boost-1.89.0.tar.xz> \
    --opencc-bundle <verified OpenCC runtime directory> \
    --output <new output directory>

The image supplies a Linux Android NDK r28 whose archive is checked against the FrostKeys lock.
The output is atomically published and contains:

  native/libfrostkeys_rime.so
  native/librime.so
  data/shared/<Rime Pinyin schema and OpenCC runtime>
  data/metadata/opencc-build-inputs.json
  data/licenses/opencc-LICENSE.txt
  BUILD_INPUTS.json

The output is not an APK itself. It must next pass package-engine-bundle.py, then the Gradle
Rime staging verifier, before a Chinese subtype may be advertised.
EOF
}

SOURCE_ROOT=""
BOOST_ARCHIVE=""
OPENCC_BUNDLE=""
OUTPUT=""
while (($#)); do
  case "$1" in
    --source-root) SOURCE_ROOT=${2:?missing value for --source-root}; shift 2 ;;
    --boost-archive) BOOST_ARCHIVE=${2:?missing value for --boost-archive}; shift 2 ;;
    --opencc-bundle) OPENCC_BUNDLE=${2:?missing value for --opencc-bundle}; shift 2 ;;
    --output) OUTPUT=${2:?missing value for --output}; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

if [[ -z "$SOURCE_ROOT" || -z "$BOOST_ARCHIVE" || -z "$OPENCC_BUNDLE" || -z "$OUTPUT" ]]; then
  usage >&2
  exit 2
fi

for command in cmake find git ninja python3 sha256sum sort tar unzip install; do
  command -v "$command" >/dev/null || { echo "Required command unavailable: $command" >&2; exit 1; }
done

readonly SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
readonly TOOL_ROOT=${FROSTKEYS_CJK_TOOL_ROOT:-$SCRIPT_DIR}
readonly LOCK_FILE="$TOOL_ROOT/engine-sources.json"
readonly NDK_ROOT="/opt/frostkeys/toolchain/android-ndk-r28"
readonly NDK_ARCHIVE="/opt/frostkeys/toolchain/android-ndk-r28-linux.zip"
readonly SOURCE_ROOT=$(readlink -f -- "$SOURCE_ROOT")
readonly BOOST_ARCHIVE=$(readlink -f -- "$BOOST_ARCHIVE")
readonly OPENCC_BUNDLE=$(readlink -f -- "$OPENCC_BUNDLE")
readonly OUTPUT=$(readlink -m -- "$OUTPUT")
readonly OUTPUT_PARENT=$(dirname -- "$OUTPUT")

[[ -f "$LOCK_FILE" && ! -L "$LOCK_FILE" ]] || { echo "Missing CJK source lock" >&2; exit 1; }
[[ -f "$BOOST_ARCHIVE" && ! -L "$BOOST_ARCHIVE" ]] || { echo "Boost archive is unsafe" >&2; exit 1; }
[[ $(sha256sum "$BOOST_ARCHIVE" | awk '{print $1}') == "$BOOST_ARCHIVE_SHA256" ]] || {
  echo "Boost archive does not match the reviewed 1.89.0 SHA-256" >&2; exit 1;
}
[[ -d "$OPENCC_BUNDLE" && ! -L "$OPENCC_BUNDLE" ]] || { echo "OpenCC bundle is unsafe" >&2; exit 1; }
[[ ! -e "$OUTPUT" ]] || { echo "Refusing to overwrite output: $OUTPUT" >&2; exit 1; }
[[ -d "$OUTPUT_PARENT" ]] || { echo "Output parent is missing: $OUTPUT_PARENT" >&2; exit 1; }

python3 "$TOOL_ROOT/verify-toolchain.py" --archive "$NDK_ARCHIVE" --ndk-root "$NDK_ROOT" > /dev/null

if git -C "$SOURCE_ROOT" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  readonly RIME_CHECKOUT=$(git -C "$SOURCE_ROOT" rev-parse --show-toplevel)
else
  readonly RIME_CHECKOUT=$(git -C "$SOURCE_ROOT/rime" rev-parse --show-toplevel)
fi
[[ $(git -C "$RIME_CHECKOUT" remote get-url origin) == "$EXPECTED_RIME_ORIGIN" ]] || {
  echo "Rime origin does not match the lock" >&2; exit 1;
}
[[ $(git -C "$RIME_CHECKOUT" rev-parse HEAD) == "$EXPECTED_RIME_CHECKOUT_COMMIT" ]] || {
  echo "Rime checkout commit does not match the lock" >&2; exit 1;
}
[[ $(git -C "$RIME_CHECKOUT" cat-file -t refs/tags/1.16.1) == "tag" ]] || {
  echo "Rime 1.16.1 is not an annotated tag" >&2; exit 1;
}
[[ $(git -C "$RIME_CHECKOUT" rev-parse refs/tags/1.16.1) == "$EXPECTED_RIME_TAG_OBJECT" ]] || {
  echo "Rime tag object does not match the lock" >&2; exit 1;
}
[[ $(git -C "$RIME_CHECKOUT" rev-parse 'refs/tags/1.16.1^{}') == "$EXPECTED_RIME_CHECKOUT_COMMIT" ]] || {
  echo "Rime tag peel does not match the lock" >&2; exit 1;
}
# A Windows bind mount may materialize CRLF and make `git status` dirty even though the Git
# objects are unchanged. The builder only reads archived objects, but rejects staged index changes.
git -C "$RIME_CHECKOUT" diff --cached --quiet "$EXPECTED_RIME_CHECKOUT_COMMIT" -- || {
  echo "Rime checkout has staged changes" >&2; exit 1;
}

readonly WORK_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/frostkeys-rime.XXXXXXXX")
cleanup() { rm -rf -- "$WORK_ROOT"; }
trap cleanup EXIT
readonly SNAPSHOT="$WORK_ROOT/source/rime"
readonly PREFIX="$WORK_ROOT/prefix"
readonly BOOST_SOURCE="$WORK_ROOT/boost-1.89.0"
readonly BOOST_HEADERS="$WORK_ROOT/boost-headers"
readonly STAGE="$WORK_ROOT/output"
mkdir -p -- "$SNAPSHOT" "$PREFIX" "$STAGE/native" "$STAGE/data/shared" "$STAGE/data/metadata" "$STAGE/data/licenses"

# Build from immutable Git objects, recursively restoring every locked submodule Gitlink.
git -C "$RIME_CHECKOUT" archive --format=tar "$EXPECTED_RIME_CHECKOUT_COMMIT" | tar -xf - -C "$SNAPSHOT"
readonly SUBMODULE_REPORT="$WORK_ROOT/submodules.tsv"
while IFS= read -r line; do
  [[ ${line:0:1} == " " ]] || { echo "Rime submodule missing/different: $line" >&2; exit 1; }
  fields=${line:1}
  submodule_commit=${fields%% *}
  remainder=${fields#* }
  submodule_path=${remainder%% *}
  readonly_submodule_checkout="$RIME_CHECKOUT/$submodule_path"
  [[ $(git -C "$readonly_submodule_checkout" rev-parse HEAD) == "$submodule_commit" ]] || {
    echo "Rime submodule checkout differs: $submodule_path" >&2; exit 1;
  }
  git -C "$readonly_submodule_checkout" cat-file -e "${submodule_commit}^{commit}" || {
    echo "Rime submodule commit unavailable: $submodule_path" >&2; exit 1;
  }
  mkdir -p -- "$SNAPSHOT/$submodule_path"
  git -C "$readonly_submodule_checkout" archive --format=tar "$submodule_commit" | tar -xf - -C "$SNAPSHOT/$submodule_path"
  printf '%s\t%s\n' "$submodule_commit" "$submodule_path" >> "$SUBMODULE_REPORT"
done < <(git -C "$RIME_CHECKOUT" submodule status --cached --recursive)
[[ -s "$SUBMODULE_REPORT" ]] || { echo "Rime source has no initialized submodules" >&2; exit 1; }

tar -xf "$BOOST_ARCHIVE" -C "$WORK_ROOT"
[[ -f "$BOOST_SOURCE/LICENSE_1_0.txt" ]] || { echo "Boost archive layout is unexpected" >&2; exit 1; }
# Boost.Regex itself has direct Boost.Core and related header dependencies which are not
# necessarily referenced by librime's own source tree. Include its compiled source as a seed
# so the aggregate closure contains every header the static Regex target actually includes.
python3 "$TOOL_ROOT/prepare-boost-headers.py" \
  --source "$BOOST_SOURCE" --output "$BOOST_HEADERS" \
  --seed-root "$SNAPSHOT/src" \
  --seed-root "$BOOST_SOURCE/libs/regex/src" > /dev/null

readonly ANDROID_TOOLCHAIN="$NDK_ROOT/build/cmake/android.toolchain.cmake"
cmake_android_args=(
  -G Ninja
  -DCMAKE_BUILD_TYPE=Release
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_TOOLCHAIN"
  -DANDROID_ABI=arm64-v8a
  -DANDROID_PLATFORM=android-31
  -DCMAKE_POSITION_INDEPENDENT_CODE=ON
  -DCMAKE_INSTALL_PREFIX="$PREFIX"
)

build_dependency() {
  local name=$1
  local source=$2
  shift 2
  cmake -S "$source" -B "$WORK_ROOT/build-$name" "${cmake_android_args[@]}" -DBUILD_SHARED_LIBS=OFF "$@"
  cmake --build "$WORK_ROOT/build-$name" --parallel > /dev/null
  cmake --install "$WORK_ROOT/build-$name" > /dev/null
}

cmake -S "$TOOL_ROOT/rime-support" -B "$WORK_ROOT/build-boost-regex" "${cmake_android_args[@]}" \
  -DBOOST_SOURCE_ROOT="$BOOST_SOURCE" -DBOOST_INCLUDE_ROOT="$BOOST_HEADERS"
cmake --build "$WORK_ROOT/build-boost-regex" --parallel > /dev/null
cmake --install "$WORK_ROOT/build-boost-regex" > /dev/null

build_dependency leveldb "$SNAPSHOT/deps/leveldb" -DLEVELDB_BUILD_TESTS=OFF -DLEVELDB_BUILD_BENCHMARKS=OFF -DLEVELDB_INSTALL=ON
build_dependency marisa "$SNAPSHOT/deps/marisa-trie" -DBUILD_TESTING=OFF
build_dependency yaml "$SNAPSHOT/deps/yaml-cpp" -DYAML_BUILD_SHARED_LIBS=OFF -DYAML_CPP_BUILD_TESTS=OFF -DYAML_CPP_BUILD_TOOLS=OFF -DYAML_CPP_INSTALL=ON
# Do not build OpenCC's `Dictionaries` ALL target for Android: it invokes the Android
# `opencc_dict` executable on the Linux host, which cannot work. The verified host OpenCC bundle
# above already produced and exercised every `.ocd2` runtime file. Rime's native core needs only
# the static libopencc and public headers, so build that explicit target and stage its bounded
# install surface manually.
readonly OPENCC_BUILD="$WORK_ROOT/build-opencc"
cmake -S "$SNAPSHOT/deps/opencc" -B "$OPENCC_BUILD" "${cmake_android_args[@]}" \
  -DBUILD_SHARED_LIBS=OFF -DBUILD_DOCUMENTATION=OFF -DBUILD_PYTHON=OFF \
  -DENABLE_GTEST=OFF -DENABLE_BENCHMARK=OFF
cmake --build "$OPENCC_BUILD" --target libopencc --parallel > /dev/null
[[ -f "$OPENCC_BUILD/src/libopencc.a" ]] || { echo "OpenCC did not produce libopencc.a" >&2; exit 1; }
mkdir -p -- "$PREFIX/lib" "$PREFIX/include/opencc"
install -m 0644 -- "$OPENCC_BUILD/src/libopencc.a" "$PREFIX/lib/libopencc.a"
find "$SNAPSHOT/deps/opencc/src" -maxdepth 1 -type f \( -name '*.hpp' -o -name 'opencc.h' \) -print0 | \
  while IFS= read -r -d '' header; do install -m 0644 -- "$header" "$PREFIX/include/opencc/$(basename -- "$header")"; done
install -m 0644 -- "$OPENCC_BUILD/src/opencc_config.h" "$PREFIX/include/opencc/opencc_config.h"
install -m 0644 -- "$OPENCC_BUILD/src/Opencc_Export.h" "$PREFIX/include/opencc/Opencc_Export.h"

# Rime's legacy Find*.cmake modules predate modern package exports and can miss a static
# Android archive even under CMAKE_PREFIX_PATH. Pin the exact just-built include/library pairs
# so it cannot accidentally resolve a host dependency.
cmake -S "$SNAPSHOT" -B "$WORK_ROOT/build-rime" "${cmake_android_args[@]}" \
  -DBUILD_SHARED_LIBS=ON -DBUILD_STATIC=ON -DENABLE_LOGGING=OFF -DBUILD_TEST=OFF -DBUILD_DATA=OFF \
  -DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384 \
  -DCMAKE_PREFIX_PATH="$PREFIX" -DBOOST_ROOT="$PREFIX" -DBoost_NO_SYSTEM_PATHS=ON -DBoost_USE_STATIC_LIBS=ON \
  -DBoost_INCLUDE_DIR="$PREFIX/include" -DBoost_REGEX_LIBRARY_RELEASE="$PREFIX/lib/libboost_regex.a" \
  -DBoost_REGEX_LIBRARY_DEBUG="$PREFIX/lib/libboost_regex.a" \
  -DYamlCpp_INCLUDE_PATH="$PREFIX/include" -DYamlCpp_NEW_API="$PREFIX/include/yaml-cpp/node/node.h" \
  -DYamlCpp_LIBRARY="$PREFIX/lib/libyaml-cpp.a" \
  -DLevelDb_INCLUDE_PATH="$PREFIX/include" -DLevelDb_LIBRARY="$PREFIX/lib/libleveldb.a" \
  -DMarisa_INCLUDE_PATH="$PREFIX/include" -DMarisa_LIBRARY="$PREFIX/lib/libmarisa.a" \
  -DOpencc_INCLUDE_PATH="$PREFIX/include" -DOpencc_LIBRARY="$PREFIX/lib/libopencc.a"
cmake --build "$WORK_ROOT/build-rime" --parallel > /dev/null
readonly CORE_LIBRARY="$WORK_ROOT/build-rime/lib/librime.so"
[[ -f "$CORE_LIBRARY" ]] || { echo "Rime did not produce librime.so" >&2; exit 1; }

cmake -S "$TOOL_ROOT/rime_bridge" -B "$WORK_ROOT/build-rime-bridge" "${cmake_android_args[@]}" \
  -DRIME_SOURCE_ROOT="$SNAPSHOT" -DRIME_LIBRARY="$CORE_LIBRARY"
cmake --build "$WORK_ROOT/build-rime-bridge" --parallel > /dev/null
readonly BRIDGE_LIBRARY="$WORK_ROOT/build-rime-bridge/libfrostkeys_rime.so"
[[ -f "$BRIDGE_LIBRARY" ]] || { echo "Rime bridge did not produce libfrostkeys_rime.so" >&2; exit 1; }

# OpenCC's manifest is independently verified before its portable runtime files are embedded.
python3 - "$OPENCC_BUNDLE" "$TOOL_ROOT" <<'PY'
import importlib.util
from pathlib import Path
import sys

bundle = Path(sys.argv[1])
tool_root = Path(sys.argv[2])
spec = importlib.util.spec_from_file_location("verify_rime_opencc_data", tool_root / "verify-rime-opencc-data.py")
module = importlib.util.module_from_spec(spec)
assert spec and spec.loader
spec.loader.exec_module(module)
module.verify(bundle)
PY

cp -- "$BRIDGE_LIBRARY" "$STAGE/native/libfrostkeys_rime.so"
cp -- "$CORE_LIBRARY" "$STAGE/native/librime.so"
for file in "$SNAPSHOT/data/minimal"/*; do
  [[ -f "$file" && ! -L "$file" ]] || { echo "Unsafe Rime minimal data file: $file" >&2; exit 1; }
  cp -- "$file" "$STAGE/data/shared/$(basename -- "$file")"
done
find "$OPENCC_BUNDLE/runtime" -maxdepth 1 -type f \( -name '*.json' -o -name '*.ocd2' \) -print0 | sort -z | while IFS= read -r -d '' file; do
  [[ ! -L "$file" ]] || { echo "Unsafe OpenCC runtime file: $file" >&2; exit 1; }
  cp -- "$file" "$STAGE/data/shared/$(basename -- "$file")"
done
cp -- "$OPENCC_BUNDLE/metadata/opencc-build-inputs.json" "$STAGE/data/metadata/opencc-build-inputs.json"
cp -- "$OPENCC_BUNDLE/licenses/opencc-LICENSE.txt" "$STAGE/data/licenses/opencc-LICENSE.txt"
cp -- "$SNAPSHOT/LICENSE" "$STAGE/data/licenses/librime-LICENSE.txt"
cp -- "$BOOST_SOURCE/LICENSE_1_0.txt" "$STAGE/data/licenses/boost-LICENSE_1_0.txt"

# The upstream minimal schema declares zh_simp and zh_trad switches, but only ships an implicit
# simplifier. Pin both exact OpenCC configurations in the temporary output so the fixed bridge's
# Simplified/Traditional modes are real conversion filters, not merely state flags.
python3 - "$STAGE/data/shared/luna_pinyin.schema.yaml" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
text = path.read_text(encoding="utf-8")
needle = "    - simplifier@zh_simp\n"
simple = "zh_simp:\n  option_name: zh_simp\n  tips: all\n"
if text.count(needle) != 1 or text.count(simple) != 1 or "\nzh_trad:\n" in text:
    raise SystemExit("Unexpected locked luna_pinyin schema shape")
text = text.replace(needle, needle + "    - simplifier@zh_trad\n")
text = text.replace(simple, "zh_simp:\n  option_name: zh_simp\n  opencc_config: t2s.json\n  tips: all\n")
text += "\nzh_trad:\n  option_name: zh_trad\n  opencc_config: s2t.json\n  tips: all\n"
path.write_text(text, encoding="utf-8")
PY

validate_file() {
  local file=$1
  [[ -f "$file" && ! -L "$file" ]] || { echo "Unsafe generated Rime file: $file" >&2; exit 1; }
  local bytes
  bytes=$(stat -c '%s' "$file")
  [[ "$bytes" -gt 0 && "$bytes" -le "$MAX_DATA_FILE_BYTES" ]] || { echo "Rime file size invalid: $file" >&2; exit 1; }
}
while IFS= read -r -d '' file; do validate_file "$file"; done < <(find "$STAGE" -type f -print0)

export FROSTKEYS_STAGE="$STAGE"
export FROSTKEYS_NDK_ARCHIVE_SHA256=$(sha256sum "$NDK_ARCHIVE" | awk '{print $1}')
export FROSTKEYS_BOOST_SHA256=$(sha256sum "$BOOST_ARCHIVE" | awk '{print $1}')
export FROSTKEYS_BRIDGE_SHA256=$(sha256sum "$STAGE/native/libfrostkeys_rime.so" | awk '{print $1}')
export FROSTKEYS_CORE_SHA256=$(sha256sum "$STAGE/native/librime.so" | awk '{print $1}')
export FROSTKEYS_BRIDGE_SOURCE_SHA256=$(sha256sum "$TOOL_ROOT/rime_bridge/frostkeys_rime_jni.cc" | awk '{print $1}')
export FROSTKEYS_BRIDGE_CMAKE_SHA256=$(sha256sum "$TOOL_ROOT/rime_bridge/CMakeLists.txt" | awk '{print $1}')
export FROSTKEYS_SCRIPT_SHA256=$(sha256sum "$SCRIPT_DIR/build-rime-arm64.sh" | awk '{print $1}')
export FROSTKEYS_LOCK_SHA256=$(sha256sum "$LOCK_FILE" | awk '{print $1}')
export FROSTKEYS_SUBMODULES="$SUBMODULE_REPORT"
python3 - <<'PY'
import hashlib
import json
import os
from pathlib import Path

stage = Path(os.environ["FROSTKEYS_STAGE"])
entries = []
for path in sorted((stage / "data").rglob("*")):
    if not path.is_file():
        continue
    relative = path.relative_to(stage).as_posix()
    entries.append({"path": relative, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
submodules = []
for line in Path(os.environ["FROSTKEYS_SUBMODULES"]).read_text(encoding="utf-8").splitlines():
    commit, path = line.split("\t", 1)
    submodules.append({"commit": commit, "path": path})
manifest = {
    "schema": 1,
    "engine": "rime",
    "sourceCommit": "de4700e9f6b75b109910613df907965e3cbe0567",
    "toolchain": {
        "ndkRevision": "28.0.13004108",
        "ndkArchiveSha256": os.environ["FROSTKEYS_NDK_ARCHIVE_SHA256"],
    },
    "native": {"path": "native/libfrostkeys_rime.so", "sha256": os.environ["FROSTKEYS_BRIDGE_SHA256"]},
    "nativeDependencies": [{"role": "librime-core", "path": "native/librime.so", "sha256": os.environ["FROSTKEYS_CORE_SHA256"]}],
    "bridge": {
        "schema": 1,
        "kind": "frostkeys-owned-rime-jni-bridge",
        "target": "tools/cjk/rime_bridge:frostkeys_rime",
        "inputs": [
            {"name": "frostkeys_rime_jni.cc", "sha256": os.environ["FROSTKEYS_BRIDGE_SOURCE_SHA256"]},
            {"name": "CMakeLists.txt", "sha256": os.environ["FROSTKEYS_BRIDGE_CMAKE_SHA256"]},
        ],
    },
    "runtimeData": {
        "schema": 1,
        "sharedDataPath": "data/shared",
        "pinyin": {
            "schemaId": "luna_pinyin",
            "schemaPath": "data/shared/luna_pinyin.schema.yaml",
            "dictionaryPath": "data/shared/luna_pinyin.dict.yaml",
            "defaultConfigPath": "data/shared/default.yaml",
        },
        "opencc": {
            "manifestPath": "data/metadata/opencc-build-inputs.json",
            "licensePath": "data/licenses/opencc-LICENSE.txt",
            "simplifiedConfigPath": "data/shared/t2s.json",
            "traditionalConfigPath": "data/shared/t2tw.json",
        },
    },
    "rimeBuild": {
        "schema": 1,
        "boost": {"version": "1.89.0", "archiveSha256": os.environ["FROSTKEYS_BOOST_SHA256"]},
        "toolHashes": {
            "build-rime-arm64.sh": os.environ["FROSTKEYS_SCRIPT_SHA256"],
            "engine-sources.json": os.environ["FROSTKEYS_LOCK_SHA256"],
        },
        "sourceVerification": {
            "origin": "https://github.com/rime/librime.git",
            "tagObject": "5d7467d037938a17abb394f560f016adc9f76e14",
            "peeledCommit": "de4700e9f6b75b109910613df907965e3cbe0567",
            "submodules": submodules,
        },
    },
    "data": entries,
}
(stage / "BUILD_INPUTS.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
PY

# The exact output directory is only made visible after every native/data/metadata file exists.
mv -- "$STAGE" "$OUTPUT"
echo "Built verified Rime ARM64 candidate: $OUTPUT"
