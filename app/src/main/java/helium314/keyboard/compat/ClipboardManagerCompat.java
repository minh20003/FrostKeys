// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.compat;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;

public class ClipboardManagerCompat {

    public static void clearPrimaryClip(ClipboardManager cm) {
        try {
            cm.clearPrimaryClip();
        } catch (Exception e) {
            // workaround for system-caused crash in https://github.com/HeliBorg/HeliBoard/issues/203
            cm.setPrimaryClip(ClipData.newPlainText("", ""));
        }
    }

    public static Long getClipTimestamp(ClipData cd) {
        final long timestamp = cd.getDescription().getTimestamp();
        return timestamp > 0 ? timestamp : System.currentTimeMillis();
    }

    public static Boolean getClipSensitivity(final ClipDescription cd) {
        return cd != null && cd.getExtras() != null
                && cd.getExtras().getBoolean("android.content.extra.IS_SENSITIVE");
    }
}
