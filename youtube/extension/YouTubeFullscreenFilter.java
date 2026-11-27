package app.morphe.extension.youtube.patches.fullscreen;

import android.app.Activity;
import android.app.Application;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.view.DisplayCutout;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

/**
 * Independent YouTube edge-to-edge fullscreen helper that eliminates the black status-bar gap
 * left above portrait videos (especially on Android tablets and HyperOS/MIUI) even when the
 * system status bar is hidden.
 */
@SuppressWarnings("unused")
public final class YouTubeFullscreenFilter {

    private static final String[] WATCH_CONTAINER_NAMES = {
            "next_gen_watch_container",
            "next_gen_watch_layout",
            "watch_while_layout",
            "watch_while_root",
            "pane_fragment_container"
    };

    private static final String[] STATUS_BAR_SPACER_NAMES = {
            "status_bar_background",
            "status_bar_scrim",
            "status_bar_view",
            "status_bar_placeholder",
            "status_bar_spacer",
            "status_bar_tint"
    };

    private static final String[] PLAYER_CONTAINER_NAMES = {
            "player_fragment_container",
            "next_gen_watch_player",
            "watch_player",
            "player_view",
            "player_overlays"
    };

    private static final WeakHashMap<Activity, ActivityState> STATES = new WeakHashMap<>();
    private static volatile boolean lifecycleRegistered = false;
    private static volatile WeakReference<Activity> currentActivityRef = new WeakReference<>(null);

    private static final class ActivityState {
        boolean listenersInstalled = false;
        boolean insetStrippersInstalled = false;
        int cachedStatusBarHeightPx = -1;
        int appliedShiftPx = 0;
        WeakReference<View> shiftedViewRef = new WeakReference<>(null);
        int originalShiftedHeight = ViewGroup.LayoutParams.MATCH_PARENT;
        ViewTreeObserver.OnGlobalLayoutListener globalLayoutListener;
        ViewTreeObserver.OnPreDrawListener preDrawListener;
        ViewTreeObserver.OnWindowFocusChangeListener focusChangeListener;
        final int[] tmpLocA = new int[2];
        final int[] tmpLocB = new int[2];
    }

    private YouTubeFullscreenFilter() {
    }

    /**
     * Hooked at index 0 of YouTube's MainActivity.onCreate(Bundle).
     * Never touches getWindow()/getDecorView() synchronously before super.onCreate().
     */
    public static void onMainActivityCreated(Activity activity) {
        if (activity == null) {
            return;
        }
        currentActivityRef = new WeakReference<>(activity);
        registerLifecycleOnce(activity);
    }

    /**
     * Hooked after YouTube reads stable insets for the player (and after official Morphe's
     * HideStatusBarPatch.getPlayerStableInsets if installed).
     */
    public static Rect stripPlayerTopInset(Rect insets) {
        if (insets == null || insets.top == 0) {
            return insets;
        }
        Activity activity = currentActivityRef.get();
        if (activity != null && isFloatingOrSplitMode(activity)) {
            return insets;
        }
        return new Rect(insets.left, 0, insets.right, insets.bottom);
    }

    /**
     * Hooked after YouTube copies system window insets for top-anchored layouts (and after
     * official Morphe's HideStatusBarPatch.setSystemWindowInsetTop if installed).
     */
    public static void stripSystemWindowInsetTop(Rect insets, View view) {
        if (insets == null || insets.top == 0) {
            return;
        }
        Activity activity = currentActivityRef.get();
        if (activity != null && isFloatingOrSplitMode(activity)) {
            return;
        }
        insets.top = 0;
    }

