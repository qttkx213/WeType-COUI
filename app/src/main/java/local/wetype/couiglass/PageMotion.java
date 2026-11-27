package local.wetype.couiglass;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.HardwareRenderer;
import android.graphics.Paint;
import android.graphics.Rect;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedInterface;

/** Animates keyboard pages above the IME window's live blur. */
final class PageMotion {
    private static final int TOOLBAR_LOGO = 0x7f090329;
    private static final int TOOLBAR_LIST = 0x7f0901c2;
    private static final int TOOLBAR_BACK = 0x7f0904e7;
    private static final int GRID_HANDWRITE = 0x7f090480;
    private static final float HALF_CROSSFADE = .20f;
    private static final float PAGE_SCALE_DELTA = .04f;

    enum Page {
        KEYBOARD, TOOLS, CLIPBOARD, CLIPBOARD_CLEAN_RECORD, CORRECTION, HANDWRITE_TOOL, TOOL, OTHER;

        static Page named(String name) {
            return switch (name) {
                case "ChineseT9", "ChineseQwerty", "EnglishQwerty", "WuBiQwerty", "DoublePinT9",
                        "DoublePinQwerty", "Stroke", "HandWrite", "HandWriteFullScreen", "EnglishNumberSymbols",
                        "ChineseNumberSymbols", "Symbols", "Number", "PureNumber" -> KEYBOARD;
                case "Setting" -> TOOLS;
                case "CustomPhraseAndClipboard" -> CLIPBOARD;
                case "ClipboardCleanRecord" -> CLIPBOARD_CLEAN_RECORD;
                case "Correction" -> CORRECTION;
                case "HandwriteFindWordT9", "HandwriteFindWordT26" -> HANDWRITE_TOOL;
                case "KeyboardChoose", "Emoji", "Inspiration", "RequestAI", "ImagePreview", "PC", "Debug" -> TOOL;
                default -> OTHER;
            };
        }

        boolean isTool() {
            return this == TOOLS || this == CLIPBOARD || this == CORRECTION || this == HANDWRITE_TOOL || this == TOOL;
        }
    }

    enum Source { OTHER, TOOLBAR, BACK }

    enum Direction {
        NONE, OPEN, CLOSE;

        private float handoff() { return this == CLOSE ? .65f : .35f; }

        float fadeStart() { return handoff() - HALF_CROSSFADE; }
        float fadeEnd() { return handoff() + HALF_CROSSFADE; }

        int incomingAlpha(float progress) {
            float fraction = Math.max(0f, Math.min(1f, (progress - fadeStart()) / (fadeEnd() - fadeStart())));
            return Math.round(255f * fraction * fraction * (3f - 2f * fraction));
        }

        float pageScale(float fraction) {
            return switch (this) {
                case OPEN -> 1f - PAGE_SCALE_DELTA * (1f - fraction);
                case CLOSE -> 1f - PAGE_SCALE_DELTA * fraction;
                case NONE -> 1f;
            };
        }
    }

    static final class ToolNavigation {
        private final List<Integer> path = new ArrayList<>();
        private boolean candidateBack;

        void onCandidateBack() { candidateBack = !path.isEmpty(); }

        Direction change(int fromId, Page from, int toId, Page to, Source source) {
            // The correction page handles the header's back click off the main thread.
            if (from == Page.CORRECTION && candidateBack && source == Source.OTHER) source = Source.BACK;
            candidateBack = false;
            if (fromId == toId || (from == Page.CLIPBOARD && to == Page.CLIPBOARD_CLEAN_RECORD)
                    || (from == Page.CLIPBOARD_CLEAN_RECORD && to == Page.CLIPBOARD)) return Direction.NONE;
            if (!path.isEmpty() && path.get(path.size() - 1) != fromId) clear();

            int parent = path.lastIndexOf(toId);
            if (parent >= 0 && (source == Source.BACK || source == Source.TOOLBAR && to.isTool())) {
                if (parent == 0) clear();
                else path.subList(parent + 1, path.size()).clear();
                return Direction.CLOSE;
            }
            if (source == Source.TOOLBAR && to.isTool()) {
                if (path.isEmpty()) path.add(fromId);
                path.add(toId);
                return Direction.OPEN;
            }
            // Some host back buttons skip the tools grid and return to the typing layout.
            boolean returning = source == Source.BACK && !path.isEmpty() && to == Page.KEYBOARD;
            clear();
            return returning ? Direction.CLOSE : Direction.NONE;
        }

