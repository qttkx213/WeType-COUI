package local.wetype.couiglass;

import android.app.Application;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.inputmethodservice.InputMethodService;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.EditorInfo;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

public final class WeTypeModule extends XposedModule {
    private static final String TAG = "WeTypeCOUI";
    private static final String PACKAGE = "com.tencent.wetype";
    private static final String IME_PROCESS = PACKAGE + ":hld";
    private static final String HOST = PACKAGE + ".plugin.hld.";
    private static final String SERVICE = HOST + "WxHldService";
    private String processName;
    private boolean entryInstalled;
    private boolean checkedVersion;
    private boolean compatible;
    private Class<?> keyView;
    private Method darkSkin;
    private InputMethodService service;
    private KeyboardStyle style;
    private boolean overlayDemoSearch;

    @Override public void onModuleLoaded(ModuleLoadedParam param) {
        processName = param.getProcessName();
        if (IME_PROCESS.equals(processName)) trace("Module loaded in the WeType IME process");
    }

    @Override public void onPackageLoaded(PackageLoadedParam param) {
        if (isIme(param)) installEntry();
    }

    @Override public void onPackageReady(PackageReadyParam param) {
        if (isIme(param)) installEntry();
    }

    private boolean isIme(PackageLoadedParam param) {
        return PACKAGE.equals(param.getPackageName())
                && IME_PROCESS.equals(processName != null ? processName : Application.getProcessName());
    }

    private synchronized void installEntry() {
        if (entryInstalled) return;
        try {
            Class<InputMethodService> ime = InputMethodService.class;
            Method create = ime.getMethod("onCreate");
            Method setInput = ime.getMethod("setInputView", View.class);
            Method startInput = ime.getMethod("onStartInput", EditorInfo.class, boolean.class);
            Method shown = ime.getMethod("onWindowShown");
            Method hidden = ime.getMethod("onWindowHidden");
            Method config = ime.getMethod("onConfigurationChanged", Configuration.class);
            Method destroy = ime.getMethod("onDestroy");
            intercept(create, chain -> {
                Object result = chain.proceed();
                if (chain.getThisObject() instanceof InputMethodService instance) prepare(instance);
                return result;
            });
            intercept(setInput, chain -> {
                Object result = chain.proceed();
                if (chain.getThisObject() instanceof InputMethodService instance) {
                    prepare(instance);
                    if (owns(chain) && chain.getArg(0) instanceof View input) style.bind(input);
                }
                return result;
            });
            intercept(startInput, chain -> {
                Object result = chain.proceed();
                if (owns(chain)) {
                    updateDemoSearch((EditorInfo) chain.getArg(0));
                    style.refresh();
                }
                return result;
            });
            intercept(shown, chain -> {
                Object result = chain.proceed();
                if (chain.getThisObject() instanceof InputMethodService instance) {
                    prepare(instance);
                    if (owns(chain)) {
                        View input = currentInputView(instance);
                        if (input != null) style.bind(input);
                        style.show();
                    }
                }
                return result;
            });
            intercept(hidden, chain -> {
                if (owns(chain)) style.hide();
                return chain.proceed();
            });
            intercept(config, chain -> {
                Object result = chain.proceed();
                if (owns(chain)) style.refresh();
                return result;
            });
            intercept(destroy, chain -> {
                if (owns(chain)) {
                    style.unbind();
                    style = null;
                    service = null;
                    overlayDemoSearch = false;
                }
                return chain.proceed();
            });
            entryInstalled = true;
            trace("WeType IME lifecycle hooks installed");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            warn("WeType input service is unavailable", failure);
        }
    }

