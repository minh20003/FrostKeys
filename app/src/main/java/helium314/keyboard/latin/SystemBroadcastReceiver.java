/*
 * Copyright (C) 2014 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.latin;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import helium314.keyboard.latin.utils.Log;

import helium314.keyboard.keyboard.KeyboardLayoutSet;

/**
 * Receives the protected system-locale change broadcast and clears the
 * {@link KeyboardLayoutSet} cache. The personal Android 12+ build deliberately does not run at
 * boot or package replacement: its launcher icon is static, so those legacy broadcasts provided
 * no useful work while requiring an unnecessary permission and exported receiver.
 */
public final class SystemBroadcastReceiver extends BroadcastReceiver {
    private static final String TAG = SystemBroadcastReceiver.class.getSimpleName();

    @Override
    public void onReceive(final Context context, final Intent intent) {
        final String intentAction = intent.getAction();
        if (Intent.ACTION_LOCALE_CHANGED.equals(intentAction)) {
            Log.i(TAG, "System locale changed");
            KeyboardLayoutSet.onSystemLocaleChanged();
        }

        // Do not kill this process from inside a manifest receiver. Android can redeliver a
        // broadcast whose receiver process disappears during onReceive(), which caused repeated
        // process start/kill loops after Android Studio installs on some devices.
    }

    public static void toggleAppIcon(final Context context) {
        // Android 12+ disallows changing launcher-component visibility at runtime.
    }
}
