#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
#
# Build and verify a host-generated OpenCC runtime data directory from the
# exact Rime 1.16.1 source checkout locked by FrostKeys.  This is deliberately
# separate from Android/Gradle: opencc_dict is a host executable, while the
# resulting .ocd2 files are portable data inputs for librime/OpenCC.

set -euo pipefail

readonly EXPECTED_RIME_CHECKOUT_COMMIT="de4700e9f6b75b109910613df907965e3cbe0567"
readonly EXPECTED_RIME_TAG_OBJECT="5d7467d037938a17abb394f560f016adc9f76e14"
readonly EXPECTED_OPENCC_SUBMODULE_COMMIT="556ed22496d650bd0b13b6c163be9814637970ae"
readonly EXPECTED_RIME_ORIGIN="https://github.com/rime/librime.git"
readonly EXPECTED_OCD2_COUNT=16
readonly EXPECTED_CONFIG_COUNT=14

usage() {
  cat <<'EOF'
Usage:
  build-rime-opencc-data.sh --source-root <locked Rime parent-or-checkout> --output <new output directory>

The source checkout is read only. The output is created atomically and has:

  runtime/*.json, runtime/*.ocd2
  metadata/opencc-build-inputs.json
  licenses/opencc-LICENSE.txt

The tool verifies the Rime source lock, builds only the host OpenCC dictionary
generator, copies the generated conversion data and performs actual t2s/s2t
conversion checks. It does not build Android native code, install an app, or
register a Chinese subtype.
EOF
}

SOURCE_ROOT=""
OUTPUT=""
while (($#)); do
  case "$1" in
    --source-root)
      SOURCE_ROOT=${2:?missing value for --source-root}
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

if [[ -z "$SOURCE_ROOT" || -z "$OUTPUT" ]]; then
  usage >&2
  exit 2
fi

for command in cmake find git ninja python3 sha256sum sort tar; do
  command -v "$command" >/dev/null || {
    echo "Required command is unavailable: $command" >&2
    exit 1
  }
done

readonly SCRIPT_PATH=$(readlink -f -- "${BASH_SOURCE[0]}")
readonly SCRIPT_DIR=$(dirname -- "$SCRIPT_PATH")
readonly TOOL_ROOT=${FROSTKEYS_CJK_TOOL_ROOT:-$SCRIPT_DIR}
readonly LOCK_FILE="$TOOL_ROOT/engine-sources.json"
readonly SOURCE_ROOT=$(readlink -f -- "$SOURCE_ROOT")
readonly OUTPUT=$(readlink -m -- "$OUTPUT")
readonly OUTPUT_PARENT=$(dirname -- "$OUTPUT")

[[ -f "$LOCK_FILE" && ! -L "$LOCK_FILE" ]] || {
  echo "Source lock is unavailable: $LOCK_FILE" >&2
  exit 1
}
[[ ! -e "$OUTPUT" ]] || {
  echo "Refusing to overwrite output: $OUTPUT" >&2
  exit 1
}
[[ -d "$OUTPUT_PARENT" ]] || {
  echo "Output parent does not exist: $OUTPUT_PARENT" >&2
  exit 1
}

if git -C "$SOURCE_ROOT" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  readonly RIME_CHECKOUT=$(git -C "$SOURCE_ROOT" rev-parse --show-toplevel)
else
  readonly RIME_CHECKOUT=$(git -C "$SOURCE_ROOT/rime" rev-parse --show-toplevel)
fi

[[ $(git -C "$RIME_CHECKOUT" remote get-url origin) == "$EXPECTED_RIME_ORIGIN" ]] || {
  echo "Rime checkout origin does not match the source lock" >&2
  exit 1
}
[[ $(git -C "$RIME_CHECKOUT" rev-parse HEAD) == "$EXPECTED_RIME_CHECKOUT_COMMIT" ]] || {
  echo "Rime checkout commit changed after verification" >&2
  exit 1
}
[[ $(git -C "$RIME_CHECKOUT" cat-file -t refs/tags/1.16.1) == "tag" ]] || {
  echo "Rime 1.16.1 is not an annotated tag" >&2
  exit 1
}
[[ $(git -C "$RIME_CHECKOUT" rev-parse refs/tags/1.16.1) == "$EXPECTED_RIME_TAG_OBJECT" ]] || {
  echo "Rime tag object changed after verification" >&2
  exit 1
}
[[ $(git -C "$RIME_CHECKOUT" rev-parse 'refs/tags/1.16.1^{}') == "$EXPECTED_RIME_CHECKOUT_COMMIT" ]] || {
  echo "Rime tag peel changed after verification" >&2
  exit 1
}
# A Windows checkout may legitimately have CRLF materialized while a Linux
# container expects LF, which makes `git status --porcelain` look dirty even
# though no Git object changed.  This builder never reads that working tree:
# it proves the immutable objects and then extracts `git archive` snapshots.
# Still reject a staged index change, because it could otherwise alter a
# submodule path used below.
git -C "$RIME_CHECKOUT" diff --cached --quiet "$EXPECTED_RIME_CHECKOUT_COMMIT" -- || {
  echo "Rime index has staged changes" >&2
  exit 1
}
[[ $(git -C "$RIME_CHECKOUT/deps/opencc" rev-parse HEAD) == "$EXPECTED_OPENCC_SUBMODULE_COMMIT" ]] || {
  echo "OpenCC submodule commit changed after verification" >&2
  exit 1
}

# Keep only the audited runtime data, notices, and manifest in the promoted
# output.  The temporary Git archive and host build tree can be hundreds of
# MiB; retaining them would both bloat a future bundle review and make the
# output look as though arbitrary source files were runtime assets.
readonly STAGE=$(mktemp -d "$OUTPUT_PARENT/.rime-opencc-stage.XXXXXXXX")
readonly WORK_DIR=$(mktemp -d "$OUTPUT_PARENT/.rime-opencc-work.XXXXXXXX")
cleanup_stage() {
  if [[ -n ${STAGE:-} && -d "$STAGE" ]]; then
    rm -rf -- "$STAGE"
  fi
  if [[ -n ${WORK_DIR:-} && -d "$WORK_DIR" ]]; then
    rm -rf -- "$WORK_DIR"
  fi
}
trap cleanup_stage EXIT

readonly BUILD_DIR="$WORK_DIR/build"
readonly RUNTIME_DIR="$STAGE/runtime"
readonly SNAPSHOT_DIR="$WORK_DIR/source/rime"
readonly SUBMODULE_REPORT="$STAGE/metadata/submodules.tsv"
mkdir -p -- "$RUNTIME_DIR" "$STAGE/metadata" "$STAGE/licenses" "$SNAPSHOT_DIR"

# `git archive` operates on the reviewed Git objects rather than materialized
# checkout files.  Apart from avoiding CRLF false positives on Windows mounts,
# this guarantees a local unstaged edit cannot reach the generated data.
git -C "$RIME_CHECKOUT" archive --format=tar "$EXPECTED_RIME_CHECKOUT_COMMIT" \
  | tar -xf - -C "$SNAPSHOT_DIR"
while IFS= read -r line; do
  [[ ${line:0:1} == " " ]] || {
    echo "Rime submodule is missing or differs from its locked Gitlink: $line" >&2
    exit 1
  }
  fields=${line:1}
  read -r submodule_commit submodule_path _submodule_description <<< "$fields"
  [[ "$submodule_commit" =~ ^[0-9a-f]{40}$ && -n "$submodule_path" ]] || {
    echo "Could not parse Rime submodule status: $line" >&2
    exit 1
  }
  [[ "$submodule_path" != /* && "$submodule_path" != *".."* ]] || {
    echo "Unsafe Rime submodule path: $submodule_path" >&2
    exit 1
  }
  # This loop visits every recursive Gitlink.  It cannot be readonly because
  # Bash keeps the variable alive for the next submodule iteration.
  SUBMODULE_CHECKOUT="$RIME_CHECKOUT/$submodule_path"
  [[ $(git -C "$SUBMODULE_CHECKOUT" rev-parse HEAD) == "$submodule_commit" ]] || {
    echo "Rime submodule checkout does not match its Gitlink: $submodule_path" >&2
    exit 1
  }
  git -C "$SUBMODULE_CHECKOUT" cat-file -e "${submodule_commit}^{commit}" || {
    echo "Rime submodule commit object is unavailable: $submodule_path" >&2
    exit 1
  }
  mkdir -p -- "$SNAPSHOT_DIR/$submodule_path"
  git -C "$SUBMODULE_CHECKOUT" archive --format=tar "$submodule_commit" \
    | tar -xf - -C "$SNAPSHOT_DIR/$submodule_path"
  printf '%s\t%s\n' "$submodule_commit" "$submodule_path" >> "$SUBMODULE_REPORT"
done < <(git -C "$RIME_CHECKOUT" submodule status --cached --recursive)
[[ -s "$SUBMODULE_REPORT" ]] || {
  echo "Rime source checkout has no initialized submodules" >&2
  exit 1
}

# `Dictionaries` builds opencc_dict plus every raw/generated .ocd2 file.
# `opencc` is deliberately built as well so we can validate the copied runtime
# data using actual conversion, rather than merely checking that files exist.
cmake -S "$SNAPSHOT_DIR/deps/opencc" -B "$BUILD_DIR" -G Ninja \
  -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_SHARED_LIBS=OFF \
  -DBUILD_DOCUMENTATION=OFF \
  -DENABLE_GTEST=OFF \
  -DENABLE_BENCHMARK=OFF \
  -DBUILD_PYTHON=OFF
cmake --build "$BUILD_DIR" --target Dictionaries opencc --parallel "${FROSTKEYS_BUILD_JOBS:-2}"

while IFS= read -r -d '' file; do
  [[ ! -L "$file" && -f "$file" ]] || {
    echo "Generated OpenCC dictionary is unsafe: $file" >&2
    exit 1
  }
  cp -- "$file" "$RUNTIME_DIR/${file##*/}"
