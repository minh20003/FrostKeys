# WebP codec — reproducible 16 KiB ARM64 build

`webp-android-1.1.2-16k.aar` is a local, ARM64-only rebuild of the upstream
WebP codec. Its SHA-256 is enforced by Gradle:

`cbfa5d7b604accd29767dee7744fd6fd983a002efb9c3f090cade73a38e333f1`

Sources pinned for this rebuild:

- `UdaraWanasinghe/webp-android` tag `v1.1.2`, commit
  `94c6411c4e78d748815b00ef722199802a8733c3` (MIT).
- `webmproject/libwebp` tag `1.5.0`, commit
  `a4d7a715337ded4451fec90ff8ce79728e04126c` (BSD-3-Clause).

Build prerequisites: Android NDK `28.0.13004108`, CMake `3.22.1`, and an
Android SDK. Configure the WebP module for `arm64-v8a` only and add this CMake
linker option before building its release AAR:

```cmake
add_link_options("-Wl,-z,max-page-size=16384")
```

Then validate every `jni/arm64-v8a/*.so` with `llvm-readelf -lW`; all `LOAD`
segments must have an alignment of at least `0x4000`. Do not substitute the
upstream Maven AAR: it contains 4 KiB-aligned native libraries.
