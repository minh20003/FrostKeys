# Third-party notices

## Vietnamese word dictionary

`dicts/main_vi.dict` is downloaded at build time from the Helium314 AOSP
Dictionaries repository, pinned by SHA-256 in `app/build.gradle.kts`.
The upstream Vietnamese data is marked experimental and attributes the Leipzig
Corpora Collection. The Leipzig Corpora Collection is licensed under
[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).

Source snapshot: `main_vi.dict`, 2023-09-16, commit
`69afafc3887d189515fa0be8b4585b91df80b92d` of the dictionary repository.

## FrostKeys Vietnamese phrase seed

`dicts/vi_phrase_model_v1.tsv` is a deliberately small, original, manually curated
FrostKeys Vietnamese bigram/trigram seed (`seed-v1`, GPL-3.0-only). It is not a
Leipzig/Wikipedia/Wiktionary corpus extract and contains no user, contact, or
scraped text. `manifests/phrase_model_vi.json` records the frozen source and
artifact SHA-256 values. The dependency-free builder at
`tools/vietnamese/build_phrase_model.py` is the reproducible source of the APK
asset and never downloads data.

## Vietnamese emoji search

`emoji/vi.xml` is Unicode CLDR release 46 Vietnamese annotation data (commit
`e1d37acce5dae468c414172be53b666d58d45be8`), downloaded at build time with a
pinned SHA-256. It is used only to build the app's local
emoji-search index. No Signal-derived emoji dictionary data is bundled.

Unicode CLDR data is available under the [Unicode License v3](https://www.unicode.org/license.txt).

## WebP animated-sticker codec

`libs/webp-android-1.1.2-16k.aar` is an ARM64-only, 16 KiB-page-compatible
rebuild of `UdaraWanasinghe/webp-android` tag `v1.1.2`
(`94c6411c4e78d748815b00ef722199802a8733c3`, MIT) and libwebp tag `1.5.0`
(`a4d7a715337ded4451fec90ff8ce79728e04126c`, BSD-3-Clause). The exact build
recipe and checksum are in `app/libs/WEBP_16K_BUILD.md`; full license texts are
bundled in `assets/licenses/`.

## Password-protected learning backup

`org.bouncycastle:bcprov-jdk18on:1.85.2` supplies the maintained, pure-Java
Argon2id implementation used only to derive backup keys. AES-256-GCM uses the
Android platform cryptography API; the Bouncy Castle provider is not registered
globally. Bouncy Castle is distributed under the Bouncy Castle Licence; the
license text is bundled as `assets/licenses/bouncycastle-license.txt`.

## Mozc offline Japanese engine

When a maintainer explicitly enables the verified optional offline Mozc bundle,
it is built from [Mozc](https://github.com/google/mozc) commit
`851c3fe33060d2a6090363e4d7ec44fafde2c03d` (BSD-3-Clause). The APK contains
the pinned `mozc.data`, its Mozc license text, and `libfrostkeys_mozc.so` — a
FrostKeys-owned JNI bridge built from source, not an upstream prebuilt
`libmozc.so`. The generated bundle manifest records SHA-256 values, source
commit, NDK 28.0.13004108 and Bazel provenance; it is reverified before APK
packaging and never downloaded by the app.

## Rime offline Chinese Pinyin engine

When a maintainer explicitly enables the verified optional Rime bundle, it is built from
[librime](https://github.com/rime/librime) 1.16.1 annotated tag object
`5d7467d037938a17abb394f560f016adc9f76e14`, peeled to commit
`de4700e9f6b75b109910613df907965e3cbe0567` (BSD-3-Clause). The bundle includes
`libfrostkeys_rime.so`, a FrostKeys-owned restricted JNI bridge, and its matching ARM64
`librime.so` core. It contains no network download path.

Traditional and Simplified conversion uses the OpenCC runtime data generated from Rime's locked
OpenCC submodule `556ed22496d650bd0b13b6c163be9814637970ae` (Apache-2.0). The `.ocd2` files,
OpenCC configuration, source/submodule evidence and SHA-256 manifest are all bundled and verified
before extraction. Rime's static Boost.Regex dependency is built from Boost 1.89.0
(`67acec02d0d118b5de9eb441f5fb707b3a1cdd884be00ca24b9a73c995511f74`, Boost Software License 1.0).
