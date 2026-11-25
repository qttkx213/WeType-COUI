package local.wetype.couiglass;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RecordingCanvas;
import android.graphics.RenderNode;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedInterface;

/** Animates keyboard pages above the IME window's live blur. */
final class PageMotion {
    enum Page {
        KEYBOARD, TOOLS, OTHER;

        static Page named(String name) {
            return switch (name) {
                case "ChineseT9", "ChineseQwerty", "EnglishQwerty", "WuBiQwerty", "DoublePinT9",
                        "DoublePinQwerty", "Stroke", "HandWrite", "HandWriteFullScreen", "EnglishNumberSymbols",
                        "ChineseNumberSymbols", "Symbols", "Number", "PureNumber" -> KEYBOARD;
                case "Setting" -> TOOLS;
                default -> OTHER;
            };
        }
    }

    static boolean animateTools(Page from, Page to, boolean inlinePanel) {
        return !inlinePanel && (from == Page.KEYBOARD && to == Page.TOOLS || from == Page.TOOLS && to == Page.KEYBOARD);
    }

    private final KeyboardStyle style;
    private final Paint sourcePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint add = new Paint();
    private final RenderNode incoming = new RenderNode("WeType incoming keyboard page");
    // ColorOS 17 push and pop translation curves from suqi8/coui's COUIPageTransition.
    private final Interpolator openEnter = new PathInterpolator(.3f, .1f, .3f, 1f);
    private final Interpolator openExit = new PathInterpolator(.3f, .15f, .3f, 1f);
    private final Interpolator closeEnter = new PathInterpolator(.3f, .26f, .4f, 1f);
    private final Interpolator closeExit = new PathInterpolator(.25f, .1f, .3f, 1f);
    private Bitmap outgoing;
    private ValueAnimator animation;
    private float progress;
    private boolean returning;
    private boolean switching;
    private boolean suppressSideSlide;
    private View target;
    private long waitUntil;

    PageMotion(KeyboardStyle style) {
        this.style = style;
        add.setBlendMode(BlendMode.PLUS);
    }

    static void install(WeTypeModule module, ClassLoader loader) throws ReflectiveOperationException {
        String prefix = "com.tencent.wetype.plugin.hld.";
        Class<?> model = Class.forName(prefix + "model.N", false, loader);
        Class<?> types = Class.forName(prefix + "keyboard.t", false, loader);
        Method value = DrawHooks.method(types, "c", int.class);
        Map<Integer, Page> pageTypes = new HashMap<>();
        for (Object option : types.getEnumConstants()) {
            Page page = Page.named(((Enum<?>) option).name());
            if (page != Page.OTHER) pageTypes.put((int) value.invoke(option), page);
        }
        Method previous = DrawHooks.method(model, "J0", int.class);
        Method current = DrawHooks.method(model, "u0", int.class);
        Method currentView = DrawHooks.method(model, "p0", Class.forName(prefix + "keyboard.s", false, loader));
        Class<?> callback = Class.forName("wc.a", false, loader);
        Class<?>[] args = new Class<?>[11];
        Arrays.fill(args, 0, 9, boolean.class);
        args[9] = args[10] = callback;
        Method switchPage = DrawHooks.method(model, "L3", void.class, args);
        Method drawChildren = DrawHooks.method(ViewGroup.class, "dispatchDraw", void.class, Canvas.class);
        Map<Class<?>, Field> bundles = new ConcurrentHashMap<>();

        module.intercept(switchPage, chain -> {
            KeyboardStyle style = module.style();
            View root = style == null ? null : style.animationRoot();
            if (root == null) return chain.proceed();
            PageMotion pages = style.pages();
            Object[] arguments = chain.getArgs().toArray();
            // Keep the host's layout branches and callbacks while disabling their visual effects.
            if ((boolean) arguments[0]) arguments[1] = false;
            arguments[4] = arguments[6] = false;
            if (pages.switching) return chain.proceed(arguments);

            Object instance = chain.getThisObject();
            Page from = pageTypes.get((int) previous.invoke(instance));
            Page to = pageTypes.get((int) current.invoke(instance));
            boolean inlinePanel = to == Page.TOOLS && requestsInlinePanel(chain.getArg(9), bundles)
                    || from == Page.TOOLS && style.inlinePanelVisible();
            Bitmap frame = animateTools(from, to, inlinePanel) ? capture(module, pages, root, drawChildren) : null;
            if (frame == null) pages.clear();

            pages.switching = true;
            pages.suppressSideSlide = (boolean) arguments[1] || (boolean) arguments[2];
            try {
                Object result = chain.proceed(arguments);
                if (frame != null && root == style.animationRoot()) {
                    View view = (View) currentView.invoke(instance);
                    pages.start(root, frame, from == Page.TOOLS, view);
                }
                return result;
            } finally {
                pages.suppressSideSlide = false;
                pages.switching = false;
                style.refresh();
            }
        });
        module.intercept(DrawHooks.method(View.class, "setVisibility", void.class, int.class), chain -> {
            View page = (View) chain.getThisObject();
            boolean visible = (int) chain.getArg(0) == View.VISIBLE;
            if (!KeyboardStyle.isInlinePanel(page.getClass().getName())
                    || visible == (page.getVisibility() == View.VISIBLE)) return chain.proceed();
            KeyboardStyle style = module.style();
            if (style == null || style.animationRoot() == null || !style.draws(page)
                    || !(page.getParent() instanceof View parent) || !style.visible(parent)) return chain.proceed();
            Object result = chain.proceed();
            if (!style.pages().switching) style.pages().clear();
            style.refresh();
            return result;
        });
        try {
            Class<?> settings = Class.forName(prefix + "utils.i1", false, loader);
            Method allowsAnimation = DrawHooks.method(settings, "t0", boolean.class);
            module.intercept(allowsAnimation, chain -> {
                KeyboardStyle style = module.style();
                // L3 still needs its right-slide branch to hide the old view and call back.
                return style != null && Looper.myLooper() == Looper.getMainLooper()
                        && style.pages().suppressSideSlide ? false : chain.proceed();
            });
            module.deoptimize(DrawHooks.method(model, "U2", void.class, callback, callback));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            module.warn("Using the original side panel exit", error);
        }
        try {
            disableInlineZoom(module, loader, prefix);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            module.warn("Using the original tool subpage zoom", error);
        }
        module.trace("Keyboard page transitions ready");
    }

