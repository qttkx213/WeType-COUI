package local.wetype.couiglass;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

final class DrawHooks {
    private static final String KEYS = "com.tencent.wetype.plugin.hld.keyboard.selfdraw.";
    private static final String EMOJI_KEYBOARD = KEYS + "S11EmojiKeyboard";
    private static final String HANDWRITING_T9 = KEYS + "S27HandwriteT9Keyboard";
    private static final String HANDWRITING_T26 = KEYS + "S28HandwriteT26Keyboard";
    private final WeTypeModule module;
    private final RectF keyFace = new RectF();
    private final Method owner;
    private final Method bounds;
    private final Method shifted;
    private final Method shadowHeight;
    private final Method fill;
    private final Method keyData;
    private final Method keyId;
    private final Method shadow;
    private final Method pressedState;
    private final Method releasing;
    private final Method releaseAlpha;
    private final Method drawFace;
    private final Method drawRelease;
    private final Method drawKey;
    private final Method selectedKey;
    private final Method recordKey;
    private final Method touchEvent;
    private final Class<?> button;
    private final ClassLoader loader;
    private final ThreadLocal<BitmapPass> bitmapPass = new ThreadLocal<>();
    private boolean drewHardwareKey;
    private boolean drewBitmapKey;
    private boolean faceFailed;
    private boolean bitmapFailed;
    private boolean motionFailureReported;

    DrawHooks(WeTypeModule module, ClassLoader loader) throws ReflectiveOperationException {
        this.module = module;
        this.loader = loader;
        button = Class.forName(KEYS + "j", false, loader);
        Class<?> drawer = Class.forName(KEYS + "drawmethod.c", false, loader);
        Class<?> drawerBase = Class.forName(KEYS + "drawmethod.a", false, loader);
        Class<?> keyboard = Class.forName(KEYS + "o", false, loader);
        owner = method(drawerBase, "c", keyboard);
        bounds = method(button, "t", Rect.class);
        shifted = method(keyboard, "Y1", boolean.class);
        shadowHeight = method(button, "Z", float.class);
        fill = method(button, "i", int.class);
        Class<?> data = Class.forName(KEYS + "bean.KeyData", false, loader);
        keyData = method(button, "O", data);
        keyId = method(data, "getId", String.class);
        shadow = method(button, "Y", int.class);
        pressedState = method(button, "c0", String.class);
        releasing = method(button, "n0", boolean.class);
        releaseAlpha = method(button, "D", int.class);
        drawFace = method(drawer, "e", void.class, Canvas.class, button);
        drawRelease = method(drawer, "f", void.class, Canvas.class, button);
        drawKey = method(drawer, "a", void.class, Canvas.class, button);
        selectedKey = method(keyboard, "getActionButton", button);
        recordKey = method(keyboard, "o2", void.class, MotionEvent.class);
        touchEvent = method(keyboard, "dispatchTouchEvent", boolean.class, MotionEvent.class);
    }

