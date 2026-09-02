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
import androidx.appcompat.content.res.AppCompatResources
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import kotlin.math.roundToInt

/** Dedicated candidate row for offline Rime Pinyin; it never becomes a Latin SuggestedWords list. */
class RimeCandidateStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    interface Listener {
        fun onCandidateSelected(index: Int, generation: Long)

        fun onPreviousCandidatePage(generation: Long)

        fun onNextCandidatePage(generation: Long)

        fun onCancelComposition(generation: Long)

        fun onOutputModeSelected(mode: RimePinyinOutputMode, generation: Long)

        companion object {
            val NONE: Listener = object : Listener {
                override fun onCandidateSelected(index: Int, generation: Long) = Unit

                override fun onPreviousCandidatePage(generation: Long) = Unit

                override fun onNextCandidatePage(generation: Long) = Unit

                override fun onCancelComposition(generation: Long) = Unit

                override fun onOutputModeSelected(mode: RimePinyinOutputMode, generation: Long) = Unit
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
    private var presentation: RimeCandidatePresentation? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.rime_candidate_strip, this, true)
        modeButton = findViewById(R.id.rime_mode_button)
        previousPageButton = findViewById(R.id.rime_candidate_previous)
        nextPageButton = findViewById(R.id.rime_candidate_next)
        cancelButton = findViewById(R.id.rime_candidate_cancel)
        candidateScroll = findViewById(R.id.rime_candidate_scroll)
        candidateContainer = findViewById(R.id.rime_candidate_container)
        modeButton.setOnClickListener { showModeMenu() }
        previousPageButton.setOnClickListener { presentation?.let { listener.onPreviousCandidatePage(it.generation) } }
        nextPageButton.setOnClickListener { presentation?.let { listener.onNextCandidatePage(it.generation) } }
        cancelButton.setOnClickListener { presentation?.let { listener.onCancelComposition(it.generation) } }
    }

    fun setListener(listener: Listener) {
        this.listener = listener
    }

    fun render(nextPresentation: RimeCandidatePresentation) {
        presentation = nextPresentation
        val state = nextPresentation.state
        val mode = RimePinyinOutputMode.fromStableId(state.inputMode) ?: RimePinyinOutputMode.SIMPLIFIED
        val keyTextColor = Settings.getValues().mColors.get(ColorType.KEY_TEXT)
        modeButton.text = modeGlyph(mode)
        modeButton.setTextColor(keyTextColor)
        modeButton.contentDescription = context.getString(R.string.rime_mode_selector, modeLabel(mode))
        modeButton.isEnabled = state.error == null
        setButtonEnabled(previousPageButton, state.error == null && !state.busy && state.canPageBackward)
        setButtonEnabled(nextPageButton, state.error == null && !state.busy && state.canPageForward)
        setButtonEnabled(cancelButton, state.error == null && (state.busy || state.hasComposition))
        previousPageButton.setColorFilter(keyTextColor)
        nextPageButton.setColorFilter(keyTextColor)
        cancelButton.setColorFilter(keyTextColor)

        candidateContainer.removeAllViews()
        when {
            state.error != null -> addStatus(R.string.rime_candidates_unavailable, keyTextColor)
            state.busy -> addStatus(R.string.rime_candidates_loading, keyTextColor)
            state.candidates.isEmpty() && state.preedit.isNotEmpty() ->
                addStatus(R.string.rime_candidates_empty, keyTextColor)
            state.candidates.isEmpty() -> addStatus(R.string.rime_candidates_ready, keyTextColor)
            else -> state.candidates.forEachIndexed { index, value ->
                addCandidate(index, value, nextPresentation.generation, keyTextColor)
            }
        }
        candidateScroll.post { candidateScroll.scrollTo(0, 0) }
    }

    fun clearPresentation() {
        presentation = null
        candidateContainer.removeAllViews()
    }

    private fun addCandidate(index: Int, value: String, generation: Long, color: Int) {
        candidateContainer.addView(TextView(context, null, R.attr.suggestionWordStyle).apply {
            layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
            minWidth = MIN_TOUCH_TARGET_DP.dp
            setMaxWidth(MAX_CANDIDATE_WIDTH_DP.dp)
            gravity = Gravity.CENTER
            setSingleLine(true)
            setEllipsize(TextUtils.TruncateAt.END)
            setPadding(CANDIDATE_HORIZONTAL_PADDING_DP.dp, 0, CANDIDATE_HORIZONTAL_PADDING_DP.dp, 0)
            background = AppCompatResources.getDrawable(context, android.R.drawable.list_selector_background)
            setTextColor(color)
            text = value
            contentDescription = context.getString(
                R.string.rime_candidate_number,
                index + 1,
                value.take(MAX_ACCESSIBILITY_CANDIDATE_CHARACTERS),
            )
            setOnClickListener { listener.onCandidateSelected(index, generation) }
        })
    }

    private fun addStatus(stringRes: Int, color: Int) {
        candidateContainer.addView(TextView(context, null, R.attr.suggestionWordStyle).apply {
            layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
            minWidth = MIN_TOUCH_TARGET_DP.dp
            setMaxWidth(MAX_CANDIDATE_WIDTH_DP.dp)
            gravity = Gravity.CENTER_VERTICAL
            setSingleLine(true)
            setEllipsize(TextUtils.TruncateAt.END)
            setPadding(CANDIDATE_HORIZONTAL_PADDING_DP.dp, 0, CANDIDATE_HORIZONTAL_PADDING_DP.dp, 0)
            setTextColor(color)
            text = context.getString(stringRes)
        })
    }

    private fun showModeMenu() {
        val current = presentation ?: return
        if (current.state.error != null) return
        PopupMenu(context, modeButton).apply {
            RimePinyinOutputMode.entries.forEach { mode ->
                menu.add(MENU_GROUP_MODE, mode.ordinal, mode.ordinal, modeLabel(mode))
            }
            setOnMenuItemClickListener { item ->
                RimePinyinOutputMode.entries.getOrNull(item.itemId)?.let { mode ->
                    listener.onOutputModeSelected(mode, current.generation)
                }
                true
            }
            show()
        }
    }

    private fun modeLabel(mode: RimePinyinOutputMode): String = context.getString(
        if (mode == RimePinyinOutputMode.SIMPLIFIED) R.string.rime_mode_simplified
        else R.string.rime_mode_traditional,
    )

    private fun modeGlyph(mode: RimePinyinOutputMode): String =
        if (mode == RimePinyinOutputMode.SIMPLIFIED) "简" else "繁"

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
