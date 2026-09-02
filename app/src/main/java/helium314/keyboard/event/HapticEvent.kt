package helium314.keyboard.event

import android.view.HapticFeedbackConstants

enum class HapticEvent(@JvmField val feedbackConstant: Int, @JvmField val allowCustomDuration: Boolean) {
    // AudioAndHapticFeedbackManager checks this enum member before reading the value. Keep a
    // stable sentinel instead of referencing NO_HAPTICS, which was only added in API 34.
    NO_HAPTICS(-1, false),
    KEY_PRESS(HapticFeedbackConstants.KEYBOARD_TAP, true),
//    KEY_RELEASE(
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
//            HapticFeedbackConstants.KEYBOARD_RELEASE
//        } else {
//            HapticFeedbackConstants.?
//        },
//        ?
//    ),
    KEY_LONG_PRESS(HapticFeedbackConstants.LONG_PRESS, true),
    KEY_REPEAT(HapticFeedbackConstants.KEYBOARD_TAP, allowCustomDuration = false),
//    GESTURE_START(
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
//            HapticFeedbackConstants.GESTURE_START
//        } else {
//            HapticFeedbackConstants.?
//        },
//        ?
//    ),
    GESTURE_MOVE(HapticFeedbackConstants.CLOCK_TICK, false),
//    GESTURE_END(
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
//            HapticFeedbackConstants.GESTURE_END
//        } else {
//            HapticFeedbackConstants.?
//        },
//        ?
//    )
}
