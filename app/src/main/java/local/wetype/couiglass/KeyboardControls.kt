package local.wetype.couiglass

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import com.itos.coui.chip.CouiChipTabDefaults
import io.github.libxposed.api.XposedInterface
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

internal object KeyboardControls {
    private const val HOST = "com.tencent.wetype.plugin.hld."
    private const val EMOJI_KEYBOARD = HOST + "keyboard.selfdraw.S11EmojiKeyboard"
    private const val NUMBER_KEYBOARD = HOST + "keyboard.selfdraw.S4NumberKeyboard"
    private const val EMOJI_BAR = 0x7f090497
    private const val EMOJI_ITEM = 0x7f09020a
    private const val SYMBOL_STRIP = 0x7f090498
    private const val SYMBOL_LIST = 0x7f0904af
    private const val SYMBOL_LABEL = 0x7f090552
    private const val SYMBOL_STRIP_KEY = "S4_recycleview"
    private const val NUMERIC_RETRY_DELAY_MS = 250L
    private var host: HostControls? = null
    private var nativeNumeric: NativeNumeric? = null
    private var numericTray: NumericTray? = null
    private var failedStock: WeakReference<ViewGroup>? = null
    private var retryNumericAfter = 0L

    @JvmStatic
    @Throws(ReflectiveOperationException::class)
    fun install(module: WeTypeModule, loader: ClassLoader) {
        val controls = HostControls(loader)
        module.intercept(DrawHooks.method(ViewGroup::class.java, "dispatchTouchEvent", Boolean::class.javaPrimitiveType,
            MotionEvent::class.java)) { chain ->
            val group = chain.thisObject as ViewGroup
            val categories = group.background as? EmojiCategories
            if (categories != null) {
                categories.dispatch(chain, chain.getArg(0) as MotionEvent)
            } else {
                chain.proceed()
            }
        }
        module.intercept(DrawHooks.method(controls.recycler, "draw", Void.TYPE, Canvas::class.java)) { chain ->
            val group = chain.thisObject as ViewGroup
            val categories = group.background as? EmojiCategories
            if (categories == null) chain.proceed() else categories.drawGroup(group, chain)
        }
        module.intercept(DrawHooks.method(controls.recycler, "dispatchChildAttached", Void.TYPE, View::class.java)) { chain ->
            val result = chain.proceed()
            val group = chain.thisObject as ViewGroup
            if (group.background !is EmojiCategories) return@intercept result
            val item = chain.getArg(0) as View
            val row = item.findViewById<View>(EMOJI_ITEM)
            if (row != null && row.background != null && row.background !is StockRowBackground) {
                KeyboardStyle.setBackground(row, StockRowBackground(row, row.background))
            }
            result
        }
        module.intercept(controls.drawIcon) { chain ->
            val style = module.style() ?: return@intercept chain.proceed()
            val keyboard = controls.drawerOwner.invoke(chain.thisObject) as View
            if (keyboard.javaClass.name != EMOJI_KEYBOARD || !style.draws(keyboard)) return@intercept chain.proceed()
            val key = chain.getArg(1)
            val data = controls.keyData.invoke(key)
            val id = controls.keyId.invoke(data) as String?
            val face = emojiActionBounds(keyboard, id) ?: return@intercept chain.proceed()
            val native = controls.keyBounds.invoke(key) as Rect
            val canvas = chain.getArg(0) as Canvas
            val saved = canvas.save()
            try {
                canvas.translate(face.centerX() - native.exactCenterX(), face.centerY() - native.exactCenterY())
                chain.proceed()
            } finally {
                canvas.restoreToCount(saved)
            }
        }
        host = controls
        try {
            val native = NativeNumeric(module, loader, controls)
            val buttonDrawer = Class.forName(HOST + "keyboard.selfdraw.e", false, loader)
            val drawerKeyboard = DrawHooks.method(buttonDrawer, "c", controls.keyboard)
            module.intercept(DrawHooks.method(buttonDrawer, "b", Void.TYPE, controls.key)) { chain ->
                val tray = numericTray
                if (tray?.hidesStock == true && drawerKeyboard.invoke(chain.thisObject) === tray.strip.parent &&
                    controls.keyId.invoke(controls.keyData.invoke(chain.getArg(0))) == SYMBOL_STRIP_KEY) {
                    null
                } else chain.proceed()
            }
            val strip = Class.forName(HOST + "view.M", false, loader)
            module.intercept(DrawHooks.method(strip, "r", Void.TYPE, Canvas::class.java)) { chain ->
                val tray = numericTray
                if (tray != null && tray.strip === chain.thisObject && tray.hidesStock) null else chain.proceed()
            }
            module.deoptimize(DrawHooks.method(strip, "dispatchDraw", Void.TYPE, Canvas::class.java))
            nativeNumeric = native
        } catch (error: Exception) {
            module.warn("Using original numeric symbol selector", error)
        } catch (error: LinkageError) {
            module.warn("Using original numeric symbol selector", error)
        }
    }

