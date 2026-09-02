# SPDX-License-Identifier: GPL-3.0-only
#
# Builds FrostKeys' deliberately narrow JNI frontend for a separately built
# ARM64 librime.  This project never downloads, packages, or registers an IME
# subtype; it exists so the native boundary can be reviewed and cross-compiled
# independently before an APK bundle is considered.

cmake_minimum_required(VERSION 3.22)
project(frostkeys_rime_jni LANGUAGES CXX)

if(NOT DEFINED RIME_SOURCE_ROOT OR NOT IS_DIRECTORY "${RIME_SOURCE_ROOT}")
  message(FATAL_ERROR "RIME_SOURCE_ROOT must name the locked librime source checkout")
endif()

if(NOT DEFINED RIME_LIBRARY OR NOT EXISTS "${RIME_LIBRARY}")
  message(FATAL_ERROR "RIME_LIBRARY must name the already-built ARM64 librime.so")
endif()

add_library(frostkeys_rime SHARED frostkeys_rime_jni.cc)
target_compile_features(frostkeys_rime PRIVATE cxx_std_17)
target_compile_definitions(frostkeys_rime PRIVATE RIME_IMPORTS=1)
target_include_directories(frostkeys_rime PRIVATE "${RIME_SOURCE_ROOT}/src")
target_link_libraries(frostkeys_rime PRIVATE "${RIME_LIBRARY}" android log)

# Android 15's 16 KiB page devices require this on every loadable native
# object, including this small bridge rather than only librime itself.
target_link_options(frostkeys_rime PRIVATE
  "-Wl,-z,max-page-size=16384"
  "-Wl,-z,relro,-z,now"
)

# The final app must put librime.so and libfrostkeys_rime.so in the same
# arm64-v8a JNI directory.  Keep this local lookup for the external candidate
# build too; it is not an APK packaging rule.
set_target_properties(frostkeys_rime PROPERTIES
  OUTPUT_NAME frostkeys_rime
  BUILD_RPATH "\$ORIGIN"
)
