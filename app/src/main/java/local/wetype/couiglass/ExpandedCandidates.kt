package local.wetype.couiglass

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.TextView
import io.github.libxposed.api.XposedInterface
import java.util.WeakHashMap

/** Keeps the S6 adapter, item views and delete action in their native layout. */
internal class ExpandedCandidates(private val style: KeyboardStyle) {
    private val backgrounds = WeakHashMap<View, AppliedBackground>()
    private val overscroll = WeakHashMap<View, Int>()
    private val emptyWords = WeakHashMap<View, WordClickState>()
    private var panel: View? = null
    private var syllables: CandidateSyllables? = null
    private var strokes: CandidateStrokes? = null
    private var nativeWordPadding = 0
    var wordList: ViewGroup? = null
        private set
    var deleteKey: View? = null
        private set

    fun sync(root: View?) {
        val native = host ?: return
        val area = root?.findViewById<ViewGroup>(CONTAINER)
        val next = (area?.parent as? View)?.takeIf { it.javaClass.name == S6_CLASS && style.visible(it) }
        if (next !== panel) {
            clear()
            panel = next
        }
        val s6 = next ?: return
        val grid = area.findViewById<ViewGroup>(WORD_GRID) ?: return
        val list = grid.findViewById<ViewGroup>(LIST)?.takeIf(native.recycler::isInstance) ?: return
        bindWords(list)
        deleteKey = grid.findViewById<View>(DELETE)?.takeIf(style::visible)
        clearBackground(s6)
        clearBackground(area)
        clearBackground(grid)
        clearBackground(list)
        clearBackground(area.findViewById(VERTICAL_DIVIDER))
        // The grid starts with a skin divider ImageView beneath the RecyclerView.
        (grid.getChildAt(0) as? ImageView)?.let(::clearBackground)
        disableStretch(list, native)
        deleteKey?.let { key ->
            if (key.background !is ButtonBackground) surface(key) { ButtonBackground(style, key, it) }
        }
        val gap = list.dp(2)
        for (index in 0 until list.childCount) {
            val cell = list.getChildAt(index)
            if (cell.id != WORD_ITEM) continue
            val label = cell.findViewById<TextView>(WORD_LABEL)
            // Fake entries hide the label without clearing a recycled TextView's previous text.
            if (label != null && label.visibility == View.VISIBLE && !label.text.isNullOrBlank()) {
                emptyWords.remove(cell)?.restore(cell)
                // The list clips the face and label together, including during press motion.
                surface(cell) { original -> InsetDrawable(ButtonBackground(style, cell, original, false), gap) }
            } else {
                clearBackground(cell)
                if (native.isPlaceholder(list, cell)) disableWordClicks(cell)
                else emptyWords.remove(cell)?.restore(cell)
            }
        }
        syncSyllables(area, native)
        syncStrokes(area, root)
    }

    fun strokePopup() = strokes?.popup

    private fun bindWords(list: ViewGroup?) {
        if (list === wordList) return
        wordList?.let { previous ->
            if (previous.paddingBottom == 0 && nativeWordPadding > 0) {
                previous.setPadding(previous.paddingLeft, previous.paddingTop, previous.paddingRight, nativeWordPadding)
            }
        }
        wordList = list
        if (list != null) useFullWordHeight(list)
    }

    private fun useFullWordHeight(list: ViewGroup) {
        // S6 reserves one row in the RecyclerView after its first-stroke state changes.
        // The popup samples those same words, so keep the native viewport full-height.
        nativeWordPadding = list.paddingBottom
        if (nativeWordPadding != 0) list.setPadding(list.paddingLeft, list.paddingTop, list.paddingRight, 0)
    }

    private fun syncWordPadding(keyboard: Any?) {
        if (keyboard === panel) wordList?.let(::useFullWordHeight)
    }

    private fun disableWordClicks(cell: View) {
        if (cell !in emptyWords) {
            emptyWords[cell] = WordClickState(cell.isClickable, cell.isLongClickable)
            cell.cancelPendingInputEvents()
            cell.isPressed = false
        }
        if (cell.isClickable) cell.isClickable = false
        if (cell.isLongClickable) cell.isLongClickable = false
    }

