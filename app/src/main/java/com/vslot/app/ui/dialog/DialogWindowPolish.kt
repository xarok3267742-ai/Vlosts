package com.vslot.app.ui.dialog

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.DialogFragment
import kotlin.math.roundToInt

internal data class BoundedDialogSize(
    val widthPx: Int,
    val heightPx: Int,
    val needsVerticalScroll: Boolean
)

internal fun calculateBoundedDialogSize(
    preferredWidthPx: Int,
    naturalHeightPx: Int,
    viewportWidthPx: Int,
    viewportHeightPx: Int
): BoundedDialogSize {
    val safeViewportWidth = viewportWidthPx.coerceAtLeast(1)
    val safeViewportHeight = viewportHeightPx.coerceAtLeast(1)
    val safePreferredWidth = preferredWidthPx.coerceAtLeast(1)
    val safeNaturalHeight = naturalHeightPx.coerceAtLeast(1)
    return BoundedDialogSize(
        widthPx = safePreferredWidth.coerceAtMost(safeViewportWidth),
        heightPx = safeNaturalHeight.coerceAtMost(safeViewportHeight),
        needsVerticalScroll = safeNaturalHeight > safeViewportHeight
    )
}

internal fun Dialog.keepGameFullscreen() {
    val dialogWindow = window ?: return
    WindowInsetsControllerCompat(dialogWindow, dialogWindow.decorView).apply {
        hide(WindowInsetsCompat.Type.systemBars())
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
    applyBoundedResponsiveLayout()
}

internal fun Dialog.applyGameDialogDim(dimAmount: Float) {
    val dialogWindow = window ?: return
    dialogWindow.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    dialogWindow.attributes = dialogWindow.attributes.apply {
        this.dimAmount = dimAmount.coerceIn(0f, 1f)
    }
}

internal fun DialogFragment.bindScalableDialogCopy(vararg copyPairs: Pair<View, TextView>) {
    val useScalableCopy = resources.configuration.fontScale > DEFAULT_FONT_SCALE
    copyPairs.forEach { (bitmapCopy, scalableCopy) ->
        bitmapCopy.visibility = if (useScalableCopy) View.GONE else View.VISIBLE
        scalableCopy.visibility = if (useScalableCopy) View.VISIBLE else View.GONE
        bitmapCopy.importantForAccessibility = accessibilityImportance(!useScalableCopy)
        scalableCopy.importantForAccessibility = accessibilityImportance(useScalableCopy)
    }
}

private fun accessibilityImportance(enabled: Boolean): Int = if (enabled) {
    View.IMPORTANT_FOR_ACCESSIBILITY_YES
} else {
    View.IMPORTANT_FOR_ACCESSIBILITY_NO
}

private fun Dialog.applyBoundedResponsiveLayout() {
    val dialogWindow = window ?: return
    val contentHost = dialogWindow.decorView.findViewById<ViewGroup>(android.R.id.content)
        ?: return
    val content = contentHost.getChildAt(0) ?: return
    if (content is BoundedDialogScrollView) return

    val viewport = currentDialogViewport()
    val preferredWidthPx = content.preferredDialogWidthPx(viewport.widthPx)
    val boundedWidthPx = preferredWidthPx.coerceAtMost(viewport.widthPx)

    content.measure(
        View.MeasureSpec.makeMeasureSpec(boundedWidthPx, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
    )
    val size = calculateBoundedDialogSize(
        preferredWidthPx = preferredWidthPx,
        naturalHeightPx = content.measuredHeight,
        viewportWidthPx = viewport.widthPx,
        viewportHeightPx = viewport.heightPx
    )

    if (size.needsVerticalScroll) {
        contentHost.installBoundedScrollView(content)
    } else if (size.widthPx < preferredWidthPx) {
        content.layoutParams = content.layoutParams.apply {
            width = ViewGroup.LayoutParams.MATCH_PARENT
        }
    }
    dialogWindow.attributes = dialogWindow.attributes.apply {
        gravity = Gravity.CENTER
        x = viewport.centerOffsetXPx
        y = viewport.centerOffsetYPx
    }
    dialogWindow.setLayout(size.widthPx, size.heightPx)
}

private fun Dialog.currentDialogViewport(): DialogViewport {
    val density = context.resources.displayMetrics.density
    val activity = context.findActivity()
    val windowBounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        activity?.windowManager?.currentWindowMetrics?.bounds
    } else {
        null
    }
    val configuration = context.resources.configuration
    val widthPx = windowBounds?.width()?.takeIf { it > 0 }
        ?: configuration.screenWidthDp
            .takeIf { it > 0 }
            ?.let { (it * density).roundToInt() }
        ?: context.resources.displayMetrics.widthPixels
    val heightPx = windowBounds?.height()?.takeIf { it > 0 }
        ?: configuration.screenHeightDp
            .takeIf { it > 0 }
            ?.let { (it * density).roundToInt() }
        ?: context.resources.displayMetrics.heightPixels
    val insetSource = activity?.window?.decorView ?: window?.decorView
    val safeInsets = insetSource?.let(ViewCompat::getRootWindowInsets)?.getInsets(
        WindowInsetsCompat.Type.systemBars() or
            WindowInsetsCompat.Type.displayCutout() or
            WindowInsetsCompat.Type.mandatorySystemGestures()
    ) ?: Insets.NONE
    return DialogViewport(
        widthPx = (widthPx - safeInsets.left - safeInsets.right).coerceAtLeast(1),
        heightPx = (heightPx - safeInsets.top - safeInsets.bottom).coerceAtLeast(1),
        centerOffsetXPx = (safeInsets.left - safeInsets.right) / 2,
        centerOffsetYPx = (safeInsets.top - safeInsets.bottom) / 2
    )
}

private fun View.preferredDialogWidthPx(viewportWidthPx: Int): Int {
    val taggedWidthDp = when (tag) {
        COMPACT_DIALOG_WIDTH_TAG -> COMPACT_DIALOG_PREFERRED_WIDTH_DP
        WIDE_DIALOG_WIDTH_TAG -> WIDE_DIALOG_PREFERRED_WIDTH_DP
        else -> null
    }
    if (taggedWidthDp != null) {
        return (taggedWidthDp * resources.displayMetrics.density).roundToInt()
    }
    return layoutParams?.width?.takeIf { it > 0 }
        ?: measuredWidth.takeIf { it > 0 }
        ?: viewportWidthPx
}

private fun ViewGroup.installBoundedScrollView(content: View) {
    removeView(content)
    val scrollView = BoundedDialogScrollView(context).apply {
        isFillViewport = false
        isVerticalScrollBarEnabled = true
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
    }
    scrollView.addView(
        content,
        FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    )
    addView(
        scrollView,
        ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    )
}

private fun Context.findActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        val base = current.baseContext
        if (base === current) return null
        current = base
    }
    return current as? Activity
}

private data class DialogViewport(
    val widthPx: Int,
    val heightPx: Int,
    val centerOffsetXPx: Int,
    val centerOffsetYPx: Int
)

private class BoundedDialogScrollView(context: Context) : ScrollView(context)

private const val COMPACT_DIALOG_WIDTH_TAG = "dialog_preferred_width_430dp"
private const val COMPACT_DIALOG_PREFERRED_WIDTH_DP = 430
private const val WIDE_DIALOG_WIDTH_TAG = "dialog_preferred_width_840dp"
private const val WIDE_DIALOG_PREFERRED_WIDTH_DP = 840
private const val DEFAULT_FONT_SCALE = 1.0f
