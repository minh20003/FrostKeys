// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.cjk

import android.content.Context
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import androidx.appcompat.content.res.AppCompatResources
import kotlin.math.roundToInt

/**
 * Dedicated Mozc candidate strip hosted as an external view by the normal suggestion container.
 *
 * It intentionally has no dependency on `SuggestedWords` or `InputLogic`: candidate taps return
 * the visible position plus the immutable runtime generation to [Listener]. The runtime verifies
 * that generation and maps the position to Mozc's native candidate id before committing text.
 */
class MozcCandidateStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    interface Listener {
        fun onCandidateSelected(index: Int, generation: Long)

        fun onPreviousCandidatePage(generation: Long)

        fun onNextCandidatePage(generation: Long)

        fun onCancelComposition(generation: Long)

        fun onInputModeSelected(inputMode: MozcInputMode, generation: Long)

        companion object {
            /** Makes an XML-preview construction inert until the IME installs its listener. */
            val NONE: Listener = object : Listener {
                override fun onCandidateSelected(index: Int, generation: Long) = Unit

                override fun onPreviousCandidatePage(generation: Long) = Unit

                override fun onNextCandidatePage(generation: Long) = Unit

                override fun onCancelComposition(generation: Long) = Unit

                override fun onInputModeSelected(inputMode: MozcInputMode, generation: Long) = Unit
            }
        }
    }

    private val modeButton: TextView
    private val previousPageButton: ImageButton
    private val nextPageButton: ImageButton
    private val cancelButton: ImageButton
    private val candidateScroll: HorizontalScrollView
    private val candidateContainer: LinearLayout
    private var listener: Listener = Listener.NONE
    private var presentation: MozcCandidatePresentation? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.mozc_candidate_strip, this, true)
        modeButton = findViewById(R.id.mozc_mode_button)
        previousPageButton = findViewById(R.id.mozc_candidate_previous)
        nextPageButton = findViewById(R.id.mozc_candidate_next)
        cancelButton = findViewById(R.id.mozc_candidate_cancel)
        candidateScroll = findViewById(R.id.mozc_candidate_scroll)
        candidateContainer = findViewById(R.id.mozc_candidate_container)

        modeButton.setOnClickListener { showModeMenu() }
        previousPageButton.setOnClickListener {
            presentation?.let { listener.onPreviousCandidatePage(it.generation) }
        }
        nextPageButton.setOnClickListener {
            presentation?.let { listener.onNextCandidatePage(it.generation) }
        }
        cancelButton.setOnClickListener {
            presentation?.let { listener.onCancelComposition(it.generation) }
        }
    }

    /** Installs the current IME runtime callback after this standard Android view is created. */
    fun setListener(listener: Listener) {
        this.listener = listener
    }

    /** Replaces the whole native candidate window; callers may safely reuse this view. */
    fun render(nextPresentation: MozcCandidatePresentation) {
        presentation = nextPresentation
        val state = nextPresentation.state
        val mode = MozcInputMode.fromStableId(state.inputMode) ?: MozcInputMode.HIRAGANA
        val keyTextColor = Settings.getValues().mColors.get(ColorType.KEY_TEXT)
        modeButton.text = mode.displayGlyph
        modeButton.setTextColor(keyTextColor)
        previousPageButton.setColorFilter(keyTextColor)
        nextPageButton.setColorFilter(keyTextColor)
        cancelButton.setColorFilter(keyTextColor)
        modeButton.contentDescription = context.getString(R.string.mozc_mode_selector, modeLabel(mode))
        modeButton.isEnabled = state.error == null
        setButtonEnabled(previousPageButton, state.error == null && !state.busy && state.canPageBackward)
        setButtonEnabled(nextPageButton, state.error == null && !state.busy && state.canPageForward)
        setButtonEnabled(cancelButton, state.error == null && (state.busy || state.hasComposition))

        candidateContainer.removeAllViews()
        when {
            state.error != null -> addStatus(R.string.mozc_candidates_unavailable)
            state.busy -> addStatus(R.string.mozc_candidates_loading)
            state.candidates.isEmpty() && state.preedit.isNotEmpty() -> {
                addStatus(R.string.mozc_candidates_empty)
            }
            state.candidates.isEmpty() -> addStatus(R.string.mozc_candidates_ready)
            else -> state.candidates.forEachIndexed { index, value ->
                addCandidate(index, value, nextPresentation.generation, keyTextColor)
            }
        }
        candidateScroll.post { candidateScroll.scrollTo(0, 0) }
    }

    /** Clears retained labels and makes clicks from this detached view inert at the runtime gate. */
    fun clearPresentation() {
        presentation = null
        candidateContainer.removeAllViews()
    }

    private fun addCandidate(index: Int, value: String, generation: Long, keyTextColor: Int) {
        val candidate = TextView(context, null, R.attr.suggestionWordStyle).apply {
            layoutParams = LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.MATCH_PARENT,
            )
            minWidth = MIN_TOUCH_TARGET_DP.dp
            setMaxWidth(MAX_CANDIDATE_WIDTH_DP.dp)
            gravity = Gravity.CENTER
            setSingleLine(true)
            setEllipsize(TextUtils.TruncateAt.END)
            setPadding(CANDIDATE_HORIZONTAL_PADDING_DP.dp, 0, CANDIDATE_HORIZONTAL_PADDING_DP.dp, 0)
            background = AppCompatResources.getDrawable(
                context,
                android.R.drawable.list_selector_background,
            )
            setTextColor(keyTextColor)
            text = value
            contentDescription = context.getString(
                R.string.mozc_candidate_number,
                index + 1,
                value.take(MAX_ACCESSIBILITY_CANDIDATE_CHARACTERS),
            )
            setOnClickListener { listener.onCandidateSelected(index, generation) }
        }
        candidateContainer.addView(candidate)
    }

    private fun addStatus(stringRes: Int) {
        candidateContainer.addView(TextView(context, null, R.attr.suggestionWordStyle).apply {
            layoutParams = LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.MATCH_PARENT,
            )
            minWidth = MIN_TOUCH_TARGET_DP.dp
            setMaxWidth(MAX_CANDIDATE_WIDTH_DP.dp)
            gravity = Gravity.CENTER_VERTICAL
            setSingleLine(true)
            setEllipsize(TextUtils.TruncateAt.END)
            setPadding(CANDIDATE_HORIZONTAL_PADDING_DP.dp, 0, CANDIDATE_HORIZONTAL_PADDING_DP.dp, 0)
            setTextColor(Settings.getValues().mColors.get(ColorType.KEY_TEXT))
            text = context.getString(stringRes)
        })
    }

    private fun showModeMenu() {
        val current = presentation ?: return
        if (current.state.error != null) return
        PopupMenu(context, modeButton).apply {
            MozcInputMode.entries.forEach { mode ->
                menu.add(MENU_GROUP_MODE, mode.ordinal, mode.ordinal, modeLabel(mode))
            }
            setOnMenuItemClickListener { item ->
                MozcInputMode.entries.getOrNull(item.itemId)?.let { mode ->
                    listener.onInputModeSelected(mode, current.generation)
                }
                true
            }
            show()
        }
    }

    private fun modeLabel(mode: MozcInputMode): String = context.getString(
        when (mode) {
            MozcInputMode.HIRAGANA -> R.string.mozc_mode_hiragana
            MozcInputMode.KATAKANA -> R.string.mozc_mode_katakana
            MozcInputMode.LATIN -> R.string.mozc_mode_latin
        },
    )

    private fun setButtonEnabled(button: View, enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = if (enabled) ENABLED_ALPHA else DISABLED_ALPHA
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).roundToInt()

    private companion object {
        const val MENU_GROUP_MODE = 1
        const val MIN_TOUCH_TARGET_DP = 48
        const val CANDIDATE_HORIZONTAL_PADDING_DP = 12
        const val MAX_CANDIDATE_WIDTH_DP = 280
        const val MAX_ACCESSIBILITY_CANDIDATE_CHARACTERS = 512
        const val ENABLED_ALPHA = 1f
        const val DISABLED_ALPHA = 0.38f
    }
}
