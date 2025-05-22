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
import androidx.core.graphics.toColorInt

class CircularUsageIndicator(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private var progress: Float = 0f
    private var targetProgress: Float = 0f

    // When showing a static message like "Add Account" or "Network Error"
    private var staticMessage: String = "Add Account"

    // For usage values, we split the text into two parts.
    private var primaryText: String = "0"       // For the number, e.g., "7025"
    private var secondaryText: String = "min used" // Label text in a smaller font

    // Flag indicates if we are showing a static message.
    private var isStaticMessage: Boolean = true

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
            48f, // Larger text for the number
            context.resources.displayMetrics
        )
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    // Paint for the secondary ("min used") text.
    private val secondaryTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            24f, // Slightly smaller
            context.resources.displayMetrics
        )
        textAlign = Paint.Align.CENTER
    }

    // Set text color based on dark mode.
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
     * Animate progress from its current value to newProgress over 1 second.
     */
    fun updateProgress(newProgress: Float) {
        // If newProgress is above 12000 and a static message is shown,
        // then just invalidate.
        if (newProgress > 12000f && isStaticMessage) {
            invalidate()
            return
        }
        // Now we’re animating usage. So, unset the static message flag.
        isStaticMessage = false
        targetProgress = newProgress
        ValueAnimator.ofFloat(progress, newProgress).apply {
            duration = 1000
            addUpdateListener { animator ->
                progress = animator.animatedValue as Float
                // Change arc color based on progress.
                when {
                    progress < 6000f -> arcPaint.color = Color.GREEN
                    progress < 10000f -> arcPaint.color = "#FFA500".toColorInt()  // Orange
                    else -> arcPaint.color = Color.RED
                }
                // Set the text values:
                primaryText = "${progress.toInt()}"  // e.g., "7025"
                secondaryText = "min used"
                invalidate()
            }
            start()
        }
    }

    /**
     * Update message directly (for static messages like "Add Account" or "Network Error").
     */
    fun updateMessage(newMessage: String) {
        staticMessage = newMessage
        isStaticMessage = true
        invalidate()
    }

    /**
     * Immediately display an error message.
     */
    fun showErrorMessage(message: String) {
        progress = 0f
        staticMessage = message
        isStaticMessage = true
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val padding = 20f
        rect.set(padding, padding, width - padding, height - padding)
        val sweepAngle = (progress / 12000f) * 360f
        canvas.drawArc(rect, -90f, sweepAngle, false, arcPaint)

        val centerX = width / 2f
        // Depending on whether we have a static message or usage data,
        // we draw accordingly.
        if (isStaticMessage) {
            // Draw the staticMessage in one centered line.
            val yPos = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(staticMessage, centerX, yPos, textPaint)
        } else {
            // Draw primaryText (the usage number) on one line...
            val primaryY = height / 2f - (primaryTextPaint.descent() + primaryTextPaint.ascent()) / 2f
            canvas.drawText(primaryText, centerX, primaryY, primaryTextPaint)
            // And then draw secondaryText ("min used") beneath it.
            val marginBetweenLines = 10f
            val secondaryY = primaryY + primaryTextPaint.textSize + marginBetweenLines
            canvas.drawText(secondaryText, centerX, secondaryY, secondaryTextPaint)
        }
    }
}