    /**
     * Hooked on YouTube's internal status-bar background view show/color method.
     * Collapses the view completely so it can never draw a solid black strip at the top.
     */
    public static void collapseStatusBarBackground(View view) {
        if (view == null) {
            return;
        }
        Activity activity = currentActivityRef.get();
        if (activity != null && isFloatingOrSplitMode(activity)) {
            return;
        }
        try {
            view.setVisibility(View.GONE);
            view.setAlpha(0f);
            view.setBackgroundColor(Color.TRANSPARENT);
            ViewGroup.LayoutParams lp = view.getLayoutParams();
            if (lp != null && lp.height > 0) {
                int maxBand = getTopBandPx(view.getResources(), -1);
                if (lp.height <= maxBand) {
                    lp.height = 0;
                    view.setLayoutParams(lp);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void registerLifecycleOnce(Activity activity) {
        if (lifecycleRegistered) {
            return;
        }
        Application app = activity.getApplication();
        if (app == null) {
            return;
        }
        synchronized (YouTubeFullscreenFilter.class) {
            if (lifecycleRegistered) {
                return;
            }
            lifecycleRegistered = true;
            app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override
                public void onActivityCreated(Activity act, Bundle savedInstanceState) {
                    if (isTargetActivity(act)) {
                        currentActivityRef = new WeakReference<>(act);
                    }
                }

                @Override
                public void onActivityStarted(Activity act) {
                    if (!isTargetActivity(act)) {
                        return;
                    }
                    currentActivityRef = new WeakReference<>(act);
                    attachAndApply(act);
                }

                @Override
                public void onActivityResumed(Activity act) {
                    if (!isTargetActivity(act)) {
                        return;
                    }
                    currentActivityRef = new WeakReference<>(act);
                    attachAndApply(act);
                }

                @Override
                public void onActivityPaused(Activity act) {
                }

                @Override
                public void onActivityStopped(Activity act) {
                }

                @Override
                public void onActivitySaveInstanceState(Activity act, Bundle outState) {
                }

                @Override
                public void onActivityDestroyed(Activity act) {
                    detachActivity(act);
                }
            });
        }
    }

    private static boolean isTargetActivity(Activity activity) {
        if (activity == null) {
            return false;
        }
        String name = activity.getClass().getName();
        return name.endsWith(".MainActivity")
                || name.endsWith(".WatchWhileActivity")
                || name.contains("watchwhile");
    }

    private static boolean isFloatingOrSplitMode(Activity activity) {
        if (activity == null) {
            return false;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activity.isInMultiWindowMode()) {
                return true;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activity.isInPictureInPictureMode()) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static ActivityState getState(Activity activity) {
        synchronized (STATES) {
            ActivityState state = STATES.get(activity);
            if (state == null) {
                state = new ActivityState();
                STATES.put(activity, state);
            }
            return state;
        }
    }

    private static void detachActivity(Activity activity) {
        if (activity == null) {
            return;
        }
        ActivityState state;
        synchronized (STATES) {
            state = STATES.remove(activity);
        }
        if (state == null) {
            return;
        }
        restoreShift(state);
        try {
            Window window = activity.getWindow();
            if (window == null) {
                return;
            }
            View decor = window.peekDecorView();
            if (decor == null) {
                return;
            }
            ViewTreeObserver vto = decor.getViewTreeObserver();
            if (vto != null && vto.isAlive()) {
                if (state.globalLayoutListener != null) {
                    vto.removeOnGlobalLayoutListener(state.globalLayoutListener);
                }
                if (state.preDrawListener != null) {
                    vto.removeOnPreDrawListener(state.preDrawListener);
                }
                if (state.focusChangeListener != null) {
                    vto.removeOnWindowFocusChangeListener(state.focusChangeListener);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void attachAndApply(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        final ActivityState state = getState(activity);
        if (isFloatingOrSplitMode(activity)) {
            restoreShift(state);
            return;
        }

        configureWindow(activity);

        Window window = activity.getWindow();
        if (window == null) {
            return;
        }
        final View decor = window.getDecorView();
        if (decor == null) {
            return;
        }

        installLayoutWatchers(activity, decor, state);

        // Post inset stripper installation so it attaches AFTER upstream Morphe's
        // HideStatusBarPatch.addDisplayCutoutInset listener on android.R.id.content.
        final WeakReference<Activity> actRef = new WeakReference<>(activity);
        decor.post(new Runnable() {
            @Override
            public void run() {
                Activity act = actRef.get();
                if (act == null || act.isFinishing() || act.isDestroyed()) {
                    return;
                }
                if (isFloatingOrSplitMode(act)) {
                    restoreShift(state);
                    return;
                }
                configureWindow(act);
                installInsetStrippers(act, state);
                enforceEdgeToEdgeLayout(act, state, false);
            }
        });
    }

    @SuppressWarnings("deprecation")
    private static void configureWindow(Activity activity) {
        if (activity == null || isFloatingOrSplitMode(activity)) {
            return;
        }
        Window window = activity.getWindow();
        if (window == null) {
            return;
        }
        try {
            WindowManager.LayoutParams lp = window.getAttributes();
            if (lp != null) {
                boolean attrsChanged = false;
                if (Build.VERSION.SDK_INT >= 30) {
                    if (lp.layoutInDisplayCutoutMode != WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS) {
                        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
                        attrsChanged = true;
                    }
                } else if (Build.VERSION.SDK_INT >= 28) {
                    if (lp.layoutInDisplayCutoutMode != WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES) {
                        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                        attrsChanged = true;
                    }
                }
                if (attrsChanged) {
                    window.setAttributes(lp);
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            window.setStatusBarColor(Color.TRANSPARENT);
        } catch (Throwable ignored) {
        }

        if (Build.VERSION.SDK_INT >= 29) {
            try {
                window.setStatusBarContrastEnforced(false);
            } catch (Throwable ignored) {
            }
        }

        if (Build.VERSION.SDK_INT >= 30) {
            try {
                window.setDecorFitsSystemWindows(false);
            } catch (Throwable ignored) {
            }
            try {
                WindowInsetsController controller = window.getInsetsController();
                if (controller != null) {
                    controller.setSystemBarsBehavior(
                            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    );
                    controller.hide(WindowInsets.Type.statusBars());
                }
            } catch (Throwable ignored) {
            }
        } else {
            try {
                View decor = window.getDecorView();
                if (decor != null) {
                    int flags = decor.getSystemUiVisibility();
                    int desired = flags
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
                    if (desired != flags) {
                        decor.setSystemUiVisibility(desired);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static void installInsetStrippers(Activity activity, final ActivityState state) {
        Window window = activity.getWindow();
        if (window == null) {
            return;
        }
        final View decor = window.getDecorView();
        final View content = window.findViewById(android.R.id.content);
        final WeakReference<Activity> actRef = new WeakReference<>(activity);

        if (decor != null) {
            decor.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override
                public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                    Activity act = actRef.get();
                    if (act == null || isFloatingOrSplitMode(act)) {
                        return v.onApplyWindowInsets(insets);
                    }
                    WindowInsets stripped = stripTopWindowInsets(insets);
                    if (v.getPaddingTop() != 0) {
                        v.setPadding(v.getPaddingLeft(), 0, v.getPaddingRight(), v.getPaddingBottom());
                    }
                    return v.onApplyWindowInsets(stripped);
                }
            });
        }

        if (content != null) {
            // Intentionally replaces any listener set by upstream HideStatusBarPatch.addDisplayCutoutInset
            // which otherwise adds top cutout padding to android.R.id.content.
            content.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override
                public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                    Activity act = actRef.get();
                    if (act == null || isFloatingOrSplitMode(act)) {
                        return v.onApplyWindowInsets(insets);
                    }
                    WindowInsets stripped = stripTopWindowInsets(insets);
                    if (v.getPaddingTop() != 0) {
                        v.setPadding(v.getPaddingLeft(), 0, v.getPaddingRight(), v.getPaddingBottom());
                    }
                    return v.onApplyWindowInsets(stripped);
                }
            });
            if (content.getPaddingTop() != 0) {
                content.setPadding(
                        content.getPaddingLeft(),
                        0,
                        content.getPaddingRight(),
                        content.getPaddingBottom()
                );
            }
        }

        state.insetStrippersInstalled = true;
        if (decor != null) {
            try {
                decor.requestApplyInsets();
            } catch (Throwable ignored) {
            }
        }
    }

    @SuppressWarnings("deprecation")
    private static WindowInsets stripTopWindowInsets(WindowInsets insets) {
        if (insets == null) {
            return null;
        }
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                WindowInsets.Builder builder = new WindowInsets.Builder(insets);

                zeroTypeTop(builder, insets, WindowInsets.Type.statusBars(), true);
                zeroTypeTop(builder, insets, WindowInsets.Type.displayCutout(), true);
                zeroTypeTop(builder, insets, WindowInsets.Type.tappableElement(), true);
                zeroTypeTop(builder, insets, WindowInsets.Type.captionBar(), true);
                zeroTypeTop(builder, insets, WindowInsets.Type.systemGestures(), false);
                zeroTypeTop(builder, insets, WindowInsets.Type.mandatorySystemGestures(), false);

                DisplayCutout cutout = insets.getDisplayCutout();
                if (cutout != null
                        && cutout.getSafeInsetTop() > 0
                        && cutout.getSafeInsetLeft() == 0
                        && cutout.getSafeInsetRight() == 0
                        && cutout.getSafeInsetBottom() == 0) {
                    try {
                        builder.setDisplayCutout(null);
                    } catch (Throwable ignored) {
                    }
                }

                return builder.build();
            } catch (Throwable ignored) {
            }
        } else if (Build.VERSION.SDK_INT == 29) {
            try {
                WindowInsets.Builder builder = new WindowInsets.Builder(insets);
                Insets sys = insets.getSystemWindowInsets();
                if (sys.top != 0) {
                    builder.setSystemWindowInsets(Insets.of(sys.left, 0, sys.right, sys.bottom));
                }
                Insets stable = insets.getStableInsets();
                if (stable.top != 0) {
                    builder.setStableInsets(Insets.of(stable.left, 0, stable.right, stable.bottom));
                }
                Insets tappable = insets.getTappableElementInsets();
                if (tappable.top != 0) {
                    builder.setTappableElementInsets(Insets.of(tappable.left, 0, tappable.right, tappable.bottom));
                }
                Insets mandatory = insets.getMandatorySystemGestureInsets();
                if (mandatory.top != 0) {
                    builder.setMandatorySystemGestureInsets(Insets.of(mandatory.left, 0, mandatory.right, mandatory.bottom));
                }
                DisplayCutout cutout = insets.getDisplayCutout();
                if (cutout != null
                        && cutout.getSafeInsetTop() > 0
                        && cutout.getSafeInsetLeft() == 0
                        && cutout.getSafeInsetRight() == 0
                        && cutout.getSafeInsetBottom() == 0) {
                    builder.setDisplayCutout(null);
                }
                return builder.build();
            } catch (Throwable ignored) {
            }
        }

        try {
            if (insets.getSystemWindowInsetTop() != 0) {
                return insets.replaceSystemWindowInsets(
                        insets.getSystemWindowInsetLeft(),
                        0,
                        insets.getSystemWindowInsetRight(),
                        insets.getSystemWindowInsetBottom()
                );
            }
        } catch (Throwable ignored) {
        }
        return insets;
    }

    private static void zeroTypeTop(
            WindowInsets.Builder builder,
            WindowInsets source,
            int type,
            boolean alsoIgnoringVisibility
    ) {
        if (Build.VERSION.SDK_INT < 30) {
            return;
        }
        try {
            Insets cur = source.getInsets(type);
            if (cur.top != 0) {
                builder.setInsets(type, Insets.of(cur.left, 0, cur.right, cur.bottom));
            }
        } catch (Throwable ignored) {
        }
        if (alsoIgnoringVisibility) {
            try {
                Insets curIgn = source.getInsetsIgnoringVisibility(type);
                if (curIgn.top != 0) {
                    builder.setInsetsIgnoringVisibility(
                            type,
                            Insets.of(curIgn.left, 0, curIgn.right, curIgn.bottom)
                    );
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static void installLayoutWatchers(Activity activity, View decor, final ActivityState state) {
        if (state.listenersInstalled) {
            return;
        }
        ViewTreeObserver vto = decor.getViewTreeObserver();
        if (vto == null || !vto.isAlive()) {
            return;
        }
        final WeakReference<Activity> actRef = new WeakReference<>(activity);

        state.globalLayoutListener = new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                Activity act = actRef.get();
                if (act == null || act.isFinishing() || act.isDestroyed()) {
                    return;
                }
                if (isFloatingOrSplitMode(act)) {
                    restoreShift(state);
                    return;
                }
                configureWindow(act);
                enforceEdgeToEdgeLayout(act, state, false);
            }
        };

        state.preDrawListener = new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                Activity act = actRef.get();
                if (act != null && !act.isFinishing() && !act.isDestroyed()) {
                    if (isFloatingOrSplitMode(act)) {
                        restoreShift(state);
                    } else {
                        enforceEdgeToEdgeLayout(act, state, true);
                    }
                }
                return true;
            }
        };

        state.focusChangeListener = new ViewTreeObserver.OnWindowFocusChangeListener() {
            @Override
            public void onWindowFocusChanged(boolean hasFocus) {
                if (!hasFocus) {
                    return;
                }
                Activity act = actRef.get();
                if (act == null || act.isFinishing() || act.isDestroyed()) {
                    return;
                }
                if (isFloatingOrSplitMode(act)) {
                    restoreShift(state);
                    return;
                }
                configureWindow(act);
                installInsetStrippers(act, state);
                enforceEdgeToEdgeLayout(act, state, false);
            }
        };

        vto.addOnGlobalLayoutListener(state.globalLayoutListener);
        vto.addOnPreDrawListener(state.preDrawListener);
        vto.addOnWindowFocusChangeListener(state.focusChangeListener);
        state.listenersInstalled = true;
    }

    private static void enforceEdgeToEdgeLayout(Activity activity, ActivityState state, boolean preDrawOnly) {
        Window window = activity.getWindow();
        if (window == null) {
            return;
        }
        View decor = window.getDecorView();
        if (decor == null || decor.getWidth() <= 0 || decor.getHeight() <= 0) {
            return;
        }

        Resources res = activity.getResources();
        int topBandPx = getTopBandPx(res, state.cachedStatusBarHeightPx);
        if (state.cachedStatusBarHeightPx < 0) {
            state.cachedStatusBarHeightPx = readStatusBarHeightPx(res);
            topBandPx = getTopBandPx(res, state.cachedStatusBarHeightPx);
        }

        // 1. Hide Android's system statusBarBackground overlay view if present.
        View sysStatusBg = decor.findViewById(android.R.id.statusBarBackground);
        if (sysStatusBg != null) {
            if (sysStatusBg.getVisibility() != View.GONE) {
                sysStatusBg.setVisibility(View.GONE);
            }
            if (sysStatusBg.getAlpha() != 0f) {
                sysStatusBg.setAlpha(0f);
            }
        }

        // 2. Clear top padding / top margin on decor, decor's root child, and android.R.id.content.
        clearSmallTopInsetOnView(decor, topBandPx);
        if (decor instanceof ViewGroup) {
            ViewGroup decorGroup = (ViewGroup) decor;
            if (decorGroup.getChildCount() > 0) {
                View firstChild = decorGroup.getChildAt(0);
                if (firstChild != null && firstChild != sysStatusBg) {
                    clearSmallTopInsetOnView(firstChild, topBandPx);
                }
            }
        }

        View content = window.findViewById(android.R.id.content);
        if (content != null) {
            clearSmallTopInsetOnView(content, topBandPx);
            if (content instanceof ViewGroup) {
                ViewGroup contentGroup = (ViewGroup) content;
                if (contentGroup.getChildCount() > 0) {
                    clearSmallTopInsetOnView(contentGroup.getChildAt(0), topBandPx);
                }
            }
        }

        String pkg = activity.getPackageName();

        // 3. Clear YouTube watch/root container top padding/margin & collapse top status-bar spacers.
        View watchContainer = null;
        for (int i = 0; i < WATCH_CONTAINER_NAMES.length; i++) {
            View v = findByName(decor, res, pkg, WATCH_CONTAINER_NAMES[i]);
            if (v != null) {
                clearSmallTopInsetOnView(v, topBandPx);
                if (watchContainer == null) {
                    watchContainer = v;
                }
            }
        }

        for (int i = 0; i < STATUS_BAR_SPACER_NAMES.length; i++) {
            View spacer = findByName(decor, res, pkg, STATUS_BAR_SPACER_NAMES[i]);
            if (spacer != null) {
                collapseSpacerView(spacer, topBandPx);
            }
        }

        // 4. Portrait maximized video player flush safety net:
        //    Some YouTube tablet layouts still read android:dimen/status_bar_height directly
        //    and lay out the portrait video player below y = 0 even when WindowInsets.top == 0.
        int decorW = decor.getWidth();
        int decorH = decor.getHeight();
        boolean isPortrait = decorH > decorW;
        if (!isPortrait) {
            restoreShift(state);
            return;
        }

        View playerView = findPortraitMaximizedPlayer(decor, watchContainer, res, pkg, decorW, decorH);
        if (playerView == null) {
            restoreShift(state);
            return;
        }

        // First try clearing residual top padding/margin on ancestors between playerView and content.
        if (!preDrawOnly) {
            clearAncestorTopInsets(playerView, content, topBandPx);
        }

        decor.getLocationOnScreen(state.tmpLocA);
        playerView.getLocationOnScreen(state.tmpLocB);
        int currentScreenGap = state.tmpLocB[1] - state.tmpLocA[1];
        int naturalGap = currentScreenGap + state.appliedShiftPx;

        if (naturalGap > 0 && naturalGap <= topBandPx) {
            View shiftTarget = watchContainer != null ? watchContainer : playerView;
            applyShift(state, shiftTarget, naturalGap, decorH);
        } else {
            restoreShift(state);
        }
    }

    private static View findPortraitMaximizedPlayer(
            View decor,
            View watchContainer,
            Resources res,
            String pkg,
            int decorW,
            int decorH
    ) {
        int minPlayerW = (int) (decorW * 0.88f);
        int minPlayerH = dpToPx(res, 120);
        int maxPlayerH = (int) (decorH * 0.65f);

        for (int i = 0; i < PLAYER_CONTAINER_NAMES.length; i++) {
            View v = findByName(decor, res, pkg, PLAYER_CONTAINER_NAMES[i]);
            if (isMaximizedPortraitPlayerBox(v, minPlayerW, minPlayerH, maxPlayerH)) {
                return v;
            }
        }

        View searchRoot = watchContainer != null ? watchContainer : decor;
        return findPlayerSurfaceContainerRecursive(searchRoot, minPlayerW, minPlayerH, maxPlayerH, 0);
    }

    private static boolean isMaximizedPortraitPlayerBox(View v, int minW, int minH, int maxH) {
        if (v == null || !v.isShown()) {
            return false;
        }
        int w = v.getWidth();
        int h = v.getHeight();
        return w >= minW && h >= minH && h <= maxH;
    }

    private static View findPlayerSurfaceContainerRecursive(
            View node,
            int minW,
            int minH,
            int maxH,
            int depth
    ) {
        if (node == null || depth > 14 || !node.isShown()) {
            return null;
        }
        if (node instanceof SurfaceView || node instanceof TextureView) {
            if (isMaximizedPortraitPlayerBox(node, minW, minH, maxH)) {
                ViewParent parent = node.getParent();
                if (parent instanceof View) {
                    View parentView = (View) parent;
                    if (isMaximizedPortraitPlayerBox(parentView, minW, minH, maxH)) {
                        return parentView;
                    }
                }
                return node;
            }
            return null;
        }
        if (node instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) node;
            int count = vg.getChildCount();
            for (int i = 0; i < count; i++) {
                View found = findPlayerSurfaceContainerRecursive(vg.getChildAt(i), minW, minH, maxH, depth + 1);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void applyShift(ActivityState state, View target, int shiftPx, int decorH) {
        if (target == null || shiftPx <= 0) {
            restoreShift(state);
            return;
        }
        View prev = state.shiftedViewRef.get();
        if (prev != null && prev != target) {
            restoreShift(state);
        }

        boolean targetChanged = (prev != target);
        if (targetChanged) {
            ViewGroup.LayoutParams curLp = target.getLayoutParams();
            state.originalShiftedHeight = curLp != null ? curLp.height : ViewGroup.LayoutParams.MATCH_PARENT;
            state.shiftedViewRef = new WeakReference<>(target);
        }

        if (target.getTranslationY() != -shiftPx) {
            target.setTranslationY(-shiftPx);
        }

        // Extend height only when the target is a full-screen container (MATCH_PARENT) so the
        // bottom of the watch layout remains flush with the bottom of the screen.
        ViewGroup.LayoutParams lp = target.getLayoutParams();
        if (lp != null && state.originalShiftedHeight == ViewGroup.LayoutParams.MATCH_PARENT) {
            int desiredH = decorH + shiftPx;
            if (lp.height != desiredH) {
                lp.height = desiredH;
                target.setLayoutParams(lp);
            }
        }
        state.appliedShiftPx = shiftPx;
    }

    private static void restoreShift(ActivityState state) {
        if (state == null) {
            return;
        }
        View prev = state.shiftedViewRef.get();
        if (prev != null && state.appliedShiftPx != 0) {
            try {
                if (prev.getTranslationY() != 0f) {
                    prev.setTranslationY(0f);
                }
                ViewGroup.LayoutParams lp = prev.getLayoutParams();
                if (lp != null && lp.height != state.originalShiftedHeight) {
                    lp.height = state.originalShiftedHeight;
                    prev.setLayoutParams(lp);
                }
            } catch (Throwable ignored) {
            }
        }
        state.appliedShiftPx = 0;
        state.shiftedViewRef = new WeakReference<>(null);
        state.originalShiftedHeight = ViewGroup.LayoutParams.MATCH_PARENT;
    }

    private static void clearAncestorTopInsets(View leaf, View stopAt, int topBandPx) {
        View cur = leaf;
        int steps = 0;
        while (cur != null && steps < 12) {
            clearSmallTopInsetOnView(cur, topBandPx);
            if (cur == stopAt) {
                break;
            }
            ViewParent p = cur.getParent();
            cur = (p instanceof View) ? (View) p : null;
            steps++;
        }
    }

    private static void clearSmallTopInsetOnView(View v, int topBandPx) {
        if (v == null) {
            return;
        }
        try {
            int padTop = v.getPaddingTop();
            if (padTop > 0 && padTop <= topBandPx) {
                v.setPadding(v.getPaddingLeft(), 0, v.getPaddingRight(), v.getPaddingBottom());
            }
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
                if (mlp.topMargin > 0 && mlp.topMargin <= topBandPx) {
                    mlp.topMargin = 0;
                    v.setLayoutParams(mlp);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void collapseSpacerView(View spacer, int topBandPx) {
        if (spacer == null) {
            return;
        }
        try {
            int h = spacer.getHeight();
            ViewGroup.LayoutParams lp = spacer.getLayoutParams();
            int lpH = lp != null ? lp.height : 0;
            if ((h > 0 && h <= topBandPx) || (lpH > 0 && lpH <= topBandPx)) {
                if (spacer.getVisibility() != View.GONE) {
                    spacer.setVisibility(View.GONE);
                }
                if (spacer.getAlpha() != 0f) {
                    spacer.setAlpha(0f);
                }
                if (lp != null && lp.height != 0) {
                    lp.height = 0;
                    spacer.setLayoutParams(lp);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static View findByName(View root, Resources res, String pkg, String idName) {
        if (root == null || res == null || pkg == null) {
            return null;
        }
        int id = res.getIdentifier(idName, "id", pkg);
        if (id == 0) {
            return null;
        }
        return root.findViewById(id);
    }

    private static int readStatusBarHeightPx(Resources res) {
        if (res == null) {
            return 0;
        }
        try {
            int resId = res.getIdentifier("status_bar_height", "dimen", "android");
            if (resId > 0) {
                return res.getDimensionPixelSize(resId);
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static int getTopBandPx(Resources res, int cachedStatusBarHeightPx) {
        int sb = cachedStatusBarHeightPx >= 0 ? cachedStatusBarHeightPx : readStatusBarHeightPx(res);
        int minBand = dpToPx(res, 56);
        int sbBand = sb + dpToPx(res, 16);
        return Math.max(minBand, sbBand);
    }

    private static int dpToPx(Resources res, int dp) {
        if (res == null) {
            return dp * 2;
        }
        return Math.round(dp * res.getDisplayMetrics().density);
    }
}