    @JvmStatic
    fun syncNumericTray(style: KeyboardStyle, root: View?) {
        val native = nativeNumeric ?: return
        val strip = root?.let(::visibleNumericStrip)?.takeIf(style::draws)
        val stock = strip?.findViewById<View>(SYMBOL_LIST) as? ViewGroup
        val frame = stock?.parent as? FrameLayout
        val current = numericTray
        if (current != null && (current.strip !== strip || current.stock !== stock ||
                current.parent != null && current.parent !== frame)) {
            releaseNumericTray()
        }
        if (strip == null || stock == null || frame == null) return
        updateNumericTray(stock) {
            val tray = numericTray ?: NumericTray(style, strip, stock, native).also { numericTray = it }
            if (!tray.sync()) {
                releaseNumericTray()
                return
            }
            if (tray !== current) {
                // This runs from pre-draw. Mount after traversal so the parent measures the tray on the next frame.
                frame.post {
                    if (numericTray !== tray) return@post
                    val layout = stock.layoutParams as? FrameLayout.LayoutParams
                    if (layout == null || stock.parent !== frame || !style.visible(strip)) {
                        releaseNumericTray()
                    } else {
                        updateNumericTray(stock) { frame.addView(tray, FrameLayout.LayoutParams(layout)) }
                    }
                }
            } else if (tray.width > 0 && tray.height > 0) {
                tray.hideStock()
            }
        }
    }

    @JvmStatic
    fun releaseNumericTray() {
        val tray = numericTray
        numericTray = null
        tray?.remove()
    }

    private fun visibleNumericStrip(view: View): ViewGroup? {
        if (view.visibility != View.VISIBLE || view.alpha == 0f || view !is ViewGroup) return null
        if (view.javaClass.name == NUMBER_KEYBOARD) {
            return view.findViewById<ViewGroup>(SYMBOL_STRIP)?.takeIf {
                it.parent === view && it.visibility == View.VISIBLE && it.alpha != 0f
            }
        }
        // Cached keyboard pages can contain the same resource IDs as the active one.
        for (index in view.childCount - 1 downTo 0) {
            val strip = visibleNumericStrip(view.getChildAt(index))
            if (strip != null) return strip
        }
        return null
    }

    private inline fun updateNumericTray(stock: ViewGroup, update: () -> Unit) {
        val retrying = failedStock?.get() === stock
        if (retrying && SystemClock.uptimeMillis() < retryNumericAfter) return
        try {
            update()
            if (retrying) failedStock = null
        } catch (error: Exception) {
            retryNumericTray(stock, error)
        } catch (error: LinkageError) {
            retryNumericTray(stock, error)
        }
    }

    private fun retryNumericTray(stock: ViewGroup, error: Throwable) {
        val firstFailure = failedStock?.get() !== stock
        failedStock = WeakReference(stock)
        retryNumericAfter = SystemClock.uptimeMillis() + NUMERIC_RETRY_DELAY_MS
        releaseNumericTray()
        if (firstFailure) {
            nativeNumeric?.module?.warn("Could not mount numeric symbol tray; will retry", error)
            stock.postInvalidateDelayed(NUMERIC_RETRY_DELAY_MS)
        }
    }

    /** Supplies the shared surfaces and clears stock row faces while retaining their Views. */
    @JvmStatic
    fun background(style: KeyboardStyle, view: View, original: Drawable?): Drawable? {
        val controls = host ?: return null
        return when {
            view is ViewGroup && view.id == EMOJI_BAR && view.parentView()?.javaClass?.name == EMOJI_KEYBOARD ->
                original as? EmojiCategories ?: EmojiCategories(style, view, original, controls)
            rowControl(view) != null -> original as? StockRowBackground ?: StockRowBackground(view, original)
            else -> null
        }
    }

    /** Aligns both action key faces with the category track inside their native hit bounds. */
    @JvmStatic
    fun emojiActionBounds(keyboard: View, id: String?): RectF? {
        if ((id != "return" && id != "delete") || keyboard.javaClass.name != EMOJI_KEYBOARD) return null
        val categories = keyboard.findViewById<View>(EMOJI_BAR)?.background as? EmojiCategories ?: return null
        return try {
            categories.actionFace(keyboard, id == "return")
        } catch (_: ReflectiveOperationException) {
            null
        }
    }

    private fun View.parentView(): View? = parent as? View

    private fun rowControl(view: View): EmojiCategories? = if (view.id == EMOJI_ITEM)
        view.parentView()?.parentView()?.background as? EmojiCategories else null

    private class HostControls(private val loader: ClassLoader) {
        private fun load(name: String): Class<*> = Class.forName(name, false, loader)

