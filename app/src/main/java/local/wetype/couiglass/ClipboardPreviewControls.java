package local.wetype.couiglass;

import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.IdentityHashMap;

final class ClipboardPreviewControls {
    private static final String HOST = "com.tencent.wetype.plugin.hld.candidate.t";
    private static final int ICON = 0x7f090139;
    private final KeyboardStyle style;
    private final IdentityHashMap<View, TrackButton> buttons = new IdentityHashMap<>();
    private final Rect viewport = new Rect();
    private final Rect touchBounds = new Rect();
    private final int[] position = new int[2];
    private final Matrix coordinateMapping = new Matrix();
    private final float[] mappedPoint = new float[2];
    private final RectF chevron = new RectF();
    private final Drawable clearIcon = new ColorDrawable(Color.TRANSPARENT);
    private HorizontalScrollView track;
    private Rect originalClip;
    private boolean originalFade;
    private int originalFadeLength;
    private LinearLayout.LayoutParams overlapLayout;
    private int originalRightMargin;
    private IconMargin iconMargin;
    private Method findKey;
    private Method drawBounds;
    private boolean geometryUnavailable;
    private View innerIcon;
    private Drawable originalIconBackground;
    private View touchTarget;
    private View touchFace;
    private float downX;
    private float downY;
    private int touchSlop;
    private boolean canceledClick;
    private boolean dispatchingCancel;
    private ImageView arrow;
    private float arrowOffset;
    private int center = -1;

    ClipboardPreviewControls(KeyboardStyle style) { this.style = style; }

    int center() { return center; }

    boolean sync(View normal) {
        center = -1;
        View icon = normal != null && style.visible(normal) ? normal.findViewById(ICON) : null;
        if (icon == null || !style.visible(icon) || !(icon.getParent() instanceof LinearLayout row)
                || row.getChildAt(0) != icon) {
            boolean changed = restoreIconMargin();
            clear();
            return changed;
        }
        HorizontalScrollView nextTrack = trackOf(row);
        if (track != nextTrack) {
            releaseTrack();
            track = nextTrack;
            if (track != null) {
                originalClip = track.getClipBounds();
                originalFade = track.isHorizontalFadingEdgeEnabled();
                originalFadeLength = track.getHorizontalFadingEdgeLength();
                KeyboardStyle.setHorizontalScrollFade(track, 12);
            }
        }
        ImageView nextArrow = track == null ? null : findArrow(track);
        boolean changed = track != null && syncTrack(row, icon, nextArrow != null);
        if ((track != null || HOST.equals(row.getClass().getName()))
                && row.getChildAt(1) instanceof TextView preview && preview.getVisibility() == View.VISIBLE
                && preview.getLayoutParams() instanceof LinearLayout.LayoutParams layout
                && icon.getLayoutParams() instanceof LinearLayout.LayoutParams iconLayout) {
            if (preview.getHeight() > 0) {
                // The button can be moving. The arrow and close key use its laid-out center.
                row.getLocationInWindow(position);
                center = position[1] + preview.getTop() - row.getScrollY() + preview.getHeight() / 2;
            }
            changed |= alignToFirstLetter(row, icon, false);
            float density = preview.getResources().getDisplayMetrics().density;
            int inset = Math.round(8f * density);
            int left = Math.max(preview.getPaddingLeft(), inset);
            int right = Math.max(preview.getPaddingRight(), inset);
            if (left != preview.getPaddingLeft() || right != preview.getPaddingRight()) {
                preview.setPadding(left, preview.getPaddingTop(), right, preview.getPaddingBottom());
                changed = true;
            }
            // Preserve the gap and vertical alignment for text and image previews.
            int gap = Math.round(4f * density);
            if (layout.leftMargin < gap) {
                layout.leftMargin = gap;
                preview.setLayoutParams(layout);
                changed = true;
            }
            int gravity = (Math.max(iconLayout.gravity, 0) & ~Gravity.VERTICAL_GRAVITY_MASK) | Gravity.CENTER_VERTICAL;
            if (iconLayout.gravity != gravity) {
                iconLayout.gravity = gravity;
                icon.setLayoutParams(iconLayout);
                changed = true;
            }
        } else if (track != null && entryIcon(icon) != null) {
            changed |= alignToFirstLetter(row, icon, true);
        } else {
            changed |= restoreIconMargin();
        }
        alignArrow(nextArrow);
        return changed;
    }

