package com.vslot.app.ui.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class StepperRowLayoutTest {
    @Test
    fun `320dp row keeps readable centers and 48dp edge controls`() {
        val stepperWidth = (
            HOST_WIDTH_DP -
                SLOT_HORIZONTAL_PADDING_DP
            ) / 2

        val measure = calculateStepperChildMeasure(
            contentWidthPx = stepperWidth,
            contentHeightPx = STEPPER_CONTENT_HEIGHT_DP,
            edgeButtonSizePx = EDGE_CONTROL_SIZE_DP,
            reservesRightEdge = true
        )

        assertEquals(146, stepperWidth)
        assertEquals(48, measure.edgeWidthPx)
        assertEquals(54, measure.childHeightPx)
        assertEquals(50, measure.centerWidthPx)
    }

    @Test
    fun `edge controls shrink without overlap when parent is narrower than tap targets`() {
        val measure = calculateStepperChildMeasure(
            contentWidthPx = 80,
            contentHeightPx = 32,
            edgeButtonSizePx = EDGE_CONTROL_SIZE_DP,
            reservesRightEdge = true
        )

        assertEquals(40, measure.edgeWidthPx)
        assertEquals(32, measure.childHeightPx)
        assertEquals(0, measure.centerWidthPx)
    }

    @Test
    fun `semantic start and end controls mirror in rtl without moving the center`() {
        val ltr = calculateStepperHorizontalBounds(12, 280, 48, isRtl = false)
        val rtl = calculateStepperHorizontalBounds(12, 280, 48, isRtl = true)

        assertEquals(12, ltr.startButtonLeftPx)
        assertEquals(232, ltr.endButtonLeftPx)
        assertEquals(232, rtl.startButtonLeftPx)
        assertEquals(12, rtl.endButtonLeftPx)
        assertEquals(ltr.centerLeftPx, rtl.centerLeftPx)
        assertEquals(ltr.centerRightPx, rtl.centerRightPx)
    }

    @Test
    fun `label and value remain vertically centered at their requested heights`() {
        val measure = calculateStepperCenterMeasure(
            contentHeightPx = 54,
            labelDesiredHeightPx = 20,
            valueDesiredHeightPx = 32
        )

        assertEquals(1, measure.topPx)
        assertEquals(20, measure.labelHeightPx)
        assertEquals(32, measure.valueHeightPx)
    }

    @Test
    fun `label and value shrink proportionally when height is constrained`() {
        val measure = calculateStepperCenterMeasure(
            contentHeightPx = 39,
            labelDesiredHeightPx = 20,
            valueDesiredHeightPx = 32
        )

        assertEquals(0, measure.topPx)
        assertEquals(15, measure.labelHeightPx)
        assertEquals(24, measure.valueHeightPx)
    }

    private companion object {
        const val HOST_WIDTH_DP = 320
        const val SLOT_HORIZONTAL_PADDING_DP = 28
        const val STEPPER_CONTENT_HEIGHT_DP = 54
        const val EDGE_CONTROL_SIZE_DP = 48
    }
}