    private fun syncSyllables(area: ViewGroup, native: CandidateLists) {
        val container = area.findViewById<ViewGroup>(PINYIN)?.takeIf(style::visible)
        val list = container?.findViewById<ViewGroup>(LIST)?.takeIf(native.recycler::isInstance)
        val frame = list?.parent as? RelativeLayout
        if (list !== syllables?.list) {
            syllables?.let {
                it.close()
                restoreBackground(it.list)
            }
            syllables = if (list != null && frame != null) CandidateSyllables(style, list, frame, native) else null
        }
        val rail = syllables ?: return
        clearBackground(container)
        clearBackground(frame)
        disableStretch(rail.list, native)
        surface(rail.list) { rail.face }
        for (index in 0 until rail.list.childCount) {
            val row = rail.list.getChildAt(index)
            if (row.id == PINYIN_ITEM) clearBackground(row)
        }
        rail.sync()
    }

    private fun syncStrokes(area: ViewGroup, root: View) {
        val filter = area.findViewById<ViewGroup>(STROKE_FILTER)?.takeIf(style::visible)
        if (filter !== strokes?.view) {
            strokes?.let {
                it.close()
                it.options?.let(::restoreBackground)
                it.toggle?.let(::restoreBackground)
                it.stockBackgrounds.forEach(::restoreBackground)
            }
            strokes = filter?.let { CandidateStrokes(style, it) }
        }
        val strip = strokes ?: return
        for (view in strip.stockBackgrounds) clearBackground(view)
        // A background keeps the native row on View.draw, where the popup owns its paint.
        strip.options?.let { row -> surface(row) { strip.face } }
        strip.toggle?.let { toggle ->
            surface(toggle) { InsetDrawable(ButtonBackground(style, toggle, it), toggle.dp(2)) }
        }
        strip.sync(root)
    }

    private fun clearBackground(view: View?) {
        val current = view?.background ?: return
        val applied = backgrounds[view]
        val original = if (applied != null && current === applied.replacement) applied.original else current
        replaceBackground(view, original, null)
    }

    private fun surface(view: View, make: (Drawable?) -> Drawable) {
        val current = view.background
        val applied = backgrounds[view]
        if (current != null && current === applied?.replacement) return
        val original = if (applied != null && current === applied.replacement) applied.original else current
        replaceBackground(view, original, make(original))
    }

    private fun replaceBackground(view: View, original: Drawable?, replacement: Drawable?) {
        backgrounds[view] = AppliedBackground(original, replacement)
        KeyboardStyle.setBackground(view, replacement)
    }

    private fun restoreBackground(view: View) {
        val applied = backgrounds.remove(view) ?: return
        if (view.background === applied.replacement) KeyboardStyle.setBackground(view, applied.original)
    }

    private fun disableStretch(list: ViewGroup, native: CandidateLists) {
        if (list.overScrollMode == View.OVER_SCROLL_NEVER) return
        overscroll.putIfAbsent(list, list.overScrollMode)
        list.overScrollMode = View.OVER_SCROLL_NEVER
        // RecyclerView can still draw an EdgeEffect created before the mode changed.
        native.clearEdges(list)
    }

    fun clear() {
        syllables?.close()
        strokes?.close()
        syllables = null
        strokes = null
        for ((view, applied) in backgrounds) {
            if (view.background === applied.replacement) KeyboardStyle.setBackground(view, applied.original)
        }
        backgrounds.clear()
        for ((cell, clickState) in emptyWords) clickState.restore(cell)
        emptyWords.clear()
        for ((view, mode) in overscroll) {
            if (view.overScrollMode == View.OVER_SCROLL_NEVER) view.overScrollMode = mode
        }
        overscroll.clear()
        panel = null
        bindWords(null)
        deleteKey = null
    }

    private fun ownsList(view: Any?) = view === wordList || view === syllables?.list

    private fun dispatch(view: Any?, event: MotionEvent): Boolean {
        syllables?.let {
            if (view === it.list) return it.dispatch(event)
            if (it.dispatchPage(view, event)) return true
        }
        strokes?.let {
            if (view === it.view) return it.dispatch(event)
            if (it.dispatchToggle(view, event)) return true
        }
        return false
    }

    private data class AppliedBackground(val original: Drawable?, val replacement: Drawable?)

    private data class WordClickState(val clickable: Boolean, val longClickable: Boolean) {
        fun restore(cell: View) {
            cell.isClickable = clickable
            cell.isLongClickable = longClickable
        }
    }