    void install() {
        module.intercept(drawFace, this::drawFace);
        module.intercept(drawRelease, this::drawRelease);
        module.intercept(drawKey, this::drawKey);
        module.intercept(touchEvent, this::dispatchTouch);
        module.intercept(recordKey, this::recordKey);
        try {
            Method dispatch = method(View.class, "dispatchTouchEvent", boolean.class, MotionEvent.class);
            module.intercept(dispatch, chain -> chain.getThisObject() instanceof ViewGroup
                    ? chain.proceed() : dispatchViewButton(chain));
            module.intercept(method(ViewGroup.class, "dispatchTouchEvent", boolean.class, MotionEvent.class),
                    this::dispatchViewButton);
            module.intercept(method(View.class, "draw", void.class, Canvas.class), this::drawViewButton);
            module.intercept(method(ViewGroup.class, "dispatchDraw", void.class, Canvas.class), chain -> {
                KeyboardStyle style = module.style();
                View view = (View) chain.getThisObject();
                if (style == null || !style.isInputRoot(view)) return chain.proceed();
                return style.pages().draw(chain, view, (Canvas) chain.getArg(0));
            });
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            module.warn("View button touch feedback unavailable", error);
        }
        try {
            installCandidateHighlight();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            module.warn("Using original candidate highlight", error);
        }
        try {
            PageMotion.install(module, loader);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            module.warn("Using original keyboard page transitions", error);
        }
        try {
            ToolbarPager.install(module, loader);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            module.warn("Using original toolbar page indicator", error);
        }
        try {
            KeyboardControls.install(module, loader);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            module.warn("Using original emoji and numeric controls", error);
        }
        try {
            SegmentedTabs.install(module, loader);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            module.warn("Using original segmented tabs", error);
        }
        try {
            SymbolMenu.install(module, loader);
            module.intercept(method(View.class, "setVisibility", void.class, int.class), chain -> {
                KeyboardStyle style = module.style();
                if (style != null) {
                    style.layers().popupVisibilityChanging((View) chain.getThisObject(), (int) chain.getArg(0));
                }
                return chain.proceed();
            });
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            module.warn("Using original symbol menu", error);
        }
        try {
            Class<?> alpha = Class.forName(KEYS + "drawmethod.h", false, loader);
            Field paint = alpha.getDeclaredField("i");
            if (paint.getType() != Paint.class) throw new NoSuchFieldException("NormalAlphaDrawer paint");
            paint.setAccessible(true);
            Method drawBitmap = method(alpha, "i", Bitmap.class, Canvas.class, button);
            Method drawBitmapToCanvas = method(alpha, "b", void.class, Canvas.class, button);
            Method round = method(Canvas.class, "drawRoundRect", void.class, RectF.class,
                    float.class, float.class, Paint.class);
            module.intercept(round, this::drawBitmapFace);
            module.intercept(drawBitmap, chain -> bitmapScope(chain, paint));
            module.deoptimize(drawBitmap);
            module.deoptimize(drawBitmapToCanvas);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            module.warn("Bitmap key hook unavailable", error);
        }
    }

    private Object dispatchTouch(XposedInterface.Chain chain) throws Throwable {
        KeyboardStyle style = module.style();
        View view = (View) chain.getThisObject();
        if (style != null && style.draws(view)) {
            try {
                style.motion().update(view, (MotionEvent) chain.getArg(0));
            } catch (RuntimeException | LinkageError error) {
                resetMotion(style, error);
            }
        }
        return chain.proceed();
    }

