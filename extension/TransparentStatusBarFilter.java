package app.morphe.extension.music.patches.miniplayer;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;

/**
 * Standalone runtime hook for the "Transparent status bar" patch.
 * Removes status bar scrims and opaque header backdrops so vertical content scrolls
 * smoothly edge-to-edge behind the system status bar icons while keeping icons untouched.
 */
@SuppressWarnings({"unused", "deprecation"})
public final class TransparentStatusBarFilter {

    private static final int PLAYER_PAGE_ID =
            ResourceUtils.getIdentifier(ResourceType.ID, "player_page");

    private static final Set<View> hookedDecorViews =
            Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<Window> configuredWindows =
            Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<View> suppressedTopScrims =
            Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<View> clearedScrimToolbars =
            Collections.newSetFromMap(new WeakHashMap<>());
    // Tracks vertical scrollers whose top edge was extended behind the status bar: [appliedShiftPx, expectedPadTop]
    private static final Map<View, int[]> adjustedScrollables = new WeakHashMap<>();
    private static final Map<Class<?>, Boolean> verticalScrollableClasses = new WeakHashMap<>();

    private static final int[] tmpLoc = new int[2];
    private static final int[] tmpLoc2 = new int[2];
    private static long lastFullScanMs = 0L;

    private TransparentStatusBarFilter() {}

    /**
     * Entry point injected by TransparentStatusBarPatch when YouTube Music builds its main UI.
     */
    public static void onAttach(@Nullable View anchorView) {
        if (anchorView == null) return;
        try {
            Activity activity = resolveActivity(anchorView.getContext());
            if (activity == null) return;
            Window window = activity.getWindow();
            if (window == null) return;
            final View decorView = window.getDecorView();
            if (decorView == null) return;

            applyWindowEdgeToEdge(window, decorView);

            if (!hookedDecorViews.add(decorView)) {
                return;
            }

            decorView.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                try {
                    syncTransparentStatusBar(activity, window, decorView, true);
                } catch (Throwable ignored) {}
            });

