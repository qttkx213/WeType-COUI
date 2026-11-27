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
import android.view.ViewGroup;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/** Contains the scrolling word faces and lets the delete key sample the words beneath it. */
final class CandidateGridBackdrop {
    private static final int SCROLL_LIST = 0x7f0904af;

    private final RenderNode words = new RenderNode("WeType expanded candidates");
    private final CouiBackdropBlur blur = new CouiBackdropBlur();
    private final Path mask = new Path();
    private final Matrix transform = new Matrix();
    private final Matrix intoList = new Matrix();
    private final Matrix lastTransform = new Matrix();
    private final Rect lastBounds = new Rect();
    private final ListEdgeFade edges = new ListEdgeFade();
    private final Matrix deleteFit = new Matrix();
    private final Rect keyBounds = new Rect();
    private final RectF movingBounds = new RectF();
    private View list;
    private View key;

    static void install(WeTypeModule module, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> recycler = Class.forName("androidx.recyclerview.widget.RecyclerView", false, loader);
        Method draw = DrawHooks.method(recycler, "draw", void.class, Canvas.class);
        module.intercept(draw, chain -> {
            ViewGroup list = (ViewGroup) chain.getThisObject();
            KeyboardStyle style = module.style();
            if (style == null || list.getId() != SCROLL_LIST) return chain.proceed();
            Canvas canvas = (Canvas) chain.getArg(0);
            CandidateGridBackdrop grid = style.layers().candidateGrid();
            return list == grid.list ? grid.draw(chain, style, list, canvas)
                    : style.drawClipboardList(chain, list, canvas);
        });
        Method drawView = DrawHooks.method(View.class, "draw", void.class, Canvas.class);
        // Apply the inset inside DrawHooks' motion transform, including on the lifted canvas.
        module.hook(drawView).setPriority(XposedInterface.PRIORITY_DEFAULT - 1)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    View view = (View) chain.getThisObject();
                    if (view.getId() != ExpandedCandidates.DELETE) return chain.proceed();
                    KeyboardStyle style = module.style();
                    if (style == null) return chain.proceed();
                    return style.layers().candidateGrid().drawDelete(chain, view, (Canvas) chain.getArg(0));
                });
    }

    void sync(KeyboardStyle style, View root, View nextList, View nextKey) {
        if (nextList == null || !style.visible(nextList)) {
            clear();
            return;
        }
        ButtonBackground face = nextKey != null && style.visible(nextKey) && nextKey.getWidth() > 0
                && nextKey.getHeight() > 0 && nextKey.getBackground() instanceof ButtonBackground button
                ? button : null;
        View activeKey = face == null ? null : nextKey;
        boolean rebound = list != nextList || key != activeKey;
        if (rebound) {
            clear();
            list = nextList;
            key = activeKey;
            list.invalidate();
            if (key != null) key.invalidate();
        }
        if (face == null) return;
        keyBounds.set(face.getBounds());
        if (keyBounds.isEmpty()) keyBounds.set(0, 0, key.getWidth(), key.getHeight());
        transform.reset();
        IconButtonMotion.Press press = style.motion().sample(key);
        if (press != null) press.transform(transform, keyBounds.exactCenterX(), keyBounds.exactCenterY());
        if (press != null && press.overflows()) {
            transform.postConcat(KeyboardLayers.localToRoot(key, root, intoList));
            movingBounds.set(keyBounds);
            transform.mapRect(movingBounds);
            transform.postTranslate(KeyboardLayers.horizontalShift(movingBounds, root.getWidth()), 0f);
            transform.postConcat(KeyboardLayers.localToRoot(root, list, intoList));
        } else {
            transform.postConcat(KeyboardLayers.localToRoot(key, list, intoList));
        }
        float centerX = key.getWidth() / 2f;
        float centerY = key.getHeight() / 2f;
        float inset = Math.min(ExpandedCandidatesKt.dp(key, 2), Math.min(centerX, centerY) / 2f);
        deleteFit.setScale(1f - inset / centerX, 1f - inset / centerY, centerX, centerY);
        transform.preConcat(deleteFit);
        if (!rebound && lastBounds.equals(keyBounds) && lastTransform.equals(transform)) return;
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
        if (key != null) key.invalidate();
        list = null;
        key = null;
        words.discardDisplayList();
        blur.clear();
    }

    private Object drawDelete(XposedInterface.Chain chain, View view, Canvas canvas) throws Throwable {
        if (view != key) return chain.proceed();
        int saved = canvas.save();
        try {
            canvas.concat(deleteFit);
            return chain.proceed();
        } finally {
            canvas.restoreToCount(saved);
        }
    }

    private Object draw(XposedInterface.Chain chain, KeyboardStyle style, ViewGroup view, Canvas canvas)
            throws Throwable {
        if (view != list || view.getWidth() <= 0 || view.getHeight() <= 0) return chain.proceed();
        int width = view.getWidth();
        int height = view.getHeight();
        edges.setViewport(view);
        int inset = ExpandedCandidatesKt.dp(view, 2);
        int limit = ExpandedCandidatesKt.dp(view, 16);
        for (int i = 0; i < view.getChildCount(); i++) {
            View cell = view.getChildAt(i);
            if (cell.getVisibility() != View.VISIBLE || ButtonBackground.find(cell.getBackground()) == null) continue;
            movingBounds.set(inset, inset, cell.getWidth() - inset, cell.getHeight() - inset);
            if (movingBounds.isEmpty()) continue;
            IconButtonMotion.Press press = style.motion().sample(cell);
            if (press != null && press.overflows()) {
                press.transform(transform, cell.getWidth() / 2f, cell.getHeight() / 2f);
                transform.mapRect(movingBounds);
            }
            float rowTop = cell.getY() + movingBounds.top;
            float rowBottom = cell.getY() + movingBounds.bottom;
            edges.include(rowTop, rowBottom, limit);
        }
        int saved = edges.save(canvas, width);
        try {
            Object result = null;
            if (key != null && canvas.isHardwareAccelerated()) {
                drawBlurred(chain, view, canvas, width, height);
            } else {
                result = chain.proceed();
            }
            edges.draw(canvas, width);
            return result;
        } finally {
            canvas.restoreToCount(saved);
        }
    }

    private void drawBlurred(XposedInterface.Chain chain, View view, Canvas canvas, int width, int height)
            throws Throwable {
        words.setPosition(0, 0, width, height);
        RecordingCanvas source = words.beginRecording();
        try {
            chain.proceed(new Object[]{source});
        } finally {
            words.endRecording();
        }
        float radius = 10f * view.getResources().getDisplayMetrics().density;
        if (!blur.draw(canvas, words, mask, width, height, radius, 1f, Color.TRANSPARENT)) {
            canvas.drawRenderNode(words);
        }
    }
}