    private static void disableInlineZoom(WeTypeModule module, ClassLoader loader, String prefix)
            throws ReflectiveOperationException {
        Class<?> zoom = Class.forName(prefix + "utils.K0", false, loader);
        Class<?> callback = Class.forName(prefix + "utils.K0$b", false, loader);
        Class<?>[] args = {View.class, View.class, View.class, boolean.class, callback};
        Method enter = DrawHooks.method(zoom, "m", void.class, args);
        Method exit = DrawHooks.method(zoom, "o", void.class, args);
        XposedInterface.Hooker withoutZoom = chain -> {
            KeyboardStyle style = module.style();
            View background = (View) chain.getArg(0);
            if (!(boolean) chain.getArg(3) || style == null || style.animationRoot() == null
                    || !style.draws(background)) return chain.proceed();
            for (View view = background; view != null;
                    view = view.getParent() instanceof View parent ? parent : null) {
                if (KeyboardStyle.isInlinePanel(view.getClass().getName())) {
                    Object[] arguments = chain.getArgs().toArray();
                    arguments[3] = false;
                    return chain.proceed(arguments);
                }
            }
            return chain.proceed();
        };
        module.intercept(enter, withoutZoom);
        module.intercept(exit, withoutZoom);
    }

    private static boolean requestsInlinePanel(Object callback, Map<Class<?>, Field> cache) throws IllegalAccessException {
        if (callback == null) return false;
        Field field = cache.computeIfAbsent(callback.getClass(), type -> {
            for (Field candidate : type.getDeclaredFields()) {
                if (candidate.getType() == Bundle.class) {
                    candidate.setAccessible(true);
                    return candidate;
                }
            }
            return null;
        });
        Bundle request = field == null ? null : (Bundle) field.get(callback);
        int target = request == null ? -1 : request.getInt("target_scroll_index", -1);
        return target == 3 || target == 4 || target == 5 || target == 7;
    }

    private static Bitmap capture(WeTypeModule module, PageMotion pages, View root, Method drawChildren) {
        if (!ValueAnimator.areAnimatorsEnabled()) return null;
        try {
            return pages.snapshot(root, drawChildren);
        } catch (ReflectiveOperationException | RuntimeException error) {
            module.warn("Unable to capture the outgoing keyboard page", error);
            return null;
        }
    }

