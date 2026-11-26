package local.wetype.couiglass

import android.graphics.Canvas
import android.graphics.drawable.InsetDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.RelativeLayout
import android.widget.TextView
import kotlin.math.roundToInt

/** The native syllable list is the data source and its rows still perform the selection. */
internal class CandidateSyllables(
    private val style: KeyboardStyle,
    val list: ViewGroup,
    private val frame: RelativeLayout,
    private val native: CandidateLists,
) {
    private val density = list.resources.displayMetrics.density
    private val gap = list.dp(2)
    private val slop = ViewConfiguration.get(list.context).scaledTouchSlop
    private val stockMargin = (list.layoutParams as RelativeLayout.LayoutParams).bottomMargin
    private val stockPaddingBottom = list.paddingBottom
    private val childPosition = native.recycler.getMethod("getChildAdapterPosition", View::class.java)
    private val layoutManager = native.recycler.getMethod("getLayoutManager")
    // WeType 59201's LinearLayoutManager.scrollToPositionWithOffset.
    private val scrollToOffset = Class.forName(
        "androidx.recyclerview.widget.LinearLayoutManager", false, native.recycler.classLoader,
    ).getMethod("R", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
    private val chinese = list.resources.configuration.locales[0].language == "zh"
    private val surface = ButtonBackground(style, list, null)
    private val position = SelectionSpring(::invalidateFace)
    private val previewAlpha = SelectionSpring(::invalidateFace)
    private var center = 0f
    private var rowHeight = 0
    private var showingSelection = false
    private var hasRows = false
    private var tracking = false
    private var touchX = 0f
    private var touchY = 0f
    private var stride = 0
    private var measuredContentHeight = 0
    private var pageStart = 0
    private var pageSize = 0
    private var footer = 0
    private var appliedFooter = 0
    private var layoutPosted = false
    private var closed = false
    private val slide = CandidateSlide(list, ::rowAt) { row, x ->
        tracking = x != null
        if (row != null || !layoutChanging()) updateSelection(row)
    }
    // Match the numeric tray: move the frame and keep the label in its own display list.
    private val nextLabel = TextView(list.context).apply {
        gravity = Gravity.CENTER
        setSingleLine(true)
        textSize = 14f
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val next = PageKey(list).apply {
        isFocusable = true
        addView(nextLabel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        background = ButtonBackground(style, this, null)
        setOnClickListener { page() }
    }
    private val nextTouch = CandidateKeyTouch(style, next)
    val face: InsetDrawable = object : InsetDrawable(surface, gap) {
        override fun draw(canvas: Canvas) {
            val bounds = surface.bounds
            val left = bounds.left
            val top = bounds.top
            val right = bounds.right
            val bottom = bounds.bottom
            if (left >= right || top >= bottom || !hasRows) return
            super.draw(canvas)

            val alpha = (previewAlpha.value.coerceIn(0f, 1f) * 255f).roundToInt()
            val inset = minOf(2f * density, (right - left) / 8f, (bottom - top) / 8f, rowHeight / 8f)
            val height = (rowHeight - 2f * inset).coerceIn(0f, bottom - top - 2f * inset)
            if (alpha == 0 || height <= 0f) return
            val y = (position.value - height / 2f).coerceIn(top + inset, bottom - inset - height)
            val width = right - left - 2f * inset
            val radius = (KeySurface.radius((right - left).toFloat(), (bottom - top).toFloat(), density) - inset)
                .coerceIn(0f, minOf(width, height) / 2f)
            val dark = style.dark()
            style.material().draw(canvas, left + inset, y, right - inset, y + height, density, radius,
                CouiGlass.indicator(dark, alpha), alpha, false, dark, null)
        }
    }

    fun sync() {
        if (layoutChanging()) return
        val count = native.count(list) ?: return
        if (hasRows != (count > 0)) {
            hasRows = count > 0
            invalidateFace()
        }
        syncPager(count)
        updateSelection()
    }

    private fun stockContentHeight(): Int {
        val margin = list.layoutParams as RelativeLayout.LayoutParams
        return frame.height - frame.paddingTop - frame.paddingBottom - margin.topMargin - stockMargin -
            list.paddingTop - stockPaddingBottom
    }

    private fun syncPager(count: Int) {
        val height = stockContentHeight()
        if (height <= 0) return
        val firstRow = list.getChildAt(0)
        val firstPosition = firstRow?.let { childPosition.invoke(list, it) as Int } ?: -1
        if (count > 0) {
            // Recycled or unlaid-out rows are not evidence that the footer slot has changed.
            if (firstRow == null || firstRow.id != ExpandedCandidates.PINYIN_ITEM ||
                firstRow.height <= 0 || firstPosition !in 0 until count) return
            if (height != measuredContentHeight) {
                val measured = native.stride(list, firstRow)
                if (measured < firstRow.height) return
                stride = measured
                measuredContentHeight = height
            }
        }
        footer = if (count.toLong() * stride > height + 1L) {
            minOf(stride, height / 2).takeIf { it > 2 * gap } ?: 0
        } else 0
        val capacity = if (footer > 0) ((height - footer + 1) / stride).coerceAtLeast(1) else 0
        val aligned = firstRow?.top == list.paddingTop
        // The host also returns to item zero when the composition supplies a new syllable list.
        val start = when {
            capacity == 0 || pageStart >= count || firstPosition == 0 && aligned -> 0
            else -> pageStart / capacity * capacity
        }
        if (start != pageStart || capacity != pageSize) slide.cancel()
        pageStart = start
        pageSize = capacity
        val needsAnchor = footer > 0 && firstPosition >= 0 && (firstPosition != start || !aligned)
        if (!layoutPosted && (footer != appliedFooter || pagePadding(count) != list.paddingBottom || needsAnchor)) {
            layoutPosted = true
            // Let the current traversal finish before changing the native list viewport.
            frame.post {
                layoutPosted = false
                if (!closed && !layoutChanging() && count == native.count(list)) applyPage(count)
            }
        }
        if (appliedFooter > 0) updateLabel(count)
    }

    fun dispatch(event: MotionEvent): Boolean {
        touchX = event.x
        touchY = event.y
        val down = event.actionMasked == MotionEvent.ACTION_DOWN
        if (down) native.stop(list) else style.motion().trackCandidate(list, event)
        val handled = slide.dispatch(event)
        if (down && handled) style.motion().trackCandidate(list, event)
        if (handled && event.actionMasked == MotionEvent.ACTION_UP && !layoutChanging()) updateSelection()
        // A touch starting in a divider or blank slot must not fall through to RecyclerView scrolling.
        return true
    }

    fun dispatchPage(view: Any?, event: MotionEvent) = view === next && nextTouch.dispatch(event)

    private fun layoutChanging() = list.isLayoutRequested || native.updating(list)

    private fun invalidateFace() {
        face.invalidateSelf()
    }

    private fun rowAt(x: Float, y: Float): View? {
        val top = list.paddingTop
        val bottom = list.height - list.paddingBottom
        val tolerance = if (tracking) slop else 0
        if (bottom <= top || y < top - tolerance || y >= bottom + tolerance ||
            layoutChanging()) return null
        val visibleY = y.coerceIn(top.toFloat(), bottom.toFloat())
        var nearest: View? = null
        var distance = Float.POSITIVE_INFINITY
        for (index in 0 until list.childCount) {
            val row = list.getChildAt(index)
            if (row.id != ExpandedCandidates.PINYIN_ITEM || !row.isEnabled || !row.isClickable ||
                row.visibility != View.VISIBLE || x < row.x - tolerance || x >= row.x + row.width + tolerance) continue
            val rowTop = row.y
            val rowBottom = rowTop + row.height
            if (row.height <= 0 || rowTop >= bottom || rowBottom <= top) continue
            if (visibleY >= rowTop && visibleY < rowBottom) return row
            val offset = maxOf(rowTop - visibleY, visibleY - rowBottom)
            // The host includes the divider in its decorated stride. Split that space between adjacent rows.
            if (offset < distance && offset <= (stride - row.height).coerceAtLeast(0) / 2f) {
                nearest = row
                distance = offset
            }
        }
        return nearest
    }

    private fun updateSelection(preview: View? = if (tracking) rowAt(touchX, touchY) else null) {
        val row = preview ?: selectedRow()
        val visible = row != null
        if (row != null) {
            val nextCenter = row.y + row.height / 2f
            if (rowHeight != row.height) {
                rowHeight = row.height
                invalidateFace()
            }
            if (!showingSelection) position.snapTo(nextCenter)
            else if (center != nextCenter) position.animateTo(nextCenter)
            center = nextCenter
        }
        if (visible != showingSelection) previewAlpha.animateTo(if (visible) 1f else 0f)
        showingSelection = visible
    }

    private fun selectedRow(): View? {
        for (index in 0 until list.childCount) {
            val row = list.getChildAt(index)
            if (row.isSelected && row.visibility == View.VISIBLE && row.y + row.height > list.paddingTop &&
                row.y < list.height - list.paddingBottom) return row
        }
        return null
    }

    private fun updatePager() {
        if (footer == appliedFooter) return
        val margin = list.layoutParams as RelativeLayout.LayoutParams
        margin.bottomMargin = stockMargin + footer
        list.layoutParams = margin
        appliedFooter = footer
        if (footer == 0) {
            nextTouch.close()
            next.visibility = View.GONE
            return
        }
        next.visibility = View.VISIBLE
        val params = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, footer - 2 * gap)
        params.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
        params.addRule(RelativeLayout.ALIGN_PARENT_START)
        params.setMargins(gap, 0, gap, stockMargin + gap)
        if (next.parent === frame) next.layoutParams = params else frame.addView(next, params)
    }

    private fun updateLabel(count: Int) {
        val first = count - pageStart <= pageSize
        val label = if (chinese) {
            if (first) "首页" else "下页"
        } else if (first) "First" else "Next"
        if (nextLabel.text != label) {
            nextLabel.text = label
            next.contentDescription = label
        }
        val color = if (style.dark()) 0xffeeeeee.toInt() else 0xff242424.toInt()
        if (nextLabel.currentTextColor != color) nextLabel.setTextColor(color)
    }

    private fun page() {
        if (closed || pageSize == 0 || appliedFooter == 0 || layoutPosted || layoutChanging()) return
        val count = native.count(list) ?: return
        if (count <= pageSize) return
        slide.cancel()
        pageStart = if (count - pageStart <= pageSize) 0 else pageStart + pageSize
        applyPage(count)
    }

    private fun pagePadding(count: Int): Int {
        if (footer == 0) return stockPaddingBottom
        val rows = minOf(pageSize, count - pageStart).coerceAtLeast(0)
        // Keep full pages aligned and leave any unused slots on the final page blank.
        return stockPaddingBottom + (stockContentHeight() - footer - rows * stride).coerceAtLeast(0)
    }

    private fun applyPage(count: Int) {
        updatePager()
        setBottomPadding(pagePadding(count))
        if (footer > 0) {
            native.stop(list)
            layoutManager.invoke(list)?.let { scrollToOffset.invoke(it, pageStart, 0) }
            updateLabel(count)
        } else if (count > 0) {
            native.firstPage(list)
        }
    }

    private fun setBottomPadding(bottom: Int) {
        if (bottom != list.paddingBottom) list.setPadding(list.paddingLeft, list.paddingTop, list.paddingRight, bottom)
    }

    fun close() {
        closed = true
        slide.finish()
        style.motion().cancelUnder(list)
        position.snapTo(0f)
        previewAlpha.snapTo(0f)
        footer = 0
        updatePager()
        if (next.parent === frame) frame.removeView(next)
        setBottomPadding(stockPaddingBottom)
    }

    private class PageKey(anchor: View) : FrameLayout(anchor.context) {
        override fun getAccessibilityClassName() = Button::class.java.name
    }
}
