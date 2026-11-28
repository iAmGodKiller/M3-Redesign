package app.morphe.extension.youtube.patches.fullscreen;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.OverScroller;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Locale;
import java.util.WeakHashMap;

/**
 * Standalone YouTube tablet-landscape watch layout patch helper:
 * - Active strictly in tablet landscape two-column watch mode (dormant on phones, portrait, fullscreen, miniplayer).
 * - Merges the channel info + subscribe/bell bar with the action buttons bar (Like/Dislike, Share, Ask, Save,
 *   Download, Report, etc.) AND a native-styled Comment button into ONE single horizontally scrollable row.
 * - Automatically opens and docks the live Comments section in the remaining bottom-left space (leaving
 *   right-column Related Videos visible and scrollable).
 * - Coordinates nested scrolling + header drag so the entire left column from Video Title down through
 *   Comments scrolls smoothly together as one continuous page.
 * - Pressing the Comment button triggers YouTube's native comment composer and keyboard.
 */
@SuppressWarnings("unused")
public final class YouTubeTabletWatchLayout {

    private static final String HOST_TAG = "morphe_yt_tablet_watch_host";
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final WeakHashMap<Activity, WatchState> STATES = new WeakHashMap<>();

    private static volatile boolean enabled = false;
    private static volatile boolean lifecycleRegistered = false;
    private static volatile WeakReference<Activity> currentActivityRef = new WeakReference<>(null);

    private static final String[] NON_COMMENTS_PANEL_TITLES = {
            "description",
            "ask",
            "chapters",
            "transcript",
            "live chat",
            "top chat",
            "products",
            "shopping",
            "clip",
            "playlist",
            "more videos"
    };

    private static final class WatchState {
        boolean listenersInstalled = false;
        View.OnLayoutChangeListener decorLayoutListener;
        ViewTreeObserver.OnWindowFocusChangeListener focusListener;

        WeakReference<ViewGroup> leftWatchRvRef = new WeakReference<>(null);
        View.OnLayoutChangeListener leftWatchRvListener;
        View.OnLayoutChangeListener leftWatchParentListener;
        WeakReference<ViewGroup> leftWatchParentRef = new WeakReference<>(null);

        UnifiedWatchScrollHost hostView;

        // Current video metadata snapshots (never reparented out of YouTube's RecyclerView).
        WeakReference<View> titleItemRef = new WeakReference<>(null);
        WeakReference<View> channelItemRef = new WeakReference<>(null);
        WeakReference<View> actionsItemRef = new WeakReference<>(null);
        WeakReference<View> commentsTeaserRef = new WeakReference<>(null);
        String lastVideoSignature = "";
        String lastAutoOpenedSignature = "";
        long lastCommentsAutoClickMs = 0L;
        int commentsRetryCount = 0;

        // Docked Comments Engagement Panel state (restored cleanly on teardown / orientation change).
        WeakReference<ViewGroup> engagementWrapperRef = new WeakReference<>(null);
        WeakReference<ViewGroup> dockedPanelOriginalParentRef = new WeakReference<>(null);
        WeakReference<View> dockedPanelRootRef = new WeakReference<>(null);
        WeakReference<View> dockedCommentsRvRef = new WeakReference<>(null);
        WeakReference<View> dockedCommentComposerRef = new WeakReference<>(null);
        WeakReference<View> hiddenPanelHeaderRef = new WeakReference<>(null);
        int hiddenPanelHeaderPrevVis = View.VISIBLE;
        int savedComposerOrigHeight = ViewGroup.LayoutParams.WRAP_CONTENT;
        boolean composerHeightSaved = false;
        int dockedPanelOriginalIndex = -1;
        ViewGroup.LayoutParams dockedPanelOriginalLp = null;
        View.OnLayoutChangeListener wrapperMonitorListener;
        boolean wrapperHiddenByUs = false;
        int wrapperPrevVisibility = View.VISIBLE;

        Runnable syncRunnable;
        Runnable delayedSyncRunnable;
        Runnable retryCommentsRunnable;
        Runnable headerRefreshRunnable;
    }

    private YouTubeTabletWatchLayout() {
    }

    // -------------------------------------------------------------------------
    // Patch entry point
    // -------------------------------------------------------------------------

    /**
     * Hooked at index 0 of YouTube's MainActivity.onCreate(Bundle) by "Tablet landscape watch layout".
     */
    public static void onTabletWatchLayoutEnabled(Activity activity) {
        enabled = true;
        if (activity == null) {
            return;
        }
        currentActivityRef = new WeakReference<>(activity);
        registerLifecycleOnce(activity);
    }

