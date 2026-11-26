/* Backdrop sampling adapted from ItosEO/COUI-Kit, MPL-2.0.
 * See THIRD_PARTY_NOTICES.md for the upstream revision.
 */
package local.wetype.couiglass;

import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;

import com.itos.coui.effect.CouiCoverageBlurEffect;

/** COUI-Kit's padded backdrop sampling adapted to View render nodes. */
final class CouiBackdropBlur {
    private final RenderNode sample = new RenderNode("WeType blurred backdrop");
    private final Rect sampleBounds = new Rect();
    private final RectF maskBounds = new RectF();
    private final Paint mix = new Paint();
    private RenderNode sampledSource;
    private float radius;
    private int backing;
    private int padding;

    CouiBackdropBlur() {
        mix.setBlendMode(BlendMode.PLUS);
    }

    void clear() {
        sample.discardDisplayList();
        sampledSource = null;
    }

    boolean draw(Canvas canvas, RenderNode source, Path mask, int width, int height,
                 float blurRadius, float strength, int opaqueBacking) {
        boolean changed = backing != opaqueBacking || radius != blurRadius;
        if (changed) {
            if (opaqueBacking != Color.TRANSPARENT) {
                CouiCoverageBlurEffect coverage = new CouiCoverageBlurEffect(blurRadius);
                sample.setRenderEffect(coverage.getEffect());
                padding = (int) coverage.getPadding();
            } else {
                sample.setRenderEffect(RenderEffect.createBlurEffect(blurRadius, blurRadius, Shader.TileMode.CLAMP));
                padding = (int) Math.ceil(blurRadius);
            }
            radius = blurRadius;
            backing = opaqueBacking;
        }
        mask.computeBounds(maskBounds, true);
        maskBounds.roundOut(sampleBounds);
        sampleBounds.inset(-padding, -padding);
        if (!sampleBounds.intersect(0, 0, width, height)) return false;
        boolean moved = sample.setPosition(sampleBounds);
        if (changed || moved || sampledSource != source || !sample.hasDisplayList()) {
            RecordingCanvas input = sample.beginRecording();
            try {
                if (backing != Color.TRANSPARENT) input.drawColor(backing);
                input.translate(-sampleBounds.left, -sampleBounds.top);
                input.drawRenderNode(source);
            } finally {
                sample.endRecording();
            }
            sampledSource = source;
        }
        int saved = canvas.save();
        canvas.clipOutPath(mask);
        canvas.drawRenderNode(source);
        canvas.restoreToCount(saved);

        saved = canvas.save();
        canvas.clipPath(mask);
        if (strength >= .999f) {
            canvas.drawRenderNode(sample);
        } else {
            // Interpolate sharp and blurred alpha before compositing over the window.
            int layer = canvas.saveLayer(maskBounds, null);
            int alpha = Math.round(strength * 255f);
            int sharp = canvas.saveLayerAlpha(maskBounds, 255 - alpha);
            canvas.drawRenderNode(source);
            canvas.restoreToCount(sharp);
            mix.setAlpha(alpha);
            int soft = canvas.saveLayer(maskBounds, mix);
            canvas.drawRenderNode(sample);
            canvas.restoreToCount(soft);
            canvas.restoreToCount(layer);
        }
        canvas.restoreToCount(saved);
        return true;
    }
}
