package com.vslot.app.ui.widget

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.annotation.DrawableRes
import com.vslot.app.R

fun ImageView.setImageResourceIfChanged(@DrawableRes resId: Int) {
    if (getTag(R.id.tag_bound_image_resource) == resId) return
    setTag(R.id.tag_bound_image_resource, resId)
    setImageResource(resId)
}

fun ImageView.clearBoundImageResource() {
    setTag(R.id.tag_bound_image_resource, null)
    setImageDrawable(null)
}

fun View.clearImageResourcesRecursively() {
    if (this is ImageView) {
        clearBoundImageResource()
    }
    if (this is ViewGroup) {
        for (index in 0 until childCount) {
            getChildAt(index).clearImageResourcesRecursively()
        }
    }
}
