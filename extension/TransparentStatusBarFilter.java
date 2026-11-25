package app.morphe.extension.music.patches.miniplayer;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Standalone runtime hook for the "Transparent status bar" patch.
 * Removes system and app-level status bar layers (DecorView statusBarBackground,
 * CoordinatorLayout/DrawerLayout statusBarBackground, AppBarLayout statusBarForeground,
 * CollapsingToolbarLayout scrims, root fitsSystemWindows top insets, and opaque top-bar
 * backdrops) so vertical content scrolls edge-to-edge all the way to y = 0 behind
 * the system status bar icons while keeping icons untouched.
 */
@SuppressWarnings({"unused", "deprecation"})
public final class TransparentStatusBarFilter {

    private static final int MAX_TREE_DEPTH = 24;

    private static volatile boolean idsResolved = false;
    private static int playerPageId = 0;
    private static int pivotBarId = 0;

    private static volatile boolean lifecycleRegistered = false;

    private static final Set<View> hookedDecorViews =
            Collections.newSetFromMap(new WeakHashMap<>());
    private static final Map<Window, Long> lastInsetsApplyMs = new WeakHashMap<>();

    // Enforced every frame in OnPreDrawListener so YT Music scroll/theme animations cannot re-darken them
    private static final Set<View> suppressedTopScrims =
            Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<View> clearedTopBarBgViews =
            Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<View> clearedRootTopPaddingViews =
            Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<View> adjustedAppBars =
            Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<View> paddedHeaders =
            Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<View> paddedPlayerPages =
            Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<View> rootTopInsetRemovedForDecor =
            Collections.newSetFromMap(new WeakHashMap<>());

    // Tracks vertical scrollers extended behind the status bar: [appliedShiftPx, expectedPadTop]
    private static final Map<View, int[]> adjustedScrollables = new WeakHashMap<>();
    private static final Map<Class<?>, Map<String, Boolean>> classHierarchyCache = new WeakHashMap<>();
    private static final Map<View, Long> lastFullScanByDecor = new WeakHashMap<>();

    private static final int[] tmpLoc = new int[2];
    private static final int[] tmpLoc2 = new int[2];

    private TransparentStatusBarFilter() {}

    /**
     * Entry point injected by TransparentStatusBarPatch when YouTube Music builds its UI.
     */
    public static void onAttach(@Nullable View anchorView) {
        if (anchorView == null) return;
        try {
            ensureIdsResolved(anchorView.getContext());
            Activity activity = resolveActivity(anchorView.getContext());
            if (activity == null) return;
            registerActivityLifecycle(activity);
            attachToActivity(activity);
        } catch (Throwable ignored) {}
    }

    private static void ensureIdsResolved(@Nullable Context context) {
        if (idsResolved || context == null) return;
        try {
            Resources res = context.getResources();
            String pkg = context.getPackageName();
            if (res != null && pkg != null) {
                playerPageId = res.getIdentifier("player_page", "id", pkg);
                pivotBarId = res.getIdentifier("pivot_bar", "id", pkg);
                idsResolved = true;
            }
        } catch (Throwable ignored) {}
    }

