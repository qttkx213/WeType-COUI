package local.wetype.couiglass;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.Build;
import android.util.LruCache;

final class KeySurface {
    private final WeTypeModule module;
    private final LruCache<Dimensions, Brushes> brushes = new LruCache<>(48);
    private final LruCache<OutlineDimensions, Path> outlines = new LruCache<>(48);
    private final Paint feedback = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint spotlight = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RadialGradient touchGlow = new RadialGradient(0f, 0f, 1f, Color.WHITE,
            Color.TRANSPARENT, Shader.TileMode.CLAMP);
    private final Matrix lightTransform = new Matrix();
    private boolean shaderFailed;
    private boolean shaderReported;

    KeySurface(WeTypeModule module) {
        this.module = module;
        spotlight.setShader(touchGlow);
    }

    boolean draw(Canvas canvas, float left, float top, float right, float bottom, float density,
                 int opacity, boolean pressed, boolean dark, IconButtonMotion.Press motion) {
        return draw(canvas, left, top, right, bottom, density, radius(right - left, bottom - top, density),
                CouiPalette.topBarFill(dark, opacity), opacity, pressed, dark, motion);
    }

    boolean draw(Canvas canvas, float left, float top, float right, float bottom, float density,
                 float radius, int fill, int opacity, boolean pressed, boolean dark, IconButtonMotion.Press motion) {
        float width = right - left;
        float height = bottom - top;
        if (!valid(width, height) || opacity == 0) return false;
        boolean hardware = Build.VERSION.SDK_INT >= 33 && canvas.isHardwareAccelerated() && !shaderFailed;
        Dimensions dimensions = new Dimensions(width, height, radius, density, fill, dark, hardware);
        Brushes material = brushes.get(dimensions);
        if (material == null) {
            material = buildBrushes(dimensions);
            brushes.put(dimensions, material);
        }

        Path shape = outline(width, height, radius);
        int saved = canvas.save();
        try {
            canvas.translate(left, top);
            canvas.drawPath(shape, material.fill);
            if (material.edge != null) canvas.drawPath(material.edgePath, material.edge);
            if (motion == null && pressed) {
                feedback.setColor(CouiPalette.fade(CouiPalette.pressMask(dark), opacity));
                canvas.drawPath(shape, feedback);
            }
            if (motion != null && motion.getStrength() > 0f) {
                float x = motion.lightX(left + width / 2f) - left;
                float y = motion.lightY(top + height / 2f) - top;
                float reach = Math.max(width, height) * .8f;
                lightTransform.setScale(reach, reach);
                lightTransform.postTranslate(x, y);
                touchGlow.setLocalMatrix(lightTransform);
                spotlight.setAlpha(Math.round(opacity * motion.getStrength() * (dark ? .12f : .24f)));
                canvas.drawPath(shape, spotlight);
            }
        } finally {
            canvas.restoreToCount(saved);
        }
        return true;
    }

    void drawRelease(Canvas canvas, float left, float top, float right, float bottom, float density, int color) {
        float width = right - left;
        float height = bottom - top;
        if (!valid(width, height) || Color.alpha(color) == 0) return;
        feedback.setColor(color);
        float radius = radius(width, height, density);
        int saved = canvas.save();
        try {
            canvas.translate(left, top);
            canvas.drawPath(outline(width, height, radius), feedback);
        } finally {
            canvas.restoreToCount(saved);
        }
    }

    Path outline(float width, float height, float radius) {
        OutlineDimensions size = new OutlineDimensions(width, height, radius);
        Path path = outlines.get(size);
        if (path == null) {
            path = CouiCorners.path(width, height, radius);
            outlines.put(size, path);
        }
        return path;
    }

    static float radius(float width, float height, float density) {
        return Math.min(10f * density, Math.min(width, height) * .28f);
    }

    private static boolean valid(float width, float height) {
        return width >= 2f && height >= 2f && Float.isFinite(width) && Float.isFinite(height);
    }

    private Brushes buildBrushes(Dimensions size) {
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        if (size.hardware) {
            try {
                fill.setShader(CouiContourShader.create(size.width, size.height, size.radius,
                        size.density, size.color, size.dark));
                if (!shaderReported) {
                    shaderReported = true;
                    module.trace("COUI IconButton contour shader ready");
                }
                return new Brushes(fill, null, null);
            } catch (RuntimeException | LinkageError failure) {
                shaderFailed = true;
                module.warn("COUI contour shader unavailable; using gradient", failure);
            }
        }
        fill.setColor(size.color);
        Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(Math.min(size.radius * .5f, Math.max(1f, size.density * .65f)));
        int alpha = Color.alpha(size.color);
        int bright = CouiPalette.fade(size.dark ? 0x38FFFFFF : 0xADFFFFFF, alpha);
        int mid = CouiPalette.fade(size.dark ? 0x0BFFFFFF : 0x24FFFFFF, alpha);
        int lower = CouiPalette.fade(size.dark ? 0x44FFFFFF : 0x2C777777, alpha);
        edge.setShader(new LinearGradient(0f, 0f, 0f, size.height, new int[]{bright, mid, lower},
                new float[]{0f, .48f, 1f}, Shader.TileMode.CLAMP));
        float inset = edge.getStrokeWidth() * .5f;
        Path edgePath = CouiCorners.path(size.width - 2f * inset, size.height - 2f * inset, size.radius - inset);
        edgePath.offset(inset, inset);
        return new Brushes(fill, edge, edgePath);
    }

    private record OutlineDimensions(float width, float height, float radius) {}
    private record Dimensions(float width, float height, float radius, float density,
                              int color, boolean dark, boolean hardware) {}
    private record Brushes(Paint fill, Paint edge, Path edgePath) {}
}
