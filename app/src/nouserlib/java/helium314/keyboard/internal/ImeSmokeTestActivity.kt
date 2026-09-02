// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.internal

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.Constants.Subtype.ExtraValue
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.prefs
import java.util.Locale

/**
 * A neutral, offline editor used only by the `nouserlib` device-smoke APK.
 *
 * Keeping this outside the release source set lets the IME be tested against text, credential,
 * URI, email and numeric input attributes without opening an app that can expose user data.
 */
class ImeSmokeTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        setContentView(R.layout.activity_ime_smoke_test)

        val generalText = findViewById<EditText>(R.id.ime_smoke_text)
        findViewById<Button>(R.id.ime_smoke_select_telex).setOnClickListener {
            selectSubtype(Locale.forLanguageTag("vi"), ExtraValue.COMBINING_RULES, "vi_telex")
        }
        findViewById<Button>(R.id.ime_smoke_select_vni).setOnClickListener {
            selectSubtype(Locale.forLanguageTag("vi"), ExtraValue.COMBINING_RULES, "vi_vni")
        }
        generalText.requestFocus()
        generalText.post {
            getSystemService(InputMethodManager::class.java)
                .showSoftInput(generalText, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun selectSubtype(locale: Locale, extraValueKey: String, extraValue: String) {
        val subtype = SubtypeSettings.getResourceSubtypesForLocale(locale)
            .firstOrNull { it.toSettingsSubtype().getExtraValueOf(extraValueKey) == extraValue }
            ?: return
        if (!SubtypeSettings.isEnabled(subtype)) {
            SubtypeSettings.addEnabledSubtype(applicationContext.prefs(), subtype)
        }
        // Normal settings changes are applied when the IME begins its next editor session. Do
        // the same here instead of asking an Activity to call InputMethodService.switchInputMethod
        // directly; that system-only transition can detach the currently focused test editor.
        SubtypeSettings.setSelectedSubtype(applicationContext.prefs(), subtype)
        finish()
    }
}
