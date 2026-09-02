// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.latin.define

import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionFlagsTest {
    @Test
    fun physicalKeyboardRouteRemainsEnabledForVietnameseComposition() {
        // Telex/VNI must receive decoded physical keys before Android sends the character
        // straight to the editor. A device smoke test covers the full route; this catches an
        // accidental restoration of the historical upstream off-switch.
        assertTrue(ProductionFlags.IS_HARDWARE_KEYBOARD_SUPPORTED)
    }
}
