// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.internal

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.toColorInt
import helium314.keyboard.keyboard.KeyboardActionListener
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.keyboard.KeyboardTypeface
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.keyboard.internal.translation.TranslationLanguage
import helium314.keyboard.keyboard.internal.translation.TranslationPromptBuilder
import helium314.keyboard.keyboard.internal.translation.TranslationCoordinator
import helium314.keyboard.keyboard.internal.translation.TranslationService
import helium314.keyboard.keyboard.internal.translation.OnDeviceTranslationWrapper
import helium314.keyboard.keyboard.internal.translation.OnDeviceTranslationProvider
import helium314.keyboard.keyboard.internal.translation.StubOnDeviceTranslationProvider
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Colors
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ResourceUtils
import helium314.keyboard.latin.utils.dpToPx
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.AudioAndHapticFeedbackManager
import helium314.keyboard.event.HapticEvent
import java.util.Locale

@SuppressLint("ViewConstructor")
class TranslationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private lateinit var keyboardActionListener: KeyboardActionListener
    private var inputConnection: android.view.inputmethod.InputConnection? = null

    private var sourceLanguage: TranslationLanguage = TranslationLanguage.DEFAULT_SOURCE
    private var targetLanguage: TranslationLanguage = TranslationLanguage.DEFAULT_TARGET
    private var sourceText: String = ""
    private var translationResult: String = ""
    private var isTranslating = false

    // UI state tracking
    private var isReplacingSelection = false
    private var canReplaceWholeField = false
    private var replaceBeforeCursorChars = 0
    private var replaceAfterCursorChars = 0
    private var isExecutingReplacement = false

    private var currentThemeColors: Colors? = null
    private val colors: Colors
        get() = currentThemeColors ?: Settings.getValues().mColors

    // Translation coordinator
    private val translationCoordinator = TranslationCoordinator(
        context = context,
        onDeviceProvider = createOnDeviceProvider()
    )

    /** Creates the on-device translation provider, or null if not available */
    private fun createOnDeviceProvider(): OnDeviceTranslationProvider? {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                // Use the actual on-device wrapper
                createRealOnDeviceProvider()
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.S)
    private fun createRealOnDeviceProvider(): OnDeviceTranslationProvider {
        return object : OnDeviceTranslationProvider {
            override suspend fun translate(
                text: String,
                sourceLanguage: String,
                targetLanguage: String,
                signal: android.os.CancellationSignal,
            ): OnDeviceTranslationWrapper.TranslationResult {
                return OnDeviceTranslationWrapper.translate(context, text, sourceLanguage, targetLanguage, signal)
            }

            override fun getCapabilityState(
                sourceLanguage: String,
                targetLanguage: String,
            ): OnDeviceTranslationWrapper.CapabilityState {
                return OnDeviceTranslationWrapper.getCapabilityState(context, sourceLanguage, targetLanguage)
            }

            override fun detectLanguage(text: String): Pair<String, Float>? {
                return OnDeviceTranslationWrapper.detectLanguage(context, text)
            }

            override fun getSettingsPendingIntent(): android.app.PendingIntent? {
                return OnDeviceTranslationWrapper.getOnDeviceSettingsPendingIntent(context)
            }
        }
    }

    // View references
    private lateinit var btnBack: ImageButton
    private lateinit var tvTitle: TextView
    private lateinit var tvProviderChip: TextView
    private lateinit var spinnerSourceLanguage: Spinner
    private lateinit var spinnerTargetLanguage: Spinner
    private lateinit var btnSwap: ImageButton
    private lateinit var etSourceText: EditText
    private lateinit var tvCharCount: TextView
    private lateinit var loadingContainer: FrameLayout
    private lateinit var progressTranslating: ProgressBar
    private lateinit var tvLoadingState: TextView
    private lateinit var tvErrorState: TextView
    private lateinit var tvResultText: TextView
    private lateinit var btnTranslate: Button
    private lateinit var btnCopy: Button
    private lateinit var btnInsert: Button
    private lateinit var btnDelete: ImageButton

    companion object {
        private const val MAX_DISPLAY_CHARS = 8000
    }

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.translation_panel, this, true)
        setupUI()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val settings = Settings.getValues()
        val abcHeight = ResourceUtils.getKeyboardHeight(resources, settings)
        val persistentEmojiEnabled = context.prefs().getBoolean(
            Settings.PREF_PERSISTENT_EMOJI_ROW,
            helium314.keyboard.latin.settings.Defaults.PREF_PERSISTENT_EMOJI_ROW
        )
        val emojiRowHeight = if (persistentEmojiEnabled) (41 * resources.displayMetrics.density).toInt() else 0
        val stripHeight = resources.getDimensionPixelSize(R.dimen.config_suggestions_strip_height)
        val finalHeight = abcHeight + emojiRowHeight + stripHeight + paddingTop + paddingBottom + 1

        super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(finalHeight, View.MeasureSpec.EXACTLY))
        setMeasuredDimension(View.MeasureSpec.getSize(widthMeasureSpec), finalHeight)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupUI() {
        // Initialize view references
        btnBack = findViewById(R.id.btn_back_translation)
        tvTitle = findViewById(R.id.tv_translation_title)
        tvProviderChip = findViewById(R.id.tv_provider_chip)
        spinnerSourceLanguage = findViewById(R.id.spinner_source_language)
        spinnerTargetLanguage = findViewById(R.id.spinner_target_language)
        btnSwap = findViewById(R.id.btn_swap_languages)
        etSourceText = findViewById(R.id.et_source_text)
        // Clear translation result when source text changes (it's now stale)
        etSourceText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (translationResult.isNotBlank()) {
                    translationResult = ""
                    tvResultText.text = ""
                    // Also invalidate Replace eligibility since source changed
                    canReplaceWholeField = false
                    isReplacingSelection = false
                }
            }
        })
        tvCharCount = findViewById(R.id.tv_char_count)
        loadingContainer = findViewById(R.id.loading_container)
        progressTranslating = findViewById(R.id.progress_translating)
        tvLoadingState = findViewById(R.id.tv_loading_state)
        tvErrorState = findViewById(R.id.tv_error_state)
        tvResultText = findViewById(R.id.tv_result_text)
        btnTranslate = findViewById(R.id.btn_translate)
        btnCopy = findViewById(R.id.btn_copy_translation)
        btnInsert = findViewById(R.id.btn_insert_translation)
        btnDelete = findViewById(R.id.btn_delete_text)

        setupSpinners()
        setupClickListeners()
    }

    private fun setupSpinners() {
        // Source language spinner (includes Auto detect)
        val sourceLanguages = TranslationLanguage.SOURCE_LANGUAGES
        val sourceAdapter = LanguageSpinnerAdapter(context, sourceLanguages)
        spinnerSourceLanguage.adapter = sourceAdapter
        spinnerSourceLanguage.setSelection(sourceLanguages.indexOf(sourceLanguage))

        spinnerSourceLanguage.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                sourceLanguage = sourceLanguages[position]
                // Language change invalidates any existing translation
                if (translationResult.isNotBlank()) {
                    translationResult = ""
                    tvResultText.text = ""
                }
                saveLanguagePreferences()
                updateProviderChip()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Target language spinner (excludes Auto detect)
        val targetLanguages = TranslationLanguage.TARGET_LANGUAGES
        val targetAdapter = LanguageSpinnerAdapter(context, targetLanguages)
        spinnerTargetLanguage.adapter = targetAdapter
        spinnerTargetLanguage.setSelection(targetLanguages.indexOf(targetLanguage))

        spinnerTargetLanguage.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                targetLanguage = targetLanguages[position]
                // Language change invalidates any existing translation
                if (translationResult.isNotBlank()) {
                    translationResult = ""
                    tvResultText.text = ""
                }
                saveLanguagePreferences()
                updateProviderChip()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun updateProviderChip(providerName: String = context.getString(R.string.translation_provider_gemini)) {
        // Show which provider was used for the current translation result
        tvProviderChip.visibility = View.VISIBLE
        tvProviderChip.text = providerName
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupClickListeners() {
        // Back/Close button
        btnBack.setOnClickListener { view ->
            AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(
                KeyCode.NOT_SPECIFIED, view, HapticEvent.KEY_PRESS
            )
            onCloseClicked()
        }

        // Swap languages button
        btnSwap.setOnClickListener { view ->
            AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(
                KeyCode.NOT_SPECIFIED, view, HapticEvent.KEY_PRESS
            )
            onSwapLanguages()
        }

        // Translate button
        btnTranslate.setOnClickListener { view ->
            AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(
                KeyCode.NOT_SPECIFIED, view, HapticEvent.KEY_PRESS
            )
            onTranslateClicked()
        }

        // Copy button
        btnCopy.setOnClickListener { view ->
            AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(
                KeyCode.NOT_SPECIFIED, view, HapticEvent.KEY_PRESS
            )
            onCopyClicked()
        }

        // Insert button
        btnInsert.setOnClickListener { view ->
            AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(
                KeyCode.NOT_SPECIFIED, view, HapticEvent.KEY_PRESS
            )
            onInsertClicked()
        }

        // Delete button with touch controller
        val deleteTouchController = DeleteTouchController()
        btnDelete.isLongClickable = false
        btnDelete.setOnClickListener {
            dispatchPanelLocalDelete(0, HapticEvent.KEY_PRESS)
        }
        btnDelete.setOnTouchListener { view, event ->
            deleteTouchController.onTouch(view, event)
        }
    }

    private inner class DeleteTouchController {
        private val handler = Handler(Looper.getMainLooper())
        private var isPressedState = false
        private var repeatCount = 0

        private val repeatRunnable = object : Runnable {
            override fun run() {
                if (isPressedState) {
                    repeatCount++
                    dispatchPanelLocalDelete(repeatCount, HapticEvent.KEY_REPEAT)
                    handler.postDelayed(this, 50)
                }
            }
        }

        fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    handler.removeCallbacks(repeatRunnable)
                    isPressedState = true
                    v.isPressed = true
                    repeatCount = 0
                    dispatchPanelLocalDelete(0, HapticEvent.KEY_PRESS)
                    handler.postDelayed(repeatRunnable, 400)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(repeatRunnable)
                    v.isPressed = false
                    isPressedState = false
                    return true
                }
            }
            return false
        }
    }

    /**
     * Deletes characters from the PANEL-LOCAL source EditText only.
     * This method intentionally does NOT route through keyboardActionListener
     * to prevent deletion from the external application editor.
     */
    private fun dispatchPanelLocalDelete(repeatCount: Int, hapticEvent: HapticEvent) {
        val et = etSourceText
        val text = et.text
        val selStart = et.selectionStart
        val selEnd = et.selectionEnd

        // Nothing to delete at the beginning
        if (selStart <= 0) return

        val deleteStart: Int
        val deleteCount: Int

        if (selStart != selEnd) {
            // Delete selection
            deleteStart = selStart
            deleteCount = selEnd - selStart
        } else {
            // Delete character before cursor
            deleteStart = selStart - 1
            deleteCount = 1.coerceAtLeast(repeatCount + 1)
        }

        // Clamp to valid range
        val safeDeleteStart = deleteStart.coerceIn(0, text.length)
        val safeDeleteCount = deleteCount.coerceIn(0, safeDeleteStart)

        if (safeDeleteCount > 0) {
            text.delete(safeDeleteStart, safeDeleteStart + safeDeleteCount)
            // Update source text
            sourceText = text.toString()
        }

        // Haptic feedback
        if (::keyboardActionListener.isInitialized) {
            keyboardActionListener.onPressKey(KeyCode.DELETE, repeatCount, true, hapticEvent)
            keyboardActionListener.onReleaseKey(KeyCode.DELETE, false)
        }
    }

    private fun getLatinIME(): helium314.keyboard.latin.LatinIME? {
        var ctx = context
        while (ctx is android.content.ContextWrapper) {
            if (ctx is helium314.keyboard.latin.LatinIME) return ctx
            ctx = ctx.baseContext
        }
        return ctx as? helium314.keyboard.latin.LatinIME
    }

    fun setKeyboardActionListener(listener: KeyboardActionListener) {
        this.keyboardActionListener = listener
    }

    fun updateThemeColors(colors: Colors) {
        currentThemeColors = colors
        applyThemeFixes()
        updateButtonStates()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        applyThemeFixes()
        updateButtonStates()
    }

    override fun onDetachedFromWindow() {
        onClose()
        super.onDetachedFromWindow()
    }

    private fun applyThemeFixes() {
        val colors = this.colors
        val isNight = ResourceUtils.isNight(context.resources)

        // Title text color
        tvTitle.setTextColor(colors.get(ColorType.KEY_TEXT))
        KeyboardTypeface.applyToTextView(tvTitle, tvTitle.text, android.graphics.Typeface.defaultFromStyle(android.graphics.Typeface.BOLD))

        // Back button tint
        btnBack.imageTintList = ColorStateList.valueOf(colors.get(ColorType.KEY_TEXT))
        btnBack.background = createBackButtonBackground(colors)
        btnBack.setPadding(0, 0, 0, 0)
        btnBack.scaleType = android.widget.ImageView.ScaleType.CENTER

        // Swap button tint
        btnSwap.imageTintList = ColorStateList.valueOf(colors.get(ColorType.KEY_TEXT))

        // Delete button tint
        btnDelete.imageTintList = ColorStateList.valueOf(colors.get(ColorType.KEY_TEXT))

        // Source text input
        etSourceText.setTextColor(colors.get(ColorType.KEY_TEXT))

        // Result text
        tvResultText.setTextColor(colors.get(ColorType.KEY_TEXT))

        // Apply button text colors
        btnTranslate.setTextColor(Color.WHITE)
        btnCopy.setTextColor(if (isNight) Color.WHITE else Color.BLACK)
        btnInsert.setTextColor(if (isNight) Color.WHITE else Color.BLACK)
    }

    private fun createBackButtonBackground(colors: Colors): RippleDrawable {
        val circle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
            colors.setColor(this, ColorType.SPECIAL_KEY_BACKGROUND)
        }
        val rippleColor = ColorStateList.valueOf(
            ColorUtils.setAlphaComponent(colors.get(ColorType.FUNCTIONAL_KEY_TEXT), 0x33)
        )
        val horizontalInset = 3.dpToPx(resources)
        val verticalInset = 3.dpToPx(resources)
        val content = android.graphics.drawable.InsetDrawable(circle, horizontalInset, verticalInset, horizontalInset, verticalInset)

        val maskCircle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.BLACK)
        }
        val mask = android.graphics.drawable.InsetDrawable(maskCircle, horizontalInset, verticalInset, horizontalInset, verticalInset)

        return RippleDrawable(rippleColor, content, mask)
    }

    private fun updateButtonStates() {
        val colors = this.colors
        val isNight = ResourceUtils.isNight(context.resources)
        val disabledAlpha = if (isNight) 0.8f else 0.5f

        // Update button enabled states
        btnCopy.isEnabled = translationResult.isNotBlank() && !isTranslating
        btnCopy.alpha = if (btnCopy.isEnabled) 1.0f else disabledAlpha

        btnInsert.isEnabled = translationResult.isNotBlank() && !isTranslating
        btnInsert.alpha = if (btnInsert.isEnabled) 1.0f else disabledAlpha

        btnTranslate.isEnabled = !isTranslating
        btnTranslate.alpha = if (btnTranslate.isEnabled) 1.0f else disabledAlpha

        // Show/hide loading
        loadingContainer.visibility = if (isTranslating) View.VISIBLE else View.GONE

        // Hide error when not translating
        if (!isTranslating && tvErrorState.visibility == View.VISIBLE && translationResult.isNotBlank()) {
            tvErrorState.visibility = View.GONE
        }
    }

    fun onOpen(connection: android.view.inputmethod.InputConnection?) {
        cancelPendingTranslation()
        this.inputConnection = connection
        if (isExecutingReplacement) {
            updateButtonStates()
            return
        }

        // Capture selected text first, then ExtractedText
        val selectedText = connection?.getSelectedText(0)?.toString()
        if (!selectedText.isNullOrBlank()) {
            this.sourceText = selectedText
            this.isReplacingSelection = true
            this.canReplaceWholeField = false
            this.replaceBeforeCursorChars = 0
            this.replaceAfterCursorChars = 0
        } else {
            val extracted = connection?.getExtractedText(
                android.view.inputmethod.ExtractedTextRequest(), 0
            )
            val extractedText = extracted?.text?.toString().orEmpty()
            val selectionStart = extracted?.selectionStart ?: -1
            val selectionEnd = extracted?.selectionEnd ?: -1
            this.sourceText = extractedText
            this.isReplacingSelection = false
            this.canReplaceWholeField = extracted?.partialStartOffset == -1 &&
                selectionStart in 0..extractedText.length &&
                selectionEnd in selectionStart..extractedText.length
            this.replaceBeforeCursorChars = if (canReplaceWholeField) selectionStart else 0
            this.replaceAfterCursorChars = if (canReplaceWholeField) {
                extractedText.length - selectionEnd
            } else {
                0
            }
        }

        // Initialize UI state
        etSourceText.setText(sourceText)
        tvResultText.text = ""
        translationResult = ""
        isTranslating = false
        tvErrorState.visibility = View.GONE

        // Load saved language preferences or use defaults
        sourceLanguage = loadSavedSourceLanguage()
        targetLanguage = loadSavedTargetLanguage()

        // Update spinner selections
        val sourceLanguages = TranslationLanguage.SOURCE_LANGUAGES
        spinnerSourceLanguage.setSelection(sourceLanguages.indexOf(sourceLanguage))
        val targetLanguages = TranslationLanguage.TARGET_LANGUAGES
        spinnerTargetLanguage.setSelection(targetLanguages.indexOf(targetLanguage))

        updateButtonStates()
    }

    private fun loadSavedSourceLanguage(): TranslationLanguage {
        val savedTag = context.prefs().getString(Settings.PREF_TRANSLATION_SOURCE_LANGUAGE, null)
        return if (savedTag.isNullOrBlank()) {
            TranslationLanguage.DEFAULT_SOURCE
        } else {
            TranslationLanguage.fromTag(savedTag) ?: TranslationLanguage.DEFAULT_SOURCE
        }
    }

    private fun loadSavedTargetLanguage(): TranslationLanguage {
        val savedTag = context.prefs().getString(Settings.PREF_TRANSLATION_TARGET_LANGUAGE, null)
        return if (savedTag.isNullOrBlank()) {
            TranslationLanguage.DEFAULT_TARGET
        } else {
            TranslationLanguage.fromTag(savedTag) ?: TranslationLanguage.DEFAULT_TARGET
        }
    }

    private fun saveLanguagePreferences() {
        val prefs = context.prefs()
        val editor = prefs.edit()
        editor.putString(Settings.PREF_TRANSLATION_SOURCE_LANGUAGE, sourceLanguage.id)
        editor.putString(Settings.PREF_TRANSLATION_TARGET_LANGUAGE, targetLanguage.id)
        editor.apply()
    }

    private fun loadSavedQuality(): TranslationPromptBuilder.Quality {
        val savedQuality = context.prefs().getString(Settings.PREF_TRANSLATION_QUALITY, null)
        return when (savedQuality) {
            "high" -> TranslationPromptBuilder.Quality.HIGH
            else -> TranslationPromptBuilder.Quality.FAST
        }
    }

    fun onClose() {
        cancelPendingTranslation()
        inputConnection = null
        canReplaceWholeField = false
        replaceBeforeCursorChars = 0
        replaceAfterCursorChars = 0
        isTranslating = false
        isExecutingReplacement = false
    }

    private fun cancelPendingTranslation() {
        TranslationService.cancelAll()
        translationCoordinator.cancelPending()
        isTranslating = false
    }

    fun setHardwareAcceleratedDrawingEnabled(enabled: Boolean) {
        if (!enabled) return
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    private fun onCloseClicked() {
        if (::keyboardActionListener.isInitialized) {
            keyboardActionListener.onCodeInput(KeyCode.ALPHA, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
        }
    }

    private fun onSwapLanguages() {
        // Only swap if neither language is Auto detect
        if (sourceLanguage.isAutoDetect) {
            Toast.makeText(context, R.string.translation_toast_source_same, Toast.LENGTH_SHORT).show()
            return
        }

        // Exchange source and target, but keep text in source field
        val oldSource = sourceLanguage
        sourceLanguage = targetLanguage
        targetLanguage = oldSource

        // Save preferences after swap
        saveLanguagePreferences()

        // Update spinner selections
        val sourceLanguages = TranslationLanguage.SOURCE_LANGUAGES
        spinnerSourceLanguage.setSelection(sourceLanguages.indexOf(sourceLanguage))
        val targetLanguages = TranslationLanguage.TARGET_LANGUAGES
        spinnerTargetLanguage.setSelection(targetLanguages.indexOf(targetLanguage))

        // If there's a translation result, swap the text too
        if (translationResult.isNotBlank()) {
            val tempText = sourceText
            sourceText = translationResult
            translationResult = tempText
            etSourceText.setText(sourceText)
            tvResultText.text = translationResult
        }

        // Clear error state on swap
        tvErrorState.visibility = View.GONE

        updateProviderChip()
    }

    private fun onTranslateClicked() {
        if (isTranslating) return

        sourceText = etSourceText.text.toString()

        if (sourceText.isBlank()) {
            showError(context.getString(R.string.translation_error_no_text))
            return
        }

        if (TranslationPromptBuilder.areSameLanguage(sourceLanguage, targetLanguage)) {
            showError(context.getString(R.string.translation_error_same_language))
            return
        }

        val validation = TranslationPromptBuilder.validateSourceText(sourceText)
        if (validation is TranslationPromptBuilder.ValidationResult.Invalid) {
            val errorMessage = when (validation.reason) {
                "empty" -> context.getString(R.string.translation_error_no_text)
                "too_large_codepoints", "too_large_bytes" -> {
                    context.getString(R.string.translation_error_input_too_large, MAX_DISPLAY_CHARS)
                }
                else -> context.getString(R.string.translation_error_generic)
            }
            showError(errorMessage)
            return
        }

        isTranslating = true
        translationResult = ""
        tvResultText.text = ""
        tvErrorState.visibility = View.GONE
        updateButtonStates()

        // Show loading state
        loadingContainer.visibility = View.VISIBLE
        progressTranslating.visibility = View.VISIBLE
        tvLoadingState.text = context.getString(R.string.translation_state_translating)
        if (sourceLanguage.isAutoDetect) {
            tvLoadingState.text = context.getString(R.string.translation_state_detecting)
        }

        // Call translation coordinator
        translationCoordinator.translate(
            sourceText = sourceText,
            source = sourceLanguage,
            target = targetLanguage,
            quality = loadSavedQuality(),
            callback = object : TranslationCoordinator.TranslationCallback {
                override fun onResult(translation: String?, provider: TranslationCoordinator.ResultProvider) {
                    isTranslating = false
                    loadingContainer.visibility = View.GONE

                    if (translation != null && translation.isNotBlank()) {
                        translationResult = translation
                        tvResultText.text = translation
                        tvErrorState.visibility = View.GONE
                        // Show provider chip based on actual provider
                        val providerName = when (provider) {
                            TranslationCoordinator.ResultProvider.LOCAL ->
                                "" // Hide provider for local same-language result
                            TranslationCoordinator.ResultProvider.ON_DEVICE ->
                                context.getString(R.string.translation_provider_on_device)
                            TranslationCoordinator.ResultProvider.GEMINI ->
                                context.getString(R.string.translation_provider_gemini)
                            TranslationCoordinator.ResultProvider.NONE -> ""
                        }
                        updateProviderChip(providerName = providerName)
                    } else {
                        showError(context.getString(R.string.translation_error_unavailable))
                    }
                    updateButtonStates()
                }

                override fun onError(error: TranslationCoordinator.TranslationError, provider: TranslationCoordinator.ResultProvider) {
                    isTranslating = false
                    loadingContainer.visibility = View.GONE

                    val errorMessage = when (error) {
                        is TranslationCoordinator.TranslationError.InputInvalid ->
                            context.getString(R.string.translation_error_input_too_large, MAX_DISPLAY_CHARS)
                        TranslationCoordinator.TranslationError.CloudDisabled ->
                            context.getString(R.string.translation_error_cloud_disabled)
                        TranslationCoordinator.TranslationError.ApiKeyMissing ->
                            context.getString(R.string.translation_error_api_key_missing)
                        TranslationCoordinator.TranslationError.ApiKeyInvalid ->
                            context.getString(R.string.translation_error_invalid_api_key)
                        is TranslationCoordinator.TranslationError.QuotaExhausted ->
                            context.getString(R.string.translation_error_quota, error.seconds.coerceAtLeast(1L))
                        TranslationCoordinator.TranslationError.SafetyBlocked ->
                            context.getString(R.string.translation_error_safety_blocked)
                        is TranslationCoordinator.TranslationError.OnDeviceUnavailable ->
                            if (error.canDownload) {
                                context.getString(R.string.translation_error_on_device_download)
                            } else {
                                context.getString(R.string.translation_error_on_device_unavailable)
                            }
                        TranslationCoordinator.TranslationError.Cancelled ->
                            return
                        else ->
                            context.getString(R.string.translation_error_generic)
                    }
                    showError(errorMessage)
                    updateButtonStates()
                }

                override fun onOnDeviceDownloadRequired() {
                    // Offer on-device download if available
                    translationCoordinator.getOnDeviceSettingsIntent()?.let { pendingIntent ->
                        try {
                            pendingIntent.send()
                        } catch (e: Exception) {
                            // Settings intent not available, ignore
                        }
                    }
                }
            }
        )
    }

    private fun showError(message: String) {
        tvErrorState.text = message
        tvErrorState.visibility = View.VISIBLE
        tvResultText.text = ""
        // Retry on error state click
        tvErrorState.setOnClickListener {
            if (!isTranslating && sourceText.isNotBlank()) {
                onTranslateClicked()
            }
        }
    }

    private fun onCopyClicked() {
        if (translationResult.isNotBlank()) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(context.getString(R.string.translation_title), translationResult)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, R.string.translation_toast_copied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun onInsertClicked() {
        if (translationResult.isBlank()) return

        val ic = inputConnection ?: getLatinIME()?.currentInputConnection ?: return

        // Step 1: If there's a selection, verify it still matches and replace it
        if (isReplacingSelection) {
            val currentSelection = ic.getSelectedText(0)?.toString()
            if (currentSelection == sourceText) {
                // Selection still matches — safe to replace
                ic.commitText(translationResult, 1)
                Toast.makeText(context, R.string.translation_toast_inserted, Toast.LENGTH_SHORT).show()
                return
            }
            // Selection changed — fall through to Insert
        }

        // Step 2: If we had captured whole-field state, verify it
        if (canReplaceWholeField) {
            val extracted = ic.getExtractedText(
                android.view.inputmethod.ExtractedTextRequest(), 0
            )
            val currentText = extracted?.text?.toString().orEmpty()
            val isPartial = extracted?.partialStartOffset != -1

            if (!isPartial && currentText == sourceText) {
                // Editor state still matches captured snapshot — safe to replace full field
                isExecutingReplacement = true
                ic.beginBatchEdit()
                try {
                    ic.deleteSurroundingText(replaceBeforeCursorChars, replaceAfterCursorChars)
                    ic.commitText(translationResult, 1)
                } finally {
                    ic.endBatchEdit()
                    Handler(Looper.getMainLooper()).postDelayed({
                        isExecutingReplacement = false
                    }, 1000)
                }
                Toast.makeText(context, R.string.translation_toast_inserted, Toast.LENGTH_SHORT).show()
                return
            }
            // Editor changed or partial — fall through to Insert
        }

        // Step 3: Insert at current cursor position (safe, always valid)
        ic.beginBatchEdit()
        try {
            ic.commitText(translationResult, 1)
        } finally {
            ic.endBatchEdit()
        }
        Toast.makeText(context, R.string.translation_toast_inserted, Toast.LENGTH_SHORT).show()
    }

    private fun canReplaceCurrentText(): Boolean = isReplacingSelection || canReplaceWholeField

    /**
     * Simple spinner adapter for language selection.
     */
    private class LanguageSpinnerAdapter(
        private val context: Context,
        private val languages: List<TranslationLanguage>
    ) : ArrayAdapter<String>(context, android.R.layout.simple_spinner_item) {

        private val displayNames: List<String>

        init {
            displayNames = languages.map { language ->
                getLanguageDisplayName(language)
            }
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        override fun getCount(): Int = languages.size

        override fun getItem(position: Int): String = displayNames[position]

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = super.getView(position, convertView, parent)
            if (view is TextView) {
                view.text = displayNames[position]
            }
            return view
        }

        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = super.getDropDownView(position, convertView, parent)
            if (view is TextView) {
                view.text = displayNames[position]
            }
            return view
        }

        fun getLanguageAt(position: Int): TranslationLanguage = languages[position]

        companion object {
            fun getLanguageDisplayName(language: TranslationLanguage): String {
                // Return language ID as fallback; actual display names come from instance
                return when (language) {
                    TranslationLanguage.AUTO_DETECT -> "Auto detect"
                    TranslationLanguage.VIETNAMESE -> "Vietnamese"
                    TranslationLanguage.ENGLISH -> "English"
                    TranslationLanguage.CHINESE_SIMPLIFIED -> "Chinese (Simplified)"
                    TranslationLanguage.CHINESE_TRADITIONAL -> "Chinese (Traditional)"
                    TranslationLanguage.JAPANESE -> "Japanese"
                    TranslationLanguage.KOREAN -> "Korean"
                    TranslationLanguage.THAI -> "Thai"
                    TranslationLanguage.INDONESIAN -> "Indonesian"
                    TranslationLanguage.FRENCH -> "French"
                    TranslationLanguage.GERMAN -> "German"
                    TranslationLanguage.SPANISH -> "Spanish"
                    TranslationLanguage.PORTUGUESE -> "Portuguese"
                    TranslationLanguage.RUSSIAN -> "Russian"
                    TranslationLanguage.ARABIC -> "Arabic"
                    TranslationLanguage.HINDI -> "Hindi"
                    else -> language.id
                }
            }
        }
    }
}