done < <(find "$BUILD_DIR/data" -maxdepth 1 -type f -name '*.ocd2' -print0 | sort -z)

while IFS= read -r -d '' file; do
  [[ ! -L "$file" && -f "$file" ]] || {
    echo "OpenCC configuration is unsafe: $file" >&2
    exit 1
  }
  cp -- "$file" "$RUNTIME_DIR/${file##*/}"
done < <(find "$SNAPSHOT_DIR/deps/opencc/data/config" -maxdepth 1 -type f -name '*.json' -print0 | sort -z)

readonly OCD2_COUNT=$(find "$RUNTIME_DIR" -maxdepth 1 -type f -name '*.ocd2' | wc -l)
readonly CONFIG_COUNT=$(find "$RUNTIME_DIR" -maxdepth 1 -type f -name '*.json' | wc -l)
[[ "$OCD2_COUNT" -eq "$EXPECTED_OCD2_COUNT" ]] || {
  echo "Unexpected OpenCC .ocd2 count: $OCD2_COUNT" >&2
  exit 1
}
[[ "$CONFIG_COUNT" -eq "$EXPECTED_CONFIG_COUNT" ]] || {
  echo "Unexpected OpenCC config count: $CONFIG_COUNT" >&2
  exit 1
}

readonly T2S_OUTPUT=$(printf '繁體中文\n' | "$BUILD_DIR/src/tools/opencc" -c "$RUNTIME_DIR/t2s.json")
readonly S2T_OUTPUT=$(printf '汉字\n' | "$BUILD_DIR/src/tools/opencc" -c "$RUNTIME_DIR/s2t.json")
[[ "$T2S_OUTPUT" == "繁体中文" ]] || {
  echo "OpenCC t2s conversion check failed" >&2
  exit 1
}
[[ "$S2T_OUTPUT" == "漢字" ]] || {
  echo "OpenCC s2t conversion check failed" >&2
  exit 1
}