        val recycler = load("androidx.recyclerview.widget.RecyclerView")
        private val emojiBar = load(HOST + "emoji.ImeSymbolEmojiTypeScrollView")
        val keyboard = load(HOST + "keyboard.selfdraw.o")
        val key = load(HOST + "keyboard.selfdraw.j")
        private val data = load(HOST + "keyboard.selfdraw.bean.KeyData")
        private val drawer = load(HOST + "keyboard.selfdraw.drawmethod.a")
        private val imageDrawer = load(HOST + "keyboard.selfdraw.drawmethod.g")
        val childPosition = DrawHooks.method(recycler, "getChildAdapterPosition", Int::class.javaPrimitiveType, View::class.java)
        val childUnder = DrawHooks.method(recycler, "findChildViewUnder", View::class.java,
            Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
        val selectedPosition = DrawHooks.method(emojiBar, "getCurrentSelectItemPos", Int::class.javaPrimitiveType)
        val findKey = DrawHooks.method(keyboard, "w1", key, String::class.java, Boolean::class.javaPrimitiveType)
        val keyBounds = DrawHooks.method(key, "t", Rect::class.java)
        val hitBounds = DrawHooks.method(key, "F", Rect::class.java)
        val keyData = DrawHooks.method(key, "O", data)
        val keyId = DrawHooks.method(data, "getId", String::class.java)
        val alignRight = DrawHooks.method(data, "getAlignParentRight", Boolean::class.javaObjectType)

        val drawerOwner = DrawHooks.method(drawer, "c", keyboard)
        val drawIcon = DrawHooks.method(imageDrawer, "b", Void.TYPE, Canvas::class.java, key)
        private val shifted = DrawHooks.method(keyboard, "Y1", Boolean::class.javaPrimitiveType)
        private val shadow = DrawHooks.method(key, "Z", Float::class.javaPrimitiveType)

        fun faceBottom(owner: View, button: Any, bounds: Rect): Float =
            bounds.bottom - if (shifted.invoke(owner) == true) shadow.invoke(button) as Float else 0f
    }

    private abstract class ControlBackground(protected val stock: Drawable?) : Drawable() {
        protected var drawableAlpha = 255
        override fun setAlpha(alpha: Int) { drawableAlpha = alpha; invalidateSelf() }
        override fun getAlpha() = drawableAlpha
        override fun setColorFilter(filter: ColorFilter?) = Unit
        override fun getMinimumWidth() = stock?.minimumWidth ?: 0
        override fun getMinimumHeight() = stock?.minimumHeight ?: 0
        override fun getIntrinsicWidth() = stock?.intrinsicWidth ?: -1
        override fun getIntrinsicHeight() = stock?.intrinsicHeight ?: -1
        override fun getPadding(padding: Rect) = stock?.getPadding(padding) ?: super.getPadding(padding)
        @Deprecated("Deprecated in Android")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private class StockRowBackground(row: View, original: Drawable?) : ControlBackground(original) {
        private val owner = WeakReference(row)
        override fun isStateful() = stock?.isStateful == true
        override fun onStateChange(state: IntArray): Boolean {
            val changed = stock?.setState(state) == true
            if (changed) invalidateSelf()
            return changed
        }
        override fun draw(canvas: Canvas) {
            if (owner.get()?.let(::rowControl) != null) return
            val original = stock ?: return
            original.setBounds(bounds)
            original.setAlpha(drawableAlpha)
            original.setState(state)
            original.draw(canvas)
        }
    }

