// PageIndicator drawing adapted from suqi8/coui at 0f870e322242f25de6302e4cee6760146d014da0.
// Copyright 2026, compose-coui-ui contributors
// SPDX-License-Identifier: Apache-2.0
package local.wetype.couiglass

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.itos.coui.effect.CouiContour
import com.itos.coui.effect.CouiContourStyle
import com.itos.coui.segmentbutton.CouiSegmentedControlDefaults
import com.itos.coui.shape.CouiCapsuleShape
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Adapts the logo panel's native RecyclerView pager and its three stock dots. */
internal object ToolbarPager {
    private const val HOST = "com.tencent.wetype.plugin.hld."
    private const val PAGER = 0x7f09029c
    private const val PANEL = 0x7f09048b
    private const val DOT_ROW = 0x7f0904f6
    private const val FIRST_PAGE_ROW = 0x7f090317 // line1
    private const val SECOND_PAGE_ROW = 0x7f090318 // line2
    private const val PLUS_PAGE_COLUMN = 0x7f0903d7 // plus_left
    private const val FIRST_TOOL = 0x7f09046b
    private const val LAST_TOOL = 0x7f09048a
    private const val TEXT_TO_IMAGE = 0x7f090494
    private const val TOOLBAR_EDITOR = 0x7f0901ad // custom_tool_setting_base_grid_view_container
    private const val FIRST_EDITOR_ROW = 0x7f0901aa // custom_tool_line1
    private const val SECOND_EDITOR_ROW = 0x7f0901ab // custom_tool_line2
    private const val EDITOR_CARD = HOST + "view.settingkeyboard.c"
    private const val SCROLL_STATE_IDLE = 0
    private const val SCROLL_STATE_DRAGGING = 1
    private const val DRAG_PAGE_THRESHOLD = .6f

    /** A card inside a swipeable toolbar page. */
    @JvmStatic
    fun isPagerCard(view: View): Boolean {
        val row = view.parent as? View ?: return false
        return when (row.id) {
            FIRST_PAGE_ROW, SECOND_PAGE_ROW, PLUS_PAGE_COLUMN ->
                (view.id in FIRST_TOOL..LAST_TOOL || view.id == TEXT_TO_IMAGE) && isUnder(row, PAGER)
            else -> false
        }
    }

    @JvmStatic
    fun isPageButton(view: View): Boolean {
        val row = view.parent as? View ?: return false
        return when (row.id) {
            FIRST_EDITOR_ROW, SECOND_EDITOR_ROW ->
                view.javaClass.name == EDITOR_CARD && isUnder(row, TOOLBAR_EDITOR)
            else -> isPagerCard(view)
        }
    }

    private fun isUnder(view: View, ancestorId: Int): Boolean {
        var current: View? = view
        while (current != null) {
            if (current.id == ancestorId) return true
            current = current.parent as? View
        }
        return false
    }

    // The host can reapply its skin to existing cards without rebinding a page.
    @JvmStatic
    fun needsCardRepair(style: KeyboardStyle, root: View): Boolean {
        val panel = root.findViewById<View>(PANEL) ?: return false
        if (!style.visible(panel)) return false
        val pager = panel.findViewById<ViewGroup>(PAGER)
        if (pager != null && style.visible(pager)) {
            for (index in 0 until pager.childCount) {
                val page = pager.getChildAt(index)
                if (rowNeedsRepair(style, page.findViewById(FIRST_PAGE_ROW)) ||
                    rowNeedsRepair(style, page.findViewById(SECOND_PAGE_ROW)) ||
                    rowNeedsRepair(style, page.findViewById(PLUS_PAGE_COLUMN))) return true
            }
        }
        val editor = panel.findViewById<ViewGroup>(TOOLBAR_EDITOR) ?: return false
        return style.visible(editor) && (rowNeedsRepair(style, editor.findViewById(FIRST_EDITOR_ROW)) ||
            rowNeedsRepair(style, editor.findViewById(SECOND_EDITOR_ROW)))
    }

    private fun rowNeedsRepair(style: KeyboardStyle, row: ViewGroup?): Boolean {
        if (row == null || !style.visible(row)) return false
        for (index in 0 until row.childCount) {
            val card = row.getChildAt(index)
            val background = card.background
            if (card.visibility == View.VISIBLE && card.alpha > 0f && isPageButton(card) &&
                background !is ButtonBackground && !KeyboardBackdrop.isBlurDrawable(background)) return true
        }
        return false
    }

