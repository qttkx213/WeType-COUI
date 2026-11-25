/* Regional blur setup adapted from mos-input-glass, Apache-2.0.
 * See THIRD_PARTY_NOTICES.md for the upstream revision and copyright.
 */
package local.wetype.couiglass;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.graphics.drawable.LayerDrawable;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;

final class KeyboardBackdrop {
    private static final String TAG = "WeTypeCOUI";
    private final Window window;
    private final View root;
    private final ColorDrawable transparent = new ColorDrawable(Color.TRANSPARENT);
    private Drawable originalRoot;
    private Drawable originalWindow;
    private Drawable installedRoot;
    private Drawable installedWindow;
    private boolean blurFailed;
    private boolean reportedReuse;
    private boolean usedBlur;
    private int tint;
    private int backing;
    private float radius;

    KeyboardBackdrop(Window window, View root) {
        this.window = window;
        this.root = root;
    }

    boolean apply(boolean dark, boolean enabled, int pageColor) {
        if (root.getWidth() <= 0 || root.getHeight() <= 0 || !root.isAttachedToWindow()) return false;
        if (hasExistingBlur()) {
            restore();
            if (!reportedReuse) {
                reportedReuse = true;
                Log.i(TAG, "Existing WeType window blur detected");
            }
            return false;
        }
        float density = root.getResources().getDisplayMetrics().density;
        boolean blur = pageColor == Color.TRANSPARENT && enabled && !blurFailed && root.isHardwareAccelerated();
        int color = CouiPalette.panel(dark, blur);
        float maxCorner = Math.min(root.getWidth(), root.getHeight()) / 2f;
        float corner = Math.min((blur ? 20f : 24f) * density, maxCorner);
        Drawable background = root.getBackground();
        if (installedRoot == null || background != installedRoot) originalRoot = background;
        if (installedRoot == null || color != tint || pageColor != backing || corner != radius || usedBlur != blur) {
            if (installedRoot != null) installedRoot.setVisible(false, false);
            Drawable region = blur ? createBlurDrawable(density, corner) : null;
            usedBlur = region != null;
            tint = CouiPalette.panel(dark, usedBlur);
            backing = pageColor;
            radius = corner;
            installedRoot = new Panel(region, usedBlur ? corner : Math.min(24f * density, maxCorner), tint, pageColor);
            KeyboardStyle.invalidateTree(root);
        }
        if (background != installedRoot) KeyboardStyle.setBackground(root, installedRoot);

        View decor = window.peekDecorView();
        if (decor != null && decor != root && decor.getBackground() != installedWindow) {
            originalWindow = decor.getBackground();
            window.setBackgroundDrawable(transparent);
            installedWindow = decor.getBackground();
        }
        return true;
    }

    boolean needsRepair() {
        View decor = window.peekDecorView();
        return installedRoot != null && (root.getBackground() != installedRoot
                || decor != null && decor != root && decor.getBackground() != installedWindow);
    }

    void restore() {
        usedBlur = false;
        if (installedRoot != null && root.getBackground() == installedRoot) {
            KeyboardStyle.setBackground(root, originalRoot);
        }
        View decor = window.peekDecorView();
        if (installedWindow != null && decor != null && decor.getBackground() == installedWindow) {
            window.setBackgroundDrawable(originalWindow);
        }
        if (installedRoot != null) installedRoot.setVisible(false, false);
        originalRoot = null;
        originalWindow = null;
        installedRoot = null;
        installedWindow = null;
    }

    private Drawable createBlurDrawable(float density, float corner) {
        Drawable blur = null;
        try {
            Object viewRoot = View.class.getMethod("getViewRootImpl").invoke(root);
            if (viewRoot == null) return null;
            Method createRegion = viewRoot.getClass().getMethod("createBackgroundBlurDrawable");
            blur = (Drawable) createRegion.invoke(viewRoot);
            Class<?> type = blur.getClass();
            type.getMethod("setCornerRadius", float.class, float.class, float.class, float.class)
                    .invoke(blur, corner, corner, 0f, 0f);
            type.getMethod("setColor", int.class).invoke(blur, Color.TRANSPARENT);
            // One window sample covers the toolbar and keys throughout press and page animations.
            type.getMethod("setBlurRadius", int.class).invoke(blur, Math.round(30f * density));
            Log.i(TAG, "Applied regional IME blur");
            return blur;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            if (blur != null) blur.setVisible(false, false);
            blurFailed = true;
            Log.w(TAG, "Regional blur unavailable; using panel tint", failure);
            return null;
        }
    }

