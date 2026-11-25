package local.wetype.couiglass

import android.animation.ValueAnimator
import android.view.View
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.itos.coui.chip.CouiChipTabDefaults
import com.itos.coui.effect.CouiFloatingMaterial
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.segmentbutton.CouiSegmentedControlDefaults
import kotlin.math.PI
import kotlin.math.abs

/** COUI materials adapted to the host's Canvas surfaces. */
internal object CouiGlass {
    private val lightSegment = tint(CouiSegmentedControlDefaults.material(false))
    private val darkSegment = tint(CouiSegmentedControlDefaults.material(true))
    private val lightChip = tint(CouiChipTabDefaults.material(false, 1f))
    private val darkChip = tint(CouiChipTabDefaults.material(true, 1f))

    @JvmStatic fun segment(dark: Boolean, alpha: Int) =
        CouiPalette.fade(if (dark) darkSegment else lightSegment, alpha)

    @JvmStatic fun indicator(dark: Boolean, alpha: Int) =
        CouiPalette.fade(CouiSegmentedControlDefaults.indicatorColor(dark).toArgb(), alpha)

    @JvmStatic fun chip(dark: Boolean) = if (dark) darkChip else lightChip

    private val lightGroup = CouiPageDefaults.colors(Color.Transparent, false).card.toArgb()
    private val darkGroup = CouiPageDefaults.colors(Color.Transparent, true).card.toArgb()

    @JvmStatic fun group(dark: Boolean, alpha: Int) =
        CouiPalette.fade(if (dark) darkGroup else lightGroup, alpha * 3 / 5)

    @JvmStatic fun groupRadius(density: Float) = CouiPageDefaults.shape.radius.value * density

    @JvmStatic fun groupPath(width: Float, height: Float, radius: Float) =
        CouiCorners.path(width, height, radius, CouiPageDefaults.shape.cornerWeight)

    @JvmStatic fun itemInset(view: View) = CouiPageDefaults.rowPadding.value * view.resources.displayMetrics.density

    // A separate window supplies its own blurred pixels. Use translucent colors for that surface.
    private fun tint(material: CouiFloatingMaterial) = ColorUtils.compositeColors(
        material.secondColor.toArgb(), CouiPalette.fade(material.firstColor.toArgb(), 64),
    )
}

/** The COUI segmented control's position spring. */
internal class SelectionSpring(private val invalidate: Runnable) {
    var value = 0f
        private set
    private val animation = SpringAnimation(FloatValueHolder()).apply {
        val frequency = (2 * PI / .4).toFloat()
        spring = SpringForce().setStiffness(frequency * frequency).setDampingRatio(.9f)
        setMinimumVisibleChange(.001f)
        addUpdateListener { _, next, _ ->
            value = next
            invalidate.run()
        }
    }

    fun snapTo(next: Float) {
        animation.cancel()
        value = next
        invalidate.run()
    }

    fun animateTo(target: Float) {
        if (!ValueAnimator.areAnimatorsEnabled() || abs(value - target) < .001f) snapTo(target)
        else {
            if (!animation.isRunning) animation.setStartValue(value)
            animation.animateToFinalPosition(target)
        }
    }
}