    private boolean alignToFirstLetter(LinearLayout row, View icon, boolean entry) {
        if (row.getLayoutDirection() != View.LAYOUT_DIRECTION_LTR || icon.getWidth() <= 0
                || !(icon.getLayoutParams() instanceof LinearLayout.LayoutParams layout) || layout.isMarginRelative()) {
            return restoreIconMargin();
        }
        // Read the anchor in the track's viewport so it stays put when the row is scrolled.
        View anchorSpace = track == null ? row : track;
        int rowLeft = track == null ? 0 : row.getLeft();
        int available = anchorSpace.getWidth() - rowLeft - row.getPaddingLeft() - row.getPaddingRight()
                - (track == null ? 0 : track.getPaddingRight());
        float x = firstLetterX(anchorSpace, entry);
        if (!Float.isFinite(x) || available < icon.getWidth()) return restoreIconMargin();
        int desired = Math.round(x - rowLeft - row.getPaddingLeft() - (entry ? 0f : icon.getWidth() / 2f));
        int margin = Math.max(0, Math.min(desired, available - icon.getWidth()));
        boolean changed = iconMargin != null && (iconMargin.view() != icon || iconMargin.layout() != layout)
                && restoreIconMargin();
        if (layout.leftMargin == margin) return changed;
        int original = iconMargin != null && layout.leftMargin == iconMargin.applied()
                ? iconMargin.original() : layout.leftMargin;
        iconMargin = new IconMargin(icon, layout, original, margin);
        layout.leftMargin = margin;
        icon.setLayoutParams(layout);
        return true;
    }

    private float firstLetterX(View target, boolean entry) {
        View root = style.animationRoot();
        if (root == null || geometryUnavailable) return Float.NaN;
        View keyboard = findLetterKeyboard(root);
        if (keyboard == null) return Float.NaN;
        try {
            if (findKey == null) {
                findKey = keyboard.getClass().getMethod("w1", String.class, boolean.class);
                drawBounds = DrawHooks.method(findKey.getReturnType(), "t", Rect.class);
            }
            String id = AlphaKeyLabels.keyPrefix(keyboard.getClass().getName()) + "q";
            Object key = findKey.invoke(keyboard, id, false);
            Rect bounds = key == null ? null : (Rect) drawBounds.invoke(key);
            if (bounds == null || bounds.isEmpty()) return Float.NaN;
            mappedPoint[0] = entry ? bounds.left : bounds.exactCenterX();
            mappedPoint[1] = bounds.exactCenterY();
            KeyboardLayers.localToRoot(keyboard, target, coordinateMapping).mapPoints(mappedPoint);
            return mappedPoint[0];
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            geometryUnavailable = true;
            return Float.NaN;
        }
    }

    private static View findLetterKeyboard(View view) {
        if (view.getVisibility() != View.VISIBLE || view.getAlpha() == 0f) return null;
        if (AlphaKeyLabels.keyPrefix(view.getClass().getName()) != null && view.getWidth() > 0 && view.getHeight() > 0) {
            return view;
        }
        if (view instanceof ViewGroup group) {
            for (int index = group.getChildCount() - 1; index >= 0; index--) {
                View keyboard = findLetterKeyboard(group.getChildAt(index));
                if (keyboard != null) return keyboard;
            }
        }
        return null;
    }

    private boolean restoreIconMargin() {
        IconMargin previous = iconMargin;
        iconMargin = null;
        if (previous == null || previous.view().getLayoutParams() != previous.layout()
                || previous.layout().leftMargin != previous.applied() || previous.applied() == previous.original()) {
            return false;
        }
        previous.layout().leftMargin = previous.original();
        previous.view().setLayoutParams(previous.layout());
        return true;
    }

    static boolean ownsButton(View view) {
        if (!(view.getParent() instanceof LinearLayout row)) return false;
        if (isTrackButton(view) && trackOf(row) != null) return true;
        return row.getId() == ICON && entryIcon(row) == view
                && row.getParent() instanceof LinearLayout content && trackOf(content) != null;
    }

    private static HorizontalScrollView trackOf(LinearLayout row) {
        return row.getParent() instanceof HorizontalScrollView scroll && scroll.getChildAt(0) == row
                && scroll.getParent() instanceof LinearLayout host && HOST.equals(host.getClass().getName())
                && host.getChildAt(0) == scroll ? scroll : null;
    }

