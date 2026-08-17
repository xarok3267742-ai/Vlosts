package com.vslot.app.ui.slot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path
import kotlin.io.path.readText

class SlotReelAccessibilityContractTest {
    private val slotFragment = Path.of(
        "src/main/java/com/vslot/app/ui/slot/SlotFragment.kt"
    ).readText()
    private val strings = Path.of("src/main/res/values/strings.xml").readText()

    @Test
    fun `reel grid is the only talkback node`() {
        val setupGrid = slotFragment
            .substringAfter("private fun setupGrid()")
            .substringBefore("private fun setupReelSpinStripLayer()")
        val renderReels = slotFragment
            .substringAfter("private fun renderReels(")
            .substringBefore("private fun renderSymbolWinHalos(")
        val renderReelColumn = slotFragment
            .substringAfter("private fun renderReelColumn(")
            .substringBefore("private fun spinningColumnSymbols(")
        val revealStoppedColumn = slotFragment
            .substringAfter("private fun revealStoppedReelColumn(")
            .substringBefore("private fun fadeStoppedSpinColumn(")

        assertTrue(slotFragment.contains("binding.reelsGrid.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES"))
        assertTrue(setupGrid.contains("importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO"))
        assertTrue(setupGrid.contains("contentDescription = null"))
        assertFalse(renderReels.contains("imageView.contentDescription"))
        assertFalse(renderReelColumn.contains("imageView.contentDescription"))
        assertFalse(revealStoppedColumn.contains("imageView.contentDescription"))
    }

    @Test
    fun `idle grid summarizes three rows with symbol labels`() {
        val descriptionUpdate = slotFragment
            .substringAfter("private fun updateReelsContentDescription(")
            .substringBefore("private fun renderSymbolWinHalos(")

        assertTrue(strings.contains("<string name=\"slot_reels_accessibility\">Игровые барабаны</string>"))
        assertTrue(strings.contains("Верхний ряд: %1\$s. Средний ряд: %2\$s. Нижний ряд: %3\$s."))
        assertTrue(descriptionUpdate.contains("0 until REEL_VISIBLE_ROWS"))
        assertTrue(descriptionUpdate.contains("0 until REEL_COUNT"))
        assertTrue(descriptionUpdate.contains("SlotSymbolResources.label(theme, reels[column][row])"))
        assertTrue(descriptionUpdate.contains("R.string.slot_reels_rows_accessibility"))
    }

    @Test
    fun `spin and result accessibility updates cannot expose stale reels`() {
        val stateCollector = slotFragment
            .substringAfter("private suspend fun collectState()")
            .substringBefore("private fun updateReelAccessibility(")
        val spinAccessibility = slotFragment
            .substringAfter("private fun updateReelAccessibility(")
            .substringBefore("private fun updateSpinResultAccessibility(")
        val resultAccessibility = slotFragment
            .substringAfter("private fun updateSpinResultAccessibility(")
            .substringBefore("private fun paylineMarkersOverlayDrawable(")

        assertTrue(strings.contains("<string name=\"slot_reels_spinning\">Барабаны вращаются</string>"))
        assertTrue(spinAccessibility.contains("R.string.slot_reels_spinning"))
        assertTrue(
            stateCollector.indexOf("renderReels(state.config.theme, reels, highlightedCells, bonusScatterCells)") <
                stateCollector.indexOf("updateSpinResultAccessibility(state.lastResult, announce = spinEnded)")
        )
        assertTrue(resultAccessibility.contains("ViewCompat.setStateDescription(binding.reelsGrid, resultDescription)"))
        assertTrue(resultAccessibility.contains("if (announce && resultDescription != null)"))
        assertTrue(resultAccessibility.contains("AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED"))
    }

    @Test
    fun `decorative payline layers do not retain stale line count descriptions`() {
        assertTrue(slotFragment.contains("val paylinesDescription = activePaylinesDescription(selectedLines)"))
        assertTrue(slotFragment.contains("binding.linesDigits.contentDescription = paylinesDescription"))
        assertTrue(slotFragment.contains("binding.activeLinesRail.contentDescription = paylinesDescription"))
        assertTrue(slotFragment.contains("binding.paylineMarkersOverlay.contentDescription = paylinesDescription"))
    }
}
