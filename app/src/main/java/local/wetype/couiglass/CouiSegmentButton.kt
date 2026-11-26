// SegmentButton and TabRowWithContour motion/gesture parameters from suqi8/coui.
// Copyright 2026, compose-coui-ui contributors
// SPDX-License-Identifier: Apache-2.0
package local.wetype.couiglass

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.PathInterpolator
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.ColorUtils
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.itos.coui.effect.CouiContour
import com.itos.coui.effect.CouiContourStyle
import java.lang.ref.WeakReference
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/** COUI SegmentButton motion and material in the native host's layout coordinates. */
internal class CouiSegmentButton(host: View, private val invalidate: () -> Unit) {
    /** Resting indicator rect. Pass slots in visual X order, with stable native IDs. */
    data class Slot(val id: Int, val bounds: RectF, val enabled: Boolean = true)
    data class Touch(val consumed: Boolean = false, val startedDrag: Boolean = false, val commitId: Int? = null)

    private val owner = WeakReference(host)
    private val density = host.resources.displayMetrics.density
    private val slop = ViewConfiguration.get(host.context).scaledTouchSlop
    private val switchDistance = 5f * density
    private val track = RectF()
    private var slots = emptyList<Slot>()
    var outerRadius = 0f
        private set
    private var enabled = true
    private var selectedId = -1
    private var targetId = -1
    private val targetBounds = RectF()
    private val indicator = RectF()
    private val trackSurface = Surface(density, false)
    private val indicatorSurface = Surface(density, true)
    private var widthLimit = 0f
    private val position = Axis(0f, .75f, invalidate)
    private val width = Axis(0f, .75f, invalidate)
    private val height = Axis(0f, .75f, invalidate)
    private val rubber = Axis(0f, .5f, invalidate)
    private val press = Axis(1f, .001f, invalidate)
    private var candyPress = false
    private var rubberOrigin: Float? = null
    private var rubberAnchor = 0f
    private var downX = 0f
    private var downY = 0f
    private var fingerX = 0f
    private var pointer = -1
    private var pressedId = -1
    private var previewId = -1
    private var canGrab = false
    private var nativePressed = false
    private var scrollable = false
    private var scrollOffset = 0f
    private var viewportDrag = false

    // Once the host received CANCEL, retain ownership until the stream ends.
    var isDragging = false
        private set
    val isTrackingDrag get() = isDragging && pointer >= 0

    fun update(track: RectF, slots: List<Slot>, selectedId: Int, enabled: Boolean = true,
        scrollable: Boolean = false,
    ) {
        val targetBefore = targetId
        val wasNativeEnabled = nativePressed && this.enabled && slot(this.selectedId)?.enabled == true
        this.track.set(track)
        this.slots = slots
        outerRadius = if (scrollable) minOf(track.width(), track.height()) / 2f else trackRadius(track, slots)
        this.selectedId = selectedId
        this.enabled = enabled
        this.scrollable = scrollable
        // A scrolling host can recycle the pressed view while the drag is still active.
        val keepDrag = scrollable && isDragging
        if (pointer >= 0 && (!enabled || !keepDrag && (slot(pressedId)?.enabled != true ||
                isDragging && slot(previewId)?.enabled != true))) {
            release()
        }
        if (!viewportDrag) select(slot(if (isTrackingDrag) previewId else selectedId))
        if (scrollable && isTrackingDrag) followDrag(dragPosition(fingerX))
        if (nativePressed) {
            val nativeEnabled = enabled && slot(selectedId)?.enabled == true
            if (targetId != targetBefore || wasNativeEnabled != nativeEnabled) updateNativePress()
        }
    }

    /** During edge scrolling, the indicator follows the pointer in viewport coordinates until release. */
    fun anchorDragToViewport() {
        if (!scrollable || !isTrackingDrag || viewportDrag || !indicatorRect(ValueAnimator.areAnimatorsEnabled())) return
        viewportDrag = true
        position.snapTo(indicator.centerX())
        scrollOffset = 0f
        rubberOrigin = null
        rubber.snapTo(0f)
        followDrag(dragPosition(fingerX))
    }

    fun draw(canvas: Canvas, style: KeyboardStyle, alpha: Int = 255, indicatorColor: Int? = null) {
        drawTrack(canvas, style, alpha)
        drawIndicator(canvas, style, alpha, indicatorColor)
    }