    private Bitmap snapshot(View root, Method drawChildren) throws ReflectiveOperationException {
        // Start reversals from the displayed composition. Ignore the window backdrop in both passes.
        if (outgoing != null && animation == null && outgoing.getWidth() == root.getWidth()) return outgoing;
        Bitmap bitmap = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        if (outgoing != null && outgoing.getWidth() == root.getWidth()) {
            drawFrame(canvas, root, live -> drawChildren.invoke(root, live));
        } else {
            drawChildren.invoke(root, canvas);
        }
        return bitmap;
    }

    private void start(View root, Bitmap frame, boolean returning, View target) {
        clear();
        outgoing = frame;
        this.returning = returning;
        this.target = target;
        waitUntil = SystemClock.uptimeMillis() + 220L;
        progress = 0f;
        root.invalidate();
    }

    private boolean targetMounted() {
        return target == null || target.isLaidOut() && target.getWidth() > 0 && target.getHeight() > 0
                && style.visible(target);
    }

    private void animate(View root) {
        target = null;
        ValueAnimator next = ValueAnimator.ofFloat(0f, 1f);
        animation = next;
        next.setDuration(240);
        next.setInterpolator(new LinearInterpolator());
        next.addUpdateListener(value -> {
            progress = value.getAnimatedFraction();
            root.invalidate();
        });
        next.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator ended) {
                if (animation == ended) {
                    animation = null;
                    outgoing = null;
                    incoming.discardDisplayList();
                    root.invalidate();
                }
            }
        });
        next.start();
    }

    Object draw(XposedInterface.Chain chain, View root, Canvas canvas) throws Throwable {
        if (!canvas.isHardwareAccelerated()) return chain.proceed();
        if (outgoing != null && (outgoing.getWidth() != root.getWidth() || !ValueAnimator.areAnimatorsEnabled())) {
            clear();
        }
        if (outgoing != null && animation == null) {
            boolean timedOut = SystemClock.uptimeMillis() >= waitUntil;
            if (targetMounted()) {
                animate(root);
            } else if (!timedOut) {
                canvas.drawBitmap(outgoing, 0f, root.getHeight() - outgoing.getHeight(), null);
                root.postInvalidateOnAnimation();
                return null;
            } else {
                clear();
            }
        }
        if (outgoing == null) return style.layers().draw(chain, root, canvas);

        incoming.setPosition(0, 0, root.getWidth(), root.getHeight());
        RecordingCanvas content = incoming.beginRecording();
        try {
            style.layers().draw(chain, root, content);
        } finally {
            incoming.endRecording();
        }
        drawFrame(canvas, root, canvasToDraw -> canvasToDraw.drawRenderNode(incoming));
        return null;
    }

    private void drawFrame(Canvas canvas, View root, DrawPage live) throws ReflectiveOperationException {
        int width = root.getWidth();
        int height = root.getHeight();
        float reveal = (returning ? closeEnter : openEnter).getInterpolation(progress);
        float exit = (returning ? closeExit : openExit).getInterpolation(progress);
        float density = root.getResources().getDisplayMetrics().density;
        float travel = Math.min(24f * density, width * .12f);
        float direction = root.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? -1f : 1f;
        int enterOffset = Math.round(direction * travel * (returning ? -.3f : 1f) * (1f - reveal));
        int exitOffset = Math.round(direction * travel * (returning ? 1f : -.3f) * exit);
        int targetAlpha = Math.round(reveal * 255f);
        int blend = canvas.saveLayer(0f, 0f, width, height, null);
        try {
            canvas.clipRect(0, 0, width, height);
            // Complementary weights preserve the amount of glass over the shared blurred window.
            int saved = canvas.save();
            canvas.translate(exitOffset, 0f);
            sourcePaint.setAlpha(255 - targetAlpha);
            canvas.drawBitmap(outgoing, 0f, height - outgoing.getHeight(), sourcePaint);
            canvas.restoreToCount(saved);
            if (targetAlpha != 0) {
                add.setAlpha(targetAlpha);
                int layer = canvas.saveLayer(0f, 0f, width, height, add);
                try {
                    canvas.translate(enterOffset, 0f);
                    live.draw(canvas);
                } finally {
                    canvas.restoreToCount(layer);
                }
            }
        } finally {
            canvas.restoreToCount(blend);
        }
    }

    @FunctionalInterface
    private interface DrawPage {
        void draw(Canvas canvas) throws ReflectiveOperationException;
    }

    void clear() {
        if (animation != null) {
            animation.removeAllListeners();
            animation.removeAllUpdateListeners();
            animation.cancel();
            animation = null;
        }
        outgoing = null;
        incoming.discardDisplayList();
        target = null;
    }
}
