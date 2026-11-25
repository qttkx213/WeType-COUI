package local.wetype.couiglass;

import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
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
    private final RenderNode blurred = new RenderNode("WeType key backdrop");
    private final Map<Object, LiftedKey> lifted = new IdentityHashMap<>();
    private final List<LiftedKey> foreground = new ArrayList<>();
    private final Path blurMask = new Path();
    private final Path facePath = new Path();
    private final RectF faceBounds = new RectF();
    private final Rect sampleBounds = new Rect();
    private final Paint blurMix = new Paint();
    private View root;
    private SymbolPopup popup;
    private boolean recording;
    private float blurRadius;

    KeyboardLayers(KeyboardStyle style) {
        this.style = style;
        keyboard.setClipToBounds(false);
        blurMix.setBlendMode(BlendMode.PLUS);
    }

    void bind(View view) { root = view; }

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
        if (!ownsPopup(view) || popup.getClosing() || view.getVisibility() == visibility) return;
        if (visibility == View.VISIBLE) popup.open();
        else if (view.getVisibility() == View.VISIBLE) closePopup(view);
    }

    boolean ownsPopup(View view) { return popup != null && popup.getView() == view; }

    void invalidatePopup(View view) {
        if (ownsPopup(view)) popup.invalidate();
    }

    void observePopupTouch(View view, View source, MotionEvent event, int index) {
        if (ownsPopup(view)) popup.observeTouch(source, event, index);
    }

    Object drawPopup(XposedInterface.Chain chain) throws Throwable {
        return popup.drawHost(chain);
    }

    void clear() {
        if (popup != null) popup.dispose();
        popup = null;
        lifted.clear();
        foreground.clear();
        blurred.discardDisplayList();
        keyboard.discardDisplayList();
        if (root != null) root.invalidate();
    }

    Object draw(XposedInterface.Chain chain, View root, Canvas canvas) throws Throwable {
        foreground.clear();
        SymbolPopup visiblePopup = popup != null && (popup.getClosing() || popup.getView().isShown()) ? popup : null;
        if (!canvas.isHardwareAccelerated() || root.getWidth() <= 0 || root.getHeight() <= 0
                || (!style.motion().hasOverflow() && visiblePopup == null)) {
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
            float strength = press.backdropStrength();
            placeKey(root, key, strength > 0f);
            blurStrength = Math.max(blurStrength, strength);
            foreground.add(key);
        }
        if (visiblePopup != null) {
            visiblePopup.addBackdrop(blurMask);
            blurStrength = Math.max(blurStrength, visiblePopup.getOpacity());
        }

        if (blurMask.isEmpty() || blurStrength <= 0f || !drawBlur(canvas, root, blurStrength)) {
            canvas.drawRenderNode(keyboard);
        }
        for (LiftedKey key : foreground) {
            int saved = canvas.save();
            canvas.concat(key.mapping);
            canvas.drawRenderNode(key.content);
            canvas.restoreToCount(saved);
        }
        return null;
    }

    private boolean drawBlur(Canvas canvas, View root, float strength) {
        float radius = 7f * root.getResources().getDisplayMetrics().density;
        blurMask.computeBounds(faceBounds, true);
        // Include neighboring pixels in the Gaussian kernel, even outside the face.
        faceBounds.roundOut(sampleBounds);
        int padding = (int) Math.ceil(3f * radius);
        sampleBounds.inset(-padding, -padding);
        if (!sampleBounds.intersect(0, 0, root.getWidth(), root.getHeight())) return false;
        boolean moved = blurred.setPosition(sampleBounds);
        if (radius != blurRadius) {
            blurRadius = radius;
            // The window blur is below this layer. Keep empty pixels transparent and tint the face once.
            blurred.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP));
        }
        if (moved || !blurred.hasDisplayList()) {
            RecordingCanvas sample = blurred.beginRecording();
            try {
                sample.translate(-sampleBounds.left, -sampleBounds.top);
                sample.drawRenderNode(keyboard);
            } finally {
                blurred.endRecording();
            }
        }
        int saved = canvas.save();
        canvas.clipOutPath(blurMask);
        canvas.drawRenderNode(keyboard);
        canvas.restoreToCount(saved);

        saved = canvas.save();
        canvas.clipPath(blurMask);
        if (strength >= .999f) {
            canvas.drawRenderNode(blurred);
        } else {
            // Blend source pixels with their blurred counterpart before composing above the IME backdrop.
            int mix = canvas.saveLayer(faceBounds, null);
            int alpha = Math.round(strength * 255f);
            int sharp = canvas.saveLayerAlpha(faceBounds, 255 - alpha);
            canvas.drawRenderNode(keyboard);
            canvas.restoreToCount(sharp);
            blurMix.setAlpha(alpha);
            int soft = canvas.saveLayer(faceBounds, blurMix);
            canvas.drawRenderNode(blurred);
            canvas.restoreToCount(soft);
            canvas.restoreToCount(mix);
        }
        canvas.restoreToCount(saved);
        return true;
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
        // The physical window is the remaining clip. Keep the whole face inside it on either side.
        float horizontal = Math.max(0f, -faceBounds.left) - Math.max(0f, faceBounds.right - root.getWidth());
        if (horizontal != 0f) {
            key.mapping.postTranslate(horizontal, 0f);
            facePath.offset(horizontal, 0f);
        }
        if (blur) blurMask.addPath(facePath);
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
