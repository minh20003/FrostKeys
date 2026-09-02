APP_STL := c++_static
# This personal build only targets Android 12+ ARM64 devices. Keep this in sync with
# defaultConfig.ndk.abiFilters so ndk-build never emits stray 32-bit libraries.
APP_ABI := arm64-v8a
# Android 15-class devices may use 16 KiB pages. Every bundled native library must be
# linked for flexible page sizes; Android.mk also sets the explicit 16 KiB PT_LOAD limit.
APP_SUPPORT_FLEXIBLE_PAGE_SIZES := true
