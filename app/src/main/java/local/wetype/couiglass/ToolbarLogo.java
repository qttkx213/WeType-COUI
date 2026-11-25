package local.wetype.couiglass;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.StateSet;
import android.view.View;
import android.widget.ImageView;

import java.lang.ref.WeakReference;

/** Draws the stock logo glyph over the TopAppBar face, with the baked-in disc removed. */
final class ToolbarLogo extends Drawable {
    private static final int[] PRESSED = {android.R.attr.state_pressed};
    private static final int[] SELECTED = {android.R.attr.state_selected};
    private static final int SIZE = 96;
    private final KeyboardStyle style;
    private final WeakReference<ImageView> image;
    final Drawable original;
    private final Bitmap glyph;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final float density;

    static ToolbarLogo create(KeyboardStyle style, ImageView view, Drawable original) {
        if (original.getIntrinsicWidth() <= 0 || original.getIntrinsicHeight() <= 0) return null;
        Bitmap image = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Rect initial = new Rect(original.getBounds());
        try {
            original.setBounds(0, 0, SIZE, SIZE);
            original.draw(new Canvas(image));
        } finally {
            original.setBounds(initial);
        }
        // The four stock WeType logo vectors have a white disc of fixed opacity. Other toolbar
        // glyphs (e.g. the back arrow) are already transparent and need only a new face.
        int base = image.getPixel(SIZE / 2, 8);
        int baseAlpha = Color.alpha(base);
        if (baseAlpha > 30 && baseAlpha < 248 && base == image.getPixel(8, SIZE / 2)
                && (base & 0xFFFFFF) == 0xFFFFFF) {
            int[] pixels = new int[SIZE * SIZE];
            image.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE);
            for (int i = 0; i < pixels.length; i++) {
                int pixel = pixels[i];
                int alpha = Color.alpha(pixel);
                if (alpha <= baseAlpha) {
                    pixels[i] = 0;
                    continue;
                }
                int foreground = 255 * (alpha - baseAlpha) / (255 - baseAlpha);
                int white = alpha - foreground;
                pixels[i] = Color.argb(foreground, removeWhite(Color.red(pixel), alpha, foreground, white),
                        removeWhite(Color.green(pixel), alpha, foreground, white),
                        removeWhite(Color.blue(pixel), alpha, foreground, white));
            }
            image.setPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE);
        }
        return new ToolbarLogo(style, view, original, image);
    }

    private static int removeWhite(int channel, int alpha, int foreground, int white) {
        return Math.max(0, Math.min(255, (channel * alpha - white * 255) / foreground));
    }

    private ToolbarLogo(KeyboardStyle style, ImageView view, Drawable original, Bitmap glyph) {
        this.style = style;
        this.original = original;
        this.glyph = glyph;
        image = new WeakReference<>(view);
        density = view.getResources().getDisplayMetrics().density;
    }

    @Override public void draw(Canvas canvas) {
        Rect r = getBounds();
        ImageView view = image.get();
        View owner = view != null && view.getParent() instanceof View parent ? parent : view;
        boolean active = (owner != null && owner.isPressed()) || StateSet.stateSetMatches(PRESSED, getState())
                || StateSet.stateSetMatches(SELECTED, getState());
        IconButtonMotion.Press motion = style.motion().sample(view);
        style.material().draw(canvas, r.left, r.top, r.right, r.bottom, density, paint.getAlpha(),
                active, style.dark(), motion);
        canvas.drawBitmap(glyph, null, r, paint);
    }

    @Override public boolean isStateful() { return true; }
    @Override protected boolean onStateChange(int[] state) { invalidateSelf(); return true; }
    @Override public int getIntrinsicWidth() { return original.getIntrinsicWidth(); }
    @Override public int getIntrinsicHeight() { return original.getIntrinsicHeight(); }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public int getAlpha() { return paint.getAlpha(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
