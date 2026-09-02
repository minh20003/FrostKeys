// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.compat

import android.app.KeyguardManager
import android.content.Context
import android.os.UserManager

fun isDeviceLocked(context: Context): Boolean {
    val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    return keyguardManager.isDeviceLocked
}

fun isUserLocked(context: Context): Boolean {
    val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
    return !userManager.isUserUnlocked
}
