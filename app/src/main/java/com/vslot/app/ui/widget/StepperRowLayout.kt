package com.vslot.app.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal data class StepperChildMeasure(
    val edgeWidthPx: Int,
    val centerWidthPx: Int,
    val childHeightPx: Int
)

internal data class StepperCenterMeasure(
    val topPx: Int,
    val labelHeightPx: Int,
    val valueHeightPx: Int
)

internal data class StepperHorizontalBounds(
    val startButtonLeftPx: Int,
    val centerLeftPx: Int,
    val centerRightPx: Int,
    val endButtonLeftPx: Int
)

internal fun calculateStepperChildMeasure(
    contentWidthPx: Int,
    contentHeightPx: Int,
    edgeButtonSizePx: Int,
    reservesRightEdge: Boolean
): StepperChildMeasure {
    val contentWidth = max(0, contentWidthPx)
    val edgeSlots = if (reservesRightEdge) 2 else 1
    val edgeWidth = min(max(0, edgeButtonSizePx), contentWidth / edgeSlots)
    val rightEdgeWidth = if (reservesRightEdge) edgeWidth else 0
    return StepperChildMeasure(
        edgeWidthPx = edgeWidth,
        centerWidthPx = max(0, contentWidth - edgeWidth - rightEdgeWidth),
        childHeightPx = max(0, contentHeightPx)
    )
}

internal fun calculateStepperCenterMeasure(
    contentHeightPx: Int,
    labelDesiredHeightPx: Int,
    valueDesiredHeightPx: Int
): StepperCenterMeasure {
    val contentHeight = max(0, contentHeightPx)
    val labelDesired = max(0, labelDesiredHeightPx)
    val valueDesired = max(0, valueDesiredHeightPx)
    val desiredHeight = labelDesired + valueDesired
    if (desiredHeight == 0) {
        return StepperCenterMeasure(contentHeight / 2, 0, 0)
    }
    if (desiredHeight <= contentHeight) {
        return StepperCenterMeasure(
            topPx = (contentHeight - desiredHeight) / 2,
            labelHeightPx = labelDesired,
            valueHeightPx = valueDesired
        )
    }
    val labelHeight = (contentHeight.toLong() * labelDesired / desiredHeight).toInt()
    return StepperCenterMeasure(
        topPx = 0,
        labelHeightPx = labelHeight,
        valueHeightPx = contentHeight - labelHeight
    )
}

internal fun calculateStepperHorizontalBounds(
    contentLeftPx: Int,
    contentRightPx: Int,
    edgeWidthPx: Int,
    isRtl: Boolean
): StepperHorizontalBounds {
    val contentWidth = max(0, contentRightPx - contentLeftPx)
    val edgeWidth = min(max(0, edgeWidthPx), contentWidth / 2)
    return StepperHorizontalBounds(
        startButtonLeftPx = if (isRtl) contentRightPx - edgeWidth else contentLeftPx,
        centerLeftPx = contentLeftPx + edgeWidth,
        centerRightPx = contentRightPx - edgeWidth,
        endButtonLeftPx = if (isRtl) contentLeftPx else contentRightPx - edgeWidth
    )
}

class StepperRowLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    private val edgeButtonSizePx = (48f * resources.displayMetrics.density).roundToInt()

    init {
        clipChildren = false
        clipToPadding = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val reservedEdgeCount = when {
            childCount >= 2 -> 2
            childCount == 1 -> 1
            else -> 0
        }
        val desiredWidth = max(
            suggestedMinimumWidth,
            edgeButtonSizePx * reservedEdgeCount + paddingLeft + paddingRight
        )
        val width = resolveSize(desiredWidth, widthMeasureSpec)
        val minimumHeight = edgeButtonSizePx + paddingTop + paddingBottom
        val height = resolveSize(max(suggestedMinimumHeight, minimumHeight), heightMeasureSpec)
        val contentWidth = max(0, width - paddingLeft - paddingRight)
        val contentHeight = max(0, height - paddingTop - paddingBottom)
        val reservesRightEdge = childCount >= 2
        val childMeasure = calculateStepperChildMeasure(
            contentWidthPx = contentWidth,
            contentHeightPx = contentHeight,
            edgeButtonSizePx = edgeButtonSizePx,
            reservesRightEdge = reservesRightEdge
        )
        val rightButton = if (childCount >= 3) getChildAt(1) else null
        val center = when {
            childCount >= 3 -> getChildAt(2)
            childCount >= 2 -> getChildAt(1)
            else -> null
        }
        val centerValue = getChildOrNull(3)

        getChildOrNull(0)?.measure(
            MeasureSpec.makeMeasureSpec(childMeasure.edgeWidthPx, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(childMeasure.childHeightPx, MeasureSpec.EXACTLY)
        )
        rightButton?.measure(
            MeasureSpec.makeMeasureSpec(childMeasure.edgeWidthPx, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(childMeasure.childHeightPx, MeasureSpec.EXACTLY)
        )
        if (centerValue == null) {
            center?.measure(
                MeasureSpec.makeMeasureSpec(childMeasure.centerWidthPx, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(childMeasure.childHeightPx, MeasureSpec.EXACTLY)
            )
        } else {
            val centerWidthSpec = MeasureSpec.makeMeasureSpec(
                childMeasure.centerWidthPx,
                MeasureSpec.EXACTLY
            )
            center?.measure(
                centerWidthSpec,
                MeasureSpec.makeMeasureSpec(contentHeight, MeasureSpec.AT_MOST)
            )
            val valueLayoutHeight = centerValue.layoutParams.height
            centerValue.measure(
                centerWidthSpec,
                if (valueLayoutHeight >= 0) {
                    MeasureSpec.makeMeasureSpec(valueLayoutHeight, MeasureSpec.EXACTLY)
                } else {
                    MeasureSpec.makeMeasureSpec(contentHeight, MeasureSpec.AT_MOST)
                }
            )
            val centerMeasure = calculateStepperCenterMeasure(
                contentHeightPx = contentHeight,
                labelDesiredHeightPx = center?.measuredHeight ?: 0,
                valueDesiredHeightPx = centerValue.measuredHeight
            )
            center?.measure(
                centerWidthSpec,
                MeasureSpec.makeMeasureSpec(centerMeasure.labelHeightPx, MeasureSpec.EXACTLY)
            )
            centerValue.measure(
                centerWidthSpec,
                MeasureSpec.makeMeasureSpec(centerMeasure.valueHeightPx, MeasureSpec.EXACTLY)
            )
        }

        for (index in 4 until childCount) {
            getChildAt(index).measure(
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY)
            )
        }

        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val contentLeft = paddingLeft
        val contentTop = paddingTop
        val contentRight = width - paddingRight
        val contentBottom = height - paddingBottom
        val startButton = getChildOrNull(0)
        val endButton = if (childCount >= 3) getChildAt(1) else null
        val center = when {
            childCount >= 3 -> getChildAt(2)
            childCount >= 2 -> getChildAt(1)
            else -> null
        }
        val centerValue = getChildOrNull(3)
        val edgeWidth = startButton?.measuredWidth ?: 0
        val bounds = calculateStepperHorizontalBounds(
            contentLeftPx = contentLeft,
            contentRightPx = contentRight,
            edgeWidthPx = edgeWidth,
            isRtl = layoutDirection == LAYOUT_DIRECTION_RTL
        )
        startButton?.layout(
            bounds.startButtonLeftPx,
            contentTop,
            bounds.startButtonLeftPx + edgeWidth,
            contentBottom
        )
        endButton?.layout(
            bounds.endButtonLeftPx,
            contentTop,
            bounds.endButtonLeftPx + edgeWidth,
            contentBottom
        )
        if (centerValue == null) {
            center?.layout(bounds.centerLeftPx, contentTop, bounds.centerRightPx, contentBottom)
        } else {
            val centerHeight = center?.measuredHeight ?: 0
            val valueHeight = centerValue.measuredHeight
            val centerTop = contentTop + (contentBottom - contentTop - centerHeight - valueHeight) / 2
            center?.layout(
                bounds.centerLeftPx,
                centerTop,
                bounds.centerRightPx,
                centerTop + centerHeight
            )
            centerValue.layout(
                bounds.centerLeftPx,
                centerTop + centerHeight,
                bounds.centerRightPx,
                centerTop + centerHeight + valueHeight
            )
        }

        for (index in 4 until childCount) {
            getChildAt(index).layout(0, 0, 0, 0)
        }
    }

    override fun generateDefaultLayoutParams(): LayoutParams {
        return LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    }

    override fun generateLayoutParams(attrs: AttributeSet): LayoutParams {
        return LayoutParams(context, attrs)
    }

    override fun generateLayoutParams(params: LayoutParams): LayoutParams {
        return LayoutParams(params)
    }

    override fun checkLayoutParams(params: LayoutParams): Boolean {
        return true
    }

    private fun getChildOrNull(index: Int): View? {
        return if (index in 0 until childCount) getChildAt(index) else null
    }
}