    private class EmojiCategories(
        private val style: KeyboardStyle, group: ViewGroup, original: Drawable?, private val controls: HostControls,
    ) : ControlBackground(original), View.OnLayoutChangeListener {
        private val owner = WeakReference(group)
        private val density = group.resources.displayMetrics.density
        private val slop = ViewConfiguration.get(group.context).scaledTouchSlop
        private val track = RectF()
        private val actionTrack = RectF()
        private val toKeyboard = Matrix()
        private val motionBounds = Rect()
        private val segment = CouiSegmentButton(group) { invalidateSelf() }
        private var captured = false

        init {
            group.addOnLayoutChangeListener(this)
            alignTrack(group)
        }

        override fun onLayoutChange(view: View, left: Int, top: Int, right: Int, bottom: Int,
                                    oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int) {
            if (view.background === this) {
                alignTrack(view as ViewGroup)
            } else {
                if (view.background !is EmojiCategories) view.translationX = 0f
                view.removeOnLayoutChangeListener(this)
            }
        }

        private fun alignTrack(group: ViewGroup) {
            val keyboard = group.parentView() ?: return
            val translation = try {
                val keys = actionKeys(keyboard)
                if (keys == null || !keys.outerDelete || !layoutTrack(group, slots(group))) {
                    0f
                } else {
                    val center = keyboard.width / 2f
                    val halfWidth = track.width() / 2f
                    // Keep the RecyclerView and its native touch dispatch together.
                    if (mirroredBackFace(keyboard.width, keys, center - halfWidth, center + halfWidth) != null) {
                        center - group.left - track.centerX()
                    } else 0f
                }
            } catch (_: ReflectiveOperationException) {
                0f
            }
            group.translationX = translation
        }

        override fun draw(canvas: Canvas) {
            val group = owner.get() ?: return
            val slots = slots(group)
            if (!layoutTrack(group, slots)) return
            updateSegment(group, slots, selected(group))
            segment.draw(canvas, style, alpha = drawableAlpha)
        }

        fun drawGroup(group: ViewGroup, chain: XposedInterface.Chain): Any? {
            val motion = style.motion().sample(group) ?: return chain.proceed()
            val slots = slots(group)
            if (!layoutTrack(group, slots)) return chain.proceed()
            updateSegment(group, slots, selected(group))
            // Lift the complete control above its RecyclerView bounds while the spring moves it.
            if (style.layers().lift(group, group, track, segment.outerRadius, motion, chain)) return null
            val canvas = chain.getArg(0) as Canvas
            val saved = canvas.save()
            try {
                motion.transform(canvas, track.centerX(), track.centerY())
                return chain.proceed()
            } finally {
                canvas.restoreToCount(saved)
            }
        }

        fun actionFace(keyboard: View, back: Boolean): RectF? {
            val group = owner.get() ?: return null
            if (!layoutTrack(group, slots(group))) return null
            actionTrack.set(track)
            KeyboardLayers.localToRoot(group, keyboard, toKeyboard).mapRect(actionTrack)
            val keys = actionKeys(keyboard) ?: return null
            val backHit = keys.backHit
            val deleteHit = keys.deleteHit
            val top = maxOf(actionTrack.top, backHit.top.toFloat(), deleteHit.top.toFloat())
            val bottom = minOf(actionTrack.bottom, backHit.bottom.toFloat(), deleteHit.bottom.toFloat())
            if (bottom - top < 2f) return null

            val rowWidth = keyboard.width
            if (keys.outerDelete) {
                val mirrored = mirroredBackFace(rowWidth, keys, actionTrack.left, actionTrack.right)
                if (mirrored != null) {
                    val (left, right) = mirrored
                    return if (back) RectF(left, top, right, bottom)
                    else RectF(rowWidth - right, top, rowWidth - left, bottom)
                }
            }

            val edgePadding = 4f * density
            val trackGap = 3f * density
            val backLeft = maxOf(edgePadding, backHit.left.toFloat())
            val backRight = minOf(actionTrack.left - trackGap, backHit.right.toFloat())
            val deleteLeft = maxOf(actionTrack.right + trackGap, deleteHit.left.toFloat())
            val deleteRight = minOf(rowWidth - edgePadding, deleteHit.right.toFloat())
            val width = minOf(keys.backWidth, backRight - backLeft, deleteRight - deleteLeft)
            if (width < 2f) return null
            val left = if (back) backRight - width else deleteLeft
            return RectF(left, top, left + width, bottom)
        }

        private fun mirroredBackFace(rowWidth: Int, keys: ActionKeys, trackLeft: Float,
                                     trackRight: Float): Pair<Float, Float>? {
            val gap = 3f * density
            val outerLimit = maxOf(4f * density, keys.backHit.left.toFloat(), rowWidth - keys.deleteHit.right.toFloat())
            val innerLimit = minOf(trackLeft - gap, rowWidth - trackRight - gap,
                keys.backHit.right.toFloat(), rowWidth - keys.deleteHit.left.toFloat())
            val width = minOf(keys.backWidth, innerLimit - outerLimit)
            if (width < minOf(keys.backWidth, keys.deleteWidth)) return null
            return (innerLimit - width) to innerLimit
        }

        private fun actionKeys(keyboard: View): ActionKeys? {
            val backKey = controls.findKey.invoke(keyboard, "return", false) ?: return null
            val deleteKey = controls.findKey.invoke(keyboard, "delete", false) ?: return null
            val backBounds = controls.keyBounds.invoke(backKey) as Rect
            val deleteBounds = controls.keyBounds.invoke(deleteKey) as Rect
            val backHit = controls.hitBounds.invoke(backKey) as Rect
            val deleteHit = controls.hitBounds.invoke(deleteKey) as Rect
            if (backBounds.isEmpty || deleteBounds.isEmpty || backHit.isEmpty || deleteHit.isEmpty) return null
            val outerDelete = controls.alignRight.invoke(controls.keyData.invoke(deleteKey)) == true
            return ActionKeys(backHit, deleteHit, backBounds.width().toFloat(), deleteBounds.width().toFloat(), outerDelete)
        }

        private data class ActionKeys(val backHit: Rect, val deleteHit: Rect, val backWidth: Float,
                                      val deleteWidth: Float, val outerDelete: Boolean)

        private fun indicatorBounds(slot: Slot): RectF? {
            val gap = minOf(2f * density, track.height() / 6f)
            val x = slot.glyphX
            val y = (slot.top + slot.bottom) / 2f
            val halfWidth = minOf(x - slot.left, slot.right - x) - gap
            val halfHeight = minOf(slot.bottom - y, y - track.top - gap, track.bottom - y - gap)
            if (halfWidth < 1f || halfHeight < 1f) return null
            return RectF(x - halfWidth, y - halfHeight, x + halfWidth, y + halfHeight)
        }

        private fun layoutTrack(group: ViewGroup, slots: List<Slot>): Boolean {
            if (slots.isEmpty()) return false
            val gap = 2f * density
            track.set(slots.first().left, slots.minOf { it.top } - gap,
                slots.last().right, slots.maxOf { it.bottom } + gap)
            return track.intersect(0f, 0f, group.width.toFloat(), group.height.toFloat())
        }

        private fun slots(group: ViewGroup): List<Slot> = buildList {
            for (index in 0 until group.childCount) {
                val item = group.getChildAt(index)
                val button = item.findViewById<View>(EMOJI_ITEM) ?: continue
                if (item.visibility != View.VISIBLE || button.visibility != View.VISIBLE ||
                    item.width <= 0 || button.width <= 0 || button.height <= 0) continue
                val indexInAdapter = controls.childPosition.invoke(group, item) as Int
                if (indexInAdapter < 0) continue
                val left = item.x
                val top = item.y + button.y
                // Edge items have one-sided padding around the centered icon container.
                val glyphX = left + button.x + button.width / 2f
                add(Slot(indexInAdapter, item, left, top, left + item.width, top + button.height, glyphX))
            }
        }.sortedBy { it.left }

        private fun selected(group: ViewGroup) = controls.selectedPosition.invoke(group) as Int

        private fun updateSegment(group: ViewGroup, slots: List<Slot>, selected: Int) {
            segment.update(track, slots.mapNotNull { slot ->
                indicatorBounds(slot)?.let { CouiSegmentButton.Slot(slot.index, it, slot.clickable) }
            }, selected, group.isEnabled)
        }

        fun dispatch(chain: XposedInterface.Chain, event: MotionEvent): Any? {
            val group = owner.get() ?: return chain.proceed()
            val action = event.actionMasked
            val ending = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
            val motion = style.motion()
            motion.update(group, event)
            if (action == MotionEvent.ACTION_DOWN) unlock(group)
            val slots = slots(group)
            if (!group.isEnabled || !group.isShown || !layoutTrack(group, slots)) {
                val wasCaptured = captured
                segment.cancel()
                motion.sample(group)?.takeIf { it.pointerId >= 0 }?.release(null)
                if (ending) unlock(group)
                return if (wasCaptured) true else chain.proceed()
            }
            val selected = selected(group)
            updateSegment(group, slots, selected)
            val hitId: (Float, Float) -> Int? = { x, y -> slotAt(group, slots, x, y)?.index }
            if (action == MotionEvent.ACTION_DOWN) {
                val result = chain.proceed()
                if (result == true && hitId(event.x, event.y) != null) {
                    segment.onTouch(event, hitId)
                    track.roundOut(motionBounds)
                    motion.down(group, group, motionBounds, event, event.actionIndex)
                } else {
                    segment.cancel()
                }
                return result
            }
            val touch = segment.onTouch(event, hitId) { pressedId ->
                slots.size > 1 && (pressedId == selected ||
                    !group.canScrollHorizontally(-1) && !group.canScrollHorizontally(1))
            }
            if (touch.startedDrag) {
                val cancel = MotionEvent.obtain(event)
                try {
                    cancel.action = MotionEvent.ACTION_CANCEL
                    chain.proceed(arrayOf(cancel))
                } finally {
                    cancel.recycle()
                }
                captured = true
                group.parent?.requestDisallowInterceptTouchEvent(true)
            }
            if (captured) {
                if (ending) unlock(group)
                if (action == MotionEvent.ACTION_UP && touch.commitId != null) {
                    // Allow downward drift after capture, but cancel a release up in the emoji grid.
                    if (event.y >= track.top - slop) {
                        slots.firstOrNull { it.index == touch.commitId && it.clickable }?.item?.performClick()
                    }
                    updateSegment(group, slots, selected(group))
                }
                return true
            }
            return chain.proceed()
        }

        override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
            owner.get()?.let { group ->
                if (visible) {
                    alignTrack(group)
                } else {
                    group.translationX = 0f
                    segment.cancel()
                    unlock(group)
                    style.motion().sample(group)?.takeIf { it.pointerId >= 0 }?.release(null)
                }
            }
            return super.setVisible(visible, restart)
        }

