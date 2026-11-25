package local.wetype.couiglass

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import io.github.libxposed.api.XposedInterface
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Draws COUI behind WeType's existing tabs and forwards committed drags as native clicks. */
internal class SegmentedTabs(private val style: KeyboardStyle, tabs: ViewGroup) : Drawable() {
    private val owner = WeakReference(tabs)
    private val inset = 3f * tabs.resources.displayMetrics.density
    private val track = RectF()
    private val motionBounds = Rect()
    private val segment = CouiSegmentButton(tabs, ::invalidateSelf)
    private var drawableAlpha = 255
    val outlineRadius get() = segment.outerRadius

    override fun draw(canvas: Canvas) {
        val group = owner.get() ?: return
        val tabs = visibleTabs(group)
        update(group, tabs)
        segment.draw(canvas, style, drawableAlpha)
    }

    fun touchBounds(): Rect {
        val group = owner.get() ?: return Rect()
        layoutTrack(group, visibleTabs(group))
        return Rect().also(track::roundOut)
    }

    private fun visibleTabs(group: ViewGroup): List<View> = buildList {
        for (index in 0 until group.childCount) {
            val child = group.getChildAt(index)
            if (child.visibility == View.VISIBLE && child.width > 0 && child.height > 0) add(child)
        }
    }.sortedBy { it.left }

    private fun layoutTrack(group: ViewGroup, tabs: List<View>) {
        track.setEmpty()
        if (tabs.isEmpty()) return
        val left = tabs.first().left.toFloat()
        val right = tabs.last().right.toFloat()
        val top = tabs.minOf { it.top }.toFloat()
        val bottom = tabs.maxOf { it.bottom }.toFloat()
        val horizontal = minOf(inset, left, group.width - right)
        val vertical = minOf(inset, top, group.height - bottom)
        track.set(left - horizontal, top - vertical, right + horizontal, bottom + vertical)
    }

    private fun update(group: ViewGroup, tabs: List<View>) {
        layoutTrack(group, tabs)
        val slots = if (track.isEmpty) emptyList() else tabs.mapIndexedNotNull { index, tab ->
            val left = if (index == 0) track.left
                else (tabs[index - 1].right + tab.left) / 2f
            val right = if (index == tabs.lastIndex) track.right
                else (tab.right + tabs[index + 1].left) / 2f
            val center = (tab.left + tab.right) / 2f
            val bounds = CouiSegmentButton.slotBounds(track, left, right, inset, center) ?: return@mapIndexedNotNull null
            CouiSegmentButton.Slot(tab.id, bounds, tab.isEnabled && tab.isClickable)
        }
        segment.update(track, slots, (tabs.firstOrNull { it.isSelected } ?: tabs.firstOrNull())?.id ?: -1,
            group.isEnabled && group.isShown && !track.isEmpty)
    }

