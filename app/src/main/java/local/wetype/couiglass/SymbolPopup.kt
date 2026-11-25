package local.wetype.couiglass

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.graphics.drawable.Drawable
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import androidx.compose.animation.core.FloatSpringSpec
import io.github.libxposed.api.XposedInterface
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.roundToInt

/** Shares the popup's visual transform with the keyboard backdrop, while touch uses its laid-out bounds. */
internal class SymbolPopup(
    private val root: View,
    val view: View,
    private val body: View,
    private val sourceKey: Rect,
) {
    private val location = IntArray(2)
    private val pivot = FloatArray(2)
    private val bounds = RectF()
    private val touchBounds = Rect()
    private val host = view.parent as ViewGroup
    private val oldOutline = view.outlineProvider
    private val viewport = Rect()
    private val projected = RectF()
    private val base = Matrix()
    private val drag = Matrix()
    private val inRoot = Matrix()
    private val toHost = Matrix()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val content = RenderNode("WeType popup content").apply { setClipToBounds(false) }
    private val density = root.resources.displayMetrics.density
    private val motion = IconButtonMotion(::invalidate)
    private val overlay = object : Drawable() {
        override fun draw(canvas: Canvas) = drawPopup(canvas)
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(filter: ColorFilter?) = Unit
        @Deprecated("Deprecated in Android")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
    private var animator: ValueAnimator? = null
    private var frame: Bitmap? = null
    private var exitFace: Path? = null
    private var drawing = false

    init {
        view.outlineProvider = null
        overlay.setBounds(0, 0, host.width, host.height)
        host.overlay.add(overlay)
    }

    var closing = false
        private set
    private var scale = 0f
    var opacity = 0f
        private set

    fun open() = animate(true) {}

    fun close(onEnd: Runnable) {
        if (closing) return
        closing = true
        val face = SymbolMenu.face(body)
        if (!ValueAnimator.areAnimatorsEnabled() || opacity <= 0f || face == null || !view.isShown || !locate(face)) {
            onEnd.run()
            return
        }
        exitFace = Path(face)
        motion.clear()
        // WeType recycles the menu's child views synchronously in its hide callback.
        drawing = true
        try {
            frame = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
                view.draw(Canvas(it))
            }
        } catch (_: RuntimeException) {
            frame = null
        } finally {
            drawing = false
        }
        if (frame == null) onEnd.run() else animate(false, onEnd)
    }

    fun dispose() {
        animator?.removeAllListeners()
        animator?.cancel()
        animator = null
        motion.clear()
        host.overlay.remove(overlay)
        view.outlineProvider = oldOutline
        view.invalidate()
        content.discardDisplayList()
        frame = null
        root.invalidate()
    }

    fun invalidate() {
        overlay.invalidateSelf()
        root.invalidate()
    }

    fun observeTouch(source: View, event: MotionEvent, index: Int) {
        if (closing) return
        val active = motion.sample(view)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (index !in 0 until event.pointerCount) return
                if (active?.pointerId != event.getPointerId(index) || active.view !== source) {
                    // The menu opens after the held key has received DOWN. Begin at the first live position.
                    val face = SymbolMenu.face(body) ?: return
                    face.computeBounds(bounds, true)
                    bounds.roundOut(touchBounds)
                    motion.down(source, view, touchBounds, event, index)
                } else {
                    motion.update(source, event)
                }
            }
            else -> active?.takeIf { it.pointerId >= 0 }?.release(event)
        }
    }

    fun addBackdrop(path: Path) {
        if (opacity <= 0f || scale <= 0f) return
        val face = (if (closing) exitFace else SymbolMenu.face(body)) ?: return
        if (!locate(face)) return
        KeyboardLayers.localToRoot(host, root, inRoot)
        inRoot.preConcat(toHost)
        path.addPath(face, inRoot)
    }

    fun drawHost(chain: XposedInterface.Chain): Any? = if (drawing) chain.proceed() else null

    private fun drawPopup(canvas: Canvas) {
        if (opacity <= 0f || scale <= 0f || !root.isShown || (!closing && !view.isShown)) return
        val face = (if (closing) exitFace else SymbolMenu.face(body)) ?: return
        if (!locate(face)) return
        val saved = canvas.save()
        try {
            canvas.concat(toHost)
            drawShadow(canvas, face)
            canvas.clipPath(face)
            val image = frame
            if (image != null) {
                paint.alpha = (opacity * 255f).roundToInt()
                canvas.drawBitmap(image, 0f, 0f, paint)
            } else if (!closing) {
                if (opacity < 1f) canvas.saveLayerAlpha(bounds.left, bounds.top, bounds.right, bounds.bottom,
                    (opacity * 255f).roundToInt())
                if (canvas.isHardwareAccelerated) {
                    content.setPosition(0, 0, view.width, view.height)
                    val input = content.beginRecording()
                    try { drawContent(input) } finally { content.endRecording() }
                    canvas.drawRenderNode(content)
                } else drawContent(canvas)
            }
        } finally {
            canvas.restoreToCount(saved)
        }
    }

    private fun drawContent(canvas: Canvas) {
        drawing = true
        try { view.draw(canvas) } finally { drawing = false }
    }

    private fun drawShadow(canvas: Canvas, face: Path) {
        val saved = canvas.save()
        canvas.clipOutPath(face)
        shadow.color = Color.BLACK
        shadow.setShadowLayer(6f * density, 0f, 2f * density,
            Color.argb((64f * opacity).roundToInt(), 0, 0, 0))
        canvas.drawPath(face, shadow)
        canvas.restoreToCount(saved)
    }

    private fun locate(face: Path): Boolean {
        // The overlay's parent can draw above the keyboard root and below it for recall.
        if (!host.getLocalVisibleRect(viewport)) return false
        if (!closing || exitFace == null) {
            face.computeBounds(bounds, true)
            val press = motion.sample(view)
            if (press == null) drag.reset()
            else press.transform(drag, bounds.centerX(), bounds.centerY())
            KeyboardLayers.localToRoot(view, host, base)
            host.getLocationInWindow(location)
            val x = sourceKey.exactCenterX() - location[0]
            val y = sourceKey.exactCenterY() - location[1]
            host.getLocationOnScreen(location)
            pivot[0] = x + location[0]
            pivot[1] = y + location[1]
            toHost.reset()
            host.transformMatrixToLocal(toHost)
            toHost.mapPoints(pivot)
        }
        toHost.set(base)
        toHost.preConcat(drag)
        toHost.mapRect(projected, bounds)
        val fit = minOf(scale, viewport.width() / projected.width().coerceAtLeast(1f),
            viewport.height() / projected.height().coerceAtLeast(1f))
        toHost.postScale(fit, fit, pivot[0], pivot[1])
        toHost.mapRect(projected, bounds)
        val dx = maxOf(0f, viewport.left - projected.left) - maxOf(0f, projected.right - viewport.right)
        val dy = maxOf(0f, viewport.top - projected.top) - maxOf(0f, projected.bottom - viewport.bottom)
        toHost.postTranslate(dx, dy)
        return true
    }

    private fun animate(open: Boolean, end: Runnable) {
        animator?.removeAllListeners()
        animator?.cancel()
        val target = if (open) 1f else 0f
        if (!ValueAnimator.areAnimatorsEnabled()) {
            scale = target
            opacity = target
            invalidate()
            end.run()
            return
        }
        val firstScale = scale
        val firstAlpha = opacity
        val size = if (open) openScale else closeScale
        val fade = if (open) openAlpha else closeAlpha
        val nanos = max(size.getDurationNanos(firstScale, target, 0f), fade.getDurationNanos(firstAlpha, target, 0f))
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (nanos / 1_000_000L).coerceAtLeast(1)
            interpolator = LinearInterpolator()
            addUpdateListener {
                val time = (it.animatedFraction.toDouble() * nanos).toLong()
                scale = size.getValueFromNanos(time, firstScale, target, 0f).coerceAtLeast(0f)
                opacity = fade.getValueFromNanos(time, firstAlpha, target, 0f).coerceIn(0f, 1f)
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    scale = target
                    opacity = target
                    animator = null
                    invalidate()
                    end.run()
                }
            })
            start()
        }
    }

    private companion object {
        // NavigationPopupMenu uses these four COUI list-popup springs.
        val openScale = spring(.40f, .20f)
        val openAlpha = spring(.27f, 0f)
        val closeScale = spring(.21f, 0f)
        val closeAlpha = spring(.23f, 0f)

        fun spring(response: Float, bounce: Float): FloatSpringSpec {
            val frequency = 2.0 * PI / response
            return FloatSpringSpec(1f - bounce, (frequency * frequency).toFloat(), .003f)
        }
    }
}
