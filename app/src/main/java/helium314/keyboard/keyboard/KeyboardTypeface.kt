// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.keyboard

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.compose.ui.text.font.FontFamily
import helium314.keyboard.latin.common.isEmoji
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.TypefaceUtils

object KeyboardTypeface {
    private const val EMOJI_PROBE_TEXT_SIZE = 64f
    private const val MAX_JOINED_EMOJI_WIDTH_RATIO = 1.85f
    private const val VARIATION_SELECTOR_16 = "\uFE0F"
    private const val VIETNAMESE_LATIN_1_GLYPHS = "ÀÁÂÃÈÉÊÌÍÒÓÔÕÙÚÝàáâãèéêìíòóôõùúý"
    private val vietnameseDistinctCodePoints = intArrayOf(
        0x0102, 0x0103, // Ă, ă
        0x0110, 0x0111, // Đ, đ
        0x01A0, 0x01A1, // Ơ, ơ
        0x01AF, 0x01B0, // Ư, ư
    )
    private val lock = Any()
    private val emojiProbePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val vietnameseGlyphProbePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var cachedCustomTypeface: Typeface? = null
    private var cachedCustomFontFamily: FontFamily? = null
    private val cachedStyledCustomTypefaces = HashMap<Int, Typeface>()
    private var customTypefaceSupportsVietnamese = true
    @Volatile
    private var customTypefaceLoaded = false

    private var cachedVietnameseFallbackTypeface: Typeface? = null
    private val cachedStyledVietnameseFallbackTypefaces = HashMap<Int, Typeface>()
    @Volatile
    private var vietnameseFallbackTypefaceLoaded = false

    private var cachedEmojiTypeface: Typeface? = null
    @Volatile
    private var emojiTypefaceLoaded = false

    private fun loadCustomTypeface(context: Context): Typeface? {
        return runCatching {
            Typeface.createFromFile(Settings.getCustomFontFile(context))
        }.getOrNull()
    }

    private fun loadCustomEmojiTypeface(context: Context): Typeface? {
        return runCatching {
            Typeface.createFromFile(Settings.getCustomEmojiFontFile(context))
        }.getOrNull()
    }

    @JvmStatic
    fun customTypeface(): Typeface? {
        if (customTypefaceLoaded) return cachedCustomTypeface
        val context = Settings.getCurrentContext() ?: return null
        synchronized(lock) {
            if (!customTypefaceLoaded) {
                cachedCustomTypeface = loadCustomTypeface(context)
                cachedCustomFontFamily = cachedCustomTypeface?.let(::FontFamily)
                customTypefaceSupportsVietnamese = cachedCustomTypeface
                    ?.let(::supportsVietnameseGlyphs)
                    ?: true
                customTypefaceLoaded = true
            }
            return cachedCustomTypeface
        }
    }

    @JvmStatic
    fun emojiTypeface(): Typeface? {
        if (emojiTypefaceLoaded) return cachedEmojiTypeface
        val context = Settings.getCurrentContext() ?: return null
        synchronized(lock) {
            if (!emojiTypefaceLoaded) {
                cachedEmojiTypeface = loadCustomEmojiTypeface(context)
                emojiTypefaceLoaded = true
            }
            return cachedEmojiTypeface
        }
    }

    @JvmStatic
    fun customFontFamily(): FontFamily? {
        if (!customTypefaceLoaded) customTypeface()
        // Compose typography is selected for an entire screen rather than per Text node. Do not
        // return a partial custom family here: a Vietnamese label could otherwise bypass
        // resolve(text) and render as a missing-glyph box. Callers already fall back to bundled
        // Google Sans Flex when this is null.
        return cachedCustomFontFamily?.takeIf { customTypefaceSupportsVietnamese }
    }

    @JvmStatic
    fun resolve(
        text: CharSequence?,
        defaultTypeface: Typeface = Typeface.DEFAULT,
    ): Typeface {
        val emojiTypeface = emojiTypeface()
        if (emojiTypeface != null && text != null && isEmoji(text)) {
            return if (canUseCustomEmojiTypeface(text, emojiTypeface)) emojiTypeface else defaultTypeface
        }
        val custom = customTypeface() ?: return defaultTypeface
        if (text != null && containsVietnameseGlyph(text) && !customTypefaceSupportsVietnamese) {
            return vietnameseFallbackTypeface(defaultTypeface)
        }
        return if (defaultTypeface.style != Typeface.NORMAL) {
            synchronized(lock) {
                cachedStyledCustomTypefaces.getOrPut(defaultTypeface.style) {
                    Typeface.create(custom, defaultTypeface.style)
                }
            }
        } else {
            custom
        }
    }

