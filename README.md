# FrostKeys 3.0 VN

FrostKeys là bản fork cá nhân, ưu tiên tiếng Việt và hoạt động ngoại tuyến trên Android 12+ ARM64.
Nó giữ package `com.orion.frostkeys`, nhưng không phải bản phát hành chính chủ và không dùng quy
trình Play Store/fastlane của upstream.

## Những gì bản VN có

- Tiếng Việt là ngôn ngữ giao diện mặc định, cùng English, 日本語, 简体中文, 繁體中文, 한국어 và ไทย.
- Việt Telex, VNI và raw QWERTY; cài mới bật sẵn Telex và English, mặc định Telex.
- Từ điển Việt, emoji CLDR Việt, gợi ý cụm từ Việt và spell checker `vi` đều hoạt động offline.
- Rime Pinyin (Giản thể/Phồn thể qua OpenCC) và Mozc Nhật (Romaji/Kana/Kanji) chỉ được quảng bá khi
  bundle native đã được xác minh được cung cấp lúc build. Không có tải dữ liệu CJK qua mạng.
- Cloud, danh bạ và theo dõi ảnh chụp đều tắt mặc định. Gemini/Klipy chỉ có thể tạo request qua
  một cloud gate khi người dùng bật cloud và thiết bị đã được mở khóa.
- Sao lưu v2 bảo vệ dữ liệu học bằng mật khẩu (Argon2id + AES-256-GCM); API key, clipboard, cache
  media và log không nằm trong backup.
- Bàn phím chỉ hỗ trợ `arm64-v8a`; native payload được kiểm tra căn chỉnh trang 16 KiB.

## Build nhanh

Yêu cầu: JDK/Gradle wrapper của project, Android SDK 36, NDK `28.0.13004108`, và Python 3.

Một build test không chứa CJK native vẫn luôn hợp lệ:

```powershell
./gradlew.bat :app:testRunTestsUnitTest
```

Để build với Rime và Mozc offline, trước tiên tạo bundle theo
[hướng dẫn CJK](tools/cjk/BUILDING.md), rồi đặt biến môi trường chỉ đến thư mục bundle đã xác minh:

```powershell
$env:FROSTKEYS_RIME_BUNDLE_DIR = 'D:\out\rime-owned-assets'
$env:FROSTKEYS_MOZC_BUNDLE_DIR = 'D:\out\mozc-owned-assets'
./gradlew.bat :app:testRunTestsUnitTest :app:lintRelease
```

APK smoke-test ký bằng debug key có package khác (`com.orion.frostkeys.internal`) để không thể bị
nhầm là bản cập nhật cá nhân:

```powershell
./gradlew.bat :app:assembleNouserlib
```

## Tạo APK cá nhân có thể cập nhật

Chỉ `:app:assembleRelease` tạo tên artifact release
`FrostKeys_3.0.0-vn.1-arm64.apk`. Khóa phải được giữ lâu dài để Android chấp nhận các bản cập nhật
sau này. Sao chép [keystore.properties.example](keystore.properties.example) thành
`keystore.properties` (file đã ignored), hoặc đặt các biến môi trường sau:

```text
FROSTKEYS_STORE_FILE=%USERPROFILE%\.android\frostkeys-personal.jks
FROSTKEYS_STORE_PASSWORD=...
FROSTKEYS_KEY_ALIAS=frostkeys-personal
FROSTKEYS_KEY_PASSWORD=...
FROSTKEYS_CERT_SHA256=<SHA-256 certificate fingerprint, không dấu :>
```

`FROSTKEYS_CERT_SHA256` là guard bắt buộc để phát hiện nhầm keystore và bảo vệ update lineage.
Sau khi cấu hình, build release tự kiểm tra chữ ký, `zipalign -P 16`, ABI và ELF trước khi chấp nhận
artifact.

```powershell
./gradlew.bat :app:assembleRelease
```

## Trạng thái xác minh

Source tree có gate unit test, lint, kiểm tra Việt hóa, manifest/hash bundle CJK, nội dung APK,
ABI và 16 KiB. Tuy vậy một bản chỉ được gọi là release hoàn chỉnh sau khi:

1. ký bằng khóa cá nhân ổn định nói trên;
2. cài mới/cập nhật thử trên thiết bị Android 12+ ARM64;
3. chạy golden test native Rime/Mozc và benchmark latency/memory trên thiết bị thật.

Không đưa API key, keystore, clipboard, lịch sử cá nhân hoặc ảnh nền vào repository hay APK.

## Ghi nhận nguồn và giấy phép

Xem [THIRD_PARTY_NOTICES](app/src/main/assets/THIRD_PARTY_NOTICES.md) cho Rime, Mozc, OpenCC,
Boost, Unicode CLDR, Leipzig và các thành phần bundle khác. FrostKeys là fork của HeliBoard,
OpenBoard và AOSP LatinIME, được phát hành theo GPL-3.0-only cùng các notice gốc.