cp -- "$SNAPSHOT_DIR/deps/opencc/LICENSE" "$STAGE/licenses/opencc-LICENSE.txt"
[[ ! -L "$STAGE/licenses/opencc-LICENSE.txt" ]] || {
  echo "OpenCC license copy is unsafe" >&2
  exit 1
}

export FROSTKEYS_OPENCC_RUNTIME_DIR="$RUNTIME_DIR"
export FROSTKEYS_OPENCC_MANIFEST="$STAGE/metadata/opencc-build-inputs.json"
export FROSTKEYS_OPENCC_SUBMODULE_REPORT="$SUBMODULE_REPORT"
export FROSTKEYS_OPENCC_SCRIPT_SHA256=$(sha256sum "$SCRIPT_PATH" | awk '{print $1}')
export FROSTKEYS_OPENCC_LOCK_SHA256=$(sha256sum "$LOCK_FILE" | awk '{print $1}')
export FROSTKEYS_OPENCC_CONTAINER_IMAGE=${FROSTKEYS_CONTAINER_IMAGE:-unknown}
export FROSTKEYS_OPENCC_CMAKE_VERSION=$(cmake --version | sed -n '1p')
export FROSTKEYS_OPENCC_CXX_VERSION=$(c++ --version | sed -n '1p')
export FROSTKEYS_OPENCC_PYTHON_VERSION=$(python3 --version)
export FROSTKEYS_OPENCC_T2S_OUTPUT="$T2S_OUTPUT"
export FROSTKEYS_OPENCC_S2T_OUTPUT="$S2T_OUTPUT"
export FROSTKEYS_OPENCC_ORIGIN="$EXPECTED_RIME_ORIGIN"
export FROSTKEYS_OPENCC_TAG_OBJECT="$EXPECTED_RIME_TAG_OBJECT"
export FROSTKEYS_OPENCC_CHECKOUT_COMMIT="$EXPECTED_RIME_CHECKOUT_COMMIT"

