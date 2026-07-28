package com.vslot.app.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import java.util.LinkedHashMap
import kotlin.math.ceil
import kotlin.math.roundToInt

class ReelStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private var drawableCache = ReelStripDrawableCache(context)
    private val renderState = ReelStripRenderState(SYMBOL_COUNT)
    private val drawables = arrayOfNulls<Drawable>(SYMBOL_COUNT)
    private val clipBounds = Rect()
    private val symbolPaddingPx = SYMBOL_PADDING_DP * resources.displayMetrics.density
    private var firstDrawnSymbolIndex = 0
    private var lastDrawnSymbolIndex = SYMBOL_COUNT - 1

    internal constructor(
        context: Context,
        drawableCache: ReelStripDrawableCache
    ) : this(context) {
        this.drawableCache = drawableCache
    }

    init {
        contentDescription = null
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isFocusable = false
    }

    fun setSymbols(
        @DrawableRes resourceIds: IntArray,
        symbolAlpha: Float = 1f,
        symbolScaleY: Float = 1f
    ) {
        if (!renderState.update(resourceIds, symbolAlpha, symbolScaleY)) return

        for (index in resourceIds.indices) {
            drawables[index] = drawableCache[renderState.resourceIdAt(index)]
        }
        invalidate()
    }

    fun clearSymbols() {
        if (!renderState.clear()) return
        drawables.fill(null)
        invalidate()
    }

    fun setDrawnSymbolRange(firstIndex: Int, lastIndex: Int) {
        require(firstIndex in 0 until SYMBOL_COUNT)
        require(lastIndex in firstIndex until SYMBOL_COUNT)
        if (
            firstDrawnSymbolIndex == firstIndex &&
            lastDrawnSymbolIndex == lastIndex
        ) {
            return
        }
        firstDrawnSymbolIndex = firstIndex
        lastDrawnSymbolIndex = lastIndex
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!renderState.hasContent || width <= 0 || height <= 0) return

        val cellHeight = height.toFloat() / SYMBOL_COUNT
        if (!canvas.getClipBounds(clipBounds)) return
        val visibleIndexes = visibleSymbolIndexes(
            clipTop = clipBounds.top,
            clipBottom = clipBounds.bottom,
            cellHeight = cellHeight,
            symbolCount = SYMBOL_COUNT
        ) ?: return
        val firstIndex = maxOf(visibleIndexes.first, firstDrawnSymbolIndex)
        val lastIndex = minOf(visibleIndexes.last, lastDrawnSymbolIndex)
        if (firstIndex > lastIndex) return
        for (index in firstIndex..lastIndex) {
            val drawable = drawables[index] ?: continue
            drawSymbol(canvas, drawable, index, cellHeight)
        }
    }

    override fun onDetachedFromWindow() {
        clearSymbols()
        super.onDetachedFromWindow()
    }

    private fun drawSymbol(
        canvas: Canvas,
        drawable: Drawable,
        index: Int,
        cellHeight: Float
    ) {
        val contentWidth = width - symbolPaddingPx * 2f
        val contentHeight = cellHeight - symbolPaddingPx * 2f
        if (contentWidth <= 0f || contentHeight <= 0f) return

        val cellTop = index * cellHeight
        val cellCenterY = cellTop + cellHeight / 2f
        val saveCount = canvas.save()
        val previousAlpha = drawable.alpha
        try {
            canvas.scale(1f, renderState.symbolScaleY, width / 2f, cellCenterY)
            drawable.alpha = (renderState.symbolAlpha * 255f).roundToInt()

            val intrinsicWidth = drawable.intrinsicWidth
            val intrinsicHeight = drawable.intrinsicHeight
            if (intrinsicWidth > 0 && intrinsicHeight > 0) {
                val fitScale = minOf(
                    contentWidth / intrinsicWidth,
                    contentHeight / intrinsicHeight
                )
                val drawnWidth = intrinsicWidth * fitScale
                val drawnHeight = intrinsicHeight * fitScale
                canvas.translate(
                    symbolPaddingPx + (contentWidth - drawnWidth) / 2f,
                    cellTop + symbolPaddingPx + (contentHeight - drawnHeight) / 2f
                )
                canvas.scale(fitScale, fitScale)
                drawable.setBounds(0, 0, intrinsicWidth, intrinsicHeight)
            } else {
                drawable.setBounds(
                    symbolPaddingPx.roundToInt(),
                    (cellTop + symbolPaddingPx).roundToInt(),
                    (width - symbolPaddingPx).roundToInt(),
                    (cellTop + cellHeight - symbolPaddingPx).roundToInt()
                )
            }
            drawable.draw(canvas)
        } finally {
            drawable.alpha = previousAlpha
            canvas.restoreToCount(saveCount)
        }
    }

    companion object {
        const val SYMBOL_COUNT = 8
        private const val SYMBOL_PADDING_DP = 2
    }
}