        private fun unlock(group: ViewGroup) {
            if (captured) group.parent?.requestDisallowInterceptTouchEvent(false)
            captured = false
        }

        private fun slotAt(group: ViewGroup, slots: List<Slot>, x: Float, y: Float): Slot? {
            if (x < track.left || x >= track.right || y < track.top - slop || y >= track.bottom + slop) return null
            val item = controls.childUnder.invoke(group, x, y) as? View ?: return null
            return slots.firstOrNull { it.item === item && it.clickable }
        }

        private data class Slot(
            val index: Int, val item: View, val left: Float, val top: Float,
            val right: Float, val bottom: Float, val glyphX: Float,
        ) {
            val clickable get() = item.isEnabled && item.isClickable

        }
    }

    private class NativeNumeric(val module: WeTypeModule, private val loader: ClassLoader, private val controls: HostControls) {
        private fun load(name: String): Class<*> = Class.forName(name, false, loader)

        private val adapterClass = load(HOST + "oldsymbol.b")
        private val recyclerAdapter = load("androidx.recyclerview.widget.RecyclerView\$h")
        private val holder = load("androidx.recyclerview.widget.RecyclerView\$ViewHolder")
        private val symbolHolder = load(HOST + "oldsymbol.d")
        private val getAdapter = DrawHooks.method(controls.recycler, "getAdapter", recyclerAdapter)
        private val keyFill = DrawHooks.method(controls.key, "i", Int::class.javaPrimitiveType)
        private val getData = DrawHooks.method(adapterClass, "getDataList", List::class.java)
        private val itemType = DrawHooks.method(adapterClass, "getItemViewType", Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType)
        private val create = DrawHooks.method(adapterClass, "onCreateViewHolder", holder, ViewGroup::class.java,
            Int::class.javaPrimitiveType)
        private val bind = DrawHooks.method(symbolHolder, "a", Void.TYPE, Any::class.java)
        private val itemView = holder.getField("itemView")
        private val digitToStock = Matrix()
        private val face = RectF()

