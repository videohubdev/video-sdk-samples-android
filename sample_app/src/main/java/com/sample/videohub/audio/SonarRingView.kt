package com.sample.videohub.audio

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.sample.videohub.R

/**
 * "Someone is speaking" indicator: concentric rings grow outward from the
 * avatar and fade as they expand, staggered so a new ring starts before the
 * previous one fully fades.
 *
 * Only animates while [start]ed — a RecyclerView full of idle participant
 * rows costs nothing, since [stop] cancels the ValueAnimator outright rather
 * than just hiding the view.
 */
class SonarRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val ringCount: Int
    private val ringColor: Int

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private var progress = 0f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1600
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            progress = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        val ta = context.obtainStyledAttributes(attrs, R.styleable.SonarRingView)
        ringCount = ta.getInt(R.styleable.SonarRingView_ringCount, 3)
        ringColor = ta.getColor(R.styleable.SonarRingView_ringColor, 0xFF4CAF50.toInt())
        ta.recycle()
        paint.color = ringColor
    }

    fun start() {
        visibility = VISIBLE
        if (!animator.isStarted) animator.start()
    }

    fun stop() {
        animator.cancel()
        progress = 0f
        visibility = INVISIBLE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val outerRadius = minOf(width, height) / 2f
        val innerRadius = outerRadius * 0.5f // hugs the avatar it surrounds

        for (i in 0 until ringCount) {
            val phase = (progress + i.toFloat() / ringCount) % 1f
            val radius = innerRadius + (outerRadius - innerRadius) * phase

            paint.alpha = ((1f - phase) * 255).toInt().coerceIn(0, 255)
            paint.strokeWidth = 5f * (1f - phase * 0.6f)
            canvas.drawCircle(cx, cy, radius, paint)
        }
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }
}
