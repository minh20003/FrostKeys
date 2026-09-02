# FrostKeys-owned Rime JNI bridge

This directory is a source-only native frontend for the locked librime 1.16.1
checkout.  It intentionally exposes a very small session API:

- one ASCII Pinyin letter/apostrophe at a time;
- Backspace, explicit candidate selection, explicit page navigation, commit,
  reset, and the reviewed Simplified/Traditional switch;
- an immutable bounded state packet containing preedit, visible candidates,
  page bounds, and one commit result.

It does not accept arbitrary Rime key-sequence strings, arbitrary schema IDs,
  arbitrary options, filesystem paths after session creation, plugins, network
  URLs, or a generic JNI command channel.  Kotlin validates the packet again
before it reaches an `InputConnection`.

The bridge owns a process-global librime runtime behind a mutex because
librime's setup/finalize API is global.  Each Java handle owns one Rime
session; close clears the Java handle before native destruction and causes the
last session to finalize librime.  Initial deployment can be expensive, so a
future IME integration must call `RimeCompositionEngine.create` only from
`EngineBundleManager`'s background executor.

This is not proof that Chinese input is bundled.  A release still needs a
cross-compiled `libfrostkeys_rime.so`, matching `librime.so`, source/data/NDK
evidence, an APK manifest, and device golden tests.  Until all of those exist,
there must be no Chinese subtype or APK asset registration.
