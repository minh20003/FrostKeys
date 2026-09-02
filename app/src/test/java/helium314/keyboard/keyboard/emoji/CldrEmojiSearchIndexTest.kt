// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.emoji

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CldrEmojiSearchIndexTest {
    @Test
    fun bundledAnnotationsAreAdvertisedForVietnameseOnly() {
        assertTrue(CldrEmojiSearchIndex.hasBundledAnnotations(Locale.forLanguageTag("vi-VN")))
        assertTrue(CldrEmojiSearchIndex.hasBundledAnnotations(Locale("vi")))
        assertFalse(CldrEmojiSearchIndex.hasBundledAnnotations(Locale.US))
        assertFalse(CldrEmojiSearchIndex.hasBundledAnnotations(Locale.forLanguageTag("zh-CN")))
    }
}
