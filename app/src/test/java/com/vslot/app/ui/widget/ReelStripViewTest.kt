package com.vslot.app.ui.widget

import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ReelStripViewTest {
    @Test
    fun `identical renderer updates are idempotent`() {
        val state = ReelStripRenderState(symbolCount = 8)
        val resources = intArrayOf(1, 2, 3, 4, 5, 6, 7, 8)

        assertTrue(state.update(resources, newSymbolAlpha = 0.94f, newSymbolScaleY = 1.12f))
        assertFalse(
            state.update(
                resources.copyOf(),
                newSymbolAlpha = 0.94f,
                newSymbolScaleY = 1.12f
            )
        )

        resources[0] = 9
        assertTrue(state.update(resources, newSymbolAlpha = 0.94f, newSymbolScaleY = 1.12f))
        assertFalse(state.update(resources, newSymbolAlpha = 0.94f, newSymbolScaleY = 1.12f))
        assertTrue(state.update(resources, newSymbolAlpha = 1f, newSymbolScaleY = 1f))
        assertFalse(state.update(resources, newSymbolAlpha = 1f, newSymbolScaleY = 1f))
        assertTrue(state.clear())
        assertFalse(state.clear())
    }

    @Test
    fun `drawable cache is bounded and reuses loaded entries`() {
        var loads = 0
        val cache = BoundedResourceCache(maxEntries = 2) { resourceId ->
            loads += 1
            "drawable-$resourceId-$loads"
        }

        val first = cache[1]
        assertSame(first, cache[1])
        cache[2]
        cache[3]

        assertEquals(2, cache.size)
        assertEquals(3, loads)
        cache[1]
        assertEquals(4, loads)
        cache.clear()
        assertEquals(0, cache.size)
    }

    @Test
    fun `visible symbol range excludes clipped strip cells`() {
        assertEquals(0..2, visibleSymbolIndexes(0, 300, cellHeight = 100f, symbolCount = 8))
        assertEquals(2..5, visibleSymbolIndexes(250, 525, cellHeight = 100f, symbolCount = 8))
        assertEquals(7..7, visibleSymbolIndexes(799, 800, cellHeight = 100f, symbolCount = 8))
        assertEquals(null, visibleSymbolIndexes(800, 900, cellHeight = 100f, symbolCount = 8))
        assertEquals(null, visibleSymbolIndexes(-100, 0, cellHeight = 100f, symbolCount = 8))
    }

    @Test
    fun `spin hot path binds one renderer without image resource calls`() {
        val rendererSource = source(
            "src/main/java/com/vslot/app/ui/widget/ReelStripView.kt"
        )
        val fragmentSource = source(
            "src/main/java/com/vslot/app/ui/slot/SlotFragment.kt"
        )
        val renderSpinStripColumn = fragmentSource
            .substringAfter("private fun renderSpinStripColumn(")
            .substringBefore("private fun animateReelColumnSpin")
        val setupSpinStripLayer = fragmentSource
            .substringAfter("private fun setupReelSpinStripLayer()")
            .substringBefore("private fun setupReelBrakeLayer()")

        assertTrue(setupSpinStripLayer.contains("ReelStripView(requireContext(), drawableCache)"))
        assertFalse(setupSpinStripLayer.contains("ImageView("))
        assertTrue(renderSpinStripColumn.contains("strip.setSymbols("))
        assertFalse(renderSpinStripColumn.contains("setImageResource"))
        assertFalse(rendererSource.contains("setImageResource"))
        assertTrue(
            rendererSource.contains(
                "if (!renderState.update(resourceIds, symbolAlpha, symbolScaleY)) return"
            )
        )
        assertTrue(rendererSource.contains("const val SYMBOL_COUNT = 8"))
        assertTrue(rendererSource.contains("private const val SYMBOL_PADDING_DP = 2"))
        assertTrue(rendererSource.contains("val visibleIndexes = visibleSymbolIndexes("))
        assertTrue(rendererSource.contains("fun setDrawnSymbolRange(firstIndex: Int, lastIndex: Int)"))
        assertTrue(rendererSource.contains("maxOf(visibleIndexes.first, firstDrawnSymbolIndex)"))
        assertTrue(rendererSource.contains("fun preload(@DrawableRes resourceIds: IntArray)"))
        assertTrue(fragmentSource.contains("reelSpinDrawableCache?.preload(symbolResources)"))
        assertTrue(fragmentSource.contains("preloadSpinPresentationResources(state.config, state.pendingResult)"))
        assertTrue(fragmentSource.contains("prepareReelSpinStripDimensions()"))
        assertTrue(fragmentSource.contains("const val REEL_SPIN_TICK_MS = 20L"))
        assertTrue(fragmentSource.contains("const val REEL_SPIN_COLUMNS_PER_TICK = 1"))
        assertTrue(fragmentSource.contains("columnScanStart = (columnScanStart + 1) % REEL_COUNT"))
        assertFalse(fragmentSource.contains("setLayerType(View.LAYER_TYPE_HARDWARE"))
        assertFalse(fragmentSource.contains("adjustViewBounds = true"))
        assertTrue(fragmentSource.contains("if (reelMotionStreakModes[column] == mode) return"))
        assertTrue(fragmentSource.contains("!shouldUseRichSpinEffects() || phase != ReelSpinPhase.Acceleration"))
        assertTrue(fragmentSource.contains("strip.setDrawnSymbolRange("))
        assertTrue(fragmentSource.contains("launch(Dispatchers.Default)"))
        assertTrue(fragmentSource.contains("delay(RESULT_DRAWABLE_PRELOAD_DELAY_MS)"))
        assertTrue(fragmentSource.contains("PerformanceHintApi31.createSession("))
        assertTrue(fragmentSource.contains("reportSpinPerformanceWork("))
        assertTrue(fragmentSource.contains("shouldUseRichSpinEffects()"))
        assertTrue(
            fragmentSource.contains(
                "!ValueAnimator.areAnimatorsEnabled() || !shouldUseRichSpinEffects()"
            )
        )
        assertTrue(
            fragmentSource.contains(
                "strip.scaleY = if (richEffects) reelSpinStartScaleY(phase) else REEL_SPIN_SYMBOL_BLUR_SCALE_Y"
            )
        )
        assertTrue(fragmentSource.contains("val motion = strip.animate()"))
        assertTrue(fragmentSource.contains("val shouldAnimateHighlights = shouldUseRichSpinEffects() &&"))
        assertTrue(fragmentSource.contains("val shouldAnimate = animate &&"))
        assertTrue(fragmentSource.contains("shouldUseRichSpinEffects() &&"))
        assertTrue(fragmentSource.contains("stopSpinReadyGlow(immediate = !shouldUseRichSpinEffects())"))
        assertTrue(fragmentSource.contains("if (shouldUseRichSpinEffects()) {\n            animateSpinImpactFlash()"))
    }

    @Test
    fun `renderer preserves strip presentation accessibility and cleanup contracts`() {
        val rendererSource = source(
            "src/main/java/com/vslot/app/ui/widget/ReelStripView.kt"
        )
        val fragmentSource = source(
            "src/main/java/com/vslot/app/ui/slot/SlotFragment.kt"
        )
        val stopAnimation = fragmentSource
            .substringAfter("private fun animateSpinStripColumnStop(")
            .substringBefore("private fun revealStoppedReelColumn")
        val destroyView = fragmentSource
            .substringAfter("override fun onDestroyView()")
            .substringBefore("private fun stopWinGlowOverlay")

        assertTrue(rendererSource.contains("importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO"))
        assertTrue(rendererSource.contains("val fitScale = minOf("))
        assertTrue(rendererSource.contains("canvas.scale(1f, renderState.symbolScaleY"))
        assertTrue(rendererSource.contains("drawable.alpha = (renderState.symbolAlpha * 255f)"))
        assertTrue(rendererSource.contains("override fun onDetachedFromWindow()"))
        assertFalse(rendererSource.contains("recycle("))
        assertTrue(stopAnimation.contains("ObjectAnimator.ofFloat("))
        assertTrue(stopAnimation.contains("View.TRANSLATION_Y"))
        assertTrue(stopAnimation.contains("ObjectAnimator.ofFloat(strip, View.SCALE_Y"))
        assertTrue(stopAnimation.contains("ObjectAnimator.ofFloat(strip, View.ALPHA"))
        assertTrue(destroyView.contains("reelSpinStrips.forEach(ReelStripView::clearSymbols)"))
        assertTrue(destroyView.contains("reelSpinDrawableCache?.clear()"))
        assertTrue(destroyView.contains("transientDrawablePreloads.clear()"))
    }

    private fun source(relativePath: String): String = Path.of(relativePath).readText()
}
