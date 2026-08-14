package com.example.iuserusagenotifier

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.graphics.toColorInt

class CircularUsageIndicator(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    // Current progress in minutes.
    private var progress: Float = 0f
    // Maximum progress (free limit) in minutes.
    private var maxProgress: Float = 12000f

    // Static message like "Add Account".
    private var staticMessage: String = "Add Account"

    // For usage values, the text is split into two parts.
    private var primaryText: String = "0"
    private var secondaryText: String = "min used"

    // Flag indicating we are showing a static message.
    private var isStaticMessage: Boolean = true

    // Indeterminate (spinning) state used while waiting for IUT Wi-Fi.
    private var indeterminateAnimator: ValueAnimator? = null
    private var indeterminateStart: Float = 0f
    private val indeterminateSweep = 120f

    // Paint used to draw the arc.
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 20f
    }

    // Paint for static messages or combined text.
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            24f,
            context.resources.displayMetrics
        )
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    // Paint for the primary (usage number) text.
    private val primaryTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            48f,
            context.resources.displayMetrics
        )
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    // Paint for the secondary text.
    private val secondaryTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            24f,
            context.resources.displayMetrics
        )
        textAlign = Paint.Align.CENTER
    }

    // Text color depends on dark mode.
    init {
        val nightModeFlags = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val color = when (nightModeFlags) {
            Configuration.UI_MODE_NIGHT_YES -> Color.WHITE
            else -> Color.BLACK
        }
        textPaint.color = color
        primaryTextPaint.color = color
        secondaryTextPaint.color = color
    }

    // Rectangle defining the bounds of the circle.
    private val rect = RectF()

    /**
     * Animates the arc from its current value to [newProgress].
     *
     * Values are in minutes; [maxProgress] is the free limit in minutes.
     * The arc color is based on the percentage of the free limit consumed.
     */
    fun updateProgress(newProgress: Float, maxProgress: Float = 12000f) {
        stopIndeterminate()
        this.maxProgress = maxProgress.coerceAtLeast(1f)
        val target = newProgress.coerceIn(0f, this.maxProgress)
        isStaticMessage = false
        ValueAnimator.ofFloat(progress, target).apply {
            duration = 1000
            addUpdateListener { animator ->
                progress = animator.animatedValue as Float
                val percent = progress / this@CircularUsageIndicator.maxProgress
                arcPaint.color = when {
                    percent < 0.5f -> Color.GREEN
                    percent < 0.83f -> "#FFA500".toColorInt()  // Orange
                    else -> Color.RED
                }
                if (progress >= 60f) {
                    primaryText = "${(progress / 60f).toInt()}"
                    secondaryText = "hrs used"
                } else {
                    primaryText = "${progress.toInt()}"
                    secondaryText = "min used"
                }
                invalidate()
            }
            start()
        }
    }

    /** Shows a static message like "Add Account" or "Fetching...". */
    fun updateMessage(newMessage: String) {
        stopIndeterminate()
        staticMessage = newMessage
        isStaticMessage = true
        invalidate()
    }

    /** Immediately displays an error message. */
    fun showErrorMessage(message: String) {
        stopIndeterminate()
        progress = 0f
        staticMessage = message
        isStaticMessage = true
        invalidate()
    }

    /**
     * Shows a continuously spinning arc with [message] in the center.
     * Used while the app waits for the IUT campus Wi-Fi to be available.
     */
    fun showIndeterminate(message: String) {
        stopIndeterminate()
        progress = 0f
        staticMessage = message
        isStaticMessage = true
        indeterminateAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                indeterminateStart = animator.animatedValue as Float
                invalidate()
            }
            start()
        }
        invalidate()
    }

    private fun stopIndeterminate() {
        indeterminateAnimator?.cancel()
        indeterminateAnimator = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val padding = 20f
        rect.set(padding, padding, width - padding, height - padding)
        if (indeterminateAnimator != null) {
            // Spinning arc while waiting (e.g. for IUT Wi-Fi).
            arcPaint.color = Color.rgb(0, 218, 197) // teal_200
            canvas.drawArc(rect, indeterminateStart - 90f, indeterminateSweep, false, arcPaint)
        } else {
            val sweepAngle = (progress / maxProgress) * 360f
            canvas.drawArc(rect, -90f, sweepAngle, false, arcPaint)
        }

        val centerX = width / 2f
        if (isStaticMessage) {
            val yPos = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(staticMessage, centerX, yPos, textPaint)
        } else {
            val primaryY = height / 2f - (primaryTextPaint.descent() + primaryTextPaint.ascent()) / 2f
            canvas.drawText(primaryText, centerX, primaryY, primaryTextPaint)
            val marginBetweenLines = 10f
            val secondaryY = primaryY + primaryTextPaint.textSize + marginBetweenLines
            canvas.drawText(secondaryText, centerX, secondaryY, secondaryTextPaint)
        }
    }

    override fun onDetachedFromWindow() {
        stopIndeterminate()
        super.onDetachedFromWindow()
    }
}