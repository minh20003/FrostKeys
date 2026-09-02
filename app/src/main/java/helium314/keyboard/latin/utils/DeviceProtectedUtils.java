/*
 * Copyright (C) 2012 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.latin.utils;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class DeviceProtectedUtils {

    static final String TAG = DeviceProtectedUtils.class.getSimpleName();
    private static SharedPreferences prefs;

    public static SharedPreferences getSharedPreferences(final Context context) {
        if (prefs != null)
            return prefs;
        final Context deviceProtectedContext = getDeviceProtectedContext(context);
        prefs = getDefaultSharedPreferences(deviceProtectedContext);
        if (prefs.getAll() == null)
            return prefs; // happens for compose previews
        if (prefs.getAll().isEmpty()) {
            // Do not use moveSharedPreferencesFrom(): old releases stored cloud API keys in the
            // credential-protected default preferences, and moving the whole file would briefly
            // put those plaintext values into storage available before first unlock.  Copy only
            // non-secret settings; CloudManager migrates legacy credentials directly from the
            // credential-protected source into Keystore-backed storage on its later startup.
            final SharedPreferences credentialPrefs = getDefaultSharedPreferences(context);
            try {
                final Map<String, ?> credentialValues = credentialPrefs.getAll();
                if (!credentialValues.isEmpty()) {
                    final SharedPreferences.Editor editor = prefs.edit();
                    copyNonSensitivePreferences(credentialValues, editor);
                    // The editor is immediately visible to this process; using apply() keeps
                    // IME startup off the main-thread disk-write path.
                    editor.apply();
                    Log.i(TAG, "Initialized device-protected preferences without credentials");
                }
            } catch (SecurityException ignored) {
                // Credential storage is unavailable before the first device unlock. It will be
                // copied safely on the first later call after unlock.
            }
        }
        return prefs;
    }

    /**
     * Copies the supported SharedPreferences value types while withholding every legacy cloud
     * secret. Package-visible for a pure unit regression test; callers must commit the editor.
     */
    static void copyNonSensitivePreferences(final Map<String, ?> source, final SharedPreferences.Editor editor) {
        for (final Map.Entry<String, ?> entry : source.entrySet()) {
            final String key = entry.getKey();
            if (isSensitivePreferenceKey(key)) continue;
            final Object value = entry.getValue();
            if (value instanceof Boolean) {
                editor.putBoolean(key, (Boolean) value);
            } else if (value instanceof Float) {
                editor.putFloat(key, (Float) value);
            } else if (value instanceof Integer) {
                editor.putInt(key, (Integer) value);
            } else if (value instanceof Long) {
                editor.putLong(key, (Long) value);
            } else if (value instanceof String) {
                editor.putString(key, (String) value);
            } else if (value instanceof Set<?>) {
                final Set<String> stringSet = new HashSet<>();
                for (final Object item : (Set<?>) value) {
                    if (item instanceof String) stringSet.add((String) item);
                }
                editor.putStringSet(key, stringSet);
            }
        }
    }

    private static boolean isSensitivePreferenceKey(final String key) {
        return "pref_gemini_api_key".equals(key)
                || "pref_klipy_api_key".equals(key)
                || "klipy_customer_id".equals(key);
    }

    // keep this private to avoid accidental use of device protected context anywhere in the app
    private static Context getDeviceProtectedContext(final Context context) {
        final Context ctx = context.isDeviceProtectedStorage() ? context : context.createDeviceProtectedStorageContext();
        if (ctx == null) return context; // happens for compose previews
        else return ctx;
    }

    private static SharedPreferences getDefaultSharedPreferences(Context context) {
        // from androidx.preference.PreferenceManager
        return context.getSharedPreferences(context.getPackageName() + "_preferences", Context.MODE_PRIVATE);
    }

    public static File getFilesDir(final Context context) {
        return getDeviceProtectedContext(context).getFilesDir();
    }

    private DeviceProtectedUtils() {
        // This utility class is not publicly instantiable.
    }
}
