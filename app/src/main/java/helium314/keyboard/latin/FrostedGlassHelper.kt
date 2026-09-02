package helium314.keyboard.latin

import android.app.ActivityManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.core.graphics.ColorUtils
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ResourceUtils
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.updateSoftInputWindowLayoutParameters
import helium314.keyboard.settings.SettingsActivity
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap

/**
 * Frosted Glass deliberately uses only Android's public Android 12+ window-blur APIs.
 *
 * OEM reflection (including Samsung's SemBlurInfo and extension flags) was removed: it can
 * break across firmware updates and bypass the platform's blur/resource policy. If cross-window
 * blur is unavailable, the device is under pressure, or the user forces it off, this helper uses
 * the opaque themed fallback instead.
 */
object FrostedGlassHelper {
    private const val TAG = "KBoardBlur"
    private const val NATIVE_BLUR_HIDE_CLEANUP_DELAY_MS = 250L

    private val windowsWithAppliedFrostedGlass: MutableSet<Window> =
        Collections.newSetFromMap(WeakHashMap<Window, Boolean>())
    private val windowsWithResizeOverlayBlurSuppressed: MutableSet<Window> =
        Collections.newSetFromMap(WeakHashMap<Window, Boolean>())
    private val defaultBlurStates: MutableMap<Window, DefaultBlurState> =
        Collections.synchronizedMap(WeakHashMap<Window, DefaultBlurState>())
    private val nativeBlurStates: MutableMap<Window, NativeBlurState> =
        Collections.synchronizedMap(WeakHashMap<Window, NativeBlurState>())

    private data class DefaultBlurState(
        val enabled: Boolean,
        val radius: Int,
        val backgroundColor: Int,
        val cornerRadiusPx: Float,
    )

    private class NativeBlurState {
        var generation = 0
        var ready = false
        var decorView: View? = null
        var inputView: View? = null
        var cornerRadiusPx = -1f
        var blurRadius = -1
        var pendingCleanup: Runnable? = null
        var pendingCleanupDecorView: View? = null
    }

    @JvmStatic
    fun isFrostedTheme(context: Context): Boolean {
        val prefs = context.prefs()
        var isNight = SettingsActivity.forceNight
            ?: (ResourceUtils.isNight(context.resources) &&
                prefs.getBoolean(Settings.PREF_THEME_DAY_NIGHT, Defaults.PREF_THEME_DAY_NIGHT))

        if (KeyboardTheme.themeOverride == "light") isNight = false
        else if (KeyboardTheme.themeOverride == "dark") isNight = true
        val themeName = SettingsActivity.forceTheme ?: if (isNight) {
            prefs.getString(Settings.PREF_THEME_COLORS_NIGHT, Defaults.PREF_THEME_COLORS_NIGHT)
        } else {
            prefs.getString(Settings.PREF_THEME_COLORS, Defaults.PREF_THEME_COLORS)
        }
        // This answers whether the user selected the theme, not whether the device can render
        // live blur. The latter is decided independently in configureFrostedGlassInternal so a
        // selected Frosted theme keeps its readable solid fallback on low-end or OEM-limited
        // devices instead of silently becoming a different theme.
        return isFrostedThemeName(themeName)
    }

    internal fun isFrostedThemeName(themeName: String?): Boolean =
        themeName?.contains("frosted", ignoreCase = true) == true

    @JvmStatic
    fun isBatterySaverMode(context: Context): Boolean = try {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        powerManager?.isPowerSaveMode == true
    } catch (_: Exception) {
        false
    }

    /** Thermal throttling is a resource-management state, so no blur backend should stay live. */
    @JvmStatic
    fun isThermalPressure(context: Context): Boolean = try {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        powerManager?.currentThermalStatus?.let {
            it >= android.os.PowerManager.THERMAL_STATUS_SEVERE
        } == true
    } catch (_: Exception) {
        false
    }