    @JvmStatic
    @Throws(ReflectiveOperationException::class)
    fun install(module: WeTypeModule, loader: ClassLoader) {
        val keyboard = Class.forName(HOST + "keyboard.S10SettingsKeyboard", false, loader)
        val pager = Class.forName(HOST + "view.settingkeyboard.S10SettingRecyclerView", false, loader)
        val adapter = Class.forName(HOST + "view.settingkeyboard.e", false, loader)
        val holder = Class.forName(HOST + "view.settingkeyboard.o", false, loader)
        val native = NativePages(keyboard, pager)
        val attach = DrawHooks.method(keyboard, "onAttachedToWindow", Void.TYPE)
        val bind = DrawHooks.method(adapter, "h", Void.TYPE, holder, Int::class.javaPrimitiveType)
        val attachPage = DrawHooks.method(adapter, "j", Void.TYPE, holder)
        val itemView = holder.getField("itemView")
        module.intercept(native.scrollStateChanged) { chain ->
            val view = chain.thisObject
            if (view is View && view.id == PAGER && native.type.isInstance(view)) {
                val state = chain.getArg(0) as Int
                // RecyclerView enters DRAGGING during interception, before offsetting any child.
                if (state != SCROLL_STATE_IDLE) module.style()?.motion()?.cancelUnder(view)
                if (state == SCROLL_STATE_IDLE || state == SCROLL_STATE_DRAGGING) {
                    val column = view.parent as? View
                    val panel = column?.parent as? ViewGroup
                    if (column != null && panel != null) {
                        val indicator = panel.getChildAt(panel.indexOfChild(column) + 1) as? Indicator
                        indicator?.onPagerScrollStateChanged(state)
                    }
                }
            }
            chain.proceed()
        }
        module.intercept(attach) { chain ->
            val result = chain.proceed()
            mount(module, chain.thisObject as View, native)
            result
        }
        // A cached holder can reattach without a bind, before its detached press has been removed.
        for (method in listOf(bind, attachPage)) {
            module.intercept(method) { chain ->
                module.style()?.motion()?.cancelUnder(itemView.get(chain.getArg(0)) as View)
                val result = chain.proceed()
                module.style()?.refresh()
                result
            }
        }
    }

    private fun mount(module: WeTypeModule, keyboard: View, native: NativePages) {
        val panel = keyboard.findViewById<View>(PANEL) as? FrameLayout ?: return
        val pager = keyboard.findViewById<View>(PAGER) as? ViewGroup ?: return
        val dots = keyboard.findViewById<View>(DOT_ROW) as? ViewGroup ?: return
        val column = dots.parent as? View ?: return
        val index = panel.indexOfChild(column)
        if (index < 0 || pager.parent !== column || !native.type.isInstance(pager)) return
        if (panel.getChildAt(index + 1) is Indicator) return
        val view = Indicator(module, keyboard, pager, dots, column, native)
        val density = view.resources.displayMetrics.density
        // Use the stock center and reserve extra drawing room for the capsule's spring.
        panel.addView(view, index + 1, FrameLayout.LayoutParams(
            (96f * density).roundToInt(), (48f * density).roundToInt(),
            Gravity.TOP or Gravity.CENTER_HORIZONTAL,
        ))
    }

    private class NativePages(keyboard: Class<*>, val type: Class<*>) {
        private val recycler = type.superclass ?: throw NoSuchMethodException("S10 pager superclass")
        val count = DrawHooks.method(type, "getPageNum", Int::class.javaPrimitiveType)
        val position = DrawHooks.method(recycler, "getChildAdapterPosition", Int::class.javaPrimitiveType, View::class.java)
        val scrollState = DrawHooks.method(recycler, "getScrollState", Int::class.javaPrimitiveType)
        val scrollStateChanged = DrawHooks.method(
            recycler, "dispatchOnScrollStateChanged", Void.TYPE, Int::class.javaPrimitiveType,
        )
        val stop = DrawHooks.method(recycler, "stopScroll", Void.TYPE)
        val jump = DrawHooks.method(recycler, "scrollToPosition", Void.TYPE, Int::class.javaPrimitiveType)
        val smooth = DrawHooks.method(recycler, "smoothScrollToPosition", Void.TYPE, Int::class.javaPrimitiveType)
        val reportSelection = DrawHooks.method(keyboard, "T0", Void.TYPE, Int::class.javaPrimitiveType)
    }

