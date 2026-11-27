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
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toolbar;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.WeakHashMap;

/**
 * Independent YouTube fullscreen & Home toolbar helper engineered for zero scroll overhead:
 * - Zero per-frame hooks (no OnPreDrawListener, no OnGlobalLayoutListener during scroll).
 * - Zero view-tree walks, findViewById calls, reflection, or LayoutParams mutations during scroll.
 * - Purely event-driven: runs only on Activity lifecycle, window focus, window resize/rotation,
 *   WindowInsets dispatch, and idle (top == 0) AppBarLayout chrome updates.
 */
@SuppressWarnings("unused")
public final class YouTubeFullscreenFilter {

    private static final int SCROLL_FLAG_SCROLL = 0x1;
    private static final long APPBAR_UPDATE_DEBOUNCE_MS = 60L;
    private static final int MAX_COLD_START_RETRIES = 4;

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

    private static final String[] APP_BAR_NAMES = {
            "appbar_layout",
            "app_bar_layout",
            "action_bar_app_bar_layout",
            "toolbar_app_bar",
            "toolbar_appbar"
    };

    private static final String[] NAV_BAR_NAMES = {
            "pivot_bar",
            "navigation_rail",
            "bottom_bar_container"
    };

    private static final WeakHashMap<Activity, ActivityState> STATES = new WeakHashMap<>();
    private static final HashMap<Class<?>, ScrollFlagsAccessor> SCROLL_FLAGS_CACHE = new HashMap<>();
    private static final HashMap<Class<?>, Method> TOOLBAR_GET_TITLE_CACHE = new HashMap<>();
    private static final ScrollFlagsAccessor NO_ACCESSOR = new ScrollFlagsAccessor(null, null, null);
    private static final Method NO_METHOD;

    static {
        Method sentinel = null;
        try {
            sentinel = Object.class.getMethod("toString");
        } catch (Throwable ignored) {
        }
        NO_METHOD = sentinel;
    }

    private static volatile boolean removeTopBlackBarEnabled = false;
    private static volatile boolean homeScrollToolbarEnabled = false;
    private static volatile boolean lifecycleRegistered = false;
    private static volatile WeakReference<Activity> currentActivityRef = new WeakReference<>(null);

    // Process-wide cached resource IDs (resolved once, never during scroll).
    private static volatile boolean idsResolved = false;
    private static int[] watchContainerIds = new int[0];
    private static int[] statusBarSpacerIds = new int[0];
    private static int[] appBarIds = new int[0];
    private static int[] navBarIds = new int[0];
    private static int cachedStatusBarHeightPx = -1;

    private static final class ScrollFlagsAccessor {
        final Method getter;
        final Method setter;
        final Field field;

        ScrollFlagsAccessor(Method getter, Method setter, Field field) {
            this.getter = getter;
            this.setter = setter;
            this.field = field;
        }

        boolean isValid() {
            return (getter != null && setter != null) || field != null;
        }

        int get(ViewGroup.LayoutParams lp) throws Exception {
            if (getter != null) {
                Object val = getter.invoke(lp);
                if (val instanceof Integer) {
                    return (Integer) val;
                }
            }
            if (field != null) {
                return field.getInt(lp);
            }
            return -1;
        }

        void set(ViewGroup.LayoutParams lp, int flags) throws Exception {
            if (setter != null) {
                setter.invoke(lp, flags);
                return;
            }
            if (field != null) {
                field.setInt(lp, flags);
            }
        }
    }

    private static final class ActivityState {
        boolean decorListenersInstalled = false;
        boolean sysStatusBgSearched = false;
        int coldStartRetries = 0;

        WeakReference<View> cachedSysStatusBgRef = new WeakReference<>(null);
        WeakReference<View> cachedWatchContainerRef = new WeakReference<>(null);
        WeakReference<ViewGroup> cachedAppBarRef = new WeakReference<>(null);
        WeakReference<ViewGroup> cachedNavBarRef = new WeakReference<>(null);
        boolean appBarListenerAttached = false;

        final WeakHashMap<View, Integer> originalScrollFlags = new WeakHashMap<>();

