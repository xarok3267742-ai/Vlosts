package com.vslot.app.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout

class SlotLandscapeContentLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val boundedHeightSpec = if (
            MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED &&
            suggestedMinimumHeight > 0
        ) {
            MeasureSpec.makeMeasureSpec(suggestedMinimumHeight, MeasureSpec.EXACTLY)
        } else {
            heightMeasureSpec
        }
        super.onMeasure(widthMeasureSpec, boundedHeightSpec)
    }
}