        fun adapter(list: ViewGroup): Any? = getAdapter.invoke(list)?.takeIf(adapterClass::isInstance)

        fun data(adapter: Any): List<*> = (getData.invoke(adapter) as List<*>).toList()

        data class DigitLayout(val fill: Int, val top: Int, val bottom: Int, val gap: Int)

        fun digitLayout(keyboard: View, stock: View): DigitLayout? {
            // The replacement shares the stock RecyclerView's FrameLayout slot.
            KeyboardLayers.localToRoot(keyboard, stock, digitToStock)
            val first = controls.findKey.invoke(keyboard, "S4_key_1", false) ?: return null
            if (!readFace(keyboard, first)) return null
            val top = face.top.roundToInt().coerceAtLeast(0)
            val fourth = controls.findKey.invoke(keyboard, "S4_key_4", false) ?: return null
            if (!readFace(keyboard, fourth)) return null
            val fourthBottom = face.bottom
            val seventh = controls.findKey.invoke(keyboard, "S4_key_7", false) ?: return null
            if (!readFace(keyboard, seventh)) return null
            val bottom = face.bottom.roundToInt()
            if (bottom <= top) return null
            val gap = (face.top - fourthBottom).roundToInt().coerceIn(0, (bottom - top) / 4)
            return DigitLayout(keyFill.invoke(first) as Int, top, bottom, gap)
        }

        private fun readFace(keyboard: View, key: Any): Boolean {
            val bounds = controls.keyBounds.invoke(key) as Rect
            face.set(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(),
                controls.faceBottom(keyboard, key, bounds))
            if (face.isEmpty) return false
            digitToStock.mapRect(face)
            return !face.isEmpty
        }

        fun createRow(list: ViewGroup, adapter: Any, position: Int, value: Any?): View {
            val row = create.invoke(adapter, list, itemType.invoke(adapter, position))
            // Use the native holder's glyph binding and click listener without altering the RecyclerView's holder cache.
            if (value != null && symbolHolder.isInstance(row)) bind.invoke(row, value)
            return (itemView.get(row) as View).also { if (value == null) it.isEnabled = false }
        }
    }

    private class NumericTray(
        private val style: KeyboardStyle, val strip: ViewGroup, val stock: ViewGroup, private val native: NativeNumeric,
    ) : LinearLayout(stock.context) {
        private val density = resources.displayMetrics.density
        private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        private val chinese = resources.configuration.locales[0].language == "zh"
        private val transparent = ColorDrawable(Color.TRANSPARENT)
        private var stockBackground: Drawable? = null
        private var stockStripBottomPadding = strip.paddingBottom
        private var appliedStripBottomPadding: Int? = null
        var hidesStock = false
            private set
        private var adapter: Any? = null
        private var entries = emptyList<Any?>()
        private var slots = 0
        private var visiblePage = -1
        private var dark = style.dark()
        private var page = 0
        private var pageCount = 1
        private val menu = NumericMenu(style, context)
        private val nextLabel = TextView(context).apply {
            setSingleLine()
            gravity = Gravity.CENTER
            textSize = 14f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        private val next = object : FrameLayout(context) {
            private var downX = 0f
            private var downY = 0f
            private var suppressClick = false

            override fun getAccessibilityClassName() = Button::class.java.name

            override fun onTouchEvent(event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.x
                        downY = event.y
                        suppressClick = false
                    }
                    MotionEvent.ACTION_POINTER_DOWN -> suppressClick = true
                    MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> if (!suppressClick) {
                        suppressClick = outsideSlop(event.x, event.y) || (0 until event.historySize).any {
                            outsideSlop(event.getHistoricalX(it), event.getHistoricalY(it))
                        }
                    }
                }
                if (event.actionMasked != MotionEvent.ACTION_UP || !suppressClick) return super.onTouchEvent(event)

                // Keep the button's drag feedback, then cancel its pending page click.
                val cancel = MotionEvent.obtain(event)
                try {
                    cancel.action = MotionEvent.ACTION_CANCEL
                    return super.onTouchEvent(cancel)
                } finally {
                    cancel.recycle()
                }
            }

            private fun outsideSlop(x: Float, y: Float): Boolean {
                val dx = x - downX
                val dy = y - downY
                return dx * dx + dy * dy > slop * slop
            }
        }.apply {
            isFocusable = true
            addView(nextLabel, FrameLayout.LayoutParams(-1, -1))
            background = ButtonBackground(style, this, null)
            setOnClickListener {
                if (numericTray !== this@NumericTray) return@setOnClickListener
                page = (page + 1) % pageCount
                updateNumericTray(stock) { if (!sync()) releaseNumericTray() }
            }
        }

