// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FrostedGlassHelperTest {
    @Test
    fun themeSelectionDoesNotEncodeBackendSupport() {
        assertTrue(FrostedGlassHelper.isFrostedThemeName("frosted_glass"))
        assertTrue(FrostedGlassHelper.isFrostedThemeName("My Frosted Theme"))
        assertFalse(FrostedGlassHelper.isFrostedThemeName("dynamic"))
        assertFalse(FrostedGlassHelper.isFrostedThemeName(null))
    }
}
