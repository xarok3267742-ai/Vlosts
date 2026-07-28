package com.vslot.app.ui.widget

import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BitmapNumberViewContractTest {
    @Test
    fun `initialization promotes only automatic accessibility mode`() {
        val initBlock = source
            .substringAfter("init {")
            .substringBefore("fun setNumber")

        assertTrue(
            initBlock.contains(
                "if (importantForAccessibility == IMPORTANT_FOR_ACCESSIBILITY_AUTO)"
            )
        )
        assertTrue(
            initBlock.contains(
                "importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES"
            )
        )
    }

    @Test
    fun `changed characters reuse existing image views and trim only trailing surplus`() {
        val setCharactersBlock = source
            .substringAfter("fun setCharacters(")
            .substringBefore("private fun createGlyphView")

        assertFalse(setCharactersBlock.contains("removeAllViews()"))
        assertFalse(setCharactersBlock.contains("mapNotNull"))
        assertTrue(setCharactersBlock.contains("var glyphIndex = 0"))
        assertTrue(setCharactersBlock.contains("value.forEach { character ->"))
        assertTrue(setCharactersBlock.contains("if (glyphIndex < childCount)"))
        assertTrue(setCharactersBlock.contains("getChildAt(glyphIndex) as ImageView"))
        assertTrue(setCharactersBlock.contains("createGlyphView().also(::addView)"))
        assertTrue(setCharactersBlock.contains("imageView.setImageResourceIfChanged(glyph.resId)"))
        assertTrue(setCharactersBlock.contains("glyph.updateLayoutParams("))
        assertTrue(setCharactersBlock.contains("removeViews(glyphIndex, childCount - glyphIndex)"))
    }

    @Test
    fun `reused image views update their existing layout params`() {
        val layoutParamsBlock = source
            .substringAfter("fun updateLayoutParams(")
            .substringBeforeLast("\n    }")

        assertTrue(
            layoutParamsBlock.contains(
                "val params = imageView.layoutParams as LinearLayout.LayoutParams"
            )
        )
        assertTrue(layoutParamsBlock.contains("params.width = width"))
        assertTrue(layoutParamsBlock.contains("params.weight = layoutWeight"))
        assertTrue(layoutParamsBlock.contains("params.marginStart = spacingPx"))
        assertTrue(layoutParamsBlock.contains("params.marginEnd = spacingPx"))
        assertTrue(layoutParamsBlock.contains("imageView.layoutParams = params"))
    }

    @Test
    fun `bitmap balance digits format long values without narrowing to int`() {
        assertTrue(source.contains("fun setNumber(value: Long"))
        assertTrue(source.contains("private fun Long.formattedNumber(showPlus: Boolean)"))
        assertTrue(source.contains("for (index in firstDigitIndex until raw.length)"))
        assertFalse(source.contains("chunked("))
        assertFalse(source.contains("joinToString("))
        assertFalse(source.contains("value.toInt()"))
    }

    private companion object {
        val source: String = Path.of(
            "src/main/java/com/vslot/app/ui/widget/BitmapNumberView.kt"
        ).readText()
    }
}