    private class Indicator(
        private val module: WeTypeModule,
        private val keyboard: View,
        private val pager: ViewGroup,
        private val stockRow: ViewGroup,
        private val column: View,
        private val native: NativePages,
    ) : View(stockRow.context) {
        private val density = resources.displayMetrics.density
        private val trace = PageIndicatorTrace(density)
        private val glass = IndicatorCapsule(density)
        private val glassBounds = RectF()
        private val pressBounds = Rect()
        private val motion = IconButtonMotion()
        private val slop = min(ViewConfiguration.get(context).scaledTouchSlop.toFloat(), trace.step / 3f)
        private val stockDots = List(stockRow.childCount) { stockRow.getChildAt(it) }
        private var stockAlphas: FloatArray? = null
        private var observer: ViewTreeObserver? = null
        private var failed = false
        private var pageCount = 0
        private var position = 0f
        private var dark = false
        private var touchTop = 0f
        private var pointer = -1
        private var startX = 0f
        private var startY = 0f
        private var startPage = 0
        private var dragTarget = -1
        private var stepPage = -1
        private var dragging = false
        private var tappedPage = -1
        private val rtl get() = pager.layoutDirection == LAYOUT_DIRECTION_RTL
        private val pageWidth get() = pager.width - pager.paddingLeft - pager.paddingRight
        private val hitHalfWidth get() = maxOf(28f * density, glassBounds.width() / 2f)
        private val preDraw = ViewTreeObserver.OnPreDrawListener {
            if (!failed) {
                try {
                    updateFromPager()
                } catch (error: ReflectiveOperationException) {
                    useStockIndicator(error)
                }
            }
            true
        }

        init {
            visibility = INVISIBLE
            isFocusable = true
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            observer = viewTreeObserver.also { it.addOnPreDrawListener(preDraw) }
        }

        override fun onDetachedFromWindow() {
            observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(preDraw)
            observer = null
            showIndicator(false)
            super.onDetachedFromWindow()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (pageCount >= 2) layoutCapsule(pageCount)
        }

        private fun layoutCapsule(count: Int) {
            val cx = width / 2f
            val cy = height / 2f
            val halfWidth = ((count - 1) * trace.step + trace.diameter) / 2f + 18f * density
            val halfHeight = 14f * density
            glassBounds.set(cx - halfWidth, cy - halfHeight, cx + halfWidth, cy + halfHeight)
            glassBounds.round(pressBounds)
            glass.resize(glassBounds.width(), glassBounds.height())
        }

        private fun updateFromPager() {
            val style = module.style()
            if (style == null || !style.draws(pager) || !style.visible(pager) ||
                !stockRow.isShown || stockRow.height == 0 || width == 0 || height == 0) {
                showIndicator(false)
                return
            }
            val count = native.count.invoke(pager) as Int
            val current = if (count in 2..3) readPosition(count) else null
            if (current == null) {
                showIndicator(false)
                return
            }
            val center = column.y + stockRow.y + stockRow.height / 2f
            val indicatorTop = center - height / 2f
            translationY = indicatorTop - top
            // Leave the bottom toolbar buttons tappable in a shorter keyboard layout.
            val pageBottom = column.y + pager.y + pager.height - pager.paddingBottom
            touchTop = (pageBottom - indicatorTop).coerceAtLeast(0f)
            showIndicator(true)
            val newDark = style.dark()
            if (pageCount != count) {
                resetGesture()
                layoutCapsule(count)
            }
            if (pageCount != count || position != current || dark != newDark) {
                pageCount = count
                position = current
                dark = newDark
                invalidate()
            }
            advanceDrag(current)
        }

        private fun readPosition(count: Int): Float? {
            if (pageWidth <= 0) return null
            for (index in 0 until pager.childCount) {
                val child = pager.getChildAt(index)
                if (child.width <= 0) continue
                val page = native.position.invoke(pager, child) as Int
                if (page !in 0 until count) continue
                val leadingEdge = if (rtl) pager.width - pager.paddingRight - child.right
                    else child.left - pager.paddingLeft
                return (page - leadingEdge.toFloat() / pageWidth).coerceIn(0f, (count - 1).toFloat())
            }
            return null
        }

        private fun showIndicator(show: Boolean) {
            if (show == (visibility == VISIBLE)) return
            if (show) {
                stockAlphas = FloatArray(stockDots.size) { stockDots[it].alpha }
                stockDots.forEach { it.alpha = 0f }
            } else {
                stockAlphas?.forEachIndexed { index, alpha -> stockDots[index].alpha = alpha }
                stockAlphas = null
                motion.clear()
                resetGesture()
            }
            visibility = if (show) VISIBLE else INVISIBLE
        }

        private fun useStockIndicator(error: ReflectiveOperationException) {
            failed = true
            showIndicator(false)
            module.warn("Using original toolbar page indicator", error)
        }

        override fun onDraw(canvas: Canvas) {
            if (pageCount < 2) return
            val cx = width / 2f
            val cy = height / 2f
            val saved = canvas.save()
            try {
                motion.sample(this)?.transform(canvas, cx, cy)
                glass.draw(canvas, glassBounds, dark)
                canvas.translate(cx, cy)
                if (rtl) canvas.scale(-1f, 1f)
                trace.draw(canvas, pageCount, position, dark)
            } finally {
                canvas.restoreToCount(saved)
            }
        }

        private fun pageOffset(dx: Float, pixelsPerPage: Float): Float = (if (rtl) -dx else dx) / pixelsPerPage

        private fun updateDragTarget(x: Float) {
            val pixelsPerPage = 2f * hitHalfWidth / pageCount
            val sample = startPage + pageOffset(x - startX, pixelsPerPage)
            // Cross different thresholds in each direction so small reversals keep the current target.
            while (dragTarget < pageCount - 1 && sample >= dragTarget + DRAG_PAGE_THRESHOLD) dragTarget++
            while (dragTarget > 0 && sample <= dragTarget - DRAG_PAGE_THRESHOLD) dragTarget--
            postInvalidateOnAnimation()
        }

        fun onPagerScrollStateChanged(state: Int) {
            if (state == SCROLL_STATE_DRAGGING) resetGesture()
            else if (dragTarget >= 0) postInvalidateOnAnimation()
        }

        private fun advanceDrag(current: Float) {
            if (dragTarget < 0 || (native.scrollState.invoke(pager) as Int) != SCROLL_STATE_IDLE) return
            val page = current.roundToInt()
            val centered = abs(current - page) * pageWidth < 2f
            // Even a single large move must let the previous native snap reach its page first.
            if (stepPage >= 0 && (!centered || page != stepPage)) return
            stepPage = -1
            if (centered && page == dragTarget) {
                if (!dragging) dragTarget = -1
                return
            }
            stepPage = if (centered) page + (dragTarget - page).coerceIn(-1, 1) else page
            selectPage(stepPage)
        }

        private fun nearestPage(x: Float): Int =
            ((pageCount - 1) / 2f + pageOffset(x - width / 2f, trace.step)).roundToInt().coerceIn(0, pageCount - 1)

        override fun onTouchEvent(event: MotionEvent): Boolean = try {
            handleTouch(event)
        } catch (error: ReflectiveOperationException) {
            useStockIndicator(error)
            false
        }

        private fun handleTouch(event: MotionEvent): Boolean {
            val action = event.actionMasked
            if (action == MotionEvent.ACTION_DOWN) {
                if (visibility != VISIBLE || pageCount < 2 || event.y < touchTop ||
                    abs(event.x - width / 2f) > hitHalfWidth) return false
                resetGesture()
                startX = event.x
                startY = event.y
                pointer = event.getPointerId(0)
                motion.down(this, this, pressBounds, event, 0)
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            if (pointer < 0) return false
            val index = event.findPointerIndex(pointer)
            if (index < 0 || action == MotionEvent.ACTION_CANCEL ||
                action == MotionEvent.ACTION_POINTER_UP && event.actionIndex == index ||
                action == MotionEvent.ACTION_UP && (event.flags and MotionEvent.FLAG_CANCELED) != 0) {
                val wasDragging = dragging
                clearTouch()
                if (wasDragging) {
                    dragTarget = startPage
                    postInvalidateOnAnimation()
                }
                return true
            }
            if (action != MotionEvent.ACTION_MOVE && action != MotionEvent.ACTION_UP) return true
            val x = event.getX(index)
            val dx = abs(x - startX)
            val dy = abs(event.getY(index) - startY)
            if (!dragging && dy > slop && dy > dx) {
                clearTouch()
                return true
            }
            if (action == MotionEvent.ACTION_MOVE) motion.update(this, event)
            if (dragging || dx > slop) {
                if (!dragging) {
                    module.style()?.motion()?.cancelUnder(pager)
                    native.stop.invoke(pager)
                    startPage = (readPosition(pageCount) ?: position).roundToInt()
                    dragTarget = startPage
                    dragging = true
                }
                updateDragTarget(x)
            }
            if (action == MotionEvent.ACTION_UP) {
                val tap = !dragging && dx <= slop && dy <= slop
                clearTouch(event)
                if (tap) {
                    tappedPage = nearestPage(x)
                    performClick()
                }
            }
            return true
        }

        private fun clearTouch(event: MotionEvent? = null) {
            if (pointer >= 0) {
                motion.sample(this)?.release(event)
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            pointer = -1
            dragging = false
        }

        private fun resetGesture() {
            clearTouch()
            dragTarget = -1
            stepPage = -1
        }

        private fun selectPage(page: Int): Boolean {
            if (visibility != VISIBLE || page !in 0 until pageCount) return false
            return try {
                module.style()?.motion()?.cancelUnder(pager)
                native.reportSelection.invoke(keyboard, page)
                val method = if (ValueAnimator.areAnimatorsEnabled()) native.smooth else native.jump
                method.invoke(pager, page)
                true
            } catch (error: ReflectiveOperationException) {
                useStockIndicator(error)
                false
            }
        }

        override fun performClick(): Boolean {
            val handled = super.performClick()
            val page = tappedPage
            tappedPage = -1
            return selectPage(page) || handled
        }

        override fun getAccessibilityClassName(): CharSequence = "android.widget.SeekBar"

        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            if (pageCount < 2) return
            val current = position.roundToInt()
            info.isScrollable = true
            info.rangeInfo = AccessibilityNodeInfo.RangeInfo(
                AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, 1f, pageCount.toFloat(), current + 1f,
            )
            if (current > 0) info.addAction(ACTION_SCROLL_BACKWARD)
            if (current < pageCount - 1) info.addAction(ACTION_SCROLL_FORWARD)
            info.addAction(ACTION_SET_PROGRESS)
        }

        override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
            if (visibility != VISIBLE || pageCount < 2) return super.performAccessibilityAction(action, arguments)
            val current = position.roundToInt()
            val target = when (action) {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> current - 1
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> current + 1
                ACTION_SET_PROGRESS.id -> {
                    val value = arguments?.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, Float.NaN)
                    if (value == null || !value.isFinite()) return false
                    value.coerceIn(1f, pageCount.toFloat()).roundToInt() - 1
                }
                else -> return super.performAccessibilityAction(action, arguments)
            }
            if (target in 0 until pageCount) resetGesture()
            return selectPage(target)
        }
    }
}

