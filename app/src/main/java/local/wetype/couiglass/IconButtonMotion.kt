package local.wetype.couiglass

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import androidx.compose.runtime.snapshots.ObserverHandle
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.itos.coui.motion.CouiPressMotion
import com.itos.coui.motion.CouiTouchMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import java.util.IdentityHashMap
import kotlin.math.abs

internal class IconButtonMotion(private val invalidateOverlay: (() -> Unit)? = null) {
    private val keys = IdentityHashMap<Any, Press>()
    private val main = Handler(Looper.getMainLooper())
    private var writeObserver: ObserverHandle? = null
    var root: View? = null
    private val overflowing = HashSet<ViewGroup>()
    private val unclipped = HashSet<ViewGroup>()
    private var redrawQueued = false
    private val redraw = Runnable {
        redrawQueued = false
        // There is no Compose host to deliver the spring states' snapshot notifications.
        Snapshot.sendApplyNotifications()
        val iterator = keys.values.iterator()
        while (iterator.hasNext()) {
            val press = iterator.next()
            if (!press.target.isShown || press.finished()) {
                press.dispose()
                iterator.remove()
            }
            press.target.invalidate()
        }
        updateClipping()
        root?.invalidate()
        invalidateOverlay?.invoke()
        if (keys.isEmpty()) stopObserving()
    }

    fun down(view: View, key: Any, bounds: Rect, event: MotionEvent, index: Int) {
        val target = key as? View ?: view
        if (!target.isEnabled || bounds.isEmpty) return
        val animate = ValueAnimator.areAnimatorsEnabled()
        val press = keys[key]?.takeIf { it.view === view && it.animate == animate }
            ?: Press(view, target, animate, ::requestRedraw).also {
                keys.put(key, it)?.dispose()
            }
        if (animate && writeObserver == null) {
            writeObserver = Snapshot.registerGlobalWriteObserver {
                if (Looper.myLooper() === main.looper && writeObserver != null) requestRedraw()
            }
        }
        press.down(IntSize(bounds.width(), bounds.height()), event, index)
        requestRedraw()
    }

    fun update(view: View, event: MotionEvent) {
        val action = event.actionMasked
        for (press in keys.values) {
            if (press.view !== view || press.pointerId < 0) continue
            when (action) {
                MotionEvent.ACTION_MOVE -> {
                    val index = event.findPointerIndex(press.pointerId)
                    if (index >= 0) press.move(event, index)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                    if (event.getPointerId(event.actionIndex) == press.pointerId) press.release(event)
                }
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL -> press.release(null)
            }
        }
    }

    fun hasOverflow() = keys.values.any { it.overflows() }

    fun sample(key: Any?): Press? = if (Looper.myLooper() === main.looper) keys[key] else null

    fun cancelUnder(container: View) {
        val iterator = keys.values.iterator()
        var removed = false
        while (iterator.hasNext()) {
            val press = iterator.next()
            var current: View? = press.target
            while (current != null && current !== container) current = current.parent as? View
            if (current == null) continue
            iterator.remove()
            press.dispose()
            press.target.invalidate()
            removed = true
        }
        if (removed) {
            updateClipping()
            root?.invalidate()
            if (keys.isEmpty()) stopObserving()
        }
    }

    fun clear() {
        stopObserving()
        main.removeCallbacks(redraw)
        redrawQueued = false
        for (press in keys.values) {
            press.dispose()
            press.target.invalidate()
        }
        keys.clear()
        updateClipping()
    }

    private fun updateClipping() {
        overflowing.clear()
        val boundary = root
        if (boundary != null) {
            for (press in keys.values) {
                if (!press.overflows()) continue
                var child = press.target
                while (child !== boundary) {
                    val parent = child.parent as? ViewGroup ?: break
                    child = parent
                    // The candidate list is clipped to the native slot even while its sibling button moves.
                    if (parent.id == KeyboardStyle.CANDIDATE_NORMAL_CONTAINER) continue
                    if (!overflowing.add(parent)) break
                }
            }
        }
        val iterator = unclipped.iterator()
        while (iterator.hasNext()) {
            val group = iterator.next()
            if (group !in overflowing) {
                group.clipChildren = true
                iterator.remove()
            }
        }
        for (group in overflowing) {
            if (group.clipChildren) {
                group.clipChildren = false
                unclipped.add(group)
            }
        }
    }

    private fun stopObserving() {
        writeObserver?.dispose()
        writeObserver = null
    }

    private fun requestRedraw() {
        if (redrawQueued) return
        redrawQueued = true
        main.post(redraw)
    }

    class Press(
        val view: View,
        val target: View,
        val animate: Boolean,
        private val invalidate: () -> Unit,
    ) : View.OnAttachStateChangeListener {
        private val scope = CoroutineScope(Dispatchers.Main.immediate)
        private val density = view.resources.displayMetrics.density
        private val press = CouiPressMotion(scope, density)
        private val touch = CouiTouchMotion(scope, density)
        private val glow = ValueAnimator().apply {
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                strength = it.animatedValue as Float
                invalidate()
            }
        }
        private var tracker: VelocityTracker? = null
        private val screenLocation = IntArray(2)
        private var touchX = 0f
        private var touchY = 0f
        private var dragOrigin = Offset.Zero
        private var directDrag = Offset.Zero
        private val stretchLimit = Math.rint((28f * density).toDouble()).toFloat()
        var pointerId = -1
            private set
        var strength = 0f
            private set