            decorView.post(() -> {
                try {
                    syncTransparentStatusBar(activity, window, decorView, true);
                    decorView.getViewTreeObserver().addOnPreDrawListener(() -> {
                        try {
                            syncTransparentStatusBar(activity, window, decorView, false);
                        } catch (Throwable ignored) {}
                        return true;
                    });
                } catch (Throwable ignored) {}
            });
        } catch (Throwable ex) {
            Logger.printException(() -> "TransparentStatusBarFilter.onAttach failure", ex);
        }
    }

    private static void applyWindowEdgeToEdge(Window window, View decorView) {
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

            if (configuredWindows.add(window)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    window.setDecorFitsSystemWindows(false);
                } else {
                    int flags = decorView.getSystemUiVisibility();
                    int want = flags
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
                    if (want != flags) {
                        decorView.setSystemUiVisibility(want);
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

        applyWindowEdgeToEdge(window, decorView);

        // Keep system status bar background view completely transparent on every frame
        View sysStatusBg = decorView.findViewById(android.R.id.statusBarBackground);
        if (sysStatusBg != null) {
            suppressScrimView(sysStatusBg);
        }
        if (!suppressedTopScrims.isEmpty()) {
            for (View v : suppressedTopScrims) {
                if (v != null && v.getAlpha() != 0f) {
                    v.setAlpha(0f);
                }
            }
        }

        long now = SystemClock.uptimeMillis();
        if (!forceScan && (now - lastFullScanMs) < 180L) {
            return;
        }
        lastFullScanMs = now;

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

        View playerPage = (PLAYER_PAGE_ID != 0) ? decorView.findViewById(PLAYER_PAGE_ID) : null;

        // 1. Remove any app-level status bar background / scrim layers & opaque AppBar/CollapsingToolbar backdrops
        suppressTopStatusBarLayersRecursive(decorView, playerPage, screenW, decorTop, statusBarH, 0);

        // 2. Make vertical page scrollers flow edge-to-edge behind the status bar icons
        makeScrollablesEdgeToEdgeTopRecursive(decorView, decorView, playerPage, screenW, screenH, decorTop, statusBarH, 0);
    }

    private static void suppressScrimView(View v) {
        suppressedTopScrims.add(v);
        if (v.getAlpha() != 0f) {
            v.setAlpha(0f);
        }
    }

    private static void suppressTopStatusBarLayersRecursive(
            View view, @Nullable View playerPage, int screenW, int decorTop, int statusBarH, int depth
    ) {
        if (view == null || depth > 12 || view.getVisibility() != View.VISIBLE) return;
        if (view == playerPage) return;

        String clsName = view.getClass().getName();
        if (clsName.contains("M3NavigationFilter$")) return;

        String resName = getEntryNameLower(view);
        boolean isStatusBarNamed = resName.contains("status_bar") || resName.contains("statusbar");

        // Clear Material CollapsingToolbarLayout statusBarScrim & contentScrim
        if (clsName.contains("CollapsingToolbarLayout")) {
            clearCollapsingToolbarScrims(view);
        }

        // Clear opaque background & shadow on AppBarLayout / top action bar / Toolbar containers covering the status bar
        if (clsName.contains("AppBarLayout")
                || clsName.contains("Toolbar")
                || resName.equals("action_bar_container")
                || resName.equals("action_bar")
                || resName.equals("toolbar")
                || resName.contains("app_bar")
                || resName.contains("music_action_bar")
                || resName.contains("toolbar_container")) {
            view.getLocationOnScreen(tmpLoc2);
            if (tmpLoc2[1] <= decorTop + dpToPx(8f)) {
                clearTopBarBackground(view);
                if (clsName.contains("AppBarLayout") && view instanceof ViewGroup appBar) {
                    makeAppBarStatusSpacersScrollable(appBar, statusBarH);
                }
            }
        }

        // Suppress leaf or empty status bar background / scrim views
        boolean isLeafOrEmpty = !(view instanceof ViewGroup) || ((ViewGroup) view).getChildCount() == 0;
        if (isLeafOrEmpty
                && !(view instanceof TextView)
                && !(view instanceof ImageView)
                && !(view instanceof ProgressBar)
                && !(view instanceof SurfaceView)
                && !(view instanceof TextureView)
                && !view.isClickable()
                && !view.isFocusable()) {
            int w = view.getWidth();
            int h = view.getHeight();
            if (isStatusBarNamed && h > 0 && h <= statusBarH + dpToPx(16f)) {
                suppressScrimView(view);
                return;
            }
            if (view.getBackground() != null && view.getAlpha() > 0.01f
                    && w >= screenW * 0.90f
                    && h >= Math.max(10, statusBarH - dpToPx(10f))
                    && h <= statusBarH + dpToPx(12f)) {
                view.getLocationOnScreen(tmpLoc2);
                int vTop = tmpLoc2[1];
                int vBottom = vTop + h;
                if (vTop <= decorTop + dpToPx(4f) && vBottom <= decorTop + statusBarH + dpToPx(12f)) {
                    suppressScrimView(view);
                    return;
                }
            }
        }

        // Also clear background on non-empty status-bar wrapper containers without hiding their children
        if (isStatusBarNamed && view instanceof ViewGroup && view.getBackground() != null) {
            clearTopBarBackground(view);
        }

        if (view instanceof ViewGroup vg) {
            for (int i = 0; i < vg.getChildCount(); i++) {
                suppressTopStatusBarLayersRecursive(vg.getChildAt(i), playerPage, screenW, decorTop, statusBarH, depth + 1);
            }
        }
    }

    private static void clearTopBarBackground(View view) {
        Drawable bg = view.getBackground();
        if (bg != null) {
            if (!(bg instanceof ColorDrawable cd) || cd.getColor() != Color.TRANSPARENT) {
                view.setBackgroundColor(Color.TRANSPARENT);
            }
        }
        if (view.getElevation() > 0f) {
            view.setElevation(0f);
        }
        if (view.getStateListAnimator() != null) {
            view.setStateListAnimator(null);
        }
    }

    private static void clearCollapsingToolbarScrims(View view) {
        if (!clearedScrimToolbars.add(view)) return;
        try {
            Method m1 = view.getClass().getMethod("setStatusBarScrimColor", int.class);
            m1.invoke(view, Color.TRANSPARENT);
        } catch (Throwable ignored) {}
        try {
            Method m2 = view.getClass().getMethod("setStatusBarScrim", Drawable.class);
            m2.invoke(view, (Object) null);
        } catch (Throwable ignored) {}
        try {
            Method m3 = view.getClass().getMethod("setContentScrimColor", int.class);
            m3.invoke(view, Color.TRANSPARENT);
        } catch (Throwable ignored) {}
    }

    private static void makeAppBarStatusSpacersScrollable(ViewGroup appBar, int statusBarH) {
        for (int i = 0; i < appBar.getChildCount(); i++) {
            View child = appBar.getChildAt(i);
            if (child == null) continue;
            String resName = getEntryNameLower(child);
            boolean isSpacer = resName.contains("status_bar")
                    || (!(child instanceof ViewGroup) && !(child instanceof TextView) && !(child instanceof ImageView)
                    && child.getHeight() > 0 && child.getHeight() <= statusBarH + dpToPx(12f));
            if (!isSpacer) continue;
            suppressScrimView(child);
            ViewGroup.LayoutParams lp = child.getLayoutParams();
            if (lp != null && lp.getClass().getName().contains("AppBarLayout")) {
                try {
                    Method getFlags = lp.getClass().getMethod("getScrollFlags");
                    Method setFlags = lp.getClass().getMethod("setScrollFlags", int.class);
                    Object curObj = getFlags.invoke(lp);
                    if (curObj instanceof Integer cur && (cur & 1) == 0) {
                        setFlags.invoke(lp, cur | 1); // SCROLL_FLAG_SCROLL = 1
                        child.setLayoutParams(lp);
                    }
                } catch (Throwable ignored) {}
            }
        }
    }

    private static void makeScrollablesEdgeToEdgeTopRecursive(
            View view,
            View decorView,
            @Nullable View playerPage,
            int screenW,
            int screenH,
            int decorTop,
            int statusBarH,
            int depth
    ) {
        if (view == null || depth > 14 || view.getVisibility() != View.VISIBLE) return;
        if (view == playerPage) return;

        String clsName = view.getClass().getName();
        if (clsName.contains("M3NavigationFilter$")) return;

        if (isVerticalScrollableView(view)) {
            int w = view.getWidth();
            int h = view.getHeight();
            // Only adjust full-page vertical scrollers (skip horizontal carousels, chip bars, and small sub-lists)
            if (w >= screenW * 0.85f && h >= screenH * 0.40f) {
                view.getLocationOnScreen(tmpLoc2);
                int rvScreenTop = tmpLoc2[1] - decorTop;
                if (rvScreenTop <= statusBarH + dpToPx(140f)) {
                    configureScrollableForTopEdgeToEdge(view, decorView, playerPage, rvScreenTop, statusBarH);
                }
            }
            return;
        }

        if (view instanceof ViewGroup vg) {
            for (int i = 0; i < vg.getChildCount(); i++) {
                makeScrollablesEdgeToEdgeTopRecursive(
                        vg.getChildAt(i), decorView, playerPage, screenW, screenH, decorTop, statusBarH, depth + 1);
            }
        }
    }

    private static void configureScrollableForTopEdgeToEdge(
            View scrollable, View decorView, @Nullable View playerPage, int rvScreenTop, int statusBarH
    ) {
        if (scrollable instanceof ViewGroup vg) {
            if (vg.getClipToPadding()) vg.setClipToPadding(false);
            if (vg.getClipChildren()) vg.setClipChildren(false);
        }

        // Unclip ancestor chain so scrolled items render cleanly all the way to y = 0 behind status bar icons
        ViewParent p = scrollable.getParent();
        int hops = 0;
        while (p instanceof ViewGroup ancestor && hops < 12) {
            if (ancestor == playerPage) break;
            if (ancestor.getClipToPadding()) {
                ancestor.setClipToPadding(false);
            }
            String ancName = ancestor.getClass().getSimpleName();
            if (!ancName.contains("ViewPager") && ancestor.getClipChildren()) {
                ancestor.setClipChildren(false);
            }
            if (ancestor == decorView) break;
            p = ancestor.getParent();
            hops++;
        }

        // If the scroller itself starts right below the status bar (instead of y = 0), extend its top behind the status bar
        // while adding the exact same amount to paddingTop (with clipToPadding=false) so resting content stays aligned.
        int[] state = adjustedScrollables.get(scrollable);
        if (state == null) {
            int tol = Math.round(dpToPx(10f));
            if (rvScreenTop >= Math.max(12, statusBarH - tol) && rvScreenTop <= statusBarH + tol) {
                View shiftTarget = scrollable;
                if (scrollable.getParent() instanceof ViewGroup parentVg
                        && parentVg.getClass().getSimpleName().contains("SwipeRefresh")) {
                    shiftTarget = parentVg;
                }
                if (canSafelyExtendTopMargin(shiftTarget)) {
                    ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) shiftTarget.getLayoutParams();
                    int shift = rvScreenTop;
                    mlp.topMargin -= shift;
                    shiftTarget.setLayoutParams(mlp);

                    int newPadTop = scrollable.getPaddingTop() + shift;
                    scrollable.setPadding(
                            scrollable.getPaddingLeft(),
                            newPadTop,
                            scrollable.getPaddingRight(),
                            scrollable.getPaddingBottom()
                    );
                    adjustedScrollables.put(scrollable, new int[]{shift, newPadTop});
                }
            }
        } else {
            // Re-enforce paddingTop if YouTube Music resets padding after a refresh/tab switch
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

    private static boolean canSafelyExtendTopMargin(View target) {
        ViewGroup.LayoutParams lp = target.getLayoutParams();
        if (!(lp instanceof ViewGroup.MarginLayoutParams)) return false;
        ViewParent parent = target.getParent();
        if (!(parent instanceof ViewGroup parentVg)) return false;
        String parentCls = parentVg.getClass().getName();
        // CoordinatorLayout ScrollingViewBehavior overrides top layout directly; don't fight it with negative margins
        if (parentCls.contains("CoordinatorLayout") && lp.getClass().getName().contains("CoordinatorLayout")) {
            try {
                Method getBehavior = lp.getClass().getMethod("getBehavior");
                Object behavior = getBehavior.invoke(lp);
                if (behavior != null && behavior.getClass().getName().contains("ScrollingViewBehavior")) {
                    return false;
                }
            } catch (Throwable ignored) {}
        }
        return true;
    }

    private static boolean isVerticalScrollableView(View view) {
        if (view instanceof ScrollView || view instanceof ListView) return true;
        Class<?> cls = view.getClass();
        Boolean cached = verticalScrollableClasses.get(cls);
        if (cached != null) {
            if (cached) return true;
        } else {
            boolean match = false;
            for (Class<?> t = cls; t != null && t != View.class; t = t.getSuperclass()) {
                String simple = t.getSimpleName();
                if ("RecyclerView".equals(simple) || "NestedScrollView".equals(simple)) {
                    match = true;
                    break;
                }
            }
            verticalScrollableClasses.put(cls, match);
            if (match) return true;
        }
        // Fallback for obfuscated vertical scroll containers (excluding horizontal pagers)
        if (view instanceof ViewGroup) {
            String simple = cls.getSimpleName();
            if (!simple.contains("ViewPager")
                    && (view.canScrollVertically(1) || view.canScrollVertically(-1))) {
                return true;
            }
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
