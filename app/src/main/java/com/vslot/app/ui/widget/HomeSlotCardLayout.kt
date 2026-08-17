package com.vslot.app.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import kotlin.math.roundToInt

class HomeSlotCardLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {
    private val density = resources.displayMetrics.density
    private val tokenGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f * density
        color = Color.argb(66, 126, 235, 255)
    }
    private val tokenGoldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = Color.rgb(255, 220, 101)
    }
    private val tokenCyanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = Color.rgb(111, 238, 255)
    }
    private val titleTokenBounds = RectF()
    private val actionTokenBounds = RectF()

    init {
        clipChildren = false
        clipToPadding = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        if (width <= 0 || MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val cardHeight = (width / CARD_ASPECT_RATIO).roundToInt()
        super.onMeasure(
            widthMeasureSpec,
            MeasureSpec.makeMeasureSpec(cardHeight, MeasureSpec.EXACTLY)
        )
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (width <= 0 || height <= 0) return

        titleTokenBounds.set(
            width * TITLE_LEFT,
            height * TITLE_TOP,
            width * TITLE_RIGHT,
            height * TITLE_BOTTOM
        )
        actionTokenBounds.set(
            width * ACTION_LEFT,
            height * ACTION_TOP,
            width * ACTION_RIGHT,
            height * ACTION_BOTTOM
        )

        val pressedBoost = if (isPressed) 1.28f else 1f
        val enabledAlpha = if (isEnabled) 1f else 0.44f
        drawTokenHalo(canvas, titleTokenBounds, pressedBoost * enabledAlpha)
        drawTokenHalo(canvas, actionTokenBounds, pressedBoost * enabledAlpha)
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    private fun drawTokenHalo(canvas: Canvas, bounds: RectF, alphaScale: Float) {
        val radius = bounds.height() / 2f
        tokenGlowPaint.alpha = (66 * alphaScale).roundToInt().coerceIn(0, 255)
        tokenGoldPaint.alpha = (235 * alphaScale).roundToInt().coerceIn(0, 255)
        tokenCyanPaint.alpha = (198 * alphaScale).roundToInt().coerceIn(0, 255)
        canvas.drawRoundRect(bounds, radius, radius, tokenGlowPaint)

        val innerInset = 4f * density
        bounds.inset(innerInset, innerInset)
        canvas.drawRoundRect(bounds, radius, radius, tokenGoldPaint)

        val cyanInset = 3f * density
        bounds.inset(cyanInset, cyanInset)
        canvas.drawRoundRect(bounds, radius, radius, tokenCyanPaint)
        bounds.inset(-cyanInset - innerInset, -cyanInset - innerInset)
    }

    private companion object {
        const val CARD_ASPECT_RATIO = 980f / 620f
        const val TITLE_LEFT = 0.055f
        const val TITLE_TOP = 0.035f
        const val TITLE_RIGHT = 0.945f
        const val TITLE_BOTTOM = 0.19f
        const val ACTION_LEFT = 0.325f
        const val ACTION_TOP = 0.705f
        const val ACTION_RIGHT = 0.675f
        const val ACTION_BOTTOM = 0.875f
    }
}
