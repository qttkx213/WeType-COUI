package local.wetype.couiglass;

import android.graphics.Canvas;
import android.view.View;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface;

final class AlphaKeyLabels {
    private static final String HOST = "com.tencent.wetype.plugin.hld.";
    private static final String KEYS = HOST + "keyboard.selfdraw.";
    private static final int LOWERCASE = 1;
    private static final int SHIFT_ONCE = 2;
    private static final int HELD_SHIFT = 4;

    private final WeTypeModule module;
    private final Method owner;
    private final Method keyData;
    private final Method keyId;
    private final Set<Object> manuallyShifted = Collections.newSetFromMap(new WeakHashMap<>());
    private final ThreadLocal<Object> shiftSource = new ThreadLocal<>();
    private final ThreadLocal<Label> drawing = new ThreadLocal<>();

    AlphaKeyLabels(WeTypeModule module, Method owner, Method keyData, Method keyId) {
        this.module = module;
        this.owner = owner;
        this.keyData = keyData;
        this.keyId = keyId;
    }

    void install(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> keyboard = owner.getReturnType();
        Class<?> button = keyData.getDeclaringClass();
        Method upperMode = DrawHooks.method(keyboard, "getUpperMode", int.class);
        Method setUpperMode = DrawHooks.method(keyboard, "n0", void.class, int.class);
        Method shift = DrawHooks.method(Class.forName(KEYS + "b", false, loader), "X2", void.class, boolean.class);
        Method autoShift = DrawHooks.method(Class.forName(HOST + "utils.Q$a", false, loader),
                "invokeSuspend", Object.class, Object.class);
        Method mainText = DrawHooks.method(button, "R", String.class);
        Method floatDraw = DrawHooks.method(Class.forName(KEYS + "drawmethod.e", false, loader),
                "b", void.class, Canvas.class, button);
        Method normalDraw = DrawHooks.method(Class.forName(KEYS + "drawmethod.i", false, loader),
                "b", void.class, Canvas.class, button);

        // Keep the mode setter and text getter calls visible to LSPosed.
        for (Method caller : new Method[]{shift, autoShift, floatDraw, normalDraw}) module.deoptimize(caller);
        module.intercept(shift, chain -> scoped(shiftSource, chain.getThisObject(), chain));
        module.intercept(setUpperMode, this::rememberShift);
        module.intercept(mainText, this::mainText);
        module.intercept(floatDraw, chain -> drawLabel(upperMode, chain));
        module.intercept(normalDraw, chain -> drawLabel(upperMode, chain));
    }

    private Object rememberShift(XposedInterface.Chain chain) throws Throwable {
        Object keyboard = chain.getThisObject();
        if (keyPrefix(keyboard.getClass().getName()) == null) return chain.proceed();
        int requestedMode = (int) chain.getArg(0);
        if (requestedMode == SHIFT_ONCE && shiftSource.get() == keyboard) manuallyShifted.add(keyboard);
        else if (requestedMode != SHIFT_ONCE && requestedMode != HELD_SHIFT) manuallyShifted.remove(keyboard);
        // Automatic first-letter capitalization and a Shift tap request the same host mode.
        int mode = requestedMode == SHIFT_ONCE && !manuallyShifted.contains(keyboard) ? LOWERCASE : requestedMode;
        return mode == requestedMode ? chain.proceed() : chain.proceed(new Object[]{mode});
    }

    private Object drawLabel(Method upperMode, XposedInterface.Chain chain) throws Throwable {
        KeyboardStyle style = module.style();
        if (style == null) return chain.proceed();
        View keyboard = (View) owner.invoke(chain.getThisObject());
        String prefix = keyPrefix(keyboard.getClass().getName());
        if (prefix == null || !style.draws(keyboard)) return chain.proceed();
        Object key = chain.getArg(1);
        char letter = keyLetter(prefix, (String) keyId.invoke(keyData.invoke(key)));
        if (letter == 0) return chain.proceed();
        int mode = (int) upperMode.invoke(keyboard);
        return scoped(drawing, new Label(key, letter, mode, mode == SHIFT_ONCE && manuallyShifted.contains(keyboard)),
                chain);
    }

    private Object mainText(XposedInterface.Chain chain) throws Throwable {
        Object text = chain.proceed();
        Label label = drawing.get();
        if (label == null || label.key() != chain.getThisObject()) return text;
        return display((String) text, label.letter(), label.mode(), label.manual());
    }

    private static <T> Object scoped(ThreadLocal<T> scope, T value, XposedInterface.Chain chain) throws Throwable {
        T previous = scope.get();
        scope.set(value);
        try {
            return chain.proceed();
        } finally {
            if (previous == null) scope.remove();
            else scope.set(previous);
        }
    }

    static String keyPrefix(String keyboardClass) {
        return switch (keyboardClass) {
            case KEYS + "S2ChineseQwertyKeyboard", KEYS + "S9DoublePinQwertyKeyboard",
                    KEYS + "S17WuBiQwertyKeyboard" -> "S2_key_";
            case KEYS + "S3EnglishQwertyKeyboard" -> "S3_key_";
            default -> null;
        };
    }

    static char keyLetter(String prefix, String id) {
        if (prefix == null || id == null || id.length() != prefix.length() + 1 || !id.startsWith(prefix)) return 0;
        char letter = id.charAt(prefix.length());
        return letter >= 'a' && letter <= 'z' ? letter : 0;
    }

    static String display(String text, char letter, int mode, boolean manual) {
        if (letter == 0 || text == null || text.length() != 1) return text;
        char original = text.charAt(0);
        char upper = Character.toUpperCase(letter);
        if (original != letter && original != upper) return text;
        char glyph = switch (mode) {
            case 1, 5 -> letter; // Lowercase and the pinyin apostrophe key state.
            case SHIFT_ONCE -> manual ? upper : letter;
            case 3, 4 -> upper; // Caps Lock and held Shift.
            default -> original;
        };
        return glyph == original ? text : String.valueOf(glyph);
    }

    private record Label(Object key, char letter, int mode, boolean manual) {}
}