        void clear() {
            path.clear();
            candidateBack = false;
        }
    }

    private final KeyboardStyle style;
    private final Paint sourcePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint incomingPaint = new Paint();
    private final Rect targetBounds = new Rect();
    private final RenderNode captureNode = new RenderNode("WeType outgoing keyboard page");
    private final ToolNavigation navigation = new ToolNavigation();
    // ColorOS 17 push and pop translation curves from suqi8/coui's COUIPageTransition.
    private final Interpolator openEnter = new PathInterpolator(.3f, .1f, .3f, 1f);
    private final Interpolator closeExit = new PathInterpolator(.25f, .1f, .3f, 1f);
    private Source source = Source.OTHER;
    private Direction direction = Direction.NONE;
    private Bitmap outgoing;
    private Bitmap handwriteGridFrame;
    private ValueAnimator animation;
    private float progress;
    private boolean switching;
    private boolean capturing;
    private Method hardwareSnapshot;
    private boolean suppressSideSlide;
    private View target;
    private String expectedOverlay;
    private String inlineTool;
    private long waitUntil;

    PageMotion(KeyboardStyle style) {
        this.style = style;
        incomingPaint.setBlendMode(BlendMode.PLUS);
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
        Class<?> toolsGrid = Class.forName(prefix + "keyboard.S10SettingsKeyboard", false, loader);
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
            int fromId = (int) previous.invoke(instance);
            int toId = (int) current.invoke(instance);
            Page from = pageTypes.getOrDefault(fromId, Page.OTHER);
            Page to = pageTypes.getOrDefault(toId, Page.OTHER);
            Direction direction = pages.navigation.change(fromId, from, toId, to, pages.source);
            int scrollIndex = to == Page.TOOLS && pages.source == Source.TOOLBAR
                    ? requestedScrollIndex(chain.getArg(9), bundles) : -1;
            String inline = inlinePanel(scrollIndex);
            // Index 1 opens the guide's chooser after a delay. Show the grid while it waits.
            String expected = scrollIndex == 1 ? null : inline;
            if (expected != null && !style.overlayVisible(expected)) direction = Direction.OPEN;
            pages.inlineTool = inline;

            pages.switching = true;
            pages.suppressSideSlide = (boolean) arguments[1] || (boolean) arguments[2];
            try {
                Bitmap frame = null;
                if (direction != Direction.NONE) {
                    frame = direction == Direction.OPEN && to == Page.HANDWRITE_TOOL ? pages.handwriteGridFrame : null;
                    if (frame == null) frame = capture(module, pages, root, drawChildren);
                }
                if (frame == null) pages.clearAnimation();
                Object result = chain.proceed(arguments);
                if (frame != null && root == style.animationRoot()) {
                    View view = (View) currentView.invoke(instance);
                    pages.start(root, frame, direction, view, expected);
                }
                return result;
            } finally {
                pages.suppressSideSlide = false;
                pages.switching = false;
                style.refresh();
            }
        });
        // N.m3 runs these main-thread keyboard switches before the click handler returns.
        Class<?> candidate = Class.forName(prefix + "candidate.ImeCandidateView", false, loader);
        module.intercept(DrawHooks.method(candidate, "onClick", void.class, View.class), chain -> {
            View view = (View) chain.getArg(0);
            if (view == null) return chain.proceed();
            return switch (view.getId()) {
                case TOOLBAR_LOGO -> withSource(module, chain, Source.TOOLBAR);
                case TOOLBAR_BACK -> {
                    KeyboardStyle style = module.style();
                    if (style != null) style.pages().navigation.onCandidateBack();
                    yield withSource(module, chain, Source.BACK);
                }
                default -> chain.proceed();
            };
        });
        Class<?> toolbar = Class.forName(prefix + "toolbar.m", false, loader);
        Class<?> continuation = Class.forName("kotlin.coroutines.d", false, loader);
        module.intercept(DrawHooks.method(toolbar, "I", Object.class, View.class, continuation), chain ->
                insideToolbar((View) chain.getArg(0)) ? withSource(module, chain, Source.TOOLBAR) : chain.proceed());
        module.intercept(DrawHooks.method(toolsGrid, "Q0", void.class, int.class, View.class), chain -> {
            KeyboardStyle style = module.style();
            View root = style == null ? null : style.animationRoot();
            if (root == null || (int) chain.getArg(0) != GRID_HANDWRITE) {
                return withSource(module, chain, Source.TOOLBAR);
            }
            // The grid closes before the host opens the T9 or T26 finding-words layout.
            PageMotion pages = style.pages();
            pages.handwriteGridFrame = capture(module, pages, root, drawChildren);
            try {
                return withSource(module, chain, Source.TOOLBAR);
            } finally {
                pages.handwriteGridFrame = null;
            }
        });
        Class<?> clipboardPreview = Class.forName(prefix + "candidate.t", false, loader);
        // u(false) opens the clipboard panel from its icon. u(true) opens the preview's separate arrow action.
        module.intercept(DrawHooks.method(clipboardPreview, "u", void.class, boolean.class), chain ->
                (boolean) chain.getArg(0) ? chain.proceed() : withSource(module, chain, Source.TOOLBAR));
        // Clipboard's header back uses Q2. Its internal actions can also call the generic X switch.
        module.intercept(DrawHooks.method(model, "Q2", void.class, boolean.class),
                chain -> withSource(module, chain, Source.BACK));
        module.intercept(DrawHooks.method(model, "X", void.class, boolean.class), chain ->
                pageTypes.get((int) current.invoke(chain.getThisObject())) == Page.CLIPBOARD
                        ? chain.proceed() : withSource(module, chain, Source.BACK));

        module.intercept(DrawHooks.method(View.class, "setVisibility", void.class, int.class), chain -> {
            View page = (View) chain.getThisObject();
            String name = page.getClass().getName();
            boolean visible = (int) chain.getArg(0) == View.VISIBLE;
            if (!KeyboardStyle.isInlinePanel(name)
                    || visible == (page.getVisibility() == View.VISIBLE)) return chain.proceed();
            KeyboardStyle style = module.style();
            View root = style == null ? null : style.animationRoot();
            if (root == null || !insideGrid(page, toolsGrid) || !(page.getParent() instanceof View parent)
                    || !style.visible(parent)) return chain.proceed();

            PageMotion pages = style.pages();
            if (pages.switching) return chain.proceed();
            if (visible && name.equals(pages.expectedOverlay)) {
                Object result = chain.proceed();
                style.refresh();
                return result;
            }
            Direction direction = inlineMotion(visible, pages.source == Source.TOOLBAR, name.equals(pages.inlineTool));
            if (visible || direction == Direction.CLOSE) pages.inlineTool = direction == Direction.OPEN ? name : null;

            pages.switching = true;
            try {
                Bitmap frame = direction == Direction.NONE ? null : capture(module, pages, root, drawChildren);
                Object result = chain.proceed();
                if (frame != null && root == style.animationRoot()) {
                    pages.start(root, frame, direction, visible ? page : parent, null);
                } else if (visible || direction == Direction.CLOSE) {
                    pages.clearAnimation();
                }
                return result;
            } finally {
                pages.switching = false;
                style.refresh();
            }
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
            disableInlineZoom(module, loader, prefix, toolsGrid);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            module.warn("Using the original tool subpage zoom", error);
        }
        module.trace("Keyboard page transitions ready");
    }

    private static Object withSource(WeTypeModule module, XposedInterface.Chain chain, Source source) throws Throwable {
        KeyboardStyle style = module.style();
        if (style == null || Looper.myLooper() != Looper.getMainLooper()) return chain.proceed();
        PageMotion pages = style.pages();
        Source previous = pages.source;
        if (previous == Source.TOOLBAR && source == Source.BACK) return chain.proceed();
        pages.source = source;
        try {
            return chain.proceed();
        } finally {
            pages.source = previous;
        }
    }

    private static boolean insideToolbar(View view) {
        for (; view != null; view = view.getParent() instanceof View parent ? parent : null) {
            if (view.getId() == TOOLBAR_LIST) return true;
        }
        return false;
    }

    private static boolean insideGrid(View view, Class<?> toolsGrid) {
        for (; view != null; view = view.getParent() instanceof View parent ? parent : null) {
            if (toolsGrid.isInstance(view)) return true;
        }
        return false;
    }

    private static void disableInlineZoom(WeTypeModule module, ClassLoader loader, String prefix, Class<?> toolsGrid)
            throws ReflectiveOperationException {
        Class<?> zoom = Class.forName(prefix + "utils.K0", false, loader);
        Class<?> callback = Class.forName(prefix + "utils.K0$b", false, loader);
        Class<?>[] args = {View.class, View.class, View.class, boolean.class, callback};
        Method enter = DrawHooks.method(zoom, "m", void.class, args);
        Method exit = DrawHooks.method(zoom, "o", void.class, args);
        XposedInterface.Hooker withoutZoom = chain -> {
            KeyboardStyle style = module.style();
            View background = (View) chain.getArg(0);
            if (style == null || style.animationRoot() == null || !style.draws(background)) return chain.proceed();
            for (View view = background; view != null;
                    view = view.getParent() instanceof View parent ? parent : null) {
                if (KeyboardStyle.isInlinePanel(view.getClass().getName())) {
                    PageMotion pages = style.pages();
                    // The grid's visibility callback can run after the card click returns.
                    if (chain.getExecutable().equals(enter) && pages.source == Source.TOOLBAR && !pages.switching
                            && insideGrid(view, toolsGrid)) {
                        pages.inlineTool = view.getClass().getName();
                    }
                    if (!(boolean) chain.getArg(3)) return chain.proceed();
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

    private static int requestedScrollIndex(Object callback, Map<Class<?>, Field> cache) throws IllegalAccessException {
        if (callback == null) return -1;
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
        return request == null ? -1 : request.getInt("target_scroll_index", -1);
    }

    static String inlinePanel(int scrollIndex) {
        return switch (scrollIndex) {
            case 1, 5 -> KeyboardStyle.INLINE_CHOOSER;
            case 3 -> KeyboardStyle.INLINE_PLUS;
            case 4 -> KeyboardStyle.INLINE_TOOLBAR;
            case 7 -> KeyboardStyle.INLINE_OFFLINE_VOICE;
            default -> null;
        };
    }

    static Direction inlineMotion(boolean visible, boolean clickedToolbar, boolean toolbarPanel) {
        if (visible) return clickedToolbar || toolbarPanel ? Direction.OPEN : Direction.NONE;
        return toolbarPanel ? Direction.CLOSE : Direction.NONE;
    }

    private static Bitmap capture(WeTypeModule module, PageMotion pages, View root, Method drawChildren) {
        if (Looper.myLooper() != Looper.getMainLooper() || !ValueAnimator.areAnimatorsEnabled()) return null;
        try {
            return pages.snapshot(root, drawChildren);
        } catch (ReflectiveOperationException | RuntimeException error) {
            module.warn("Unable to capture the outgoing keyboard page", error);
            return null;
        }
    }

    private Bitmap snapshot(View root, Method drawChildren) throws ReflectiveOperationException {
        // Reuse a waiting frame on reversal. The root's background stays live in the IME window.
        if (outgoing != null && animation == null && outgoing.getWidth() == root.getWidth()) return outgoing;
        if (hardwareSnapshot == null) {
            hardwareSnapshot = HardwareRenderer.class.getMethod("createHardwareBitmap",
                    RenderNode.class, int.class, int.class);
        }
        int width = root.getWidth();
        int height = root.getHeight();
        captureNode.setPosition(0, 0, width, height);
        try {
            RecordingCanvas canvas = captureNode.beginRecording();
            capturing = true;
            try {
                drawChildren.invoke(root, canvas);
            } finally {
                capturing = false;
                captureNode.endRecording();
            }
            // Freeze the GPU-rendered material before the host recycles its child views.
            Bitmap frame = (Bitmap) hardwareSnapshot.invoke(null, captureNode, width, height);
            if (frame != null) frame.setDensity(Bitmap.DENSITY_NONE);
            return frame;
        } finally {
            captureNode.discardDisplayList();
        }
    }

    private void start(View root, Bitmap frame, Direction direction, View target, String expectedOverlay) {
        clearAnimation();
        outgoing = frame;
        this.direction = direction;
        this.target = target;
        this.expectedOverlay = expectedOverlay;
        waitUntil = SystemClock.uptimeMillis() + 220L;
        progress = 0f;
        root.invalidate();
    }

    private boolean targetMounted() {
        return target != null && target.isLaidOut() && !target.isLayoutRequested() && style.visible(target)
                && target.getGlobalVisibleRect(targetBounds)
                && (expectedOverlay == null || style.overlayVisible(expectedOverlay));
    }

    private void animate(View root) {
        target = null;
        expectedOverlay = null;
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
                    root.invalidate();
                }
            }
        });
        next.start();
    }

    Object draw(XposedInterface.Chain chain, View root, Canvas canvas) throws Throwable {
        if (!canvas.isHardwareAccelerated()) return chain.proceed();
        if (capturing) {
            return outgoing != null && animation != null && outgoing.getWidth() == root.getWidth()
                    ? drawFrame(chain, root, canvas) : style.layers().draw(chain, root, canvas);
        }
        if (outgoing != null && (outgoing.getWidth() != root.getWidth() || !ValueAnimator.areAnimatorsEnabled())) {
            clearAnimation();
        }
        if (outgoing != null && animation == null) {
            boolean timedOut = SystemClock.uptimeMillis() >= waitUntil;
            if (targetMounted()) {
                animate(root);
            } else if (!timedOut) {
                drawOutgoing(canvas, root.getWidth(), root.getHeight(), 1f, 255);
                root.postInvalidateOnAnimation();
                return null;
            } else {
                clearAnimation();
            }
        }
        if (outgoing == null) return style.layers().draw(chain, root, canvas);

        return drawFrame(chain, root, canvas);
    }

    private Object drawFrame(XposedInterface.Chain chain, View root, Canvas canvas) throws Throwable {
        int width = root.getWidth();
        int height = root.getHeight();
        boolean returning = direction == Direction.CLOSE;
        int alpha = direction.incomingAlpha(progress);
        // Mix the weighted contents in a transparent layer, then composite over the live blur once.
        int saved = canvas.save();
        try {
            canvas.clipRect(0, 0, width, height);
            boolean crossfading = alpha > 0 && alpha < 255;
            if (crossfading) canvas.saveLayer(0, 0, width, height, null);
            if (alpha < 255) {
                float scale = returning ? direction.pageScale(
                        closeExit.getInterpolation(progress / direction.fadeEnd())) : 1f;
                drawOutgoing(canvas, width, height, scale, 255 - alpha);
            }
            if (alpha == 0) return null;
            if (crossfading) {
                incomingPaint.setAlpha(alpha);
                canvas.saveLayer(0, 0, width, height, incomingPaint);
            }
            if (!returning) {
                float start = direction.fadeStart();
                float scale = direction.pageScale(openEnter.getInterpolation((progress - start) / (1f - start)));
                scalePage(canvas, width, scale);
            }
            return style.layers().draw(chain, root, canvas);
        } finally {
            canvas.restoreToCount(saved);
        }
    }

    private static void scalePage(Canvas canvas, int width, float scale) {
        // Anchor at the top so the content moves downward as it grows, inside the rounded panel.
        canvas.scale(scale, scale, width * .5f, 0f);
    }

    static float snapshotFit(int frameHeight, int height) {
        return Math.min(1f, height / (float) frameHeight);
    }

    private void drawOutgoing(Canvas canvas, int width, int height, float scale, int alpha) {
        int frameHeight = outgoing.getHeight();
        int saved = canvas.save();
        try {
            // Fit the whole frame if the host shortens the keyboard while mounting the next page.
            scalePage(canvas, width, scale * snapshotFit(frameHeight, height));
            sourcePaint.setAlpha(alpha);
            canvas.drawBitmap(outgoing, 0f, Math.max(0, height - frameHeight), sourcePaint);
        } finally {
            canvas.restoreToCount(saved);
        }
    }

    void clear() {
        navigation.clear();
        inlineTool = null;
        handwriteGridFrame = null;
        clearAnimation();
    }

    private void clearAnimation() {
        if (animation != null) {
            animation.removeAllListeners();
            animation.removeAllUpdateListeners();
            animation.cancel();
            animation = null;
        }
        outgoing = null;
        target = null;
        expectedOverlay = null;
    }
}