/** COUI-Kit capsule tint and contour over the IME's existing backdrop. */
private class IndicatorCapsule(private val density: Float) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
    }
    private var size = Size.Zero
    private var outline = Path()
    private var contour: CouiContour? = null
    private var shaderFailed = false

    fun resize(width: Float, height: Float) {
        val next = Size(width, height)
        if (size == next) return
        size = next
        val shape = CouiCapsuleShape.createOutline(size, LayoutDirection.Ltr, pixels) as Outline.Generic
        outline = shape.path.asAndroidPath()
        rim.shader = LinearGradient(0f, 0f, 0f, height,
            intArrayOf(0xA0FFFFFF.toInt(), 0x27FFFFFF, 0x82FFFFFF.toInt()),
            floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP)
    }

    fun draw(canvas: Canvas, bounds: RectF, dark: Boolean) {
        val tint = CouiGlass.segment(dark, 144)
        val rimStrength = if (dark) .85f else 1f
        val shader = if (Build.VERSION.SDK_INT >= 33 && canvas.isHardwareAccelerated && !shaderFailed) {
            try {
                val effect = contour ?: CouiContour(style = CouiContourStyle.Button).also { contour = it }
                val material = if (dark) darkMaterial else lightMaterial
                effect.shader(size, density, size.height / 2f, 1f, Color(tint),
                    material.innerGlowAlpha, material.edgeAlpha * rimStrength, 1f, 1.6f * density)
            } catch (_: RuntimeException) {
                shaderFailed = true
                null
            } catch (_: LinkageError) {
                shaderFailed = true
                null
            }
        } else null
        fill.shader = shader
        fill.color = if (shader == null) tint else 0xFFFFFFFF.toInt()
        val saved = canvas.save()
        try {
            canvas.translate(bounds.left, bounds.top)
            canvas.drawPath(outline, fill)
            if (shader == null) {
                canvas.clipPath(outline)
                rim.alpha = (255 * rimStrength).roundToInt()
                canvas.drawPath(outline, rim)
            }
        } finally {
            canvas.restoreToCount(saved)
        }
    }

    companion object {
        private val pixels = Density(1f)
        private val lightMaterial = CouiSegmentedControlDefaults.material(false)
        private val darkMaterial = CouiSegmentedControlDefaults.material(true)
    }
}