    private static void registerLifecycleOnce(Activity activity) {
        if (lifecycleRegistered) {
            return;
        }
        Application app = activity.getApplication();
        if (app == null) {
            return;
        }
        synchronized (YouTubeTabletWatchLayout.class) {
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
                    if (isTargetActivity(act)) {
                        currentActivityRef = new WeakReference<>(act);
                        attachAndSchedule(act);
                    }
                }

                @Override
                public void onActivityResumed(Activity act) {
                    if (isTargetActivity(act)) {
                        currentActivityRef = new WeakReference<>(act);
                        attachAndSchedule(act);
                    }
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

    private static WatchState getState(Activity activity) {
        synchronized (STATES) {
            WatchState s = STATES.get(activity);
            if (s == null) {
                s = new WatchState();
                STATES.put(activity, s);
            }
            return s;
        }
    }

    private static void attachAndSchedule(final Activity activity) {
        if (!enabled || activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        Window window = activity.getWindow();
        if (window == null) {
            return;
        }
        final View decor = window.getDecorView();
        if (decor == null) {
            return;
        }
        final WatchState state = getState(activity);
        installDecorListeners(activity, decor, state);
        scheduleSync(activity, state, 120L);
        scheduleDelayedSync(activity, state, 500L);
    }

    private static void installDecorListeners(Activity activity, final View decor, final WatchState state) {
        if (state.listenersInstalled) {
            return;
        }
        final WeakReference<Activity> actRef = new WeakReference<>(activity);
        state.decorLayoutListener = new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(
                    View v,
                    int left, int top, int right, int bottom,
                    int oldLeft, int oldTop, int oldRight, int oldBottom
            ) {
                Activity act = actRef.get();
                if (act == null || act.isFinishing() || act.isDestroyed()) {
                    return;
                }
                if (!isTabletLandscape(act)) {
                    teardownWatchOverlay(act, state);
                    return;
                }
                scheduleSync(act, state, 100L);
            }
        };
        decor.addOnLayoutChangeListener(state.decorLayoutListener);

        ViewTreeObserver vto = decor.getViewTreeObserver();
        if (vto != null && vto.isAlive()) {
            state.focusListener = new ViewTreeObserver.OnWindowFocusChangeListener() {
                @Override
                public void onWindowFocusChanged(boolean hasFocus) {
                    if (!hasFocus) {
                        return;
                    }
                    Activity act = actRef.get();
                    if (act == null || act.isFinishing() || act.isDestroyed()) {
                        return;
                    }
                    scheduleSync(act, state, 120L);
                }
            };
            vto.addOnWindowFocusChangeListener(state.focusListener);
        }
        state.listenersInstalled = true;
    }

    private static void detachActivity(Activity activity) {
        if (activity == null) {
            return;
        }
        WatchState state;
        synchronized (STATES) {
            state = STATES.remove(activity);
        }
        if (state == null) {
            return;
        }
        cancelCallbacks(state);
        teardownWatchOverlay(activity, state);
        try {
            Window w = activity.getWindow();
            View decor = w != null ? w.peekDecorView() : null;
            if (decor != null) {
                if (state.decorLayoutListener != null) {
                    decor.removeOnLayoutChangeListener(state.decorLayoutListener);
                }
                ViewTreeObserver vto = decor.getViewTreeObserver();
                if (vto != null && vto.isAlive() && state.focusListener != null) {
                    vto.removeOnWindowFocusChangeListener(state.focusListener);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void cancelCallbacks(WatchState state) {
        if (state == null) {
            return;
        }
        if (state.syncRunnable != null) {
            MAIN_HANDLER.removeCallbacks(state.syncRunnable);
            state.syncRunnable = null;
        }
        if (state.delayedSyncRunnable != null) {
            MAIN_HANDLER.removeCallbacks(state.delayedSyncRunnable);
            state.delayedSyncRunnable = null;
        }
        if (state.retryCommentsRunnable != null) {
            MAIN_HANDLER.removeCallbacks(state.retryCommentsRunnable);
            state.retryCommentsRunnable = null;
        }
        if (state.headerRefreshRunnable != null) {
            MAIN_HANDLER.removeCallbacks(state.headerRefreshRunnable);
            state.headerRefreshRunnable = null;
        }
    }

    private static void scheduleSync(Activity activity, final WatchState state, long delayMs) {
        if (state.syncRunnable != null) {
            MAIN_HANDLER.removeCallbacks(state.syncRunnable);
        }
        final WeakReference<Activity> actRef = new WeakReference<>(activity);
        state.syncRunnable = new Runnable() {
            @Override
            public void run() {
                Activity act = actRef.get();
                if (act == null || act.isFinishing() || act.isDestroyed()) {
                    return;
                }
                syncTabletWatchLayout(act, state);
            }
        };
        MAIN_HANDLER.postDelayed(state.syncRunnable, delayMs);
    }

    private static void scheduleDelayedSync(Activity activity, final WatchState state, long delayMs) {
        if (state.delayedSyncRunnable != null) {
            MAIN_HANDLER.removeCallbacks(state.delayedSyncRunnable);
        }
        final WeakReference<Activity> actRef = new WeakReference<>(activity);
        state.delayedSyncRunnable = new Runnable() {
            @Override
            public void run() {
                Activity act = actRef.get();
                if (act == null || act.isFinishing() || act.isDestroyed()) {
                    return;
                }
                syncTabletWatchLayout(act, state);
            }
        };
        MAIN_HANDLER.postDelayed(state.delayedSyncRunnable, delayMs);
    }

    // -------------------------------------------------------------------------
    // Tablet landscape detection & core synchronization
    // -------------------------------------------------------------------------

    private static boolean isTabletLandscape(Activity activity) {
        if (activity == null) {
            return false;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activity.isInPictureInPictureMode()) {
                return false;
            }
            Resources res = activity.getResources();
            if (res == null) {
                return false;
            }
            Configuration cfg = res.getConfiguration();
            DisplayMetrics dm = res.getDisplayMetrics();
            if (cfg == null || dm == null || dm.density <= 0f) {
                return false;
            }
            boolean landscape = cfg.orientation == Configuration.ORIENTATION_LANDSCAPE
                    || dm.widthPixels > dm.heightPixels;
            if (!landscape) {
                return false;
            }
            float minDimDp = Math.min(dm.widthPixels, dm.heightPixels) / dm.density;
            int swDp = cfg.smallestScreenWidthDp > 0 ? cfg.smallestScreenWidthDp : Math.round(minDimDp);
            float widthDp = dm.widthPixels / dm.density;
            return swDp >= 580 || widthDp >= 840f;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void syncTabletWatchLayout(Activity activity, WatchState state) {
        if (!enabled || activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        if (!isTabletLandscape(activity)) {
            teardownWatchOverlay(activity, state);
            return;
        }
        Window window = activity.getWindow();
        if (window == null) {
            return;
        }
        View content = window.findViewById(android.R.id.content);
        View decor = window.getDecorView();
        View root = content != null ? content : decor;
        if (root == null || root.getWidth() <= 0 || root.getHeight() <= 0) {
            return;
        }

        int screenW = root.getWidth();
        int screenH = root.getHeight();
        Resources res = activity.getResources();

        // 1. Locate the left-column watch metadata RecyclerView directly below the video player.
        ViewGroup leftWatchRv = findLeftWatchRecyclerView(root, screenW, screenH, res);
        if (leftWatchRv == null) {
            teardownWatchOverlay(activity, state);
            return;
        }

        // Verify watch metadata items exist inside leftWatchRv (Title + Channel + Action Buttons).
        WatchMetaItems items = parseWatchMetaItems(leftWatchRv, res);
        if (items == null || items.titleItem == null || items.channelItem == null || items.actionsItem == null) {
            // Video metadata may still be binding; keep existing host if same RV, otherwise retry once.
            if (state.hostView != null && state.leftWatchRvRef.get() != leftWatchRv) {
                teardownWatchOverlay(activity, state);
            }
            return;
        }

        // Attach layout observer to leftWatchRv so video changes, resizes, fullscreen, or minimize trigger instant sync.
        attachLeftWatchObservers(activity, leftWatchRv, state);

        // 2. Ensure UnifiedWatchScrollHost is mounted directly over leftWatchRv inside its parent.
        UnifiedWatchScrollHost host = ensureHostMounted(activity, leftWatchRv, state);
        if (host == null) {
            return;
        }
        syncHostBoundsToTarget(host, leftWatchRv);

        // 3. Detect video switches and update the merged header (Title + Single Scrollable Row).
        String sig = extractTitleSignature(items.titleItem, items.channelItem);
        boolean videoChanged = !sig.isEmpty() && !sig.equals(state.lastVideoSignature);
        boolean itemsChanged = items.titleItem != state.titleItemRef.get()
                || items.channelItem != state.channelItemRef.get()
                || items.actionsItem != state.actionsItemRef.get();

        state.titleItemRef = new WeakReference<>(items.titleItem);
        state.channelItemRef = new WeakReference<>(items.channelItem);
        state.actionsItemRef = new WeakReference<>(items.actionsItem);
        state.commentsTeaserRef = new WeakReference<>(items.commentsTeaserItem);

        if (videoChanged) {
            state.lastVideoSignature = sig;
            state.commentsRetryCount = 0;
            host.resetScrollPosition();
            // Return any stale docked panel from a previous video if detached.
            if (isDockedPanelStale(state)) {
                undockCommentsPanel(state);
            }
        }

        if (videoChanged || itemsChanged || !host.hasHeaderContent()) {
            host.bindHeaderItems(
                    activity,
                    items.titleItem,
                    items.channelItem,
                    items.actionsItem,
                    items.commentsTeaserItem,
                    new Runnable() {
                        @Override
                        public void run() {
                            onCommentButtonClicked(activity, state);
                        }
                    }
            );
            scheduleHeaderVisualRefresh(activity, state);
        } else {
            host.refreshHeaderVisuals();
        }

        // 4. Check whether a non-Comments Engagement Panel (Description, Ask, Chapters, etc.) is open.
        //    If so, keep YouTube's right-side engagement panel wrapper visible so those panels work normally.
        checkWrapperForNonCommentsPanel(state);

        // 5. Auto-open Comments by default for this video and dock the live Comments panel into the bottom slot.
        if (!hasValidDockedComments(state)) {
            if (!tryDockLiveCommentsPanel(root, screenW, screenH, state, host)) {
                // Trigger YouTube's Comments Engagement Panel automatically once per video.
                if (items.commentsTeaserItem != null
                        && (!sig.equals(state.lastAutoOpenedSignature) || state.commentsRetryCount < 3)) {
                    long now = SystemClock.uptimeMillis();
                    if (now - state.lastCommentsAutoClickMs > 900L) {
                        state.lastAutoOpenedSignature = sig;
                        state.lastCommentsAutoClickMs = now;
                        state.commentsRetryCount++;
                        clickViewSmart(items.commentsTeaserItem);
                    }
                    scheduleCommentsDockRetry(activity, state, 280L);
                }
            }
        } else {
            host.setCommentsBodyView(
                    state.dockedPanelRootRef.get(),
                    state.dockedCommentsRvRef.get()
            );
        }
    }

    private static void scheduleCommentsDockRetry(Activity activity, final WatchState state, long delayMs) {
        if (state.retryCommentsRunnable != null) {
            MAIN_HANDLER.removeCallbacks(state.retryCommentsRunnable);
        }
        final WeakReference<Activity> actRef = new WeakReference<>(activity);
        state.retryCommentsRunnable = new Runnable() {
            @Override
            public void run() {
                Activity act = actRef.get();
                if (act == null || act.isFinishing() || act.isDestroyed() || !isTabletLandscape(act)) {
                    return;
                }
                Window w = act.getWindow();
                View content = w != null ? w.findViewById(android.R.id.content) : null;
                View root = content != null ? content : (w != null ? w.getDecorView() : null);
                if (root == null || state.hostView == null) {
                    return;
                }
                boolean docked = tryDockLiveCommentsPanel(
                        root, root.getWidth(), root.getHeight(), state, state.hostView
                );
                if (!docked && state.commentsRetryCount < 8) {
                    state.commentsRetryCount++;
                    MAIN_HANDLER.postDelayed(this, 320L);
                }
            }
        };
        MAIN_HANDLER.postDelayed(state.retryCommentsRunnable, delayMs);
    }

    private static void scheduleHeaderVisualRefresh(Activity activity, final WatchState state) {
        if (state.headerRefreshRunnable != null) {
            MAIN_HANDLER.removeCallbacks(state.headerRefreshRunnable);
        }
        final WeakReference<Activity> actRef = new WeakReference<>(activity);
        state.headerRefreshRunnable = new Runnable() {
            @Override
            public void run() {
                Activity act = actRef.get();
                if (act == null || act.isFinishing() || act.isDestroyed() || state.hostView == null) {
                    return;
                }
                state.hostView.refreshHeaderVisuals();
            }
        };
        MAIN_HANDLER.postDelayed(state.headerRefreshRunnable, 450L);
        MAIN_HANDLER.postDelayed(state.headerRefreshRunnable, 1100L);
    }

    private static void attachLeftWatchObservers(final Activity activity, ViewGroup leftWatchRv, final WatchState state) {
        ViewGroup prevRv = state.leftWatchRvRef.get();
        if (prevRv == leftWatchRv) {
            return;
        }
        if (prevRv != null && state.leftWatchRvListener != null) {
            try {
                prevRv.removeOnLayoutChangeListener(state.leftWatchRvListener);
            } catch (Throwable ignored) {
            }
        }
        ViewGroup prevParent = state.leftWatchParentRef.get();
        if (prevParent != null && state.leftWatchParentListener != null) {
            try {
                prevParent.removeOnLayoutChangeListener(state.leftWatchParentListener);
            } catch (Throwable ignored) {
            }
        }

        final WeakReference<Activity> actRef = new WeakReference<>(activity);
        state.leftWatchRvListener = new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(
                    View v,
                    int left, int top, int right, int bottom,
                    int oldLeft, int oldTop, int oldRight, int oldBottom
            ) {
                Activity act = actRef.get();
                if (act == null || act.isFinishing() || act.isDestroyed()) {
                    return;
                }
                if (state.hostView != null && v instanceof ViewGroup) {
                    syncHostBoundsToTarget(state.hostView, (ViewGroup) v);
                }
                scheduleSync(act, state, 90L);
            }
        };
        leftWatchRv.addOnLayoutChangeListener(state.leftWatchRvListener);
        state.leftWatchRvRef = new WeakReference<>(leftWatchRv);

        ViewParent vp = leftWatchRv.getParent();
        if (vp instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) vp;
            state.leftWatchParentListener = new View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(
                        View v,
                        int left, int top, int right, int bottom,
                        int oldLeft, int oldTop, int oldRight, int oldBottom
                ) {
                    Activity act = actRef.get();
                    ViewGroup rv = state.leftWatchRvRef.get();
                    if (act == null || rv == null || state.hostView == null) {
                        return;
                    }
                    syncHostBoundsToTarget(state.hostView, rv);
                }
            };
            parent.addOnLayoutChangeListener(state.leftWatchParentListener);
            state.leftWatchParentRef = new WeakReference<>(parent);
        }
    }

    private static UnifiedWatchScrollHost ensureHostMounted(
            Activity activity,
            ViewGroup leftWatchRv,
            WatchState state
    ) {
        ViewParent parent = leftWatchRv.getParent();
        if (!(parent instanceof ViewGroup)) {
            return null;
        }
        ViewGroup container = (ViewGroup) parent;

        UnifiedWatchScrollHost host = state.hostView;
        if (host != null && host.getParent() == container && host.isAttachedToWindow()) {
            host.bringToFront();
            return host;
        }

        // Remove any previous host instance cleanly.
        if (host != null && host.getParent() instanceof ViewGroup) {
            ((ViewGroup) host.getParent()).removeView(host);
        }

        host = new UnifiedWatchScrollHost(activity);
        host.setTag(HOST_TAG);
        host.setBackgroundColor(resolveWatchSurfaceColor(activity, leftWatchRv));

        ViewGroup.LayoutParams lp = createMatchingOverlayParams(container, leftWatchRv);
        container.addView(host, lp);
        host.bringToFront();
        state.hostView = host;
        return host;
    }

    private static ViewGroup.LayoutParams createMatchingOverlayParams(ViewGroup parent, View target) {
        int w = target.getWidth() > 0 ? target.getWidth() : ViewGroup.LayoutParams.MATCH_PARENT;
        int h = target.getHeight() > 0 ? target.getHeight() : ViewGroup.LayoutParams.MATCH_PARENT;
        if (parent instanceof FrameLayout) {
            FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(w, h, Gravity.TOP | Gravity.START);
            flp.leftMargin = target.getLeft();
            flp.topMargin = target.getTop();
            return flp;
        }
        ViewGroup.MarginLayoutParams mlp = new ViewGroup.MarginLayoutParams(w, h);
        mlp.leftMargin = target.getLeft();
        mlp.topMargin = target.getTop();
        return mlp;
    }

    private static void syncHostBoundsToTarget(UnifiedWatchScrollHost host, ViewGroup target) {
        if (host == null || target == null) {
            return;
        }
        int tw = target.getWidth();
        int th = target.getHeight();
        int tl = target.getLeft();
        int tt = target.getTop();
        if (tw <= 0 || th <= 0 || !target.isShown()) {
            if (host.getVisibility() != View.GONE) {
                host.setVisibility(View.GONE);
            }
            return;
        }
        if (host.getVisibility() != View.VISIBLE) {
            host.setVisibility(View.VISIBLE);
        }
        ViewGroup.LayoutParams lp = host.getLayoutParams();
        boolean changed = false;
        if (lp != null) {
            if (lp.width != tw) {
                lp.width = tw;
                changed = true;
            }
            if (lp.height != th) {
                lp.height = th;
                changed = true;
            }
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
                if (mlp.leftMargin != tl) {
                    mlp.leftMargin = tl;
                    changed = true;
                }
                if (mlp.topMargin != tt) {
                    mlp.topMargin = tt;
                    changed = true;
                }
            }
            if (changed) {
                host.setLayoutParams(lp);
            }
        }
        // Keep exact alignment if parent uses custom layout (like NextGenWatchLayout).
        if (host.getLeft() != tl || host.getTop() != tt
                || host.getRight() != tl + tw || host.getBottom() != tt + th) {
            host.measure(
                    View.MeasureSpec.makeMeasureSpec(tw, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(th, View.MeasureSpec.EXACTLY)
            );
            host.layout(tl, tt, tl + tw, tt + th);
        }
        host.setTranslationX(target.getTranslationX());
        host.setTranslationY(target.getTranslationY());
    }

    private static void teardownWatchOverlay(Activity activity, WatchState state) {
        if (state == null) {
            return;
        }
        if (state.retryCommentsRunnable != null) {
            MAIN_HANDLER.removeCallbacks(state.retryCommentsRunnable);
            state.retryCommentsRunnable = null;
        }
        undockCommentsPanel(state);

        if (state.hostView != null) {
            try {
                ViewParent p = state.hostView.getParent();
                if (p instanceof ViewGroup) {
                    ((ViewGroup) p).removeView(state.hostView);
                }
            } catch (Throwable ignored) {
            }
            state.hostView = null;
        }
        ViewGroup prevRv = state.leftWatchRvRef.get();
        if (prevRv != null && state.leftWatchRvListener != null) {
            try {
                prevRv.removeOnLayoutChangeListener(state.leftWatchRvListener);
            } catch (Throwable ignored) {
            }
        }
        state.leftWatchRvRef = new WeakReference<>(null);
        ViewGroup prevParent = state.leftWatchParentRef.get();
        if (prevParent != null && state.leftWatchParentListener != null) {
            try {
                prevParent.removeOnLayoutChangeListener(state.leftWatchParentListener);
            } catch (Throwable ignored) {
            }
        }
        state.leftWatchParentRef = new WeakReference<>(null);
        state.lastVideoSignature = "";
        state.lastAutoOpenedSignature = "";
        state.commentsRetryCount = 0;
    }

    // -------------------------------------------------------------------------
    // Finding left-column watch RecyclerView & parsing Title / Channel / Actions / Comments teaser
    // -------------------------------------------------------------------------

    private static ViewGroup findLeftWatchRecyclerView(View root, int screenW, int screenH, Resources res) {
        ArrayList<ViewGroup> candidates = new ArrayList<>(6);
        collectVisibleRecyclerViews(root, 0, candidates);
        if (candidates.isEmpty()) {
            return null;
        }

        int minHeightPx = dpToPx(res, 110);
        int minPlayerTopPx = Math.max(dpToPx(res, 150), (int) (screenH * 0.22f));
        int maxTopPx = (int) (screenH * 0.80f);
        int[] loc = new int[2];

        ViewGroup best = null;
        int bestScore = -1;

        for (int i = 0; i < candidates.size(); i++) {
            ViewGroup rv = candidates.get(i);
            if (rv == null || !rv.isShown() || rv.getChildCount() < 2) {
                continue;
            }
            if (isInsideOurHost(rv)) {
                continue;
            }
            int w = rv.getWidth();
            int h = rv.getHeight();
            if (w < (int) (screenW * 0.45f) || w > (int) (screenW * 0.84f) || h < minHeightPx) {
                continue;
            }
            rv.getLocationInWindow(loc);
            int x = loc[0];
            int y = loc[1];
            if (x > (int) (screenW * 0.14f) || y < minPlayerTopPx || y > maxTopPx) {
                continue;
            }
            // Prefer vertical RecyclerView containing an action-buttons horizontal scroller.
            int score = 10;
            if (findHorizontalScrollerInside(rv, 0) != null) {
                score += 100;
            }
            if (score > bestScore) {
                bestScore = score;
                best = rv;
            }
        }
        return best;
    }

    private static void collectVisibleRecyclerViews(View node, int depth, ArrayList<ViewGroup> out) {
        if (node == null || depth > 14 || !node.isShown()) {
            return;
        }
        if (HOST_TAG.equals(node.getTag())) {
            return;
        }
        if (node instanceof ViewGroup) {
            if (isRecyclerView(node)) {
                out.add((ViewGroup) node);
            }
            ViewGroup vg = (ViewGroup) node;
            int count = vg.getChildCount();
            for (int i = 0; i < count; i++) {
                collectVisibleRecyclerViews(vg.getChildAt(i), depth + 1, out);
            }
        }
    }

    private static final class WatchMetaItems {
        View titleItem;
        View channelItem;
        View actionsItem;
        View commentsTeaserItem;
    }

    private static WatchMetaItems parseWatchMetaItems(ViewGroup leftWatchRv, Resources res) {
        int count = leftWatchRv.getChildCount();
        if (count < 3) {
            return null;
        }
        ArrayList<View> sorted = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            View c = leftWatchRv.getChildAt(i);
            if (c != null && c.getVisibility() == View.VISIBLE && c.getHeight() > dpToPx(res, 16)) {
                sorted.add(c);
            }
        }
        if (sorted.size() < 3) {
            return null;
        }
        Collections.sort(sorted, new Comparator<View>() {
            @Override
            public int compare(View a, View b) {
                return Integer.compare(a.getTop(), b.getTop());
            }
        });

        int actionsIdx = -1;
        for (int i = 1; i < Math.min(sorted.size(), 5); i++) {
            View child = sorted.get(i);
            if (child.getHeight() <= dpToPx(res, 84) && findHorizontalScrollerInside(child, 0) != null) {
                actionsIdx = i;
                break;
            }
        }
        if (actionsIdx < 2) {
            // Fallback: standard YouTube tablet watch order is [0]=Title, [1]=Channel, [2]=Actions, [3]=Comments teaser.
            if (sorted.size() >= 3) {
                actionsIdx = 2;
            } else {
                return null;
            }
        }

        WatchMetaItems out = new WatchMetaItems();
        out.actionsItem = sorted.get(actionsIdx);
        out.channelItem = sorted.get(actionsIdx - 1);
        out.titleItem = sorted.get(0);
        if (actionsIdx + 1 < sorted.size()) {
            out.commentsTeaserItem = sorted.get(actionsIdx + 1);
        }
        return out;
    }

    private static ViewGroup findHorizontalScrollerInside(View node, int depth) {
        if (node == null || depth > 6 || !node.isShown()) {
            return null;
        }
        if (node instanceof HorizontalScrollView) {
            return (ViewGroup) node;
        }
        if (node instanceof ViewGroup && isRecyclerView(node)) {
            int w = node.getWidth();
            int h = node.getHeight();
            if (w > 0 && h > 0 && w >= h * 3 && ((ViewGroup) node).getChildCount() >= 2) {
                return (ViewGroup) node;
            }
        }
        if (node instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) node;
            int count = vg.getChildCount();
            for (int i = 0; i < count; i++) {
                ViewGroup found = findHorizontalScrollerInside(vg.getChildAt(i), depth + 1);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static String extractTitleSignature(View titleItem, View channelItem) {
        StringBuilder sb = new StringBuilder();
        collectTextSnippet(titleItem, 0, sb, 64);
        sb.append('|');
        collectTextSnippet(channelItem, 0, sb, 32);
        return sb.toString();
    }

    private static void collectTextSnippet(View node, int depth, StringBuilder sb, int maxLen) {
        if (node == null || depth > 5 || sb.length() >= maxLen) {
            return;
        }
        if (node instanceof TextView) {
            CharSequence t = ((TextView) node).getText();
            if (t != null && t.length() > 0) {
                sb.append(t, 0, Math.min(t.length(), maxLen - sb.length()));
            }
        }
        CharSequence cd = node.getContentDescription();
        if (cd != null && cd.length() > 0 && sb.length() < maxLen) {
            sb.append(cd, 0, Math.min(cd.length(), maxLen - sb.length()));
        }
        if (node instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) node;
            int count = vg.getChildCount();
            for (int i = 0; i < count && sb.length() < maxLen; i++) {
                collectTextSnippet(vg.getChildAt(i), depth + 1, sb, maxLen);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Docking YouTube's live Comments Engagement Panel into the left column
    // -------------------------------------------------------------------------

    private static boolean hasValidDockedComments(WatchState state) {
        View panel = state.dockedPanelRootRef.get();
        return panel != null && panel.isAttachedToWindow() && state.hostView != null
                && isDescendantOf(panel, state.hostView);
    }

    private static boolean isDockedPanelStale(WatchState state) {
        View panel = state.dockedPanelRootRef.get();
        return panel != null && !panel.isAttachedToWindow();
    }

    private static boolean tryDockLiveCommentsPanel(
            View root,
            int screenW,
            int screenH,
            final WatchState state,
            UnifiedWatchScrollHost host
    ) {
        if (root == null || host == null) {
            return false;
        }
        // Locate a visible Engagement Panel container holding the Comments RecyclerView.
        ViewGroup leftWatchRv = state.leftWatchRvRef.get();
        CommentsPanelMatch match = findCommentsEngagementPanel(root, leftWatchRv, screenW, screenH, state);
        if (match == null || match.panelRoot == null || match.commentsRv == null) {
            return false;
        }

        ViewParent origParent = match.panelRoot.getParent();
        if (!(origParent instanceof ViewGroup)) {
            return false;
        }
        ViewGroup origParentGroup = (ViewGroup) origParent;

        // Save original attachment info so we can restore 100% cleanly on rotation / fullscreen / non-comments panel.
        undockCommentsPanel(state);

        int origIndex = origParentGroup.indexOfChild(match.panelRoot);
        ViewGroup.LayoutParams origLp = match.panelRoot.getLayoutParams();

        state.engagementWrapperRef = new WeakReference<>(match.wrapperToHide);
        state.dockedPanelOriginalParentRef = new WeakReference<>(origParentGroup);
        state.dockedPanelOriginalIndex = origIndex;
        state.dockedPanelOriginalLp = origLp;
        state.dockedPanelRootRef = new WeakReference<>(match.panelRoot);
        state.dockedCommentsRvRef = new WeakReference<>(match.commentsRv);
        state.dockedCommentComposerRef = new WeakReference<>(match.composerView);

        // Hide the redundant "Comments  X" title header bar to maximize space for actual comments.
        if (match.headerToHide != null && match.headerToHide != match.commentsRv) {
            state.hiddenPanelHeaderRef = new WeakReference<>(match.headerToHide);
            state.hiddenPanelHeaderPrevVis = match.headerToHide.getVisibility();
            match.headerToHide.setVisibility(View.GONE);
        }

        // Collapse the bottom composer bar visually (kept attached so our merged-row Comment button triggers it).
        if (match.composerView != null && match.composerView != match.commentsRv) {
            collapseComposerVisually(match.composerView, state);
        }

        try {
            origParentGroup.removeView(match.panelRoot);
            host.setCommentsBodyView(match.panelRoot, match.commentsRv);

            // Hide the now-empty right-side engagement panel wrapper so Related Videos are 100% visible & scrollable.
            if (match.wrapperToHide != null && !isAncestorOf(match.wrapperToHide, host)) {
                state.wrapperPrevVisibility = match.wrapperToHide.getVisibility();
                match.wrapperToHide.setVisibility(View.INVISIBLE);
                state.wrapperHiddenByUs = true;
                attachWrapperMonitor(match.wrapperToHide, state);
            }
            return true;
        } catch (Throwable ignored) {
            undockCommentsPanel(state);
            return false;
        }
    }

    private static void attachWrapperMonitor(final ViewGroup wrapper, final WatchState state) {
        if (wrapper == null) {
            return;
        }
        if (state.wrapperMonitorListener != null) {
            try {
                wrapper.removeOnLayoutChangeListener(state.wrapperMonitorListener);
            } catch (Throwable ignored) {
            }
        }
        state.wrapperMonitorListener = new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(
                    View v,
                    int left, int top, int right, int bottom,
                    int oldLeft, int oldTop, int oldRight, int oldBottom
            ) {
                checkWrapperForNonCommentsPanel(state);
            }
        };
        wrapper.addOnLayoutChangeListener(state.wrapperMonitorListener);
    }

    private static void checkWrapperForNonCommentsPanel(WatchState state) {
        ViewGroup wrapper = state.engagementWrapperRef.get();
        if (wrapper == null || !state.wrapperHiddenByUs) {
            return;
        }
        // If YouTube added/revealed another panel (e.g. Description from "...more" or Ask) inside the wrapper,
        // reveal the wrapper so the user can interact with Description / Ask normally on the right column.
        boolean hasOtherActiveChild = false;
        int count = wrapper.getChildCount();
        for (int i = 0; i < count; i++) {
            View child = wrapper.getChildAt(i);
            if (child != null && child.getVisibility() == View.VISIBLE && child.getWidth() > 0 && child.getHeight() > 0) {
                if (hasNonCommentsHeaderTitle(child, 0) || hasVisibleContentChildren(child)) {
                    hasOtherActiveChild = true;
                    break;
                }
            }
        }
        int targetVis = hasOtherActiveChild ? View.VISIBLE : View.INVISIBLE;
        if (wrapper.getVisibility() != targetVis) {
            wrapper.setVisibility(targetVis);
        }
    }

    private static boolean hasVisibleContentChildren(View v) {
        if (!(v instanceof ViewGroup)) {
            return false;
        }
        ViewGroup vg = (ViewGroup) v;
        for (int i = 0; i < vg.getChildCount(); i++) {
            View c = vg.getChildAt(i);
            if (c != null && c.getVisibility() == View.VISIBLE && c.getHeight() > 40) {
                return true;
            }
        }
        return false;
    }

    private static void undockCommentsPanel(WatchState state) {
        if (state == null) {
            return;
        }
        View hiddenHeader = state.hiddenPanelHeaderRef.get();
        if (hiddenHeader != null) {
            try {
                hiddenHeader.setVisibility(state.hiddenPanelHeaderPrevVis);
            } catch (Throwable ignored) {
            }
            state.hiddenPanelHeaderRef = new WeakReference<>(null);
        }

        View composer = state.dockedCommentComposerRef.get();
        if (composer != null) {
            restoreComposerVisually(composer, state);
        }

        View panelRoot = state.dockedPanelRootRef.get();
        ViewGroup origParent = state.dockedPanelOriginalParentRef.get();
        if (panelRoot != null) {
            try {
                ViewParent currentParent = panelRoot.getParent();
                if (currentParent instanceof ViewGroup && currentParent != origParent) {
                    ((ViewGroup) currentParent).removeView(panelRoot);
                }
                if (origParent != null && origParent.isAttachedToWindow() && panelRoot.getParent() == null) {
                    int idx = Math.max(0, Math.min(state.dockedPanelOriginalIndex, origParent.getChildCount()));
                    if (state.dockedPanelOriginalLp != null) {
                        origParent.addView(panelRoot, idx, state.dockedPanelOriginalLp);
                    } else {
                        origParent.addView(panelRoot, idx);
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        ViewGroup wrapper = state.engagementWrapperRef.get();
        if (wrapper != null) {
            try {
                if (state.wrapperMonitorListener != null) {
                    wrapper.removeOnLayoutChangeListener(state.wrapperMonitorListener);
                }
                if (state.wrapperHiddenByUs) {
                    wrapper.setVisibility(state.wrapperPrevVisibility);
                }
            } catch (Throwable ignored) {
            }
        }

        state.wrapperHiddenByUs = false;
        state.engagementWrapperRef = new WeakReference<>(null);
        state.dockedPanelOriginalParentRef = new WeakReference<>(null);
        state.dockedPanelRootRef = new WeakReference<>(null);
        state.dockedCommentsRvRef = new WeakReference<>(null);
        state.dockedCommentComposerRef = new WeakReference<>(null);
        state.dockedPanelOriginalLp = null;
        state.dockedPanelOriginalIndex = -1;
    }

    private static final class CommentsPanelMatch {
        ViewGroup wrapperToHide;
        View panelRoot;
        View commentsRv;
        View headerToHide;
        View composerView;
    }

    private static CommentsPanelMatch findCommentsEngagementPanel(
            View root,
            ViewGroup leftWatchRv,
            int screenW,
            int screenH,
            WatchState state
    ) {
        ArrayList<ViewGroup> rvs = new ArrayList<>(8);
        collectVisibleRecyclerViews(root, 0, rvs);
        int[] loc = new int[2];
        boolean recentlyAutoOpened = (SystemClock.uptimeMillis() - state.lastCommentsAutoClickMs) < 3500L;

        for (int i = 0; i < rvs.size(); i++) {
            ViewGroup rv = rvs.get(i);
            if (rv == null || rv == leftWatchRv || isInsideOurHost(rv) || !rv.isShown()) {
                continue;
            }
            if (rv.getHeight() < screenH * 0.25f || rv.getWidth() < screenW * 0.18f) {
                continue;
            }
            // Walk up to find an EngagementPanel container enclosing this RecyclerView.
            ViewGroup epContainer = findEnclosingEngagementPanel(rv, leftWatchRv);
            if (epContainer == null) {
                continue;
            }
            // Never steal Description / Ask / Chapters / Transcript / Live Chat panels.
            if (hasNonCommentsHeaderTitle(epContainer, 0)) {
                continue;
            }

            View composer = findCommentComposerView(epContainer, rv, 0);
            boolean isCommentsPanel = composer != null
                    || hasCommentsHeaderTitle(epContainer, 0)
                    || hasCommentResourceName(epContainer, 0);

            if (!isCommentsPanel && recentlyAutoOpened) {
                // On non-English devices right after clicking the comments teaser, verify it's an engagement panel
                // positioned on the right column or overlay.
                epContainer.getLocationInWindow(loc);
                if (loc[0] >= (int) (screenW * 0.40f)) {
                    isCommentsPanel = true;
                }
            }

            if (!isCommentsPanel) {
                continue;
            }

            CommentsPanelMatch m = new CommentsPanelMatch();
            m.commentsRv = rv;
            m.composerView = composer;
            m.panelRoot = resolveReparentablePanelRoot(epContainer, rv);
            m.wrapperToHide = resolveOuterEngagementWrapper(epContainer, leftWatchRv);
            m.headerToHide = findPanelHeaderBar(m.panelRoot, rv);
            return m;
        }
        return null;
    }

    private static ViewGroup findEnclosingEngagementPanel(View rv, ViewGroup leftWatchRv) {
        ViewParent p = rv.getParent();
        ViewGroup candidate = null;
        int steps = 0;
        while (p instanceof ViewGroup && steps < 10) {
            ViewGroup vg = (ViewGroup) p;
            if (leftWatchRv != null && isAncestorOf(vg, leftWatchRv)) {
                break;
            }
            String idName = getResourceEntryNameSafe(vg);
            String clsName = vg.getClass().getName().toLowerCase(Locale.US);
            if (idName.contains("engagement_panel")
                    || idName.contains("panel_content")
                    || idName.contains("comments")
                    || clsName.contains("engagementpanel")) {
                candidate = vg;
            }
            p = vg.getParent();
            steps++;
        }
        return candidate;
    }

    private static View resolveReparentablePanelRoot(ViewGroup epContainer, View commentsRv) {
        // Prefer the direct panel child inside the engagement panel wrapper so the wrapper remains intact
        // in YouTube's layout if the user opens Description/Ask later.
        View current = commentsRv;
        View best = epContainer;
        while (current != null && current != epContainer && current.getParent() instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) current.getParent();
            if (parent == epContainer) {
                best = current;
                break;
            }
            current = parent;
        }
        return best;
    }

    private static ViewGroup resolveOuterEngagementWrapper(ViewGroup epContainer, ViewGroup leftWatchRv) {
        ViewGroup wrapper = epContainer;
        ViewParent p = epContainer.getParent();
        int steps = 0;
        while (p instanceof ViewGroup && steps < 4) {
            ViewGroup vg = (ViewGroup) p;
            if (leftWatchRv != null && isAncestorOf(vg, leftWatchRv)) {
                break;
            }
            String idName = getResourceEntryNameSafe(vg);
            if (idName.contains("engagement_panel") || idName.contains("panel_wrapper")) {
                wrapper = vg;
            }
            p = vg.getParent();
            steps++;
        }
        return wrapper;
    }

    private static View findPanelHeaderBar(View panelRoot, View commentsRv) {
        if (!(panelRoot instanceof ViewGroup)) {
            return null;
        }
        ViewGroup vg = (ViewGroup) panelRoot;
        int rvTop = getRelativeTop(commentsRv, vg);
        int count = vg.getChildCount();
        for (int i = 0; i < count; i++) {
            View c = vg.getChildAt(i);
            if (c == null || c == commentsRv || isAncestorOf(c, commentsRv) || !c.isShown()) {
                continue;
            }
            String idName = getResourceEntryNameSafe(c);
            if (idName.contains("header") || (c.getBottom() <= rvTop + 16 && c.getHeight() > 0 && c.getHeight() < 260)) {
                return c;
            }
        }
        return null;
    }

    private static View findCommentComposerView(View node, View commentsRv, int depth) {
        if (node == null || depth > 7 || node == commentsRv) {
            return null;
        }
        String idName = getResourceEntryNameSafe(node);
        if (idName.contains("comment_composer")
                || idName.contains("add_comment")
                || idName.contains("create_comment")
                || idName.contains("composer")) {
            return node;
        }
        if (node instanceof TextView) {
            CharSequence txt = ((TextView) node).getText();
            CharSequence hint = ((TextView) node).getHint();
            if (isCommentComposerText(txt) || isCommentComposerText(hint)) {
                return findClickableAncestor(node, 4);
            }
        }
        CharSequence cd = node.getContentDescription();
        if (isCommentComposerText(cd)) {
            return findClickableAncestor(node, 3);
        }
        if (node instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) node;
            int count = vg.getChildCount();
            for (int i = 0; i < count; i++) {
                View found = findCommentComposerView(vg.getChildAt(i), commentsRv, depth + 1);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static boolean isCommentComposerText(CharSequence cs) {
        if (cs == null || cs.length() == 0) {
            return false;
        }
        String s = cs.toString().trim().toLowerCase(Locale.US);
        return s.contains("add a comment")
                || s.contains("add a public comment")
                || s.contains("comment as ")
                || s.equals("comment...");
    }

    private static boolean hasCommentsHeaderTitle(View node, int depth) {
        if (node == null || depth > 5 || !node.isShown()) {
            return false;
        }
        if (node instanceof TextView) {
            CharSequence txt = ((TextView) node).getText();
            if (txt != null) {
                String s = txt.toString().trim().toLowerCase(Locale.US);
                if (s.equals("comments") || s.startsWith("comments ")) {
                    return true;
                }
            }
        }
        if (node instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) node;
            int count = vg.getChildCount();
            for (int i = 0; i < count; i++) {
                if (hasCommentsHeaderTitle(vg.getChildAt(i), depth + 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasNonCommentsHeaderTitle(View node, int depth) {
        if (node == null || depth > 4 || !node.isShown()) {
            return false;
        }
        if (node instanceof TextView) {
            CharSequence txt = ((TextView) node).getText();
            if (txt != null) {
                String s = txt.toString().trim().toLowerCase(Locale.US);
                for (int i = 0; i < NON_COMMENTS_PANEL_TITLES.length; i++) {
                    if (s.equals(NON_COMMENTS_PANEL_TITLES[i])) {
                        return true;
                    }
                }
            }
        }
        if (node instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) node;
            int count = vg.getChildCount();
            for (int i = 0; i < count; i++) {
                if (hasNonCommentsHeaderTitle(vg.getChildAt(i), depth + 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasCommentResourceName(View node, int depth) {
        if (node == null || depth > 5) {
            return false;
        }
        if (getResourceEntryNameSafe(node).contains("comment")) {
            return true;
        }
        if (node instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) node;
            int count = vg.getChildCount();
            for (int i = 0; i < count; i++) {
                if (hasCommentResourceName(vg.getChildAt(i), depth + 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void collapseComposerVisually(View composer, WatchState state) {
        try {
            composer.setAlpha(0f);
            ViewGroup.LayoutParams lp = composer.getLayoutParams();
            if (lp != null && lp.height != 1) {
                state.savedComposerOrigHeight = lp.height;
                state.composerHeightSaved = true;
                lp.height = 1;
                composer.setLayoutParams(lp);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void restoreComposerVisually(View composer, WatchState state) {
        try {
            composer.setAlpha(1f);
            ViewGroup.LayoutParams lp = composer.getLayoutParams();
            if (state != null && state.composerHeightSaved && lp != null) {
                lp.height = state.savedComposerOrigHeight;
                composer.setLayoutParams(lp);
                state.composerHeightSaved = false;
            }
        } catch (Throwable ignored) {
        }
    }

    // -------------------------------------------------------------------------
    // Merged-row Comment button action -> triggers YouTube's comment composer + keyboard
    // -------------------------------------------------------------------------

    private static void onCommentButtonClicked(final Activity activity, final WatchState state) {
        View composer = state.dockedCommentComposerRef.get();
        View panelRoot = state.dockedPanelRootRef.get();
        View commentsRv = state.dockedCommentsRvRef.get();

        if (composer == null && panelRoot != null) {
            composer = findCommentComposerView(panelRoot, commentsRv, 0);
            if (composer != null) {
                state.dockedCommentComposerRef = new WeakReference<>(composer);
            }
        }
        // Also search inside the first items of commentsRv (some YouTube versions put "Add a comment..." as item 0).
        if (composer == null && commentsRv instanceof ViewGroup) {
            composer = findCommentComposerView(commentsRv, null, 0);
            if (composer != null) {
                state.dockedCommentComposerRef = new WeakReference<>(composer);
            }
        }

        if (composer != null && composer.isAttachedToWindow()) {
            clickViewSmart(composer);
            return;
        }

        // If comments panel hasn't finished opening yet, click the teaser first and trigger composer right after.
        View teaser = state.commentsTeaserRef.get();
        if (teaser != null && teaser.isAttachedToWindow()) {
            clickViewSmart(teaser);
            MAIN_HANDLER.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                        return;
                    }
                    View p = state.dockedPanelRootRef.get();
                    View rv = state.dockedCommentsRvRef.get();
                    View c = state.dockedCommentComposerRef.get();
                    if (c == null && p != null) {
                        c = findCommentComposerView(p, rv, 0);
                    }
                    if (c == null && rv != null) {
                        c = findCommentComposerView(rv, null, 0);
                    }
                    if (c != null) {
                        clickViewSmart(c);
                    }
                }
            }, 420L);
        }
    }

    private static void clickViewSmart(View target) {
        if (target == null) {
            return;
        }
        try {
            View clickable = findFirstClickableDescendant(target, 0);
            View toClick = clickable != null ? clickable : target;
            if (toClick.performClick()) {
                return;
            }
            if (target != toClick && target.performClick()) {
                return;
            }
            // Fallback: dispatch synthetic tap at view center for Litho touch listeners.
            long now = SystemClock.uptimeMillis();
            float cx = Math.max(1f, toClick.getWidth() * 0.5f);
            float cy = Math.max(1f, toClick.getHeight() * 0.5f);
            MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, cx, cy, 0);
            MotionEvent up = MotionEvent.obtain(now, now + 24L, MotionEvent.ACTION_UP, cx, cy, 0);
            toClick.dispatchTouchEvent(down);
            toClick.dispatchTouchEvent(up);
            down.recycle();
            up.recycle();
        } catch (Throwable ignored) {
        }
    }

    private static View findFirstClickableDescendant(View node, int depth) {
        if (node == null || depth > 5) {
            return null;
        }
        if (node.isClickable()) {
            return node;
        }
        if (node instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) node;
            int count = vg.getChildCount();
            for (int i = 0; i < count; i++) {
                View found = findFirstClickableDescendant(vg.getChildAt(i), depth + 1);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static View findClickableAncestor(View v, int maxSteps) {
        View cur = v;
        for (int i = 0; i < maxSteps && cur != null; i++) {
            if (cur.isClickable()) {
                return cur;
            }
            ViewParent p = cur.getParent();
            cur = p instanceof View ? (View) p : null;
        }
        return v;
    }

    // -------------------------------------------------------------------------
    // UnifiedWatchScrollHost: scrolls Video Title + Merged Single Row + Comments together
    // -------------------------------------------------------------------------

    private static final class UnifiedWatchScrollHost extends FrameLayout {

        private final LinearLayout headerContainer;
        private final FrameLayout titleSlot;
        private final HorizontalScrollView mergedRowScroll;
        private final LinearLayout mergedRowContent;
        private final View dividerLine;
        private final FrameLayout commentsBodyContainer;

        private final int touchSlop;
        private final int minFlingVel;
        private final int maxFlingVel;
        private final OverScroller scroller;
        private VelocityTracker velocityTracker;

        private int headerHeightPx = 0;
        private int headerScrollOffset = 0;
        private boolean draggingHeader = false;
        private float downX = 0f;
        private float downY = 0f;
        private float lastTouchY = 0f;

        private WeakReference<View> commentsRvRef = new WeakReference<>(null);
        private final ArrayList<CanvasProxySlot> proxySlots = new ArrayList<>(6);

        private final Runnable flingTickRunnable = new Runnable() {
            @Override
            public void run() {
                if (!scroller.computeScrollOffset()) {
                    return;
                }
                int currY = scroller.getCurrY();
                int dy = currY - lastFlingY;
                lastFlingY = currY;
                if (dy != 0) {
                    applyCombinedVerticalDelta(dy);
                }
                if (!scroller.isFinished()) {
                    postOnAnimation(this);
                }
            }
        };
        private int lastFlingY = 0;

        UnifiedWatchScrollHost(Context context) {
            super(context);
            setClipChildren(true);
            setClipToPadding(true);

            ViewConfiguration vc = ViewConfiguration.get(context);
            touchSlop = vc.getScaledTouchSlop();
            minFlingVel = vc.getScaledMinimumFlingVelocity();
            maxFlingVel = vc.getScaledMaximumFlingVelocity();
            scroller = new OverScroller(context);

            // Bottom comments body container (measured at full host height so scrolling header off leaves zero gap).
            commentsBodyContainer = new FrameLayout(context);
            addView(commentsBodyContainer, new LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));

            // Top header container holding Video Title + Merged Single Horizontally Scrollable Row.
            headerContainer = new LinearLayout(context);
            headerContainer.setOrientation(LinearLayout.VERTICAL);

            titleSlot = new FrameLayout(context);
            headerContainer.addView(titleSlot, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));

            mergedRowScroll = new HorizontalScrollView(context);
            mergedRowScroll.setHorizontalScrollBarEnabled(false);
            mergedRowScroll.setOverScrollMode(OVER_SCROLL_NEVER);
            mergedRowScroll.setFillViewport(false);

            mergedRowContent = new LinearLayout(context);
            mergedRowContent.setOrientation(LinearLayout.HORIZONTAL);
            mergedRowContent.setGravity(Gravity.CENTER_VERTICAL);
            int padH = dpToPx(getResources(), 10);
            int padV = dpToPx(getResources(), 4);
            mergedRowContent.setPadding(padH, padV, padH, padV);
            mergedRowScroll.addView(mergedRowContent, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));

            headerContainer.addView(mergedRowScroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));

            dividerLine = new View(context);
            dividerLine.setBackgroundColor(isDarkTheme(context) ? 0x22FFFFFF : 0x18000000);
            LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Math.max(1, dpToPx(getResources(), 1))
            );
            divLp.topMargin = dpToPx(getResources(), 4);
            headerContainer.addView(dividerLine, divLp);

            addView(headerContainer, new LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));
        }

        boolean hasHeaderContent() {
            return titleSlot.getChildCount() > 0 && mergedRowContent.getChildCount() > 0;
        }

        void resetScrollPosition() {
            scroller.abortAnimation();
            headerScrollOffset = 0;
            mergedRowScroll.scrollTo(0, 0);
            applyScrollTranslations();
        }

        void refreshHeaderVisuals() {
            for (int i = 0; i < proxySlots.size(); i++) {
                proxySlots.get(i).invalidate();
            }
        }

        void bindHeaderItems(
                Context ctx,
                View titleItem,
                View channelItem,
                View actionsItem,
                View commentsTeaserItem,
                Runnable onCommentClick
        ) {
            proxySlots.clear();
            titleSlot.removeAllViews();
            mergedRowContent.removeAllViews();

            Resources res = ctx.getResources();
            int gapPx = dpToPx(res, 8);
            int rowH = Math.max(dpToPx(res, 44), Math.max(channelItem.getHeight(), actionsItem.getHeight()));

            // 1. Video title + views/date/...more proxy slice (full width, exact live height).
            int titleW = Math.max(1, titleItem.getWidth());
            int titleH = Math.max(dpToPx(res, 42), titleItem.getHeight());
            CanvasProxySlot titleProxy = new CanvasProxySlot(ctx, titleItem, 0, 0, titleW, titleH);
            proxySlots.add(titleProxy);
            titleSlot.addView(titleProxy, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    titleH
            ));

            // 2. Channel row sliced into compact Left (avatar + name + subs) and Right (Subscribe/Bell) clusters.
            int chW = Math.max(1, channelItem.getWidth());
            int chH = Math.max(dpToPx(res, 40), channelItem.getHeight());
            int[] chSlices = detectChannelHorizontalSlices(channelItem, res);
            if (chSlices != null) {
                int lStart = chSlices[0];
                int lEnd = chSlices[1];
                int rStart = chSlices[2];
                int rEnd = chSlices[3];

                CanvasProxySlot chLeft = new CanvasProxySlot(ctx, channelItem, lStart, 0, lEnd, chH);
                proxySlots.add(chLeft);
                addMergedChild(chLeft, lEnd - lStart, chH, 0, gapPx);

                CanvasProxySlot chRight = new CanvasProxySlot(ctx, channelItem, rStart, 0, rEnd, chH);
                proxySlots.add(chRight);
                addMergedChild(chRight, rEnd - rStart, chH, 0, gapPx);
            } else {
                CanvasProxySlot chFull = new CanvasProxySlot(ctx, channelItem, 0, 0, chW, chH);
                proxySlots.add(chFull);
                addMergedChild(chFull, chW, chH, 0, gapPx);
            }

            // 3. Action buttons row split right after the Like/Dislike pill so Comment sits alongside Like/Dislike & Share.
            int actW = Math.max(1, actionsItem.getWidth());
            int actH = Math.max(dpToPx(res, 40), actionsItem.getHeight());
            int[] actBounds = detectActionButtonsSplit(actionsItem, res);
            int firstStart = actBounds[0];
            int firstEnd = actBounds[1];
            int restStart = actBounds[2];
            int restEnd = actBounds[3];

            if (firstEnd > firstStart + dpToPx(res, 24)) {
                CanvasProxySlot likeDislikeSlot = new CanvasProxySlot(ctx, actionsItem, firstStart, 0, firstEnd, actH);
                proxySlots.add(likeDislikeSlot);
                addMergedChild(likeDislikeSlot, firstEnd - firstStart, actH, 0, gapPx);
            }

            // 4. Native-styled Comment pill button (triggers YouTube comment composer + keyboard).
            CommentPillButton commentBtn = new CommentPillButton(ctx, onCommentClick);
            LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dpToPx(res, 36)
            );
            btnLp.gravity = Gravity.CENTER_VERTICAL;
            btnLp.rightMargin = gapPx;
            mergedRowContent.addView(commentBtn, btnLp);

            if (restEnd > restStart + dpToPx(res, 24)) {
                CanvasProxySlot restActionsSlot = new CanvasProxySlot(ctx, actionsItem, restStart, 0, restEnd, actH);
                proxySlots.add(restActionsSlot);
                addMergedChild(restActionsSlot, restEnd - restStart, actH, 0, dpToPx(res, 6));
            } else if (firstEnd <= firstStart + dpToPx(res, 24)) {
                CanvasProxySlot fullActionsSlot = new CanvasProxySlot(ctx, actionsItem, 0, 0, actW, actH);
                proxySlots.add(fullActionsSlot);
                addMergedChild(fullActionsSlot, actW, actH, 0, dpToPx(res, 6));
            }

            // If comments body isn't docked yet, show the comments teaser proxy cleanly while auto-opening.
            if (commentsBodyContainer.getChildCount() == 0 && commentsTeaserItem != null) {
                int ctW = Math.max(1, commentsTeaserItem.getWidth());
                int ctH = Math.max(dpToPx(res, 64), commentsTeaserItem.getHeight());
                CanvasProxySlot teaserProxy = new CanvasProxySlot(ctx, commentsTeaserItem, 0, 0, ctW, ctH);
                proxySlots.add(teaserProxy);
                commentsBodyContainer.addView(teaserProxy, new LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ctH
                ));
            }

            requestLayout();
        }

        private void addMergedChild(View child, int widthPx, int heightPx, int leftMargin, int rightMargin) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Math.max(1, widthPx), Math.max(1, heightPx));
            lp.gravity = Gravity.CENTER_VERTICAL;
            lp.leftMargin = leftMargin;
            lp.rightMargin = rightMargin;
            mergedRowContent.addView(child, lp);
        }

        void setCommentsBodyView(View panelRoot, View commentsRv) {
            commentsRvRef = new WeakReference<>(commentsRv);
            if (panelRoot == null) {
                return;
            }
            if (panelRoot.getParent() == commentsBodyContainer) {
                return;
            }
            commentsBodyContainer.removeAllViews();
            if (panelRoot.getParent() instanceof ViewGroup) {
                ((ViewGroup) panelRoot.getParent()).removeView(panelRoot);
            }
            panelRoot.setVisibility(View.VISIBLE);
            panelRoot.setAlpha(1f);
            commentsBodyContainer.addView(panelRoot, new LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));
            requestLayout();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int w = MeasureSpec.getSize(widthMeasureSpec);
            int h = MeasureSpec.getSize(heightMeasureSpec);

            headerContainer.measure(
                    MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(h, MeasureSpec.AT_MOST)
            );
            headerHeightPx = headerContainer.getMeasuredHeight();
            if (headerScrollOffset > headerHeightPx) {
                headerScrollOffset = headerHeightPx;
            }

            // Measure comments body at full host height so when header scrolls away, comments fill 100% of space.
            commentsBodyContainer.measure(
                    MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(Math.max(1, h), MeasureSpec.EXACTLY)
            );
            setMeasuredDimension(w, h);
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            int w = right - left;
            int h = bottom - top;
            headerHeightPx = headerContainer.getMeasuredHeight();
            headerContainer.layout(0, 0, w, headerHeightPx);
            commentsBodyContainer.layout(0, headerHeightPx, w, headerHeightPx + h);
            applyScrollTranslations();
        }

        private void applyScrollTranslations() {
            int clamped = Math.max(0, Math.min(headerHeightPx, headerScrollOffset));
            headerScrollOffset = clamped;
            headerContainer.setTranslationY(-clamped);
            commentsBodyContainer.setTranslationY(-clamped);
        }

        private void applyCombinedVerticalDelta(int dy) {
            if (dy > 0) {
                int canConsume = Math.max(0, headerHeightPx - headerScrollOffset);
                int headerDelta = Math.min(dy, canConsume);
                if (headerDelta > 0) {
                    headerScrollOffset += headerDelta;
                    applyScrollTranslations();
                }
                int rem = dy - headerDelta;
                if (rem > 0) {
                    View rv = commentsRvRef.get();
                    if (rv != null) {
                        rv.scrollBy(0, rem);
                    }
                }
            } else if (dy < 0) {
                View rv = commentsRvRef.get();
                if (rv != null && rv.canScrollVertically(-1)) {
                    rv.scrollBy(0, dy);
                } else if (headerScrollOffset > 0) {
                    int headerDelta = Math.max(dy, -headerScrollOffset);
                    headerScrollOffset += headerDelta;
                    applyScrollTranslations();
                }
            }
        }

        // --- Nested scrolling coordination with YouTube's Comments RecyclerView ---

        @Override
        public boolean onStartNestedScroll(View child, View target, int nestedScrollAxes) {
            if ((nestedScrollAxes & View.SCROLL_AXIS_VERTICAL) != 0) {
                scroller.abortAnimation();
                return true;
            }
            return false;
        }

        @Override
        public void onNestedPreScroll(View target, int dx, int dy, int[] consumed) {
            if (dy > 0 && headerScrollOffset < headerHeightPx) {
                int take = Math.min(dy, headerHeightPx - headerScrollOffset);
                headerScrollOffset += take;
                applyScrollTranslations();
                consumed[1] = take;
            } else if (dy < 0 && headerScrollOffset > 0 && !target.canScrollVertically(-1)) {
                int take = Math.max(dy, -headerScrollOffset);
                headerScrollOffset += take;
                applyScrollTranslations();
                consumed[1] = take;
            }
        }

        @Override
        public void onNestedScroll(View target, int dxConsumed, int dyConsumed, int dxUnconsumed, int dyUnconsumed) {
            if (dyUnconsumed < 0 && headerScrollOffset > 0) {
                int take = Math.max(dyUnconsumed, -headerScrollOffset);
                headerScrollOffset += take;
                applyScrollTranslations();
            } else if (dyUnconsumed > 0 && headerScrollOffset < headerHeightPx) {
                int take = Math.min(dyUnconsumed, headerHeightPx - headerScrollOffset);
                headerScrollOffset += take;
                applyScrollTranslations();
            }
        }

        @Override
        public boolean onNestedPreFling(View target, float velocityX, float velocityY) {
            if (velocityY > minFlingVel && headerScrollOffset < headerHeightPx) {
                startHeaderFling((int) velocityY);
                return false;
            }
            if (velocityY < -minFlingVel && headerScrollOffset > 0 && !target.canScrollVertically(-1)) {
                startHeaderFling((int) velocityY);
                return true;
            }
            return false;
        }

        private void startHeaderFling(int velocityY) {
            scroller.abortAnimation();
            lastFlingY = headerScrollOffset;
            scroller.fling(0, headerScrollOffset, 0, velocityY, 0, 0, 0, Math.max(0, headerHeightPx));
            postOnAnimation(flingTickRunnable);
        }

        // --- Direct vertical drag on Video Title / Merged Row scrolls the whole column ---

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            int action = ev.getActionMasked();
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    scroller.abortAnimation();
                    downX = ev.getX();
                    downY = ev.getY();
                    lastTouchY = downY;
                    draggingHeader = false;
                    if (velocityTracker != null) {
                        velocityTracker.recycle();
                    }
                    velocityTracker = VelocityTracker.obtain();
                    velocityTracker.addMovement(ev);
                    return false;

                case MotionEvent.ACTION_MOVE:
                    if (velocityTracker != null) {
                        velocityTracker.addMovement(ev);
                    }
                    // Only intercept drags originating inside the header area (comments RecyclerView handles itself via nested scroll).
                    float visibleHeaderBottom = headerHeightPx - headerScrollOffset;
                    if (downY <= visibleHeaderBottom) {
                        float dx = Math.abs(ev.getX() - downX);
                        float dy = Math.abs(ev.getY() - downY);
                        if (dy > touchSlop && dy > dx * 1.15f) {
                            draggingHeader = true;
                            lastTouchY = ev.getY();
                            return true;
                        }
                    }
                    return false;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    draggingHeader = false;
                    if (velocityTracker != null) {
                        velocityTracker.recycle();
                        velocityTracker = null;
                    }
                    return false;
            }
            return false;
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            if (!draggingHeader) {
                return super.onTouchEvent(ev);
            }
            if (velocityTracker != null) {
                velocityTracker.addMovement(ev);
            }
            int action = ev.getActionMasked();
            switch (action) {
                case MotionEvent.ACTION_MOVE:
                    float y = ev.getY();
                    int dy = Math.round(lastTouchY - y);
                    lastTouchY = y;
                    if (dy != 0) {
                        applyCombinedVerticalDelta(dy);
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                    if (velocityTracker != null) {
                        velocityTracker.computeCurrentVelocity(1000, maxFlingVel);
                        int vy = (int) -velocityTracker.getYVelocity();
                        if (Math.abs(vy) >= minFlingVel) {
                            startHeaderFling(vy);
                        }
                        velocityTracker.recycle();
                        velocityTracker = null;
                    }
                    draggingHeader = false;
                    return true;

                case MotionEvent.ACTION_CANCEL:
                    if (velocityTracker != null) {
                        velocityTracker.recycle();
                        velocityTracker = null;
                    }
                    draggingHeader = false;
                    return true;
            }
            return true;
        }
    }

    // -------------------------------------------------------------------------
    // Pixel-perfect non-destructive View slice proxy + touch forwarder
    // -------------------------------------------------------------------------

    private static final class CanvasProxySlot extends View {
        private final WeakReference<View> sourceRef;
        private final int srcLeft;
        private final int srcTop;
        private final int srcRight;
        private final int srcBottom;

        CanvasProxySlot(Context context, View source, int srcLeft, int srcTop, int srcRight, int srcBottom) {
            super(context);
            this.sourceRef = new WeakReference<>(source);
            this.srcLeft = Math.max(0, srcLeft);
            this.srcTop = Math.max(0, srcTop);
            this.srcRight = Math.max(this.srcLeft + 1, srcRight);
            this.srcBottom = Math.max(this.srcTop + 1, srcBottom);
            setClickable(true);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            View src = sourceRef.get();
            if (src == null || !src.isAttachedToWindow()) {
                return;
            }
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            int save = canvas.save();
            canvas.clipRect(0, 0, w, h);
            canvas.translate(-srcLeft, -srcTop);
            try {
                src.draw(canvas);
            } catch (Throwable ignored) {
            }
            canvas.restoreToCount(save);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            View src = sourceRef.get();
            if (src == null || !src.isAttachedToWindow()) {
                return false;
            }
            MotionEvent copy = MotionEvent.obtain(event);
            copy.offsetLocation(srcLeft, srcTop);
            boolean handled = false;
            try {
                handled = src.dispatchTouchEvent(copy);
            } catch (Throwable ignored) {
            } finally {
                copy.recycle();
            }

            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                // Refresh live state (like count, subscribe/bell toggle, download progress) shortly after tap.
                postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        invalidate();
                    }
                }, 120L);
                postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        invalidate();
                    }
                }, 420L);
            }
            return handled || action == MotionEvent.ACTION_DOWN;
        }
    }

    // -------------------------------------------------------------------------
    // Native-styled "Comment" pill button (matching YouTube's dark/light action pills)
    // -------------------------------------------------------------------------

    private static final class CommentPillButton extends LinearLayout {
        CommentPillButton(Context context, final Runnable onClickAction) {
            super(context);
            Resources res = context.getResources();
            boolean dark = isDarkTheme(context);

            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setClickable(true);
            setFocusable(true);
            setPadding(dpToPx(res, 12), 0, dpToPx(res, 14), 0);

            int bgNormal = dark ? 0xFF272727 : 0xFFF2F2F2;
            int bgPressed = dark ? 0xFF3F3F3F : 0xFFE2E2E2;
            int fgColor = dark ? 0xFFF1F1F1 : 0xFF0F0F0F;
            float radius = dpToPx(res, 18);

            GradientDrawable normalBg = new GradientDrawable();
            normalBg.setColor(bgNormal);
            normalBg.setCornerRadius(radius);

            GradientDrawable pressedBg = new GradientDrawable();
            pressedBg.setColor(bgPressed);
            pressedBg.setCornerRadius(radius);

            StateListDrawable bg = new StateListDrawable();
            bg.addState(new int[]{android.R.attr.state_pressed}, pressedBg);
            bg.addState(new int[]{}, normalBg);
            setBackground(bg);

            CommentBubbleIcon icon = new CommentBubbleIcon(context, fgColor);
            int iconSize = dpToPx(res, 18);
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(iconSize, iconSize);
            iconLp.rightMargin = dpToPx(res, 6);
            addView(icon, iconLp);

            TextView label = new TextView(context);
            label.setText("Comment");
            label.setTextColor(fgColor);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            label.setSingleLine(true);
            addView(label);

            setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (onClickAction != null) {
                        onClickAction.run();
                    }
                }
            });
        }
    }

    private static final class CommentBubbleIcon extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        CommentBubbleIcon(Context context, int color) {
            super(context);
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            float s = Math.min(w, h) / 24f;
            float ox = (w - 24f * s) * 0.5f;
            float oy = (h - 24f * s) * 0.5f;
            canvas.save();
            canvas.translate(ox, oy);

            paint.setStrokeWidth(1.7f * s);
            path.reset();
            // Rounded speech bubble outline with bottom-left tail.
            path.moveTo(4f * s, 5.5f * s);
            path.quadTo(4f * s, 4f * s, 5.5f * s, 4f * s);
            path.lineTo(18.5f * s, 4f * s);
            path.quadTo(20f * s, 4f * s, 20f * s, 5.5f * s);
            path.lineTo(20f * s, 15.5f * s);
            path.quadTo(20f * s, 17f * s, 18.5f * s, 17f * s);
            path.lineTo(9.2f * s, 17f * s);
            path.lineTo(5.2f * s, 20.5f * s);
            path.lineTo(5.2f * s, 17f * s);
            path.lineTo(5.5f * s, 17f * s);
            path.quadTo(4f * s, 17f * s, 4f * s, 15.5f * s);
            path.close();
            canvas.drawPath(path, paint);

            // Two crisp comment lines inside bubble.
            canvas.drawLine(8f * s, 9f * s, 16f * s, 9f * s, paint);
            canvas.drawLine(8f * s, 12.5f * s, 13.5f * s, 12.5f * s, paint);

            canvas.restore();
        }
    }