    private void prepare(InputMethodService ime) {
        if (service == ime || !SERVICE.equals(ime.getClass().getName())) return;
        try {
            if (!checkedVersion) {
                checkedVersion = true;
                PackageInfo info = ime.getPackageManager().getPackageInfo(PACKAGE, 0);
                long version = info.getLongVersionCode();
                if (version != 59201L) {
                    trace("Unsupported WeType version: " + version);
                    return;
                }
                ClassLoader loader = ime.getClass().getClassLoader();
                keyView = optionalClass(HOST + "view.v", loader);
                Class<?> skinManager = optionalClass(HOST + "skin.e", loader);
                if (skinManager != null) {
                    try {
                        darkSkin = DrawHooks.method(skinManager, "l", boolean.class);
                    } catch (ReflectiveOperationException ignored) {
                        // Resource UI mode remains available if the skin accessor changes.
                    }
                }
                try {
                    new DrawHooks(this, loader).install();
                } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                    warn("Self-drawn key bridge did not match", failure);
                }
                try {
                    ExpandedCandidates.install(this, loader);
                } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                    warn("Expanded candidate controls unavailable", failure);
                }
                try {
                    CandidateGridBackdrop.install(this, loader);
                } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                    warn("Expanded candidate backdrop unavailable", failure);
                }
                try {
                    Method computeInsets = ime.getClass().getDeclaredMethod("onComputeInsets", InputMethodService.Insets.class);
                    intercept(computeInsets, chain -> {
                        Object result = chain.proceed();
                        if (owns(chain) && overlayDemoSearch) keepDemoBackdrop((InputMethodService.Insets) chain.getArg(0));
                        return result;
                    });
                } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                    warn("Demo search backdrop compatibility unavailable", failure);
                }
                compatible = true;
                trace("WeType " + version + ", API=" + getApiVersion());
            }
            if (compatible) {
                if (style != null) style.unbind();
                style = new KeyboardStyle(this, ime, keyView, darkSkin);
                service = ime;
            }
        } catch (Exception | LinkageError failure) {
            compatible = false;
            warn("Keyboard material setup failed", failure);
        }
    }

    private void updateDemoSearch(EditorInfo editor) {
        boolean overlay = false;
        if (editor != null && "com.itos.coui.demo".equals(editor.packageName)
                && (editor.imeOptions & EditorInfo.IME_MASK_ACTION) == EditorInfo.IME_ACTION_SEARCH) {
            try {
                String version = service.getPackageManager().getPackageInfo(editor.packageName, 0).versionName;
                overlay = version != null && switch (version) {
                    case "0.0.1-alpha16", "0.0.1-alpha17", "0.0.1-alpha18", "0.0.1-alpha20" -> true;
                    default -> false;
                };
            } catch (PackageManager.NameNotFoundException ignored) {
                // Leave layout insets alone when the installed Demo version is unknown.
            }
        }
        if (overlayDemoSearch == overlay) return;
        overlayDemoSearch = overlay;
        View decor = imeDecor();
        if (decor != null) decor.requestLayout();
    }

    private void keepDemoBackdrop(InputMethodService.Insets insets) {
        if (!service.isInputViewShown() || service.isFullscreenMode()
                || insets.touchableInsets != InputMethodService.Insets.TOUCHABLE_INSETS_VISIBLE) return;
        View decor = imeDecor();
        if (decor == null || decor.getHeight() <= insets.contentTopInsets) return;
        // These Demo versions apply IME padding to the entire Scaffold, cutting away the blur source.
        // Report overlay content while preserving the visible and touchable keyboard bounds.
        insets.contentTopInsets = decor.getHeight();
    }

    private View imeDecor() {
        Window window = service.getWindow() == null ? null : service.getWindow().getWindow();
        return window == null ? null : window.peekDecorView();
    }

    private static View currentInputView(InputMethodService ime) {
        if (ime.getWindow() == null) return null;
        Window window = ime.getWindow().getWindow();
        if (window == null) return null;
        View area = window.findViewById(android.R.id.inputArea);
        return area instanceof ViewGroup group && group.getChildCount() > 0 ? group.getChildAt(0) : null;
    }

    KeyboardStyle style() { return style; }

    void intercept(Method method, Hooker callback) {
        hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(callback);
    }

    void trace(String message) {
        Log.i(TAG, message);
        log(Log.INFO, TAG, message);
    }

    void warn(String message, Throwable failure) {
        Log.w(TAG, message, failure);
        log(Log.WARN, TAG, message, failure);
    }

    private boolean owns(Chain chain) {
        return style != null && chain.getThisObject() == service;
    }

    private static Class<?> optionalClass(String name, ClassLoader loader) {
        try {
            return Class.forName(name, false, loader);
        } catch (ClassNotFoundException | LinkageError ignored) {
            return null;
        }
    }
}
