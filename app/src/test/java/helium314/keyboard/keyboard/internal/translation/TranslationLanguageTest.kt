// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.internal.translation

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TranslationLanguageTest {

    // Tests for fromTag() - exact matches
    @Test
    fun fromTag_returnsVietnamese_forVi() {
        assertSame(TranslationLanguage.VIETNAMESE, TranslationLanguage.fromTag("vi"))
    }

    @Test
    fun fromTag_returnsEnglish_forEn() {
        assertSame(TranslationLanguage.ENGLISH, TranslationLanguage.fromTag("en"))
    }

    @Test
    fun fromTag_returnsChineseSimplified_forZhHans() {
        assertSame(TranslationLanguage.CHINESE_SIMPLIFIED, TranslationLanguage.fromTag("zh-Hans"))
    }

    @Test
    fun fromTag_returnsChineseTraditional_forZhHant() {
        assertSame(TranslationLanguage.CHINESE_TRADITIONAL, TranslationLanguage.fromTag("zh-Hant"))
    }

    @Test
    fun fromTag_returnsJapanese_forJa() {
        assertSame(TranslationLanguage.JAPANESE, TranslationLanguage.fromTag("ja"))
    }

    @Test
    fun fromTag_returnsKorean_forKo() {
        assertSame(TranslationLanguage.KOREAN, TranslationLanguage.fromTag("ko"))
    }

    @Test
    fun fromTag_returnsThai_forTh() {
        assertSame(TranslationLanguage.THAI, TranslationLanguage.fromTag("th"))
    }

    @Test
    fun fromTag_returnsIndonesian_forId() {
        assertSame(TranslationLanguage.INDONESIAN, TranslationLanguage.fromTag("id"))
    }

    @Test
    fun fromTag_returnsFrench_forFr() {
        assertSame(TranslationLanguage.FRENCH, TranslationLanguage.fromTag("fr"))
    }

    @Test
    fun fromTag_returnsGerman_forDe() {
        assertSame(TranslationLanguage.GERMAN, TranslationLanguage.fromTag("de"))
    }

    @Test
    fun fromTag_returnsSpanish_forEs() {
        assertSame(TranslationLanguage.SPANISH, TranslationLanguage.fromTag("es"))
    }

    @Test
    fun fromTag_returnsPortuguese_forPt() {
        assertSame(TranslationLanguage.PORTUGUESE, TranslationLanguage.fromTag("pt"))
    }

    @Test
    fun fromTag_returnsRussian_forRu() {
        assertSame(TranslationLanguage.RUSSIAN, TranslationLanguage.fromTag("ru"))
    }

    @Test
    fun fromTag_returnsArabic_forAr() {
        assertSame(TranslationLanguage.ARABIC, TranslationLanguage.fromTag("ar"))
    }

    @Test
    fun fromTag_returnsHindi_forHi() {
        assertSame(TranslationLanguage.HINDI, TranslationLanguage.fromTag("hi"))
    }

    // Tests for fromTag() - regional variants
    @Test
    fun fromTag_returnsSimplified_forZhCN() {
        assertSame(TranslationLanguage.CHINESE_SIMPLIFIED, TranslationLanguage.fromTag("zh-CN"))
    }

    @Test
    fun fromTag_returnsSimplified_forZhSG() {
        assertSame(TranslationLanguage.CHINESE_SIMPLIFIED, TranslationLanguage.fromTag("zh-SG"))
    }

    @Test
    fun fromTag_returnsTraditional_forZhTW() {
        assertSame(TranslationLanguage.CHINESE_TRADITIONAL, TranslationLanguage.fromTag("zh-TW"))
    }

    @Test
    fun fromTag_returnsTraditional_forZhHK() {
        assertSame(TranslationLanguage.CHINESE_TRADITIONAL, TranslationLanguage.fromTag("zh-HK"))
    }

    // Tests for fromTag() - bare language codes
    @Test
    fun fromTag_returnsSimplified_forBareZh() {
        assertSame(TranslationLanguage.CHINESE_SIMPLIFIED, TranslationLanguage.fromTag("zh"))
    }

    @Test
    fun fromTag_returnsNull_forUnsupportedTags() {
        assertNull(TranslationLanguage.fromTag("zz"))
        assertNull(TranslationLanguage.fromTag("xyz"))
        assertNull(TranslationLanguage.fromTag(""))
        // Regional variants like en-US/fr-FR are not exact matches but
        // start with supported language codes, so they match those languages
        assertEquals(TranslationLanguage.ENGLISH, TranslationLanguage.fromTag("en-US"))
        assertEquals(TranslationLanguage.FRENCH, TranslationLanguage.fromTag("fr-FR"))
    }

    @Test
    fun fromTag_handlesWhitespace() {
        assertSame(TranslationLanguage.ENGLISH, TranslationLanguage.fromTag("  en  "))
    }

    // Tests for SOURCE_LANGUAGES and TARGET_LANGUAGES lists
    @Test
    fun sourceLanguages_includesAutoDetect() {
        assertTrue(TranslationLanguage.SOURCE_LANGUAGES.contains(TranslationLanguage.AUTO_DETECT))
    }

    @Test
    fun targetLanguages_excludesAutoDetect() {
        assertFalse(TranslationLanguage.TARGET_LANGUAGES.any { it.isAutoDetect })
    }

    @Test
    fun sourceLanguages_hasMoreEntriesThanTargetLanguages() {
        assertTrue(TranslationLanguage.SOURCE_LANGUAGES.size > TranslationLanguage.TARGET_LANGUAGES.size)
    }

    @Test
    fun allLanguages_containsBothAutoDetectAndVietnamese() {
        assertTrue(TranslationLanguage.ALL_LANGUAGES.contains(TranslationLanguage.AUTO_DETECT))
        assertTrue(TranslationLanguage.ALL_LANGUAGES.contains(TranslationLanguage.VIETNAMESE))
    }

    @Test
    fun sourceLanguages_equalsAllLanguages() {
        assertEquals(TranslationLanguage.ALL_LANGUAGES, TranslationLanguage.SOURCE_LANGUAGES)
    }

    @Test
    fun targetLanguages_hasCorrectCount() {
        // ALL_LANGUAGES has 16 entries (1 auto-detect + 15 languages)
        assertEquals(16, TranslationLanguage.ALL_LANGUAGES.size)
        assertEquals(16, TranslationLanguage.SOURCE_LANGUAGES.size)
        assertEquals(15, TranslationLanguage.TARGET_LANGUAGES.size)
    }

    // Tests for DEFAULT_SOURCE and DEFAULT_TARGET
    @Test
    fun defaultSource_isAutoDetect() {
        assertSame(TranslationLanguage.AUTO_DETECT, TranslationLanguage.DEFAULT_SOURCE)
    }

    @Test
    fun defaultTarget_isVietnamese() {
        assertSame(TranslationLanguage.VIETNAMESE, TranslationLanguage.DEFAULT_TARGET)
    }

    // Tests for isAutoDetect flag
    @Test
    fun autoDetect_hasIsAutoDetectTrue() {
        assertTrue(TranslationLanguage.AUTO_DETECT.isAutoDetect)
    }

    @Test
    fun otherLanguages_haveIsAutoDetectFalse() {
        val languages = listOf(
            TranslationLanguage.VIETNAMESE,
            TranslationLanguage.ENGLISH,
            TranslationLanguage.CHINESE_SIMPLIFIED,
            TranslationLanguage.CHINESE_TRADITIONAL,
            TranslationLanguage.JAPANESE,
            TranslationLanguage.KOREAN,
            TranslationLanguage.THAI,
            TranslationLanguage.INDONESIAN,
            TranslationLanguage.FRENCH,
            TranslationLanguage.GERMAN,
            TranslationLanguage.SPANISH,
            TranslationLanguage.PORTUGUESE,
            TranslationLanguage.RUSSIAN,
            TranslationLanguage.ARABIC,
            TranslationLanguage.HINDI,
        )
        for (lang in languages) {
            assertFalse(lang.isAutoDetect, "${lang.id} should have isAutoDetect=false")
        }
    }

    // Tests for stable IDs
    @Test
    fun languageIds_areStableBcP47Identifiers() {
        assertEquals("auto", TranslationLanguage.AUTO_DETECT.id)
        assertEquals("vi", TranslationLanguage.VIETNAMESE.id)
        assertEquals("en", TranslationLanguage.ENGLISH.id)
        assertEquals("zh-Hans", TranslationLanguage.CHINESE_SIMPLIFIED.id)
        assertEquals("zh-Hant", TranslationLanguage.CHINESE_TRADITIONAL.id)
        assertEquals("ja", TranslationLanguage.JAPANESE.id)
        assertEquals("ko", TranslationLanguage.KOREAN.id)
        assertEquals("th", TranslationLanguage.THAI.id)
        assertEquals("id", TranslationLanguage.INDONESIAN.id)
        assertEquals("fr", TranslationLanguage.FRENCH.id)
        assertEquals("de", TranslationLanguage.GERMAN.id)
        assertEquals("es", TranslationLanguage.SPANISH.id)
        assertEquals("pt", TranslationLanguage.PORTUGUESE.id)
        assertEquals("ru", TranslationLanguage.RUSSIAN.id)
        assertEquals("ar", TranslationLanguage.ARABIC.id)
        assertEquals("hi", TranslationLanguage.HINDI.id)
    }
}
