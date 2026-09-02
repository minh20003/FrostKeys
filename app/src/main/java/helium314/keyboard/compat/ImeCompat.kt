// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.compat

import android.inputmethodservice.InputMethodService
import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodSubtype

object ImeCompat {
    fun InputMethodService.switchInputMethod(): Boolean = switchToNextInputMethod(false)

    fun InputMethodService.shouldSwitchToOtherInputMethods(): Boolean = shouldOfferSwitchingToNextInputMethod()

    fun InputMethodService.switchInputMethodAndSubtype(imi: InputMethodInfo, subtype: InputMethodSubtype) {
        switchInputMethod(imi.id, subtype)
    }
}