python3 - <<'PY'
import hashlib
import json
import os
from pathlib import Path

runtime = Path(os.environ["FROSTKEYS_OPENCC_RUNTIME_DIR"])
manifest_path = Path(os.environ["FROSTKEYS_OPENCC_MANIFEST"])
submodules = []
for line in Path(os.environ["FROSTKEYS_OPENCC_SUBMODULE_REPORT"]).read_text(encoding="utf-8").splitlines():
    commit, path = line.split("\t", 1)
    submodules.append({"commit": commit, "path": path})

entries = []
for path in sorted(runtime.iterdir(), key=lambda item: item.name):
    if path.is_symlink() or not path.is_file():
        raise SystemExit(f"Unsafe OpenCC runtime entry: {path}")
    if path.suffix not in (".json", ".ocd2"):
        raise SystemExit(f"Unexpected OpenCC runtime entry: {path.name}")
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    entries.append({"path": f"runtime/{path.name}", "bytes": path.stat().st_size, "sha256": digest})

manifest = {
    "schema": 1,
    "component": "opencc-runtime-data",
    "engine": "rime",
    "tool": "tools/cjk/build-rime-opencc-data.sh",
    "inputs": {
        "rimeSourceVerification": {
            "method": "git-object-archive",
            "origin": os.environ["FROSTKEYS_OPENCC_ORIGIN"],
            "tagObject": os.environ["FROSTKEYS_OPENCC_TAG_OBJECT"],
            "peeledCommit": os.environ["FROSTKEYS_OPENCC_CHECKOUT_COMMIT"],
            "submodules": submodules,
        },
        "containerImage": os.environ["FROSTKEYS_OPENCC_CONTAINER_IMAGE"],
        "toolHashes": {
            "build-rime-opencc-data.sh": os.environ["FROSTKEYS_OPENCC_SCRIPT_SHA256"],
            "engine-sources.json": os.environ["FROSTKEYS_OPENCC_LOCK_SHA256"],
        },
        "hostTools": {
            "cmake": os.environ["FROSTKEYS_OPENCC_CMAKE_VERSION"],
            "cxx": os.environ["FROSTKEYS_OPENCC_CXX_VERSION"],
            "python": os.environ["FROSTKEYS_OPENCC_PYTHON_VERSION"],
        },
    },
    "files": entries,
    "checks": {
        "t2s": {"input": "繁體中文", "output": os.environ["FROSTKEYS_OPENCC_T2S_OUTPUT"]},
        "s2t": {"input": "汉字", "output": os.environ["FROSTKEYS_OPENCC_S2T_OUTPUT"]},
    },
}
manifest_path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
PY

mv -- "$STAGE" "$OUTPUT"
echo "Built verified Rime OpenCC data bundle: $OUTPUT"
