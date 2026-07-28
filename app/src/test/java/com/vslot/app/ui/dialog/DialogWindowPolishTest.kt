package com.vslot.app.ui.dialog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DialogWindowPolishTest {
    @Test
    fun `roomy viewport preserves preferred dialog size`() {
        val size = calculateBoundedDialogSize(
            preferredWidthPx = 430,
            naturalHeightPx = 344,
            viewportWidthPx = 900,
            viewportHeightPx = 600
        )

        assertEquals(430, size.widthPx)
        assertEquals(344, size.heightPx)
        assertFalse(size.needsVerticalScroll)
    }

    @Test
    fun `compact viewport caps both dimensions and enables whole dialog scrolling`() {
        val size = calculateBoundedDialogSize(
            preferredWidthPx = 430,
            naturalHeightPx = 700,
            viewportWidthPx = 320,
            viewportHeightPx = 480
        )

        assertEquals(320, size.widthPx)
        assertEquals(480, size.heightPx)
        assertTrue(size.needsVerticalScroll)
    }

    @Test
    fun `width only overflow does not add unnecessary vertical scrolling`() {
        val size = calculateBoundedDialogSize(
            preferredWidthPx = 430,
            naturalHeightPx = 344,
            viewportWidthPx = 360,
            viewportHeightPx = 500
        )

        assertEquals(360, size.widthPx)
        assertEquals(344, size.heightPx)
        assertFalse(size.needsVerticalScroll)
    }
}
