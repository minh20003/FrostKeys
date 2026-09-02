// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.internal.translation

/**
 * Represents a supported translation language with stable BCP-47 identifier.
 * Keep stable IDs separate from localized names.
 */
data class TranslationLanguage(
    val id: String,           // Stable BCP-47 identifier (e.g., "vi", "en", "zh-Hans")
    val isAutoDetect: Boolean = false  // True only for Auto Detect (source only)
) {
    companion object {
        val AUTO_DETECT = TranslationLanguage("auto", isAutoDetect = true)

        val VIETNAMESE = TranslationLanguage("vi")
        val ENGLISH = TranslationLanguage("en")
        val CHINESE_SIMPLIFIED = TranslationLanguage("zh-Hans")
        val CHINESE_TRADITIONAL = TranslationLanguage("zh-Hant")
        val JAPANESE = TranslationLanguage("ja")
        val KOREAN = TranslationLanguage("ko")
        val THAI = TranslationLanguage("th")
        val INDONESIAN = TranslationLanguage("id")
        val FRENCH = TranslationLanguage("fr")
        val GERMAN = TranslationLanguage("de")
        val SPANISH = TranslationLanguage("es")
        val PORTUGUESE = TranslationLanguage("pt")
        val RUSSIAN = TranslationLanguage("ru")
        val ARABIC = TranslationLanguage("ar")
        val HINDI = TranslationLanguage("hi")

        /**
         * All supported languages for the UI.
         * Auto detect is source-only.
         */
        val ALL_LANGUAGES = listOf(
            AUTO_DETECT,
            VIETNAMESE,
            ENGLISH,
            CHINESE_SIMPLIFIED,
            CHINESE_TRADITIONAL,
            JAPANESE,
            KOREAN,
            THAI,
            INDONESIAN,
            FRENCH,
            GERMAN,
            SPANISH,
            PORTUGUESE,
            RUSSIAN,
            ARABIC,
            HINDI
        )

        /**
         * Languages available for source selection (includes Auto detect).
         */
        val SOURCE_LANGUAGES = ALL_LANGUAGES

        /**
         * Languages available for target selection (excludes Auto detect).
         */
        val TARGET_LANGUAGES = ALL_LANGUAGES.filter { !it.isAutoDetect }

        /**
         * Default source language.
         */
        val DEFAULT_SOURCE = AUTO_DETECT

        /**
         * Default target language.
         */
        val DEFAULT_TARGET = VIETNAMESE

        /**
         * Map BCP-47 language tag to TranslationLanguage.
         * Used for normalizing detected language tags.
         */
        fun fromTag(tag: String): TranslationLanguage? {
            val normalized = tag.trim()
            return when {
                // Exact matches
                normalized == "vi" -> VIETNAMESE
                normalized == "en" -> ENGLISH
                normalized == "zh-Hans" || normalized == "zh-CN" || normalized == "zh-SG" -> CHINESE_SIMPLIFIED
                normalized == "zh-Hant" || normalized == "zh-TW" || normalized == "zh-HK" -> CHINESE_TRADITIONAL
                normalized == "ja" -> JAPANESE
                normalized == "ko" -> KOREAN
                normalized == "th" -> THAI
                normalized == "id" -> INDONESIAN
                normalized == "fr" -> FRENCH
                normalized == "de" -> GERMAN
                normalized == "es" -> SPANISH
                normalized == "pt" -> PORTUGUESE
                normalized == "ru" -> RUSSIAN
                normalized == "ar" -> ARABIC
                normalized == "hi" -> HINDI
                // Language code only matches (base tag without script)
                normalized.startsWith("vi") -> VIETNAMESE
                normalized.startsWith("en") -> ENGLISH
                // Bare "zh" defaults to Simplified Chinese (most common globally)
                normalized.startsWith("zh") -> CHINESE_SIMPLIFIED
                normalized.startsWith("ja") -> JAPANESE
                normalized.startsWith("ko") -> KOREAN
                normalized.startsWith("th") -> THAI
                normalized.startsWith("id") -> INDONESIAN
                normalized.startsWith("fr") -> FRENCH
                normalized.startsWith("de") -> GERMAN
                normalized.startsWith("es") -> SPANISH
                normalized.startsWith("pt") -> PORTUGUESE
                normalized.startsWith("ru") -> RUSSIAN
                normalized.startsWith("ar") -> ARABIC
                normalized.startsWith("hi") -> HINDI
                else -> null
            }
        }
    }
}
