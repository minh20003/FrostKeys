/*
 * SPDX-License-Identifier: GPL-3.0-only
 */
package helium314.keyboard.latin.suggestions

import android.animation.ObjectAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.HorizontalScrollView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class ElasticHorizontalScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : HorizontalScrollView(context, attrs, defStyleAttr) {

    private val maxElasticOffset = 72f * resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var lastTouchX = 0f
    private var downTouchX = 0f
    private var downTouchY = 0f
    private var movedBeyondTouchSlop = false
    private var returnAnimator: ObjectAnimator? = null

    init {
        overScrollMode = OVER_SCROLL_NEVER
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            cancelReturnAnimation()
            lastTouchX = event.x
        }
        return super.onInterceptTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelReturnAnimation()
                lastTouchX = event.x
                downTouchX = event.x
                downTouchY = event.y
                movedBeyondTouchSlop = false
            }
            MotionEvent.ACTION_MOVE -> {
                movedBeyondTouchSlop = movedBeyondTouchSlop ||
                    abs(event.x - downTouchX) > touchSlop ||
                    abs(event.y - downTouchY) > touchSlop
                applyElasticOffset(lastTouchX - event.x)
                lastTouchX = event.x
            }
            MotionEvent.ACTION_UP -> {
                if (!movedBeyondTouchSlop) performClick()
                animateBack()
            }
            MotionEvent.ACTION_CANCEL -> animateBack()
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }

    private fun applyElasticOffset(deltaX: Float) {
        val content = getChildAt(0) ?: return
        val maxScrollX = max(0, content.width - width + paddingLeft + paddingRight)
        if (maxScrollX <= 0) return

        val currentOffset = content.translationX
        val pullingStart = scrollX <= 0 && deltaX < 0
        val pullingEnd = scrollX >= maxScrollX && deltaX > 0
        if (!pullingStart && !pullingEnd && currentOffset == 0f) return

        val resistance = 0.34f * (1f - min(1f, abs(currentOffset) / maxElasticOffset))
        content.translationX = (currentOffset - deltaX * resistance)
            .coerceIn(-maxElasticOffset, maxElasticOffset)
    }

    private fun animateBack() {
        val content = getChildAt(0) ?: return
        if (content.translationX == 0f) return
        cancelReturnAnimation()
        returnAnimator = ObjectAnimator.ofFloat(content, "translationX", 0f).apply {
            duration = 220L
            interpolator = DecelerateInterpolator(1.8f)
            start()
        }
    }

    private fun cancelReturnAnimation() {
        returnAnimator?.cancel()
        returnAnimator = null
    }
}