        init {
            target.addOnAttachStateChangeListener(this)
        }

        private val dx get() = if (animate) touch.translationX.value else directDrag.x
        private val dy get() = if (animate) touch.translationY.value else directDrag.y
        private val sx get() = touch.scaleX.value + press.scale.value - 1f
        private val sy get() = touch.scaleY.value + press.scale.value - 1f

        fun down(size: IntSize, event: MotionEvent, index: Int) {
            tracker?.recycle()
            tracker = if (animate) VelocityTracker.obtain() else null
            trackMovement(event)
            pointerId = event.getPointerId(index)
            readTouchPosition(event, index)
            dragOrigin = Offset(event.getRawX(index), event.getRawY(index))
            if (animate) {
                touch.begin(dragOrigin, size)
                press.press(size)
                fadeGlow(1f, 95L)
            } else {
                directDrag = Offset.Zero
                strength = 1f
            }
        }

        fun move(event: MotionEvent, index: Int) {
            trackMovement(event)
            readTouchPosition(event, index)
            val position = Offset(event.getRawX(index), event.getRawY(index))
            if (animate) {
                touch.move(position)
            } else {
                // Keep finger-driven feedback when Android disables autonomous animations.
                val delta = position - dragOrigin
                directDrag = if (maxOf(abs(delta.x), abs(delta.y)) < 5f) Offset.Zero
                    else Offset(rubberOffset(delta.x), rubberOffset(delta.y))
            }
            invalidate()
        }

        // Match CouiTouchMotion without scheduling a spring.
        private fun rubberOffset(distance: Float): Float {
            val scaled = distance * .05f
            return scaled / (1f + abs(scaled) / stretchLimit)
        }

        fun release(event: MotionEvent?) {
            var velocity = Offset.Zero
            tracker?.let {
                if (event != null && (event.flags and MotionEvent.FLAG_CANCELED) == 0) {
                    trackMovement(event)
                    // Match CouiTouchInput's release velocity in pixels per millisecond.
                    it.computeCurrentVelocity(1)
                    velocity = Offset(it.getXVelocity(pointerId) * .3f, it.getYVelocity(pointerId) * .3f)
                }
                it.recycle()
            }
            tracker = null
            pointerId = -1
            if (animate) {
                touch.release(velocity)
                press.release()
                fadeGlow(0f, 180L)
            } else {
                directDrag = Offset.Zero
                strength = 0f
            }
            invalidate()
        }

        private fun trackMovement(event: MotionEvent) {
            val velocity = tracker ?: return
            if (target === view) {
                velocity.addMovement(event)
                return
            }
            // DOWN arrives in the button wrapper; the rest can come from the IME root.
            val sample = MotionEvent.obtain(event)
            try {
                sample.setLocation(event.rawX, event.rawY)
                velocity.addMovement(sample)
            } finally {
                sample.recycle()
            }
        }

        private fun readTouchPosition(event: MotionEvent, index: Int) {
            if (target === view) {
                touchX = event.getX(index)
                touchY = event.getY(index)
            } else {
                target.getLocationOnScreen(screenLocation)
                touchX = event.getRawX(index) - screenLocation[0]
                touchY = event.getRawY(index) - screenLocation[1]
            }
        }

        private fun fadeGlow(target: Float, durationMs: Long) {
            glow.cancel()
            if (strength == target) return
            glow.setFloatValues(strength, target)
            glow.duration = durationMs
            glow.start()
        }

        fun overflows() = dx != 0f || dy != 0f || sx > 1f || sy > 1f

        fun backdropStrength(): Float = ((maxOf(abs(dx), abs(dy)) / density - 1f) / 5f).coerceIn(0f, 1f)

        fun finished() = pointerId < 0 && strength == 0f && press.scale.value == 1f &&
            touch.scaleX.value == 1f && touch.scaleY.value == 1f && dx == 0f && dy == 0f

        fun dispose() {
            target.removeOnAttachStateChangeListener(this)
            tracker?.recycle()
            tracker = null
            glow.cancel()
            scope.cancel()
        }

        fun transform(canvas: Canvas, centerX: Float, centerY: Float) {
            canvas.translate(dx, dy)
            canvas.scale(sx, sy, centerX, centerY)
        }

        fun transform(matrix: Matrix, centerX: Float, centerY: Float) {
            matrix.setScale(sx, sy, centerX, centerY)
            matrix.postTranslate(dx, dy)
        }

        fun lightX(centerX: Float) = centerX + (touchX - centerX - dx) / sx
        fun lightY(centerY: Float) = centerY + (touchY - centerY - dy) / sy

        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) = invalidate()
    }
}