// The host has at most three pages, so all dots use the same radius.
private class PageIndicatorTrace(density: Float) {
    val step = 12f * density
    val diameter = 6f * density
    private val radius = diameter / 2f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ease = PathInterpolator(.33f, 0f, .67f, 1f)
    private val colors = ArgbEvaluator()
    private val trace = Path()
    private val upperCutout = Path()
    private val lowerCutout = Path()
    private val mirrorY = Matrix().apply { setScale(1f, -1f) }

    fun draw(canvas: Canvas, count: Int, position: Float, dark: Boolean) {
        val dotColor = if (dark) 0x4DFFFFFF else 0x42000000
        val traceColor = if (dark) 0xE6FFFFFF.toInt() else 0xE6000000.toInt()
        val first = -(count - 1) * step / 2f
        val page = position.toInt()
        val offset = position - page
        val betweenPages = offset > 0f
        for (index in 0 until count) {
            if (betweenPages && (index == page || index == page + 1)) continue
            paint.color = if (index == page) traceColor else dotColor
            canvas.drawCircle(first + index * step, 0f, radius, paint)
        }
        if (!betweenPages) return

        val distance = min(offset, 1f - offset)
        val move = (ease.getInterpolation(((distance - .05f) / .45f).coerceIn(0f, 1f)) / 2f)
            .coerceIn(.005f, .495f)
        val left = first + page * step
        val right = left + step
        val depart = if (offset <= .5f) left + 2f * move * step else left
        val arrive = if (offset > .5f) right - 2f * move * step else right
        val neck = 1f - 2f * move
        val contactX = radius * neck
        val contactY = sqrt(radius * radius - contactX * contactX)
        val middle = (depart + arrive) / 2f
        val controlY = -(radius * radius - (middle - depart) * contactX) / contactY
        val angle = Math.toDegrees(asin(neck).toDouble()).toFloat()

        upperCutout.rewind()
        upperCutout.moveTo(left - radius, 0f)
        upperCutout.arcTo(left - radius, -radius, left + radius, radius, 180f, 90f, false)
        upperCutout.lineTo(depart, -radius)
        upperCutout.arcTo(depart - radius, -radius, depart + radius, radius, 270f, angle, false)
        upperCutout.quadTo(middle, controlY, arrive - contactX, -contactY)
        upperCutout.arcTo(arrive - radius, -radius, arrive + radius, radius, 270f - angle, angle, false)
        upperCutout.lineTo(right, -radius)
        upperCutout.arcTo(right - radius, -radius, right + radius, radius, 270f, 90f, false)
        upperCutout.lineTo(right + radius, -radius)
        upperCutout.lineTo(left - radius, -radius)
        upperCutout.close()
        upperCutout.transform(mirrorY, lowerCutout)

        trace.rewind()
        trace.addRect(left - radius, -radius, right + radius, radius, Path.Direction.CW)
        trace.op(upperCutout, Path.Op.DIFFERENCE)
        trace.op(lowerCutout, Path.Op.DIFFERENCE)
        paint.color = traceColor
        if (distance >= .05f) {
            canvas.drawPath(trace, paint)
            return
        }
        val faded = colors.evaluate(distance / .05f, dotColor, traceColor) as Int
        val saved = canvas.save()
        canvas.clipRect(left - radius - 1f, -radius - 1f, middle, radius + 1f)
        paint.color = if (offset <= .5f) traceColor else faded
        canvas.drawPath(trace, paint)
        canvas.restoreToCount(saved)
        val other = canvas.save()
        canvas.clipRect(middle, -radius - 1f, right + radius + 1f, radius + 1f)
        paint.color = if (offset > .5f) traceColor else faded
        canvas.drawPath(trace, paint)
        canvas.restoreToCount(other)
    }
}
