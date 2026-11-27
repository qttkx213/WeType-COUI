package local.wetype.couiglass;

import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.view.ViewGroup;

/** Fades partially visible rows into the live keyboard background at the list edges. */
final class ListEdgeFade {
    private final Rect visible = new Rect();
    private final Matrix gradientMatrix = new Matrix();
    private final LinearGradient gradient = new LinearGradient(0f, 0f, 0f, 1f,
            Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP);
    private final Paint erase = new Paint();
    private int top;
    private int bottom;
    private float topFade;
    private float bottomFade;

    ListEdgeFade() {
        erase.setShader(gradient);
        erase.setBlendMode(BlendMode.DST_OUT);
    }

    void setViewport(ViewGroup list) {
        boolean padded = list.getClipToPadding();
        top = padded ? list.getPaddingTop() : 0;
        bottom = list.getHeight() - (padded ? list.getPaddingBottom() : 0);
        if (list.getLocalVisibleRect(visible)) {
            top = Math.max(top, visible.top);
            bottom = Math.min(bottom, visible.bottom);
        }
        topFade = bottomFade = 0f;
    }

    void include(float rowTop, float rowBottom, int limit) {
        topFade = Math.max(topFade, fadeDepth(rowBottom - top, top - rowTop, limit));
        bottomFade = Math.max(bottomFade, fadeDepth(bottom - rowTop, rowBottom - bottom, limit));
    }

    boolean active() { return bottom > top && (topFade > 0f || bottomFade > 0f); }

    int save(Canvas canvas, int width) {
        int saved = active() ? canvas.saveLayer(0, top, width, bottom, null) : canvas.save();
        canvas.clipRect(0, top, width, bottom);
        return saved;
    }

    void draw(Canvas canvas, int width) {
        if (topFade > 0f) fadeEdge(canvas, width, top + topFade, top);
        if (bottomFade > 0f) fadeEdge(canvas, width, bottom - bottomFade, bottom);
    }

    private void fadeEdge(Canvas canvas, int width, float inner, float edge) {
        gradientMatrix.setScale(1f, edge - inner);
        gradientMatrix.postTranslate(0f, inner);
        gradient.setLocalMatrix(gradientMatrix);
        canvas.drawRect(0, Math.min(inner, edge), width, Math.max(inner, edge), erase);
    }

    static float fadeDepth(float inside, float outside, int limit) {
        return Math.max(0f, Math.min(limit, Math.min(inside, outside)));
    }
}
