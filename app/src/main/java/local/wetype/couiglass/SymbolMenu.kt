package local.wetype.couiglass

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import java.lang.ref.WeakReference

/** Applies the COUI segmented surface to the host's horizontal symbol chooser. */
internal object SymbolMenu {
    private const val CLEAR_CONFIRM = 0xD9DC393F.toInt()
    private const val HOST = "com.tencent.wetype.plugin.hld.floatview."
    private const val KEYS = "com.tencent.wetype.plugin.hld.keyboard.selfdraw."
    private val outlines = java.util.WeakHashMap<View, MenuOutline>()
    private var layoutRadius: ((View, RectF, Float) -> Float)? = null

    @JvmStatic
    @Throws(ReflectiveOperationException::class)
    fun install(module: WeTypeModule, loader: ClassLoader) {
        val base = Class.forName(HOST + "u", false, loader)
        val shortMenu = Class.forName(HOST + "D", false, loader)
        val scrollMenu = Class.forName(HOST + "p", false, loader)
        val canScrollMenu = DrawHooks.method(scrollMenu, "S", Boolean::class.javaPrimitiveType)
        val deleteMenu = Class.forName(HOST + "g", false, loader)
        val recallMenu = Class.forName(HOST + "z", false, loader)
        val rowClass = Class.forName(HOST + "m", false, loader)
        val rowView = DrawHooks.method(rowClass, "a", View::class.java)
        val itemCount = DrawHooks.method(base, "getItemCount", Int::class.javaPrimitiveType)
        val selectedPosition = DrawHooks.method(base, "getLastSelectedPos", Int::class.javaPrimitiveType)
        val holderAt = DrawHooks.method(base, "n", rowClass, Int::class.javaPrimitiveType)
        val container = DrawHooks.method(base, "getContainer", ViewGroup::class.java)
        val rowToPopup = Matrix()
        fun isStyled(view: View) = shortMenu.isInstance(view) || scrollMenu.isInstance(view) || deleteMenu.isInstance(view)
        val mount = base.declaredMethods.single { it.name == "v" && it.parameterCount == 6 }
        val segments = java.util.WeakHashMap<View, CouiSegmentButton>()
        val triggerPointer = DrawHooks.method(base, "getTriggerPointerIndex", Int::class.javaPrimitiveType)
        val nativeTouch = DrawHooks.method(base, "K", Boolean::class.javaPrimitiveType,
            View::class.java, MotionEvent::class.java, Boolean::class.javaPrimitiveType)
        val sourceLocation = IntArray(2)
        val popupLocation = IntArray(2)
        val key = Class.forName(KEYS + "j", false, loader)
        val keyboard = Class.forName(KEYS + "o", false, loader)
        val keyBounds = DrawHooks.method(key, "t", Rect::class.java)
        val deleteAction = DrawHooks.method(
            Class.forName(HOST + "w", false, loader), "I", Void.TYPE,
            Class.forName(HOST + "w\$a", false, loader), Context::class.java, key, keyboard, keyboard,
            Class.forName("wc.l", false, loader),
        )
        val deleteSource = ThreadLocal<Rect>()

        fun segment(popup: View): CouiSegmentButton = segments.getOrPut(popup) {
            val owner = WeakReference(popup)
            CouiSegmentButton(popup) {
                owner.get()?.let {
                    it.invalidate()
                    module.style()?.layers()?.invalidatePopup(it)
                }
            }
        }

        fun slots(popup: View, track: RectF, inset: Float, count: Int, alignLabels: Boolean): List<CouiSegmentButton.Slot> {
            // The host also uses its scrollable menu class for short rows, including the language switch.
            val scrolling = scrollMenu.isInstance(popup) && canScrollMenu.invoke(popup) == true
            val rows = buildList {
                for (index in 0 until count) {
                    val holder = holderAt.invoke(popup, index) ?: continue
                    val row = rowView.invoke(holder) as View
                    if (row.visibility != View.VISIBLE || row.width <= 0 || row.height <= 0) continue
                    val bounds = RectF(0f, 0f, row.width.toFloat(), row.height.toFloat())
                    KeyboardLayers.localToRoot(row, popup, rowToPopup).mapRect(bounds)
                    add(MenuRow(index, row, bounds))
                }
            }.sortedBy { it.bounds.left }
            return rows.mapIndexedNotNull { index, row ->
                // Fixed rows share the native space between labels, including any divider or margin.
                val left = when {
                    scrolling -> row.bounds.left
                    index == 0 -> track.left
                    else -> (rows[index - 1].bounds.right + row.bounds.left) / 2f
                }
                val right = when {
                    scrolling -> row.bounds.right
                    index == rows.lastIndex -> track.right
                    else -> (row.bounds.right + rows[index + 1].bounds.left) / 2f
                }
                val indicator = CouiSegmentButton.slotBounds(track, left, right, inset, clipHorizontal = !scrolling)
                    ?: return@mapIndexedNotNull null
                if (alignLabels && row.view is ViewGroup) {
                    // Move the label contents only. Partially visible scroll rows keep their native X.
                    val x = if (scrolling) 0f else indicator.centerX() - row.bounds.centerX()
                    val y = indicator.centerY() - row.bounds.centerY()
                    for (child in 0 until row.view.childCount) {
                        row.view.getChildAt(child).translationX = x
                        row.view.getChildAt(child).translationY = y
                    }
                }
                CouiSegmentButton.Slot(row.id, indicator, row.view.isEnabled)
            }
        }

        layoutRadius = { popup, track, inset ->
            val count = itemCount.invoke(popup) as Int
            CouiSegmentButton.trackRadius(track, slots(popup, track, inset, count, alignLabels = false))
        }

        fun update(popup: View, body: View, track: RectF, count: Int, alignLabels: Boolean): CouiSegmentButton {
            val slots = slots(popup, track, 4f * body.resources.displayMetrics.density, count, alignLabels)
            outline(body, track, CouiSegmentButton.trackRadius(track, slots))
            val selected = selectedPosition.invoke(popup) as Int
            val holder = if (selected in 0 until count) holderAt.invoke(popup, selected) else null
            val visibleSelection = if ((holder?.let { rowView.invoke(it) } as? View)?.isSelected == true) selected else -1
            return segment(popup).also { it.update(track, slots, visibleSelection, popup.isEnabled && popup.isShown) }
        }

        module.intercept(deleteAction) { chain ->
            val source = chain.getArg(3) as View
            if (module.style()?.sharesWindow(source) != true) return@intercept chain.proceed()
            // Clear and recall widen the native anchor to the menu width, even on the emoji keyboard.
            val bounds = Rect(keyBounds.invoke(chain.getArg(2)) as Rect)
            KeyboardControls.emojiActionBounds(source, "delete")?.roundOut(bounds)
            val location = IntArray(2)
            source.getLocationInWindow(location)
            bounds.offset(location[0], location[1])
            val previous = deleteSource.get()
            deleteSource.set(bounds)
            try {
                chain.proceed()
            } finally {
                if (previous == null) deleteSource.remove() else deleteSource.set(previous)
            }
        }
        module.intercept(mount) { chain ->
            val popup = chain.thisObject as View
            if (!isStyled(popup)) return@intercept chain.proceed()
            val source = (if (deleteMenu.isInstance(popup)) deleteSource.get() else null)
                ?: chain.getArg(0) as Rect
            val origin = Rect(source)
            val result = chain.proceed()
            val style = module.style()
            if (style != null && style.sharesWindow(popup)) {
                segments.remove(popup)?.cancel()
                val body = container.invoke(popup) as View
                outlines.remove(body)
                style.layers().openPopup(popup, body, origin)
            }
            result
        }
        module.intercept(DrawHooks.method(base, "t", Void.TYPE)) { chain ->
            val popup = chain.thisObject as View
            module.style()?.layers()?.closePopup(popup)
            segments.remove(popup)?.cancel()
            chain.proceed()
        }
        module.intercept(nativeTouch) { chain ->
            val popup = chain.thisObject as View
            val style = module.style()
            if (style == null || !isStyled(popup) || !style.sharesWindow(popup) || !popup.isShown) {
                return@intercept chain.proceed()
            }
            val source = chain.getArg(0) as View
            val event = chain.getArg(1) as MotionEvent
            val action = event.actionMasked
            val index = triggerPointer.invoke(popup) as Int
            if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_UP ||
                action == MotionEvent.ACTION_POINTER_UP && index == event.actionIndex) {
                segments[popup]?.observeNativeTouch(null)
                style.layers().observePopupTouch(popup, source, event, index)
                return@intercept chain.proceed()
            }
            val result = chain.proceed()
            if (result == true && popup.isShown && index in 0 until event.pointerCount &&
                (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE)) {
                val count = itemCount.invoke(popup) as Int
                val body = if (count > 0) container.invoke(popup) as View else null
                val track = body?.let(::bounds)
                if (body != null && track != null) {
                    val target = update(popup, body, track, count, alignLabels = false)
                    style.layers().observePopupTouch(popup, source, event, index)
                    source.getLocationInWindow(sourceLocation)
                    popup.getLocationInWindow(popupLocation)
                    target.observeNativeTouch(event.getX(index) + sourceLocation[0] - popupLocation[0])
                }
            }
            result
        }
        module.intercept(DrawHooks.method(base, "onDraw", Void.TYPE, Canvas::class.java)) { chain ->
            val popup = chain.thisObject as View
            val style = module.style()
            // The host can draw once after recycling its rows and before detaching.
            val count = if (style != null && style.sharesWindow(popup) && isStyled(popup)) itemCount.invoke(popup) as Int else 0
            val body = if (count > 0) container.invoke(popup) as View else null
            val track = body?.let(::bounds)
            if (style == null || body == null || track == null) return@intercept chain.proceed()
            val target = update(popup, body, track, count, alignLabels = true)
            val color = if (deleteMenu.isInstance(popup) && !recallMenu.isInstance(popup)) CLEAR_CONFIRM else null
            target.draw(chain.getArg(0) as Canvas, style, indicatorColor = color)
            null
        }

