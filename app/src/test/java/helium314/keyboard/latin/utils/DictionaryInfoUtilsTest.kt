// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DictionaryInfoUtilsTest {
    @Test
    fun phraseAssetsCannotBeMisclassifiedAsDictionaries() {
        assertTrue(DictionaryInfoUtils.isAssetsDictionaryFileName("main_vi.dict"))
        assertTrue(DictionaryInfoUtils.isAssetsDictionaryFileName("main_en-US.dict"))
        assertFalse(DictionaryInfoUtils.isAssetsDictionaryFileName("vi_phrase_model_v1.tsv"))
        assertFalse(DictionaryInfoUtils.isAssetsDictionaryFileName("dictionary_vi.json"))
    }
}
