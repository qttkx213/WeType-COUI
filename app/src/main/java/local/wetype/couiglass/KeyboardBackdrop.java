/* Regional blur setup adapted from mos-input-glass, Apache-2.0.
 * See THIRD_PARTY_NOTICES.md for the upstream revision and copyright.
 */
package local.wetype.couiglass;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.graphics.drawable.LayerDrawable;
import android.util.Log;
import android.view.RoundedCorner;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
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
    private final int[] panelLocation = new int[2];
    private Corners corners;
    private boolean dark;
    private float density;

    KeyboardBackdrop(Window window, View root) {
        this.window = window;
        this.root = root;
    }

    boolean apply(boolean dark, boolean enabled) {
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
        boolean blur = enabled && !blurFailed && root.isHardwareAccelerated();
        Corners nextCorners = corners(density);
        Drawable background = root.getBackground();
        if (installedRoot == null || background != installedRoot) originalRoot = background;
        if (installedRoot == null || this.dark != dark || this.density != density
                || !nextCorners.equals(corners) || usedBlur != blur) {
            if (installedRoot != null) installedRoot.setVisible(false, false);
            Drawable region = blur ? createBlurDrawable(density, nextCorners) : null;
            usedBlur = region != null;
            corners = nextCorners;
            this.dark = dark;
            this.density = density;
            installedRoot = new Panel(region, corners, CouiPalette.panel(dark, usedBlur), density, dark);
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

    private Corners corners(float density) {
        float maxCorner = Math.min(root.getWidth(), root.getHeight()) / 2f;
        float top = Math.min(20f * density, maxCorner);
        WindowInsets insets = root.getRootWindowInsets();
        if (insets == null) return new Corners(top, 0f, 0f);
        root.getLocationInWindow(panelLocation);
        int left = panelLocation[0];
        int right = left + root.getWidth();
        int bottom = panelLocation[1] + root.getHeight();
        return new Corners(top,
                bottomCorner(insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT), left, bottom, true, maxCorner),
                bottomCorner(insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT), right, bottom, false, maxCorner));
    }

    private static float bottomCorner(RoundedCorner corner, int side, int bottom, boolean left, float limit) {
        if (corner == null) return 0f;
        Point center = corner.getCenter();
        int radius = corner.getRadius();
        int outerSide = center.x + (left ? -radius : radius);
        int outerBottom = center.y + radius;
        if (Math.abs(side - outerSide) > 1 || Math.abs(bottom - outerBottom) > 1) return 0f;
        return Math.min(radius, limit);
    }

    private record Corners(float top, float bottomLeft, float bottomRight) {
        float[] radii() {
            return new float[]{top, top, top, top, bottomRight, bottomRight, bottomLeft, bottomLeft};
        }
    }

    // Only Android's compositor can sample the app below the screen-sized IME window.
    private Drawable createBlurDrawable(float density, Corners corners) {
        Drawable blur = null;
        try {
            Object viewRoot = View.class.getMethod("getViewRootImpl").invoke(root);
            if (viewRoot == null) return null;
            Method createRegion = viewRoot.getClass().getMethod("createBackgroundBlurDrawable");
            blur = (Drawable) createRegion.invoke(viewRoot);
            Class<?> type = blur.getClass();
            type.getMethod("setCornerRadius", float.class, float.class, float.class, float.class)
                    .invoke(blur, corners.top(), corners.top(), corners.bottomLeft(), corners.bottomRight());
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
        private final Corners corners;
        private final int color;
        private final KeyboardPanelLight light;
        private final Path shape = new Path();
        private int alpha = 255;

        Panel(Drawable blur, Corners corners, int color, float density, boolean dark) {
            super(blur);
            this.corners = corners;
            this.color = color;
            light = new KeyboardPanelLight(density, dark);
        }

        @Override protected void onBoundsChange(Rect bounds) {
            super.onBoundsChange(bounds);
            shape.rewind();
            if (!bounds.isEmpty()) {
                shape.addRoundRect(0f, 0f, bounds.width(), bounds.height(), corners.radii(), Path.Direction.CW);
            }
        }

        @Override public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            if (bounds.isEmpty()) return;
            if (canvas.isHardwareAccelerated()) super.draw(canvas);
            int saved = canvas.save();
            canvas.translate(bounds.left, bounds.top);
            light.draw(canvas, shape, bounds.width(), bounds.height(), corners.top(), corners.bottomLeft(),
                    corners.bottomRight(), CouiPalette.fade(color, alpha));
            canvas.restoreToCount(saved);
        }

        @Override public void setAlpha(int alpha) {
            if (this.alpha == alpha) return;
            this.alpha = alpha;
            super.setAlpha(alpha);
            invalidateSelf();
        }

        @Override public int getAlpha() { return alpha; }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
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