    // -------------------------------------------------------------------------
    // Geometry slicers: compact Channel row (eliminating empty middle gap) & split Action pills
    // -------------------------------------------------------------------------

    /**
     * Finds the exact empty horizontal gap between the left channel cluster (avatar + name + subs)
     * and the right Subscribe/Unsubscribe/Bell button using a fast 1/4-scale single-shot bitmap scan.
     * Returns [leftStart, leftEnd, rightStart, rightEnd] or null if no large gap exists.
     */
    private static int[] detectChannelHorizontalSlices(View channelItem, Resources res) {
        int w = channelItem.getWidth();
        int h = channelItem.getHeight();
        if (w < dpToPx(res, 260) || h < dpToPx(res, 24)) {
            return null;
        }
        int scale = 4;
        int bw = Math.max(16, w / scale);
        int bh = Math.max(8, h / scale);
        Bitmap bmp = null;
        try {
            bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            c.scale(1f / scale, 1f / scale);
            channelItem.draw(c);

            int bg = bmp.getPixel(bw / 2, 1);
            boolean[] colHasContent = new boolean[bw];
            for (int x = 0; x < bw; x++) {
                boolean active = false;
                for (int y = 1; y < bh - 1; y++) {
                    if (colorDistance(bmp.getPixel(x, y), bg) > 22) {
                        active = true;
                        break;
                    }
                }
                colHasContent[x] = active;
            }

            // Find the widest empty run inside [18% .. 92%] of width.
            int minX = (int) (bw * 0.18f);
            int maxX = (int) (bw * 0.92f);
            int bestGapStart = -1;
            int bestGapEnd = -1;
            int runStart = -1;

            for (int x = minX; x < maxX; x++) {
                if (!colHasContent[x]) {
                    if (runStart < 0) {
                        runStart = x;
                    }
                } else {
                    if (runStart >= 0) {
                        if ((x - runStart) > (bestGapEnd - bestGapStart)) {
                            bestGapStart = runStart;
                            bestGapEnd = x;
                        }
                        runStart = -1;
                    }
                }
            }
            if (runStart >= 0 && (maxX - runStart) > (bestGapEnd - bestGapStart)) {
                bestGapStart = runStart;
                bestGapEnd = maxX;
            }

            int gapPx = (bestGapEnd - bestGapStart) * scale;
            if (bestGapStart < 0 || gapPx < dpToPx(res, 24)) {
                return null;
            }

            // Trim leading/trailing empty margins cleanly.
            int firstCol = 0;
            while (firstCol < bestGapStart && !colHasContent[firstCol]) {
                firstCol++;
            }
            int lastCol = bw - 1;
            while (lastCol > bestGapEnd && !colHasContent[lastCol]) {
                lastCol--;
            }

            int pad = dpToPx(res, 4);
            int leftStart = Math.max(0, firstCol * scale - pad);
            int leftEnd = Math.min(w, bestGapStart * scale + pad);
            int rightStart = Math.max(leftEnd, bestGapEnd * scale - pad);
            int rightEnd = Math.min(w, (lastCol + 1) * scale + pad);

            if (leftEnd - leftStart < dpToPx(res, 48) || rightEnd - rightStart < dpToPx(res, 32)) {
                return null;
            }
            return new int[]{leftStart, leftEnd, rightStart, rightEnd};
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (bmp != null && !bmp.isRecycled()) {
                bmp.recycle();
            }
        }
    }

