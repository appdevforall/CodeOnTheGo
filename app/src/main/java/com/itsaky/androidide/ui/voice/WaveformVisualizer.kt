/*
 *  This file is part of CodeOnTheGo.
 *
 *  CodeOnTheGo is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  CodeOnTheGo is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with CodeOnTheGo.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.ui.voice

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.itsaky.androidide.R
import kotlin.math.abs
import kotlin.math.sin

/**
 * Custom view that visualizes audio waveform during voice recording.
 * Creates animated bars that pulse based on audio amplitude.
 */
class WaveformVisualizer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val DEFAULT_BAR_COUNT = 5
        private const val DEFAULT_BAR_WIDTH_DP = 4f
        private const val DEFAULT_BAR_SPACING_DP = 8f
        private const val DEFAULT_MIN_BAR_HEIGHT_DP = 8f
        private const val DEFAULT_MAX_BAR_HEIGHT_DP = 64f
        private const val ANIMATION_DURATION_MS = 300L
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.primaryColor)
    }

    private val barCount = DEFAULT_BAR_COUNT
    private val barWidth = dpToPx(DEFAULT_BAR_WIDTH_DP)
    private val barSpacing = dpToPx(DEFAULT_BAR_SPACING_DP)
    private val minBarHeight = dpToPx(DEFAULT_MIN_BAR_HEIGHT_DP)
    private val maxBarHeight = dpToPx(DEFAULT_MAX_BAR_HEIGHT_DP)

    private val amplitudes = FloatArray(barCount) { 0f }
    private val targetAmplitudes = FloatArray(barCount) { 0f }
    private var isAnimating = false
    private var animator: ValueAnimator? = null

    // For idle animation (when no audio input)
    private var idleAnimationProgress = 0f
    private var idleAnimator: ValueAnimator? = null

    init {
        // Start idle animation by default
        startIdleAnimation()
    }

    /**
     * Update waveform with new audio amplitudes.
     * @param newAmplitudes Array of amplitude values (0.0 to 1.0)
     */
    fun updateAmplitudes(newAmplitudes: FloatArray) {
        stopIdleAnimation()

        if (newAmplitudes.size != barCount) {
            // If size mismatch, interpolate or use default
            for (i in 0 until barCount) {
                val index = (i * newAmplitudes.size / barCount).coerceIn(0, newAmplitudes.size - 1)
                targetAmplitudes[i] = newAmplitudes[index].coerceIn(0f, 1f)
            }
        } else {
            newAmplitudes.copyInto(targetAmplitudes)
        }

        animateToTarget()
    }

    /**
     * Start idle animation (gentle pulse when no audio).
     */
    fun startIdleAnimation() {
        stopIdleAnimation()

        idleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2000L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()

            addUpdateListener { animation ->
                idleAnimationProgress = animation.animatedValue as Float

                // Create wave pattern
                for (i in 0 until barCount) {
                    val phase = i * 0.5f
                    val wave = sin((idleAnimationProgress * Math.PI * 2 + phase).toFloat())
                    amplitudes[i] = (wave + 1f) / 2f * 0.3f // 0-30% amplitude
                }

                invalidate()
            }

            start()
        }
    }

    /**
     * Stop idle animation.
     */
    fun stopIdleAnimation() {
        idleAnimator?.cancel()
        idleAnimator = null
    }

    /**
     * Stop all animations and reset.
     */
    fun reset() {
        stopIdleAnimation()
        animator?.cancel()
        amplitudes.fill(0f)
        targetAmplitudes.fill(0f)
        invalidate()
    }

    /**
     * Animate from current amplitudes to target amplitudes.
     */
    private fun animateToTarget() {
        animator?.cancel()

        val startAmplitudes = amplitudes.copyOf()

        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ANIMATION_DURATION_MS
            interpolator = LinearInterpolator()

            addUpdateListener { animation ->
                val progress = animation.animatedValue as Float

                for (i in amplitudes.indices) {
                    amplitudes[i] = startAmplitudes[i] +
                        (targetAmplitudes[i] - startAmplitudes[i]) * progress
                }

                invalidate()
            }

            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val centerX = width / 2f
        val centerY = height / 2f

        // Calculate total width of all bars and spacing
        val totalWidth = barCount * barWidth + (barCount - 1) * barSpacing
        var x = centerX - totalWidth / 2f

        for (i in 0 until barCount) {
            val amplitude = amplitudes[i].coerceIn(0f, 1f)
            val barHeight = minBarHeight + (maxBarHeight - minBarHeight) * amplitude

            val rect = RectF(
                x,
                centerY - barHeight / 2f,
                x + barWidth,
                centerY + barHeight / 2f
            )

            // Draw rounded rectangle bar
            canvas.drawRoundRect(rect, barWidth / 2f, barWidth / 2f, paint)

            x += barWidth + barSpacing
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val totalWidth = (barCount * barWidth + (barCount - 1) * barSpacing).toInt()
        val totalHeight = maxBarHeight.toInt()

        val width = resolveSize(totalWidth, widthMeasureSpec)
        val height = resolveSize(totalHeight, heightMeasureSpec)

        setMeasuredDimension(width, height)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        reset()
    }

    private fun dpToPx(dp: Float): Float {
        return dp * context.resources.displayMetrics.density
    }
}
