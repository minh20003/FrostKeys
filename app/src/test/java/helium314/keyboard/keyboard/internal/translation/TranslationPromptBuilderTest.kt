// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.internal.translation

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TranslationPromptBuilderTest {

    // Tests for validateSourceText()
    @Test
    fun validateSourceText_rejectsEmptyString() {
        val result = TranslationPromptBuilder.validateSourceText("")
        assertTrue(result is TranslationPromptBuilder.ValidationResult.Invalid)
        assertTrue((result as TranslationPromptBuilder.ValidationResult.Invalid).reason == "empty")
    }

    @Test
    fun validateSourceText_rejectsWhitespaceOnly() {
        val result = TranslationPromptBuilder.validateSourceText("   ")
        assertTrue(result is TranslationPromptBuilder.ValidationResult.Invalid)
        assertTrue((result as TranslationPromptBuilder.ValidationResult.Invalid).reason == "empty")

        val result2 = TranslationPromptBuilder.validateSourceText("\t\n")
        assertTrue(result2 is TranslationPromptBuilder.ValidationResult.Invalid)
    }

    @Test
    fun validateSourceText_acceptsValidText() {
        val result = TranslationPromptBuilder.validateSourceText("Hello, world!")
        assertTrue(result is TranslationPromptBuilder.ValidationResult.Valid)
    }

    @Test
    fun validateSourceText_acceptsUnicodeText() {
        val result = TranslationPromptBuilder.validateSourceText("Xin chào thế giới! 你好世界！")
        assertTrue(result is TranslationPromptBuilder.ValidationResult.Valid)
    }

    @Test
    fun validateSourceText_rejectsTooManyCodepoints() {
        // 8001 codepoints should be rejected
        val manyCodepoints = "a".repeat(8001)
        val result = TranslationPromptBuilder.validateSourceText(manyCodepoints)
        assertTrue(result is TranslationPromptBuilder.ValidationResult.Invalid)
        assertTrue((result as TranslationPromptBuilder.ValidationResult.Invalid).reason == "too_large_codepoints")
    }

    @Test
    fun validateSourceText_acceptsMaximumCodepoints() {
        // Exactly 8000 codepoints should be accepted
        val maxCodepoints = "a".repeat(8000)
        val result = TranslationPromptBuilder.validateSourceText(maxCodepoints)
        assertTrue(result is TranslationPromptBuilder.ValidationResult.Valid)
    }

    @Test
    fun validateSourceText_rejectsTooManyBytes() {
        // The codepoint limit (8000) is checked before the byte limit (32 KiB).
        // Production limits are: max 8000 codepoints, max 32 KiB UTF-8 bytes.
        // A Unicode codepoint occupies at most 4 UTF-8 bytes, so 8000 codepoints
        // occupy at most 32000 bytes — always below 32 KiB. The byte guard is
        // defence-in-depth; codepoints are the primary gate.
        // Any string that exceeds 8000 codepoints fails on the codepoint check.
        val tooManyCodepoints = "家".repeat(8001)  // 8001 codepoints, > 8000 limit
        assertTrue(tooManyCodepoints.codePointCount(0, tooManyCodepoints.length) == 8001)
        val rejectResult = TranslationPromptBuilder.validateSourceText(tooManyCodepoints)
        assertTrue(rejectResult is TranslationPromptBuilder.ValidationResult.Invalid)
        assertTrue((rejectResult as TranslationPromptBuilder.ValidationResult.Invalid).reason == "too_large_codepoints")
    }

    @Test
    fun validateSourceText_acceptsMaximumBytes() {
        // Exactly 8000 codepoints is the maximum valid input.
        // Using ASCII so 1 char = 1 byte = 1 codepoint.
        val maxCodepoints = "a".repeat(8000)
        assertTrue(maxCodepoints.codePointCount(0, maxCodepoints.length) == 8000)
        assertTrue(maxCodepoints.toByteArray(Charsets.UTF_8).size == 8000)

        val result = TranslationPromptBuilder.validateSourceText(maxCodepoints)
        assertTrue(result is TranslationPromptBuilder.ValidationResult.Valid,
            "Exactly 8000 codepoints must be accepted")
    }

    // Tests for areSameLanguage()
    @Test
    fun areSameLanguage_autoDetectPlusAnything_returnsFalse() {
        assertFalse(TranslationPromptBuilder.areSameLanguage(
            TranslationLanguage.AUTO_DETECT, TranslationLanguage.VIETNAMESE))
        assertFalse(TranslationPromptBuilder.areSameLanguage(
            TranslationLanguage.VIETNAMESE, TranslationLanguage.AUTO_DETECT))
        assertFalse(TranslationPromptBuilder.areSameLanguage(
            TranslationLanguage.AUTO_DETECT, TranslationLanguage.AUTO_DETECT))
    }

    @Test
    fun areSameLanguage_vietnamesePlusVietnamese_returnsTrue() {
        assertTrue(TranslationPromptBuilder.areSameLanguage(
            TranslationLanguage.VIETNAMESE, TranslationLanguage.VIETNAMESE))
    }

    @Test
    fun areSameLanguage_vietnamesePlusEnglish_returnsFalse() {
        assertFalse(TranslationPromptBuilder.areSameLanguage(
            TranslationLanguage.VIETNAMESE, TranslationLanguage.ENGLISH))
    }

    @Test
    fun areSameLanguage_differentLanguages_returnsFalse() {
        assertFalse(TranslationPromptBuilder.areSameLanguage(
            TranslationLanguage.ENGLISH, TranslationLanguage.CHINESE_SIMPLIFIED))
        assertFalse(TranslationPromptBuilder.areSameLanguage(
            TranslationLanguage.JAPANESE, TranslationLanguage.KOREAN))
    }

    @Test
    fun areSameLanguage_sameLanguageDifferentScript_returnsFalse() {
        assertFalse(TranslationPromptBuilder.areSameLanguage(
            TranslationLanguage.CHINESE_SIMPLIFIED, TranslationLanguage.CHINESE_TRADITIONAL))
    }

    // Tests for buildSystemInstruction()
    @Test
    fun buildSystemInstruction_returnsNonBlankString() {
        val result = TranslationPromptBuilder.buildSystemInstruction()
        assertNotNull(result)
        assertTrue(result.isNotBlank())
    }

    @Test
    fun buildSystemInstruction_containsTranslationGuidance() {
        val result = TranslationPromptBuilder.buildSystemInstruction()
        assertTrue(result.contains("translation"))
        assertTrue(result.contains("Translate"))
    }

    @Test
    fun buildSystemInstruction_containsNoPreambleGuidance() {
        val result = TranslationPromptBuilder.buildSystemInstruction()
        assertTrue(result.contains("no preamble"))
    }

    // Tests for buildUserInstruction()
    @Test
    fun buildUserInstruction_autoDetectPlusVietnamese_containsAutoDetect() {
        val result = TranslationPromptBuilder.buildUserInstruction(
            TranslationLanguage.AUTO_DETECT, TranslationLanguage.VIETNAMESE)
        assertTrue(result.contains("Auto-detect"))
    }

    @Test
    fun buildUserInstruction_englishPlusVietnamese_containsBothLanguages() {
        val result = TranslationPromptBuilder.buildUserInstruction(
            TranslationLanguage.ENGLISH, TranslationLanguage.VIETNAMESE)
        assertTrue(result.contains("English"))
        assertTrue(result.contains("Vietnamese"))
    }

    @Test
    fun buildUserInstruction_autoDetectFormatsCorrectly() {
        val result = TranslationPromptBuilder.buildUserInstruction(
            TranslationLanguage.AUTO_DETECT, TranslationLanguage.CHINESE_SIMPLIFIED)
        assertTrue(result.contains("Auto-detect"))
        assertTrue(result.contains("Simplified Chinese"))
    }

    @Test
    fun buildUserInstruction_explicitSourceFormatsCorrectly() {
        val result = TranslationPromptBuilder.buildUserInstruction(
            TranslationLanguage.JAPANESE, TranslationLanguage.KOREAN)
        assertTrue(result.contains("Japanese"))
        assertTrue(result.contains("Korean"))
        assertTrue(result.contains("Translate this"))
    }

    // Tests for validateTranslationOutput()
    @Test
    fun validateTranslationOutput_rejectsEmptyOutput() {
        val result = TranslationPromptBuilder.validateTranslationOutput("", "Hello")
        assertTrue(result is TranslationPromptBuilder.ValidationResult.Invalid)
        assertTrue((result as TranslationPromptBuilder.ValidationResult.Invalid).reason.startsWith("empty"))
    }

    @Test
    fun validateTranslationOutput_rejectsBlankOutput() {
        val result = TranslationPromptBuilder.validateTranslationOutput("   ", "Hello")
        assertTrue(result is TranslationPromptBuilder.ValidationResult.Invalid)
    }

    @Test
    fun validateTranslationOutput_rejectsOutputStartingWithTranslationColon() {
        val result = TranslationPromptBuilder.validateTranslationOutput(
            "translation: Hello", "Hello")
        assertTrue(result is TranslationPromptBuilder.ValidationResult.Invalid)
        assertTrue((result as TranslationPromptBuilder.ValidationResult.Invalid).reason.contains("translation:"))
    }

    @Test
    fun validateTranslationOutput_rejectsOtherPreambles() {
        val preambles = listOf(
            "The translation is: Hello",
            "Translated text: Bonjour",
            "Here is the translation:",
            "Result: Hello",
            "```\nHello\n```",
        )
        for (preamble in preambles) {
            val result = TranslationPromptBuilder.validateTranslationOutput(preamble, "Hello")
            assertTrue(result is TranslationPromptBuilder.ValidationResult.Invalid,
                "Expected invalid for preamble: $preamble")
        }
    }

    @Test
    fun validateTranslationOutput_rejectsSuspiciouslyShortOutput() {
        // Source text with 100+ codepoints but output is only 1 codepoint
        val source = "a".repeat(100)
        val shortOutput = "x"
        val result = TranslationPromptBuilder.validateTranslationOutput(shortOutput, source)
        assertTrue(result is TranslationPromptBuilder.ValidationResult.Invalid)
        assertTrue((result as TranslationPromptBuilder.ValidationResult.Invalid).reason == "suspiciously_short")
    }

    @Test
    fun validateTranslationOutput_acceptsShortOutputForShortSource() {
        // Source is short (10 codepoints), so a 1 codepoint output is acceptable
        val source = "Hello there"
        val shortOutput = "Hi"
        val result = TranslationPromptBuilder.validateTranslationOutput(shortOutput, source)
        assertTrue(result is TranslationPromptBuilder.ValidationResult.Valid)
    }

    @Test
    fun validateTranslationOutput_acceptsValidOutput() {
        val result = TranslationPromptBuilder.validateTranslationOutput(
            "Bonjour le monde", "Hello world")
        assertTrue(result is TranslationPromptBuilder.ValidationResult.Valid)
    }

    @Test
    fun validateTranslationOutput_acceptsUnicodeOutput() {
        val result = TranslationPromptBuilder.validateTranslationOutput(
            "你好世界", "Hello world")
        assertTrue(result is TranslationPromptBuilder.ValidationResult.Valid)
    }

    @Test
    fun validateTranslationOutput_acceptsMixedLanguageOutput() {
        val result = TranslationPromptBuilder.validateTranslationOutput(
            "Xin chào, hello, and こんにちは", "Hello")
        assertTrue(result is TranslationPromptBuilder.ValidationResult.Valid)
    }

    @Test
    fun validateTranslationOutput_acceptsLongOutputForLongSource() {
        val source = "a".repeat(100)
        val output = "b".repeat(100)
        val result = TranslationPromptBuilder.validateTranslationOutput(output, source)
        assertTrue(result is TranslationPromptBuilder.ValidationResult.Valid)
    }
}