    /** Returns true when blur should yield to the device's resource-management policy. */
    @JvmStatic
    fun shouldForceSolidFallback(context: Context): Boolean {
        if (isBatterySaverMode(context) || isThermalPressure(context)) return true
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            activityManager?.isLowRamDevice == true
        } catch (_: Exception) {
            false
        }
    }

    @JvmStatic
    fun setResizeOverlayBlurSuppressed(
        service: InputMethodService,
        inputView: View?,
        suppressed: Boolean,
        restoreEnabled: Boolean,
    ) {
        val window = service.window?.window ?: return
        if (suppressed) {
            windowsWithResizeOverlayBlurSuppressed.add(window)
            configureFrostedGlassInternal(service, inputView, false, allowDelayedNativeCleanup = false)
        } else {
            windowsWithResizeOverlayBlurSuppressed.remove(window)
            configureFrostedGlassInternal(service, inputView, restoreEnabled, allowDelayedNativeCleanup = true)
        }
    }

    @JvmStatic
    fun configureFrostedGlass(service: InputMethodService, inputView: View?, enable: Boolean) {
        configureFrostedGlassInternal(service, inputView, enable, allowDelayedNativeCleanup = true)
    }

    private fun configureFrostedGlassInternal(
        service: InputMethodService,
        inputView: View?,
        enable: Boolean,
        allowDelayedNativeCleanup: Boolean,
    ) {
        val window = service.window?.window ?: return
        val nativeState = nativeBlurState(window)
        val generation = ++nativeState.generation
        val overrideMode = normalizedBlurOverride(service)

        if (enable && windowsWithResizeOverlayBlurSuppressed.contains(window)) {
            configureFrostedGlassInternal(service, inputView, false, allowDelayedNativeCleanup = false)
            return
        }

        if (!enable) {
            val hadAppliedFrostedGlass = windowsWithAppliedFrostedGlass.contains(window)
            val hadDefaultBlurEnabled = defaultBlurStates[window]?.enabled == true
            if (!hadAppliedFrostedGlass && !hadDefaultBlurEnabled && !hasNativeBlurFlag(window)) {
                cancelPendingNativeBlurCleanup(nativeState)
                clearNativeBlurReady(nativeState)
                return
            }

            constrainImeWindowToKeyboardBounds(service, window, inputView)
            if (allowDelayedNativeCleanup &&
                scheduleNativeBlurCleanupIfReady(service, window, inputView, nativeState, generation)
            ) {
                Log.i(TAG, "Frosted glass disabled. Scheduled native blur cleanup.")
                return
            }

            cancelPendingNativeBlurCleanup(nativeState)
            clearNativeBlurReady(nativeState)
            windowsWithAppliedFrostedGlass.remove(window)
            if (applyDefaultBlur(service, window, false)) {
                Log.i(TAG, "Frosted glass disabled. Cleared public blur state.")
            }
            return
        }

        if (!service.isInputViewShown) {
            cancelPendingNativeBlurCleanup(nativeState)
            clearNativeBlurReady(nativeState)
            windowsWithAppliedFrostedGlass.remove(window)
            applyDefaultBlur(service, window, false, force = true)
            Log.d(TAG, "Skipped frosted blur enable while IME input view is hidden.")
            return
        }

        constrainImeWindowToKeyboardBounds(service, window, inputView)
        val shouldUseSolidFallback = overrideMode == "force_solid" ||
            isKnownFrostedGlassBlurUnsupportedDevice() ||
            shouldForceSolidFallback(service) ||
            !isSystemBlurAvailable(service)
        if (shouldUseSolidFallback) {
            cancelPendingNativeBlurCleanup(nativeState)
            clearNativeBlurReady(nativeState)
            val changed = applyDefaultBlur(service, window, false, solidFallbackColor(service))
            applySolidFallbackBackground(service, window, inputView)
            windowsWithAppliedFrostedGlass.add(window)
            if (changed) {
                Log.i(TAG, "Frosted glass blur unavailable or forced solid. Applied opaque fallback.")
            }
            return
        }

        restoreFrostedThemeBackground(service, inputView)
        if (applyNativeBlur(service, window, inputView, nativeState, generation)) {
            val reason = if (overrideMode == "force_native") "override" else "auto"
            Log.i(TAG, "Frosted glass $reason: requested public Android window blur.")
        }
        windowsWithAppliedFrostedGlass.add(window)
    }

    /** Legacy force_samsung preferences are mapped to the nearest supported public backend. */
    private fun normalizedBlurOverride(service: InputMethodService): String {
        val configured = service.prefs().getString(Settings.PREF_BLUR_RENDER_OVERRIDE, "auto")
        return if (configured == "force_samsung") "force_native" else configured ?: "auto"
    }

    private fun hasNativeBlurFlag(window: Window): Boolean =
        (window.attributes.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND) != 0 ||
            window.attributes.blurBehindRadius != 0

    private fun constrainImeWindowToKeyboardBounds(
        service: InputMethodService,
        window: Window,
        inputView: View?,
    ) {
        service.updateSoftInputWindowLayoutParameters(inputView, true)
        val params = window.attributes
        var changed = false
        if (params.width != WindowManager.LayoutParams.MATCH_PARENT) {
            params.width = WindowManager.LayoutParams.MATCH_PARENT
            changed = true
        }
        if (params.height != WindowManager.LayoutParams.WRAP_CONTENT) {
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
            changed = true
        }
        if (params.gravity != Gravity.BOTTOM) {
            params.gravity = Gravity.BOTTOM
            changed = true
        }
        if (changed) window.attributes = params
    }

    private fun applyDefaultBlur(
        service: InputMethodService,
        window: Window,
        enable: Boolean,
        backgroundColor: Int = Color.TRANSPARENT,
        force: Boolean = false,
    ): Boolean {
        val targetRadius = if (enable) blurRadius(service) else 0
        val desiredState = DefaultBlurState(
            enabled = enable,
            radius = targetRadius,
            backgroundColor = backgroundColor,
            cornerRadiusPx = keyboardCornerRadiusPx(service),
        )
        if (!force && defaultBlurStates[window] == desiredState) return false

        // AOSP background blur reads a non-zero radius from a uniform round-rect outline.
        window.setBackgroundDrawable(
            roundedWindowBackground(service, backgroundColor, topOnlyCorners = !enable)
        )
        window.setBackgroundBlurRadius(targetRadius)

        val params = window.attributes
        var layoutParamsChanged = false
        val blurFlag = WindowManager.LayoutParams.FLAG_BLUR_BEHIND
        if ((params.flags and blurFlag) != 0) {
            params.flags = params.flags and blurFlag.inv()
            layoutParamsChanged = true
        }
        if (params.blurBehindRadius != 0) {
            params.setBlurBehindRadius(0)
            layoutParamsChanged = true
        }
        if (layoutParamsChanged) window.attributes = params

        defaultBlurStates[window] = desiredState
        return true
    }

    private fun applyNativeBlur(
        service: InputMethodService,
        window: Window,
        inputView: View?,
        nativeState: NativeBlurState,
        generation: Int,
    ): Boolean {
        val cornerRadiusPx = keyboardCornerRadiusPx(service)
        val targetBlurRadius = blurRadius(service)
        if (reuseReadyNativeBlurIfPossible(
                service,
                window,
                inputView,
                nativeState,
                cornerRadiusPx,
                targetBlurRadius,
            )
        ) {
            return false
        }

        cancelPendingNativeBlurCleanup(nativeState)
        clearNativeBlurReady(nativeState)
        applyDefaultBlur(service, window, false, force = true)
        scheduleNativeBlurEnable(service, window, inputView, nativeState, generation, cornerRadiusPx, targetBlurRadius)
        return true
    }

    private fun scheduleNativeBlurEnable(
        service: InputMethodService,
        window: Window,
        inputView: View?,
        nativeState: NativeBlurState,
        generation: Int,
        cornerRadiusPx: Float,
        targetBlurRadius: Int,
    ) {
        val decorView = window.decorView
        // Post activation so AOSP samples the uniform background outline during the next predraw.
        decorView.post {
            if (generation != nativeState.generation ||
                !isFrostedTheme(service) ||
                !service.isInputViewShown ||
                shouldForceSolidFallback(service)
            ) {
                return@post
            }
            applyDefaultBlur(service, window, true, force = true)
            markNativeBlurReady(window, inputView, nativeState, cornerRadiusPx, targetBlurRadius)
            Log.d(TAG, "Native Android window blur enabled through public API.")
            frostedBlurTarget(inputView)?.invalidateOutline()
            inputView?.invalidate()
            decorView.invalidate()
        }
    }

    private fun scheduleNativeBlurCleanupIfReady(
        service: InputMethodService,
        window: Window,
        inputView: View?,
        nativeState: NativeBlurState,
        generation: Int,
    ): Boolean {
        if (!canReuseNativeBlur(window, inputView, nativeState, keyboardCornerRadiusPx(service), blurRadius(service))) {
            return false
        }

        cancelPendingNativeBlurCleanup(nativeState)
        val decorView = window.decorView
        val cleanup = Runnable {
            nativeState.pendingCleanup = null
            nativeState.pendingCleanupDecorView = null
            if (generation != nativeState.generation) return@Runnable
            applyDefaultBlur(service, window, false, force = true)
            clearNativeBlurReady(nativeState)
            windowsWithAppliedFrostedGlass.remove(window)
            frostedBlurTarget(inputView)?.invalidateOutline()
            inputView?.invalidate()
            decorView.invalidate()
            Log.d(TAG, "Delayed public blur cleanup completed.")
        }
        nativeState.pendingCleanup = cleanup
        nativeState.pendingCleanupDecorView = decorView
        decorView.postDelayed(cleanup, NATIVE_BLUR_HIDE_CLEANUP_DELAY_MS)
        return true
    }

    private fun reuseReadyNativeBlurIfPossible(
        service: InputMethodService,
        window: Window,
        inputView: View?,
        nativeState: NativeBlurState,
        cornerRadiusPx: Float,
        targetBlurRadius: Int,
    ): Boolean {
        if (!canReuseNativeBlur(window, inputView, nativeState, cornerRadiusPx, targetBlurRadius)) return false
        if (nativeState.pendingCleanup != null) {
            cancelPendingNativeBlurCleanup(nativeState)
            clearNativeBlurReady(nativeState)
            applyDefaultBlur(service, window, false, force = true)
            frostedBlurTarget(inputView)?.invalidateOutline()
            inputView?.invalidate()
            window.decorView.invalidate()
            return true
        }
        cancelPendingNativeBlurCleanup(nativeState)
        applyDefaultBlur(service, window, true, force = true)
        markNativeBlurReady(window, inputView, nativeState, cornerRadiusPx, targetBlurRadius)
        frostedBlurTarget(inputView)?.invalidateOutline()
        inputView?.invalidate()
        window.decorView.invalidate()
        return true
    }

    private fun canReuseNativeBlur(
        window: Window,
        inputView: View?,
        nativeState: NativeBlurState,
        cornerRadiusPx: Float,
        targetBlurRadius: Int,
    ): Boolean = nativeState.ready &&
        nativeState.decorView === window.decorView &&
        nativeState.inputView === inputView &&
        nativeState.cornerRadiusPx == cornerRadiusPx &&
        nativeState.blurRadius == targetBlurRadius

    private fun markNativeBlurReady(
        window: Window,
        inputView: View?,
        nativeState: NativeBlurState,
        cornerRadiusPx: Float,
        targetBlurRadius: Int,
    ) {
        nativeState.ready = true
        nativeState.decorView = window.decorView
        nativeState.inputView = inputView
        nativeState.cornerRadiusPx = cornerRadiusPx
        nativeState.blurRadius = targetBlurRadius
    }

    private fun clearNativeBlurReady(nativeState: NativeBlurState) {
        nativeState.ready = false
        nativeState.decorView = null
        nativeState.inputView = null
        nativeState.cornerRadiusPx = -1f
        nativeState.blurRadius = -1
    }

    private fun cancelPendingNativeBlurCleanup(nativeState: NativeBlurState) {
        val cleanup = nativeState.pendingCleanup
        val decorView = nativeState.pendingCleanupDecorView
        if (cleanup != null && decorView != null) decorView.removeCallbacks(cleanup)
        nativeState.pendingCleanup = null
        nativeState.pendingCleanupDecorView = null
    }

    private fun nativeBlurState(window: Window): NativeBlurState = synchronized(nativeBlurStates) {
        nativeBlurStates.getOrPut(window) { NativeBlurState() }
    }

    private fun roundedWindowBackground(
        context: Context,
        color: Int,
        topOnlyCorners: Boolean = true,
    ): GradientDrawable {
        val radiusPx = keyboardCornerRadiusPx(context)
        return GradientDrawable().apply {
            setColor(color)
            if (topOnlyCorners) {
                cornerRadii = floatArrayOf(
                    radiusPx, radiusPx,
                    radiusPx, radiusPx,
                    0f, 0f,
                    0f, 0f,
                )
            } else {
                setCornerRadius(radiusPx)
            }
        }
    }

    private fun keyboardCornerRadiusPx(context: Context): Float =
        Settings.readKeyboardCornerRadius(context.prefs()) * context.resources.displayMetrics.density

    private fun frostedBlurTarget(inputView: View?): View? =
        inputView?.findViewById<View?>(R.id.main_keyboard_frame) ?: inputView

    private fun restoreFrostedThemeBackground(context: Context, inputView: View?) {
        val target = frostedBlurTarget(inputView) ?: return
        target.setBackgroundColor(Color.WHITE)
        val colors = runCatching { KeyboardTheme.getColorsForCurrentTheme(context) }
            .getOrNull()
            ?: Settings.getValues()?.mColors
        colors?.setBackground(target, ColorType.MAIN_BACKGROUND)
        target.invalidate()
    }

    private fun applySolidFallbackBackground(context: Context, window: Window, inputView: View?) {
        val color = solidFallbackColor(context)
        window.setBackgroundDrawable(roundedWindowBackground(context, color))
        inputView?.setBackgroundColor(Color.TRANSPARENT)
        frostedBlurTarget(inputView)?.let { target ->
            target.setBackgroundColor(color)
            target.invalidate()
        }
    }

    private fun solidFallbackColor(context: Context): Int {
        val baseColor = runCatching { KeyboardTheme.getColorsForCurrentTheme(context).get(ColorType.MAIN_BACKGROUND) }
            .getOrNull()
            ?: Settings.getValues()?.mColors?.get(ColorType.MAIN_BACKGROUND)
            ?: if (isNight(context)) Color.BLACK else Color.WHITE
        return if (baseColor == Color.TRANSPARENT) {
            if (isNight(context)) Color.BLACK else Color.WHITE
        } else {
            ColorUtils.setAlphaComponent(baseColor, 255)
        }
    }

    private fun isSystemBlurAvailable(context: Context): Boolean {
        if (isBatterySaverMode(context)) return false
        return try {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            windowManager?.isCrossWindowBlurEnabled == true
        } catch (e: Throwable) {
            Log.w(TAG, "Could not read cross-window blur availability; using solid fallback", e)
            false
        }
    }

    private fun blurRadius(context: Context): Int {
        val isNight = isNight(context)
        return KeyboardTheme.livePreviewValues?.blurRadius
            ?: if (isNight) {
                context.prefs().getInt(
                    Settings.PREF_FROSTED_BLUR_RADIUS_NIGHT,
                    Defaults.PREF_FROSTED_BLUR_RADIUS_NIGHT,
                )
            } else {
                context.prefs().getInt(
                    Settings.PREF_FROSTED_BLUR_RADIUS,
                    Defaults.PREF_FROSTED_BLUR_RADIUS,
                )
            }
    }

    private fun isNight(context: Context): Boolean = KeyboardTheme.isDarkThemeActive(context)

    fun shouldWarnAboutFrostedGlassBlurUnsupported(themeName: String?): Boolean =
        themeName == KeyboardTheme.THEME_FROSTED_GLASS && isKnownFrostedGlassBlurUnsupportedDevice()

    fun isKnownFrostedGlassBlurUnsupportedDevice(): Boolean {
        if (!Build.MANUFACTURER.equals("samsung", ignoreCase = true)) return false
        val deviceInfo = listOf(Build.MODEL, Build.DEVICE, Build.PRODUCT, Build.HARDWARE)
            .joinToString(" ")
            .lowercase(Locale.US)
        return listOf("sm-m315", "m31").any { deviceInfo.contains(it) }
    }
}
