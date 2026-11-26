package local.wetype.couiglass;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.util.StateSet;
import android.view.View;

import java.lang.ref.WeakReference;

final class ButtonBackground extends Drawable {
    private static final int[] ENABLED = {android.R.attr.state_enabled};
    private static final int[] ACTIVE = {android.R.attr.state_pressed};
    private static final int[] SELECTED = {android.R.attr.state_selected};
    private static final int[] CHECKED = {android.R.attr.state_checked};

    private final KeyboardStyle style;
    private final WeakReference<View> owner;
    private final Drawable original;
    private final float density;
    private final SelectionSpring choiceSelection;
    final boolean liftMotion;
    private boolean choiceInitialized;
    private boolean choiceSelected;
    private int alpha = 255;
    private int digitFill = Color.WHITE;

    static ButtonBackground find(Drawable background) {
        while (background instanceof InsetDrawable inset) background = inset.getDrawable();
        return background instanceof ButtonBackground button ? button : null;
    }

    ButtonBackground(KeyboardStyle style, View view, Drawable original) {
        this(style, view, original, true);
    }

    ButtonBackground(KeyboardStyle style, View view, Drawable original, boolean liftMotion) {
        this.style = style;
        this.original = original;
        this.liftMotion = liftMotion;
        owner = new WeakReference<>(view);
        density = view.getResources().getDisplayMetrics().density;
        choiceSelection = view.getClass().getName().endsWith(".S10SettingItemView") && style.inChooser(view)
                ? new SelectionSpring(this::invalidateSelf) : null;
    }

    void setDigitFill(int fill) {
        if (digitFill == fill) return;
        digitFill = fill;
        invalidateSelf();
    }

    @Override
    public void draw(Canvas canvas) {
        Rect face = getBounds();
        int[] state = getState();
        boolean enabled = StateSet.stateSetMatches(ENABLED, state);
        boolean pressed = StateSet.stateSetMatches(ACTIVE, state);
        boolean selected = StateSet.stateSetMatches(SELECTED, state) || StateSet.stateSetMatches(CHECKED, state);
        View view = owner.get();
        IconButtonMotion.Press motion = style.motion().sample(view);
        int opacity = enabled ? alpha : alpha / 2;
        opacity = (opacity * Color.alpha(digitFill) + 127) / 255;
        boolean dark = CouiPalette.darkKey(style.dark(), digitFill);
        if (choiceSelection == null) {
            style.material().draw(canvas, face.left, face.top, face.right, face.bottom, density,
                    opacity, pressed || selected, dark, motion);
        } else {
            if (!choiceInitialized) {
                choiceInitialized = true;
                choiceSelected = selected;
                choiceSelection.snapTo(selected ? 1f : 0f);
            } else if (choiceSelected != selected) {
                choiceSelected = selected;
                choiceSelection.animateTo(selected ? 1f : 0f);
            }
            float press = motion == null ? pressed ? 1f : 0f : motion.getStrength();
            int indicator = Math.round(opacity * Math.max(press, Math.min(1f, choiceSelection.getValue())));
            style.material().draw(canvas, face.left, face.top, face.right, face.bottom, density,
                    KeySurface.radius(face.width(), face.height(), density), CouiGlass.indicator(dark, indicator),
                    indicator, false, dark, motion);
        }
    }

    @Override public boolean isStateful() { return true; }

    @Override protected boolean onStateChange(int[] state) {
        invalidateSelf();
        return true;
    }

    @Override public int getMinimumWidth() { return original == null ? 0 : original.getMinimumWidth(); }
    @Override public int getMinimumHeight() { return original == null ? 0 : original.getMinimumHeight(); }
    @Override public int getIntrinsicWidth() { return original == null ? -1 : original.getIntrinsicWidth(); }
    @Override public int getIntrinsicHeight() { return original == null ? -1 : original.getIntrinsicHeight(); }
    @Override public boolean getPadding(Rect padding) { return original != null && original.getPadding(padding); }
    @Override public void setAlpha(int alpha) { this.alpha = alpha; invalidateSelf(); }
    @Override public int getAlpha() { return alpha; }
    @Override public void setColorFilter(ColorFilter filter) {}
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
