package local.wetype.couiglass

import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import io.github.libxposed.api.XposedInterface
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/** Applies SegmentButton to S5's native rows, including any expanded subtypes. */
internal class SymbolCategories(
    private val style: KeyboardStyle, group: ViewGroup, original: Drawable?, private val adapterPosition: Method,
    private val rebindCells: (ViewGroup) -> Unit,
) : KeyboardControls.ControlBackground(original), View.OnLayoutChangeListener {
    private val owner = WeakReference(group)
    private val density = group.resources.displayMetrics.density
    private val inset = 2f * density
    private val slop = ViewConfiguration.get(group.context).scaledTouchSlop
    private val track = RectF()
    private val motionBounds = Rect()
    private val segment = CouiSegmentButton(group) {
        invalidateSelf()
        invalidateLiftedTrack(owner.get())
    }
    private val fade = LinearGradient(0f, 0f, 1f, 0f, Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP)
    private val fadeMatrix = Matrix()
    private val fadePaint = Paint().apply {
        shader = fade
        blendMode = BlendMode.DST_OUT
    }
    private var iconHeight = 0
    private var boundWidth = 0
    private var drawing = false
    private var parentLocked = false
    private var captured = false
    private var pendingSelection: Int? = null
    private var dragX = 0f
    private var dragY = 0f
    private var edgeTime = 0L
    private val edgeScroll = Runnable { scrollAtEdge() }
    private val refreshCells = Runnable {
        val group = owner.get() ?: return@Runnable
        val width = viewportWidth(group)
        if (group.background === this && width > 0 && width != boundWidth) {
            boundWidth = width
            rebindCells(group)
        }
    }

    init {
        group.addOnLayoutChangeListener(this)
        group.post(refreshCells)
    }

    override fun onLayoutChange(view: View, left: Int, top: Int, right: Int, bottom: Int,
                                oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int) {
        if (view.background !== this) {
            view.removeOnLayoutChangeListener(this)
        } else if (viewportWidth(view as ViewGroup) != boundWidth) {
            view.removeCallbacks(refreshCells)
            view.post(refreshCells)
        }
    }

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        if (!visible) {
            segment.cancel()
            owner.get()?.let(::finishGesture)
        }
        return super.setVisible(visible, restart)
    }

    override fun draw(canvas: Canvas) {
        if (drawing) return
        val original = stock ?: return
        original.setBounds(bounds)
        original.alpha = drawableAlpha
        original.state = state
        original.draw(canvas)
    }

    fun drawGroup(group: ViewGroup, chain: XposedInterface.Chain): Any? {
        updateSegment(group) ?: return chain.proceed()
        val motion = style.motion().sample(group)
        if (motion != null) {
            // Record the track, indicator and scrolled icons together on the lifted canvas.
            val drawStrip = object : XposedInterface.Chain by chain {
                override fun proceed(args: Array<out Any?>): Any? =
                    drawContents(group, args[0] as Canvas, null) { chain.proceed(args) }
            }
            if (style.layers().lift(group, group, track, segment.outerRadius, motion, drawStrip)) return null
        }
        return drawContents(group, chain.getArg(0) as Canvas, motion) { chain.proceed() }
    }

    private inline fun drawContents(group: ViewGroup, canvas: Canvas, motion: IconButtonMotion.Press?,
                                    drawItems: () -> Any?): Any? {
        val fadeLeft = group.canScrollHorizontally(-1)
        val fadeRight = group.canScrollHorizontally(1)
        val saved = canvas.save()
        drawing = true
        try {
            motion?.transform(canvas, track.centerX(), track.centerY())
            segment.drawTrack(canvas, style, drawableAlpha)
            segment.clipTrack(canvas, style)
            // Fade the scrolling icons and selection inside the track rim.
            if (fadeLeft || fadeRight) canvas.saveLayer(track, null)
            segment.drawIndicator(canvas, style, drawableAlpha)
            val result = drawItems()
            val fadeWidth = minOf(14f * density, track.width() / 4f)
            if (fadeLeft) fadeEdge(canvas, track.left, track.left + fadeWidth)
            if (fadeRight) fadeEdge(canvas, track.right, track.right - fadeWidth)
            return result
        } finally {
            drawing = false
            canvas.restoreToCount(saved)
        }
    }

    private fun fadeEdge(canvas: Canvas, edge: Float, inner: Float) {
        fadeMatrix.setScale(inner - edge, 1f)
        fadeMatrix.postTranslate(edge, 0f)
        fade.setLocalMatrix(fadeMatrix)
        canvas.drawRect(minOf(edge, inner), track.top, maxOf(edge, inner), track.bottom, fadePaint)
    }

    fun dispatch(group: ViewGroup, chain: XposedInterface.Chain, event: MotionEvent): Any? {
        val action = event.actionMasked
        val ending = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
        val motion = style.motion()
        motion.update(group, event)
        if (action == MotionEvent.ACTION_DOWN) finishGesture(group)
        val cells = updateSegment(group)
        if (cells == null || !group.isShown) {
            segment.cancel()
            releaseMotion(group)
            stopEdgeScroll(group)
            val wasCaptured = captured
            if (ending) finishGesture(group)
            return if (wasCaptured) true else chain.proceed()
        }
        val hitId: (Float, Float) -> Int? = { x, y ->
            if (x < track.left || x >= track.right || y < track.top - slop || y >= track.bottom + slop) null
            else cells.firstOrNull { x >= it.left && x < it.right && it.clickable }?.id
        }
        if (action == MotionEvent.ACTION_DOWN) {
            val result = chain.proceed()
            if (result == true) {
                segment.onTouch(event, hitId)
                if (hitId(event.x, event.y) != null) {
                    track.roundOut(motionBounds)
                    motion.down(group, group, motionBounds, event, event.actionIndex)
                }
                lockParent(group)
            } else {
                segment.cancel()
            }
            return result
        }
        if (captured && action == MotionEvent.ACTION_UP && event.y < track.top - slop) {
            segment.cancel()
            finishGesture(group)
            return true
        }
        val selected = cells.firstOrNull { it.view.isSelected }?.id
        val touch = segment.onTouch(event, hitId) { pressedId ->
            pressedId == selected || !group.canScrollHorizontally(-1) && !group.canScrollHorizontally(1)
        }
        if (touch.startedDrag) {
            KeyboardControls.cancelNativeTouch(chain, event)
            captured = true
            lockParent(group)
        }
        if (!captured) {
            try {
                return chain.proceed()
            } finally {
                if (ending) finishGesture(group)
            }
        }
        if (ending) {
            finishGesture(group)
            val cell = cells.firstOrNull { it.id == touch.commitId && it.clickable }
            if (cell?.view?.performClick() == true) {
                pendingSelection = cell.id
            }
        } else if (action == MotionEvent.ACTION_MOVE && segment.isTrackingDrag) {
            dragX = event.x
            dragY = event.y
            if (edgeSpeed(group) == 0f) stopEdgeScroll(group)
            else if (edgeTime == 0L) {
                segment.anchorDragToViewport()
                edgeTime = SystemClock.uptimeMillis()
                group.postOnAnimation(edgeScroll)
            }
        } else if (!segment.isTrackingDrag) {
            stopEdgeScroll(group)
        }
        return true
    }

    private fun lockParent(group: ViewGroup) {
        // S5 otherwise intercepts moves over its action keys.
        group.parent?.requestDisallowInterceptTouchEvent(true)
        parentLocked = true
    }

    private fun releaseMotion(group: ViewGroup) {
        style.motion().sample(group)?.takeIf { it.pointerId >= 0 }?.release(null)
    }

    private fun invalidateLiftedTrack(group: ViewGroup?) {
        val motion = style.motion()
        if (motion.sample(group)?.overflows() == true) motion.root?.invalidate()
    }

    private fun finishGesture(group: ViewGroup) {
        releaseMotion(group)
        stopEdgeScroll(group)
        if (parentLocked) group.parent?.requestDisallowInterceptTouchEvent(false)
        parentLocked = false
        captured = false
        pendingSelection = null
    }

    private fun stopEdgeScroll(group: ViewGroup) {
        if (edgeTime == 0L) return
        edgeTime = 0L
        group.removeCallbacks(edgeScroll)
    }

    private fun edgeSpeed(group: ViewGroup): Float {
        if (dragY < track.top - slop || dragY >= group.height + slop) return 0f
        val edge = minOf(24f * density, track.width() / 4f)
        val left = dragX - track.left
        val right = track.right - dragX
        val direction = when {
            left >= -slop && left < edge -> -1
            right >= -slop && right < edge -> 1
            else -> return 0f
        }
        if (!group.canScrollHorizontally(direction)) return 0f
        val distance = if (direction < 0) left else right
        val strength = 1f - (distance / edge).coerceIn(0f, 1f)
        return direction * density * (80f + 320f * strength)
    }

    private fun scrollAtEdge() {
        val group = owner.get() ?: return
        if (edgeTime == 0L) return
        if (group.background !== this || !style.draws(group) || !group.isShown) {
            segment.cancel()
            finishGesture(group)
            return
        }
        val speed = edgeSpeed(group)
        if (!segment.isTrackingDrag || speed == 0f) {
            stopEdgeScroll(group)
            return
        }
        val now = SystemClock.uptimeMillis()
        val elapsed = (now - edgeTime).coerceIn(1L, 32L)
        edgeTime = now
        val dx = (abs(speed) * elapsed / 1000f).roundToInt().coerceAtLeast(1) * sign(speed).toInt()
        val first = group.getChildAt(0)
        val left = first?.left
        group.scrollBy(dx, 0)
        val moved = group.getChildAt(0) !== first || first?.left != left
        if (!moved || updateSegment(group) == null) {
            stopEdgeScroll(group)
        } else {
            // Edge scrolling can continue after the whole-track spring has settled.
            invalidateLiftedTrack(group)
            group.postOnAnimation(edgeScroll)
        }
    }

    private fun viewportLeft(group: ViewGroup) = maxOf(group.paddingLeft.toFloat(), -group.x)

    private fun viewportRight(group: ViewGroup): Float {
        val keyboard = group.parent as? View ?: return 0f
        return minOf((group.width - group.paddingRight).toFloat(), keyboard.width - group.x)
    }

    private fun viewportWidth(group: ViewGroup) = (viewportRight(group) - viewportLeft(group)).roundToInt()

    private fun updateSegment(group: ViewGroup): List<Cell>? {
        if (!style.draws(group) || group.height <= 0) return null
        val left = viewportLeft(group)
        val right = viewportRight(group)
        if (right - left <= 2f * inset) return null

        val cells = cells(group)
        cells.firstOrNull()?.let {
            if (iconHeight != it.iconHeight) {
                iconHeight = it.iconHeight
                boundWidth = 0
                group.removeCallbacks(refreshCells)
                group.post(refreshCells)
            }
        }
        if (iconHeight <= 0) return null
        val halfHeight = minOf(group.height / 2f, iconHeight / 2f + inset)
        val centerY = group.height / 2f
        track.set(left + inset / 2f, centerY - halfHeight, right - inset / 2f, centerY + halfHeight)
        val slots = cells.mapNotNull { cell ->
            CouiSegmentButton.slotBounds(track, cell.left + inset, cell.right - inset, inset,
                centerX = cell.iconCenter, clipHorizontal = false)?.let {
                CouiSegmentButton.Slot(cell.id, it, cell.clickable)
            }
        }
        val nativeSelection = cells.firstOrNull { it.view.isSelected }?.id ?: -1
        // performClick changes the adapter selection before RecyclerView rebinds the old selected cell.
        if (pendingSelection == nativeSelection || !group.isLayoutRequested) pendingSelection = null
        segment.update(track, slots, pendingSelection ?: nativeSelection, group.isEnabled, scrollable = true)
        return cells
    }

    private fun cells(group: ViewGroup): List<Cell> = buildList {
        for (index in 0 until group.childCount) {
            val row = group.getChildAt(index) as? ViewGroup ?: continue
            val items = row.getChildAt(0) as? ViewGroup ?: continue
            val position = adapterPosition.invoke(group, row) as Int
            if (position < 0 || row.visibility != View.VISIBLE) continue
            val origin = row.x + items.x
            val rowLeft = maxOf(row.x, origin)
            val rowRight = minOf(row.x + row.width, origin + items.width)
            for (subtype in 0 until items.childCount) {
                val cell = items.getChildAt(subtype)
                val icon = cell.findViewById<View>(SYMBOL_ICON) ?: continue
                if (cell.visibility != View.VISIBLE || icon.visibility != View.VISIBLE ||
                    cell.width <= 0 || icon.width <= 0 || icon.height <= 0) continue
                val x = origin + cell.x
                val left = maxOf(x, rowLeft)
                val right = minOf(x + cell.width, rowRight)
                val center = x + icon.x + icon.width / 2f
                if (center < left || center >= right) continue
                add(Cell((position shl 16) or subtype, cell, left, right, center, icon.height))
            }
        }
    }.sortedBy { it.left }

    private data class Cell(
        val id: Int, val view: View, val left: Float, val right: Float, val iconCenter: Float, val iconHeight: Int,
    ) {
        val clickable get() = view.isEnabled && view.isClickable
    }

    companion object {
        private const val HOST = "com.tencent.wetype.plugin.hld."
        private const val SYMBOL_KEYBOARD = HOST + "keyboard.selfdraw.S5SymbolKeyboard"
        private const val SYMBOL_LIST_CLASS = HOST + "symbol.ImeSymbolTypeScrollView"
        private const val SYMBOL_LIST = 0x7f09049a
        private const val SYMBOL_ICON = 0x7f090554
        private const val VISIBLE_CELLS = 6.5f

        fun matches(group: ViewGroup): Boolean = group.id == SYMBOL_LIST && group.javaClass.name == SYMBOL_LIST_CLASS &&
            (group.parent as? View)?.javaClass?.name == SYMBOL_KEYBOARD

        fun install(module: WeTypeModule, loader: ClassLoader, recycler: Class<*>): (ViewGroup) -> Unit {
            fun load(name: String) = Class.forName(name, false, loader)
            val decoration = load(HOST + "symbol.d")
            val state = load("androidx.recyclerview.widget.RecyclerView\$z")
            val adapter = load("androidx.recyclerview.widget.RecyclerView\$h")
            val item = load(HOST + "symbol.m")
            val holder = load(HOST + "symbol.h")
            val listener = load(HOST + "symbol.h\$a")
            val data = load(HOST + "utils.M")
            val int = Int::class.javaPrimitiveType
            val bool = Boolean::class.javaPrimitiveType
            val drawDecoration = DrawHooks.method(decoration, "i", Void.TYPE, Canvas::class.java, recycler, state)
            val getAdapter = DrawHooks.method(recycler, "getAdapter", adapter)
            val notifyChanged = DrawHooks.method(adapter, "notifyDataSetChanged", Void.TYPE)
            val getListener = DrawHooks.method(item, "getListener", listener)
            val render = DrawHooks.method(item, "g", Void.TYPE, data, int, int, bool, int, bool)
            module.deoptimize(DrawHooks.method(holder, "a", Void.TYPE, data, bool, int, int, int, int))

            module.intercept(render) { chain ->
                val row = chain.thisObject as ViewGroup
                val group = (row.parent as? ViewGroup) ?: (getListener.invoke(row) as? ViewGroup)
                    ?: return@intercept chain.proceed()
                val control = group.background as? SymbolCategories ?: return@intercept chain.proceed()
                val width = control.boundWidth
                if (width <= 0 || control.iconHeight <= 0 || !control.style.draws(group)) return@intercept chain.proceed()
                val args = chain.args.toTypedArray()
                val padding = ((width / VISIBLE_CELLS).roundToInt() - control.iconHeight)
                    .coerceAtLeast((args[1] as Int) + (args[2] as Int))
                args[1] = padding / 2
                args[2] = padding - padding / 2
                val result = chain.proceed(args)
                val items = row.getChildAt(0) as? ViewGroup ?: return@intercept result
                for (index in 0 until items.childCount) {
                    val icon = items.getChildAt(index).findViewById<View>(SYMBOL_ICON) ?: continue
                    val params = icon.layoutParams as? FrameLayout.LayoutParams ?: continue
                    if (params.gravity != Gravity.CENTER) {
                        params.gravity = Gravity.CENTER
                        icon.layoutParams = params
                    }
                }
                result
            }

            module.intercept(drawDecoration) { chain ->
                val group = chain.getArg(1) as ViewGroup
                if ((group.background as? SymbolCategories)?.drawing == true) null else chain.proceed()
            }
            return { group -> getAdapter.invoke(group)?.let { notifyChanged.invoke(it) } }
        }
    }
}