        View.OnLayoutChangeListener decorResizeListener;
        ViewTreeObserver.OnWindowFocusChangeListener focusChangeListener;
        View.OnLayoutChangeListener appBarLayoutChangeListener;
        Runnable debouncedAppBarRunnable;
        Runnable coldStartRetryRunnable;
    }

    private YouTubeFullscreenFilter() {
    }

    /**
     * Hooked at index 0 of YouTube's MainActivity.onCreate(Bundle) by "Remove top black bar".
     * Never touches getWindow()/getDecorView() synchronously before super.onCreate().
     */
    public static void onMainActivityCreated(Activity activity) {
        removeTopBlackBarEnabled = true;
        if (activity == null) {
            return;
        }
        currentActivityRef = new WeakReference<>(activity);
        registerLifecycleOnce(activity);
    }

    /**
     * Hooked at index 0 of YouTube's MainActivity.onCreate(Bundle) by "Scroll away home toolbar".
     */
    public static void onHomeScrollToolbarEnabled(Activity activity) {
        homeScrollToolbarEnabled = true;
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
        if (!removeTopBlackBarEnabled || insets == null || insets.top == 0) {
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
        if (!removeTopBlackBarEnabled || insets == null || insets.top == 0) {
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
        if (!removeTopBlackBarEnabled || view == null) {
            return;
        }
        Activity activity = currentActivityRef.get();
        if (activity != null && isFloatingOrSplitMode(activity)) {
            return;
        }
        try {
            if (view.getVisibility() != View.GONE) {
                view.setVisibility(View.GONE);
            }
            if (view.getAlpha() != 0f) {
                view.setAlpha(0f);
            }
            view.setBackgroundColor(Color.TRANSPARENT);
            ViewGroup.LayoutParams lp = view.getLayoutParams();
            if (lp != null && lp.height > 0) {
                int maxBand = getTopBandPx(view.getResources());
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

    private static void resolveResourceIdsOnce(Resources res, String pkg) {
        if (idsResolved || res == null || pkg == null) {
            return;
        }
        synchronized (YouTubeFullscreenFilter.class) {
            if (idsResolved) {
                return;
            }
            watchContainerIds = resolveIdArray(res, pkg, WATCH_CONTAINER_NAMES);
            statusBarSpacerIds = resolveIdArray(res, pkg, STATUS_BAR_SPACER_NAMES);
            appBarIds = resolveIdArray(res, pkg, APP_BAR_NAMES);
            navBarIds = resolveIdArray(res, pkg, NAV_BAR_NAMES);
            cachedStatusBarHeightPx = readStatusBarHeightPx(res);
            idsResolved = true;
        }
    }

    private static int[] resolveIdArray(Resources res, String pkg, String[] names) {
        int[] raw = new int[names.length];
        int count = 0;
        for (int i = 0; i < names.length; i++) {
            int id = res.getIdentifier(names[i], "id", pkg);
            if (id != 0) {
                raw[count++] = id;
            }
        }
        if (count == raw.length) {
            return raw;
        }
        int[] trimmed = new int[count];
        System.arraycopy(raw, 0, trimmed, 0, count);
        return trimmed;
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
        try {
            ViewGroup appBar = state.cachedAppBarRef.get();
            if (appBar != null) {
                if (state.appBarLayoutChangeListener != null) {
                    appBar.removeOnLayoutChangeListener(state.appBarLayoutChangeListener);
                }
                if (state.debouncedAppBarRunnable != null) {
                    appBar.removeCallbacks(state.debouncedAppBarRunnable);
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            Window window = activity.getWindow();
            if (window == null) {
                return;
            }
            View decor = window.peekDecorView();
            if (decor == null) {
                return;
            }
            if (state.coldStartRetryRunnable != null) {
                decor.removeCallbacks(state.coldStartRetryRunnable);
            }
            if (state.decorResizeListener != null) {
                decor.removeOnLayoutChangeListener(state.decorResizeListener);
            }
            ViewTreeObserver vto = decor.getViewTreeObserver();
            if (vto != null && vto.isAlive() && state.focusChangeListener != null) {
                vto.removeOnWindowFocusChangeListener(state.focusChangeListener);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void attachAndApply(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        resolveResourceIdsOnce(activity.getResources(), activity.getPackageName());

        final ActivityState state = getState(activity);
        if (removeTopBlackBarEnabled && !isFloatingOrSplitMode(activity)) {
            configureWindow(activity);
        }

        Window window = activity.getWindow();
        if (window == null) {
            return;
        }
        final View decor = window.getDecorView();
        if (decor == null) {
            return;
        }

        installDecorEventListeners(activity, decor, state);

        // Post inset stripper & toolbar setup so we attach AFTER upstream Morphe's
        // HideStatusBarPatch.addDisplayCutoutInset listener on android.R.id.content.
        final WeakReference<Activity> actRef = new WeakReference<>(activity);
        decor.post(new Runnable() {
            @Override
            public void run() {
                Activity act = actRef.get();
                if (act == null || act.isFinishing() || act.isDestroyed()) {
                    return;
                }
                applyEventDrivenFixes(act, state);
            }
        });
    }

    private static void installDecorEventListeners(Activity activity, final View decor, final ActivityState state) {
        if (state.decorListenersInstalled) {
            return;
        }
        final WeakReference<Activity> actRef = new WeakReference<>(activity);

        // Fires ONLY when the window dimensions actually change (rotation, fullscreen toggle,
        // or split-screen resize). Never does any work during feed scrolling.
        state.decorResizeListener = new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(
                    View v,
                    int left, int top, int right, int bottom,
                    int oldLeft, int oldTop, int oldRight, int oldBottom
            ) {
                int newW = right - left;
                int newH = bottom - top;
                int oldW = oldRight - oldLeft;
                int oldH = oldBottom - oldTop;
                if (newW == oldW && newH == oldH) {
                    return;
                }
                Activity act = actRef.get();
                if (act == null || act.isFinishing() || act.isDestroyed()) {
                    return;
                }
                if (removeTopBlackBarEnabled && !isFloatingOrSplitMode(act)) {
                    configureWindow(act);
                    cleanRootTopInsets(act, state);
                }
                if (homeScrollToolbarEnabled) {
                    attachAndSyncAppBar(act, state);
                }
            }
        };
        decor.addOnLayoutChangeListener(state.decorResizeListener);

        ViewTreeObserver vto = decor.getViewTreeObserver();
        if (vto != null && vto.isAlive()) {
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
                    applyEventDrivenFixes(act, state);
                }
            };
            vto.addOnWindowFocusChangeListener(state.focusChangeListener);
        }

        state.decorListenersInstalled = true;
    }

    private static void applyEventDrivenFixes(Activity activity, final ActivityState state) {
        if (removeTopBlackBarEnabled && !isFloatingOrSplitMode(activity)) {
            configureWindow(activity);
            installInsetStrippers(activity, state);
            cleanRootTopInsets(activity, state);
        }

        if (homeScrollToolbarEnabled) {
            boolean attached = attachAndSyncAppBar(activity, state);
            if (!attached && state.coldStartRetries < MAX_COLD_START_RETRIES) {
                Window window = activity.getWindow();
                final View decor = window != null ? window.getDecorView() : null;
                if (decor != null) {
                    state.coldStartRetries++;
                    final WeakReference<Activity> actRef = new WeakReference<>(activity);
                    if (state.coldStartRetryRunnable != null) {
                        decor.removeCallbacks(state.coldStartRetryRunnable);
                    }
                    state.coldStartRetryRunnable = new Runnable() {
                        @Override
                        public void run() {
                            Activity act = actRef.get();
                            if (act == null || act.isFinishing() || act.isDestroyed()) {
                                return;
                            }
                            if (!attachAndSyncAppBar(act, state) && state.coldStartRetries < MAX_COLD_START_RETRIES) {
                                state.coldStartRetries++;
                                decor.postDelayed(this, 450L);
                            }
                        }
                    };
                    decor.postDelayed(state.coldStartRetryRunnable, 450L);
                }
            }
        }
    }

    @SuppressWarnings("deprecation")
    private static void configureWindow(Activity activity) {
        if (!removeTopBlackBarEnabled || activity == null || isFloatingOrSplitMode(activity)) {
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
            if (window.getStatusBarColor() != Color.TRANSPARENT) {
                window.setStatusBarColor(Color.TRANSPARENT);
            }
        } catch (Throwable ignored) {
        }

        if (Build.VERSION.SDK_INT >= 29) {
            try {
                if (window.isStatusBarContrastEnforced()) {
                    window.setStatusBarContrastEnforced(false);
                }
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
        if (!removeTopBlackBarEnabled) {
            return;
        }
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
                    hideSysStatusBgIfPresent(v, state);
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

    /**
     * One-shot root container cleanup executed ONLY on resume, window focus, or window resize.
     * Never called during scrolling.
     */
    private static void cleanRootTopInsets(Activity activity, ActivityState state) {
        Window window = activity.getWindow();
        if (window == null) {
            return;
        }
        View decor = window.getDecorView();
        if (decor == null) {
            return;
        }

        int topBandPx = getTopBandPx(activity.getResources());
        View sysStatusBg = hideSysStatusBgIfPresent(decor, state);

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

        View watchContainer = state.cachedWatchContainerRef.get();
        if (watchContainer == null || !watchContainer.isAttachedToWindow()) {
            watchContainer = null;
            for (int i = 0; i < watchContainerIds.length; i++) {
                View v = decor.findViewById(watchContainerIds[i]);
                if (v != null) {
                    clearSmallTopInsetOnView(v, topBandPx);
                    if (watchContainer == null) {
                        watchContainer = v;
                    }
                }
            }
            state.cachedWatchContainerRef = new WeakReference<>(watchContainer);
        } else {
            clearSmallTopInsetOnView(watchContainer, topBandPx);
        }

        for (int i = 0; i < statusBarSpacerIds.length; i++) {
            View spacer = decor.findViewById(statusBarSpacerIds[i]);
            if (spacer != null) {
                collapseSpacerView(spacer, topBandPx);
            }
        }
    }

    private static View hideSysStatusBgIfPresent(View decor, ActivityState state) {
        View sysStatusBg = state.cachedSysStatusBgRef.get();
        if (sysStatusBg == null && !state.sysStatusBgSearched) {
            state.sysStatusBgSearched = true;
            sysStatusBg = decor.findViewById(android.R.id.statusBarBackground);
            state.cachedSysStatusBgRef = new WeakReference<>(sysStatusBg);
        }
        if (sysStatusBg != null) {
            if (sysStatusBg.getVisibility() != View.GONE) {
                sysStatusBg.setVisibility(View.GONE);
            }
            if (sysStatusBg.getAlpha() != 0f) {
                sysStatusBg.setAlpha(0f);
            }
        }
        return sysStatusBg;
    }

    // -------------------------------------------------------------------------
    // Home screen scrolling toolbar ("Scroll away home toolbar" patch)
    // -------------------------------------------------------------------------

    private static boolean attachAndSyncAppBar(Activity activity, final ActivityState state) {
        Window window = activity.getWindow();
        if (window == null) {
            return false;
        }
        View content = window.findViewById(android.R.id.content);
        View decor = window.getDecorView();
        View searchRoot = content != null ? content : decor;
        if (searchRoot == null) {
            return false;
        }

        ViewGroup appBar = findAppBarLayoutFast(searchRoot, state);
        if (appBar == null) {
            return false;
        }

        if (!state.appBarListenerAttached) {
            final WeakReference<Activity> actRef = new WeakReference<>(activity);
            final WeakReference<ViewGroup> barRef = new WeakReference<>(appBar);

            state.debouncedAppBarRunnable = new Runnable() {
                @Override
                public void run() {
                    Activity act = actRef.get();
                    ViewGroup bar = barRef.get();
                    if (act == null || bar == null || act.isFinishing() || act.isDestroyed()) {
                        return;
                    }
                    // Never mutate AppBarLayout LayoutParams while scrolled off-screen mid-fling.
                    if (bar.getTop() < 0) {
                        return;
                    }
                    updateAppBarScrollFlags(act, bar, state);
                }
            };

            state.appBarLayoutChangeListener = new View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(
                        View v,
                        int left, int top, int right, int bottom,
                        int oldLeft, int oldTop, int oldRight, int oldBottom
                ) {
                    // Skip while scrolled partway or fully off-screen; only sync when toolbar is
                    // at rest at top == 0 (e.g., tab switch, Search open, sub-page navigation).
                    if (top < 0) {
                        return;
                    }
                    if (state.debouncedAppBarRunnable != null) {
                        v.removeCallbacks(state.debouncedAppBarRunnable);
                        v.postDelayed(state.debouncedAppBarRunnable, APPBAR_UPDATE_DEBOUNCE_MS);
                    }
                }
            };
            appBar.addOnLayoutChangeListener(state.appBarLayoutChangeListener);
            state.appBarListenerAttached = true;
        }

        if (appBar.getTop() >= 0) {
            updateAppBarScrollFlags(activity, appBar, state);
        }
        return true;
    }

    private static void updateAppBarScrollFlags(Activity activity, ViewGroup appBar, ActivityState state) {
        if (appBar == null || !appBar.isAttachedToWindow() || appBar.getTop() < 0) {
            return;
        }
        Window window = activity.getWindow();
        View decor = window != null ? window.getDecorView() : null;
        boolean shouldScrollAway = isHomeToolbarScreen(decor, appBar, state);

        int count = appBar.getChildCount();
        for (int i = 0; i < count; i++) {
            View child = appBar.getChildAt(i);
            if (child == null) {
                continue;
            }
            ViewGroup.LayoutParams lp = child.getLayoutParams();
            if (lp == null) {
                continue;
            }
            ScrollFlagsAccessor accessor = getScrollFlagsAccessor(lp.getClass());
            if (accessor == null || !accessor.isValid()) {
                continue;
            }
            try {
                int current = accessor.get(lp);
                if (shouldScrollAway) {
                    if (!state.originalScrollFlags.containsKey(child)) {
                        state.originalScrollFlags.put(child, current);
                    }
                    if (current != SCROLL_FLAG_SCROLL) {
                        accessor.set(lp, SCROLL_FLAG_SCROLL);
                        child.setLayoutParams(lp);
                    }
                } else {
                    Integer orig = state.originalScrollFlags.get(child);
                    if (orig != null && current != orig) {
                        accessor.set(lp, orig);
                        child.setLayoutParams(lp);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static boolean isHomeToolbarScreen(View decor, ViewGroup appBar, ActivityState state) {
        if (containsVisibleEditText(appBar, 0)) {
            return false;
        }
        if (hasNonHomeToolbarTitle(appBar, 0)) {
            return false;
        }
        if (decor == null) {
            return true;
        }
        ViewGroup navBar = findNavBarFast(decor, state);
        if (navBar == null || !navBar.isShown()) {
            return true;
        }
        int selectedIndex = findSelectedNavTabIndex(navBar);
        return selectedIndex <= 0;
    }

    private static boolean containsVisibleEditText(View node, int depth) {
        if (node == null || depth > 6 || !node.isShown()) {
            return false;
        }
        if (node instanceof EditText) {
            return true;
        }
        if (node instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) node;
            int count = vg.getChildCount();
            for (int i = 0; i < count; i++) {
                if (containsVisibleEditText(vg.getChildAt(i), depth + 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasNonHomeToolbarTitle(View node, int depth) {
        if (node == null || depth > 5 || !node.isShown()) {
            return false;
        }
        CharSequence title = extractToolbarTitle(node);
        if (title != null && title.length() > 0) {
            String s = title.toString().trim();
            if (!s.isEmpty() && !s.equalsIgnoreCase("YouTube") && !s.equalsIgnoreCase("Home")) {
                return true;
            }
        }
        if (node instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) node;
            int count = vg.getChildCount();
            for (int i = 0; i < count; i++) {
                if (hasNonHomeToolbarTitle(vg.getChildAt(i), depth + 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static CharSequence extractToolbarTitle(View view) {
        if (view instanceof Toolbar) {
            return ((Toolbar) view).getTitle();
        }
        Class<?> cls = view.getClass();
        String className = cls.getName();
        if (!className.endsWith("Toolbar") && !className.contains(".Toolbar")) {
            return null;
        }
        Method m = getCachedToolbarTitleMethod(cls);
        if (m == null) {
            return null;
        }
        try {
            Object res = m.invoke(view);
            if (res instanceof CharSequence) {
                return (CharSequence) res;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Method getCachedToolbarTitleMethod(Class<?> cls) {
        synchronized (TOOLBAR_GET_TITLE_CACHE) {
            Method cached = TOOLBAR_GET_TITLE_CACHE.get(cls);
            if (cached != null) {
                return cached == NO_METHOD ? null : cached;
            }
        }
        Method resolved = null;
        try {
            resolved = cls.getMethod("getTitle");
        } catch (Throwable ignored) {
        }
        synchronized (TOOLBAR_GET_TITLE_CACHE) {
            TOOLBAR_GET_TITLE_CACHE.put(cls, resolved != null ? resolved : NO_METHOD);
        }
        return resolved;
    }

    private static ViewGroup findAppBarLayoutFast(View root, ActivityState state) {
        ViewGroup cached = state.cachedAppBarRef.get();
        if (cached != null && cached.isAttachedToWindow()) {
            return cached;
        }
        state.appBarListenerAttached = false;

        for (int i = 0; i < appBarIds.length; i++) {
            View v = root.findViewById(appBarIds[i]);
            if (v instanceof ViewGroup && isAppBarLayoutClass(v)) {
                ViewGroup bar = (ViewGroup) v;
                state.cachedAppBarRef = new WeakReference<>(bar);
                return bar;
            }
        }

        ViewGroup found = findAppBarLayoutRecursive(root, 0);
        if (found != null) {
            state.cachedAppBarRef = new WeakReference<>(found);
        }
        return found;
    }

    private static ViewGroup findAppBarLayoutRecursive(View node, int depth) {
        if (node == null || depth > 8) {
            return null;
        }
        if (node instanceof ViewGroup) {
            if (isAppBarLayoutClass(node)) {
                return (ViewGroup) node;
            }
            if (isScrollableFeedContainer(node)) {
                return null;
            }
            ViewGroup vg = (ViewGroup) node;
            int count = vg.getChildCount();
            for (int i = 0; i < count; i++) {
                ViewGroup found = findAppBarLayoutRecursive(vg.getChildAt(i), depth + 1);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static boolean isAppBarLayoutClass(View v) {
        if (!(v instanceof ViewGroup)) {
            return false;
        }
        Class<?> c = v.getClass();
        while (c != null && c != ViewGroup.class && c != View.class && c != Object.class) {
            if (c.getName().contains("AppBarLayout")) {
                return true;
            }
            c = c.getSuperclass();
        }
        return false;
    }

    private static ViewGroup findNavBarFast(View decor, ActivityState state) {
        ViewGroup cached = state.cachedNavBarRef.get();
        if (cached != null && cached.isAttachedToWindow()) {
            return cached;
        }
        for (int i = 0; i < navBarIds.length; i++) {
            View v = decor.findViewById(navBarIds[i]);
            if (v instanceof ViewGroup) {
                ViewGroup nav = (ViewGroup) v;
                state.cachedNavBarRef = new WeakReference<>(nav);
                return nav;
            }
        }
        ViewGroup found = findNavBarRecursive(decor, 0);
        if (found != null) {
            state.cachedNavBarRef = new WeakReference<>(found);
        }
        return found;
    }

    private static ViewGroup findNavBarRecursive(View node, int depth) {
        if (node == null || depth > 8) {
            return null;
        }
        if (node instanceof ViewGroup) {
            String name = node.getClass().getName();
            if (name.contains("PivotBar") || name.contains("NavigationRail")) {
                return (ViewGroup) node;
            }
            if (isScrollableFeedContainer(node)) {
                return null;
            }
            ViewGroup vg = (ViewGroup) node;
            int count = vg.getChildCount();
            for (int i = 0; i < count; i++) {
                ViewGroup found = findNavBarRecursive(vg.getChildAt(i), depth + 1);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static int findSelectedNavTabIndex(ViewGroup navBar) {
        ViewGroup tabsContainer = navBar;
        for (int unwrap = 0; unwrap < 3; unwrap++) {
            if (tabsContainer.getChildCount() == 1 && tabsContainer.getChildAt(0) instanceof ViewGroup) {
                tabsContainer = (ViewGroup) tabsContainer.getChildAt(0);
            } else {
                break;
            }
        }
        int visibleTabIdx = 0;
        int count = tabsContainer.getChildCount();
        for (int i = 0; i < count; i++) {
            View tab = tabsContainer.getChildAt(i);
            if (tab == null || tab.getVisibility() != View.VISIBLE) {
                continue;
            }
            if (isViewOrChildSelected(tab, 0)) {
                return visibleTabIdx;
            }
            visibleTabIdx++;
        }
        return -1;
    }

    private static boolean isViewOrChildSelected(View v, int depth) {
        if (v == null || depth > 3) {
            return false;
        }
        if (v.isSelected() || v.isActivated()) {
            return true;
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            int count = vg.getChildCount();
            for (int i = 0; i < count; i++) {
                if (isViewOrChildSelected(vg.getChildAt(i), depth + 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static ScrollFlagsAccessor getScrollFlagsAccessor(Class<?> lpClass) {
        synchronized (SCROLL_FLAGS_CACHE) {
            ScrollFlagsAccessor cached = SCROLL_FLAGS_CACHE.get(lpClass);
            if (cached != null) {
                return cached == NO_ACCESSOR ? null : cached;
            }
        }

        ScrollFlagsAccessor resolved = resolveScrollFlagsAccessor(lpClass);
        synchronized (SCROLL_FLAGS_CACHE) {
            SCROLL_FLAGS_CACHE.put(lpClass, resolved != null ? resolved : NO_ACCESSOR);
        }
        return resolved;
    }

    private static ScrollFlagsAccessor resolveScrollFlagsAccessor(Class<?> lpClass) {
        try {
            Method getter = lpClass.getMethod("getScrollFlags");
            Method setter = lpClass.getMethod("setScrollFlags", int.class);
            if (getter.getReturnType() == int.class) {
                return new ScrollFlagsAccessor(getter, setter, null);
            }
        } catch (Throwable ignored) {
        }

        Class<?> c = lpClass;
        Field uniqueIntField = null;
        int intFieldCount = 0;
        while (c != null
                && c != LinearLayout.LayoutParams.class
                && c != ViewGroup.MarginLayoutParams.class
                && c != ViewGroup.LayoutParams.class
                && c != Object.class) {
            Field[] fields = c.getDeclaredFields();
            for (int i = 0; i < fields.length; i++) {
                Field f = fields[i];
                int mod = f.getModifiers();
                if (f.getType() == int.class && !Modifier.isStatic(mod) && !Modifier.isFinal(mod)) {
                    if ("scrollFlags".equals(f.getName())) {
                        f.setAccessible(true);
                        return new ScrollFlagsAccessor(null, null, f);
                    }
                    uniqueIntField = f;
                    intFieldCount++;
                }
            }
            c = c.getSuperclass();
        }

        if (intFieldCount == 1 && uniqueIntField != null) {
            try {
                uniqueIntField.setAccessible(true);
                return new ScrollFlagsAccessor(null, null, uniqueIntField);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static boolean isScrollableFeedContainer(View v) {
        String name = v.getClass().getName();
        return name.contains("RecyclerView")
                || name.contains("ScrollView")
                || name.contains("ListView")
                || name.contains("GridView")
                || name.contains("WebView")
                || name.contains("ViewPager");
    }

    // -------------------------------------------------------------------------
    // Shared view helpers
    // -------------------------------------------------------------------------

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

    private static int getTopBandPx(Resources res) {
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
