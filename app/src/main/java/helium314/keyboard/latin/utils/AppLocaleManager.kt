// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.core.os.LocaleListCompat

/** App-language policy for the personal Vietnamese-first build. */
object AppLocaleManager {
    const val VIETNAMESE = "vi"
    const val ENGLISH = "en"
    const val JAPANESE = "ja"
    const val CHINESE_SIMPLIFIED = "zh-CN"
    const val CHINESE_TRADITIONAL = "zh-TW"
    const val KOREAN = "ko"
    const val THAI = "th"

    private const val PREF_APP_LOCALE_INITIALIZED = "app_locale_initialized"
    private const val PREF_APP_LOCALE = "app_locale"
    private val supportedTags = setOf(
        VIETNAMESE,
        ENGLISH,
        JAPANESE,
        CHINESE_SIMPLIFIED,
        CHINESE_TRADITIONAL,
        KOREAN,
        THAI,
    )

    /**
     * Sets Vietnamese on a fresh install and keeps only the seven UI locales shipped by this
     * personal build.  There is deliberately no "follow system" option: otherwise a new install
     * on an English device would silently violate the Vietnamese-first policy.
     */
    fun initialize(context: Context) {
        val prefs = context.prefs()
        val existing = AppCompatDelegate.getApplicationLocales()
        if (prefs.getBoolean(PREF_APP_LOCALE_INITIALIZED, false)) {
            val stored = prefs.getString(PREF_APP_LOCALE, VIETNAMESE)
                .orEmpty()
                .takeIf { it in supportedTags }
                ?: VIETNAMESE
            // AppCompat persists locales through the platform on Android 13+, but on Android
            // 12L/12 it can start with an empty list after process death. Restore our explicit
            // choice in that case without overriding a non-empty platform choice.
            if (existing.isEmpty) {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(stored))
            } else if (!existing.isEmpty && existing.toLanguageTags() in supportedTags
                && existing.toLanguageTags() != stored
            ) {
                prefs.edit { putString(PREF_APP_LOCALE, existing.toLanguageTags()) }
            } else if (!existing.isEmpty && existing.toLanguageTags() !in supportedTags) {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(stored))
            }
            return
        }
        val initialTag = existing.toLanguageTags().takeIf { it in supportedTags } ?: VIETNAMESE
        if (existing.isEmpty || existing.toLanguageTags() !in supportedTags) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(initialTag))
        }
        prefs.edit {
            putBoolean(PREF_APP_LOCALE_INITIALIZED, true)
            putString(PREF_APP_LOCALE, initialTag)
        }
    }

    fun currentTag(context: Context): String {
        val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        return current.takeIf { it in supportedTags }
            ?: context.prefs().getString(PREF_APP_LOCALE, VIETNAMESE)
                .orEmpty()
                .takeIf { it in supportedTags }
            ?: VIETNAMESE
    }

    fun setLocale(context: Context, tag: String) {
        require(tag in supportedTags) { "Unsupported app locale: $tag" }
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
        context.prefs().edit {
            putBoolean(PREF_APP_LOCALE_INITIALIZED, true)
            putString(PREF_APP_LOCALE, tag)
        }
    }
}
