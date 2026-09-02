/*
 * Copyright (C) 2015 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.latin.permissions;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.content.ContextCompat;

/**
 * Utility class for permissions.
 */
public class PermissionsUtil {
    /**
     * Queries if al the permissions are granted for the given permission strings.
     */
    public static boolean checkAllPermissionsGranted(Context context, String... permissions) {
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(context, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    /**
     * Permissions needed when the user explicitly enables screenshot monitoring.
     *
     * <p>On Android 14 the visual-user-selected permission lets us distinguish a selected-photo
     * grant from full-library access. The screenshot observer scans standard MediaStore folders,
     * so a selected-photo grant is deliberately not sufficient.</p>
     */
    public static String[] getScreenshotReadPermissionsToRequest() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return new String[] {
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            };
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return new String[] { Manifest.permission.READ_MEDIA_IMAGES };
        }
        return new String[] { Manifest.permission.READ_EXTERNAL_STORAGE };
    }

    /**
     * Returns whether the app currently has full image-library access, never merely selected
     * photos access. Call this immediately before querying screenshot media; permission state can
     * change while the IME is running.
     */
    public static boolean hasFullScreenshotReadPermission(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                && checkAllPermissionsGranted(
                        context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return checkAllPermissionsGranted(context, Manifest.permission.READ_MEDIA_IMAGES);
        }
        return checkAllPermissionsGranted(context, Manifest.permission.READ_EXTERNAL_STORAGE);
    }
}