    fun drawTrack(canvas: Canvas, style: KeyboardStyle, alpha: Int) {
        if (alpha <= 0 || track.isEmpty) return
        val dark = style.dark()
        val outline = style.material().outline(track.width(), track.height(), outerRadius)
        val saved = canvas.save()
        try {
            canvas.translate(track.left, track.top)
            trackSurface.draw(canvas, outline, track.width(), track.height(), outerRadius,
                CouiPalette.topBarFill(dark, 255), alpha, dark)
        } finally {
            canvas.restoreToCount(saved)
        }
    }

    fun clipTrack(canvas: Canvas, style: KeyboardStyle) {
        canvas.translate(track.left, track.top)
        canvas.clipPath(style.material().outline(track.width(), track.height(), outerRadius))
        canvas.translate(-track.left, -track.top)
    }

    fun drawIndicator(canvas: Canvas, style: KeyboardStyle, alpha: Int, indicatorColor: Int? = null) {
        if (alpha <= 0 || !indicatorRect(ValueAnimator.areAnimatorsEnabled())) return
        val dark = style.dark()
        val material = style.material()
        val fill = CouiPalette.topBarFill(dark, 255)
        val saved = canvas.save()
        try {
            canvas.translate(track.left, track.top)

            if (scrollable || scrollsThroughEnds()) {
                // Clip inside the same track rim even when its end cells change.
                val gap = scrollInset()
                canvas.translate(gap, gap)
                canvas.clipPath(material.outline(track.width() - 2f * gap, track.height() - 2f * gap,
                    (outerRadius - gap).coerceAtLeast(0f)))
                canvas.translate(-gap, -gap)
            } else {
                canvas.clipPath(material.outline(track.width(), track.height(), outerRadius))
            }

            val width = indicator.width()
            val height = indicator.height()
            val radius = (outerRadius - inset(track, height)).coerceIn(0f, minOf(width, height) / 2f)
            val color = indicatorColor ?: ColorUtils.compositeColors(CouiPalette.pressMask(dark), fill)
            canvas.translate(indicator.left - track.left, indicator.top - track.top)
            indicatorSurface.draw(canvas, material.outline(width, height, radius), width, height, radius, color, alpha, dark)
        } finally {
            canvas.restoreToCount(saved)
        }
    }