internal fun visibleSymbolIndexes(
    clipTop: Int,
    clipBottom: Int,
    cellHeight: Float,
    symbolCount: Int
): IntRange? {
    require(cellHeight > 0f)
    require(symbolCount > 0)
    val contentBottom = cellHeight * symbolCount
    if (clipBottom <= 0 || clipTop >= contentBottom || clipBottom <= clipTop) return null

    val first = (clipTop.coerceAtLeast(0) / cellHeight)
        .toInt()
        .coerceIn(0, symbolCount - 1)
    val last = (ceil(clipBottom.toFloat().coerceAtMost(contentBottom) / cellHeight).toInt() - 1)
        .coerceIn(0, symbolCount - 1)
    return if (first <= last) first..last else null
}

internal class ReelStripRenderState(private val symbolCount: Int) {
    private val resourceIds = IntArray(symbolCount)

    var hasContent: Boolean = false
        private set
    var symbolAlpha: Float = 1f
        private set
    var symbolScaleY: Float = 1f
        private set

    fun update(
        newResourceIds: IntArray,
        newSymbolAlpha: Float,
        newSymbolScaleY: Float
    ): Boolean {
        require(newResourceIds.size == symbolCount)
        require(newResourceIds.all { it != 0 })
        require(newSymbolAlpha in 0f..1f)
        require(newSymbolScaleY > 0f)

        var changed = !hasContent ||
            symbolAlpha != newSymbolAlpha ||
            symbolScaleY != newSymbolScaleY
        for (index in 0 until symbolCount) {
            if (resourceIds[index] != newResourceIds[index]) {
                changed = true
                break
            }
        }
        if (!changed) return false

        newResourceIds.copyInto(resourceIds)
        symbolAlpha = newSymbolAlpha
        symbolScaleY = newSymbolScaleY
        hasContent = true
        return true
    }

    @DrawableRes
    fun resourceIdAt(index: Int): Int = resourceIds[index]

    fun clear(): Boolean {
        if (!hasContent) return false
        resourceIds.fill(0)
        symbolAlpha = 1f
        symbolScaleY = 1f
        hasContent = false
        return true
    }
}

internal class ReelStripDrawableCache(
    context: Context,
    maxEntries: Int = MAX_ENTRIES
) {
    private val resources = BoundedResourceCache(maxEntries) { resourceId ->
        AppCompatResources.getDrawable(context, resourceId)
    }

    operator fun get(@DrawableRes resourceId: Int): Drawable? = resources[resourceId]

    fun preload(@DrawableRes resourceIds: IntArray) {
        resourceIds.forEach { resourceId -> resources[resourceId] }
    }

    fun clear() {
        // Resource drawables can share bitmap storage. Drop references without recycling them.
        resources.clear()
    }

    private companion object {
        const val MAX_ENTRIES = ReelStripView.SYMBOL_COUNT * 2
    }
}

internal class BoundedResourceCache<T : Any>(
    maxEntries: Int,
    private val loader: (Int) -> T?
) {
    private val entries = object : LinkedHashMap<Int, T>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, T>?): Boolean {
            return size > maxEntries
        }
    }

    init {
        require(maxEntries > 0)
    }

    val size: Int
        get() = entries.size

    operator fun get(resourceId: Int): T? {
        require(resourceId != 0)
        entries[resourceId]?.let { return it }
        return loader(resourceId)?.also { loaded -> entries[resourceId] = loaded }
    }

    fun clear() {
        entries.clear()
    }
}