    private static final class Panel extends DrawableWrapper {
        private final float corner;
        private final int color;
        private final int pageColor;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Path shape = new Path();
        private int alpha = 255;

        Panel(Drawable blur, float corner, int color, int pageColor) {
            super(blur);
            this.corner = corner;
            this.color = color;
            this.pageColor = pageColor;
        }

        @Override protected void onBoundsChange(Rect bounds) {
            super.onBoundsChange(bounds);
            if (bounds.isEmpty()) {
                shape.reset();
                return;
            }
            float width = bounds.width();
            float height = bounds.height();
            if (getDrawable() != null) {
                // Match the compositor's per-corner radii so the tint and blur share one edge.
                shape = new Path();
                shape.addRoundRect(bounds.left, bounds.top, bounds.right, bounds.bottom,
                        new float[]{corner, corner, corner, corner, 0f, 0f, 0f, 0f}, Path.Direction.CW);
            } else {
                shape = CouiCorners.path(width, height, corner);
                shape.addRect(0f, height / 2f, width, height, Path.Direction.CW);
                shape.offset(bounds.left, bounds.top);
            }
        }

        @Override public void draw(Canvas canvas) {
            if (pageColor != Color.TRANSPARENT) {
                paint.setColor(CouiPalette.fade(pageColor, alpha));
                canvas.drawRect(getBounds(), paint);
            }
            if (canvas.isHardwareAccelerated()) super.draw(canvas);
            paint.setColor(CouiPalette.fade(color, alpha));
            canvas.drawPath(shape, paint);
        }

        @Override public void setAlpha(int alpha) {
            if (this.alpha == alpha) return;
            this.alpha = alpha;
            super.setAlpha(alpha);
            invalidateSelf();
        }

        @Override public int getAlpha() { return alpha; }
        @Override public int getOpacity() {
            return pageColor != Color.TRANSPARENT && alpha == 255
                    ? PixelFormat.OPAQUE : PixelFormat.TRANSLUCENT;
        }
    }

    private boolean hasExistingBlur() {
        WindowManager.LayoutParams params = window.getAttributes();
        if ((params.flags & WindowManager.LayoutParams.FLAG_BLUR_BEHIND) != 0
                && params.getBlurBehindRadius() > 0) return true;
        View decor = window.peekDecorView();
        if (hasWindowBlurRadius(decor)) return true;
        for (View v = root; v != null; v = v.getParent() instanceof View parent ? parent : null) {
            if (v.getBackground() != installedRoot && isBlurDrawable(v.getBackground())) return true;
            if (isBlurDrawable(v.getForeground())) return true;
        }
        return containsChildBlur(root);
    }

    private boolean containsChildBlur(View view) {
        if (!(view instanceof ViewGroup group)) return false;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            if (isBlurDrawable(child.getBackground()) || isBlurDrawable(child.getForeground())
                    || containsChildBlur(child)) return true;
        }
        return false;
    }

    static boolean isBlurDrawable(Drawable drawable) {
        if (drawable == null) return false;
        if (drawable.getClass().getSimpleName().toLowerCase(Locale.ROOT).contains("blurdrawable")) return true;
        if (drawable instanceof DrawableWrapper wrapper) return isBlurDrawable(wrapper.getDrawable());
        if (drawable instanceof LayerDrawable layers) {
            for (int i = 0; i < layers.getNumberOfLayers(); i++) {
                if (isBlurDrawable(layers.getDrawable(i))) return true;
            }
        }
        Drawable current = drawable.getCurrent();
        return current != drawable && isBlurDrawable(current);
    }

    private static boolean hasWindowBlurRadius(View decor) {
        if (decor == null) return false;
        for (Class<?> type = decor.getClass(); type != null && type != View.class; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField("mBackgroundBlurRadius");
                field.setAccessible(true);
                return field.getInt(decor) > 0;
            } catch (NoSuchFieldException ignored) {
                // OEM DecorView subclasses can inherit the AOSP field.
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return false;
            }
        }
        return false;
    }
}
