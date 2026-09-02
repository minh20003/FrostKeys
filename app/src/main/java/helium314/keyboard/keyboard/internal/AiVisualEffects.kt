// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.internal

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs

/**
 * Resolves the optional AI-panel animation to a small, device-appropriate budget.
 *
 * The preference is deliberately read when the panel starts an animation rather than cached:
 * toggling Power Saver or the animator scale while the IME stays alive must take effect for the
 * next request without an IME restart.
 */
internal object AiVisualEffects {
    internal data class Configuration(
        val enabled: Boolean,
        val particleBudget: Int,
    )

    private const val AUTO = "auto"
    private const val FULL = "full"
    private const val REDUCED = "reduced"
    private const val OFF = "off"
    private const val FULL_PARTICLE_BUDGET = 400
    private const val REDUCED_PARTICLE_BUDGET = 200

    fun current(context: Context): Configuration {
        return when (context.prefs().getString(
            Settings.PREF_AI_VISUAL_EFFECTS,
            Defaults.PREF_AI_VISUAL_EFFECTS,
        )) {
            OFF -> Configuration(enabled = false, particleBudget = 0)
            REDUCED -> Configuration(enabled = true, particleBudget = REDUCED_PARTICLE_BUDGET)
            FULL -> Configuration(enabled = true, particleBudget = FULL_PARTICLE_BUDGET)
            else -> automatic(context)
        }
    }

    private fun automatic(context: Context): Configuration {
        val powerManager = context.getSystemService(PowerManager::class.java)
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val animationScale = runCatching {
            AndroidSettings.Global.getFloat(
                context.contentResolver,
                AndroidSettings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            )
        }.getOrDefault(1f)
        val thermalSevere = powerManager?.currentThermalStatus
            ?.let { it >= PowerManager.THERMAL_STATUS_SEVERE }
            ?: false

        // Respect explicit reduced-motion, Power Saver, and thermal pressure by avoiding an
        // always-running animation entirely. Low-RAM devices retain only a modest 200-particle
        // effect so the panel still has visual feedback without keeping a large canvas hot.
        if (animationScale == 0f || powerManager?.isPowerSaveMode == true || thermalSevere) {
            return Configuration(enabled = false, particleBudget = 0)
        }
        if (activityManager?.isLowRamDevice == true) {
            return Configuration(enabled = true, particleBudget = REDUCED_PARTICLE_BUDGET)
        }
        return Configuration(enabled = true, particleBudget = FULL_PARTICLE_BUDGET)
    }
}
