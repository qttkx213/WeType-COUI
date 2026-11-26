package local.wetype.couiglass;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Path;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderNode;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import io.github.libxposed.api.XposedInterface;

/** Draws moving keys above the keyboard and filters the content behind their faces. */
final class KeyboardLayers {
    private final KeyboardStyle style;
    private final RenderNode keyboard = new RenderNode("WeType keyboard content");
    private final CouiBackdropBlur blurred = new CouiBackdropBlur();
    private final CandidateGridBackdrop candidateGrid = new CandidateGridBackdrop();
    private final ExpandedCandidates expandedCandidates;
    private final Map<Object, LiftedKey> lifted = new IdentityHashMap<>();
    private final List<LiftedKey> foreground = new ArrayList<>();
    private final Path blurMask = new Path();
    private final Path facePath = new Path();
    private final RectF faceBounds = new RectF();
    private final Matrix previewFit = new Matrix();
    private View root;
    private SymbolPopup popup;
    private boolean recording;

    KeyboardLayers(KeyboardStyle style) {
        this.style = style;
        expandedCandidates = new ExpandedCandidates(style);
        keyboard.setClipToBounds(false);
    }

    void bind(View view) { root = view; }
    void syncCandidateGrid() {
        expandedCandidates.sync(root);
        candidateGrid.sync(style, root, expandedCandidates.getWordList(), expandedCandidates.getDeleteKey());
    }
    ExpandedCandidates expandedCandidates() { return expandedCandidates; }
    CandidateGridBackdrop candidateGrid() { return candidateGrid; }

    void openPopup(View view, View body, Rect sourceKey) {
        if (root == null || !root.isHardwareAccelerated() || !(view.getParent() instanceof ViewGroup)) return;
        if (popup != null) popup.dispose();
        popup = new SymbolPopup(root, view, body, sourceKey);
        if (view.getVisibility() == View.VISIBLE) popup.open();
    }

    void closePopup(View view) {
        SymbolPopup active = popup;
        if (active == null || active.getView() != view) return;
        active.close(() -> {
            if (popup == active) popup = null;
            active.dispose();
        });
    }

    void popupVisibilityChanging(View view, int visibility) {
        if (popup == null || popup.getView() != view || popup.getClosing() || view.getVisibility() == visibility) return;
        if (visibility == View.VISIBLE) popup.open();
        else if (view.getVisibility() == View.VISIBLE) closePopup(view);
    }

    private SymbolPopup popupFor(View view) {
        if (popup != null && popup.getView() == view) return popup;
        SymbolPopup stroke = expandedCandidates.strokePopup();
        return stroke != null && stroke.getView() == view ? stroke : null;
    }

    boolean ownsPopup(View view) { return popupFor(view) != null; }

    void invalidatePopup(View view) {
        SymbolPopup active = popupFor(view);
        if (active != null) active.invalidate();
    }

    void observePopupTouch(View view, View source, MotionEvent event, int index) {
        SymbolPopup active = popupFor(view);
        if (active != null) active.observeTouch(source, event, index);
    }

    Object drawPopup(XposedInterface.Chain chain) throws Throwable {
        SymbolPopup active = popupFor((View) chain.getThisObject());
        return active == null ? chain.proceed() : active.drawHost(chain);
    }

    void clear() {
        if (popup != null) popup.dispose();
        popup = null;
        lifted.clear();
        foreground.clear();
        blurred.clear();
        candidateGrid.clear();
        expandedCandidates.clear();
        keyboard.discardDisplayList();
        if (root != null) root.invalidate();
    }