    companion object {
        private const val S6_CLASS = "com.tencent.wetype.plugin.hld.keyboard.S6AlternativeWordsKeyboard"
        private const val CONTAINER = 0x7f09049b
        private const val PINYIN = 0x7f09049c
        private const val WORD_GRID = 0x7f09049d
        private const val LIST = 0x7f0904af
        internal const val DELETE = 0x7f0900d9
        private const val VERTICAL_DIVIDER = 0x7f090627
        private const val WORD_ITEM = 0x7f090072
        private const val WORD_LABEL = 0x7f09049e
        internal const val PINYIN_ITEM = 0x7f090073
        private const val STROKE_FILTER = 0x7f090545
        private var host: CandidateLists? = null

        @JvmStatic
        @Throws(ReflectiveOperationException::class)
        fun install(module: WeTypeModule, loader: ClassLoader) {
            val lists = CandidateLists(loader)
            val state = Class.forName("${lists.recycler.name}\$z", false, loader)
            val wordDivider = Class.forName("com.tencent.wetype.plugin.hld.view.f", false, loader)
            val syllableDivider = Class.forName("z7.b", false, loader)
            val strokeState = Class.forName("com.tencent.wetype.plugin.hld.alternative.strokefilter.c", false, loader)
            val updateStroke = DrawHooks.method(Class.forName(S6_CLASS, false, loader), "A", Void.TYPE,
                strokeState, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            // The word divider draws over the cells and the syllable divider draws under them.
            // Keep both decorations' native measurement and skip only their painting.
            val wordPaint = DrawHooks.method(wordDivider, "l", Void.TYPE, Canvas::class.java, lists.recycler, state)
            val syllablePaint = DrawHooks.method(syllableDivider, "i", Void.TYPE, Canvas::class.java, lists.recycler, state)
            for (paint in listOf(wordPaint, syllablePaint)) {
                module.intercept(paint) { chain ->
                    if (module.style()?.layers()?.expandedCandidates()?.ownsList(chain.getArg(1)) == true) null
                    else chain.proceed()
                }
            }
            for (owner in listOf(View::class.java, ViewGroup::class.java)) {
                val touch = DrawHooks.method(owner, "dispatchTouchEvent", Boolean::class.javaPrimitiveType,
                    MotionEvent::class.java)
                // The three controls own their press lifecycle before generic button observation sees DOWN.
                module.hook(touch).setPriority(XposedInterface.PRIORITY_DEFAULT + 1)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                        val panel = module.style()?.layers()?.expandedCandidates()
                        if (panel?.dispatch(chain.thisObject, chain.getArg(0) as MotionEvent) == true) true
                        else chain.proceed()
                    }
            }
            module.intercept(updateStroke) { chain ->
                val result = chain.proceed()
                module.style()?.layers()?.expandedCandidates()?.syncWordPadding(chain.thisObject)
                result
            }
            host = lists
        }
    }
}

/** The two S6 lists are the host's RecyclerViews. */
internal class CandidateLists(loader: ClassLoader) {
    val recycler = Class.forName("androidx.recyclerview.widget.RecyclerView", false, loader)
    private val adapter = Class.forName("${recycler.name}\$h", false, loader)
    private val manager = Class.forName("${recycler.name}\$LayoutManager", false, loader)
    private val getAdapter = DrawHooks.method(recycler, "getAdapter", adapter)
    private val getCount = DrawHooks.method(adapter, "getItemCount", Int::class.javaPrimitiveType)
    private val getLayout = DrawHooks.method(recycler, "getLayoutManager", manager)
    private val decoratedHeight = DrawHooks.method(manager, "getDecoratedMeasuredHeight", Int::class.javaPrimitiveType,
        View::class.java)
    private val invalidateGlows = DrawHooks.method(recycler, "invalidateGlows", Void.TYPE)
    private val scrollTo = DrawHooks.method(recycler, "scrollToPosition", Void.TYPE, Int::class.javaPrimitiveType)
    private val stopScroll = DrawHooks.method(recycler, "stopScroll", Void.TYPE)
    private val pending = DrawHooks.method(recycler, "hasPendingAdapterUpdates", Boolean::class.javaPrimitiveType)
    private val holder = Class.forName("${recycler.name}\$ViewHolder", false, loader)
    private val wordHolder = Class.forName("com.tencent.wetype.plugin.hld.alternative.r", false, loader)
    private val holderFor = DrawHooks.method(recycler, "getChildViewHolder", holder, View::class.java)
    // 59201's holder field q is its bound candidate, and candidate.p0() returns isFake.
    private val word = wordHolder.getDeclaredField("q").apply { isAccessible = true }
    private val fake = DrawHooks.method(word.type, "p0", Boolean::class.javaPrimitiveType)