    fun onTouch(event: MotionEvent, hitId: (Float, Float) -> Int?, canDrag: (Int) -> Boolean = { true }): Touch {
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            cancel()
            if (!enabled) return pass
            val selected = slot(selectedId)?.takeIf { it.enabled }
            val nearIndicator = selected != null && track.contains(event.x, event.y) && inIndicator(event.x, event.y)
            val hitIdAtDown = hitId(event.x, event.y)
            val hit = if (hitIdAtDown != null) slot(hitIdAtDown) else selected?.takeIf { nearIndicator }
            if (hit == null || !hit.enabled) return pass
            pointer = event.getPointerId(0)
            pressedId = hit.id
            downX = event.x
            downY = event.y
            fingerX = downX
            canGrab = selected != null && (hit.id == selectedId || nearIndicator)
            if (canGrab) {
                rubberAnchor = visibleCenter()
                rubberOrigin = downX
            }
            pressIndicator(if (canGrab) selected else null)
            invalidate()
            return pass
        }
        if (pointer < 0 && !isDragging) return pass
        val index = event.findPointerIndex(pointer)
        val cancelled = index < 0 || !enabled || event.pointerCount != 1 ||
            (event.flags and MotionEvent.FLAG_CANCELED) != 0
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            val captured = isDragging
            val result = if (captured && action == MotionEvent.ACTION_UP && !cancelled) {
                releaseDrag(event.getX(index))?.takeIf { it.id != selectedId }
            } else null
            isDragging = false
            release(result ?: slot(selectedId))
            return if (captured) Touch(consumed = true, commitId = result?.id) else pass
        }
        if (cancelled) {
            release()
            return if (isDragging) consumed else pass
        }
        if (action != MotionEvent.ACTION_MOVE) return if (isDragging) consumed else pass

        fingerX = event.getX(index)
        val fingerY = event.getY(index)
        val horizontal = abs(fingerX - downX)
        val vertical = abs(fingerY - downY)
        if (!isDragging && vertical > slop && vertical > 1.5f * horizontal) {
            release()
            return pass
        }
        val started = !isDragging && canGrab && horizontal > slop && vertical <= 1.5f * horizontal &&
            slots.count { it.enabled } > 1 && canDrag(pressedId)
        if (started) {
            isDragging = true
            previewId = selectedId
        }
        if (isDragging) {
            followDrag(dragPosition(fingerX))
        } else if (hitId(fingerX, fingerY) != pressedId) {
            pressIndicator(null)
        } else if (canGrab) {
            slot(selectedId)?.let { followRubber(it, fingerX) }
        }
        invalidate()
        return if (started) dragStarted else if (isDragging) consumed else pass
    }

    private fun dragPosition(x: Float): Float {
        if (!scrollable || track.isEmpty) return x
        // Sample inside the viewport when the finger is held over a fading edge.
        val gap = minOf(3f * switchDistance, track.width() / 4f)
        return x.coerceIn(track.left + gap, track.right - gap)
    }

    private fun releaseDrag(x: Float): Slot? {
        val inside = dragPosition(x)
        if (inside != x) {
            // Keep the last preview while a new slot is still crossing the edge's switch threshold.
            val preview = dragSlot(inside) ?: slot(previewId)?.takeIf { it.enabled }
            if (preview != null) return preview
        }
        return releaseSlot(inside)
    }

    private fun followDrag(x: Float) {
        if (viewportDrag) {
            val margin = maxOf(targetBounds.width() / 2f + scrollInset(), outerRadius)
            val reach = (track.width() / 2f - margin).coerceAtLeast(0f)
            position.springTo(x.coerceIn(track.centerX() - reach, track.centerX() + reach), .25f)
        }
        val next = dragSlot(x) ?: if (scrollable && slot(previewId) == null) releaseSlot(x) else null
        if (next != null && next.id != previewId) {
            previewId = next.id
            if (!viewportDrag) {
                select(next)
                pressIndicator(next)
            }
            owner.get()?.let { view ->
                if (!view.performHapticFeedback(COUI_SWITCH_HAPTIC)) {
                    view.performHapticFeedback(HapticFeedbackConstants.TEXT_HANDLE_MOVE)
                }
            }
        } else if (!viewportDrag) {
            slot(previewId)?.let { followRubber(it, x) }
        }
    }

    /** Follows a popup's native selection and pointer. The native dispatcher owns its haptic and commit. */
    fun observeNativeTouch(x: Float?) {
        if (x == null) {
            if (!nativePressed) return
            nativePressed = false
            release()
        } else {
            if (!nativePressed) {
                rubberOrigin = x
                rubberAnchor = visibleCenter()
            }
            nativePressed = true
            fingerX = x
            updateNativePress()
        }
        invalidate()
    }

    fun cancel() {
        isDragging = false
        nativePressed = false
        release()
    }

    private fun slot(id: Int): Slot? = slots.firstOrNull { it.id == id && visible(it) }

    private fun updateNativePress() {
        val active = slot(selectedId)?.takeIf { enabled && it.enabled }
        pressIndicator(active)
        if (active == null) rubber.springTo(0f, .6f)
        else followRubber(active, fingerX)
    }

    private fun release(target: Slot? = slot(selectedId)) {
        val viewportCenter = if (viewportDrag && indicatorRect(ValueAnimator.areAnimatorsEnabled())) {
            indicator.centerX()
        } else null
        viewportDrag = false
        pointer = -1
        pressedId = -1
        previewId = -1
        canGrab = false
        if (viewportCenter != null && target != null) {
            // Start the release at the drawn position, even if the preview ID stayed the same.
            targetId = target.id
            targetBounds.set(target.bounds)
            position.snapTo(viewportCenter)
            position.springTo(targetBounds.centerX(), .4f, .1f)
            widthLimit = maxOf(widthLimit, targetBounds.width())
            width.springTo(targetBounds.width(), .6f)
            height.springTo(targetBounds.height(), .6f)
        } else {
            select(target)
        }
        pressIndicator(null)
        rubber.springTo(0f, .6f)
    }

    private fun inIndicator(x: Float, y: Float): Boolean = indicatorRect(ValueAnimator.areAnimatorsEnabled()) &&
        x >= indicator.left - 10f && x <= indicator.right + 10f &&
        y >= indicator.top - 10f && y <= indicator.bottom + 10f

    private fun dragSlot(x: Float): Slot? {
        if (x < track.left || x >= track.right) return null
        return slots.firstOrNull {
            val inset = minOf(switchDistance, it.bounds.width() / 4f)
            it.enabled && x >= it.bounds.left + inset && x < it.bounds.right - inset
        }
    }

    private fun releaseSlot(x: Float): Slot? {
        // After horizontal lock, clipboard swipes can finish below the row or past either end.
        val first = slots.firstOrNull { visible(it) } ?: return null
        val last = slots.last { visible(it) }
        val target = when {
            x <= first.bounds.left -> first
            x >= last.bounds.right -> last
            else -> slots.firstOrNull { x >= it.bounds.left && x < it.bounds.right } ?: slot(previewId)
        }
        return target?.takeIf { it.enabled && visible(it) }
    }

    private fun visible(slot: Slot): Boolean = !slot.bounds.isEmpty && RectF.intersects(track, slot.bounds)

    private fun pressIndicator(slot: Slot?) {
        if (slot == null) {
            press.springTo(1f, .5f, if (candyPress) .5f else .6f)
            return
        }
        val w = slot.bounds.width() / density
        val h = slot.bounds.height() / density
        val aspect = maxOf(w, h) / minOf(w, h)
        candyPress = aspect > 2f
        val area = ((w * h - 400f) / (22500f - 400f)).coerceIn(0f, 1f)
        val areaScale = .85f + .12f * pressEasing.getInterpolation(area)
        val aspectScale = .85f + .15f * pressEasing.getInterpolation(((aspect - 2f) / 13f).coerceIn(0f, 1f))
        val scale = if (candyPress) (areaScale + aspectScale) / 2f else areaScale
        press.springTo(scale, if (candyPress) .3f else .25f)
    }

    private fun select(slot: Slot?) {
        val id = slot?.id ?: -1
        if (id == targetId && (slot == null || targetBounds == slot.bounds)) return
        val wasVisible = targetId != -1
        val changed = id != targetId
        val bounds = slot?.bounds
        if (scrollable && !changed && bounds != null && bounds.top == targetBounds.top &&
            bounds.bottom == targetBounds.bottom && abs(bounds.width() - targetBounds.width()) < .01f) {
            // Move the running spring with its RecyclerView slot instead of restarting it on every scroll frame.
            val dx = bounds.centerX() - targetBounds.centerX()
            scrollOffset += dx
            rubberAnchor += dx
            targetBounds.set(bounds)
            return
        }
        val previousCenter = visibleCenter()
        val previousWidth = targetBounds.width()
        targetId = id
        scrollOffset = 0f
        rubberOrigin = null
        rubber.snapTo(0f)
        if (slot == null) {
            targetBounds.setEmpty()
            position.snapTo(0f)
            width.snapTo(0f)
            height.snapTo(0f)
            return
        }
        val b = slot.bounds
        targetBounds.set(b)
        val compact = track.height() < 36f * density
        val overshoot = (if (compact) 5f else 7f) * density
        val index = slots.indexOf(slot)
        val first = (index - 1).coerceIn(0, maxOf(0, slots.size - 3))
        val last = minOf(first + 2, slots.lastIndex)
        val span = slots[last].bounds.right - slots[first].bounds.left
        widthLimit = maxOf(b.width(), minOf(maxOf(previousWidth, b.width()) + overshoot, span))
        if (!wasVisible || !changed || !ValueAnimator.areAnimatorsEnabled()) {
            position.snapTo(b.centerX())
            width.snapTo(b.width())
            height.snapTo(b.height())
            return
        }

        // SegmentButton/TabRowWithContour use independent position, width and height springs.
        // A fresh outward kick on every switch makes equal-width slots grow on repeated reversals.
        if (width.value > widthLimit) width.snapTo(widthLimit)
        val growing = b.width() > previousWidth && b.width() > width.value
        val widthRatio = abs(b.width() - width.value) / maxOf(b.width(), width.value)
        val widthDifference = widthRatio * widthRatio
        val positionBounce = if (compact && growing) .1f - .07f * widthRatio else .1f
        position.snapTo(previousCenter)
        position.springTo(b.centerX(), .4f, positionBounce)
        width.springTo(b.width(), .6f - .2f * widthDifference,
            if (growing) .5f else .5f - .4f * widthDifference, if (growing) 1000f else 0f)
        height.springTo(b.height(), .6f, .5f, -500f)
    }

    private fun visibleCenter(): Float {
        val center = position.value + scrollOffset
        if (scrollable) return center + rubber.value
        val first = slots.firstOrNull() ?: return center
        val last = slots.last()
        return center.coerceIn(first.bounds.centerX(), last.bounds.centerX()) + rubber.value
    }

    private fun indicatorRect(animate: Boolean): Boolean {
        if (targetId == -1 || track.isEmpty || (slots.isEmpty() && !viewportDrag)) return false
        val scale = if (animate) press.value else 1f
        val center = if (animate || viewportDrag) visibleCenter() else targetBounds.centerX()
        val y = targetBounds.centerY()
        val w = if (animate) width.value.coerceIn(0f, widthLimit) else targetBounds.width()
        val h = if (animate) maxOf(height.value, targetBounds.height() * .9f) else targetBounds.height()
        // Press in by the same distance on every side, even on a wide tab.
        val shrink = (1f - scale) * minOf(w, h) / 2f
        val halfHeight = minOf(h / 2f - shrink, targetBounds.height() / 2f)
        if (halfHeight <= 0f) return false
        val gap = inset(track, 2f * halfHeight)
        if (scrollable) {
            // A clipped cell can be narrow. Keep the capsule round and let the fixed track clip it.
            val maxHalfWidth = track.width() / 2f - scrollInset()
            val minHalfWidth = (outerRadius - gap).coerceIn(0f, maxHalfWidth)
            val halfWidth = (w / 2f - shrink).coerceIn(minHalfWidth, maxHalfWidth)
            if (halfWidth <= 0f) return false
            indicator.set(center - halfWidth, y - halfHeight, center + halfWidth, y + halfHeight)
            return true
        }
        val first = slots.first().bounds.left
        val last = slots.last().bounds.right
        val scrolling = scrollsThroughEnds()
        val left = if (scrolling) first else maxOf(track.left + gap, first)
        val right = if (scrolling) last else minOf(track.right - gap, last)
        if (right <= left) return false
        // Keep enough width for the shared corner even when the springs overshoot at an end.
        val minHalfWidth = (outerRadius - gap).coerceIn(0f, (right - left) / 2f)
        val mid = (left + right) / 2f
        val range = (right - left) / 2f - minHalfWidth
        val x = center.coerceIn(mid - range, mid + range)
        val halfWidth = minOf(maxOf(w / 2f - shrink, minHalfWidth), widthLimit / 2f, x - left, right - x)
        if (halfWidth <= 0f) return false
        indicator.set(x - halfWidth, y - halfHeight, x + halfWidth, y + halfHeight)
        return true
    }

    private fun scrollInset() = inset(track, targetBounds.height()).coerceAtMost(track.width() / 4f)

    private fun scrollsThroughEnds() = slots.first().bounds.left < track.left || slots.last().bounds.right > track.right

    private fun followRubber(active: Slot, x: Float) {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        if (position.isRunning) {
            rubberOrigin = null
            return
        }
        val origin = rubberOrigin ?: x.also {
            rubberOrigin = it
            rubberAnchor = visibleCenter()
        }
        val delta = x - origin
        val atEdge = active === slots.firstOrNull() || active === slots.lastOrNull()
        val slack = ((1f - press.value) * width.value / 2f).coerceAtLeast(0f)
        val limit = slack + if (atEdge) 0f else switchDistance
        val offset = if (limit > 0f) (1f - 1f / (1f + abs(delta) * .12f / limit)) * limit * sign(delta) else 0f
        rubber.springTo(rubberAnchor - position.value - scrollOffset + offset, .6f)
    }

    private class Axis(initial: Float, private val threshold: Float, private val invalidate: () -> Unit) {
        var value = initial
            private set
        private var target = initial
        val isRunning get() = animation.isRunning
        private val animation = SpringAnimation(FloatValueHolder(initial)).apply {
            spring = SpringForce(initial)
            setMinimumVisibleChange(threshold)
            addUpdateListener { _, next, _ -> value = next; invalidate() }
        }

        fun snapTo(next: Float) {
            animation.cancel()
            target = next
            if (value == next) return
            value = next
            invalidate()
        }

        fun springTo(next: Float, response: Float, bounce: Float = 0f, velocity: Float = 0f) {
            if (!ValueAnimator.areAnimatorsEnabled()) {
                snapTo(next)
                return
            }
            if (target == next && velocity == 0f && (isRunning || abs(value - next) < threshold)) return
            if (velocity != 0f) animation.cancel()
            target = next
            val frequency = (2.0 * PI / response).toFloat()
            animation.spring.setStiffness(frequency * frequency).setDampingRatio(1f - bounce)
            if (!isRunning) animation.setStartValue(value).setStartVelocity(velocity)
            animation.animateToFinalPosition(next)
        }
    }

    private class Surface(private val density: Float, private val indicator: Boolean) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rim = CouiSegmentRim(density, indicator)

        private var contour: CouiContour? = null
        private var shaderFailed = false

        fun draw(canvas: Canvas, shape: Path, width: Float, height: Float, radius: Float, tint: Int, alpha: Int, dark: Boolean) {
            val color = CouiPalette.fade(tint, alpha)
            val edge = rim.opacity(dark)
            val glow = if (indicator) 0f else (if (dark) .04f else .12f)
            val shader = if (Build.VERSION.SDK_INT >= 33 && canvas.isHardwareAccelerated && !shaderFailed) {
                try {
                    val effect = contour ?: CouiContour(style = CouiContourStyle.Button).also { contour = it }
                    effect.shader(Size(width, height), density, radius, CouiCorners.WEIGHT, Color(color),
                        glow, edge, alpha / 255f, rim.lineWidth)
                } catch (_: RuntimeException) {
                    shaderFailed = true
                    null
                } catch (_: LinkageError) {
                    shaderFailed = true
                    null
                }
            } else null
            paint.shader = shader
            paint.color = if (shader == null) color else 0xFFFFFFFF.toInt()
            canvas.drawPath(shape, paint)
            if (shader != null) return

            // The popup's closing bitmap also needs the rim on a software canvas.
            rim.draw(canvas, shape, 0f, height, alpha, dark)
        }
    }

    companion object {
        private const val COUI_SWITCH_HAPTIC = 302
        private val pass = Touch()
        private val consumed = Touch(consumed = true)
        private val dragStarted = Touch(consumed = true, startedDrag = true)
        private val pressEasing = PathInterpolator(0f, 0f, 0f, 1f)

        private fun inset(track: RectF, height: Float) = ((track.height() - height) * .5f).coerceAtLeast(0f)

        /** Pick one outer radius that leaves room for its inset corner in every resting slot. */
        fun trackRadius(track: RectF, slots: List<Slot>): Float =
            slots.fold(minOf(track.width(), track.height()) / 2f) { radius, slot ->
                minOf(radius, slot.bounds.width() / 2f + inset(track, slot.bounds.height()))
            }

        /** Clips the host cell to the inset track. Supply a center to preserve native label alignment. */
        fun slotBounds(track: RectF, left: Float, right: Float, inset: Float, centerX: Float? = null,
            clipHorizontal: Boolean = true,
        ): RectF? {
            if (track.isEmpty) return null
            val gap = inset.coerceIn(0f, minOf(track.width(), track.height()) * .2f)
            val start = if (clipHorizontal) maxOf(left, track.left + gap) else left
            val end = if (clipHorizontal) minOf(right, track.right - gap) else right
            val bounds = RectF(start, track.top + gap, end, track.bottom - gap)
            if (centerX != null) {
                val halfWidth = minOf(centerX - start, end - centerX)
                bounds.left = centerX - halfWidth
                bounds.right = centerX + halfWidth
            }
            return bounds.takeUnless { it.isEmpty }
        }
    }
}

/** Draws the segment's gradient highlight inside an existing outline. */
internal class CouiSegmentRim(density: Float, private val indicator: Boolean) {
    val lineWidth = (if (indicator) 1.7f else 2f) * density
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = lineWidth
    }
    private val gradient = LinearGradient(0f, 0f, 0f, 1f,
        intArrayOf(0xBFFFFFFF.toInt(), 0x22FFFFFF, 0xE6FFFFFF.toInt()), null, Shader.TileMode.CLAMP)
    private val scale = Matrix()

    fun opacity(dark: Boolean) = if (indicator) (if (dark) .12f else .62f) else (if (dark) .60f else .50f)

    fun draw(canvas: Canvas, shape: Path, top: Float, height: Float, alpha: Int, dark: Boolean) {
        scale.setScale(1f, height)
        scale.postTranslate(0f, top)
        gradient.setLocalMatrix(scale)
        rim.shader = gradient
        rim.alpha = (alpha * opacity(dark)).roundToInt()
        val saved = canvas.save()
        canvas.clipPath(shape)
        canvas.drawPath(shape, rim)
        canvas.restoreToCount(saved)
    }
}