    private static void registerActivityLifecycle(Activity activity) {
        if (lifecycleRegistered) return;
        try {
            Application app = activity.getApplication();
            if (app == null) return;
            lifecycleRegistered = true;
            app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override
                public void onActivityCreated(@NonNull Activity act, @Nullable Bundle savedInstanceState) {
                    try {
                        attachToActivity(act);
                    } catch (Throwable ignored) {}
                }

                @Override public void onActivityStarted(@NonNull Activity act) {}

                @Override
                public void onActivityResumed(@NonNull Activity act) {
                    try {
                        attachToActivity(act);
                        Window w = act.getWindow();
                        View decor = (w != null) ? w.getDecorView() : null;
                        if (w != null && decor != null) {
                            applyWindowEdgeToEdge(w, decor, true);
                            decor.post(() -> {
                                try {
                                    syncTransparentStatusBar(act, w, decor, true);
                                } catch (Throwable ignored) {}
                            });
                        }
                    } catch (Throwable ignored) {}
                }

                @Override public void onActivityPaused(@NonNull Activity act) {}
                @Override public void onActivityStopped(@NonNull Activity act) {}
                @Override public void onActivitySaveInstanceState(@NonNull Activity act, @NonNull Bundle outState) {}
                @Override public void onActivityDestroyed(@NonNull Activity act) {}
            });
        } catch (Throwable ignored) {}
    }

    private static void attachToActivity(@Nullable Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        Window window = activity.getWindow();
        if (window == null) return;
        final View decorView = window.getDecorView();
        if (decorView == null) return;

        ensureIdsResolved(activity);
        applyWindowEdgeToEdge(window, decorView, true);

        if (!hookedDecorViews.add(decorView)) {
            return;
        }

        decorView.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            try {
                syncTransparentStatusBar(activity, window, decorView, true);
            } catch (Throwable ignored) {}
        });

        decorView.getViewTreeObserver().addOnWindowFocusChangeListener(hasFocus -> {
            if (!hasFocus) return;
            try {
                applyWindowEdgeToEdge(window, decorView, true);
                syncTransparentStatusBar(activity, window, decorView, true);
            } catch (Throwable ignored) {}
        });

        decorView.getViewTreeObserver().addOnPreDrawListener(() -> {
            try {
                syncTransparentStatusBar(activity, window, decorView, false);
            } catch (Throwable ignored) {}
            return true;
        });

        decorView.post(() -> {
            try {
                applyWindowEdgeToEdge(window, decorView, true);
                syncTransparentStatusBar(activity, window, decorView, true);
            } catch (Throwable ignored) {}
        });
    }

    private static void applyWindowEdgeToEdge(Window window, View decorView, boolean forceInsets) {
        try {
            WindowManager.LayoutParams wlp = window.getAttributes();
            if (wlp != null) {
                if ((wlp.flags & WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS) != 0) {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
                }
                if ((wlp.flags & WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS) == 0) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    int desiredCutout = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                            ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                            : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                    if (wlp.layoutInDisplayCutoutMode != desiredCutout) {
                        wlp.layoutInDisplayCutoutMode = desiredCutout;
                        window.setAttributes(wlp);
                    }
                }
            }

            if (window.getStatusBarColor() != Color.TRANSPARENT) {
                window.setStatusBarColor(Color.TRANSPARENT);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && window.isStatusBarContrastEnforced()) {
                window.setStatusBarContrastEnforced(false);
            }

            // Detect whether DecorView / mContentRoot is still pushing content down by statusBarH
            boolean decorStillPushesTop = forceInsets;
            View contentView = decorView.findViewById(android.R.id.content);
            if (!decorStillPushesTop && contentView != null && decorView.isAttachedToWindow()) {
                decorView.getLocationOnScreen(tmpLoc);
                contentView.getLocationOnScreen(tmpLoc2);
                if (tmpLoc2[1] - tmpLoc[1] > 2) {
                    decorStillPushesTop = true;
                }
            }
            if (!decorStillPushesTop && decorView instanceof ViewGroup dvg && dvg.getChildCount() > 0) {
                View first = dvg.getChildAt(0);
                if (first != null && first.getId() != android.R.id.statusBarBackground
                        && first.getId() != android.R.id.navigationBarBackground) {
                    if (first.getTop() > 2 || first.getPaddingTop() > 2) {
                        decorStillPushesTop = true;
                    } else if (first.getLayoutParams() instanceof ViewGroup.MarginLayoutParams mlp
                            && mlp.topMargin > 2) {
                        decorStillPushesTop = true;
                    }
                }
            }

            if (decorStillPushesTop) {
                long now = SystemClock.uptimeMillis();
                Long lastMs = lastInsetsApplyMs.get(window);
                if (lastMs == null || (now - lastMs) >= 300L) {
                    lastInsetsApplyMs.put(window, now);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        window.setDecorFitsSystemWindows(false);
                    }
                    // Keep LAYOUT_STABLE | LAYOUT_FULLSCREEN (without hiding status bar icons)
                    int flags = decorView.getSystemUiVisibility();
                    int want = flags
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
                    if (want != flags) {
                        decorView.setSystemUiVisibility(want);
                    }
                    if (decorView.isAttachedToWindow()) {
                        decorView.requestApplyInsets();
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void syncTransparentStatusBar(
            Activity activity, Window window, View decorView, boolean forceScan
    ) {
        if (activity.isFinishing() || activity.isDestroyed() || !decorView.isAttachedToWindow()) {
            return;
        }

        // Fast per-frame enforcement (runs before every draw)
        applyWindowEdgeToEdge(window, decorView, false);
        suppressDecorStatusBarOverlays(decorView);
        enforceTrackedViewsPerFrame();

        long now = SystemClock.uptimeMillis();
        Long lastScan = lastFullScanByDecor.get(decorView);
        if (!forceScan && lastScan != null && (now - lastScan) < 160L) {
            return;
        }
        lastFullScanByDecor.put(decorView, now);

        int screenW = decorView.getWidth() > 0
                ? decorView.getWidth()
                : Resources.getSystem().getDisplayMetrics().widthPixels;
        int screenH = decorView.getHeight() > 0
                ? decorView.getHeight()
                : Resources.getSystem().getDisplayMetrics().heightPixels;
        if (screenW <= 0 || screenH <= 0) return;

        int statusBarH = getStatusBarHeight(decorView);
        if (statusBarH <= 0) return;

        decorView.getLocationOnScreen(tmpLoc);
        int decorTop = tmpLoc[1];

        View playerPage = (playerPageId != 0) ? decorView.findViewById(playerPageId) : null;
        View pivotBar = (pivotBarId != 0) ? decorView.findViewById(pivotBarId) : null;

        // 1. Remove top-inset padding/margins on root structural containers (mContentRoot -> content -> WatchWhileLayout -> browseContent)
        boolean clearedRootTopInset = clearRootContainerTopInsets(decorView, playerPage, pivotBar, statusBarH);
        if (clearedRootTopInset) {
            rootTopInsetRemovedForDecor.add(decorView);
        }
        boolean rootInsetWasRemoved = rootTopInsetRemovedForDecor.contains(decorView);

        // If we pulled WatchWhileLayout/root up from y = statusBarH to y = 0, keep Now Playing's top controls below the cutout
        if (rootInsetWasRemoved && playerPage instanceof ViewGroup ppVg) {
            preservePlayerPageTopSafeArea(ppVg, decorTop, statusBarH);
        }

        // 2. Clear all app-level status bar scrims, canvas-drawn statusBarBackground/Foreground layers,
        //    opaque top bar backgrounds, and make AppBarLayouts fully scrollable to y = 0
        suppressTopStatusBarLayersRecursive(
                decorView, decorView, playerPage, pivotBar,
                screenW, decorTop, statusBarH, rootInsetWasRemoved, false, 0
        );

        // 3. Extend all full-page vertical scrollers to y = 0 behind the status bar icons
        makeScrollablesEdgeToEdgeTopRecursive(
                decorView, decorView, playerPage, pivotBar,
                screenW, screenH, decorTop, statusBarH, 0
        );
    }

    private static void suppressDecorStatusBarOverlays(View decorView) {
        View sysStatusBg = decorView.findViewById(android.R.id.statusBarBackground);
        if (sysStatusBg != null) {
            suppressScrimView(sysStatusBg);
        }
        if (!(decorView instanceof ViewGroup dvg)) return;
        int statusBarH = getStatusBarHeight(decorView);
        int maxGuardH = statusBarH + Math.round(dpToPx(24f));
        View contentView = decorView.findViewById(android.R.id.content);
        for (int i = 0; i < dvg.getChildCount(); i++) {
            View child = dvg.getChildAt(i);
            if (child == null) continue;
            int id = child.getId();
            if (id == android.R.id.navigationBarBackground) continue;
            if (id == android.R.id.statusBarBackground) {
                suppressScrimView(child);
                continue;
            }
            // Skip mContentRoot (the child that contains android.R.id.content)
            if (child == contentView || (contentView != null && isDescendantOf(contentView, child))) {
                continue;
            }
            int h = child.getHeight();
            if (h > 0 && h <= maxGuardH && child.getTop() <= Math.round(dpToPx(8f))) {
                suppressScrimView(child);
            }
        }
    }

    private static void enforceTrackedViewsPerFrame() {
        if (!suppressedTopScrims.isEmpty()) {
            for (View v : suppressedTopScrims) {
                if (v == null) continue;
                if (v.getAlpha() != 0f) {
                    v.setAlpha(0f);
                }
                Drawable bg = v.getBackground();
                if (bg != null && (!(bg instanceof ColorDrawable cd) || cd.getColor() != Color.TRANSPARENT)) {
                    v.setBackgroundColor(Color.TRANSPARENT);
                }
            }
        }
        if (!clearedTopBarBgViews.isEmpty()) {
            for (View v : clearedTopBarBgViews) {
                if (v == null) continue;
                Drawable bg = v.getBackground();
                if (bg != null && (!(bg instanceof ColorDrawable cd) || cd.getColor() != Color.TRANSPARENT)) {
                    v.setBackgroundColor(Color.TRANSPARENT);
                }
                if (v.getForeground() != null) {
                    v.setForeground(null);
                }
                if (v.getElevation() > 0f) {
                    v.setElevation(0f);
                }
            }
        }
        if (!clearedRootTopPaddingViews.isEmpty()) {
            for (View v : clearedRootTopPaddingViews) {
                if (v == null) continue;
                if (v.getPaddingTop() != 0) {
                    v.setPadding(v.getPaddingLeft(), 0, v.getPaddingRight(), v.getPaddingBottom());
                }
                if (v.getLayoutParams() instanceof ViewGroup.MarginLayoutParams mlp && mlp.topMargin > 0) {
                    mlp.topMargin = 0;
                    v.setLayoutParams(mlp);
                }
            }
        }
    }

    /**
     * Strips top status-bar inset padding/margins from DecorView's mContentRoot, android.R.id.content,
     * intermediate wrappers, WatchWhileLayout, and the primary browse pane container so app content
     * begins at y = 0 instead of leaving a solid black strip behind the status bar.
     */
    private static boolean clearRootContainerTopInsets(
            View decorView, @Nullable View playerPage, @Nullable View pivotBar, int statusBarH
    ) {
        boolean removedAny = false;
        int maxInsetTol = statusBarH + Math.round(dpToPx(18f));

        // 1. DecorView's content root (LinearLayout) & android.R.id.content
        View contentView = decorView.findViewById(android.R.id.content);
        if (contentView != null) {
            View cur = contentView;
            int hops = 0;
            while (cur != null && cur != decorView && hops < 6) {
                if (clearTopInsetOnContainer(cur, maxInsetTol)) {
                    removedAny = true;
                }
                if (cur instanceof ViewGroup vg) {
                    if (vg.getClipToPadding()) vg.setClipToPadding(false);
                    if (vg.getClipChildren()) vg.setClipChildren(false);
                }
                ViewParent p = cur.getParent();
                cur = (p instanceof View pv) ? pv : null;
                hops++;
            }
        }

        // 2. Locate WatchWhileLayout (if present) and clear top insets from android.R.id.content -> WatchWhileLayout -> browse child
        ViewGroup wwl = findWatchWhileLayout(contentView != null ? contentView : decorView, 0);
        if (wwl != null) {
            View cur = wwl;
            int hops = 0;
            while (cur != null && cur != decorView && hops < 10) {
                if (clearTopInsetOnContainer(cur, maxInsetTol)) {
                    removedAny = true;
                }
                if (cur instanceof ViewGroup vg) {
                    if (vg.getClipToPadding()) vg.setClipToPadding(false);
                    if (vg.getClipChildren()) vg.setClipChildren(false);
                }
                ViewParent p = cur.getParent();
                cur = (p instanceof View pv) ? pv : null;
                hops++;
            }

            // Clear top inset on WatchWhileLayout's direct browse content container(s) (never player_page or pivot_bar)
            for (int i = 0; i < wwl.getChildCount(); i++) {
                View child = wwl.getChildAt(i);
                if (child == null || child == playerPage || child == pivotBar) continue;
                if (playerPage != null && isDescendantOf(playerPage, child)) continue;
                if (child.getClass().getName().contains("M3NavigationFilter$")) continue;
                if (child.getHeight() >= wwl.getHeight() * 0.55f) {
                    if (clearTopInsetOnContainer(child, maxInsetTol)) {
                        removedAny = true;
                    }
                    if (child instanceof ViewGroup vg) {
                        if (vg.getClipToPadding()) vg.setClipToPadding(false);
                        if (vg.getClipChildren()) vg.setClipChildren(false);
                    }
                }
            }
        } else if (contentView instanceof ViewGroup contentVg) {
            // Secondary Activity (e.g., Settings / standalone screen): clear top inset on root layout child
            for (int i = 0; i < contentVg.getChildCount(); i++) {
                View child = contentVg.getChildAt(i);
                if (child == null || child.getClass().getName().contains("M3NavigationFilter$")) continue;
                if (child.getHeight() >= contentVg.getHeight() * 0.55f) {
                    if (clearTopInsetOnContainer(child, maxInsetTol)) {
                        removedAny = true;
                    }
                    if (child instanceof ViewGroup vg) {
                        if (vg.getClipToPadding()) vg.setClipToPadding(false);
                        if (vg.getClipChildren()) vg.setClipChildren(false);
                    }
                }
            }
        }

        return removedAny;
    }

    private static boolean clearTopInsetOnContainer(View view, int maxInsetTol) {
        boolean changed = false;
        int padTop = view.getPaddingTop();
        if (padTop > 0 && padTop <= maxInsetTol) {
            view.setFitsSystemWindows(false);
            view.setPadding(view.getPaddingLeft(), 0, view.getPaddingRight(), view.getPaddingBottom());
            clearedRootTopPaddingViews.add(view);
            changed = true;
        }
        if (view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams mlp
                && mlp.topMargin > 0 && mlp.topMargin <= maxInsetTol) {
            mlp.topMargin = 0;
            view.setLayoutParams(mlp);
            clearedRootTopPaddingViews.add(view);
            changed = true;
        }
        return changed;
    }

    private static void preservePlayerPageTopSafeArea(ViewGroup playerPage, int decorTop, int statusBarH) {
        if (paddedPlayerPages.contains(playerPage)) return;
        playerPage.getLocationOnScreen(tmpLoc2);
        int ppTop = tmpLoc2[1] - decorTop;
        if (ppTop <= Math.round(dpToPx(8f)) && playerPage.getPaddingTop() < statusBarH / 2) {
            playerPage.setClipToPadding(false);
            playerPage.setPadding(
                    playerPage.getPaddingLeft(),
                    playerPage.getPaddingTop() + statusBarH,
                    playerPage.getPaddingRight(),
                    playerPage.getPaddingBottom()
            );
            paddedPlayerPages.add(playerPage);
        }
    }

    @Nullable
    private static ViewGroup findWatchWhileLayout(@Nullable View view, int depth) {
        if (view == null || depth > 8) return null;
        if (view instanceof ViewGroup vg) {
            if (isSubclassOf(vg.getClass(), "WatchWhileLayout")) {
                return vg;
            }
            for (int i = 0; i < vg.getChildCount(); i++) {
                ViewGroup found = findWatchWhileLayout(vg.getChildAt(i), depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void suppressScrimView(View v) {
        suppressedTopScrims.add(v);
        if (v.getAlpha() != 0f) {
            v.setAlpha(0f);
        }
        Drawable bg = v.getBackground();
        if (bg != null && (!(bg instanceof ColorDrawable cd) || cd.getColor() != Color.TRANSPARENT)) {
            v.setBackgroundColor(Color.TRANSPARENT);
        }
    }

    private static void suppressTopStatusBarLayersRecursive(
            View view,
            View decorView,
            @Nullable View playerPage,
            @Nullable View pivotBar,
            int screenW,
            int decorTop,
            int statusBarH,
            boolean rootInsetWasRemoved,
            boolean insideVerticalScrollable,
            int depth
    ) {
        if (view == null || depth > MAX_TREE_DEPTH || view.getVisibility() != View.VISIBLE) return;
        if (view == playerPage || view == pivotBar) return;

        Class<?> cls = view.getClass();
        String clsName = cls.getName();
        if (clsName.contains("M3NavigationFilter$")) return;

        // Never strip backgrounds inside a RecyclerView/ScrollView's scrolling content items
        if (insideVerticalScrollable) return;
        boolean isThisScrollable = (view != decorView) && isVerticalScrollableView(view);

        // 1. Clear canvas-drawn status bar scrims on CoordinatorLayout, DrawerLayout,
        //    CollapsingToolbarLayout, AppBarLayout, and ScrimInsetsFrameLayout (works on obfuscated subclasses!)
        boolean isAppBar = isSubclassOf(cls, "AppBarLayout");
        boolean isCoordinator = isSubclassOf(cls, "CoordinatorLayout");
        boolean isDrawer = isSubclassOf(cls, "DrawerLayout");
        boolean isCollapsingToolbar = isSubclassOf(cls, "CollapsingToolbarLayout");
        boolean isScrimInsets = isSubclassOf(cls, "ScrimInsetsFrameLayout");
        boolean isToolbar = isSubclassOf(cls, "Toolbar");

        if (isCoordinator || isDrawer) {
            clearCanvasStatusBarBackground(view);
        }
        if (isCollapsingToolbar) {
            clearCollapsingToolbarScrims(view);
        }
        if (isScrimInsets) {
            clearScrimInsetsTopForeground(view);
        }

        String resName = getEntryNameLower(view);
        boolean isStatusBarNamed = resName.contains("status_bar") || resName.contains("statusbar");
        boolean isTopBarNamed = isAppBar
                || isToolbar
                || isSubclassOf(cls, "ActionBarContainer")
                || resName.contains("action_bar")
                || resName.contains("actionbar")
                || resName.contains("toolbar")
                || resName.contains("app_bar")
                || resName.contains("appbar")
                || resName.contains("top_bar")
                || resName.contains("topbar")
                || resName.contains("header_bar")
                || resName.contains("browse_header")
                || resName.contains("music_header")
                || resName.contains("pivot_header")
                || resName.contains("chip_bar")
                || resName.contains("filter_chip")
                || resName.contains("scrim");

        // 2. Handle AppBarLayout & Toolbar / named top bars positioned near the top of the screen
        if (isTopBarNamed && !isThisScrollable) {
            view.getLocationOnScreen(tmpLoc2);
            int vTop = tmpLoc2[1] - decorTop;
            int w = view.getWidth();
            int h = view.getHeight();
            if (vTop <= statusBarH + Math.round(dpToPx(64f)) && w >= screenW * 0.80f && h > 0) {
                clearTopBarBackground(view);
                if (isAppBar && view instanceof ViewGroup appBar) {
                    makeAppBarFullyScrollable(appBar, decorTop, statusBarH, rootInsetWasRemoved);
                } else if (rootInsetWasRemoved && (isToolbar || resName.equals("action_bar") || resName.equals("toolbar"))) {
                    ensureTopBarSafePadding(view, decorTop, statusBarH);
                }
            }
        }

        // 3. Suppress dedicated status bar background / scrim strips at y = 0..statusBarH
        int w = view.getWidth();
        int h = view.getHeight();
        boolean isLeafOrEmpty = !(view instanceof ViewGroup) || ((ViewGroup) view).getChildCount() == 0;
        boolean isNonInteractiveOverlay = !(view instanceof TextView)
                && !(view instanceof EditText)
                && !(view instanceof Button)
                && !(view instanceof ProgressBar)
                && !(view instanceof SurfaceView)
                && !(view instanceof TextureView)
                && !view.isClickable()
                && !view.isFocusable();

        if (isLeafOrEmpty && isNonInteractiveOverlay && w >= screenW * 0.80f && h > 0) {
            if (isStatusBarNamed && h <= statusBarH + Math.round(dpToPx(24f))) {
                suppressScrimView(view);
                return;
            }
            if (h >= Math.max(8, statusBarH - Math.round(dpToPx(12f)))
                    && h <= statusBarH + Math.round(dpToPx(18f))) {
                view.getLocationOnScreen(tmpLoc2);
                int vTop = tmpLoc2[1] - decorTop;
                int vBottom = vTop + h;
                if (vTop <= Math.round(dpToPx(8f)) && vBottom <= statusBarH + Math.round(dpToPx(20f))) {
                    boolean hasVisualLayer = (view.getBackground() != null && view.getAlpha() > 0.01f)
                            || (view instanceof ImageView iv && iv.getDrawable() != null && view.getAlpha() > 0.01f);
                    if (hasVisualLayer) {
                        suppressScrimView(view);
                        return;
                    }
                }
            }
        }

        // 4. Clear opaque/dark backgrounds on ANY full-width top header/strip ViewGroup overlapping the status bar area
        //    (keeps full-screen page backgrounds & Home ambient gradients untouched because h <= statusBarH + 140dp)
        if (!isThisScrollable && view instanceof ViewGroup && view != decorView
                && view.getId() != android.R.id.content
                && w >= screenW * 0.88f
                && h > 0 && h <= statusBarH + Math.round(dpToPx(140f))) {
            Drawable bg = view.getBackground();
            if (isStatusBarNamed || isOpaqueOrDarkTopBarDrawable(bg) || view.getForeground() != null) {
                view.getLocationOnScreen(tmpLoc2);
                int vTop = tmpLoc2[1] - decorTop;
                if (vTop <= statusBarH + Math.round(dpToPx(12f))) {
                    clearTopBarBackground(view);
                }
            }
        }

        if (view instanceof ViewGroup vg && !isThisScrollable) {
            for (int i = 0; i < vg.getChildCount(); i++) {
                suppressTopStatusBarLayersRecursive(
                        vg.getChildAt(i), decorView, playerPage, pivotBar,
                        screenW, decorTop, statusBarH, rootInsetWasRemoved, false, depth + 1
                );
            }
        }
    }

    private static boolean isOpaqueOrDarkTopBarDrawable(@Nullable Drawable bg) {
        if (bg == null) return false;
        if (bg instanceof ColorDrawable cd) {
            return Color.alpha(cd.getColor()) > 12;
        }
        return (bg instanceof GradientDrawable || bg instanceof LayerDrawable)
                || bg.getClass().getSimpleName().contains("MaterialShapeDrawable");
    }

    private static void clearTopBarBackground(View view) {
        clearedTopBarBgViews.add(view);
        Drawable bg = view.getBackground();
        if (bg != null) {
            if (!(bg instanceof ColorDrawable cd) || cd.getColor() != Color.TRANSPARENT) {
                view.setBackgroundColor(Color.TRANSPARENT);
            }
        }
        if (view.getForeground() != null) {
            view.setForeground(null);
        }
        if (view.getElevation() > 0f) {
            view.setElevation(0f);
        }
        if (view.getStateListAnimator() != null) {
            view.setStateListAnimator(null);
        }
    }

    private static void clearCanvasStatusBarBackground(View view) {
        invokeNoThrow(view, "setStatusBarBackground", new Class<?>[]{Drawable.class}, new Object[]{null});
        invokeNoThrow(view, "setStatusBarBackgroundColor", new Class<?>[]{int.class}, new Object[]{Color.TRANSPARENT});
    }

    private static void clearCollapsingToolbarScrims(View view) {
        invokeNoThrow(view, "setStatusBarScrimColor", new Class<?>[]{int.class}, new Object[]{Color.TRANSPARENT});
        invokeNoThrow(view, "setStatusBarScrim", new Class<?>[]{Drawable.class}, new Object[]{null});
        invokeNoThrow(view, "setContentScrimColor", new Class<?>[]{int.class}, new Object[]{Color.TRANSPARENT});
        invokeNoThrow(view, "setContentScrim", new Class<?>[]{Drawable.class}, new Object[]{null});
    }

    private static void clearScrimInsetsTopForeground(View view) {
        invokeNoThrow(view, "setDrawTopInsetForeground", new Class<?>[]{boolean.class}, new Object[]{false});
    }

    /**
     * Makes an AppBarLayout completely transparent (including Material's canvas-drawn
     * statusBarForeground & liftOnScroll surface) and able to scroll 100% off-screen past y = 0
     * so its sibling RecyclerView glides all the way to y = 0 behind the status bar icons.
     */
    private static void makeAppBarFullyScrollable(
            ViewGroup appBar, int decorTop, int statusBarH, boolean rootInsetWasRemoved
    ) {
        clearTopBarBackground(appBar);
        // Clear Material AppBarLayout's canvas-drawn statusBarForeground & liftOnScroll tint
        invokeNoThrow(appBar, "setStatusBarForeground", new Class<?>[]{Drawable.class}, new Object[]{null});
        invokeNoThrow(appBar, "setStatusBarForegroundColor", new Class<?>[]{int.class}, new Object[]{Color.TRANSPARENT});
        invokeNoThrow(appBar, "setLiftOnScroll", new Class<?>[]{boolean.class}, new Object[]{false});
        invokeNoThrow(appBar, "setLifted", new Class<?>[]{boolean.class}, new Object[]{false});

        if (appBar.getClipToPadding()) appBar.setClipToPadding(false);
        if (appBar.getClipChildren()) appBar.setClipChildren(false);

        // Move AppBarLayout's own paddingTop into its first visible child so totalScrollRange covers 100% of height
        int abPadTop = appBar.getPaddingTop();
        if (abPadTop > 0) {
            View firstVisible = null;
            for (int i = 0; i < appBar.getChildCount(); i++) {
                View c = appBar.getChildAt(i);
                if (c != null && c.getVisibility() == View.VISIBLE) {
                    firstVisible = c;
                    break;
                }
            }
            appBar.setFitsSystemWindows(false);
            appBar.setPadding(appBar.getPaddingLeft(), 0, appBar.getPaddingRight(), appBar.getPaddingBottom());
            if (firstVisible != null && firstVisible.getLayoutParams() instanceof ViewGroup.MarginLayoutParams mlp) {
                mlp.topMargin += abPadTop;
                firstVisible.setLayoutParams(mlp);
            }
        } else if (rootInsetWasRemoved && !adjustedAppBars.contains(appBar)) {
            appBar.getLocationOnScreen(tmpLoc2);
            int abTop = tmpLoc2[1] - decorTop;
            if (abTop <= Math.round(dpToPx(8f))) {
                View firstVisible = null;
                for (int i = 0; i < appBar.getChildCount(); i++) {
                    View c = appBar.getChildAt(i);
                    if (c != null && c.getVisibility() == View.VISIBLE) {
                        firstVisible = c;
                        break;
                    }
                }
                if (firstVisible != null) {
                    firstVisible.getLocationOnScreen(tmpLoc2);
                    int fcTop = tmpLoc2[1] - decorTop;
                    boolean alreadySpaced = fcTop >= statusBarH / 2
                            || firstVisible.getPaddingTop() >= statusBarH / 2
                            || getEntryNameLower(firstVisible).contains("status_bar");
                    if (!alreadySpaced && firstVisible.getLayoutParams() instanceof ViewGroup.MarginLayoutParams mlp) {
                        mlp.topMargin += statusBarH;
                        firstVisible.setLayoutParams(mlp);
                    }
                }
            }
        }
        adjustedAppBars.add(appBar);

        // Ensure EVERY child of AppBarLayout can scroll completely above y = 0 (no pinned minHeight or unscrollable spacer)
        for (int i = 0; i < appBar.getChildCount(); i++) {
            View child = appBar.getChildAt(i);
            if (child == null) continue;
            String resName = getEntryNameLower(child);
            boolean isPureSpacer = resName.contains("status_bar")
                    || (!(child instanceof ViewGroup) && !(child instanceof TextView) && !(child instanceof ImageView)
                    && child.getHeight() > 0 && child.getHeight() <= statusBarH + Math.round(dpToPx(16f)));
            if (isPureSpacer) {
                suppressScrimView(child);
            } else if (!(child instanceof TextView)) {
                clearTopBarBackground(child);
            }
            if (child.getMinimumHeight() > 0) {
                child.setMinimumHeight(0);
            }
            ViewGroup.LayoutParams lp = child.getLayoutParams();
            if (lp != null && isSubclassOf(lp.getClass(), "AppBarLayout$LayoutParams")) {
                try {
                    Method getFlags = lp.getClass().getMethod("getScrollFlags");
                    Method setFlags = lp.getClass().getMethod("setScrollFlags", int.class);
                    Object curObj = getFlags.invoke(lp);
                    if (curObj instanceof Integer cur) {
                        // SCROLL_FLAG_SCROLL = 1, EXIT_UNTIL_COLLAPSED = 2, ENTER_ALWAYS = 4
                        int next = (cur == 0) ? (1 | 4) : ((cur | 1) & ~2);
                        if (next != cur) {
                            setFlags.invoke(lp, next);
                            child.setLayoutParams(lp);
                        }
                    }
                } catch (Throwable ignored) {}
            }
        }
    }

    private static void ensureTopBarSafePadding(View topBar, int decorTop, int statusBarH) {
        if (paddedHeaders.contains(topBar)) return;
        ViewParent p = topBar.getParent();
        while (p instanceof ViewGroup vg) {
            if (isSubclassOf(vg.getClass(), "AppBarLayout")) return;
            p = vg.getParent();
        }
        topBar.getLocationOnScreen(tmpLoc2);
        int topY = tmpLoc2[1] - decorTop;
        if (topY <= Math.round(dpToPx(8f)) && topBar.getPaddingTop() < statusBarH / 2) {
            ViewGroup.LayoutParams lp = topBar.getLayoutParams();
            if (lp != null && lp.height > 0) {
                lp.height += statusBarH;
                topBar.setLayoutParams(lp);
            }
            topBar.setPadding(
                    topBar.getPaddingLeft(),
                    topBar.getPaddingTop() + statusBarH,
                    topBar.getPaddingRight(),
                    topBar.getPaddingBottom()
            );
            if (topBar instanceof ViewGroup vg) {
                vg.setClipToPadding(false);
            }
            paddedHeaders.add(topBar);
        }
    }

    private static void makeScrollablesEdgeToEdgeTopRecursive(
            View view,
            View decorView,
            @Nullable View playerPage,
            @Nullable View pivotBar,
            int screenW,
            int screenH,
            int decorTop,
            int statusBarH,
            int depth
    ) {
        if (view == null || depth > MAX_TREE_DEPTH || view.getVisibility() != View.VISIBLE) return;
        if (view == playerPage || view == pivotBar) return;

        String clsName = view.getClass().getName();
        if (clsName.contains("M3NavigationFilter$")) return;

        if (view != decorView && isVerticalScrollableView(view)) {
            int w = view.getWidth();
            int h = view.getHeight();
            // Adjust full-page vertical scrollers (skip horizontal carousels, chip bars, and small sub-lists)
            if (w >= screenW * 0.85f && h >= screenH * 0.35f) {
                view.getLocationOnScreen(tmpLoc2);
                int rvScreenTop = tmpLoc2[1] - decorTop;
                if (rvScreenTop <= statusBarH + Math.round(dpToPx(180f))) {
                    configureScrollableForTopEdgeToEdge(
                            view, decorView, playerPage, screenW, screenH, rvScreenTop, decorTop, statusBarH
                    );
                }
            }
            return;
        }

        if (view instanceof ViewGroup vg) {
            for (int i = 0; i < vg.getChildCount(); i++) {
                makeScrollablesEdgeToEdgeTopRecursive(
                        vg.getChildAt(i), decorView, playerPage, pivotBar,
                        screenW, screenH, decorTop, statusBarH, depth + 1
                );
            }
        }
    }

    private static void configureScrollableForTopEdgeToEdge(
            View scrollable,
            View decorView,
            @Nullable View playerPage,
            int screenW,
            int screenH,
            int rvScreenTop,
            int decorTop,
            int statusBarH
    ) {
        if (scrollable instanceof ViewGroup vg) {
            if (vg.getClipToPadding()) vg.setClipToPadding(false);
            if (vg.getClipChildren()) vg.setClipChildren(false);
        }

        View coordinatorChild = findCoordinatorScrollingChild(scrollable);
        ViewGroup siblingAppBar = null;
        ViewGroup coord = null;
        if (coordinatorChild != null && coordinatorChild.getParent() instanceof ViewGroup coordVg) {
            coord = coordVg;
            siblingAppBar = findDirectChildSubclass(coordVg, "AppBarLayout");
        }
        boolean hasActiveAppBar = (siblingAppBar != null
                && siblingAppBar.getVisibility() == View.VISIBLE
                && siblingAppBar.getHeight() > 0);

        // Unclip ancestor chain all the way to DecorView AND clear any status-bar top inset padding/margin
        // on full-screen intermediate wrappers (e.g. fragment roots / browse containers) that would trap content at y = statusBarH.
        int maxInsetTol = statusBarH + Math.round(dpToPx(18f));
        int clearedAncestorTopPx = 0;
        ViewParent p = scrollable.getParent();
        int hops = 0;
        while (p instanceof ViewGroup ancestor && hops < 18) {
            if (ancestor == playerPage) break;
            if (ancestor.getClipToPadding()) {
                ancestor.setClipToPadding(false);
            }
            if (!isSubclassOf(ancestor.getClass(), "ViewPager") && ancestor.getClipChildren()) {
                ancestor.setClipChildren(false);
            }
            if (ancestor != decorView
                    && ancestor.getWidth() >= screenW * 0.90f
                    && ancestor.getHeight() >= screenH * 0.55f) {
                int prevPad = ancestor.getPaddingTop();
                int prevMargin = (ancestor.getLayoutParams() instanceof ViewGroup.MarginLayoutParams mlp)
                        ? mlp.topMargin : 0;
                if (clearTopInsetOnContainer(ancestor, maxInsetTol)) {
                    rootTopInsetRemovedForDecor.add(decorView);
                    if (prevPad > 0 && prevPad <= maxInsetTol) clearedAncestorTopPx += prevPad;
                    if (prevMargin > 0 && prevMargin <= maxInsetTol) clearedAncestorTopPx += prevMargin;
                }
            }
            if (ancestor == decorView) break;
            p = ancestor.getParent();
            hops++;
        }

        // Case 1: CoordinatorLayout + AppBarLayout.ScrollingViewBehavior
        if (hasActiveAppBar && coord != null) {
            clearCanvasStatusBarBackground(coord);
            if (coord.getPaddingTop() > 0) {
                coord.setPadding(coord.getPaddingLeft(), 0, coord.getPaddingRight(), coord.getPaddingBottom());
            }
            makeAppBarFullyScrollable(siblingAppBar, decorTop, statusBarH, rootTopInsetRemovedForDecor.contains(decorView));
            if (!scrollable.isNestedScrollingEnabled()) {
                scrollable.setNestedScrollingEnabled(true);
            }
            return;
        }

        // Case 2: Non-AppBar vertical scroller
        int[] state = adjustedScrollables.get(scrollable);
        if (state == null) {
            // If we just cleared an ancestor top inset (moving scrollable up by clearedAncestorTopPx),
            // transfer that inset to scrollable.paddingTop so resting items stay cleanly below the status bar.
            int remainingTop = Math.max(0, rvScreenTop - clearedAncestorTopPx);
            int totalPadAdd = clearedAncestorTopPx;

            if (remainingTop > Math.round(dpToPx(4f)) && remainingTop <= statusBarH + Math.round(dpToPx(180f))) {
                View shiftTarget = resolveShiftTarget(scrollable, decorTop, rvScreenTop);
                if (extendTargetTopToZero(shiftTarget, remainingTop)) {
                    totalPadAdd += remainingTop;
                }
            }

            if (totalPadAdd > 0) {
                int newPadTop = scrollable.getPaddingTop() + totalPadAdd;
                scrollable.setPadding(
                        scrollable.getPaddingLeft(),
                        newPadTop,
                        scrollable.getPaddingRight(),
                        scrollable.getPaddingBottom()
                );
                adjustedScrollables.put(scrollable, new int[]{totalPadAdd, newPadTop});
            }
        } else {
            // Re-enforce paddingTop if YouTube Music resets padding after a refresh or tab switch
            int shift = state[0];
            int expectedPadTop = state[1];
            int currentPadTop = scrollable.getPaddingTop();
            if (currentPadTop < shift) {
                int targetPad = currentPadTop + shift;
                scrollable.setPadding(
                        scrollable.getPaddingLeft(),
                        targetPad,
                        scrollable.getPaddingRight(),
                        scrollable.getPaddingBottom()
                );
                state[1] = targetPad;
            } else if (currentPadTop != expectedPadTop) {
                state[1] = currentPadTop;
            }
        }
    }

    /**
     * Walks up from scrollable through transparent wrappers (SwipeRefreshLayout, single-child FrameLayout)
     * that share the exact same screen top so we shift the right layout child up to y = 0.
     */
    private static View resolveShiftTarget(View scrollable, int decorTop, int rvScreenTop) {
        View target = scrollable;
        int hops = 0;
        while (hops < 4 && target.getParent() instanceof ViewGroup parentVg) {
            Class<?> pCls = parentVg.getClass();
            if (isSubclassOf(pCls, "WatchWhileLayout") || isSubclassOf(pCls, "CoordinatorLayout")
                    || isSubclassOf(pCls, "ViewPager") || parentVg.getId() == android.R.id.content) {
                break;
            }
            parentVg.getLocationOnScreen(tmpLoc2);
            int pTop = tmpLoc2[1] - decorTop;
            boolean sameTop = Math.abs(pTop - rvScreenTop) <= 2;
            boolean isSwipeWrapper = isSubclassOf(pCls, "SwipeRefresh");
            boolean isSingleWrapper = (parentVg instanceof FrameLayout && parentVg.getChildCount() == 1);
            if (sameTop && (isSwipeWrapper || isSingleWrapper)) {
                target = parentVg;
                hops++;
            } else {
                break;
            }
        }
        return target;
    }

    private static boolean extendTargetTopToZero(View target, int shiftPx) {
        ViewGroup.LayoutParams lp = target.getLayoutParams();
        if (!(lp instanceof ViewGroup.MarginLayoutParams mlp)) return false;
        ViewParent parent = target.getParent();
        if (!(parent instanceof ViewGroup parentVg)) return false;

        // 1. RelativeLayout with BELOW rule: replace BELOW with ALIGN_PARENT_TOP so it starts at top
        if (parentVg instanceof RelativeLayout && lp instanceof RelativeLayout.LayoutParams rlp) {
            int belowId = rlp.getRule(RelativeLayout.BELOW);
            if (belowId != 0) {
                rlp.removeRule(RelativeLayout.BELOW);
                rlp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
                rlp.topMargin = 0;
                target.setLayoutParams(rlp);
                return true;
            }
        }

        // 2. ConstraintLayout with topToBottom constraint: re-anchor topToTop = PARENT_ID (0)
        if (isSubclassOf(parentVg.getClass(), "ConstraintLayout")) {
            try {
                Field topToBottomF = lp.getClass().getField("topToBottom");
                Field topToTopF = lp.getClass().getField("topToTop");
                int curTopToBottom = topToBottomF.getInt(lp);
                if (curTopToBottom != -1) {
                    topToBottomF.setInt(lp, -1); // ConstraintLayout.LayoutParams.UNSET
                    topToTopF.setInt(lp, 0);     // ConstraintLayout.LayoutParams.PARENT_ID
                    mlp.topMargin = 0;
                    target.setLayoutParams(lp);
                    return true;
                }
            } catch (Throwable ignored) {}
        }

        // 3. FrameLayout / LinearLayout / CoordinatorLayout (without ScrollingViewBehavior) / generic MarginLayoutParams
        mlp.topMargin -= shiftPx;
        target.setLayoutParams(mlp);
        return true;
    }

    @Nullable
    private static View findCoordinatorScrollingChild(View view) {
        View cur = view;
        int hops = 0;
        while (cur != null && hops < 5) {
            ViewParent p = cur.getParent();
            if (p instanceof ViewGroup pg && isSubclassOf(pg.getClass(), "CoordinatorLayout")) {
                ViewGroup.LayoutParams lp = cur.getLayoutParams();
                if (lp != null && isSubclassOf(lp.getClass(), "CoordinatorLayout$LayoutParams")) {
                    try {
                        Method getBehavior = lp.getClass().getMethod("getBehavior");
                        Object behavior = getBehavior.invoke(lp);
                        if (behavior != null && isSubclassOf(behavior.getClass(), "ScrollingViewBehavior")) {
                            return cur;
                        }
                    } catch (Throwable ignored) {}
                }
                return null;
            }
            cur = (p instanceof View pv) ? pv : null;
            hops++;
        }
        return null;
    }

    @Nullable
    private static ViewGroup findDirectChildSubclass(ViewGroup parent, String classNameSubstring) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View c = parent.getChildAt(i);
            if (c instanceof ViewGroup vg && isSubclassOf(vg.getClass(), classNameSubstring)) {
                return vg;
            }
        }
        return null;
    }

    private static boolean isVerticalScrollableView(View view) {
        if (view instanceof ScrollView || view instanceof ListView) return true;
        Class<?> cls = view.getClass();
        if (isSubclassOf(cls, "RecyclerView") || isSubclassOf(cls, "NestedScrollView")) {
            return true;
        }
        // Fallback for obfuscated vertical scroll containers (excluding horizontal pagers)
        if (view instanceof ViewGroup && !isSubclassOf(cls, "ViewPager")) {
            return view.canScrollVertically(1) || view.canScrollVertically(-1);
        }
        return false;
    }

    /**
     * Checks whether {@code cls} or any of its superclasses has a name containing {@code needle}.
     * Handles R8-obfuscated YouTube Music subclasses cleanly with per-class caching.
     */
    private static boolean isSubclassOf(Class<?> cls, String needle) {
        Map<String, Boolean> map = classHierarchyCache.get(cls);
        if (map != null) {
            Boolean cached = map.get(needle);
            if (cached != null) return cached;
        }
        boolean found = false;
        for (Class<?> t = cls; t != null && t != Object.class; t = t.getSuperclass()) {
            if (t.getName().contains(needle)) {
                found = true;
                break;
            }
        }
        if (map == null) {
            map = new HashMap<>(8);
            classHierarchyCache.put(cls, map);
        }
        map.put(needle, found);
        return found;
    }

    private static void invokeNoThrow(Object target, String methodName, Class<?>[] paramTypes, Object[] args) {
        try {
            Method m = target.getClass().getMethod(methodName, paramTypes);
            m.invoke(target, args);
        } catch (Throwable ignored) {}
    }

    private static boolean isDescendantOf(View child, View potentialAncestor) {
        ViewParent p = child.getParent();
        while (p instanceof View pv) {
            if (pv == potentialAncestor) return true;
            p = pv.getParent();
        }
        return false;
    }

    private static int getStatusBarHeight(View view) {
        WindowInsets insets = view.getRootWindowInsets();
        if (insets != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                int top = insets.getInsets(WindowInsets.Type.statusBars()).top;
                if (top > 0) return top;
            } else {
                int top = insets.getSystemWindowInsetTop();
                if (top > 0) return top;
            }
        }
        try {
            Resources res = view.getResources();
            int resId = res.getIdentifier("status_bar_height", "dimen", "android");
            if (resId > 0) {
                return res.getDimensionPixelSize(resId);
            }
        } catch (Throwable ignored) {}
        return Math.round(dpToPx(24f));
    }

    private static String getEntryNameLower(View view) {
        int id = view.getId();
        if (id == View.NO_ID) return "";
        try {
            return view.getResources().getResourceEntryName(id).toLowerCase();
        } catch (Throwable ignored) {
            return "";
        }
    }

    @Nullable
    private static Activity resolveActivity(Context context) {
        while (context instanceof ContextWrapper cw) {
            if (cw instanceof Activity act) return act;
            context = cw.getBaseContext();
        }
        return null;
    }

    private static float dpToPx(float dp) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, Resources.getSystem().getDisplayMetrics());
    }
}
