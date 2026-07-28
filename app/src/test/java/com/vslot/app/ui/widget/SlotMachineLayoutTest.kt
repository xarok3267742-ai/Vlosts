package com.vslot.app.ui.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class SlotMachineLayoutTest {
    @Test
    fun `machine preserves source artwork aspect ratio`() {
        assertEquals(680, calculateSlotMachineHeight(widthPx = 980, minimumHeightPx = 0))
        assertEquals(266, calculateSlotMachineHeight(widthPx = 383, minimumHeightPx = 240))
    }

    @Test
    fun `machine retains readable minimum height on narrow screens`() {
        assertEquals(240, calculateSlotMachineHeight(widthPx = 292, minimumHeightPx = 240))
        assertEquals(240, calculateSlotMachineHeight(widthPx = -1, minimumHeightPx = 240))
    }
}
