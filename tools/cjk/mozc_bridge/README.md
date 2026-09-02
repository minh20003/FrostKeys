# FrostKeys-owned Mozc JNI bridge spike

This directory contains the reviewed source bridge for the exact pinned Mozc commit
`851c3fe33060d2a6090363e4d7ec44fafde2c03d`. It is injected only into the builder's temporary
locked Mozc source snapshot; the resulting owned library is staged by Gradle only after the
verified-bundle gates pass. It is never copied wholesale into `app/src/main` or treated as an APK
asset.

## Why it exists

Mozc's upstream Android target (`//android/jni:mozc.arm64`) exports only a JNI
initializer for Google's package and has a process-global `SessionHandler` in
`src/android/jni/mozcjni.cc`.  Calling that target directly would bind
FrostKeys to `com.google.android.apps.inputmethod...MozcJNI` and would not give
FrostKeys an explicit close/cancellation boundary.

`frostkeys_mozc_jni.cc` instead uses the pinned public C++ APIs directly:

- `DataManager::CreateFromFile` loads the verified offline `mozc.data`.
- `Engine::CreateEngine` creates a real data-backed engine; there is no
  minimal-engine fallback.
- `SessionHandler::EvalCommand` owns `CREATE_SESSION`, `SEND_KEY`,
  `SEND_COMMAND`, and `DELETE_SESSION`.
- each Java/Kotlin owner gets an opaque `jlong` pointing to its own
  `FrostKeysMozcContext`; no `g_session_handler` exists in this bridge.

The only process-wide guard is required by Mozc itself: `SystemUtil` stores the
user-profile directory globally.  The bridge therefore permits one active
context and rejects a second profile path for the process.  This avoids mixing
learned data from different FrostKeys profiles.

## Native API contract

The private Kotlin class is
`helium314.keyboard.latin.cjk.MozcNativeBridge` and must declare these instance
native methods:

```kotlin
private external fun nativeCreate(profileDirectory: String, dataFilePath: String): Long
private external fun nativeEvalCommand(handle: Long, request: ByteArray): ByteArray
private external fun nativeDataVersion(handle: Long): String
private external fun nativeClose(handle: Long)
```

`nativeEvalCommand` accepts and returns a serialized
`mozc.commands.Command`.  The bridge only permits `SEND_KEY` and
`SEND_COMMAND`, overwrites the caller-supplied session id with its own id, and
rejects a command larger than 256 KiB.  `SEND_COMMAND` is sufficient for
commit, cancel, candidate selection, and candidate paging.  The Kotlin codec
must map Mozc's `Output.preedit`, `result`, candidate IDs, and
`CandidateWindow.page_size` into FrostKeys `CompositionState`; it must not pass
arbitrary top-level Mozc server commands through this boundary.

The Kotlin owner must serialize all operations on one background dispatcher,
clear its handle to `0` under the same lock before calling `nativeClose`, and
never use a handle after close.  `EngineBundleManager` cancellation must be
checked before `System.loadLibrary("frostkeys_mozc")`, before creating the
owner, and after extraction.  Closing the owner sends `DELETE_SESSION`, which
calls Mozc's `EngineInterface::Sync()` in the pinned source and persists local
learning data to the supplied app-private profile directory.

## Reproducible build shape

`BUILD.bazel.template` is placed with the C++ file in a **temporary** snapshot
created from the locked Mozc checkout, at `src/android/frostkeys/`.  It builds
the target:

```text
//android/frostkeys:frostkeys_mozc.arm64
```

The target deliberately excludes `//android/jni:mozcjni`, links with
`-Wl,-z,max-page-size=16384`, and produces a single owned library such as
`libfrostkeys_mozc.so`.  The build helper must record both the unchanged Mozc
source lock and a SHA-256 of these two FrostKeys bridge files in
`BUILD_INPUTS.json`; then the existing bundle packager can hash the resulting
library and `mozc.data` together.

Do not copy upstream `libmozc.so` into the app: it still contains the Google JNI registration and
global handler. The builder records this bridge, Kotlin codec/adapter, asset manifest, and IME
lifecycle in the verified bundle. Device-native golden tests remain required before accepting a
signed personal release.
