package local.wetype.couiglass;

import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.inputmethodservice.InputMethodService;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

final class KeyboardStyle implements View.OnAttachStateChangeListener, ViewTreeObserver.OnGlobalLayoutListener,
        ViewTreeObserver.OnPreDrawListener {
    private static final String PREFIX = "com.tencent.wetype.plugin.hld.";
    // Full-area skin backgrounds in WeType 59201, including the keyboard switch animation.
    private static final int CANDIDATE_BACKPLATE = 0x7f09061f;
    private static final int KEYBOARD_BACKPLATE = 0x7f0900a9;
    private static final int SWITCH_BACKPLATE = 0x7f0902a5;
    private static final int CHOOSER_BACKPLATE = 0x7f0900a8;
    private static final int TOOLBAR_ITEM = 0x7f0901b9;
    private static final int TOOLBAR_LIST = 0x7f0901c2;
    private static final int SETTINGS_TOOLBAR_LIST = 0x7f0904fe;
    private static final int SETTINGS_BACK = 0x7f0904e7;
    private static final int TOOLBAR_HIDE = 0x7f0901b3;
    private static final int TOOLBAR_RECENT = 0x7f0901b8;
    private static final int EMOJI_CATEGORY = 0x7f09020a;
    private static final int LOGO_ICON = 0x7f09032a;
    private static final int LOGO_CONTAINER = 0x7f090329;
    private static final int CANDIDATE_MORE = 0x7f0904ff;
    private static final int CANDIDATE_CLOSE = 0x7f090151;
    private static final int CANDIDATE_RIGHT = 0x7f09043e;
    static final int CANDIDATE_NORMAL_CONTAINER = 0x7f0900fe;
    private static final int CHOOSER_CARD = 0x7f0904e9;
    private static final int CLIPBOARD_TABS = 0x7f090562;
    static final int CANDIDATE_TABS = 0x7f090108;
    private static final int CLIPBOARD_LIST = 0x7f090558;
    private static final int PHRASE_LIST = 0x7f090559;
    private static final int SCROLL_CONTAINER_LIST = 0x7f0904af;
    private static final int CLIPBOARD_ENTRY = 0x7f090144;
    private static final int CLIPBOARD_FOLD_BACK = 0x7f090145;
    private static final int CLIPBOARD_FOLD_FRONT = 0x7f090146;
    private static final int CLIPBOARD_TEXT = 0x7f09013b;
    private static final int PHRASE_ENTRY = 0x7f09019e;
    private static final int PHRASE_TEXT = 0x7f090192;
    private static final int CANDIDATE_PACK_UP = 0x7f0903bf;
    private static final int CANDIDATE_PACK_UP_WRAPPER = 0x7f0903c0;
    private static final int MAIN_CANDIDATE_BAR = 0x7f0900e7;
    private static final ColorFilter LIGHT_TOOLBAR_ICON = new PorterDuffColorFilter(0xFF626265, PorterDuff.Mode.SRC_IN);
    private static final ColorFilter DARK_TOOLBAR_ICON = new PorterDuffColorFilter(0xFFB6B6BC, PorterDuff.Mode.SRC_IN);
    private static final String SELF_DRAW = PREFIX + "keyboard.selfdraw.";
    private static final String EMOJI_PANEL = SELF_DRAW + "S11EmojiKeyboard";
    private static final String CLIPBOARD_PANEL = PREFIX + "keyboard.S15CustomPhraseAndClipboardKeyboard";
    private static final String CLIPBOARD_CLEAN_PANEL = PREFIX + "keyboard.S29ClipboardCleanRecordKeyboard";
    private static final String KEYBOARD_CHOOSER = PREFIX + "keyboard.S30KeyboardChooseKeyboard";
    static final String INLINE_CHOOSER = PREFIX + "view.settingkeyboard.S10SettingKeyboardTypeView";
    static final String INLINE_TOOLBAR = PREFIX + "view.settingkeyboard.S10SettingCustomToolbarView";
    static final String INLINE_PLUS = PREFIX + "view.settingkeyboard.S10SettingPlusModeOpenView";
    static final String INLINE_OFFLINE_VOICE = PREFIX + "view.settingkeyboard.S10SettingOfflineVoiceGuideView";
    private final WeTypeModule module;
    private final InputMethodService service;
    private final Class<?> keyView;
    private final Method readDarkSkin;
    private final KeySurface material;
    private final IconButtonMotion motion = new IconButtonMotion();
    private final ClipboardPreviewControls clipboardPreview = new ClipboardPreviewControls(this);
    private final KeyboardLayers layers = new KeyboardLayers(this);
    private final PageMotion pages = new PageMotion(this);
    private final Map<View, AppliedBackground> changed = new WeakHashMap<>();
    private final Consumer<Boolean> blurListener = this::systemBlurChanged;
    private boolean layoutDirty;
    private final WindowManager windowManager;
    private View root;
    private KeyboardBackdrop backdrop;
    private ViewTreeObserver observer;
    private boolean shown;
    private boolean dark;
    private boolean listenerRegistered;
    private boolean systemBlur;
    private boolean rootReady;
    private boolean hasStyledButtons;
    private boolean reportedLayout;
    private View overlayPanel;
    private View candidateAction;
    private FrameLayout.LayoutParams originalActionLayout;
    private FrameLayout.LayoutParams alignedActionLayout;
    private final Rect originalActionPadding = new Rect();
    private int originalActionWidth;
    private int originalActionHeight;
    private ColorFilter originalActionFilter;
    private final Rect toolbarSlot = new Rect();
    private final int[] toolbarPosition = new int[2];
    private final int[] candidateBarPosition = new int[2];
    private final int[] actionParentPosition = new int[2];
    private WeakReference<ImageView> logoView;
    private ToolbarLogo logo;
    private final Rect panelBounds = new Rect();
    private final Rect coveredBounds = new Rect();
    private final Map<View, Float> coveredViews = new WeakHashMap<>();

    KeyboardStyle(WeTypeModule module, InputMethodService service, Class<?> keyView, Method readDarkSkin) {
        this.module = module;
        material = new KeySurface(module);
        this.service = service;
        this.keyView = keyView;
        this.readDarkSkin = readDarkSkin;
        windowManager = service.getSystemService(WindowManager.class);
    }

    boolean dark() { return dark; }
    KeySurface material() { return material; }

    IconButtonMotion motion() { return motion; }
    KeyboardLayers layers() { return layers; }
    PageMotion pages() { return pages; }

    boolean fitLiftedPreview(View view, View root, RectF bounds, Matrix correction) {
        return clipboardPreview.fitLiftedPreview(view, root, bounds, correction);
    }

    View animationRoot() {
        return shown && rootReady && root != null && root.isShown() && root.isHardwareAccelerated()
                && root.getWidth() > 0 && root.getHeight() > 0 ? root : null;
    }

    boolean sharesWindow(View view) {
        return shown && rootReady && root != null && root.getWindowToken() == view.getWindowToken();
    }

    boolean isInputRoot(View view) { return shown && rootReady && view == root; }

    void observeButton(View view, MotionEvent event, boolean handled) {
        if (!shown || clipboardPreview.observeButton(root, view, event, handled)) return;
        View target = buttonTarget(view);
        if (target == null || !draws(view)) return;
        // RecyclerView and paged grids can cancel a child while the finger stays on the IME.
        if (handled && event.getActionMasked() == MotionEvent.ACTION_DOWN && visible(target)) {
            Rect bounds = new Rect(0, 0, target.getWidth(), target.getHeight());
            motion.down(root, target, bounds, event, 0);
        }
    }

    private View buttonTarget(View view) {
        if (ButtonBackground.find(view.getBackground()) != null) return view;
        if (view.getId() == CANDIDATE_RIGHT || view.getId() == CANDIDATE_PACK_UP_WRAPPER) {
            View action = candidateActionIn(view);
            if (action != null && action.getBackground() instanceof ButtonBackground) return action;
        }
        ImageView image = logoView == null ? null : logoView.get();
        if (image != null && (image == view || image.getParent() == view)) return image;
        // The stock toolbar's RecyclerView delivers touches to the item wrapper.
        if (view.getParent() instanceof View parent
                && (parent.getId() == TOOLBAR_LIST || parent.getId() == SETTINGS_TOOLBAR_LIST)) {
            View face = view.findViewById(TOOLBAR_ITEM);
            if (face != null && face.getBackground() instanceof ButtonBackground) return face;
        }
        return null;
    }

    void bind(View input) {
        if (input == root) return;
        unbind();
        root = input;
        motion.setRoot(input);
        layers.bind(input);
        root.addOnAttachStateChangeListener(this);
        if (root.isAttachedToWindow()) onViewAttachedToWindow(root);
    }

    void show() {
        shown = true;
        if (root == null || !root.isAttachedToWindow()) return;
        startWatching();
        refresh();
    }

    void hide() {
        shown = false;
        stopWatching();
    }

    void unbind() {
        if (root == null) return;
        restoreLayout();
        root.removeOnAttachStateChangeListener(this);
        root = null;
        motion.setRoot(null);
        layers.bind(null);
        backdrop = null;
    }

    boolean draws(View owner) {
        if (!rootReady || owner == null || !owner.isAttachedToWindow()) return false;
        for (View current = owner; current != null;
                current = current.getParent() instanceof View view ? view : null) {
            if (current == root) return true;
        }
        return false;
    }

    void refresh() {
        if (!shown || root == null || !root.isAttachedToWindow()) return;
        layoutDirty = true;
        root.invalidate();
    }

    @Override public void onViewAttachedToWindow(View view) {
        if (view != root || !shown) return;
        startWatching();
        refresh();
    }

    @Override public void onViewDetachedFromWindow(View view) {
        if (view == root) restoreLayout();
    }

    @Override public void onGlobalLayout() { layoutDirty = true; }

    @Override public boolean onPreDraw() {
        if (shown && (layoutDirty || backgroundChanged() || rootReady && ToolbarPager.needsCardRepair(this, root)
                || overlayPanel != null && (!visible(overlayPanel) || coveredViewChanged()))) {
            layoutDirty = false;
            reconcile();
        }
        readToolbarSlot();
        if (overlayVisible(CLIPBOARD_PANEL)) {
            styleClipboardRows(CLIPBOARD_LIST, CLIPBOARD_ENTRY, CLIPBOARD_TEXT);
            styleClipboardRows(PHRASE_LIST, PHRASE_ENTRY, PHRASE_TEXT);
        }
        if (shown && rootReady) KeyboardControls.syncNumericTray(this, root);
        if (shown && rootReady) layers.syncCandidateGrid();
        boolean previewLayoutChanged = shown && rootReady
                && clipboardPreview.sync(root.findViewById(CANDIDATE_NORMAL_CONTAINER));
        return !updateCandidateLayout() && !previewLayoutChanged;
    }

    private void styleClipboardRows(int listId, int cardId, int textId) {
        View container = overlayPanel.findViewById(listId);
        if (container == null || !container.isShown()
                || !(container.findViewById(SCROLL_CONTAINER_LIST) instanceof ViewGroup list)) return;
        // RecyclerView can bind and attach new rows during scroll without a global layout pass.
        for (int index = 0; index < list.getChildCount(); index++) {
            View row = list.getChildAt(index);
            View card = row.findViewById(cardId);
            if (card != null && !(card.getBackground() instanceof GroupCard)) {
                styleClipboardEntry(card, card.getBackground(), textId);
            }
            if (listId == CLIPBOARD_LIST) styleClipboardStack(row, card);
        }
    }

    private void styleClipboardStack(View row, View card) {
        if (card == null || !(card.getBackground() instanceof GroupCard)) return;
        View back = row.findViewById(CLIPBOARD_FOLD_BACK);
        View front = row.findViewById(CLIPBOARD_FOLD_FRONT);
        boolean showBack = back != null && back.getVisibility() == View.VISIBLE;
        boolean showFront = front != null && front.getVisibility() == View.VISIBLE;
        if (showBack) styleClipboardFold(back, card, showFront ? front : null, 128);
        if (showFront) styleClipboardFold(front, card, null, 192);
    }

    private void styleClipboardFold(View fold, View entry, View frontFold, int opacity) {
        Drawable background = fold.getBackground();
        GroupCard surface = background instanceof GroupCard card ? card : new GroupCard(this, fold);
        if (surface != background) replace(fold, background, surface, false);
        surface.setStackOpacity(opacity);
        surface.maskFold(fold, entry, frontFold);
    }

    private void styleClipboardEntry(View view, Drawable original, int textId) {
        if (!(original instanceof GroupCard)) {
            replace(view, original, new GroupCard(this, view), false);
        }
        if (view.findViewById(textId) instanceof TextView label) {
            int inset = Math.max(label.getPaddingStart(), Math.round(CouiGlass.itemInset(view)));
            if (label.getPaddingStart() != inset) {
                label.setPaddingRelative(inset, label.getPaddingTop(), label.getPaddingEnd(), label.getPaddingBottom());
            }
        }
    }

    private View candidateActionIn(View wrapper) {
        if (wrapper.getId() == CANDIDATE_PACK_UP_WRAPPER) {
            View packUp = wrapper.findViewById(CANDIDATE_PACK_UP);
            return packUp != null && visible(packUp) ? packUp : null;
        }
        View close = wrapper.findViewById(CANDIDATE_CLOSE);
        if (close != null && visible(close)) return close;
        View more = wrapper.findViewById(CANDIDATE_MORE);
        return more != null && visible(more) ? more : null;
    }

    private boolean updateCandidateLayout() {
        if (!shown || !rootReady || root == null || !root.isHardwareAccelerated()) return false;
        View wrapper = root.findViewById(CANDIDATE_RIGHT);
        View action = wrapper == null ? null : candidateActionIn(wrapper);
        if (action == null) {
            wrapper = root.findViewById(CANDIDATE_PACK_UP_WRAPPER);
            action = wrapper == null ? null : candidateActionIn(wrapper);
        }
        if (candidateAction != action) {
            restoreCandidateButton();
            candidateAction = action;
            if (action != null) {
                originalActionPadding.set(action.getPaddingLeft(), action.getPaddingTop(),
                        action.getPaddingRight(), action.getPaddingBottom());
                originalActionWidth = action.getWidth();
                originalActionHeight = action.getHeight();
                action.invalidate();
            }
        }
        if (action == null) return false;
        if (action instanceof ImageView image) {
            ColorFilter current = image.getColorFilter();
            ColorFilter target = dark ? DARK_TOOLBAR_ICON : LIGHT_TOOLBAR_ICON;
            if (current != target) {
                if (!isToolbarIconFilter(current)) originalActionFilter = current;
                image.setColorFilter(target);
            }
        }
        return alignCandidateButton(wrapper, action);
    }

    private void readToolbarSlot() {
        if (!shown || !rootReady || root == null) return;
        View bar = root.findViewById(MAIN_CANDIDATE_BAR);
        if (bar == null || bar.getWidth() <= 0) return;
        bar.getLocationInWindow(candidateBarPosition);
        View fixed = bar.findViewById(TOOLBAR_HIDE);
        View face = fixed == null ? null : fixed.findViewById(TOOLBAR_ITEM);
        if (face == null) face = fixed;
        if (face != null && visible(face) && recordToolbarSlot(face)) return;
        if (bar.findViewById(TOOLBAR_LIST) instanceof ViewGroup tools && visible(tools)) {
            // When all tools fit, the collapse button is the last cell of the list.
            toolbarSlot.setEmpty();
            for (int index = 0; index < tools.getChildCount(); index++) {
                View item = tools.getChildAt(index).findViewById(TOOLBAR_ITEM);
                if (item == null || item.getWidth() <= 0 || item.getHeight() <= 0) continue;
                item.getLocationInWindow(toolbarPosition);
                int right = toolbarPosition[0] - candidateBarPosition[0] + item.getWidth();
                if (right > toolbarSlot.right && right <= bar.getWidth()) recordToolbarSlot(item);
            }
        }
        if (!toolbarSlot.isEmpty()) return;
        // A prefilled editor can show predictions before the default toolbar has been laid out.
        View logo = bar.findViewById(LOGO_ICON);
        if (logo == null || !(logo.getLayoutParams() instanceof ViewGroup.MarginLayoutParams params)
                || params.width <= 0 || params.height <= 0) return;
        int right = bar.getWidth() - params.getMarginStart();
        toolbarSlot.set(right - params.width, params.topMargin, right, params.topMargin + params.height);
    }

    private boolean recordToolbarSlot(View face) {
        if (face.getWidth() <= 0 || face.getHeight() <= 0) return false;
        face.getLocationInWindow(toolbarPosition);
        int left = toolbarPosition[0] - candidateBarPosition[0];
        int top = toolbarPosition[1] - candidateBarPosition[1];
        toolbarSlot.set(left, top, left + face.getWidth(), top + face.getHeight());
        return true;
    }

    private boolean alignCandidateButton(View wrapper, View action) {
        if (toolbarSlot.isEmpty() || !(action.getLayoutParams() instanceof FrameLayout.LayoutParams layout)) return false;
        wrapper.getLocationInWindow(actionParentPosition);
        // Keep the toolbar face inside WeType's reserved candidate action slot.
        int availableWidth = wrapper.getWidth() - wrapper.getPaddingLeft() - wrapper.getPaddingRight();
        int availableHeight = wrapper.getHeight() - wrapper.getPaddingTop() - wrapper.getPaddingBottom();
        if (availableWidth <= 0 || availableHeight <= 0) return false;
        int width = Math.min(toolbarSlot.width(), availableWidth);
        int height = Math.min(toolbarSlot.height(), availableHeight);
        int left = candidateBarPosition[0] + toolbarSlot.left - actionParentPosition[0] - wrapper.getPaddingLeft();
        int clipboardPreviewCenter = clipboardPreview.center();
        int desiredTop = clipboardPreviewCenter >= 0 && wrapper.getId() == CANDIDATE_RIGHT
                ? clipboardPreviewCenter - height / 2 : candidateBarPosition[1] + toolbarSlot.top;
        int top = desiredTop - actionParentPosition[1] - wrapper.getPaddingTop();
        left = Math.max(0, Math.min(left, availableWidth - width));
        top = Math.max(0, Math.min(top, availableHeight - height));
        if (layout == alignedActionLayout && layout.width == width && layout.height == height
                && layout.leftMargin == left && layout.topMargin == top) return false;
        if (layout != alignedActionLayout) originalActionLayout = layout;
        FrameLayout.LayoutParams aligned = new FrameLayout.LayoutParams(width, height, Gravity.LEFT | Gravity.TOP);
        aligned.leftMargin = left;
        aligned.topMargin = top;
        alignedActionLayout = aligned;
        action.setLayoutParams(aligned);
        // Enlarge the touch surface without enlarging the host's glyph.
        int dx = originalActionWidth > 0 ? Math.max(0, (width - originalActionWidth) / 2) : 0;
        int dy = originalActionHeight > 0 ? Math.max(0, (height - originalActionHeight) / 2) : 0;
        action.setPadding(originalActionPadding.left + dx, originalActionPadding.top + dy,
                originalActionPadding.right + dx, originalActionPadding.bottom + dy);
        return true;
    }

    private static boolean isToolbarIconFilter(ColorFilter filter) {
        return filter == LIGHT_TOOLBAR_ICON || filter == DARK_TOOLBAR_ICON;
    }

    private void restoreCandidateButton() {
        if (candidateAction instanceof ImageView image && isToolbarIconFilter(image.getColorFilter())) {
            image.setColorFilter(originalActionFilter);
        }
        originalActionFilter = null;
        if (candidateAction != null) {
            if (alignedActionLayout != null && candidateAction.getLayoutParams() == alignedActionLayout) {
                candidateAction.setLayoutParams(originalActionLayout);
                candidateAction.setPadding(originalActionPadding.left, originalActionPadding.top,
                        originalActionPadding.right, originalActionPadding.bottom);
            }
            candidateAction.invalidate();
        }
        candidateAction = null;
        originalActionLayout = null;
        alignedActionLayout = null;
    }

    private boolean backgroundChanged() {
        if (backdrop != null && backdrop.needsRepair()) return true;
        for (Map.Entry<View, AppliedBackground> entry : changed.entrySet()) {
            View view = entry.getKey();
            AppliedBackground background = entry.getValue();
            if (view == null) continue;
            Drawable current = view.getBackground();
            if (current != background.replacement && visible(view)
                    && (background.panel || background.replacement instanceof SegmentedTabs
                        || background.replacement instanceof SymbolCategories
                        || hasTabBackground(current) && insideSegmentedTabs(view))) return true;
        }
        return false;
    }

    private void startWatching() {
        if (observer == null || !observer.isAlive()) {
            observer = root.getViewTreeObserver();
            observer.addOnGlobalLayoutListener(this);
            observer.addOnPreDrawListener(this);
        }
        if (windowManager != null && !listenerRegistered) {
            systemBlur = windowManager.isCrossWindowBlurEnabled();
            windowManager.addCrossWindowBlurEnabledListener(service.getMainExecutor(), blurListener);
            listenerRegistered = true;
        }
    }

    private void stopWatching() {
        KeyboardControls.releaseNumericTray();
        motion.clear();
        layers.clear();
        pages.clear();
        restoreCandidateButton();
        clipboardPreview.clear();
        if (observer != null && observer.isAlive()) {
            observer.removeOnGlobalLayoutListener(this);
            observer.removeOnPreDrawListener(this);
        }
        observer = null;
        if (listenerRegistered) {
            windowManager.removeCrossWindowBlurEnabledListener(blurListener);
            listenerRegistered = false;
        }
    }

    // Keep the attached surface styled while the IME window is temporarily hidden.
    private void restoreLayout() {
        stopWatching();
        rootReady = false;
        if (backdrop != null) backdrop.restore();
        backdrop = null;
        for (Map.Entry<View, AppliedBackground> entry : changed.entrySet()) reset(entry.getKey(), entry.getValue());
        changed.clear();
        restoreLogo();
        overlayPanel = null;
        toolbarSlot.setEmpty();
        restoreCoveredViews();
    }

    private void systemBlurChanged(boolean enabled) {
        systemBlur = enabled;
        refresh();
    }

    private void reconcile() {
        if (!shown || root == null || !root.isAttachedToWindow()) return;
        try {
            boolean wasDark = dark;
            dark = isDark();
            boolean hadStyle = rootReady;
            boolean ownsPanel = false;
            if (!isFloating(root, 0) && service.getWindow() != null) {
                Window window = service.getWindow().getWindow();
                if (window != null) {
                    if (backdrop == null) backdrop = new KeyboardBackdrop(window, root);
                    ownsPanel = backdrop.apply(dark, systemBlur);
                }
            }
            if (!ownsPanel) {
                if (backdrop != null) backdrop.restore();
                restoreChildPanels();
            }
            View panel = ownsPanel ? findOverlay(root, 0) : null;
            overlayPanel = panel;
            updateCoveredViews();
            rootReady = true;
            if (!reportedLayout) {
                reportedLayout = true;
                module.trace("Keyboard layout ready: " + root.getWidth() + "x" + root.getHeight()
                        + ", systemBlur=" + systemBlur + ", panelStyled=" + ownsPanel);
            }
            if (!hadStyle || wasDark != dark) invalidateTree(root);
            scan(root, Zone.KEYBOARD, ownsPanel, 0);
        } catch (RuntimeException | LinkageError error) {
            module.warn("Unable to style this keyboard layout", error);
            shown = false;
            restoreLayout();
        }
    }

    boolean visible(View target) {
        for (View view = target; view != null;
                view = view.getParent() instanceof View parent ? parent : null) {
            if (view.getVisibility() != View.VISIBLE || view.getAlpha() == 0f) return false;
            if (view == root) return true;
        }
        return false;
    }

    static boolean isInlinePanel(String name) {
        return name.equals(INLINE_CHOOSER) || name.equals(INLINE_TOOLBAR) || name.equals(INLINE_PLUS)
                || name.equals(INLINE_OFFLINE_VOICE);
    }

    boolean overlayVisible(String className) {
        return overlayPanel != null && className.equals(overlayPanel.getClass().getName()) && visible(overlayPanel);
    }

    boolean inlinePanelVisible() {
        return overlayPanel != null && isInlinePanel(overlayPanel.getClass().getName()) && visible(overlayPanel);
    }

    private boolean coveredViewChanged() {
        for (View view : coveredViews.keySet()) {
            if (view != null && view.getAlpha() != 0f) return true;
        }
        return false;
    }

    private View findOverlay(View view, int depth) {
        if (depth >= 26 || view.getVisibility() != View.VISIBLE || view.getAlpha() == 0f) return null;
        String name = view.getClass().getName();
        if ((name.equals(EMOJI_PANEL) || name.equals(CLIPBOARD_PANEL)
                || name.equals(CLIPBOARD_CLEAN_PANEL) || name.equals(KEYBOARD_CHOOSER) || isInlinePanel(name))
                && view.getGlobalVisibleRect(panelBounds) && panelBounds.width() >= root.getWidth() * .8f) return view;
        if (view instanceof ViewGroup group) {
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                View found = findOverlay(group.getChildAt(i), depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean isFloating(View view, int depth) {
        if (depth >= 10 || view.getVisibility() != View.VISIBLE || view.getAlpha() == 0f) return false;
        String name = view.getClass().getName();
        if (name.startsWith(PREFIX + "float.") || name.startsWith(PREFIX + "hardware.")) return true;
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                if (isFloating(group.getChildAt(i), depth + 1)) return true;
            }
        }
        return false;
    }

    // Keep the covered page in the view hierarchy while the overlay owns its drawing area.
    private void updateCoveredViews() {
        if (overlayPanel == null) {
            restoreCoveredViews();
            return;
        }
        Iterator<Map.Entry<View, Float>> iterator = coveredViews.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<View, Float> entry = iterator.next();
            View view = entry.getKey();
            if (view == null || !isCovered(view)) {
                restoreCoveredView(view, entry.getValue());
                iterator.remove();
            }
        }
        hideCoveredViews(root, 0);
    }

    private void hideCoveredViews(View view, int depth) {
        if (depth >= 28 || view == overlayPanel || view.getVisibility() != View.VISIBLE) return;
        if (isCovered(view)) {
            if (view.getAlpha() != 0f) {
                coveredViews.putIfAbsent(view, view.getAlpha());
                view.setAlpha(0f);
            }
            return;
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) hideCoveredViews(group.getChildAt(i), depth + 1);
        }
    }

    private boolean isCovered(View view) {
        String name = view.getClass().getName();
        // Inline pages sit over the function grid in the same FrameLayout.
        String panel = overlayPanel.getClass().getName();
        boolean inlineSibling = isInlinePanel(panel)
                && view.getParent() == overlayPanel.getParent();
        boolean cleaningClipboard = CLIPBOARD_CLEAN_PANEL.equals(panel);
        // The host keeps the clipboard under its separate cleanup screen.
        if (cleaningClipboard && CLIPBOARD_PANEL.equals(name)) return true;
        boolean clipboard = CLIPBOARD_PANEL.equals(panel) || cleaningClipboard;
        boolean fullPage = clipboard || KEYBOARD_CHOOSER.equals(panel);
        if (fullPage && name.equals(PREFIX + "candidate.ImeCandidateView")) return true;
        // Opening Clipboard from the logo can leave the toolbar grid mounted underneath.
        boolean clipboardToolbar = clipboard && name.equals(PREFIX + "keyboard.S10SettingsKeyboard");
        if (!inlineSibling && !clipboardToolbar && view.getId() != MAIN_CANDIDATE_BAR
                && !name.startsWith(SELF_DRAW)) return false;
        if (isInside(view, overlayPanel) || isInside(overlayPanel, view)
                || !view.getGlobalVisibleRect(coveredBounds)) return false;
        return clipboardToolbar ? Rect.intersects(panelBounds, coveredBounds) : panelBounds.contains(coveredBounds);
    }

    private static boolean isInside(View child, View parent) {
        for (View view = child; view != null;
                view = view.getParent() instanceof View ancestor ? ancestor : null) {
            if (view == parent) return true;
        }
        return false;
    }

    private void restoreCoveredViews() {
        for (Map.Entry<View, Float> entry : coveredViews.entrySet()) restoreCoveredView(entry.getKey(), entry.getValue());
        coveredViews.clear();
    }

    private static void restoreCoveredView(View view, float alpha) {
        if (view != null && view.getAlpha() == 0f) view.setAlpha(alpha);
    }

    private void scan(View view, Zone parentZone, boolean ownsPanel, int depth) {
        if (depth >= 32 || coveredViews.containsKey(view)) return;
        int id = view.getId();
        boolean switchPlate = id == SWITCH_BACKPLATE || id == CHOOSER_BACKPLATE;
        if (!switchPlate && view.getVisibility() != View.VISIBLE) return;
        String name = view.getClass().getName();
        Zone zone = surfaceZone(name, parentZone);
        if (view != root) {
            Drawable original = view.getBackground();
            Drawable control = KeyboardControls.background(this, view, original);
            boolean boundary = zone != parentZone;
            boolean clipboardEntry = zone == Zone.CLIPBOARD && (id == CLIPBOARD_ENTRY || id == PHRASE_ENTRY);
            if (view.getId() == LOGO_ICON && view instanceof ImageView image) styleLogo(image);
            if (control != null) {
                if (control != original) replace(view, original, control, false);
            } else if ((id == CLIPBOARD_TABS && parentZone == Zone.CLIPBOARD || id == CANDIDATE_TABS)
                    && view instanceof ViewGroup tabs) {
                if (!(original instanceof SegmentedTabs)) replace(tabs, original, new SegmentedTabs(this, tabs), false);
            } else if (insideSegmentedTabs(view)) {
                if (hasTabBackground(original)) {
                    replace(view, original, new ColorDrawable(Color.TRANSPARENT), false);
                }
            } else if (clipboardEntry) {
                styleClipboardEntry(view, original, id == CLIPBOARD_ENTRY ? CLIPBOARD_TEXT : PHRASE_TEXT);
            } else if (id == CHOOSER_CARD && inChooser(view)) {
                if (!(original instanceof GroupCard)) {
                    replace(view, original, new GroupCard(this, view), false);
                }
            } else if (isButton(view, zone, boundary) && !KeyboardBackdrop.isBlurDrawable(original)) {
                replaceButton(view, original);
            } else if (ownsPanel && (switchPlate || boundary || isKeyboardPanel(view, name) || isChooserPlate(view))
                    && isFlatBackground(original)) {
                replace(view, original, new ColorDrawable(Color.TRANSPARENT), true);
            }
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) scan(group.getChildAt(i), zone, ownsPanel, depth + 1);
        }
    }

    private boolean isButton(View view, Zone zone, boolean boundary) {
        if (ClipboardPreviewControls.ownsButton(view)) return false;
        if (keyView != null && keyView.isInstance(view)) return true;
        if (zone == Zone.EMOJI && view.getId() == EMOJI_CATEGORY) return true;
        if (zone == Zone.TOOLBAR && ToolbarPager.isPageButton(view)) return true;
        if (zone == Zone.TOOLBAR && view.getClass().getName().endsWith(".S10SettingItemView")) return true;
        if (zone == Zone.TOOLBAR || zone == Zone.KEYBOARD) {
            int id = view.getId();
            if (id == TOOLBAR_ITEM || id == TOOLBAR_HIDE || id == TOOLBAR_RECENT || id == SETTINGS_BACK
                    || id == CANDIDATE_MORE || id == CANDIDATE_CLOSE || id == CANDIDATE_PACK_UP) return true;
            if (id == LOGO_CONTAINER || id == LOGO_ICON) return false;
        }
        if (zone == Zone.KEYBOARD || boundary || !view.isClickable() || view.getWidth() <= 0
                || view.getHeight() <= 0 || view.getWidth() >= root.getWidth() * .94f) return false;
        float density = view.getResources().getDisplayMetrics().density;
        if (zone == Zone.CLIPBOARD) return view.getBackground() != null && view.getHeight() <= 120f * density;
        return (view instanceof ImageView || view.getBackground() != null)
                && view.getHeight() <= 72f * density;
    }

    private static Zone surfaceZone(String name, Zone inherited) {
        if (!name.startsWith(PREFIX)) return inherited;
        String relative = name.substring(PREFIX.length());
        if (relative.startsWith("clipboard.") || relative.startsWith("keyboard.S15CustomPhraseAndClipboard")
                || relative.startsWith("keyboard.S29Clipboard")) return Zone.CLIPBOARD;
        if (relative.startsWith("keyboard.selfdraw.S11Emoji") || relative.startsWith("emoji.")) return Zone.EMOJI;
        if (relative.startsWith("candidate.") || relative.startsWith("view.toolbar.")
                || relative.startsWith("view.settingkeyboard.") || relative.equals("keyboard.S10SettingsKeyboard")
                || name.equals(KEYBOARD_CHOOSER)) {
            return Zone.TOOLBAR;
        }
        return inherited;
    }

    private static boolean isKeyboardPanel(View view, String name) {
        if (view.getClass() == View.class && (view.getId() == CANDIDATE_BACKPLATE
                || view.getId() == KEYBOARD_BACKPLATE)) return true;
        return name.startsWith(PREFIX + "keyboard.selfdraw.")
                || name.equals(PREFIX + "candidate.ImeCandidateView");
    }

    private static boolean insideSegmentedTabs(View view) {
        for (View current = view; current.getParent() instanceof View parent; current = parent) {
            if (parent.getBackground() instanceof SegmentedTabs) return true;
        }
        return false;
    }

    private static boolean hasTabBackground(Drawable background) {
        return background != null && (!(background instanceof ColorDrawable color) || color.getColor() != Color.TRANSPARENT);
    }

    boolean inChooser(View view) {
        for (View current = view; current != null && current != root;
                current = current.getParent() instanceof View parent ? parent : null) {
            String name = current.getClass().getName();
            if (name.equals(KEYBOARD_CHOOSER) || name.equals(INLINE_CHOOSER)) return true;
        }
        return false;
    }

    private boolean isChooserPlate(View view) {
        return overlayPanel != null && KEYBOARD_CHOOSER.equals(overlayPanel.getClass().getName())
                && isInside(view, overlayPanel) && !view.isClickable()
                && view.getWidth() >= root.getWidth() * .85f
                && view.getHeight() >= overlayPanel.getHeight() * .55f;
    }

    private static boolean isFlatBackground(Drawable drawable) {
        return drawable instanceof GradientDrawable
                || drawable instanceof ColorDrawable color && Color.alpha(color.getColor()) > 0;
    }

    private void styleLogo(ImageView view) {
        Drawable current = view.getDrawable();
        if (current == null || current == logo) return;
        restoreLogo();
        ToolbarLogo replacement = ToolbarLogo.create(this, view, current);
        if (replacement != null) {
            logo = replacement;
            logoView = new WeakReference<>(view);
            view.setImageDrawable(replacement);
        }
    }

    private void restoreLogo() {
        if (logoView != null) {
            ImageView view = logoView.get();
            if (view != null && view.getDrawable() == logo) view.setImageDrawable(logo.original);
        }
        logoView = null;
        logo = null;
    }

    private void replaceButton(View view, Drawable original) {
        if (original instanceof ButtonBackground) return;
        replace(view, original, new ButtonBackground(this, view, original), false);
        if (!hasStyledButtons) {
            hasStyledButtons = true;
            module.trace("View button material applied");
        }
    }

    private void replace(View view, Drawable original, Drawable replacement, boolean panel) {
        AppliedBackground previous = changed.get(view);
        if (previous != null && view.getBackground() == previous.replacement) return;
        setBackground(view, replacement);
        changed.put(view, new AppliedBackground(original, replacement, panel));
    }

    private void restoreChildPanels() {
        Iterator<Map.Entry<View, AppliedBackground>> iterator = changed.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<View, AppliedBackground> entry = iterator.next();
            if (!entry.getValue().panel) continue;
            reset(entry.getKey(), entry.getValue());
            iterator.remove();
        }
    }

    private static void reset(View view, AppliedBackground background) {
        if (view != null && view.getBackground() == background.replacement) setBackground(view, background.original);
    }

    private boolean isDark() {
        if (readDarkSkin != null) {
            try {
                return (boolean) readDarkSkin.invoke(null);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // The input view can be attached before the skin singleton is initialized.
            }
        }
        return isSystemDark();
    }

    private boolean isSystemDark() {
        int mode = service.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    static void invalidateTree(View view) {
        if (view.getVisibility() != View.VISIBLE) return;
        view.invalidate();
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) invalidateTree(group.getChildAt(i));
        }
    }

    static void setBackground(View view, Drawable background) {
        boolean relative = view.isPaddingRelative();
        int start = relative ? view.getPaddingStart() : view.getPaddingLeft();
        int end = relative ? view.getPaddingEnd() : view.getPaddingRight();
        int top = view.getPaddingTop();
        int bottom = view.getPaddingBottom();
        view.setBackground(background);
        if (relative) view.setPaddingRelative(start, top, end, bottom);
        else view.setPadding(start, top, end, bottom);
    }

    private enum Zone { KEYBOARD, TOOLBAR, EMOJI, CLIPBOARD }

    private record AppliedBackground(Drawable original, Drawable replacement, boolean panel) {}
}
