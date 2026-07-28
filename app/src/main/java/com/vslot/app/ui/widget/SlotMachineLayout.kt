package com.vslot.app.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import kotlin.math.max
import kotlin.math.roundToInt

private const val SLOT_MACHINE_ASPECT_WIDTH = 980
private const val SLOT_MACHINE_ASPECT_HEIGHT = 680

internal fun calculateSlotMachineHeight(widthPx: Int, minimumHeightPx: Int): Int {
    val aspectHeight = (
        max(0, widthPx).toLong() * SLOT_MACHINE_ASPECT_HEIGHT / SLOT_MACHINE_ASPECT_WIDTH.toDouble()
        ).roundToInt()
    return max(max(0, minimumHeightPx), aspectHeight)
}

class SlotMachineLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = when (MeasureSpec.getMode(widthMeasureSpec)) {
            MeasureSpec.UNSPECIFIED -> suggestedMinimumWidth
            else -> MeasureSpec.getSize(widthMeasureSpec)
        }
        val desiredHeight = calculateSlotMachineHeight(
            widthPx = availableWidth - paddingLeft - paddingRight,
            minimumHeightPx = suggestedMinimumHeight - paddingTop - paddingBottom
        ) + paddingTop + paddingBottom
        val measuredHeight = resolveSize(desiredHeight, heightMeasureSpec)

        super.onMeasure(
            widthMeasureSpec,
            MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY)
        )
    }
}
