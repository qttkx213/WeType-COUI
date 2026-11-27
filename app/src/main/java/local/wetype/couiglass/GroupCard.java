package local.wetype.couiglass;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;

/** COUI card surfaces for clipboard entries, common phrases, and keyboard choices. */
final class GroupCard extends Drawable {
    private final KeyboardStyle style;
    private final float density;
    private final boolean lightKeyMaterial;
    private int alpha = 255;
    private int stackOpacity = 255;
    private float radius;
    private Path foldCover;
    private RectF entryBounds;
    private RectF frontFoldBounds;

    GroupCard(KeyboardStyle style, View owner, boolean lightKeyMaterial) {
        this.style = style;
        this.lightKeyMaterial = lightKeyMaterial;
        density = owner.getResources().getDisplayMetrics().density;
    }

    @Override protected void onBoundsChange(Rect bounds) {
        radius = radius(bounds.width(), bounds.height());
    }

    void setStackOpacity(int opacity) {
        if (stackOpacity == opacity) return;
        stackOpacity = opacity;
        invalidateSelf();
    }

    void maskFold(View fold, View entry, View frontFold) {
        if (foldCover == null) {
            foldCover = new Path();
            entryBounds = new RectF();
            frontFoldBounds = new RectF();
        }
        boolean entryChanged = updateCover(entryBounds, fold, entry);
        boolean frontChanged = updateCover(frontFoldBounds, fold, frontFold);
        if (!entryChanged && !frontChanged) return;
        foldCover.rewind();
        addCover(entryBounds);
        addCover(frontFoldBounds);
        invalidateSelf();
    }

    private static boolean updateCover(RectF bounds, View fold, View above) {
        if (above == null || above.getVisibility() != View.VISIBLE || above.getWidth() <= 0 || above.getHeight() <= 0) {
            if (bounds.isEmpty()) return false;
            bounds.setEmpty();
            return true;
        }
        // The native layers are siblings, including when their row scrolls or animates.
        float left = above.getX() - fold.getX();
        float top = above.getY() - fold.getY();
        float right = left + above.getWidth();
        float bottom = top + above.getHeight();
        if (bounds.left == left && bounds.top == top && bounds.right == right && bounds.bottom == bottom) return false;
        bounds.set(left, top, right, bottom);
        return true;
    }

    private void addCover(RectF face) {
        if (face.isEmpty()) return;
        float width = face.width();
        float height = face.height();
        foldCover.addPath(style.material().outline(width, height, radius(width, height)), face.left, face.top);
    }

    private float radius(float width, float height) {
        return Math.min(CouiGlass.groupRadius(density), Math.min(width, height) / 2f);
    }

    @Override public void draw(Canvas canvas) {
        boolean dark = style.dark();
        Rect bounds = getBounds();
        int opacity = (alpha * stackOpacity + 127) / 255;
        int fill = lightKeyMaterial && !dark ? CouiPalette.topBarFill(false, opacity) : CouiGlass.segment(dark, opacity);
        int save = 0;
        // Let each exposed layer tint the shared blurred backdrop once.
        if (foldCover != null && !foldCover.isEmpty()) {
            save = canvas.save();
            canvas.clipOutPath(foldCover);
        }
        try {
            style.material().draw(canvas, bounds.left, bounds.top, bounds.right, bounds.bottom, density,
                    radius, fill, opacity, false, dark, null);
        } finally {
            if (save != 0) canvas.restoreToCount(save);
        }
    }

    @Override public void setAlpha(int alpha) { this.alpha = alpha; invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) {}
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
