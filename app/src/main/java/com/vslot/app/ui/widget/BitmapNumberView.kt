package com.vslot.app.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import com.vslot.app.R
import kotlin.math.roundToInt

class BitmapNumberView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {
    private var lastCharacters: String? = null
    private var lastSpacingPx = Int.MIN_VALUE
    private var lastCompactSeparators = false
    private var lastFixedGlyphBaseWidthDp: Float? = null
    private var autoGlyphBaseWidthDp: Float? = null
    var displayedCharacters: String = ""
        private set

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        clipChildren = false
        clipToPadding = false
        if (importantForAccessibility == IMPORTANT_FOR_ACCESSIBILITY_AUTO) {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        }
    }

    fun setNumber(value: Int, showPlus: Boolean = false) {
        setNumber(value.toLong(), showPlus)
    }

    fun setNumber(value: Long, showPlus: Boolean = false) {
        val formatted = value.formattedNumber(showPlus)
        setCharacters(formatted)
        contentDescription = formatted
    }

    fun setVisualNumber(value: Int, showPlus: Boolean = false) {
        setVisualNumber(value.toLong(), showPlus)
    }

    fun setVisualNumber(value: Long, showPlus: Boolean = false) {
        setCharacters(value.formattedNumber(showPlus))
    }

    fun setCharacters(
        value: String,
        spacingPx: Int = 1,
        compactSeparators: Boolean = false,
        fixedGlyphBaseWidthDp: Float? = null
    ) {
        displayedCharacters = value
        if (
            value == lastCharacters &&
            spacingPx == lastSpacingPx &&
            compactSeparators == lastCompactSeparators &&
            fixedGlyphBaseWidthDp == lastFixedGlyphBaseWidthDp
        ) {
            return
        }
        lastCharacters = value
        lastSpacingPx = spacingPx
        lastCompactSeparators = compactSeparators
        lastFixedGlyphBaseWidthDp = fixedGlyphBaseWidthDp
        if (fixedGlyphBaseWidthDp == null) {
            autoGlyphBaseWidthDp = calculateAutoGlyphBaseWidthDp(value, compactSeparators)
        }
        renderCharacters(value, spacingPx, compactSeparators, fixedGlyphBaseWidthDp ?: autoGlyphBaseWidthDp)
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (lastFixedGlyphBaseWidthDp != null || width <= 0 || height <= 0) return
        val value = lastCharacters ?: return
        val nextBaseWidthDp = calculateAutoGlyphBaseWidthDp(value, lastCompactSeparators) ?: return
        if (nextBaseWidthDp == autoGlyphBaseWidthDp) return
        autoGlyphBaseWidthDp = nextBaseWidthDp
        renderCharacters(value, lastSpacingPx, lastCompactSeparators, nextBaseWidthDp)
    }

    private fun renderCharacters(
        value: String,
        spacingPx: Int,
        compactSeparators: Boolean,
        fixedGlyphBaseWidthDp: Float?
    ) {
        var glyphIndex = 0
        value.forEach { character ->
            val glyph = character.toGlyph(compactSeparators) ?: return@forEach
            val imageView = if (glyphIndex < childCount) {
                getChildAt(glyphIndex) as ImageView
            } else {
                createGlyphView().also(::addView)
            }
            imageView.setImageResourceIfChanged(glyph.resId)
            glyph.updateLayoutParams(
                imageView = imageView,
                spacingPx = spacingPx,
                fixedGlyphBaseWidthDp = fixedGlyphBaseWidthDp,
                density = resources.displayMetrics.density
            )
            glyphIndex += 1
        }
        if (childCount > glyphIndex) {
            removeViews(glyphIndex, childCount - glyphIndex)
        }
    }

    private fun calculateAutoGlyphBaseWidthDp(value: String, compactSeparators: Boolean): Float? {
        if (width <= 0 || height <= 0) return null
        val glyphWeight = value.sumOf { character ->
            character.toGlyph(compactSeparators)?.weight?.toDouble() ?: 0.0
        }.toFloat()
        if (glyphWeight <= 0f) return null
        val density = resources.displayMetrics.density
        val contentWidthPx = (width - paddingStart - paddingEnd).coerceAtLeast(1)
        val spacingWidthPx = value.length * lastSpacingPx.coerceAtLeast(0) * 2
        val widthBoundDp = ((contentWidthPx - spacingWidthPx).coerceAtLeast(1) / glyphWeight) / density
        val heightBoundDp = ((height - paddingTop - paddingBottom).coerceAtLeast(1) / density) *
            AUTO_GLYPH_WIDTH_TO_HEIGHT_RATIO
        return minOf(widthBoundDp, heightBoundDp).coerceAtLeast(MIN_AUTO_GLYPH_WIDTH_DP)
    }

    private fun createGlyphView(): ImageView {
        return ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            clipToOutline = false
            contentDescription = null
        }
    }

    private fun Long.formattedNumber(showPlus: Boolean): String {
        val raw = toString()
        val firstDigitIndex = if (raw.startsWith('-')) 1 else 0
        val digitCount = raw.length - firstDigitIndex
        return buildString(raw.length + digitCount / 3 + 1) {
            when {
                firstDigitIndex == 1 -> append('-')
                showPlus && this@formattedNumber > 0L -> append('+')
            }
            for (index in firstDigitIndex until raw.length) {
                val digitsRemaining = raw.length - index
                if (index > firstDigitIndex && digitsRemaining % 3 == 0) append(' ')
                append(raw[index])
            }
        }
    }

    private fun Char.toGlyph(compactSeparators: Boolean): Glyph? {
        return when (this) {
            in '0'..'9' -> DIGIT_GLYPHS[digitToInt()]
            ',' -> COMMA_GLYPH
            ' ' -> if (compactSeparators) COMPACT_SPACE_GLYPH else SPACE_GLYPH
            '+' -> PLUS_GLYPH
            '-' -> MINUS_GLYPH
            '/' -> if (compactSeparators) COMPACT_SLASH_GLYPH else SLASH_GLYPH
            ':' -> if (compactSeparators) COMPACT_COLON_GLYPH else COLON_GLYPH
            'x', 'X' -> X_GLYPH
            else -> null
        }
    }

    private data class Glyph(
        @param:DrawableRes val resId: Int,
        val weight: Float = 1f
    ) {
        fun updateLayoutParams(
            imageView: ImageView,
            spacingPx: Int,
            fixedGlyphBaseWidthDp: Float?,
            density: Float
        ) {
            val params = imageView.layoutParams as LinearLayout.LayoutParams
            val width: Int
            val layoutWeight: Float
            if (fixedGlyphBaseWidthDp == null) {
                width = 0
                layoutWeight = weight
            } else {
                width = (fixedGlyphBaseWidthDp * weight * density).roundToInt().coerceAtLeast(1)
                layoutWeight = 0f
            }
            if (
                params.width != width ||
                params.height != LayoutParams.MATCH_PARENT ||
                params.weight != layoutWeight ||
                params.marginStart != spacingPx ||
                params.marginEnd != spacingPx
            ) {
                params.width = width
                params.height = LayoutParams.MATCH_PARENT
                params.weight = layoutWeight
                params.marginStart = spacingPx
                params.marginEnd = spacingPx
                imageView.layoutParams = params
            }
        }
    }

    private companion object {
        const val AUTO_GLYPH_WIDTH_TO_HEIGHT_RATIO = 0.68f
        const val MIN_AUTO_GLYPH_WIDTH_DP = 4f
        val DIGIT_GLYPHS = arrayOf(
            Glyph(R.drawable.digit_0),
            Glyph(R.drawable.digit_1),
            Glyph(R.drawable.digit_2),
            Glyph(R.drawable.digit_3),
            Glyph(R.drawable.digit_4),
            Glyph(R.drawable.digit_5),
            Glyph(R.drawable.digit_6),
            Glyph(R.drawable.digit_7),
            Glyph(R.drawable.digit_8),
            Glyph(R.drawable.digit_9)
        )
        val COMMA_GLYPH = Glyph(R.drawable.digit_comma, weight = 0.35f)
        val SPACE_GLYPH = Glyph(R.drawable.digit_space, weight = 0.35f)
        val COMPACT_SPACE_GLYPH = Glyph(R.drawable.digit_space, weight = 0.22f)
        val PLUS_GLYPH = Glyph(R.drawable.digit_plus, weight = 0.75f)
        val MINUS_GLYPH = Glyph(R.drawable.digit_minus, weight = 0.75f)
        val SLASH_GLYPH = Glyph(R.drawable.digit_slash, weight = 0.72f)
        val COMPACT_SLASH_GLYPH = Glyph(R.drawable.digit_slash, weight = 0.92f)
        val COLON_GLYPH = Glyph(R.drawable.digit_colon, weight = 0.42f)
        val COMPACT_COLON_GLYPH = Glyph(R.drawable.digit_colon, weight = 0.34f)
        val X_GLYPH = Glyph(R.drawable.digit_x, weight = 0.85f)
    }
}
