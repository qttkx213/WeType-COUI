package local.wetype.couiglass

import android.graphics.Path
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.itos.coui.shape.CouiCornerWeight
import com.itos.coui.shape.CouiShape

internal object CouiCorners {
    const val WEIGHT = CouiCornerWeight
    private val pixels = Density(1f)

    @JvmStatic
    fun path(width: Float, height: Float, radius: Float): Path = path(width, height, radius, WEIGHT)

    @JvmStatic
    fun path(width: Float, height: Float, radius: Float, weight: Float): Path {
        val outline = CouiShape(radius.dp, weight).createOutline(Size(width, height), LayoutDirection.Ltr, pixels)
        return if (outline is Outline.Generic) outline.path.asAndroidPath() else Path().apply {
            addRect(0f, 0f, width, height, Path.Direction.CW)
        }
    }
}