    private Object recordKey(XposedInterface.Chain chain) throws Throwable {
        Object result = chain.proceed();
        KeyboardStyle style = module.style();
        View keyboard = (View) chain.getThisObject();
        if (style == null || !style.draws(keyboard)) return result;
        try {
            Object key = selectedKey.invoke(keyboard);
            if (key != null && !handwritingDecoration(keyboard, key) && Color.alpha(visibleFill(keyboard, key)) > 0) {
                Rect rect = (Rect) bounds.invoke(key);
                MotionEvent event = (MotionEvent) chain.getArg(0);
                if (rect != null) style.motion().down(keyboard, key, rect, event, event.getActionIndex());
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            resetMotion(style, error);
        }
        return result;
    }

    private void resetMotion(KeyboardStyle style, Throwable error) {
        style.motion().clear();
        if (!motionFailureReported) {
            motionFailureReported = true;
            module.warn("COUI-Kit key motion failed", error);
        }
    }

    private Object dispatchViewButton(XposedInterface.Chain chain) throws Throwable {
        View view = (View) chain.getThisObject();
        MotionEvent event = (MotionEvent) chain.getArg(0);
        KeyboardStyle style = module.style();
        if (style != null && style.isInputRoot(view)) {
            try {
                style.motion().update(view, event);
            } catch (RuntimeException | LinkageError error) {
                resetMotion(style, error);
            }
            return chain.proceed();
        }
        Object handled = view.getBackground() instanceof SegmentedTabs tabs
                ? tabs.dispatch(chain, event) : chain.proceed();
        if (style != null) {
            try {
                style.observeButton(view, event, Boolean.TRUE.equals(handled));
            } catch (RuntimeException | LinkageError error) {
                resetMotion(style, error);
            }
        }
        return handled;
    }

    private Object drawViewButton(XposedInterface.Chain chain) throws Throwable {
        View view = (View) chain.getThisObject();
        KeyboardStyle style = module.style();
        if (style == null) return chain.proceed();
        if (style.layers().ownsPopup(view)) {
            return style.layers().drawPopup(chain);
        }
        boolean logo = view instanceof ImageView image && image.getDrawable() instanceof ToolbarLogo;
        Object background = view.getBackground();
        if (!logo && !(background instanceof ButtonBackground) && !(background instanceof SegmentedTabs)) {
            return chain.proceed();
        }
        IconButtonMotion.Press motion = style.motion().sample(view);
        if (motion == null) return chain.proceed();
        RectF face = background instanceof SegmentedTabs tabs ? new RectF(tabs.touchBounds())
                : new RectF(0f, 0f, view.getWidth(), view.getHeight());
        float density = view.getResources().getDisplayMetrics().density;
        float radius = background instanceof SegmentedTabs tabs ? tabs.getOutlineRadius()
                : KeySurface.radius(face.width(), face.height(), density);
        if (style.layers().lift(view, view, face, radius, motion, chain)) return null;
        Canvas canvas = (Canvas) chain.getArg(0);
        int saved = canvas.save();
        try {
            motion.transform(canvas, face.centerX(), face.centerY());
            return chain.proceed();
        } finally {
            canvas.restoreToCount(saved);
        }
    }

    private Object drawKey(XposedInterface.Chain chain) throws Throwable {
        KeyboardStyle style = module.style();
        if (style == null) return chain.proceed();
        Object key = chain.getArg(1);
        IconButtonMotion.Press motion = style.motion().sample(key);
        if (motion == null) return chain.proceed();
        Rect rect = (Rect) bounds.invoke(key);
        View view = (View) owner.invoke(chain.getThisObject());
        if (rect == null || !style.draws(view)) return chain.proceed();
        RectF face = keyFace(view, key, rect);
        Canvas canvas = (Canvas) chain.getArg(0);
        float density = view.getResources().getDisplayMetrics().density;
        if (motion.overflows() && style.layers().lift(view, key, face,
                KeySurface.radius(face.width(), face.height(), density), motion, chain)) return null;
        int saved = canvas.save();
        try {
            motion.transform(canvas, face.centerX(), face.centerY());
            return chain.proceed();
        } finally {
            canvas.restoreToCount(saved);
        }
    }

    private Object drawFace(XposedInterface.Chain chain) throws Throwable {
        KeyboardStyle style = module.style();
        if (style == null || faceFailed) return chain.proceed();
        try {
            View view = (View) owner.invoke(chain.getThisObject());
            if (style.draws(view)) {
                Canvas canvas = (Canvas) chain.getArg(0);
                Object key = chain.getArg(1);
                Rect rect = (Rect) bounds.invoke(key);
                int color = visibleFill(view, key);
                boolean pressed = !handwritingDecoration(view, key) && "press".equals(pressedState.invoke(key));
                if (rect != null && Color.alpha(color) != 0) {
                    RectF face = keyFace(view, key, rect);
                    float density = view.getResources().getDisplayMetrics().density;
                    IconButtonMotion.Press motion = style.motion().sample(key);
                    if (style.material().draw(canvas, face.left, face.top, face.right, face.bottom,
                            density, Color.alpha(color), pressed, CouiPalette.darkKey(style.dark(), color), motion)) {
                        if (!drewHardwareKey) {
                            drewHardwareKey = true;
                            module.trace("Self-drawn key material applied");
                        }
                        return null;
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            faceFailed = true;
            module.warn("Using original self-drawn keys", error);
        }
        return chain.proceed();
    }

    private Object drawRelease(XposedInterface.Chain chain) throws Throwable {
        KeyboardStyle style = module.style();
        if (style == null || faceFailed) return chain.proceed();
        try {
            Object key = chain.getArg(1);
            if (style.motion().sample(key) != null) return null;
            if ((boolean) releasing.invoke(key) && !"press".equals(pressedState.invoke(key))) {
                View view = (View) owner.invoke(chain.getThisObject());
                if (style.draws(view)) {
                    if (handwritingDecoration(view, key)) return null;
                    Rect rect = (Rect) bounds.invoke(key);
                    int fillColor = visibleFill(view, key);
                    if (rect != null && Color.alpha(fillColor) != 0) {
                        boolean dark = CouiPalette.darkKey(style.dark(), fillColor);
                        int alpha = Math.round(Math.min(Color.alpha(CouiPalette.pressMask(dark)),
                                (int) releaseAlpha.invoke(key)) * (Color.alpha(fillColor) / 255f));
                        int mask = (alpha << 24) | (CouiPalette.pressMask(dark) & 0xFFFFFF);
                        RectF face = keyFace(view, key, rect);
                        style.material().drawRelease((Canvas) chain.getArg(0), face.left, face.top, face.right,
                                face.bottom, view.getResources().getDisplayMetrics().density, mask);
                        return null;
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            module.warn("Unable to draw the key release mask", error);
        }
        return chain.proceed();
    }

    private int visibleFill(View keyboard, Object key) throws ReflectiveOperationException {
        int color = (int) fill.invoke(key);
        if (Color.alpha(color) == 0 && EMOJI_KEYBOARD.equals(keyboard.getClass().getName())
                && "delete".equals(keyId.invoke(keyData.invoke(key)))) return Color.WHITE;
        return color;
    }

    private boolean handwritingDecoration(View keyboard, Object key) throws ReflectiveOperationException {
        String name = keyboard.getClass().getName();
        if (!HANDWRITING_T9.equals(name) && !HANDWRITING_T26.equals(name)) return false;
        // The host routes touch on these decorative keys to the handwriting recognizer.
        Object id = keyId.invoke(keyData.invoke(key));
        return !"return".equals(id) && !"delete".equals(id) && !"enter".equals(id);
    }

    private RectF keyFace(View view, Object key, Rect rect) throws ReflectiveOperationException {
        float bottom = (boolean) shifted.invoke(view) ? rect.bottom - (float) shadowHeight.invoke(key) : rect.bottom;
        if (EMOJI_KEYBOARD.equals(view.getClass().getName())) {
            String id = (String) keyId.invoke(keyData.invoke(key));
            RectF aligned = KeyboardControls.emojiActionBounds(view, id);
            if (aligned != null) return aligned;
        }
        keyFace.set(rect.left, rect.top, rect.right, bottom);
        return keyFace;
    }

    private Object bitmapScope(XposedInterface.Chain chain, Field paint) throws Throwable {
        KeyboardStyle style = module.style();
        if (style == null || bitmapFailed) return chain.proceed();
        BitmapPass pass = null;
        try {
            View view = (View) owner.invoke(chain.getThisObject());
            if (style.draws(view)) {
                Object key = chain.getArg(1);
                int color = (int) fill.invoke(key);
                Rect rect = (Rect) bounds.invoke(key);
                if (Color.alpha(color) != 0 && rect != null && rect.width() >= 2 && rect.height() >= 2) {
                    pass = new BitmapPass(style, (Paint) paint.get(chain.getThisObject()), color,
                            view.getResources().getDisplayMetrics().density,
                            (int) shadow.invoke(key) == 0 ? 0 : 1,
                            !handwritingDecoration(view, key) && "press".equals(pressedState.invoke(key)));
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            bitmapFailed = true;
            module.warn("Using original bitmap keys", error);
        }
        if (pass == null) return chain.proceed();
        BitmapPass previous = bitmapPass.get();
        bitmapPass.set(pass);
        try {
            return chain.proceed();
        } finally {
            if (previous == null) bitmapPass.remove();
            else bitmapPass.set(previous);
        }
    }

    private Object drawBitmapFace(XposedInterface.Chain chain) throws Throwable {
        BitmapPass pass = bitmapPass.get();
        if (pass == null || bitmapFailed || chain.getArg(3) != pass.paint) return chain.proceed();
        int shape = pass.nextShape++;
        if (shape < pass.fillIndex || pass.replaced) return null;
        if (shape == pass.fillIndex && pass.paint.getStyle() == Paint.Style.FILL
                && pass.paint.getColor() == pass.fillColor) {
            Canvas canvas = (Canvas) chain.getThisObject();
            RectF r = (RectF) chain.getArg(0);
            try {
                pass.replaced = pass.style.material().draw(canvas, r.left, r.top, r.right, r.bottom,
                        pass.density, Color.alpha(pass.fillColor), pass.pressed,
                        CouiPalette.darkKey(pass.style.dark(), pass.fillColor), null);
                if (pass.replaced) {
                    if (!drewBitmapKey) {
                        drewBitmapKey = true;
                        module.trace("Bitmap key material applied");
                    }
                    return null;
                }
            } catch (RuntimeException | LinkageError error) {
                bitmapFailed = true;
                module.warn("Unable to paint a bitmap key", error);
            }
        }
        return chain.proceed();
    }

    private void installCandidateHighlight() throws ReflectiveOperationException {
        Class<?> cell = Class.forName("com.tencent.wetype.plugin.hld.candidate.selfdraw.selfview.e", false, loader);
        Method color = method(cell, "k", int.class);
        Method highlightBounds = method(cell, "z", Rect.class, Rect.class);
        Method highlight = method(cell, "i", void.class, Context.class, Canvas.class, Rect.class);
        module.intercept(highlight, chain -> {
            KeyboardStyle style = module.style();
            if (style == null || style.animationRoot() == null) return chain.proceed();
            Object item = chain.getThisObject();
            if (Color.alpha((int) color.invoke(item)) == 0) return null;
            Rect rect = (Rect) highlightBounds.invoke(item, chain.getArg(2));
            if (rect == null || rect.isEmpty()) return chain.proceed();
            Context context = (Context) chain.getArg(0);
            float density = context.getResources().getDisplayMetrics().density;
            float radius = Math.min(rect.width(), rect.height()) / 2f;
            boolean dark = style.dark();
            return style.material().draw((Canvas) chain.getArg(1), rect.left, rect.top, rect.right, rect.bottom,
                    density, radius, CouiGlass.chip(dark), 255, false, dark, null) ? null : chain.proceed();
        });
    }

    static Method method(Class<?> type, String name, Class<?> returns, Class<?>... arguments)
            throws ReflectiveOperationException {
        Method method = type.getDeclaredMethod(name, arguments);
        if (method.getReturnType() != returns) throw new NoSuchMethodException(type.getName() + "." + name);
        method.setAccessible(true);
        return method;
    }

    private static final class BitmapPass {
        final KeyboardStyle style;
        final Paint paint;
        final int fillColor;
        final float density;
        final int fillIndex;
        final boolean pressed;
        int nextShape;
        boolean replaced;

        BitmapPass(KeyboardStyle style, Paint paint, int fillColor, float density, int fillIndex, boolean pressed) {
            this.style = style;
            this.paint = paint;
            this.fillColor = fillColor;
            this.density = density;
            this.fillIndex = fillIndex;
            this.pressed = pressed;
        }
    }
}