    private static boolean isTrackButton(View view) { return view.getId() == ICON || view instanceof TextView; }

    private static View entryIcon(View entry) {
        return entry instanceof LinearLayout group && group.getChildCount() == 2
                && group.getChildAt(0) instanceof ViewGroup icon && group.getChildAt(1) instanceof TextView ? icon : null;
    }

    private boolean syncTrack(LinearLayout row, View icon, boolean hasArrow) {
        // The stock negative margin lets the track draw into the separator in front of the arrow.
        if (!hasArrow || track.getLayoutParams() != overlapLayout) restoreMargin();
        boolean relayout = false;
        if (hasArrow && track.getLayoutParams() instanceof LinearLayout.LayoutParams layout && layout.rightMargin < 0) {
            overlapLayout = layout;
            originalRightMargin = layout.rightMargin;
            layout.rightMargin = 0;
            track.setLayoutParams(layout);
            relayout = true;
        }
        // Keep the scroll content clipped to its own slot, even while a preview is lifted.
        if (viewport.right != track.getWidth() || viewport.bottom != track.getHeight() || viewport.isEmpty()) {
            viewport.set(0, 0, track.getWidth(), track.getHeight());
            track.setClipBounds(viewport);
        }
        var iterator = buttons.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            View view = entry.getKey();
            if (view.getParent() == row && isVisibleButton(view)) continue;
            restoreButton(view, entry.getValue());
            iterator.remove();
        }
        syncInnerIcon(entryIcon(icon));
        for (int index = 0; index < row.getChildCount(); index++) {
            View view = row.getChildAt(index);
            if (!isTrackButton(view) || !isVisibleButton(view)) continue;
            Drawable original = view.getBackground();
            TrackButton button = buttons.get(view);
            if (button != null && original == button.face()) continue;
            // Lift the preview text and face together. The clipboard icon stays in the track.
            ButtonBackground face = new ButtonBackground(style, view, original, view instanceof TextView);
            buttons.put(view, new TrackButton(original, face));
            KeyboardStyle.setBackground(view, face);
        }
        return relayout;
    }

    // Both the lifted render node and its blur mask use this root-space correction.
    boolean fitLiftedPreview(View view, View root, RectF bounds, Matrix correction) {
        TrackButton button = buttons.get(view);
        if (track == null || button == null || !button.face().liftMotion || bounds.isEmpty()) return false;

        float right = root.getWidth();
        float bottom = root.getHeight();
        if (arrow != null && style.visible(arrow) && !viewport.isEmpty()) {
            mappedPoint[0] = viewport.right;
            mappedPoint[1] = viewport.exactCenterY();
            KeyboardLayers.localToRoot(track, root, coordinateMapping).mapPoints(mappedPoint);
            if (mappedPoint[0] > 0f) right = Math.min(right, mappedPoint[0]);
        }
        if (right <= 0f || bottom <= 0f) return false;

        // Normally only translation is needed. An overwide key fits as one piece.
        float scale = Math.min(1f, Math.min(right / bounds.width(), bottom / bounds.height()));
        float left = Math.max(0f, Math.min(bounds.left, right - bounds.width() * scale));
        float top = Math.max(0f, Math.min(bounds.top, bottom - bounds.height() * scale));
        correction.setScale(scale, scale, bounds.left, bounds.top);
        correction.postTranslate(left - bounds.left, top - bounds.top);
        return true;
    }

    private static boolean isVisibleButton(View view) {
        return view.getVisibility() == View.VISIBLE && view.isClickable() && view.getBackground() != null;
    }

    private void syncInnerIcon(View next) {
        if (innerIcon != next) {
            if (innerIcon != null && innerIcon.getBackground() == clearIcon) {
                KeyboardStyle.setBackground(innerIcon, originalIconBackground);
            }
            innerIcon = next;
            originalIconBackground = null;
        }
        // The host reuses the icon from the single preview in the clickable multi-clip entry.
        if (innerIcon != null && innerIcon.getBackground() != clearIcon) {
            originalIconBackground = innerIcon.getBackground();
            if (originalIconBackground != null) KeyboardStyle.setBackground(innerIcon, clearIcon);
        }
    }

    boolean observeButton(View root, View view, MotionEvent event, boolean handled) {
        int action = event.getActionMasked();
        if (view == touchTarget && action != MotionEvent.ACTION_DOWN) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL && !dispatchingCancel) {
                if (action == MotionEvent.ACTION_CANCEL) {
                    IconButtonMotion.Press press = style.motion().sample(touchFace);
                    if (press != null && press.getPointerId() >= 0) press.release(null);
                }
                touchTarget = null;
                touchFace = null;
            } else if (!canceledClick && (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_MOVE
                    && (Math.abs(event.getRawX() - downX) > touchSlop || Math.abs(event.getRawY() - downY) > touchSlop))) {
                // Cancel the native click while the root keeps feeding the real gesture to the spring.
                MotionEvent cancel = MotionEvent.obtain(event);
                canceledClick = true;
                dispatchingCancel = true;
                try {
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    view.dispatchTouchEvent(cancel);
                } finally {
                    dispatchingCancel = false;
                    cancel.recycle();
                }
            }
            return true;
        }
        View face = view == innerIcon && view.getParent() instanceof View parent ? parent : view;
        TrackButton button = buttons.get(face);
        if (button == null || face.getBackground() != button.face()) return false;
        if (action == MotionEvent.ACTION_DOWN && handled && touchTarget == null && face.isEnabled()
                && style.visible(face) && face.getWidth() > 0 && face.getHeight() > 0) {
            touchTarget = view;
            touchFace = face;
            downX = event.getRawX();
            downY = event.getRawY();
            touchSlop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
            canceledClick = false;
            view.getParent().requestDisallowInterceptTouchEvent(true);
            touchBounds.set(0, 0, face.getWidth(), face.getHeight());
            style.motion().down(root, face, touchBounds, event, 0);
        }
        return true;
    }

    private ImageView findArrow(HorizontalScrollView scroll) {
        LinearLayout host = (LinearLayout) scroll.getParent();
        View last = host.getChildAt(host.getChildCount() - 1);
        if (last != scroll && last instanceof ViewGroup slot && slot.isClickable() && slot.getChildCount() == 1
                && slot.getChildAt(0) instanceof ImageView image && style.visible(image)) return image;
        return null;
    }

    private void alignArrow(ImageView next) {
        if (arrow != next) {
            if (arrow != null && arrowOffset != 0f) arrow.setTranslationY(arrow.getTranslationY() - arrowOffset);
            arrow = next;
            arrowOffset = 0f;
        }
        if (arrow == null || center < 0) return;
        arrow.getLocationInWindow(position);
        float centerY = arrow.getHeight() / 2f;
        Drawable glyph = arrow.getDrawable();
        if (glyph != null && !glyph.getBounds().isEmpty()) {
            chevron.set(glyph.getBounds());
            arrow.getImageMatrix().mapRect(chevron);
            // The host chevron spans y=27.4 to 53 in its 72 px vector viewport.
            centerY = arrow.getPaddingTop() + chevron.top + chevron.height() * (40.2f / 72f);
        }
        int delta = Math.round(center - position[1] - centerY);
        if (delta == 0) return;
        arrow.setTranslationY(arrow.getTranslationY() + delta);
        arrowOffset += delta;
    }

    void clear() {
        center = -1;
        alignArrow(null);
        restoreIconMargin();
        releaseTrack();
    }

    private void releaseTrack() {
        if (track == null) return;
        buttons.forEach(this::restoreButton);
        buttons.clear();
        syncInnerIcon(null);
        restoreMargin();
        track.setClipBounds(originalClip);
        if (originalFadeLength > 0) track.setFadingEdgeLength(originalFadeLength);
        track.setHorizontalFadingEdgeEnabled(originalFade);
        track = null;
        originalClip = null;
        viewport.setEmpty();
    }

    private void restoreMargin() {
        if (overlapLayout == null) return;
        if (track.getLayoutParams() == overlapLayout && overlapLayout.rightMargin == 0) {
            overlapLayout.rightMargin = originalRightMargin;
            track.setLayoutParams(overlapLayout);
        }
        overlapLayout = null;
    }

    private void restoreButton(View view, TrackButton button) {
        if (touchFace == view) {
            touchTarget = null;
            touchFace = null;
            track.requestDisallowInterceptTouchEvent(false);
        }
        style.motion().cancelUnder(view);
        if (view.getBackground() == button.face()) KeyboardStyle.setBackground(view, button.original());
    }

    private record IconMargin(View view, LinearLayout.LayoutParams layout, int original, int applied) {}
    private record TrackButton(Drawable original, ButtonBackground face) {}
}