    fun count(list: ViewGroup) = getAdapter.invoke(list)?.let { getCount.invoke(it) as Int }
    fun stride(list: ViewGroup, row: View) = getLayout.invoke(list)?.let { decoratedHeight.invoke(it, row) as Int } ?: 0
    fun clearEdges(list: ViewGroup) { invalidateGlows.invoke(list) }
    fun firstPage(list: ViewGroup) { scrollTo.invoke(list, 0) }
    fun stop(list: ViewGroup) { stopScroll.invoke(list) }
    fun updating(list: ViewGroup) = pending.invoke(list) == true

    fun isPlaceholder(list: ViewGroup, cell: View): Boolean {
        val boundHolder = holderFor.invoke(list, cell)
        if (!wordHolder.isInstance(boundHolder)) return false
        return word.get(boundHolder)?.let { fake.invoke(it) == true } ?: false
    }
}

/** Captures a slide from a real button and commits its native click once on release. */
internal class CandidateSlide(
    private val owner: View,
    private val hit: (Float, Float) -> View?,
    private val preview: (View?, Float?) -> Unit,
) {
    private var captured = false
    private var cancelled = false

    fun dispatch(event: MotionEvent): Boolean = dispatch(event, hit)

    fun dispatch(event: MotionEvent, continuedHit: (Float, Float) -> View?): Boolean {
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            if (captured) finish()
            val button = hit(event.x, event.y) ?: return false
            captured = true
            cancelled = false
            owner.parent?.requestDisallowInterceptTouchEvent(true)
            preview(button, event.x)
            return true
        }
        if (!captured) return false
        if (event.pointerCount != 1 || event.flags and MotionEvent.FLAG_CANCELED != 0) cancel()
        when (action) {
            MotionEvent.ACTION_MOVE -> if (!cancelled) preview(continuedHit(event.x, event.y), event.x)
            MotionEvent.ACTION_UP -> {
                val button = if (cancelled) null else continuedHit(event.x, event.y)
                finish()
                button?.performClick()
            }
            MotionEvent.ACTION_CANCEL -> finish()
        }
        return true
    }

    fun cancel() {
        if (!captured || cancelled) return
        cancelled = true
        preview(null, null)
    }

    fun finish() {
        if (!captured) return
        captured = false
        preview(null, null)
        owner.parent?.requestDisallowInterceptTouchEvent(false)
    }
}

/** A drag moves the key but disqualifies the native click, even after returning to its bounds. */
internal class CandidateKeyTouch(private val style: KeyboardStyle, private val key: View) {
    private val slop = ViewConfiguration.get(key.context).scaledTouchSlop
    private var tracking = false
    private var tap = false
    private var downX = 0f
    private var downY = 0f

    fun dispatch(event: MotionEvent): Boolean {
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            if (!key.isEnabled || !key.isClickable) return false
            tracking = true
            tap = true
            downX = event.x
            downY = event.y
            key.isPressed = true
            key.parent?.requestDisallowInterceptTouchEvent(true)
        } else if (!tracking) return false

        style.motion().trackCandidate(key, event)
        if (event.pointerCount != 1 || event.flags and MotionEvent.FLAG_CANCELED != 0) tap = false
        if (tap && (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP)) {
            tap = !beyondSlop(event.x, event.y) && (0 until event.historySize).none {
                beyondSlop(event.getHistoricalX(it), event.getHistoricalY(it))
            }
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            val click = action == MotionEvent.ACTION_UP && tap && key.isShown && key.isEnabled && key.isClickable
            finish()
            if (click) key.performClick()
        }
        return true
    }

    fun close() {
        finish()
        style.motion().cancelUnder(key)
    }

    private fun finish() {
        if (!tracking) return
        tracking = false
        key.isPressed = false
        key.parent?.requestDisallowInterceptTouchEvent(false)
    }

    private fun beyondSlop(x: Float, y: Float): Boolean {
        val dx = x - downX
        val dy = y - downY
        return dx * dx + dy * dy > slop * slop
    }
}

internal fun IconButtonMotion.trackCandidate(view: View, event: MotionEvent) {
    if (event.pointerCount != 1 || event.flags and MotionEvent.FLAG_CANCELED != 0) {
        sample(view)?.takeIf { it.pointerId >= 0 }?.release(null)
    } else if (event.actionMasked == MotionEvent.ACTION_DOWN) {
        val bounds = ButtonBackground.find(view.background)?.bounds?.takeUnless { it.isEmpty }
            ?: Rect(0, 0, view.width, view.height)
        down(view, view, bounds, event, 0)
    } else {
        update(view, event)
    }
}

internal abstract class CandidateDrawable : Drawable() {
    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(filter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Android")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

internal fun View.dp(value: Int) = (resources.displayMetrics.density * value + .5f).toInt()