        val data = Class.forName(HOST + "C", false, loader)
        val int = Int::class.javaPrimitiveType
        val boolean = Boolean::class.javaPrimitiveType
        for ((type, holderName, updateName) in listOf(Triple(shortMenu, "E", "V"),
            Triple(scrollMenu, "F", "X"), Triple(deleteMenu, "h", "V"))) {
            val holder = Class.forName(HOST + holderName, false, loader)
            val label = DrawHooks.method(holder, "c", TextView::class.java)
            val initial = DrawHooks.method(type, if (type == scrollMenu) "V" else "T", Void.TYPE, int, holder, data)
            val update = DrawHooks.method(type, updateName, Void.TYPE, holder, int, boolean)
            for ((method, index) in listOf(initial to 1, update to 0)) {
                module.intercept(method) { chain ->
                    val result = chain.proceed()
                    val style = module.style()
                    if (style != null) {
                        val item = chain.getArg(index)
                        val row = rowView.invoke(item) as View
                        if (row.background != null) KeyboardStyle.setBackground(row, null)
                        if (method == initial && row is ViewGroup) {
                            for (child in 0 until row.childCount) {
                                row.getChildAt(child).translationX = 0f
                                row.getChildAt(child).translationY = 0f
                            }
                        }
                        val confirmedClear = type == deleteMenu && !recallMenu.isInstance(chain.thisObject) && row.isSelected
                        val color = if (style.dark() || confirmedClear) 0xFFF2F2F5.toInt() else 0xFF16171B.toInt()
                        (label.invoke(item) as TextView).setTextColor(color)
                        (row.findViewById<View>(2) as? TextView)?.setTextColor(color)
                        (row.findViewById<View>(3) as? ImageView)?.setColorFilter(color)
                        style.layers().invalidatePopup(chain.thisObject as View)
                    }
                    result
                }
            }
        }
    }

    fun face(body: View): Path? {
        val track = bounds(body) ?: return null
        val previous = outlines[body]
        if (previous?.bounds == track) return previous.path
        val popup = body.parent as View
        val radius = layoutRadius?.invoke(popup, track, 4f * body.resources.displayMetrics.density) ?: return null
        return outline(body, track, radius)
    }

    /** The frame and selection share the visible row height, excluding the host's key stem. */
    private fun bounds(body: View): RectF? {
        val popup = body.parent as? View ?: return null
        if (body.width <= 0 || body.height <= 0 || popup.width <= 0 || popup.height <= 0) return null
        return RectF(body.left.toFloat(), maxOf(0, body.top).toFloat(),
            body.right.toFloat(), minOf(popup.height, body.bottom).toFloat()).takeUnless { it.isEmpty }
    }

    private fun outline(body: View, bounds: RectF, radius: Float): Path {
        val previous = outlines[body]
        if (previous?.bounds == bounds && previous.radius == radius) return previous.path
        val path = CouiCorners.path(bounds.width(), bounds.height(), radius).apply {
            offset(bounds.left, bounds.top)
        }
        outlines[body] = MenuOutline(bounds, radius, path)
        return path
    }

    private data class MenuOutline(val bounds: RectF, val radius: Float, val path: Path)
    private data class MenuRow(val id: Int, val view: View, val bounds: RectF)
}