        init {
            orientation = VERTICAL
            isMotionEventSplittingEnabled = false
            clipToPadding = false
            addView(menu, numericRowParams())
            addView(next, numericRowParams())
        }

        fun sync(): Boolean {
            if (stock.visibility == GONE || !hidesStock && stock.visibility != VISIBLE || stock.width <= 0) return false
            val adapter = native.adapter(stock) ?: return false
            val data = native.data(adapter)
            if (data.isEmpty()) return false
            val digit = native.digitLayout(strip.parentView() ?: return false, stock) ?: return false
            val frame = stock.parentView() ?: return false
            val stockTop = (frame.y + stock.y).roundToInt()
            val stripHeightFromStock = strip.height - stockTop
            if (strip.paddingBottom != appliedStripBottomPadding) stockStripBottomPadding = strip.paddingBottom
            val originalHeight = minOf(stock.height, stripHeightFromStock - stockStripBottomPadding)
            val availableHeight = originalHeight - stock.paddingTop - stock.paddingBottom
            if (availableHeight <= 0) return false

            // The stock scroller reserves a footer inset that can stop above the last digit's face.
            val bottomPadding = (stripHeightFromStock - digit.bottom).coerceIn(0, stockStripBottomPadding)
            setStripBottomPadding(bottomPadding)
            appliedStripBottomPadding = bottomPadding
            // Keep the stock row's touch height when possible. A short tray still has a symbol and a page button.
            val nativeHeight = stock.getChildAt(0)?.height?.takeIf { it > 0 }?.toFloat() ?: 48f * density
            val slots = (availableHeight / nativeHeight).roundToInt().coerceAtLeast(2)
            val perPage = if (data.size <= slots) slots else slots - 1
            updateLayout(digit, perPage)
            val dark = style.dark()
            if (this.adapter === adapter && entries == data && this.slots == slots && visiblePage == page && this.dark == dark) {
                menu.clearRowBackgrounds()
                return true
            }
            nextLabel.setTextColor(CouiChipTabDefaults.contentColor(dark).toArgb())
            pageCount = (data.size - 1) / perPage + 1
            page = page.coerceIn(0, pageCount - 1)
            val start = page * perPage
            val rows = (start until minOf(start + perPage, data.size)).map { position ->
                native.createRow(stock, adapter, position, data[position]).apply {
                    val label = findViewById<TextView>(SYMBOL_LABEL)
                    if (label != null && isClickable && isEnabled) {
                        if (contentDescription == null) {
                            contentDescription = if (label.text.isBlank()) {
                                if (chinese) "空格" else "Space"
                            } else label.text
                        }
                        isFocusable = true
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                        label.isFocusable = false
                        label.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }
                }
            }
            menu.setRows(rows, if (pageCount > 1) perPage - rows.size else 0)
            next.visibility = if (pageCount > 1) VISIBLE else GONE
            val last = page == pageCount - 1
            nextLabel.text = if (chinese) { if (last) "首页" else "下页" } else { if (last) "First" else "Next" }
            next.contentDescription = if (chinese) {
                val action = if (last) "回到第一页" else "下一页"
                "符号第 ${page + 1} 页，共 $pageCount 页，$action"
            } else "Symbol page ${page + 1} of $pageCount, ${nextLabel.text}"
            this.adapter = adapter
            entries = data
            this.slots = slots
            visiblePage = page
            this.dark = dark
            return true
        }

        private fun updateLayout(digit: NativeNumeric.DigitLayout, perPage: Int) {
            val bottom = (stock.height - digit.bottom).coerceAtLeast(0)
            if (paddingLeft != stock.paddingLeft || paddingTop != digit.top ||
                paddingRight != stock.paddingRight || paddingBottom != bottom) {
                setPadding(stock.paddingLeft, digit.top, stock.paddingRight, bottom)
            }
            val menuLayout = menu.layoutParams as LayoutParams
            if (menuLayout.weight != perPage.toFloat()) {
                menuLayout.weight = perPage.toFloat()
                menu.layoutParams = menuLayout
            }
            val nextLayout = next.layoutParams as LayoutParams
            if (nextLayout.topMargin != digit.gap) {
                nextLayout.topMargin = digit.gap
                next.layoutParams = nextLayout
            }
            menu.setDigitFill(digit.fill)
            (next.background as? ButtonBackground)?.setDigitFill(digit.fill)
        }

        private fun setStripBottomPadding(bottom: Int) {
            if (strip.paddingBottom == bottom) return
            if (strip.isPaddingRelative) {
                strip.setPaddingRelative(strip.paddingStart, strip.paddingTop, strip.paddingEnd, bottom)
            } else {
                strip.setPadding(strip.paddingLeft, strip.paddingTop, strip.paddingRight, bottom)
            }
        }