    Object draw(XposedInterface.Chain chain, View root, Canvas canvas) throws Throwable {
        foreground.clear();
        SymbolPopup visiblePopup = popup != null && (popup.getClosing() || popup.getView().isShown()) ? popup : null;
        SymbolPopup strokePopup = expandedCandidates.strokePopup();
        if (!canvas.isHardwareAccelerated() || root.getWidth() <= 0 || root.getHeight() <= 0
                || (!style.motion().hasOverflow() && visiblePopup == null && strokePopup == null)) {
            lifted.clear();
            return canvas == chain.getArg(0) ? chain.proceed() : chain.proceed(new Object[]{canvas});
        }
        int width = root.getWidth();
        int height = root.getHeight();
        keyboard.setPosition(0, 0, width, height);
        RecordingCanvas input = keyboard.beginRecording();
        recording = true;
        try {
            chain.proceed(new Object[]{input});
        } finally {
            recording = false;
            keyboard.endRecording();
        }

        // Exclude the raised face from the source. The IME window's regional blur is drawn
        // below both passes, so only same-window content is filtered here.
        blurMask.rewind();
        float blurStrength = 0f;
        var keys = lifted.entrySet().iterator();
        while (keys.hasNext()) {
            var entry = keys.next();
            LiftedKey key = entry.getValue();
            IconButtonMotion.Press press = style.motion().sample(entry.getKey());
            if (press == null || !press.overflows() || !style.visible(key.view)) {
                keys.remove();
                continue;
            }
            float strength = candidateGrid.owns(key.view) ? 0f : press.backdropStrength();
            placeKey(root, key, strength > 0f);
            blurStrength = Math.max(blurStrength, strength);
            foreground.add(key);
        }
        if (visiblePopup != null) {
            visiblePopup.addBackdrop(blurMask);
            blurStrength = Math.max(blurStrength, visiblePopup.getOpacity());
        }
        if (strokePopup != null) {
            strokePopup.addBackdrop(blurMask);
            blurStrength = Math.max(blurStrength, strokePopup.getOpacity());
        }

        if (blurMask.isEmpty() || blurStrength <= 0f || !blurred.draw(canvas, keyboard, blurMask, width, height,
                7f * root.getResources().getDisplayMetrics().density, blurStrength, Color.TRANSPARENT)) {
            canvas.drawRenderNode(keyboard);
        }
        for (LiftedKey key : foreground) {
            int saved = canvas.save();
            canvas.concat(key.mapping);
            canvas.drawRenderNode(key.content);
            canvas.restoreToCount(saved);
        }
        // The native stroke labels were excluded from the recording by drawPopup(Chain).
        if (strokePopup != null) strokePopup.drawPopup(canvas);
        return null;
    }

    boolean lift(View view, Object id, RectF face, float radius, IconButtonMotion.Press press,
                 XposedInterface.Chain chain) throws Throwable {
        if (!recording || !press.overflows() || face.isEmpty()
                || view.getWidth() <= 0 || view.getHeight() <= 0) {
            // Android can refresh a child's display list separately from its parent's recording.
            // If this draw stays in place, don't replay an older lifted recording over it.
            if (((Canvas) chain.getArg(0)).isHardwareAccelerated()) lifted.remove(id);
            return false;
        }
        LiftedKey key = lifted.computeIfAbsent(id, ignored -> new LiftedKey(view));
        key.face.set(face);
        key.radius = radius;
        float centerX = face.centerX();
        float centerY = face.centerY();
        key.content.setPosition(0, 0, view.getWidth(), view.getHeight());
        RecordingCanvas canvas = key.content.beginRecording();
        try {
            if (press != null) press.transform(canvas, centerX, centerY);
            Object[] arguments = chain.getArgs().toArray();
            arguments[0] = canvas;
            chain.proceed(arguments);
        } finally {
            key.content.endRecording();
        }
        if (press == null) key.transform.reset();
        else press.transform(key.transform, centerX, centerY);
        return true;
    }

    private void placeKey(View root, LiftedKey key, boolean blur) {
        RectF face = key.face;
        localToRoot(key.view, root, key.mapping);
        facePath.set(style.material().outline(face.width(), face.height(), key.radius));
        facePath.offset(face.left, face.top);
        facePath.transform(key.transform);
        facePath.transform(key.mapping);
        facePath.computeBounds(faceBounds, true);
        if (style.fitLiftedPreview(key.view, root, faceBounds, previewFit)) {
            key.mapping.postConcat(previewFit);
            facePath.transform(previewFit);
        } else {
            // Keep other raised faces inside the physical window.
            float horizontal = horizontalShift(faceBounds, root.getWidth());
            if (horizontal != 0f) {
                key.mapping.postTranslate(horizontal, 0f);
                facePath.offset(horizontal, 0f);
            }
        }
        if (blur) blurMask.addPath(facePath);
    }

    static float horizontalShift(RectF bounds, int width) {
        return Math.max(0f, -bounds.left) - Math.max(0f, bounds.right - width);
    }

    static Matrix localToRoot(View view, View root, Matrix into) {
        into.reset();
        view.transformMatrixToGlobal(into);
        root.transformMatrixToLocal(into);
        return into;
    }

    private static final class LiftedKey {
        final View view;
        final RenderNode content = new RenderNode("WeType moving key");
        final RectF face = new RectF();
        float radius;
        final Matrix transform = new Matrix();
        final Matrix mapping = new Matrix();

        LiftedKey(View view) {
            this.view = view;
            content.setClipToBounds(false);
        }
    }
}