    /**
     * Splits the action buttons bar right after the first pill (Like/Dislike) so our Comment button
     * sits directly alongside Like/Dislike and Share, while trimming trailing empty whitespace.
     * Returns [firstStart, firstEnd, restStart, restEnd].
     */
    private static int[] detectActionButtonsSplit(View actionsItem, Resources res) {
        int w = actionsItem.getWidth();
        int pad = dpToPx(res, 2);
        ViewGroup scroller = findHorizontalScrollerInside(actionsItem, 0);
        if (scroller != null) {
            ViewGroup pillHost = scroller;
            if (scroller instanceof HorizontalScrollView && scroller.getChildCount() == 1
                    && scroller.getChildAt(0) instanceof ViewGroup) {
                pillHost = (ViewGroup) scroller.getChildAt(0);
            }
            int offsetX = getRelativeLeft(pillHost, actionsItem);
            ArrayList<Rect> pills = new ArrayList<>();
            int count = pillHost.getChildCount();
            for (int i = 0; i < count; i++) {
                View child = pillHost.getChildAt(i);
                if (child != null && child.getVisibility() == View.VISIBLE
                        && child.getWidth() > dpToPx(res, 24) && child.getHeight() > dpToPx(res, 20)) {
                    int l = Math.max(0, offsetX + child.getLeft());
                    int r = Math.min(w, offsetX + child.getRight());
                    if (r > l) {
                        pills.add(new Rect(l, 0, r, child.getHeight()));
                    }
                }
            }
            if (!pills.isEmpty()) {
                Collections.sort(pills, new Comparator<Rect>() {
                    @Override
                    public int compare(Rect a, Rect b) {
                        return Integer.compare(a.left, b.left);
                    }
                });
                int firstStart = Math.max(0, pills.get(0).left - pad);
                int firstEnd = Math.min(w, pills.get(0).right + pad);
                int restStart = pills.size() >= 2 ? Math.max(firstEnd, pills.get(1).left - pad) : firstEnd;
                int restEnd = Math.min(w, pills.get(pills.size() - 1).right + pad);
                return new int[]{firstStart, firstEnd, restStart, restEnd};
            }
        }
        return new int[]{0, 0, 0, w};
    }