    fun dispatch(chain: XposedInterface.Chain, event: MotionEvent): Any? {
        val group = owner.get() ?: return chain.proceed()
        val down = event.actionMasked == MotionEvent.ACTION_DOWN
        val motion = style.motion()
        motion.update(group, event)
        val nativeResult = if (down) {
            if (segment.isDragging) group.parent?.requestDisallowInterceptTouchEvent(false)
            val result = chain.proceed()
            if (result != true) {
                segment.cancel()
                return result
            }
            result
        } else null
        val tabs = visibleTabs(group)
        update(group, tabs)
        val touch = segment.onTouch(event, hitId = { x, y ->
            if (!track.contains(x, y)) null
            else tabs.lastOrNull { x >= it.x && x < it.x + it.width && y >= it.y && y < it.y + it.height }?.id
        })
        if (down) {
            if (style.draws(group) && track.contains(event.x, event.y)) {
                track.roundOut(motionBounds)
                motion.down(group, group, motionBounds, event, event.actionIndex)
            }
            return nativeResult
        }
        if (touch.startedDrag) {
            val cancel = MotionEvent.obtain(event)
            try {
                cancel.action = MotionEvent.ACTION_CANCEL
                chain.proceed(arrayOf(cancel))
            } finally {
                cancel.recycle()
            }
            group.parent?.requestDisallowInterceptTouchEvent(true)
        }
        if (touch.consumed) {
            if (!segment.isDragging) {
                group.parent?.requestDisallowInterceptTouchEvent(false)
                tabs.firstOrNull { it.id == touch.commitId && it.isEnabled && it.isClickable }?.performClick()
                update(group, visibleTabs(group))
            }
            return true
        }
        return chain.proceed()
    }

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        if (!visible) {
            owner.get()?.let { group ->
                if (segment.isDragging) group.parent?.requestDisallowInterceptTouchEvent(false)
                style.motion().cancelUnder(group)
            }
            segment.cancel()
        }
        return super.setVisible(visible, restart)
    }

    override fun setAlpha(alpha: Int) { drawableAlpha = alpha; invalidateSelf() }
    override fun getAlpha() = drawableAlpha
    override fun setColorFilter(filter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Android")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        @JvmStatic
        @Throws(ReflectiveOperationException::class)
        fun install(module: WeTypeModule, loader: ClassLoader) {
            val host = Class.forName("com.tencent.wetype.plugin.hld.candidate.TabContainerView", false, loader)
            module.intercept(DrawHooks.method(host, "onDraw", Void.TYPE, Canvas::class.java)) { chain ->
                if ((chain.thisObject as View).background is SegmentedTabs) null else chain.proceed()
            }
            module.intercept(DrawHooks.method(host, "k", Void.TYPE,
                Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)) { chain ->
                if ((chain.thisObject as View).background is SegmentedTabs) {
                    chain.proceed(arrayOf(chain.getArg(0), false, chain.getArg(2)))
                } else chain.proceed()
            }
            val candidateClass = Class.forName("com.tencent.wetype.plugin.hld.candidate.ImeCandidateView", false, loader)
            val dispatch = DrawHooks.method(candidateClass, "dispatchTouchEvent", Boolean::class.javaPrimitiveType,
                MotionEvent::class.java)
            val activeTabs = WeakHashMap<View, WeakReference<ViewGroup>>()
            val from = IntArray(2)
            val to = IntArray(2)
            module.intercept(dispatch) { chain ->
                val candidate = chain.thisObject as View
                val event = chain.getArg(0) as MotionEvent
                val action = event.actionMasked
                val start = action == MotionEvent.ACTION_DOWN
                if (start) {
                    activeTabs.remove(candidate)?.get()?.let { previous ->
                        forwardToTabs(candidate, previous, event, from, to, MotionEvent.ACTION_CANCEL)
                        candidate.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                }
                val tabs = if (start) candidate.findViewById<ViewGroup>(KeyboardStyle.CANDIDATE_TABS)
                    else activeTabs[candidate]?.get()
                val background = tabs?.background as? SegmentedTabs
                val style = module.style()
                if (tabs == null || start && (style == null || !style.draws(candidate) ||
                        background == null || !style.visible(tabs))) return@intercept chain.proceed()

                // ImeCandidateView routes horizontal movement to handwriting before dispatching to children.
                // Keep a sequence that started on these tabs with the native tabs until UP or CANCEL.
                val handled = forwardToTabs(candidate, tabs, event, from, to, checkBounds = start)
                if (start) {
                    if (!handled) return@intercept chain.proceed()
                    activeTabs[candidate] = WeakReference(tabs)
                    candidate.parent?.requestDisallowInterceptTouchEvent(true)
                } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    activeTabs.remove(candidate)
                    candidate.parent?.requestDisallowInterceptTouchEvent(false)
                }
                true
            }
        }

        private fun forwardToTabs(candidate: View, tabs: ViewGroup, event: MotionEvent, from: IntArray, to: IntArray,
            action: Int = event.action, checkBounds: Boolean = false,
        ): Boolean {
            candidate.getLocationInWindow(from)
            tabs.getLocationInWindow(to)
            val local = MotionEvent.obtain(event)
            try {
                local.offsetLocation((from[0] - to[0]).toFloat(), (from[1] - to[1]).toFloat())
                if (checkBounds) {
                    val bounds = (tabs.background as? SegmentedTabs)?.touchBounds() ?: return false
                    if (local.x < bounds.left || local.x >= bounds.right || local.y < bounds.top || local.y >= bounds.bottom) return false
                }
                local.action = action
                return tabs.dispatchTouchEvent(local)
            } finally {
                local.recycle()
            }
        }
    }
}
