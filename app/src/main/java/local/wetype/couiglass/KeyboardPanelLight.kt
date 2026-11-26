package local.wetype.couiglass

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.itos.coui.effect.CouiContour

/** COUI navigation capsule lighting on the IME's window outline. */
internal class KeyboardPanelLight(private val density: Float, private val dark: Boolean) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
    }
    private val contours = arrayOfNulls<CouiContour>(3)
    private val shaders = arrayOfNulls<RuntimeShader>(3)
    private var geometry: Geometry? = null
    private var shaderColor = 0
    private var failed = false

    fun draw(canvas: Canvas, shape: Path, width: Int, height: Int, top: Float, bottomLeft: Float,
        bottomRight: Float, color: Int) {
        if (Build.VERSION.SDK_INT >= 33 && canvas.isHardwareAccelerated && !failed) {
            try {
                val next = Geometry(width.toFloat(), height.toFloat(), top, bottomLeft, bottomRight)
                if (geometry != next || shaderColor != color) {
                    updateShaders(next, color)
                    geometry = next
                    shaderColor = color
                }
                drawLit(canvas, shape, next)
                return
            } catch (_: RuntimeException) {
                failed = true
            } catch (_: LinkageError) {
                failed = true
            }
        }
        fill.shader = null
        fill.color = color
        canvas.drawPath(shape, fill)
        drawFallback(canvas, shape, width.toFloat(), height.toFloat(), color ushr 24)
    }

    private fun updateShaders(bounds: Geometry, color: Int) {
        val inner = if (dark) .045f else .12f
        val line = if (dark) .86f else .31f
        val surface = Color(color)
        val radii = floatArrayOf(bounds.top, bounds.bottomLeft, bounds.bottomRight)
        for (index in radii.indices) {
            val previous = radii.indexOfFirst { it == radii[index] }
            if (previous != index) {
                shaders[index] = shaders[previous]
                continue
            }
            val contour = contours[index] ?: CouiContour(floating = true).also { contours[index] = it }
            // The full keyboard uses gentler light than the much smaller navigation capsule.
            shaders[index] = contour.shader(Size(bounds.width, bounds.height), density, radii[index],
                2f, surface, inner, line, .62f)
        }
    }

    private fun drawLit(canvas: Canvas, shape: Path, bounds: Geometry) {
        fill.color = android.graphics.Color.WHITE
        fill.shader = shaders[0]
        if (shaders[0] === shaders[1] && shaders[0] === shaders[2]) {
            canvas.drawPath(shape, fill)
            return
        }
        val midY = (bounds.height / 2f).toInt().toFloat()
        val midX = (bounds.width / 2f).toInt().toFloat()
        // Reuse the same full-size shader geometry so the lighting meets across each clip.
        drawPart(canvas, shape, 0, 0f, 0f, bounds.width, midY)
        drawPart(canvas, shape, 1, 0f, midY, midX, bounds.height)
        drawPart(canvas, shape, 2, midX, midY, bounds.width, bounds.height)
    }

    private fun drawPart(canvas: Canvas, shape: Path, index: Int, left: Float, top: Float, right: Float, bottom: Float) {
        fill.shader = shaders[index]
        val saved = canvas.save()
        canvas.clipRect(left, top, right, bottom)
        canvas.drawPath(shape, fill)
        canvas.restoreToCount(saved)
    }

    private fun drawFallback(canvas: Canvas, shape: Path, width: Float, height: Float, alpha: Int) {
        edge.shader = LinearGradient(width * .12f, 0f, width * .88f, height,
            intArrayOf(0x35FFFFFF, 0x09FFFFFF, 0x2BFFFFFF), floatArrayOf(0f, .52f, 1f), Shader.TileMode.CLAMP)
        edge.alpha = alpha
        val saved = canvas.save()
        canvas.clipPath(shape)
        canvas.drawPath(shape, edge)
        canvas.restoreToCount(saved)
    }

    private data class Geometry(val width: Float, val height: Float, val top: Float, val bottomLeft: Float,
        val bottomRight: Float)
}