    private static int colorDistance(int c1, int c2) {
        int dr = Math.abs(Color.red(c1) - Color.red(c2));
        int dg = Math.abs(Color.green(c1) - Color.green(c2));
        int db = Math.abs(Color.blue(c1) - Color.blue(c2));
        return dr + dg + db;
    }

    // -------------------------------------------------------------------------
    // Shared View & Theme utilities
    // -------------------------------------------------------------------------

    private static int resolveWatchSurfaceColor(Activity activity, View reference) {
        View cur = reference;
        for (int i = 0; i < 6 && cur != null; i++) {
            Drawable bg = cur.getBackground();
            if (bg instanceof ColorDrawable) {
                int c = ((ColorDrawable) bg).getColor();
                if (Color.alpha(c) > 220) {
                    return c;
                }
            }
            ViewParent p = cur.getParent();
            cur = p instanceof View ? (View) p : null;
        }
        return isDarkTheme(activity) ? 0xFF0F0F0F : 0xFFFFFFFF;
    }

    private static boolean isDarkTheme(Context context) {
        if (context == null || context.getResources() == null) {
            return true;
        }
        int mode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    private static boolean isRecyclerView(View v) {
        return v != null && v.getClass().getName().contains("RecyclerView");
    }

    private static boolean isInsideOurHost(View v) {
        ViewParent p = v.getParent();
        while (p instanceof View) {
            if (HOST_TAG.equals(((View) p).getTag())) {
                return true;
            }
            p = p.getParent();
        }
        return false;
    }

    private static boolean isDescendantOf(View child, View potentialAncestor) {
        if (child == null || potentialAncestor == null) {
            return false;
        }
        ViewParent p = child.getParent();
        while (p instanceof View) {
            if (p == potentialAncestor) {
                return true;
            }
            p = p.getParent();
        }
        return false;
    }

    private static boolean isAncestorOf(View potentialAncestor, View child) {
        return potentialAncestor == child || isDescendantOf(child, potentialAncestor);
    }

    private static int getRelativeLeft(View child, View ancestor) {
        int left = 0;
        View cur = child;
        while (cur != null && cur != ancestor) {
            left += cur.getLeft();
            ViewParent p = cur.getParent();
            cur = p instanceof View ? (View) p : null;
        }
        return left;
    }

    private static int getRelativeTop(View child, View ancestor) {
        int top = 0;
        View cur = child;
        while (cur != null && cur != ancestor) {
            top += cur.getTop();
            ViewParent p = cur.getParent();
            cur = p instanceof View ? (View) p : null;
        }
        return top;
    }

    private static String getResourceEntryNameSafe(View v) {
        if (v == null) {
            return "";
        }
        int id = v.getId();
        if (id == View.NO_ID || id == 0) {
            return "";
        }
        try {
            Resources res = v.getResources();
            if (res != null) {
                String name = res.getResourceEntryName(id);
                return name != null ? name.toLowerCase(Locale.US) : "";
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static int dpToPx(Resources res, int dp) {
        if (res == null) {
            return dp * 2;
        }
        return Math.round(dp * res.getDisplayMetrics().density);
    }
}
