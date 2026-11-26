package local.wetype.couiglass;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Path;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderNode;
import android.view.View;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/** The native candidate grid scrolls underneath its sibling delete key. */
final class CandidateGridBackdrop {
    private static final int WORD_LIST = 0x7f0904af;

    private final RenderNode words = new RenderNode("WeType expanded candidates");
    private final CouiBackdropBlur blur = new CouiBackdropBlur();
    private final Path mask = new Path();
    private final Matrix transform = new Matrix();
    private final Matrix intoList = new Matrix();
    private final Matrix lastTransform = new Matrix();
    private final Rect lastBounds = new Rect();
    private final Rect keyBounds = new Rect();
    private final RectF movingBounds = new RectF();
    private View list;
    private View key;

    static void install(WeTypeModule module, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> recycler = Class.forName("androidx.recyclerview.widget.RecyclerView", false, loader);
        Method draw = DrawHooks.method(recycler, "draw", void.class, Canvas.class);
        module.intercept(draw, chain -> {
            View list = (View) chain.getThisObject();
            KeyboardStyle style = module.style();
            if (style == null || list.getId() != WORD_LIST) return chain.proceed();
            return style.layers().candidateGrid().draw(chain, list, (Canvas) chain.getArg(0));
        });
    }

    void sync(KeyboardStyle style, View root, View nextList, View nextKey) {
        if (nextList == null || !style.visible(nextList) || nextKey == null || !style.visible(nextKey)
                || !(nextKey.getBackground() instanceof ButtonBackground face)) {
            clear();
            return;
        }
        keyBounds.set(face.getBounds());
        if (keyBounds.isEmpty()) keyBounds.set(0, 0, nextKey.getWidth(), nextKey.getHeight());
        transform.reset();
        IconButtonMotion.Press press = style.motion().sample(nextKey);
        if (press != null) press.transform(transform, keyBounds.exactCenterX(), keyBounds.exactCenterY());
        if (press != null && press.overflows()) {
            transform.postConcat(KeyboardLayers.localToRoot(nextKey, root, intoList));
            movingBounds.set(keyBounds);
            transform.mapRect(movingBounds);
            transform.postTranslate(KeyboardLayers.horizontalShift(movingBounds, root.getWidth()), 0f);
            transform.postConcat(KeyboardLayers.localToRoot(root, nextList, intoList));
        } else {
            transform.postConcat(KeyboardLayers.localToRoot(nextKey, nextList, intoList));
        }
        if (list == nextList && key == nextKey
                && lastBounds.equals(keyBounds) && lastTransform.equals(transform)) return;
        if (list != nextList || key != nextKey) clear();
        list = nextList;
        key = nextKey;
        lastBounds.set(keyBounds);
        lastTransform.set(transform);
        float density = key.getResources().getDisplayMetrics().density;
        mask.set(style.material().outline(keyBounds.width(), keyBounds.height(),
                KeySurface.radius(keyBounds.width(), keyBounds.height(), density)));
        mask.offset(keyBounds.left, keyBounds.top);
        mask.transform(transform);
        list.invalidate();
    }

    boolean owns(View view) { return key == view; }

    void clear() {
        if (list == null) return;
        list.invalidate();
        list = null;
        key = null;
        words.discardDisplayList();
        blur.clear();
    }

    private Object draw(XposedInterface.Chain chain, View view, Canvas canvas) throws Throwable {
        if (view != list || !canvas.isHardwareAccelerated() || view.getWidth() <= 0 || view.getHeight() <= 0) {
            return chain.proceed();
        }
        int width = view.getWidth();
        int height = view.getHeight();
        words.setPosition(0, 0, width, height);
        RecordingCanvas source = words.beginRecording();
        try {
            chain.proceed(new Object[]{source});
        } finally {
            words.endRecording();
        }
        int saved = canvas.save();
        try {
            // Keep the effect inside the attached list even when the key moves over its edge.
            canvas.clipRect(0, 0, width, height);
            float radius = 10f * view.getResources().getDisplayMetrics().density;
            if (!blur.draw(canvas, words, mask, width, height, radius, 1f, Color.TRANSPARENT)) {
                canvas.drawRenderNode(words);
            }
        } finally {
            canvas.restoreToCount(saved);
        }
        return null;
    }
}