    /**
     * A custom keyboard font is optional, but Vietnamese labels must remain readable. Check the
     * complete Vietnamese character set once when the font is loaded and use the bundled Google
     * Sans Flex fallback for Vietnamese text if the custom file does not cover it.
     */
    private fun supportsVietnameseGlyphs(typeface: Typeface): Boolean {
        synchronized(vietnameseGlyphProbePaint) {
            vietnameseGlyphProbePaint.typeface = typeface
            vietnameseGlyphProbePaint.textSize = EMOJI_PROBE_TEXT_SIZE
            for (glyph in VIETNAMESE_LATIN_1_GLYPHS) {
                if (!vietnameseGlyphProbePaint.hasGlyph(glyph.toString())) return false
            }
            for (codePoint in vietnameseDistinctCodePoints) {
                if (!vietnameseGlyphProbePaint.hasGlyph(String(Character.toChars(codePoint)))) return false
            }
            for (codePoint in 0x1EA0..0x1EF9) {
                if (!vietnameseGlyphProbePaint.hasGlyph(String(Character.toChars(codePoint)))) return false
            }
            return true
        }
    }

    private fun containsVietnameseGlyph(text: CharSequence): Boolean {
        val value = text.toString()
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            if (
                codePoint in 0x1EA0..0x1EF9 ||
                codePoint in vietnameseDistinctCodePoints ||
                (codePoint <= Char.MAX_VALUE.code &&
                    VIETNAMESE_LATIN_1_GLYPHS.indexOf(codePoint.toChar()) >= 0)
            ) {
                return true
            }
            index += Character.charCount(codePoint)
        }
        return false
    }

    private fun vietnameseFallbackTypeface(defaultTypeface: Typeface): Typeface {
        val context = Settings.getCurrentContext() ?: return defaultTypeface
        synchronized(lock) {
            if (!vietnameseFallbackTypefaceLoaded) {
                cachedVietnameseFallbackTypeface = runCatching {
                    ResourcesCompat.getFont(context, R.font.google_sans_flex)
                }.getOrNull()
                vietnameseFallbackTypefaceLoaded = true
            }
            val fallback = cachedVietnameseFallbackTypeface ?: return defaultTypeface
            return if (defaultTypeface.style != Typeface.NORMAL) {
                cachedStyledVietnameseFallbackTypefaces.getOrPut(defaultTypeface.style) {
                    Typeface.create(fallback, defaultTypeface.style)
                }
            } else {
                fallback
            }
        }
    }

    @JvmStatic
    fun applyToTextView(textView: TextView) {
        applyToTextView(textView, textView.text, Typeface.DEFAULT)
    }

    @JvmStatic
    fun applyToTextView(textView: TextView, text: CharSequence?, defaultTypeface: Typeface) {
        textView.typeface = resolve(text, defaultTypeface = defaultTypeface)
    }

    @JvmStatic
    fun labelForDrawing(text: String, resolvedTypeface: Typeface?): String {
        val emojiTypeface = cachedEmojiTypeface ?: return text
        if (resolvedTypeface != emojiTypeface || !text.contains(VARIATION_SELECTOR_16)) return text
        val normalized = normalizeEmojiForCustomFont(text)
        return if (canRenderCustomEmoji(normalized, emojiTypeface)) normalized else text
    }

    private fun canUseCustomEmojiTypeface(text: CharSequence, typeface: Typeface): Boolean {
        val emoji = text.toString()
        if (canRenderCustomEmoji(emoji, typeface)) return true
        if (!emoji.contains(VARIATION_SELECTOR_16)) return false
        return canRenderCustomEmoji(normalizeEmojiForCustomFont(emoji), typeface)
    }

    private fun normalizeEmojiForCustomFont(text: String): String {
        return text.replace(VARIATION_SELECTOR_16, "")
    }

    private fun canRenderCustomEmoji(emoji: String, typeface: Typeface): Boolean {
        synchronized(emojiProbePaint) {
            emojiProbePaint.typeface = typeface
            emojiProbePaint.textSize = EMOJI_PROBE_TEXT_SIZE
            if (!emojiProbePaint.hasGlyph(emoji)) return false

            val codePointCount = emoji.codePointCount(0, emoji.length)
            if (codePointCount <= 1) return true

            val referenceWidth = emojiProbePaint.measureText("😀")
                .takeIf { it > 0f } ?: EMOJI_PROBE_TEXT_SIZE
            val emojiWidth = emojiProbePaint.measureText(emoji)
            return emojiWidth <= referenceWidth * MAX_JOINED_EMOJI_WIDTH_RATIO
        }
    }

    @JvmStatic
    fun clearCache() {
        synchronized(lock) {
            cachedCustomTypeface = null
            cachedCustomFontFamily = null
            cachedStyledCustomTypefaces.clear()
            customTypefaceSupportsVietnamese = true
            customTypefaceLoaded = false
            cachedVietnameseFallbackTypeface = null
            cachedStyledVietnameseFallbackTypefaces.clear()
            vietnameseFallbackTypefaceLoaded = false
            cachedEmojiTypeface = null
            emojiTypefaceLoaded = false
            TypefaceUtils.clearCache()
        }
    }
}
