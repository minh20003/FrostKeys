/*
 * Copyright (C) 2012 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.latin.utils;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;
import android.os.Build;

import helium314.keyboard.latin.App;
import helium314.keyboard.latin.BuildConfig;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Set;

@SuppressLint("PrivateApi") // ActivityThread lookup is a fallback in try/catch.
public final class JniUtils {
    private static final String TAG = JniUtils.class.getSimpleName();
    public static final String JNI_LIB_NAME = "jni_latinime";
    public static final String JNI_LIB_NAME_GOOGLE = "jni_latinimegoogle";

    /**
     * Imported code is deliberately kept separate from the app's own JNI library. Only this
     * exact filename below {@code files/gesture-libs} is ever considered for System.load().
     */
    public static final String JNI_LIB_IMPORT_FILE_NAME = "libgesturetyping.so";
    private static final String GESTURE_LIBRARY_DIRECTORY = "gesture-libs";
    private static final String GESTURE_LIBRARY_TEMP_FILE_NAME = ".libgesturetyping.so.tmp";
    public static final long MAX_IMPORTED_GESTURE_LIBRARY_BYTES = 16L * 1024L * 1024L;

    private static final int ELF_HEADER_SIZE = 64;
    private static final int ELF_PROGRAM_HEADER_SIZE = 56;
    private static final int ELF_ET_DYN = 3;
    private static final int ELF_EM_AARCH64 = 183;
    private static final int MAX_PROGRAM_HEADERS = 1024;

    // This is the reviewed ARM64 Google gesture library distributed by the documented source.
    // Adding another binary requires adding its SHA-256 here and shipping a new app build.
    private static final String CHECKSUM_ARM64 = "b1049983e6ac5cfc6d1c66e38959751044fad213dff0637a6cf1d2a2703e754f";
    private static final Set<String> TRUSTED_ARM64_GESTURE_LIBRARY_HASHES =
            Collections.singleton(CHECKSUM_ARM64);

    /**
     * Kept for callers which need to identify the reviewed library. It is not a user preference:
     * accepting a new checksum at runtime would turn the allowlist into a bypass.
     */
    public static String expectedDefaultChecksum() {
        return CHECKSUM_ARM64;
    }

    public static boolean sHaveGestureLib = false;

    static {
        final Application app = findApplication();
        if (!BuildConfig.BUILD_TYPE.equals("nouserlib") && app != null) {
            final File userSuppliedLibrary = getImportedGestureLibraryFile(app);
            if (userSuppliedLibrary.isFile()) {
                try {
                    // Verify immediately before loading. The checksum and the ARM64 ELF header
                    // are both checked again on every app start, not merely during import.
                    if (tryLoadTrustedImportedGestureLibrary(userSuppliedLibrary)) {
                        sHaveGestureLib = true;
                    } else {
                        discardFile(userSuppliedLibrary);
                        Log.w(TAG, "Rejected an untrusted gesture typing library");
                    }
                } catch (Throwable t) { // A third-party binary may still be malformed at load.
                    discardFile(userSuppliedLibrary);
                    Log.w(TAG, "Could not load trusted gesture typing library", t);
                }
            }
        }

        if (!sHaveGestureLib) {
            // Try the Google system library only for system-app installations.
            try {
                System.loadLibrary(JNI_LIB_NAME_GOOGLE);
                sHaveGestureLib = true;
            } catch (UnsatisfiedLinkError ul) {
                Log.w(TAG, "Could not load system glide typing library " + JNI_LIB_NAME_GOOGLE + ": " + ul.getMessage());
            }
        }
        if (!sHaveGestureLib) {
            // The built-in decoder library is not the proprietary gesture engine.
            try {
                System.loadLibrary(JNI_LIB_NAME);
            } catch (UnsatisfiedLinkError ul) {
                Log.w(TAG, "Could not load native library " + JNI_LIB_NAME, ul);
            }
        }
    }

    private JniUtils() {
        // This utility class is not publicly instantiable.
    }

    /** Returns true only on an ARM64 runtime; imported libraries are never accepted elsewhere. */
    public static boolean isArm64Runtime() {
        for (String abi : Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(abi)) return true;
        }
        return false;
    }

    /** The only private path used for an imported gesture library. */
    public static File getImportedGestureLibraryFile(final Context context) {
        return new File(getGestureLibraryDirectory(context), JNI_LIB_IMPORT_FILE_NAME);
    }

    /**
     * Streams a candidate library into a temporary file, validates it, and atomically promotes it
     * to the fixed private path. The caller owns and closes {@code source}.
     *
     * @return {@code true} only when the binary is an allowlisted ARM64 ELF shared object.
     */
    public static boolean installTrustedGestureLibrary(final Context context, final InputStream source)
            throws IOException {
        if (source == null || !isArm64Runtime() || BuildConfig.BUILD_TYPE.equals("nouserlib")) return false;

        final File directory = getGestureLibraryDirectory(context);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Could not create private gesture library directory");
        }
        if (!directory.isDirectory()) {
            throw new IOException("Gesture library path is not a directory");
        }

        final File target = getImportedGestureLibraryFile(context);
        final File temporary = new File(directory, GESTURE_LIBRARY_TEMP_FILE_NAME);
        boolean installed = false;
        try {
            if (temporary.exists() && !discardFile(temporary)) {
                throw new IOException("Could not clear stale gesture library temporary file");
            }
            copyWithSizeLimit(source, temporary);
            if (!isTrustedImportedGestureLibrary(temporary)) return false;

            // Same-directory rename gives an atomic replacement on Android's app-private storage.
            // Do not delete the old target first: a failed replacement must leave it usable.
            if (target.exists() && !target.setWritable(true)) return false;
            if (!temporary.renameTo(target)) return false;
            if (!target.setReadOnly() || !isTrustedImportedGestureLibrary(target)) {
                discardFile(target);
                return false;
            }
            installed = true;
            return true;
        } finally {
            if (!installed) discardFile(temporary);
        }
    }

    /** Removes only the fixed private import and its same-directory temporary file. */
    public static boolean deleteImportedGestureLibrary(final Context context) {
        final File directory = getGestureLibraryDirectory(context);
        final File target = getImportedGestureLibraryFile(context);
        final File temporary = new File(directory, GESTURE_LIBRARY_TEMP_FILE_NAME);
        return discardFile(target) & discardFile(temporary);
    }

    /** True when the file has the structural shape of an ARM64 ELF shared object. */
    public static boolean isValidArm64Elf(final File library) {
        if (library == null || !library.isFile()
                || library.length() < ELF_HEADER_SIZE
                || library.length() > MAX_IMPORTED_GESTURE_LIBRARY_BYTES) {
            return false;
        }
        final byte[] header = new byte[ELF_HEADER_SIZE];
        try (FileInputStream input = new FileInputStream(library)) {
            int offset = 0;
            while (offset < header.length) {
                final int count = input.read(header, offset, header.length - offset);
                if (count < 0) return false;
                offset += count;
            }
        } catch (IOException e) {
            return false;
        }

        if (header[0] != 0x7f || header[1] != 'E' || header[2] != 'L' || header[3] != 'F'
                || unsignedByte(header[4]) != 2 // ELFCLASS64
                || unsignedByte(header[5]) != 1 // ELFDATA2LSB
                || unsignedByte(header[6]) != 1 // EV_CURRENT
                || readU16LE(header, 16) != ELF_ET_DYN
                || readU16LE(header, 18) != ELF_EM_AARCH64
                || readU32LE(header, 20) != 1
                || readU16LE(header, 52) != ELF_HEADER_SIZE
                || readU16LE(header, 54) != ELF_PROGRAM_HEADER_SIZE) {
            return false;
        }

        final int programHeaderCount = readU16LE(header, 56);
        final long programHeaderOffset = readU64LE(header, 32);
        if (programHeaderCount <= 0 || programHeaderCount > MAX_PROGRAM_HEADERS
                || programHeaderOffset < ELF_HEADER_SIZE) {
            return false;
        }
        final long programHeadersEnd = programHeaderOffset
                + (long) ELF_PROGRAM_HEADER_SIZE * programHeaderCount;
        return programHeadersEnd >= programHeaderOffset && programHeadersEnd <= library.length();
    }

    /** Structural validation plus the immutable SHA-256 allowlist. */
    public static boolean isTrustedImportedGestureLibrary(final File library) {
        if (!isValidArm64Elf(library)) return false;
        final String checksum = ChecksumCalculator.INSTANCE.checksum(library);
        return isTrustedGestureChecksum(checksum);
    }

    /** Exposed for deterministic verification and tests; callers cannot mutate the allowlist. */
    public static boolean isTrustedGestureChecksum(final String checksum) {
        return checksum != null && TRUSTED_ARM64_GESTURE_LIBRARY_HASHES.contains(checksum);
    }

    public static void loadNativeLibrary() {
        // Ensures the static initializer is called.
    }

    private static Application findApplication() {
        Application app = App.Companion.getApp();
        if (app != null) return app;
        try {
            // Fallback for class loading before App.onCreate has recorded the application.
            return (Application) Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null, (Object[]) null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static File getGestureLibraryDirectory(final Context context) {
        return new File(context.getFilesDir(), GESTURE_LIBRARY_DIRECTORY);
    }

    // System.load is intentionally required here: the reviewed gesture binary is imported into
    // the app-private fixed path rather than installed as an APK library. The call is reachable
    // only after ARM64 ELF validation and a compile-time SHA-256 allowlist check immediately
    // above it; a new binary requires a source change and a new signed build.
    @SuppressLint("UnsafeDynamicallyLoadedCode")
    private static boolean tryLoadTrustedImportedGestureLibrary(final File library) {
        // This check is intentionally adjacent to System.load to avoid treating a past import
        // result as authorization for a later, modified file.
        if (!isArm64Runtime()) return false;
        if (!isTrustedImportedGestureLibrary(library)) return false;
        System.load(library.getAbsolutePath());
        return true;
    }

    private static void copyWithSizeLimit(final InputStream source, final File destination)
            throws IOException {
        long total = 0;
        final byte[] buffer = new byte[8192];
        try (FileOutputStream output = new FileOutputStream(destination, false)) {
            // Keep the write descriptor open, then make the pathname read-only before the first
            // byte is written. The already-open descriptor remains usable, while another process
            // cannot race us by reopening the app-private DCL path for modification.
            if (!destination.setReadOnly()) {
                throw new IOException("Could not make gesture library read-only before writing");
            }
            int count;
            while ((count = source.read(buffer)) != -1) {
                if (count > MAX_IMPORTED_GESTURE_LIBRARY_BYTES - total) {
                    throw new IOException("Gesture library exceeds the size limit");
                }
                output.write(buffer, 0, count);
                total += count;
            }
            output.flush();
            output.getFD().sync();
        }
    }

    private static boolean discardFile(final File file) {
        if (!file.exists()) return true;
        // A previous successful import is read-only by design; make it writable only to remove it.
        if (!file.setWritable(true)) return false;
        return file.delete();
    }

    private static int unsignedByte(final byte value) {
        return value & 0xff;
    }

    private static int readU16LE(final byte[] data, final int offset) {
        return unsignedByte(data[offset]) | (unsignedByte(data[offset + 1]) << 8);
    }

    private static long readU32LE(final byte[] data, final int offset) {
        return (long) unsignedByte(data[offset])
                | ((long) unsignedByte(data[offset + 1]) << 8)
                | ((long) unsignedByte(data[offset + 2]) << 16)
                | ((long) unsignedByte(data[offset + 3]) << 24);
    }

    private static long readU64LE(final byte[] data, final int offset) {
        long result = 0;
        for (int i = 7; i >= 0; i--) {
            result = (result << 8) | unsignedByte(data[offset + i]);
        }
        return result;
    }
}
