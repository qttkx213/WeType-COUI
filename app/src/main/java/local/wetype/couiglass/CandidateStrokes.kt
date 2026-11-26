package local.wetype.couiglass

import android.graphics.Canvas
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.TextView
import kotlin.math.abs

/** The stock filter owns visibility, selection, glyphs and click handling. */
internal class CandidateStrokes(private val style: KeyboardStyle, val view: ViewGroup) {
    val toggle: TextView? = view.findViewById(0x7f0905f7)
    private val toggleTouch = toggle?.let { CandidateKeyTouch(style, it) }
    val options: ViewGroup? = view.findViewById(0x7f090325)
    private val buttons = (0x7f0905f2..0x7f0905f6).mapNotNull { id -> options?.findViewById<TextView>(id) }
    val stockBackgrounds = buttons + (0x7f09053e..0x7f090544).mapNotNull { id -> view.findViewById<View>(id) }
    private val inset = view.dp(2).toFloat()
    private val slop = ViewConfiguration.get(view.context).scaledTouchSlop
    private val strokeBounds = RectF()
    private val track = RectF()
    private val outline = Path()
    private val nativeBounds = Rect()
    private val sourceKey = Rect()
    private val location = IntArray(2)
    private var slots = emptyList<Slot>()
    private var hovered: View? = null
    private var downX = 0f
    private var downY = 0f
    private var horizontalLocked = false
    private var nativeTitle: CharSequence? = null
    private var plainTitle: CharSequence? = null

    var popup: SymbolPopup? = null
        private set

    private val segment = CouiSegmentButton(view, ::invalidateFace)
    private val slide = CandidateSlide(view, ::hitButton) { target, x ->
        val changed = hovered !== target
        hovered = target
        // Let the native click update selection before the next pre-draw reads it.
        if (changed && target != null) updateSegment()
        segment.observeNativeTouch(if (target == null || x == null) null else x - offsetX())
    }

    val face: CandidateDrawable = object : CandidateDrawable() {
        override fun draw(canvas: Canvas) = segment.draw(canvas, style)
    }

    fun sync(root: View) {
        toggle?.let { header ->
            val title = header.resources.getText(0x7f110021)
            if (!TextUtils.equals(header.text, title)) {
                nativeTitle = header.text
                header.text = title
            }
            plainTitle = title
        }
        val row = options ?: return
        if (!row.isShown) {
            val active = popup
            active?.close {
                if (popup === active) popup = null
                active.dispose()
            }
            slide.cancel()
            return
        }
        val updated = buttons.mapNotNull { button ->
            if (button.width <= 0 || button.height <= 0) return@mapNotNull null
            nativeBounds.set(0, 0, button.width, button.height)
            row.offsetDescendantRectToMyCoords(button, nativeBounds)
            Slot(button, RectF(nativeBounds))
        }.sortedBy { it.bounds.left }
        // The stock TextViews keep their identities across candidate reloads. Keep the last
        // measured hit geometry until the whole row has been laid out again.
        val geometryChanged = updated.size == 5 && updated != slots
        if (geometryChanged) {
            slots = updated
            strokeBounds.setEmpty()
            for (slot in slots) strokeBounds.union(slot.bounds)
            track.set(strokeBounds)
            track.inset(inset, inset)
        }
        updateSegment()
        if (track.isEmpty) return
        if (geometryChanged) {
            outline.set(style.material().outline(track.width(), track.height(), segment.outerRadius))
            outline.offset(track.left, track.top)
            invalidateFace()
        }
        val anchor = toggle ?: row
        anchor.getLocationInWindow(location)
        sourceKey.set(location[0], location[1], location[0] + anchor.width, location[1] + anchor.height)
        if (popup == null || popup?.closing == true) {
            popup?.dispose()
            popup = SymbolPopup(root, row, sourceKey, { outline.takeUnless { track.isEmpty } }).also { it.open() }
        }
    }

    fun dispatch(event: MotionEvent): Boolean {
        val action = event.actionMasked
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                horizontalLocked = false
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                if (!horizontalLocked) {
                    val horizontal = abs(event.x - downX)
                    val vertical = abs(event.y - downY)
                    horizontalLocked = horizontal > slop && vertical <= 1.5f * horizontal
                }
                if (!insideRow(event.x, event.y, horizontalLocked)) slide.cancel()
            }
        }
        val active = popup
        val handled = slide.dispatch(event, if (action == MotionEvent.ACTION_UP) ::releaseAt else ::slideAt)
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) horizontalLocked = false
        if (!handled) return false
        if (hovered != null) active?.observeTouch(view, event, 0)
        else active?.releaseTouch(event.takeIf { action == MotionEvent.ACTION_UP })
        return true
    }

    fun dispatchToggle(target: Any?, event: MotionEvent) = target === toggle && toggleTouch?.dispatch(event) == true

    fun close() {
        toggleTouch?.close()
        popup?.dispose()
        popup = null
        slide.finish()
        segment.cancel()
        toggle?.let { header ->
            if (TextUtils.equals(header.text, plainTitle)) nativeTitle?.let { header.text = it }
        }
    }

    private fun invalidateFace() {
        face.invalidateSelf()
        popup?.invalidate()
    }

    private fun offsetX() = (options?.x ?: 0f) - view.scrollX
    private fun offsetY() = (options?.y ?: 0f) - view.scrollY

    private fun insideRow(x: Float, y: Float, allowDownwardDrift: Boolean = false): Boolean {
        val row = options ?: return false
        if (!view.isEnabled || !row.isEnabled || !row.isShown) return false
        val localX = x - offsetX()
        val localY = y - offsetY()
        return localX >= strokeBounds.left && localX < strokeBounds.right &&
            localY >= strokeBounds.top - slop && (allowDownwardDrift || localY < strokeBounds.bottom + slop)
    }

    private fun hitButton(x: Float, y: Float): View? {
        if (!insideRow(x, y)) return null
        val localX = x - offsetX()
        val localY = y - offsetY()
        return slots.firstOrNull { it.bounds.contains(localX, localY) && it.button.canClick() }?.button
    }

    private fun slideAt(x: Float, y: Float): View? {
        if (!insideRow(x, y, horizontalLocked)) return null
        val localX = x - offsetX()
        // Give each side of a native divider to its adjacent stroke during a captured slide.
        val index = slots.indices.firstOrNull { it == slots.lastIndex || localX < divider(it) } ?: return null
        return slots[index].button
    }

    private fun releaseAt(x: Float, y: Float) = slideAt(x, y)?.takeIf { it.canClick() }

    private fun View.canClick() = isShown && isEnabled && isClickable

    private fun divider(left: Int) = (slots[left].bounds.right + slots[left + 1].bounds.left) / 2f

    private fun updateSegment() {
        val selected = hovered ?: slots.firstOrNull { it.button.isSelected }?.button
        val entries = slots.mapIndexedNotNull { index, slot ->
            val left = if (index == 0) track.left else divider(index - 1)
            val right = if (index == slots.lastIndex) track.right else divider(index)
            val bounds = CouiSegmentButton.slotBounds(track, left, right, inset, slot.bounds.centerX())
                ?: return@mapIndexedNotNull null
            CouiSegmentButton.Slot(slot.button.id, bounds, slot.button.isEnabled)
        }
        segment.update(track, entries, selected?.id ?: View.NO_ID, view.isEnabled && !track.isEmpty)
    }

    private data class Slot(val button: View, val bounds: RectF)
}