        fun hideStock() {
            val current = strip.background
            if (current != null && current !== transparent) {
                stockBackground = current
                KeyboardStyle.setBackground(strip, transparent)
            }
            if (stock.visibility != INVISIBLE) stock.visibility = INVISIBLE
            if (!hidesStock) {
                hidesStock = true
                strip.parentView()?.invalidate()
            }
        }

        fun remove() {
            menu.cancelSelection()
            style.motion().cancelUnder(this)
            if (strip.paddingBottom == appliedStripBottomPadding) setStripBottomPadding(stockStripBottomPadding)
            if (strip.background === transparent) KeyboardStyle.setBackground(strip, stockBackground)
            if (hidesStock) {
                if (stock.visibility == INVISIBLE) stock.visibility = VISIBLE
                strip.parentView()?.invalidate()
            }
            (parent as? ViewGroup)?.removeView(this)
        }

        override fun shouldDelayChildPressedState() = false
        override fun onTouchEvent(event: MotionEvent) = true
    }

    private class NumericMenu(private val style: KeyboardStyle, context: Context) : LinearLayout(context) {
        private val density = resources.displayMetrics.density
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private val surface = ButtonBackground(style, this, null)
        private val position = SelectionSpring(::invalidate)
        private val previewAlpha = SelectionSpring(::invalidate)
        private var fill = Color.WHITE
        private var tracking = false
        private var preview: View? = null
        private var previewHeight = 0

        init {
            orientation = VERTICAL
            background = surface
        }

        fun setRows(rows: List<View>, emptySlots: Int) {
            cancelSelection()
            previewAlpha.snapTo(0f)
            removeAllViews()
            for (row in rows) addView(row, numericRowParams())
            if (emptySlots > 0) addView(View(context), numericRowParams(emptySlots.toFloat()))
            clearRowBackgrounds()
        }

        fun clearRowBackgrounds() {
            // Native skin refreshes can put a selector back on an existing symbol row.
            for (index in 0 until childCount) {
                val row = getChildAt(index)
                if (row.background != null) KeyboardStyle.setBackground(row, null)
            }
        }

        fun setDigitFill(color: Int) {
            fill = color
            surface.setDigitFill(color)
        }

        override fun dispatchDraw(canvas: Canvas) {
            val opacity = (previewAlpha.value.coerceIn(0f, 1f) * Color.alpha(fill)).roundToInt()
            if (opacity > 0 && previewHeight > 0 && width > 0 && height > 0) {
                val inset = minOf(2f * density, width / 8f, previewHeight / 8f)
                val previewBottom = (height - inset).coerceAtLeast(inset)
                val rowHeight = (previewHeight - 2f * inset).coerceIn(0f, previewBottom - inset)
                val top = (position.value - rowHeight / 2f).coerceIn(inset, previewBottom - rowHeight)
                val radius = (KeySurface.radius(width.toFloat(), height.toFloat(), density) - inset)
                    .coerceIn(0f, minOf(width - 2f * inset, rowHeight) / 2f)
                val dark = CouiPalette.darkKey(style.dark(), fill)
                style.material().draw(canvas, inset, top, width - inset, top + rowHeight, density, radius,
                    CouiGlass.indicator(dark, opacity), opacity, false, dark, null)
            }
            super.dispatchDraw(canvas)
        }

        override fun onInterceptTouchEvent(event: MotionEvent) = true

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!isEnabled) return false
                    tracking = true
                    isPressed = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    showPreview(rowAt(event))
                }
                MotionEvent.ACTION_MOVE -> if (tracking) showPreview(rowAt(event))
                MotionEvent.ACTION_UP -> {
                    val selected = if (tracking && (event.flags and MotionEvent.FLAG_CANCELED) == 0) rowAt(event) else null
                    cancelSelection()
                    selected?.performClick()
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> cancelSelection()
            }
            return true
        }

        private fun rowAt(event: MotionEvent): View? {
            if (event.x < -slop || event.x >= width + slop ||
                event.y < -slop || event.y >= height + slop || height <= 0) return null
            val y = event.y.coerceIn(0f, (height - 1).toFloat())
            for (index in 0 until childCount) {
                val row = getChildAt(index)
                if (y >= row.top && y < row.bottom && row.isEnabled && row.isClickable) return row
            }
            return null
        }

        private fun showPreview(row: View?) {
            if (row === preview) return
            if (row != null) {
                previewHeight = row.height
                val center = (row.top + row.bottom) / 2f
                if (preview == null) position.snapTo(center) else position.animateTo(center)
            }
            if (row == null) previewAlpha.animateTo(0f)
            else if (preview == null) previewAlpha.animateTo(1f)
            preview = row
        }

        fun cancelSelection() {
            if (!tracking) return
            tracking = false
            isPressed = false
            showPreview(null)
            parent?.requestDisallowInterceptTouchEvent(false)
            style.motion().sample(this)?.takeIf { it.pointerId >= 0 }?.release(null)
        }
    }

    private fun numericRowParams(weight: Float = 1f) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, weight)
}
