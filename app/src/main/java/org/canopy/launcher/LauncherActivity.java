/*
 * Copyright 2026 The Canopy Launcher authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.canopy.launcher;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.Settings;
import android.accessibilityservice.AccessibilityService;
import android.view.WindowManager;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.TextView;


import java.io.File;
import java.lang.reflect.Method;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The home screen. */
public class LauncherActivity extends Activity implements AppRepository.Listener, IconView.Host,
        LauncherWidgetView.Host, Workspace.Callbacks, DropTargetBar.Callback, Badges.Listener,
        SharedPreferences.OnSharedPreferenceChangeListener {

    private static final int REQ_BIND_WIDGET = 11, REQ_CONFIG_WIDGET = 12, REQ_CALENDAR = 15, REQ_ICON_IMAGE = 16, REQ_VOICE = 18, REQ_SCAN_FILE = 19;
    private static final int WIDGET_HOST_ID = 0x4C41;

    /** Travels with a drag as its local state. */
    public static class DragInfo {
        public HomeItem item;     // existing home item (null when dragging from the drawer)
        public AppInfo app;       // app dragged from the drawer / glance
        public HomeItem folder;   // folder the item is being dragged out of
        public AppWidgetProviderInfo widgetInfo; // a new widget pulled from the widget page or an app's menu
        public View source;
    }

    private FrameLayout root;
    private Workspace workspace;
    private DockView dock;
    private TextView searchPill;
    private PageIndicator indicator;
    private DrawerView drawer;
    private DropTargetBar dropBar;
    private FeedPage feed;
    private FolderView folderView;
    /** The open long-press menu: a layer inside the launcher window (so drags can start from it). */
    private FrameLayout popup;

    private AppRepository repo;
    private HomeStore store;
    private LauncherWidgetView.WidgetHost widgetHost;
    private AppWidgetManager awm;
    private final Map<Integer, LauncherWidgetView> widgetViews = new HashMap<>();
    private final Map<String, Drawable> shortcutIcons = new HashMap<>();

    private int insetTop, insetBottom, insetLeft, insetRight;
    private boolean dragging, resumed, needsRecreate, firstBind = true;
    private int restorePage = -1;

    @Override protected void onSaveInstanceState(android.os.Bundle out) {
        super.onSaveInstanceState(out);
        if (workspace != null) out.putInt("canopy:page", workspace.getCurrentPage());
    }
    private DragInfo currentDrag;
    private int boundVersion = -1;
    private AppWidgetProviderInfo pendingWidget;
    private int pendingWidgetId = -1, pendingPage;
    private int edgeDir;

    private final Runnable bindRunnable = new Runnable() {
        @Override public void run() { bindAll(); }
    };
    private final Runnable edgeRunnable = new Runnable() {
        @Override public void run() {
            if (!dragging || edgeDir == 0) return;
            int target = workspace.getCurrentPage() + edgeDir;
            if (target >= glanceOffset() && target < workspace.getChildCount()) workspace.snapToPage(target);
            root.postDelayed(this, 700);
        }
    };

    // ------------------------------------------------------------------ lifecycle

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(null); Ui.keepBack(this);
        if (savedInstanceState != null) restorePage = savedInstanceState.getInt("canopy:page", -1);
        nightAtCreate = Ui.night(this);
        KeepAliveService.sync(this); // (a background start of the service is refused by Android; this one is allowed)
        loadFeedBg();
        Ui.edgeToEdge(getWindow());
        // draw under the camera cut-out / status bar area at all times (with the status bar hidden, Android otherwise leaves a bare strip of wallpaper at the top)
        if (android.os.Build.VERSION.SDK_INT >= 30) getWindow().getAttributes().layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        else if (android.os.Build.VERSION.SDK_INT >= 28) getWindow().getAttributes().layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        if (!Prefs.sp(this).getBoolean("allow_rotation", false)) {
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        }
        repo = AppRepository.get(this);
        store = HomeStore.get(this);
        awm = AppWidgetManager.getInstance(this);
        widgetHost = new LauncherWidgetView.WidgetHost(this, WIDGET_HOST_ID);
        sInstance = new java.lang.ref.WeakReference<>(this);
        adoptWebViews();
        // after an install/update or a restart, open the current web tab right away so it's already fresh
        if (FeedWeb.freshStart(this)) {
            final String cur = FeedWeb.prefs(this).getString("current", "feed");
            if (cur != null && FeedWeb.tabs(this).contains(cur) && !webViews.containsKey(cur)) {
                App.MAIN.postDelayed(new Runnable() { @Override public void run() { if (!isDestroyed()) feedWebView(cur); } }, 1500);
            }
        }
        // Text drawn on the wallpaper follows it: black on light wallpapers, white on dark ones.
        if (android.os.Build.VERSION.SDK_INT >= 27 && wallListener == null) {
            wallListener = new android.app.WallpaperManager.OnColorsChangedListener() {
                @Override public void onColorsChanged(android.app.WallpaperColors colors, int which) {
                    if ((which & android.app.WallpaperManager.FLAG_SYSTEM) == 0) return;
                    boolean before = Ui.wallpaperIsLight(LauncherActivity.this);
                    Ui.resetWallpaper();
                    if (Ui.wallpaperIsLight(LauncherActivity.this) == before) return;
                    if (resumed) recreate(); else needsRecreate = true;
                }
            };
            try { android.app.WallpaperManager.getInstance(this).addOnColorsChangedListener(wallListener, App.MAIN); } catch (Throwable ignored) { }
        }
        buildUi();
        repo.addListener(this);
        Prefs.sp(this).registerOnSharedPreferenceChangeListener(this);
        Badges.addListener(this);
        store.overlapWidgets = Prefs.widgetOverlap(this);
        if (repo.isLoaded()) onAppsChanged(); else repo.reload();
    }

    private boolean stopped = true, returningHome, feedArrived;
    /** True from onResume until onStop: the home screen was on screen. (onStart runs before onNewIntent, so
     *  "not stopped" can't tell a Home press on the home screen from coming back from another app.) */
    private boolean shown;

    private static java.lang.ref.WeakReference<LauncherActivity> sInstance;
    private android.app.WallpaperManager.OnColorsChangedListener wallListener;
    static LauncherActivity instance() { return sInstance == null ? null : sInstance.get(); }

    @Override protected void onStart() {
        super.onStart();
        stopped = false;
        iconsDark = null; // reapplied below: the system may have reset it while another app was on top
        // (the status bar is set once, in onResume)
        // The slow parts of coming back (every widget's update, every web tab) run just after the first frame,
        // so the home screen appears at once when you leave an app.
        // …and in two steps, ~1 s later: the widgets' updates (every one inflates views on the main thread) and the web tabs
        // used to land exactly when you swiped up to the drawer, which was the stutter after leaving an app.
        root.postDelayed(new Runnable() {
            @Override public void run() {
                if (stopped) return;
                if (overlayAnimating) { root.postDelayed(this, 400); return; } // wait for a drawer / folder slide to finish
                try { widgetHost.startListening(); } catch (Throwable ignored) { }
                root.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (stopped) return;
                        try { wakeWeb(); webForFeed = true; syncWebToFeed(); } catch (Throwable ignored) { }
                    }
                }, 500);
            }
        }, 1000);
    }

    // ------------------------------------------------------------------ battery: freezing web tabs while away

    private boolean webAsleep;
    private final Runnable sleepWebRunnable = new Runnable() { @Override public void run() { sleepWeb(); } };

    /** Freezes the websites' scripts (they stay loaded — nothing is closed or reloaded). Playing media is spared. */
    private void sleepWeb() {
        root.removeCallbacks(sleepWebRunnable);
        if (shown || !stopped) return;
        boolean keep = FeedWeb.background(this), spared = false;
        android.webkit.WebView any = null;
        for (java.util.Map.Entry<String, android.webkit.WebView> e : webViews.entrySet()) {
            any = e.getValue();
            if ((keep && isPlaying(e.getKey())) || e.getKey().equals(pipTab)) { spared = true; continue; }
            e.getValue().onPause();
        }
        // JavaScript timers are shared by every page: only stop them when nothing is playing
        if (any != null && !spared) { any.pauseTimers(); webAsleep = true; }
    }

    private void wakeWeb() {
        root.removeCallbacks(sleepWebRunnable);
        android.webkit.WebView any = null;
        for (android.webkit.WebView wv : webViews.values()) { wv.onResume(); any = wv; }
        if (any != null) any.resumeTimers();
        webAsleep = false;
    }

    /** Schedules freezing the web tabs, as chosen in Settings > Feed browser. */
    private void scheduleWebSleep() {
        root.removeCallbacks(sleepWebRunnable);
        if (webViews.isEmpty()) return;
        String m = FeedWeb.bgMode(this);
        if ("always".equals(m)) return;
        if ("now".equals(m)) sleepWeb();
        else root.postDelayed(sleepWebRunnable, FeedWeb.BG_GRACE);
    }

    @Override protected void onStop() {
        super.onStop();
        stopped = true;
        shown = false;
        try { widgetHost.stopListening(); } catch (Exception ignored) { }
        // Web tabs stay loaded while you're in other apps; after a short while (Settings) their scripts are
        // frozen to save battery — except one playing video/audio, or the pop-up player.
        scheduleWebSleep();
        android.webkit.CookieManager.getInstance().flush();
    }

    private boolean isPlaying(String id) { return WebMedia.isPlaying(id); }

    // ------------------------------------------------------------------ web tab media

    /** A web tab started or stopped playing. */
    public void onWebMediaChanged() {
        // player notification: while something is playing or paused in a tab (like a music app)
        if (FeedWeb.background(this) && WebMedia.current() != null) MediaService.show(this);
        else MediaService.stop(this);
        if (stopped && !FeedWeb.anyPlaying() && pipTab == null) scheduleWebSleep();
        if (FeedWeb.anyPlaying()) {
            if (shown && android.os.Build.VERSION.SDK_INT >= 33 && FeedWeb.background(this)
                    && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
                    && !Prefs.sp(this).getBoolean("web_asked_notif", false)) {
                Prefs.sp(this).edit().putBoolean("web_asked_notif", true).apply();
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 19);
            }
        }
    }

    /** A button in the player notification / lock screen / headphones. */
    public void webMediaCommand(String tab, String cmd, double v) {
        android.webkit.WebView wv = webViews.get(tab);
        if (wv == null) return;
        if ("play".equals(cmd)) { // may have been frozen while you were away
            root.removeCallbacks(sleepWebRunnable);
            if (webAsleep) { wv.resumeTimers(); webAsleep = false; }
            wv.onResume();
        }
        wv.evaluateJavascript(WebMedia.command(cmd, v), null);
    }

    // ------------------------------------------------------------------ pop-up player (picture-in-picture)

    private String pipTab;

    public String pipTab() { return pipTab; }
    void setPipTab(String t) { pipTab = t; }

    public void openPip(String tab) {
        if (tab == null || webViews.get(tab) == null) { Ui.toast(this, "Nothing is playing"); return; }
        PipActivity.open(this, tab);
    }

    /** The pop-up closed: the page goes back into its tab. */
    void onPipClosed(String tab) {
        if (tab.equals(pipTab)) pipTab = null;
        if (feed != null) feed.refresh();
    }

    /** A page asked for a new window after you tapped a link: open it as a new feed tab. */
    /** A page opened a new window (after a tap): it becomes a new tab, grouped with the tab it came from. */
    public void openWebTab(String url, String opener) {
        if (FeedWeb.openOutside(this, url)) return;
        String id = FeedWeb.newTabFrom(this, opener, url);
        if (feed != null) feed.showTab(id);
    }

    /** Opens a bookmark in the feed browser: in its site's tab if there is one, else a new tab. */
    public void openBookmark(String url) {
        if (FeedWeb.openOutside(this, url)) return;
        String host = FeedPage.hostOf(url);
        String found = null;
        for (String t : FeedWeb.tabs(this)) {
            if (FeedWeb.isAppTab(t)) continue;
            android.webkit.WebView wv = webViews.get(t);
            String cur = wv != null && wv.getUrl() != null ? wv.getUrl() : FeedWeb.last(this, t);
            if (host.equals(FeedPage.hostOf(cur)) || host.equals(FeedPage.hostOf(FeedWeb.url(this, t)))) { found = t; break; }
        }
        if (found == null) found = FeedWeb.newTab(this, url, null);
        else feedWebView(found).loadUrl(url);
        if (feed != null) feed.showTab(found);
        if (workspace.getCurrentPage() != 0 && feed != null) workspace.snapToPage(0);
    }

    /** App tabs in the feed: open the app in a window where the phone supports it (else normally). */
    public void launchInWindow(AppInfo a) {
        android.os.Bundle opts = null;
        // Ask for a floating window (freeform). Phones that support it (many Samsung / Xiaomi / some others,
        // or with "freeform windows" on in developer options) open the app as a window; others open it normally.
        try {
            int w = root.getWidth(), h = root.getHeight();
            android.app.ActivityOptions o = android.app.ActivityOptions.makeBasic();
            o.setLaunchBounds(new android.graphics.Rect(w / 14, h / 7, w - w / 14, h - h / 6));
            try {
                java.lang.reflect.Method m = android.app.ActivityOptions.class.getMethod("setLaunchWindowingMode", int.class);
                m.invoke(o, 5); // WINDOWING_MODE_FREEFORM
            } catch (Throwable ignored) { }
            opts = o.toBundle();
        } catch (Throwable ignored) { }
        try { repo.launcherApps().startMainActivity(a.component, a.user, null, opts); }
        catch (Exception e) { repo.launch(a, null); }
    }

    public void pauseWebMedia() {
        for (android.webkit.WebView wv : webViews.values()) wv.evaluateJavascript(FeedWeb.PAUSE_JS, null);
    }

    private android.app.Dialog videoDialog;
    private android.webkit.WebChromeClient.CustomViewCallback videoCallback;

    /** A video's full-screen button: show it full screen, sideways, with the system bars hidden. */
    public void showVideoFullscreen(View view, android.webkit.WebChromeClient.CustomViewCallback cb) {
        // a pop-up player asked for this video on its own: it goes there, not full screen
        PipActivity pip = PipActivity.waitingForVideo();
        if (pip != null) { pip.onVideoView(view, cb); return; }
        hideVideoFullscreen();
        videoCallback = cb;
        final android.app.Dialog d = new android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        if (view.getParent() != null) ((ViewGroup) view.getParent()).removeView(view);
        FrameLayout box = new FrameLayout(this);
        box.setBackgroundColor(0xFF000000);
        box.addView(view, new FrameLayout.LayoutParams(-1, -1));
        d.setContentView(box);
        d.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override public void onDismiss(android.content.DialogInterface di) {
                if (videoDialog == d) videoDialog = null;
                setRequestedOrientation(Prefs.sp(LauncherActivity.this).getBoolean("allow_rotation", false)
                        ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                android.webkit.WebChromeClient.CustomViewCallback c = videoCallback;
                videoCallback = null;
                if (c != null) { try { c.onCustomViewHidden(); } catch (Exception ignored) { } }
            }
        });
        videoDialog = d;
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        d.show();
        android.view.Window w = d.getWindow();
        if (w != null) {
            w.setLayout(-1, -1);
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.view.WindowInsetsController ic = w.getInsetsController();
                if (ic != null) {
                    ic.hide(android.view.WindowInsets.Type.systemBars());
                    ic.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            }
        }
    }

    public void hideVideoFullscreen() {
        PipActivity pip = PipActivity.current();
        if (pip != null && pip.showsVideoView() && videoDialog == null) { pip.onVideoViewGone(); return; }
        if (videoDialog != null) { android.app.Dialog d = videoDialog; videoDialog = null; d.dismiss(); }
    }

    @Override protected void onResume() {
        try { WebHistory.autoSync(this); } catch (Throwable ignored) { }
        super.onResume();
        resumed = true;
        shown = true;
        applyStatusBar();
        AppLock.guard(this, false);
        if (nightAtCreate != Ui.night(this)) { recreate(); return; } // dark mode by time, or the phone switched
        // the first frame comes first; wallpaper housekeeping runs right after it
        root.post(new Runnable() {
            @Override public void run() {
                if (!resumed) return;
                try {
                if (blurLayer != null && blurLayer.getVisibility() == View.GONE) blurLayer.setImageDrawable(null); // wallpaper may have changed
                                } catch (Throwable ignored) { }
            }
        });
        if (needsRecreate) { needsRecreate = false; recreate(); return; }
        if (repo.isLoaded() && store.version() != boundVersion) bindAll();
        // Refresh the feed only while it's off-screen, so sliding to it never shows a reload.
        // (at most once a minute: rebuilding the cards queries calendar, usage stats… on every return home)
        if (feed != null && workspace.getCurrentPage() != 0
                && android.os.SystemClock.elapsedRealtime() - lastFeedRefresh > 60_000) {
            lastFeedRefresh = android.os.SystemClock.elapsedRealtime();
            root.postDelayed(new Runnable() {
                @Override public void run() {
                    try { if (feed != null && resumed && workspace.getCurrentPage() != 0) feed.refresh(); } catch (Throwable ignored) { }
                }
            }, 2500); // well after the return animation, not during it
        }
        if (returningHome) {
            returningHome = false;
            // Android first plays its own "app closing" animation over us; ours starts once that's done
            // (onEnterAnimationComplete), or after a short fallback delay, so you can actually see it.
            if (prepareHomeEnter()) {
                homeEnterPending = true;
                root.postDelayed(new Runnable() { @Override public void run() { runHomeEnter(); } }, 450);
            }
        }
    }

    private long lastFeedRefresh;

    /** "Return to home" animation (Settings > Animations). */
    private boolean homeEnterPending, homeAnimating;

    /** Sets the starting state of the return-to-home animation. False = no animation. */
    private boolean prepareHomeEnter() {
        String a = "none"; // return-to-home animation removed: Android's own plays
        if ("none".equals(a)) return false;
        homeAnimating = true;
        for (View v : new View[]{workspace, dock, searchPill}) {
            if (v == null) continue;
            v.animate().cancel();
            if ("zoom".equals(a)) { v.setScaleX(1.12f); v.setScaleY(1.12f); v.setAlpha(0f); }
            else if ("rise".equals(a)) { v.setTranslationY(Ui.dp(this, 90)); v.setAlpha(0f); }
            else if ("fade".equals(a)) { v.setAlpha(0f); }
            else if ("shrink".equals(a)) { v.setScaleX(0.82f); v.setScaleY(0.82f); v.setAlpha(0f); }
        }
        return true;
    }

    @Override public void onEnterAnimationComplete() {
        super.onEnterAnimationComplete();
        runHomeEnter();
    }

    private void runHomeEnter() {
        if (!homeEnterPending) return;
        homeEnterPending = false;
        int i = 0;
        for (View v : new View[]{workspace, dock, searchPill}) {
            if (v == null) continue;
            android.view.ViewPropertyAnimator an = v.animate().scaleX(1f).scaleY(1f).translationY(0).alpha(1f)
                    .setStartDelay(i++ * 40L).setDuration(Anim.dur(this, 420)).setInterpolator(Anim.gentle(this));
            if (v == workspace) an.withEndAction(new Runnable() { @Override public void run() { homeAnimating = false; applyDepth(); } });
            an.start();
        }
    }

    @Override protected void onPause() {
        super.onPause();
        resumed = false;

        dismissPopup();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (wallListener != null && android.os.Build.VERSION.SDK_INT >= 27) {
            try { android.app.WallpaperManager.getInstance(this).removeOnColorsChangedListener(wallListener); } catch (Throwable ignored) { }
        }
        // Web tabs stay open: they move to the next home screen instead of closing (see feedWebView()).
        for (android.webkit.WebView wv : webViews.values()) {
            if (wv.getParent() != null) ((ViewGroup) wv.getParent()).removeView(wv);
        }
        repo.removeListener(this);
        Badges.removeListener(this);
        Prefs.sp(this).unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Was the home screen already on screen? (Home pressed while on home vs. coming back from an app.)
        boolean wasForeground = shown;
        boolean hadOverlay = popup != null || folderView != null || drawer.isOpen() || dock.isExpanded()
                || widgetPicker != null || resizeFrame != null || searchOverlay != null;
        dismissPopup();
        closeFolder();
        finishWidgetResize();
        if (widgetPicker != null) widgetPicker.close();
        if (searchOverlay != null) searchOverlay.close();
        dock.setExpanded(false);
        drawer.close(wasForeground);
        boolean onFeed = feed != null && workspace.getCurrentPage() == 0;
        // Home gesture / button: from the feed or another page, go back to the main home page.
        if (!wasForeground || !hadOverlay || onFeed) {
            if (wasForeground) workspace.snapToPage(defaultPage());
            else workspace.setCurrentPageImmediate(defaultPage());
        }
        returningHome = !wasForeground;
    }

    @Override public void onBackPressed() {
        if (popup != null) { dismissPopup(); return; }
        if (resizeFrame != null) { finishWidgetResize(); return; }
        if (widgetPicker != null) { widgetPicker.close(); return; }
        if (searchOverlay != null) { searchOverlay.close(); return; }
        if (drawer.isOpen() && drawer.isSelecting()) { drawer.endSelection(); return; }
        if (popup != null) { dismissPopup(); return; }
        if (folderView != null) { closeFolder(); return; }
        if (pageEditor != null) { closePageEditor(currentHomePage()); return; }
        if (drawer.isOpen()) { drawer.close(true); return; }
        if (dock.isExpanded()) { dock.setExpanded(false); return; }
        if (feed != null && workspace.getCurrentPage() == 0 && feed.onBack()) return;
        workspace.snapToPage(defaultPage());
    }

    @Override public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
        if (key == null) return;
        if ("feed_bg".equals(key) || "feed_color".equals(key)) {
            loadFeedBg();
            lastWindowBlur = -1;
            applyDepth();
            if (feed != null) feed.refresh();
            return;
        }
        // written by feed cards (DNS, history…): read live, never rebuild the screen for them
        if (key.startsWith("hide_bar_")) { barHidden = null; applyStatusBar(); return; }
        if (key.startsWith("pdns_") || key.startsWith("guard_") || key.startsWith("shizuwall_") || key.startsWith("smartspacer_")
                || key.startsWith("notif_history") || "menu_notifications".equals(key)) {
            if (key.startsWith("notif_history") && feed != null) feed.refresh();
            return;
        }
        if ("keep_alive".equals(key)) { KeepAliveService.sync(this); return; }
        if ("applock".equals(key)) return;
        if ("web_autoplay".equals(key)) {
            for (android.webkit.WebView wv : webViews.values()) wv.getSettings().setMediaPlaybackRequiresUserGesture(!FeedWeb.autoplay(this));
            return;
        }
        if ("home_pages".equals(key)) return;
        if ("transition".equals(key)) { workspace.setTransition(Prefs.transition(this)); return; }
        if (key.startsWith("web_") || key.startsWith("tabname_") || key.startsWith("feed_profile")) {
            if (key.startsWith("tabname_")) drawer.refresh();
            if ("web_icon_only".equals(key) && feed != null) feed.buildTabs();
            if (key.startsWith("feed_profile") && feed != null) feed.refresh();
            return;
        }
        if ("icon_pack".equals(key)) { repo.reload(); return; }
        // Search bar position changes the layout: rebuild (previously swallowed by the "search_" rule below).
        if ("search_bar".equals(key)) { if (resumed) recreate(); else needsRecreate = true; return; }
        // Read live where they're used; no rebuild needed.
        if ("drawer_locked".equals(key) || "widget_free".equals(key)) return;
        if ("drawer_groups".equals(key) || "drawer_shortcuts".equals(key) || "tabs_hide_from_all".equals(key)
                || "drawer_widgets".equals(key)) { drawer.refresh(); return; }
        if (key.startsWith("search_") || "recent_searches".equals(key) || "anim_app_open".equals(key)
                || "anim_home_enter".equals(key)) return;
        if ("pinned_target".equals(key) || "depth_blur".equals(key)) return;
        if ("drawer_alphabet".equals(key)) { drawer.applyGrid(); drawer.refresh(); return; }
        if ("drawer_keyboard".equals(key) || "haptics".equals(key)) return;
        if ("main_page".equals(key)) { indicator.setup(workspace.getChildCount(), feed != null, defaultPage()); return; }
        if ("widget_overlap".equals(key)) { store.overlapWidgets = Prefs.widgetOverlap(this); return; }
        if ("drawer_cols".equals(key) || "drawer_rows".equals(key) || "drawer_icon_scale".equals(key)) { drawer.applyGrid(); return; }
        if ("hidden_apps".equals(key) || "drawer_sort".equals(key) || "drawer_favs".equals(key)) { drawer.refresh(); return; }
        if ("user_name".equals(key) || key.startsWith("card_")) { if (feed != null) feed.refresh(); return; }
        if (key.startsWith("g_") || "feed_order".equals(key) || key.startsWith("fw_size_")) { if (!key.startsWith("g_") && feed != null) feed.refresh(); return; }
        if ("icon_shape".equals(key) || "wrap_legacy".equals(key)) { repo.reload(); }
        if (resumed) recreate(); else needsRecreate = true;
    }

    @Override public void onAppsChanged() {
        if (!store.initialized()) {
            store.createDefaults(repo, Prefs.gridCols(this), Prefs.gridRows(this), Prefs.dockCount(this));
        }
        bindAll();
    }

    // ------------------------------------------------------------------ layout

    private void buildUi() {
        root = new FrameLayout(this);
        root.setOnDragListener(rootDrag);
        setContentView(root);

        // a blurred copy of the wallpaper for the feed / drawer on phones without Android's window blur
        blurLayer = new android.widget.ImageView(this);
        blurLayer.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        blurLayer.setVisibility(View.GONE);
        root.addView(blurLayer, new FrameLayout.LayoutParams(-1, -1));

        backdrop = new View(this);
        root.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));

        workspace = new Workspace(this, this);
        root.addView(workspace, new FrameLayout.LayoutParams(-1, -1));

        indicator = new PageIndicator(this);
        indicator.setVisibility(Prefs.pageIndicator(this) ? View.VISIBLE : View.GONE);
        root.addView(indicator, new FrameLayout.LayoutParams(-1, Ui.dp(this, 14), Gravity.BOTTOM));

        boolean wl = Ui.wallpaperIsLight(this);
        searchPill = Ui.text(this, "Search apps, contacts, settings & web", 15, wl ? 0xEE111111 : 0xEEFFFFFF);
        searchPill.setCompoundDrawablesRelativeWithIntrinsicBounds(Ui.tinted(this, R.drawable.ic_search, wl ? 0xEE111111 : 0xEEFFFFFF), null, null, null);
        searchPill.setCompoundDrawablePadding(Ui.dp(this, 10));
        searchPill.setGravity(Gravity.CENTER_VERTICAL);
        searchPill.setSingleLine(true);
        searchPill.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (Ui.smallPhone(this)) searchPill.setTextSize(14);
        searchPill.setPadding(Ui.dp(this, 18), 0, Ui.dp(this, 18), 0);
        searchPill.setBackground(Ui.pressable(this, wl ? 0x59FFFFFF : 0x40000000, Ui.dp(this, 24)));
        searchPill.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { openSearch(""); } });
        searchPill.setVisibility("none".equals(Prefs.searchBar(this)) ? View.GONE : View.VISIBLE);
        root.addView(searchPill, new FrameLayout.LayoutParams(-1, Ui.dp(this, Ui.smallPhone(this) ? 42 : 46),
                "top".equals(Prefs.searchBar(this)) ? Gravity.TOP : Gravity.BOTTOM));

        dock = new DockView(this, Prefs.dockCount(this), dockHeight(), cellDrag);
        dock.setVisibility(Prefs.dockEnabled(this) ? View.VISIBLE : View.GONE);
        root.addView(dock, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        dropBar = new DropTargetBar(this, this);
        dropBar.setVisibility(View.GONE);
        root.addView(dropBar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        drawer = new DrawerView(this);
        root.addView(drawer, new FrameLayout.LayoutParams(-1, -1));

        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @SuppressWarnings("deprecation")
            @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets in) {
                insetTop = in.getStableInsetTop();
                insetBottom = in.getStableInsetBottom();
                insetLeft = in.getStableInsetLeft();
                insetRight = in.getStableInsetRight();
                layoutChrome();
                drawer.setImeInset(in.getSystemWindowInsetBottom());
                return in;
            }
        });
        root.requestApplyInsets();
        layoutChrome();
    }

    private int iconPx() { return Math.round(Ui.dp(this, 52) * Ui.compact(this) * Prefs.iconScale(this) / 100f); }
    private int dockHeight() { return iconPx() + Ui.dp(this, 22); }

    private void layoutChrome() {
        int dockH = Prefs.dockEnabled(this) ? dockHeight() + (Prefs.dockExpandable(this) ? Ui.dp(this, 12) : 0) : 0;
        int gap = Ui.dp(this, 10);
        String sb = Prefs.searchBar(this);
        int pillH = "none".equals(sb) ? 0 : Ui.dp(this, 46) + gap;
        int bottomPill = "bottom".equals(sb) ? pillH : 0, topPill = "top".equals(sb) ? pillH : 0;
        FrameLayout.LayoutParams pl = (FrameLayout.LayoutParams) searchPill.getLayoutParams();
        pl.leftMargin = insetLeft + gap * 2;
        pl.rightMargin = insetRight + gap * 2;
        pl.topMargin = insetTop + gap;
        pl.bottomMargin = insetBottom + gap;
        searchPill.setLayoutParams(pl);
        FrameLayout.LayoutParams dl = (FrameLayout.LayoutParams) dock.getLayoutParams();
        dl.bottomMargin = insetBottom + gap + bottomPill;
        dl.leftMargin = insetLeft + gap;
        dl.rightMargin = insetRight + gap;
        dock.setLayoutParams(dl);
        FrameLayout.LayoutParams il = (FrameLayout.LayoutParams) indicator.getLayoutParams();
        il.bottomMargin = insetBottom + gap + bottomPill + dockH + Ui.dp(this, 4);
        indicator.setLayoutParams(il);
        workspace.setPadding(insetLeft + Ui.dp(this, 4), insetTop + Ui.dp(this, 6) + topPill, insetRight + Ui.dp(this, 4),
                insetBottom + gap + bottomPill + dockH + Ui.dp(this, 22));
        dropBar.setPadding(0, insetTop, 0, Ui.dp(this, 12));
        workspace.setFeedBottom(0);          // the feed runs under the navigation bar, like the drawer
        if (feed != null) feed.setBottomInset(insetBottom);
        drawer.setInsets(insetTop, insetBottom);
    }

    private int glanceOffset() { return feed != null ? 1 : 0; }
    /** The "main" home page Home returns to (chosen by you). */
    private int mainHomePage() { return Math.max(0, Math.min(store.pageCount() - 1, Prefs.sp(this).getInt("main_page", 0))); }
    private int defaultPage() { return glanceOffset() + mainHomePage(); }

    // ------------------------------------------------------------------ edit home screens

    private PageEditor pageEditor;

    CellLayout pageLayoutFor(int page) { return pageLayout(page); }
    int mainPage() { return mainHomePage(); }
    void rebindPages() { bindAll(); }

    void openPageEditor() {
        if (pageEditor != null) return;
        if (drawer.isOpen()) drawer.close(false);
        pageEditor = new PageEditor(this, store, currentHomePage());
        root.addView(pageEditor, new FrameLayout.LayoutParams(-1, -1));
        pageEditor.setAlpha(0f);
        pageEditor.animate().alpha(1f).setDuration(Anim.dur(this, 180)).start();
    }

    void closePageEditor(int page) {
        if (pageEditor == null) return;
        root.removeView(pageEditor);
        pageEditor = null;
        workspace.snapToPage(Math.max(0, Math.min(store.pageCount() - 1, page)) + glanceOffset());
    }

    /** From the page editor: put a widget on that screen in the first free spot. */
    void addWidgetToPage(AppWidgetProviderInfo info, int page) {
        CellLayout cl = pageLayout(page);
        if (cl == null) return;
        int[] sp = widgetSpans(info);
        int[] f = store.findFreeNear(HomeItem.DESKTOP, page, 0, 0, sp[0], sp[1], null, cl.cols, cl.rows);
        if (f == null) { Ui.toast(this, "No room left on this screen"); return; }
        closePageEditor(page);
        pendingCell = new int[]{page, f[0], f[1], sp[0], sp[1]};
        pendingToFeed = false;
        pendingDrawerAnchor = null;
        addWidget(info);
    }

    private CellLayout pageLayout(int page) {
        int idx = page + glanceOffset();
        if (idx < 0 || idx >= workspace.getChildCount()) return null;
        View v = workspace.getChildAt(idx);
        return v instanceof CellLayout ? (CellLayout) v : null;
    }

    private int currentHomePage() {
        return Math.max(0, Math.min(store.pageCount() - 1, workspace.getCurrentPage() - glanceOffset()));
    }

    public void requestBind() {
        root.removeCallbacks(bindRunnable);
        root.post(bindRunnable);
    }

    private void bindAll() {
        boundVersion = store.version();
        if (Prefs.sp(this).getInt("home_pages", -1) != store.pageCount()) Prefs.sp(this).edit().putInt("home_pages", store.pageCount()).apply();
        int current = workspace.getCurrentPage();
        workspace.removeAllViews();
        feed = new FeedPage(this);
        feed.setBottomInset(insetBottom);
        workspace.addView(feed);
        int cols = Prefs.gridCols(this), rows = Prefs.gridRows(this);
        for (int p = 0; p < store.pageCount(); p++) {
            CellLayout cl = new CellLayout(this, HomeItem.DESKTOP, p, cols, rows);
            cl.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
            cl.setOnDragListener(cellDrag);
            workspace.addView(cl);
        }
        dock.row1.removeAllViews();
        dock.row2.removeAllViews();
        boolean changed = false;
        // If the grid got smaller in settings, pull items that fell off the edge back onto the page.
        for (HomeItem it : new ArrayList<>(store.items())) {
            if (it.container != HomeItem.DESKTOP || it.isFree()) continue;
            if (it.x + it.spanX <= cols && it.y + it.spanY <= rows) continue;
            int sx = Math.min(it.spanX, cols), sy = Math.min(it.spanY, rows);
            int[] f = store.findFreeNear(HomeItem.DESKTOP, it.page, Math.max(0, Math.min(it.x, cols - sx)),
                    Math.max(0, Math.min(it.y, rows - sy)), sx, sy, it, cols, rows);
            if (f != null) {
                it.x = f[0];
                it.y = f[1];
                it.spanX = sx;
                it.spanY = sy;
                changed = true;
            }
        }
        // Widgets first so icons are drawn on top of them when they overlap.
        List<HomeItem> ordered = new ArrayList<>();
        for (HomeItem it : store.items()) if (it.type == HomeItem.WIDGET) ordered.add(it);
        for (HomeItem it : store.items()) if (it.type != HomeItem.WIDGET) ordered.add(it);
        for (HomeItem it : ordered) {
            View v = createItemView(it);
            if (v == null) {
                if (isOrphan(it)) { store.detach(it); changed = true; }
                continue;
            }
            CellLayout target = it.container == HomeItem.DOCK ? (it.y >= 1 ? dock.row2 : dock.row1) : pageLayout(it.page);
            if (target == null) continue;
            if (it.container == HomeItem.DOCK && (it.x >= target.cols || it.y > 1)) continue;
            target.addItem(v, it.x, it.y, Math.min(it.spanX, target.cols), Math.min(it.spanY, target.rows));
            if (it.edge && it.container == HomeItem.DESKTOP && it.type == HomeItem.WIDGET) {
                ((CellLayout.LP) v.getLayoutParams()).edge = true;
                v.setPadding(0, 0, 0, 0);
            }
            if (it.isFree() && it.container == HomeItem.DESKTOP) {
                CellLayout.LP lp = (CellLayout.LP) v.getLayoutParams();
                lp.fx = it.fx; lp.fy = it.fy; lp.fw = it.fw; lp.fh = it.fh;
                if (v instanceof IconView) ((IconView) v).freeSized = true;
            }
        }
        if (changed) { store.commit(); boundVersion = store.version(); }
        if (firstBind) {
            // after a rebuild (a settings change), stay on the page you were on — e.g. the feed
            workspace.setCurrentPageImmediate(restorePage >= 0 && restorePage < workspace.getChildCount() ? restorePage : defaultPage());
            restorePage = -1;
            firstBind = false;
        } else {
            workspace.setCurrentPageImmediate(current);
        }
        indicator.setup(workspace.getChildCount(), feed != null, defaultPage());
        indicator.setPosition(workspace.getCurrentPage());
        drawer.refresh();
    }

    /** True when an item's app/widget is truly gone (not just on an unmounted SD card or paused profile). */
    private boolean isOrphan(HomeItem it) {
        if (!repo.isLoaded()) return false;
        if (it.type == HomeItem.APP) {
            ComponentName cn = ComponentName.unflattenFromString(it.component == null ? "" : it.component);
            if (cn == null) return true;
            // Work-profile apps vanish while the profile is paused; only prune main-profile items.
            return it.serial == repo.mySerial();
        }
        if (it.type == HomeItem.WIDGET) return awm.getAppWidgetInfo(it.widgetId) == null;
        if (it.type == HomeItem.LIBRARY) return true; // retired Books tile from older versions
        return false;
    }

    private View createItemView(HomeItem it) {
        int style = it.container == HomeItem.DOCK ? IconView.STYLE_DOCK : IconView.STYLE_HOME;
        switch (it.type) {
            case HomeItem.WIDGET: {
                AppWidgetProviderInfo info = awm.getAppWidgetInfo(it.widgetId);
                if (info == null) return null;
                LauncherWidgetView wv = widgetViews.get(it.widgetId);
                if (wv == null) {
                    wv = (LauncherWidgetView) widgetHost.createView(this, it.widgetId, info);
                    widgetViews.put(it.widgetId, wv);
                }
                if (wv.getParent() != null) ((ViewGroup) wv.getParent()).removeView(wv);
                wv.host = this;
                wv.setTag(it);
                wv.clickThrough = it.clickThrough && it.container == HomeItem.DESKTOP;
                wv.setCrop(it.cropL, it.cropT, it.cropR, it.cropB);
                return wv;
            }
            case HomeItem.FOLDER: {
                IconView v = new IconView(this, this, style);
                List<Drawable> previews = new ArrayList<>();
                for (HomeItem c : it.children) previews.add(iconFor(c));
                v.bindFolder(it.title == null ? "Folder" : it.title, previews);
                List<String> pkgs = new ArrayList<>();
                for (HomeItem c : it.children) { String p = pkgOf(c); if (p != null) pkgs.add(p); }
                v.setBadgePackages(pkgs);
                v.setTag(it);
                return v;
            }
            default: {
                Drawable d = iconFor(it);
                if (d == null) return null;
                IconView v = new IconView(this, this, style);
                v.bind(d, labelFor(it));
                v.setBadgePackage(pkgOf(it));
                v.setTag(it);
                return v;
            }
        }
    }

    private static String pkgOf(HomeItem it) {
        if (it.type == HomeItem.SHORTCUT) return it.pkg;
        if (it.type == HomeItem.APP && it.component != null) {
            ComponentName cn = ComponentName.unflattenFromString(it.component);
            return cn != null ? cn.getPackageName() : null;
        }
        return null;
    }

    private final Map<String, Drawable> webIcons = new HashMap<>();

    /** Coloured initial, until a website's own icon has been downloaded. */
    private Drawable letterDrawable(String label) {
        final String l = label == null || label.isEmpty() ? "?" : label.substring(0, 1).toUpperCase();
        final int bg = Ui.palette(this).accent, fg = Ui.palette(this).onAccent;
        return new Drawable() {
            final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            @Override public void draw(android.graphics.Canvas cv) {
                Rect r = getBounds();
                p.setColor(bg);
                cv.drawCircle(r.exactCenterX(), r.exactCenterY(), r.width() / 2f, p);
                p.setColor(fg);
                p.setTextSize(r.height() * 0.5f);
                p.setTextAlign(android.graphics.Paint.Align.CENTER);
                p.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                cv.drawText(l, r.exactCenterX(), r.exactCenterY() - (p.descent() + p.ascent()) / 2f, p);
            }
            @Override public void setAlpha(int a) { }
            @Override public void setColorFilter(android.graphics.ColorFilter f) { }
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        };
    }

    /** A website on the home screen (from the feed browser); it can go in folders like any app. */
    public void addWebToHome(String url, String title) {
        CellLayout cl = pageLayout(currentHomePage());
        if (cl == null || url == null) return;
        int[] f = store.findFreeNear(HomeItem.DESKTOP, currentHomePage(), 0, 0, 1, 1, null, cl.cols, cl.rows);
        if (f == null) { Ui.toast(this, "No room on this home screen"); return; }
        HomeItem it = new HomeItem();
        it.id = store.newId();
        it.type = HomeItem.WEB;
        it.container = HomeItem.DESKTOP;
        it.page = currentHomePage();
        it.x = f[0];
        it.y = f[1];
        it.component = url;
        it.title = title == null || title.trim().isEmpty() ? FeedPage.hostOf(url) : title.trim();
        store.addTop(it);
        store.commit();
        requestBind();
        Ui.toast(this, "Added to the home screen \u2014 drag it onto another icon to make a folder");
    }

    public Drawable iconFor(HomeItem it) {
        switch (it.type) {
            case HomeItem.APP: {
                AppInfo a = repo.find(it.component, it.serial);
                return a != null ? a.icon : null;
            }
            case HomeItem.WEB: {
                final String site = FeedPage.hostOf(it.component);
                Drawable d = webIcons.get(site);
                if (d == null) {
                    android.graphics.Bitmap b = SiteIcons.get(this, site);
                    if (b != null) { d = new BitmapDrawable(getResources(), b); webIcons.put(site, d); }
                    else {
                        SiteIcons.fetch(this, site, new SiteIcons.Done() { @Override public void done() { requestBind(); } });
                        d = letterDrawable(labelFor(it));
                    }
                }
                return d;
            }
            case HomeItem.SHORTCUT: {
                String k = it.pkg + "/" + it.shortcutId;
                Drawable d = shortcutIcons.get(k);
                if (d == null) {
                    File f = AddShortcutActivity.iconFile(this, it);
                    if (f.exists()) d = new BitmapDrawable(getResources(), BitmapFactory.decodeFile(f.getPath()));
                    else d = getPackageManager().getDefaultActivityIcon();
                    shortcutIcons.put(k, d);
                }
                return d;
            }
        }
        return null;
    }

    public String labelFor(HomeItem it) {
        if (it.title != null && it.type != HomeItem.FOLDER) return it.title;
        switch (it.type) {
            case HomeItem.APP: {
                AppInfo a = repo.find(it.component, it.serial);
                return a != null ? a.label : "";
            }
            case HomeItem.FOLDER: return it.title == null ? "Folder" : it.title;
        }
        return "";
    }

    // ------------------------------------------------------------------ clicks

    public void launchApp(AppInfo a, View from) {
        repo.launch(a, from);
        root.postDelayed(new Runnable() {
            @Override public void run() {
                if (drawer.isOpen()) drawer.close(false);
                // Launching from the search page used to leave it open behind the app.
                if (searchOverlay != null) searchOverlay.close();
            }
        }, 350);
    }

    @Override public void onIconClick(IconView v) {
        Object tag = v.getTag();
        if (tag instanceof DrawerGroups.Group) { openDrawerFolder((DrawerGroups.Group) tag); if (folderView != null) folderView.setAnchor(v); return; }
        if (drawer.isSelecting() && v.style == IconView.STYLE_DRAWER && drawer.isOpen()) { drawer.toggleSelect(tag); return; }
        if (tag instanceof DrawerShortcuts.Entry) { launchDrawerShortcut((DrawerShortcuts.Entry) tag, v); return; }
        if (tag instanceof AppInfo) {
            launchApp((AppInfo) tag, v);
            if (v.folderParent != null) closeFolder();
            return;
        }
        if (!(tag instanceof HomeItem)) return;
        HomeItem it = (HomeItem) tag;
        switch (it.type) {
            case HomeItem.APP: {
                AppInfo a = repo.find(it.component, it.serial);
                if (a != null) repo.launch(a, v);
                else Ui.toast(this, "App not available");
                break;
            }
            case HomeItem.FOLDER: openFolder(it); if (folderView != null) folderView.setAnchor(v); return;
            case HomeItem.SHORTCUT: startShortcut(it, v); break;
            case HomeItem.WEB: openBookmark(it.component); if (workspace.getCurrentPage() != 0 && feed != null) workspace.snapToPage(0); break;
        }
        if (v.folderParent != null) closeFolder();
    }

    private void startShortcut(HomeItem it, View v) {
        try {
            int[] loc = new int[2];
            v.getLocationOnScreen(loc);
            Rect r = new Rect(loc[0], loc[1], loc[0] + v.getWidth(), loc[1] + v.getHeight());
            repo.launcherApps().startShortcut(it.pkg, it.shortcutId, r, null, repo.userFor(it.serial));
        } catch (Exception e) {
            Ui.toast(this, "Shortcut isn't available. Make Grove your default home app.");
        }
    }

    // ------------------------------------------------------------------ universal search

    private SearchOverlay searchOverlay;

    public void openSearch(String q) {
        if (searchOverlay != null) return;
        if (drawer.isOpen()) drawer.close(false);
        dock.setExpanded(false);
        searchOverlay = new SearchOverlay(this, q);
        root.addView(searchOverlay, new FrameLayout.LayoutParams(-1, -1));
        setOverlay(true);
    }

    public void onSearchClosed(SearchOverlay s) {
        if (searchOverlay == s) searchOverlay = null;
        if (!drawer.isOpen() && widgetPicker == null && folderView == null) setOverlay(false);
    }

    /** Adds several apps to the home screen at once (multi-select). */
    public void addAppsToHome(List<AppInfo> apps) {
        if (Prefs.desktopLocked(this)) { Ui.toast(this, "Desktop is locked \u2014 unlock it in Grove settings"); return; }
        int cols = Prefs.gridCols(this), rows = Prefs.gridRows(this);
        int added = 0, firstPage = -1;
        for (AppInfo a : apps) {
            int[] f = null;
            int page = -1;
            for (int i = 0; i < store.pageCount() && f == null; i++) {
                int p = (currentHomePage() + i) % store.pageCount();
                f = store.findFirstFree(HomeItem.DESKTOP, p, 1, 1, cols, rows);
                if (f != null) page = p;
            }
            if (f == null) { store.addPage(); page = store.pageCount() - 1; f = new int[]{0, 0}; }
            HomeItem it = HomeItem.app(store.newId(), a);
            it.page = page;
            it.x = f[0];
            it.y = f[1];
            store.addTop(it);
            if (firstPage < 0) firstPage = page;
            added++;
        }
        store.commit();
        drawer.close(true);
        bindAll();
        if (firstPage >= 0) workspace.snapToPage(firstPage + glanceOffset());
        Ui.toast(this, added + (added == 1 ? " app added" : " apps added") + " to home screen");
    }

    private String pendingDrawerAnchor;

    public void showDrawerWidgetPicker(String anchorKey) {
        showWidgetPicker(false);
        pendingDrawerAnchor = anchorKey == null ? "" : anchorKey;
    }

    public void openFeed() {
        if (drawer.isOpen()) drawer.close(true);
        dock.setExpanded(false);
        if (feed != null) workspace.snapToPage(0);
    }

    private String pendingIconKey;

    public void pickIconImage(String key) {
        pendingIconKey = key;
        Intent i = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
        try { startActivityForResult(Intent.createChooser(i, "Choose icon image"), REQ_ICON_IMAGE); }
        catch (Exception e) { pendingIconKey = null; Ui.toast(this, "No image picker available"); }
    }

    /** After editing an app's name/icon: drop per-item home labels for it so the new name shows everywhere. */
    public void onAppEdited(AppInfo app) {
        String cmp = app.component.flattenToString();
        boolean changed = false;
        for (HomeItem it : store.items()) {
            changed |= clearTitle(it, cmp, app.serial);
            for (HomeItem ch : it.children) changed |= clearTitle(ch, cmp, app.serial);
        }
        if (changed) store.commit();
        repo.applyEdit(app.key); // the same icon everywhere (drawer, home, dock, folders), right away
    }

    private static boolean clearTitle(HomeItem it, String cmp, long serial) {
        if (it.type == HomeItem.APP && cmp.equals(it.component) && it.serial == serial && it.title != null) {
            it.title = null;
            return true;
        }
        return false;
    }

    public void safeStart(Intent i) {
        try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (Exception e) { Ui.toast(this, "Not available on this phone"); }
    }

    public void requestCalendar() {
        requestPermissions(new String[]{android.Manifest.permission.READ_CALENDAR}, REQ_CALENDAR);
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (feed != null) feed.refresh();
        if (searchOverlay != null) searchOverlay.refreshResults();
        if (drawer != null) drawer.refreshSearch();
    }

    @Override public void onBadgesChanged() { invalidateIcons(root); }

    private void invalidateIcons(View v) {
        if (v instanceof IconView) v.invalidate();
        else if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) invalidateIcons(g.getChildAt(i));
        }
    }

    public void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }


    // ------------------------------------------------------------------ gestures

    public void runAction(String action) {
        switch (action) {
            case "drawer": drawer.open(false); break;
            case "search": openSearch(""); break;
            case "notifications": statusBar("expandNotificationsPanel"); break;
            case "quick_settings":
                if (!CanopyAccessibility.perform(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)) statusBar("expandSettingsPanel");
                break;
            case "glance": openFeed(); break;
            case "lock":
                // Shizuku first (no accessibility service needed), then Canopy gestures (accessibility)
                if (!ShizukuShell.lock(this)) accessibility(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN, "lock the screen");
                break;
            case "recents": accessibility(AccessibilityService.GLOBAL_ACTION_RECENTS, "open recent apps"); break;
            case "power": accessibility(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG, "open the power menu"); break;
            case "settings": openSettings(); break;
            case "pages": openPageEditor(); break;
            default: break;
        }
    }

    private void accessibility(int action, String what) {
        if (CanopyAccessibility.perform(action)) return;
        Ui.dialog(this).setTitle("Turn on Grove gestures")
                .setMessage("To " + what + " from a gesture, Android needs you to enable \u201CGrove gestures\u201D in "
                        + "Accessibility settings. It only performs that action; it can't read your screen.")
                .setPositiveButton("Open settings", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { safeStart(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
                }).setNegativeButton("Cancel", null).show();
    }

    @SuppressWarnings({"WrongConstant", "JavaReflectionMemberAccess"})
    private void statusBar(String method) {
        try {
            Object sbm = getSystemService("statusbar");
            Method m = Class.forName("android.app.StatusBarManager").getMethod(method);
            m.invoke(sbm);
        } catch (Throwable t) {
            Ui.toast(this, "Your phone doesn't allow this from a launcher");
        }
    }

    /** Swipe up: if it started on an icon with a swipe action (Nova), open that; otherwise run the gesture. */
    @Override public void onSwipeUp(float x, float y) {
        if (x >= 0) {
            CellLayout cl = pageLayout(currentHomePage());
            if (cl != null) {
                // Workspace touch coordinates are relative to the visible page; the page starts at the padding.
                float lx = x - workspace.getPaddingLeft(), ly = y - workspace.getPaddingTop();
                for (int i = 0; i < cl.getChildCount(); i++) {
                    View v = cl.getChildAt(i);
                    if (lx >= v.getLeft() && lx < v.getRight() && ly >= v.getTop() && ly < v.getBottom()
                            && v.getTag() instanceof HomeItem && ((HomeItem) v.getTag()).swipe != null) {
                        String[] sw = ((HomeItem) v.getTag()).swipe.split("#");
                        AppInfo a = sw.length == 2 ? repo.find(sw[0], Long.parseLong(sw[1])) : null;
                        if (a != null) { repo.launch(a, v); return; }
                    }
                }
            }
        }
        runAction(Prefs.gesture(this, "g_up"));
    }
    @Override public void onSwipeDown() { runAction(Prefs.gesture(this, "g_down")); }
    @Override public void onTwoFingerSwipe(boolean up) { runAction(Prefs.gesture(this, up ? "g_two_up" : "g_two_down")); }
    @Override public void onDoubleTap() { runAction(Prefs.gesture(this, "g_double")); }
    @Override public void onPinchIn() { runAction(Prefs.gesture(this, "g_pinch")); }
    @Override public void onPinchOut() { runAction(Prefs.gesture(this, "g_spread")); }
    @Override public boolean isDragging() { return dragging; }
    @Override public boolean isGlancePage(int index) { return feed != null && index == 0; }
    private Boolean barHidden;
    private boolean nightAtCreate;

    /** Status bar shown or hidden depending on where you are: feed, app drawer or home screen (Settings → Home screen). */
    private Boolean iconsDark;

    /** Status bar icons follow what is under them: dark icons on a light screen, white on a dark one (home = your wallpaper, feed and drawer = the theme). */
    private void statusIcons(String surface) {
        boolean lightBg = "home".equals(surface) ? Ui.wallpaperIsLight(this) : !Ui.palette(this).night;
        if (iconsDark != null && iconsDark == lightBg) return;
        iconsDark = lightBg;
        android.view.Window w = getWindow();
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController ic = w.getInsetsController();
            if (ic != null) ic.setSystemBarsAppearance(lightBg ? android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS : 0,
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
        } else if (android.os.Build.VERSION.SDK_INT >= 23) {
            //noinspection deprecation
            int f = w.getDecorView().getSystemUiVisibility();
            //noinspection deprecation
            w.getDecorView().setSystemUiVisibility(lightBg ? f | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR : f & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
    }

    void applyStatusBar() {
        String surface = drawer != null && drawer.isOpen() ? "drawer" : feed != null && feedAmt > 0.5f ? "feed" : "home";
        statusIcons(surface);
        boolean hide = Prefs.statusBarHidden(this, surface);
        if (barHidden != null && barHidden == hide) return;
        barHidden = hide;
        android.view.Window w = getWindow();
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController ic = w.getInsetsController();
            if (ic == null) return;
            ic.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            if (hide) ic.hide(android.view.WindowInsets.Type.statusBars()); else ic.show(android.view.WindowInsets.Type.statusBars());
        } else {
            //noinspection deprecation
            int f = w.getDecorView().getSystemUiVisibility();
            //noinspection deprecation
            w.getDecorView().setSystemUiVisibility(hide ? f | View.SYSTEM_UI_FLAG_FULLSCREEN : f & ~View.SYSTEM_UI_FLAG_FULLSCREEN);
        }
    }

    @Override public void onPageScrolled(float pageFloat) {
        indicator.setPosition(pageFloat);
        if (feed == null) return;
        feedAmt = Math.max(0f, Math.min(1f, 1f - pageFloat));
        // a new motivating line each time you arrive on the feed
        if (pageFloat < 0.02f && !feedArrived) { feedArrived = true; feed.onShown(); }
        else if (pageFloat > 0.6f) feedArrived = false;

        applyDepth();
        applyStatusBar();
    }

    // ------------------------------------------------------------------ depth: blur & dim behind feed / drawer / folders

    private View backdrop;
    private float feedAmt, overlayAmt;
    private android.widget.ImageView blurLayer;
    private Boolean windowBlur;

    /** Does this phone blur the wallpaper behind Canopy's window itself? (Many don't, or have it switched off.) */
    private boolean windowBlurWorks() {
        if (windowBlur == null) {
            boolean ok = false;
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                try { ok = getWindowManager().isCrossWindowBlurEnabled(); } catch (Throwable ignored) { }
            }
            windowBlur = ok;
        }
        return windowBlur;
    }

    /** Shows Canopy's own blurred wallpaper while the feed / drawer is open, where the phone can't blur it. */
    private void updateBlurLayer(float amt, boolean blurOn) {
        if (blurLayer == null) return;
        boolean need = blurOn && amt > 0.01f && !windowBlurWorks();
        if (!need) { if (blurLayer.getVisibility() != View.GONE) blurLayer.setVisibility(View.GONE); return; }
        if (blurLayer.getDrawable() == null) { // made once (and again after the wallpaper changes, see onResume)
            android.graphics.Bitmap b = FeedBackground.blurredWallpaper(this);
            if (b == null) { blurLayer.setVisibility(View.GONE); return; }
            blurLayer.setImageBitmap(b);
        }
        blurLayer.setVisibility(View.VISIBLE);
        blurLayer.setAlpha(Math.min(1f, amt));
    }
    private String feedBgMode = "dark";
    private int feedBgColor = 0xFF101418;

    private void loadFeedBg() {
        feedBgMode = Prefs.feedBg(this);
        feedBgColor = Prefs.feedColor(this);
        if ("you".equals(feedBgMode)) feedBgMode = "color"; // Material You: the colour comes from Prefs.feedColor
    }
    private boolean feedShown;
    private int lastWindowBlur = -1, lastViewBlur = -1, lastChromeBlur = -1;
    private android.animation.ValueAnimator overlayAnim;

    /** Animates the "something is open over home" depth effect. */
    public void setOverlay(boolean on) {
        root.postDelayed(new Runnable() { @Override public void run() { try { applyStatusBar(); } catch (Throwable ignored) { } } }, Anim.dur(this, 340));
        if (overlayAnim != null) overlayAnim.cancel();
        overlayAnim = android.animation.ValueAnimator.ofFloat(overlayAmt, on ? 1f : 0f);
        overlayAnim.setDuration(Anim.dur(this, on ? 320 : 240));
        overlayAnim.setInterpolator(new android.view.animation.DecelerateInterpolator());
        overlayAnim.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                overlayAmt = (Float) a.getAnimatedValue();
                applyDepth();
            }
        });
        overlayAnimating = true;
        overlayAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean cancelled;
            @Override public void onAnimationCancel(android.animation.Animator a) { cancelled = true; }
            @Override public void onAnimationEnd(android.animation.Animator a) { if (!cancelled) { overlayAnimating = false; applyDepth(); } }
        });
        overlayAnim.start();
    }
    /** While the drawer / folder slides in or out, the heavy blur of the home screen is skipped (it is applied once the motion ends): that re-render was the stutter. */
    private boolean overlayAnimating;

    private boolean webForFeed; // false at start: the first time the feed opens, tabs are always woken (they outlive this activity, possibly still frozen)

    /** Web tabs only run while the feed is on screen: on the home screen and in the drawer they are frozen (playing media is spared). */
    private void syncWebToFeed() {
        boolean open = feedAmt > 0.02f;
        if (open == webForFeed || webViews.isEmpty()) return;
        webForFeed = open;
        try {
            if (open) wakeWeb();
            else if (!stopped) {
                boolean keep = FeedWeb.background(this), spared = false;
                android.webkit.WebView any = null;
                for (java.util.Map.Entry<String, android.webkit.WebView> e : webViews.entrySet()) {
                    any = e.getValue();
                    if ((keep && isPlaying(e.getKey())) || e.getKey().equals(pipTab)) { spared = true; continue; }
                    e.getValue().onPause();
                }
                if (any != null && !spared) { any.pauseTimers(); webAsleep = true; }
            }
        } catch (Throwable ignored) { }
    }

    private void applyDepth() {
        if (backdrop == null) return;
        syncWebToFeed();
        boolean blurOn = Prefs.sp(this).getBoolean("depth_blur", true);
        float amt = Math.max(feedAmt, overlayAmt);
        // feed background (Settings > Feed): dark blur, white blur or a plain colour
        int back;
        if (feedAmt >= overlayAmt && feedAmt > 0f && "white".equals(feedBgMode)) {
            back = android.graphics.Color.argb((int) (feedAmt * (blurOn ? 0xB8 : 0xE6)), 255, 255, 255);
        } else if (feedAmt >= overlayAmt && feedAmt > 0f && "color".equals(feedBgMode)) {
            back = Ui.alpha(feedBgColor, Math.round(feedAmt * (("you".equals(Prefs.feedBg(this)) ? Prefs.drawerOpacity(this) : 100) * 2.55f)));
        } else {
            back = android.graphics.Color.argb((int) (amt * (blurOn ? 120 : 150)), 0, 0, 0);
        }
        backdrop.setBackgroundColor(back);
        // a plain colour hides the wallpaper anyway: don't spend power blurring it
        if ("color".equals(feedBgMode)) amt = overlayAmt;
        updateBlurLayer(amt, blurOn);
        // Fade the dock / search bar / dots away while the feed is open, so it reads as its own layer.
        float chrome = 1f - feedAmt;
        float drop = feedAmt * Ui.dp(this, 36);
        for (View v : new View[]{dock, searchPill, indicator}) {
            if (v == null) continue;
            v.setAlpha(chrome);
            v.setTranslationY(drop);
        }
        if (android.os.Build.VERSION.SDK_INT < 31) return;
        int maxBlur = Ui.dp(this, 36);
        // Blur the wallpaper behind the window (needs a device with cross-window blur enabled).
        int wb = blurOn ? Math.round(amt * maxBlur) : 0;
        if (Math.abs(wb - lastWindowBlur) >= 6 || (wb == 0 && lastWindowBlur != 0) || (wb == maxBlur && lastWindowBlur != wb)) {
            lastWindowBlur = wb;
            try {
                if (getWindowManager().isCrossWindowBlurEnabled()) getWindow().setBackgroundBlurRadius(wb);
            } catch (Throwable ignored) { }
        }
        // Blur the home screen itself behind the drawer / folders / widget page.
        int vb = blurOn && !overlayAnimating ? Math.round(overlayAmt * 5f) * Ui.dp(this, 22) / 5 : 0; // stepped, so it isn't re-blurred every frame
        if (vb != lastViewBlur) {
            lastViewBlur = vb;
            android.graphics.RenderEffect fx = vb > 0
                    ? android.graphics.RenderEffect.createBlurEffect(vb, vb, android.graphics.Shader.TileMode.CLAMP) : null;
            workspace.setRenderEffect(fx);
            dock.setRenderEffect(fx);
            searchPill.setRenderEffect(fx);
        }
        int cb = blurOn ? Math.round(feedAmt * Ui.dp(this, 16)) : 0;
        if (cb != lastChromeBlur && overlayAmt == 0f) {
            lastChromeBlur = cb;
            android.graphics.RenderEffect fx = cb > 0
                    ? android.graphics.RenderEffect.createBlurEffect(cb, cb, android.graphics.Shader.TileMode.CLAMP) : null;
            dock.setRenderEffect(fx);
            searchPill.setRenderEffect(fx);
        }
    }

    /** Long-press menu > Arrange in grid: choose how this page's icons are tidied. */
    private void arrangeMenu(final int page) {
        String[] opts = {"Fill the gaps (keep order)", "Sort A\u2013Z"};
        new android.app.AlertDialog.Builder(this).setTitle("Arrange in grid").setItems(opts, new android.content.DialogInterface.OnClickListener() {
            @Override public void onClick(android.content.DialogInterface d, int which) { arrangePage(page, which == 1); }
        }).show();
    }

    /** Re-places this page's icons and folders row by row, around widgets and freely placed items. */
    private void arrangePage(int page, final boolean sortAz) {
        int cols = Prefs.gridCols(this), rows = Prefs.gridRows(this);
        boolean[][] busy = new boolean[cols][rows];
        final List<HomeItem> move = new ArrayList<>();
        for (HomeItem it : store.items()) {
            if (it.container != HomeItem.DESKTOP || it.page != page) continue;
            if (!it.isFree() && it.spanX == 1 && it.spanY == 1 && it.type != HomeItem.WIDGET) { move.add(it); continue; }
            if (it.isFree()) continue;
            for (int x = it.x; x < Math.min(cols, it.x + it.spanX); x++)
                for (int y = it.y; y < Math.min(rows, it.y + it.spanY); y++) busy[x][y] = true;
        }
        if (sortAz) {
            java.util.Collections.sort(move, new java.util.Comparator<HomeItem>() {
                @Override public int compare(HomeItem a, HomeItem b) { return String.valueOf(labelFor(a)).compareToIgnoreCase(String.valueOf(labelFor(b))); }
            });
        } else {
            java.util.Collections.sort(move, new java.util.Comparator<HomeItem>() {
                @Override public int compare(HomeItem a, HomeItem b) { return a.y != b.y ? a.y - b.y : a.x - b.x; }
            });
        }
        int i = 0;
        for (int y = 0; y < rows && i < move.size(); y++)
            for (int x = 0; x < cols && i < move.size(); x++) {
                if (busy[x][y]) continue;
                HomeItem it = move.get(i++);
                it.x = x; it.y = y;
            }
        store.commit();
        bindAll();
    }

    @Override public void onEmptyLongPress(float rawX, float rawY) {
        LauncherWidgetView ct = clickThroughAt(rawX, rawY);
        if (ct != null) { onWidgetLongPress(ct); return; }
        final int page = currentHomePage();
        List<MenuEntry> items = new ArrayList<>();
        items.add(new MenuEntry(R.drawable.ic_wallpaper, "Wallpaper", new Runnable() {
            @Override public void run() {
                try {
                    startActivity(Intent.createChooser(new Intent(Intent.ACTION_SET_WALLPAPER), "Choose wallpaper"));
                } catch (Exception e) { Ui.toast(LauncherActivity.this, "No wallpaper picker found"); }
            }
        }));
        items.add(new MenuEntry(R.drawable.ic_widgets, "Widgets", new Runnable() {
            @Override public void run() { showWidgetPicker(false); }
        }));
        items.add(new MenuEntry(R.drawable.ic_shortcut, "App shortcut", new Runnable() {
            @Override public void run() { pickAppShortcut(); }
        }));
        if (page != mainHomePage()) {
            items.add(new MenuEntry(R.drawable.ic_check, "Set as main home page", new Runnable() {
                @Override public void run() {
                    Prefs.sp(LauncherActivity.this).edit().putInt("main_page", page).apply();
                    indicator.setup(workspace.getChildCount(), feed != null, defaultPage());
                    Ui.toast(LauncherActivity.this, "Home will now return to this page");
                }
            }));
        }
        items.add(new MenuEntry(R.drawable.ic_apps, "Arrange in grid", new Runnable() {
            @Override public void run() { arrangeMenu(page); }
        }));
        items.add(new MenuEntry(R.drawable.ic_add, "Add page", new Runnable() {
            @Override public void run() { store.addPage(); bindAll(); workspace.snapToPage(store.pageCount() - 1 + glanceOffset()); }
        }));
        if (store.pageCount() > 1 && store.pageEmpty(page)) {
            items.add(new MenuEntry(R.drawable.ic_delete, "Remove this page", new Runnable() {
                @Override public void run() {
                    int main = mainHomePage();
                    store.removePage(page);
                    if (main >= page && main > 0) Prefs.sp(LauncherActivity.this).edit().putInt("main_page", main - 1).apply();
                    bindAll();
                }
            }));
        }
        final boolean barHidden = "none".equals(Prefs.searchBar(this));
        items.add(new MenuEntry(R.drawable.ic_search, barHidden ? "Show search bar" : "Hide search bar", new Runnable() {
            @Override public void run() {
                Prefs.sp(LauncherActivity.this).edit().putString("search_bar", barHidden ? "bottom" : "none").apply();
            }
        }));
        items.add(new MenuEntry(R.drawable.ic_settings, "Grove settings", new Runnable() {
            @Override public void run() { openSettings(); }
        }));
        showMenuAt((int) rawX, (int) rawY, items);
    }

    // ------------------------------------------------------------------ popup menus

    static class MenuEntry {
        final int icon; final Drawable iconDrawable; final String label; final Runnable action;
        Runnable secondary; // optional "+" button (e.g. add shortcut to home screen)
        View custom;        // optional: a whole custom row (e.g. the app's widgets strip)
        boolean wrap;       // custom row keeps its own height (e.g. the notifications card)
        static MenuEntry view(View v) { MenuEntry m = new MenuEntry(0, "", null); m.custom = v; return m; }
        MenuEntry(int icon, String label, Runnable action) { this.icon = icon; this.iconDrawable = null; this.label = label; this.action = action; }
        MenuEntry(Drawable d, String label, Runnable action) { this.icon = 0; this.iconDrawable = d; this.label = label; this.action = action; }
    }

    private View buildMenu(List<MenuEntry> entries) { return buildMenu(entries, false); }

    /** compact: app options (info, edit, hide, uninstall…) become a row of icon buttons inside one pill. */
    private View buildMenu(List<MenuEntry> entries, boolean compact) {
        Ui.Palette pal = Ui.palette(this);
        // Android 12-style menu: every option is its own pill, stacked with small gaps; app shortcuts,
        // widgets and app options form separate groups.
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipToPadding(false);
        box.setClipChildren(false);
        int pad = Ui.dp(this, 4);
        box.setPadding(pad, pad, pad, pad);
        int gap = Ui.dp(this, 3), groupGap = Ui.dp(this, 9);
        int lastGroup = -1;
        List<MenuEntry> iconOnly = new ArrayList<>();
        for (final MenuEntry e : entries) {
            if (compact && e.custom == null && e.secondary == null && e.iconDrawable == null && e.icon != 0) {
                iconOnly.add(e);
                continue;
            }
            int group = e.custom != null ? 1 : e.secondary != null ? 0 : 2;
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, -2);
            if (box.getChildCount() > 0) plp.topMargin = group != lastGroup ? groupGap : gap;
            lastGroup = group;
            if (e.custom != null) {
                // the same entry list is built twice (measure, then show): detach from the first build
                if (e.custom.getParent() != null) ((ViewGroup) e.custom.getParent()).removeView(e.custom);
                if (e.custom.getBackground() == null) {
                    e.custom.setBackground(Ui.round(pal.surface, Ui.dp(this, PILL_DP / 2f)));
                    e.custom.setElevation(Ui.dp(this, 2));
                }
                plp.height = e.wrap ? -2 : Ui.dp(this, PILL_DP);
                box.addView(e.custom, plp);
                continue;
            }
            LinearLayout row = menuPillRow(pal);
            ImageView iv = new ImageView(this);
            if (e.iconDrawable != null) iv.setImageDrawable(e.iconDrawable);
            else if (e.icon != 0) iv.setImageDrawable(Ui.tinted(this, e.icon, pal.text));
            row.addView(iv, new LinearLayout.LayoutParams(Ui.dp(this, 20), Ui.dp(this, 20)));
            TextView t = Ui.text(this, e.label, 14, pal.text);
            t.setPadding(Ui.dp(this, 12), 0, 0, 0);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
            if (e.secondary != null) {
                ImageView plus = Ui.iconButton(this, R.drawable.ic_add, pal.accent, new View.OnClickListener() {
                    @Override public void onClick(View v) { dismissPopup(); e.secondary.run(); }
                });
                plus.setContentDescription("Add to home screen");
                row.addView(plus, new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36)));
                row.setPadding(row.getPaddingLeft(), 0, Ui.dp(this, 6), 0);
            }
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { dismissPopup(); e.action.run(); }
            });
            plp.height = Ui.dp(this, PILL_DP);
            box.addView(row, plp);
        }
        if (!iconOnly.isEmpty()) {
            // Options: small icons side by side (long-press one to see its name). Each row of up to 6 is its own pill.
            int perRow = 5, btn = Ui.dp(this, 36), ip = Ui.dp(this, 9);
            for (int i = 0; i < iconOnly.size(); i += perRow) {
                LinearLayout pill = new LinearLayout(this);
                pill.setGravity(Gravity.CENTER);
                pill.setBackground(Ui.round(pal.surface, Ui.dp(this, PILL_DP / 2f)));
                pill.setElevation(Ui.dp(this, 2));
                pill.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 6), 0);
                for (int j = i; j < Math.min(iconOnly.size(), i + perRow); j++) {
                    final MenuEntry e = iconOnly.get(j);
                    ImageView b = Ui.iconButton(this, e.icon, pal.text, new View.OnClickListener() {
                        @Override public void onClick(View v) { dismissPopup(); e.action.run(); }
                    });
                    b.setContentDescription(e.label);
                    b.setTooltipText(e.label);
                    b.setPadding(ip, ip, ip, ip);
                    b.setBackground(new android.graphics.drawable.RippleDrawable(
                            android.content.res.ColorStateList.valueOf(0x33888888), null, null));
                    LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(btn, btn);
                    blp.leftMargin = blp.rightMargin = Ui.dp(this, 1);
                    pill.addView(b, blp);
                }
                // same width and height as every other pill; icons centred inside
                LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, Ui.dp(this, PILL_DP));
                if (box.getChildCount() > 0) plp.topMargin = i == 0 ? groupGap : gap;
                box.addView(pill, plp);
            }
        }
        return box;
    }

    private void showMenuAt(int x, int y, List<MenuEntry> entries) { showMenu(entries, false, x, y, y); }

    /** Menu for an icon or widget: above it when there's room (Android 12 style), otherwise below. */
    private void showMenuFor(View anchor, List<MenuEntry> entries) {
        int[] loc = new int[2];
        anchor.getLocationInWindow(loc);
        showMenu(entries, true, loc[0] + anchor.getWidth() / 2, loc[1], loc[1] + anchor.getHeight());
    }

    private void showMenu(List<MenuEntry> entries, boolean compact, int x, int anchorTop, int anchorBottom) {
        dismissPopup();
        View content = buildMenu(entries, compact);
        content.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int maxW = (root.getWidth() > 0 ? root.getWidth() : getResources().getDisplayMetrics().widthPixels) - Ui.dp(this, 16);
        int w = Math.min(maxW, Ui.dp(this, MENU_DP));
        // measure once at the real width; this height decides above/below
        content.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED);
        int h = content.getMeasuredHeight();
        int[] rl = new int[2];
        root.getLocationInWindow(rl);
        x -= rl[0];
        anchorTop -= rl[1];
        anchorBottom -= rl[1];
        int sw = root.getWidth(), sh = root.getHeight(), m = Ui.dp(this, 8);
        int px = Math.max(m, Math.min(sw - w - m, x - w / 2));
        int above = anchorTop - m - (insetTop + m), below = (sh - insetBottom - m) - (anchorBottom + m);
        int py;
        boolean up;
        // Too long for either side: the menu scrolls instead of running off screen.
        if (h > above && h > below) {
            int room = Math.max(above, below);
            android.widget.ScrollView sv = new android.widget.ScrollView(this);
            sv.setVerticalScrollBarEnabled(false);
            sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
            sv.setClipToPadding(false);
            sv.addView(content, new FrameLayout.LayoutParams(-1, -2));
            content = sv;
            h = room;
        }
        if (h <= above) { up = true; py = anchorTop - m - h; }
        else if (h <= below) { up = false; py = anchorBottom + m; }
        else { up = above >= below; py = up ? insetTop + m : Math.max(insetTop + m, sh - insetBottom - m - h); }
        // A touch anywhere outside the menu closes it and still reaches whatever is underneath.
        FrameLayout layer = new FrameLayout(this) {
            @Override public boolean onTouchEvent(MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) dismissPopup();
                return false;
            }
        };
        content.setClickable(true);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(w, h);
        clp.leftMargin = px;
        clp.topMargin = py;
        layer.addView(content, clp);
        root.addView(layer, new FrameLayout.LayoutParams(-1, -1));
        popup = layer;
        content.setPivotX(Math.max(0, Math.min(w, x - px)));
        content.setPivotY(up ? h : 0);
        Anim.popIn(content);
    }

    private void dismissPopup() {
        if (popup != null) {
            final FrameLayout p = popup;
            popup = null;
            // Removed on the next frame: this may be called from inside the menu's own touch handling.
            root.post(new Runnable() { @Override public void run() { root.removeView(p); } });
        }
    }

    @Override public void onIconLongPress(final IconView v) {
        Object tag = v.getTag();
        boolean inDrawer = v.style == IconView.STYLE_DRAWER && drawer.isOpen();
        if (inDrawer && drawer.locked() && !drawer.isSelecting()) {
            // Locked drawer layout: only the app's shortcuts and widgets, nothing that changes the drawer.
            if (!(tag instanceof AppInfo)) return;
            AppInfo la = (AppInfo) tag;
            List<MenuEntry> locked = new ArrayList<>();
            addShortcutEntries(la, v, locked);
            if (locked.isEmpty()) { Ui.toast(this, la.label + " has no shortcuts"); return; }
            showMenuFor(v, locked);
            return;
        }
        if (tag instanceof DrawerGroups.Group) { drawer.groupMenu((DrawerGroups.Group) tag, false); return; }
        if (drawer.isSelecting() && v.style == IconView.STYLE_DRAWER && drawer.isOpen()) { drawer.toggleSelect(tag); return; }
        if (tag instanceof DrawerShortcuts.Entry) { drawerShortcutMenu((DrawerShortcuts.Entry) tag, v); return; }
        final HomeItem item = tag instanceof HomeItem ? (HomeItem) tag : null;
        final AppInfo app = tag instanceof AppInfo ? (AppInfo) tag
                : (item != null && item.type == HomeItem.APP ? repo.find(item.component, item.serial) : null);
        List<MenuEntry> entries = new ArrayList<>();
        if (app != null) addNotificationEntries(app, entries);
        if (app != null) addShortcutEntries(app, v, entries);
        if (app != null && !Prefs.desktopLocked(this)) {
            entries.add(new MenuEntry(R.drawable.ic_edit, "Edit icon & name", new Runnable() {
                @Override public void run() { IconEditor.show(LauncherActivity.this, app); }
            }));
        }
        if (app != null) {
            entries.add(new MenuEntry(R.drawable.ic_info, "App info", new Runnable() {
                @Override public void run() { appInfo(app.component, app.user); }
            }));
        }
        if (item == null && app != null) {
            entries.add(new MenuEntry(R.drawable.ic_home, "Add to home screen", new Runnable() {
                @Override public void run() { addAppToHome(app); }
            }));
            if (v.style == IconView.STYLE_DRAWER && drawer.isOpen() && v.folderParent == null) {
                entries.add(new MenuEntry(R.drawable.ic_check, "Select multiple apps", new Runnable() {
                    @Override public void run() { drawer.startSelection(app); }
                }));
                entries.add(new MenuEntry(R.drawable.ic_widgets, "Add widget here", new Runnable() {
                    @Override public void run() { showDrawerWidgetPicker(app.key); }
                }));
            }
            final DrawerGroups.Group inTab = v.style == IconView.STYLE_DRAWER && drawer.isOpen() ? drawer.currentCustomTab() : null;
            if (inTab != null) {
                entries.add(new MenuEntry(R.drawable.ic_close, "Remove from \u201C" + inTab.name + "\u201D", new Runnable() {
                    @Override public void run() { drawer.removeFromCurrentTab(java.util.Collections.singletonList(app)); }
                }));
            }
            final boolean fav = Prefs.drawerFavs(this).contains(app.key);
            entries.add(new MenuEntry(fav ? R.drawable.ic_bookmark : R.drawable.ic_bookmark_border, fav ? "Remove from favourites" : "Add to favourites", new Runnable() {
                @Override public void run() { Prefs.setDrawerFav(LauncherActivity.this, app.key, !fav); }
            }));
            entries.add(new MenuEntry(R.drawable.ic_apps, "Add to drawer tab\u2026", new Runnable() {
                @Override public void run() { drawer.addToGroup(app, true); }
            }));
            if (v.folderParent == FolderView.DRAWER_MARK) {
                entries.add(new MenuEntry(R.drawable.ic_folder, "Remove from this folder", new Runnable() {
                    @Override public void run() { closeFolder(); drawer.removeFromFolder(app); }
                }));
            } else {
                entries.add(new MenuEntry(R.drawable.ic_folder, "Move to drawer folder\u2026", new Runnable() {
                    @Override public void run() { drawer.addToGroup(app, false); }
                }));
            }
            entries.add(new MenuEntry(R.drawable.ic_hide, "Hide from drawer", new Runnable() {
                @Override public void run() {
                    Set<String> h = Prefs.hidden(LauncherActivity.this);
                    h.add(app.key);
                    Prefs.setHidden(LauncherActivity.this, h);
                    Ui.toast(LauncherActivity.this, app.label + " hidden. Unhide it in Grove settings.");
                }
            }));
        }
        if (item != null && !Prefs.desktopLocked(this)) {
            final View src = v;
            if (item.type != HomeItem.APP) entries.add(new MenuEntry(R.drawable.ic_edit, item.type == HomeItem.FOLDER ? "Edit folder name" : "Edit name", new Runnable() {
                @Override public void run() { rename(item, src); }
            }));
            if (v.folderParent == null && item.container == HomeItem.DESKTOP) {
                entries.add(new MenuEntry(R.drawable.ic_expand, item.swipe == null ? "Swipe-up action\u2026" : "Swipe-up: " + swipeLabel(item), new Runnable() {
                    @Override public void run() { pickSwipeAction(item); }
                }));
            }
            if (v.folderParent == null && item.container == HomeItem.DESKTOP && freeIcons()) {
                entries.add(new MenuEntry(R.drawable.ic_resize, "Move & resize freely", new Runnable() {
                    @Override public void run() { resizeIcon(item, v); }
                }));
                if (item.isFree()) entries.add(new MenuEntry(R.drawable.ic_home, "Snap back to grid", new Runnable() {
                    @Override public void run() {
                        item.fx = item.fy = item.fw = item.fh = Float.NaN;
                        item.spanX = item.spanY = 1;
                        CellLayout cl = pageLayout(item.page);
                        int[] f = cl == null ? null : store.findFreeNear(HomeItem.DESKTOP, item.page, item.x, item.y, 1, 1, item, cl.cols, cl.rows);
                        if (f != null) { item.x = f[0]; item.y = f[1]; }
                        store.commit();
                        requestBind();
                    }
                }));
            }
            if (item.type == HomeItem.FOLDER) entries.add(new MenuEntry(R.drawable.ic_palette, "Folder colours", new Runnable() {
                @Override public void run() { folderColours(); }
            }));
            entries.add(new MenuEntry(R.drawable.ic_close, "Remove", new Runnable() {
                @Override public void run() { removeItem(item); }
            }));
        }
        if (app != null && !isSystemApp(app.pkg())) {
            entries.add(new MenuEntry(R.drawable.ic_delete, "Uninstall", new Runnable() {
                @Override public void run() { uninstall(app.pkg(), app.user); }
            }));
        }
        if (entries.isEmpty()) return;
        showMenuFor(v, entries);
    }

    /** Folder colours (all folders): icon colour, open-folder background, or back to default. */
    void folderColours() {
        final Context c = this;
        Ui.dialog(c).setTitle("Folder colours").setItems(new String[]{"Folder icon colour\u2026", "Open folder background\u2026", "Back to default colours"},
                new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        if (w == 0) FeedBackground.pickColor(c, "Folder icon colour", "folder_icon_color",
                                Prefs.folderIconColor(c) == 0 ? Ui.palette(c).accent : Prefs.folderIconColor(c), null);
                        else if (w == 1) FeedBackground.pickColor(c, "Open folder background", "folder_bg_color",
                                Prefs.folderBgColor(c) == 0 ? Ui.palette(c).surface : Prefs.folderBgColor(c), null);
                        else Prefs.sp(c).edit().remove("folder_icon_color").remove("folder_bg_color").remove("folder_bg_alpha").apply();
                    }
                }).show();
    }

    private String swipeLabel(HomeItem it) {
        String[] sw = it.swipe.split("#");
        AppInfo a = sw.length == 2 ? repo.find(sw[0], Long.parseLong(sw[1])) : null;
        return a != null ? a.label : "app";
    }

    /** Nova "swipe actions": swipe up on this icon to open a second app. */
    private void pickSwipeAction(final HomeItem item) {
        final List<AppInfo> apps = repo.visible();
        String[] labels = new String[apps.size() + 1];
        labels[0] = "None";
        for (int i = 0; i < apps.size(); i++) labels[i + 1] = apps.get(i).label;
        Ui.dialog(this).setTitle("Swipe up on \u201C" + labelFor(item) + "\u201D opens\u2026")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        item.swipe = w == 0 ? null : apps.get(w - 1).component.flattenToString() + "#" + apps.get(w - 1).serial;
                        store.commit();
                        Ui.toast(LauncherActivity.this, w == 0 ? "Swipe action removed" : "Swipe up on the icon to open " + apps.get(w - 1).label);
                    }
                }).show();
    }

    /** Standard menu pill: same height, padding and look everywhere in the app. */
    static final int PILL_DP = 44;
    /** Compact menu width, the same for every app. */
    static final int MENU_DP = 232;

    private LinearLayout menuPillRow(Ui.Palette pal) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int p = Ui.dp(this, 16);
        row.setPadding(p, 0, p + Ui.dp(this, 4), 0);
        row.setMinimumHeight(Ui.dp(this, PILL_DP));
        row.setBackground(Ui.pressable(this, pal.surface, Ui.dp(this, PILL_DP / 2f)));
        row.setElevation(Ui.dp(this, 2));
        return row;
    }

    /** Drag a brand-new widget (from the widget page or an app's menu) onto a home screen page. */
    public void startWidgetDrag(View card, AppWidgetProviderInfo info) {
        if (Prefs.desktopLocked(this)) {
            Ui.toast(this, "Desktop is locked \u2014 unlock it in Grove settings");
            return;
        }
        DragInfo di = new DragInfo();
        di.widgetInfo = info;
        boolean ok;
        try {
            ok = card.startDragAndDrop(ClipData.newPlainText("canopy", "widget"), new View.DragShadowBuilder(card), di, 0);
        } catch (Exception e) {
            ok = false;
        }
        if (!ok) { Ui.toast(this, "Couldn't start the drag \u2014 tap the widget to add it instead"); return; }
        if (Anim.haptics(this)) card.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        dragging = true;
        currentDrag = di;
        if (popup != null) {
            popup.setVisibility(View.INVISIBLE);
            dismissPopup();
        }
        if (drawer.isOpen()) drawer.close(false);
        if (widgetPicker != null) widgetPicker.close();
        if (workspace.getCurrentPage() < glanceOffset()) workspace.snapToPage(defaultPage());
    }

    /** The app's notifications at the top of its long-press menu (tap one to open it). */
    private void addNotificationEntries(AppInfo app, List<MenuEntry> out) {
        if (!Prefs.sp(this).getBoolean("menu_notifications", true)) return;
        List<Badges.Note> notes = Badges.notes(app.pkg());
        if (notes.isEmpty()) return;
        Ui.Palette pal = Ui.palette(this);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.round(pal.surface, Ui.dp(this, 22)));
        card.setElevation(Ui.dp(this, 2));
        card.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 6));
        TextView head = Ui.text(this, "Notifications \u00B7 " + notes.size(), 12, pal.accent);
        head.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 4));
        card.addView(head);
        for (int i = 0; i < notes.size() && i < 3; i++) {
            final Badges.Note n = notes.get(i);
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.VERTICAL);
            r.setPadding(Ui.dp(this, 16), Ui.dp(this, 6), Ui.dp(this, 16), Ui.dp(this, 6));
            r.setBackground(Ui.pressable(this, 0x00000000, Ui.dp(this, 16)));
            if (!n.title.isEmpty()) {
                TextView t = Ui.text(this, n.title, 14, pal.text);
                t.setSingleLine(true);
                t.setEllipsize(android.text.TextUtils.TruncateAt.END);
                t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                r.addView(t);
            }
            if (!n.text.isEmpty()) {
                TextView x = Ui.text(this, n.text, 13, pal.subtext);
                x.setMaxLines(2);
                x.setEllipsize(android.text.TextUtils.TruncateAt.END);
                r.addView(x);
            }
            r.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    dismissPopup();
                    if (n.open != null) {
                        try {
                            if (android.os.Build.VERSION.SDK_INT >= 34) {
                                android.app.ActivityOptions o = android.app.ActivityOptions.makeBasic();
                                o.setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                                n.open.send(LauncherActivity.this, 0, null, null, null, null, o.toBundle());
                            } else n.open.send();
                        } catch (Exception e) { Ui.toast(LauncherActivity.this, "Couldn't open that notification"); }
                    }
                    if (n.autoCancel) BadgeService.dismiss(n.key);
                }
            });
            card.addView(r, new LinearLayout.LayoutParams(-1, -2));
        }
        if (notes.size() > 3) {
            TextView more = Ui.text(this, "+" + (notes.size() - 3) + " more", 12, pal.subtext);
            more.setPadding(Ui.dp(this, 16), Ui.dp(this, 2), 0, Ui.dp(this, 4));
            card.addView(more);
        }
        MenuEntry m = MenuEntry.view(card);
        m.wrap = true;
        out.add(m);
    }

    private void addShortcutEntries(AppInfo app, View anchor, List<MenuEntry> out) {
        final LauncherApps la = repo.launcherApps();
        try {
            if (!la.hasShortcutHostPermission()) return;
            LauncherApps.ShortcutQuery q = new LauncherApps.ShortcutQuery();
            q.setPackage(app.pkg());
            q.setActivity(app.component);
            q.setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC | LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST);
            List<ShortcutInfo> list = la.getShortcuts(q, app.user);
            if (list == null) return;
            int density = getResources().getDisplayMetrics().densityDpi;
            int n = 0;
            for (final ShortcutInfo si : list) {
                if (n++ >= 4) break;
                Drawable d = null;
                try { d = la.getShortcutIconDrawable(si, density); } catch (Exception ignored) { }
                if (d == null) d = Ui.tinted(this, R.drawable.ic_shortcut, Ui.palette(this).text);
                CharSequence label = si.getShortLabel() != null ? si.getShortLabel() : si.getLongLabel();
                final UserHandle user = app.user;
                MenuEntry me = new MenuEntry(d, String.valueOf(label), new Runnable() {
                    @Override public void run() {
                        try { la.startShortcut(si.getPackage(), si.getId(), null, null, user); }
                        catch (Exception e) { Ui.toast(LauncherActivity.this, "Can't open shortcut"); }
                    }
                });
                me.secondary = new Runnable() { @Override public void run() { pinShortcutToHome(si, user); } };
                out.add(me);
            }
        } catch (Exception ignored) { }
    }

    /** Puts an app shortcut (e.g. "New message") on the home screen and/or in the app drawer. */
    public void pinShortcutToHome(final ShortcutInfo si, final UserHandle user) {
        Ui.dialog(this).setTitle("Add shortcut to\u2026")
                .setItems(new String[]{"Home screen", "App drawer", "Both"}, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { placeShortcut(si, user, w != 1, w != 0); }
                }).show();
    }

    private void placeShortcut(ShortcutInfo si, UserHandle user, boolean home, boolean drawerToo) {
        if (home && Prefs.desktopLocked(this)) { Ui.toast(this, "Desktop is locked \u2014 unlock it in Grove settings"); return; }
        LauncherApps la = repo.launcherApps();
        try {
            LauncherApps.ShortcutQuery q = new LauncherApps.ShortcutQuery();
            q.setPackage(si.getPackage());
            q.setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED);
            List<String> ids = new ArrayList<>();
            List<ShortcutInfo> pinned = la.getShortcuts(q, user);
            if (pinned != null) for (ShortcutInfo p : pinned) ids.add(p.getId());
            if (!ids.contains(si.getId())) ids.add(si.getId());
            la.pinShortcuts(si.getPackage(), ids, user);
        } catch (Exception ignored) { }
        HomeItem it;
        try { it = AddShortcutActivity.prepare(this, si); } catch (Exception e) { Ui.toast(this, "Couldn't add shortcut"); return; }
        shortcutIcons.remove(it.pkg + "/" + it.shortcutId);
        if (drawerToo) DrawerShortcuts.add(this, it);
        if (home) {
            int page = AddShortcutActivity.addToHome(this, it);
            if (drawer.isOpen()) drawer.close(true);
            bindAll();
            workspace.snapToPage(page + glanceOffset());
        }
        Ui.toast(this, "Added \u201C" + it.title + "\u201D" + (home && drawerToo ? " to home and drawer" : home ? " to home screen" : " to app drawer"));
    }

    public void launchDrawerShortcut(DrawerShortcuts.Entry e, View v) {
        startShortcut(e.asHomeItem(), v);
        if (drawer.isOpen()) root.postDelayed(new Runnable() { @Override public void run() { drawer.close(false); } }, 350);
    }

    public void drawerShortcutMenu(final DrawerShortcuts.Entry e, IconView v) {
        List<MenuEntry> entries = new ArrayList<>();
        entries.add(new MenuEntry(R.drawable.ic_home, "Add to home screen", new Runnable() {
            @Override public void run() {
                HomeItem it = e.asHomeItem();
                int page = AddShortcutActivity.addToHome(LauncherActivity.this, it);
                drawer.close(true);
                bindAll();
                workspace.snapToPage(page + glanceOffset());
            }
        }));
        entries.add(new MenuEntry(R.drawable.ic_close, "Remove from drawer", new Runnable() {
            @Override public void run() { DrawerShortcuts.remove(LauncherActivity.this, e); }
        }));
        showMenuFor(v, entries);
    }

    /** Home menu \u2192 "App shortcut": choose an app, then one of its shortcuts. */
    private void pickAppShortcut() {
        final LauncherApps la = repo.launcherApps();
        if (!la.hasShortcutHostPermission()) {
            Ui.toast(this, "Make Grove your default home app to use app shortcuts");
            return;
        }
        final List<AppInfo> withShortcuts = new ArrayList<>();
        final List<List<ShortcutInfo>> lists = new ArrayList<>();
        for (AppInfo a : repo.visible()) {
            try {
                LauncherApps.ShortcutQuery q = new LauncherApps.ShortcutQuery();
                q.setPackage(a.pkg());
                q.setActivity(a.component);
                q.setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC | LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST);
                List<ShortcutInfo> l = la.getShortcuts(q, a.user);
                if (l != null && !l.isEmpty()) { withShortcuts.add(a); lists.add(l); }
            } catch (Exception ignored) { }
        }
        if (withShortcuts.isEmpty()) { Ui.toast(this, "None of your apps offer shortcuts"); return; }
        String[] names = new String[withShortcuts.size()];
        for (int i = 0; i < names.length; i++) names[i] = withShortcuts.get(i).label + "  (" + lists.get(i).size() + ")";
        Ui.dialog(this).setTitle("Choose an app").setItems(names, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) {
                final AppInfo app = withShortcuts.get(w);
                final List<ShortcutInfo> l = lists.get(w);
                String[] labels = new String[l.size()];
                for (int i = 0; i < labels.length; i++) {
                    CharSequence cs = l.get(i).getLongLabel() != null ? l.get(i).getLongLabel() : l.get(i).getShortLabel();
                    labels[i] = String.valueOf(cs);
                }
                Ui.dialog(LauncherActivity.this).setTitle(app.label + " shortcuts").setItems(labels, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d2, int w2) { pinShortcutToHome(l.get(w2), app.user); }
                }).show();
            }
        }).show();
    }

    private boolean isSystemApp(String pkg) {
        try {
            ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
            return (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0 && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void appInfo(ComponentName cn, UserHandle user) {
        try { repo.launcherApps().startAppDetailsActivity(cn, user, null, null); }
        catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", cn.getPackageName(), null)));
        }
    }

    private void uninstall(String pkg, UserHandle user) {
        Intent i = new Intent(Intent.ACTION_DELETE, Uri.fromParts("package", pkg, null));
        i.putExtra(Intent.EXTRA_USER, user);
        try { startActivity(i); } catch (Exception e) { Ui.toast(this, "Can't uninstall this app"); }
    }

    /** Same design as "Edit icon & name": the icon on top, the name under it. */
    private void rename(final HomeItem item, View src) {
        Drawable preview = item.type == HomeItem.FOLDER ? IconEditor.snapshot(src) : iconFor(item);
        if (preview == null) preview = iconFor(item);
        IconEditor.nameDialog(this, item.type == HomeItem.FOLDER ? "Edit folder" : "Edit shortcut", preview, labelFor(item),
                item.type == HomeItem.FOLDER ? "Folder" : null, new IconEditor.Named() {
                    @Override public void save(String t) {
                        item.title = t.isEmpty() ? null : t;
                        store.commit();
                        bindAll();
                    }
                });
    }

    private void removeItem(HomeItem item) {
        if (item.type == HomeItem.WIDGET) {
            try { widgetHost.deleteAppWidgetId(item.widgetId); } catch (Exception ignored) { }
            widgetViews.remove(item.widgetId);
        }
        store.detach(item);
        store.commit();
        requestBind();
    }

    private void addAppToHome(AppInfo a) {
        int cols = Prefs.gridCols(this), rows = Prefs.gridRows(this);
        for (int i = 0; i < store.pageCount(); i++) {
            int page = (currentHomePage() + i) % store.pageCount();
            int[] f = store.findFirstFree(HomeItem.DESKTOP, page, 1, 1, cols, rows);
            if (f == null) continue;
            HomeItem it = HomeItem.app(store.newId(), a);
            it.page = page;
            it.x = f[0];
            it.y = f[1];
            store.addTop(it);
            store.commit();
            drawer.close(true);
            bindAll();
            workspace.snapToPage(page + glanceOffset());
            return;
        }
        Ui.toast(this, "No room on your home screens — add a page first");
    }

    // ------------------------------------------------------------------ folders

    public void openDrawerFolder(DrawerGroups.Group g) {
        closeFolder();
        folderView = new FolderView(this, g);
        root.addView(folderView, new FrameLayout.LayoutParams(-1, -1));
        if (!drawer.isOpen()) setOverlay(true);
    }

    private void openFolder(HomeItem folder) {
        closeFolder();
        folderView = new FolderView(this, folder);
        root.addView(folderView, new FrameLayout.LayoutParams(-1, -1));
        setOverlay(true);
    }

    public void closeFolder() {
        if (folderView == null) return;
        FolderView fv = folderView;
        folderView = null;
        String t = fv.currentTitle();
        if (fv.drawerGroup != null) {
            if (!t.equals(fv.drawerGroup.name)) drawer.renameGroup(fv.drawerGroup.id, false, t);
        } else if (!t.equals(fv.folder.title)) {
            fv.folder.title = t;
            store.commit();
            requestBind();
        }
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(root.getWindowToken(), 0);
        root.removeView(fv);
        if (!drawer.isOpen() && widgetPicker == null) setOverlay(false);
    }

    // ------------------------------------------------------------------ drag and drop

    @Override public void onIconDragStart(IconView v) {
        if (drawer.isSelecting()) return;
        Object tag = v.getTag();
        DragInfo di = new DragInfo();
        if (tag instanceof AppInfo) di.app = (AppInfo) tag;
        else if (tag instanceof HomeItem) di.item = (HomeItem) tag;
        else return;
        di.folder = v.folderParent == FolderView.DRAWER_MARK ? null : v.folderParent;
        startDrag(v, di);
    }

    @Override public void onWidgetLongPress(final LauncherWidgetView v) {
        final HomeItem item = (HomeItem) v.getTag();
        if (Prefs.desktopLocked(this)) { Ui.toast(this, "Desktop is locked \u2014 unlock it in Grove settings"); return; }
        List<MenuEntry> entries = new ArrayList<>();
        entries.add(new MenuEntry(R.drawable.ic_resize, "Resize", new Runnable() {
            @Override public void run() { resizeWidget(item); }
        }));
        entries.add(new MenuEntry(R.drawable.ic_crop, "Crop", new Runnable() {
            @Override public void run() { cropWidget(item, v); }
        }));
        if (item.container == HomeItem.DESKTOP && !item.isFree()) {
            entries.add(new MenuEntry(R.drawable.ic_resize, item.edge ? "Back inside the margins" : "Stretch to screen edge", new Runnable() {
                @Override public void run() {
                    item.edge = !item.edge;
                    store.commit();
                    bindAll();
                    if (item.edge && item.x != 0 && item.x + item.spanX < Prefs.gridCols(LauncherActivity.this))
                        Ui.toast(LauncherActivity.this, "It reaches the edge once it touches the left or right side \u2014 resize or move it there");
                }
            }));
        }
        if (item.container == HomeItem.DESKTOP) {
            entries.add(new MenuEntry(R.drawable.ic_layers, item.clickThrough ? "Turn off click-through" : "Click-through (touches pass to what's behind)", new Runnable() {
                @Override public void run() {
                    item.clickThrough = !item.clickThrough;
                    v.clickThrough = item.clickThrough;
                    store.commit();
                    Ui.toast(LauncherActivity.this, item.clickThrough
                            ? "Touches now go through this widget. Long-press it to change this."
                            : "This widget responds to touches again");
                }
            }));
        }
        entries.add(new MenuEntry(R.drawable.ic_close, "Remove", new Runnable() {
            @Override public void run() { removeItem(item); }
        }));
        showMenuFor(v, entries);
    }

    /** Drag the handles to hide or bring back the sides of a widget, like resizing but the content stays where it is. */
    private void cropWidget(final HomeItem item, final LauncherWidgetView v) {
        finishWidgetResize();
        if (!(v.getParent() instanceof CellLayout)) return;
        CellLayout cl = (CellLayout) v.getParent();
        if (!item.isFree()) {
            applyFree(item, cl, v.getLeft(), v.getTop(), v.getRight(), v.getBottom());
            CellLayout.LP lp = (CellLayout.LP) v.getLayoutParams();
            lp.fx = item.fx; lp.fy = item.fy; lp.fw = item.fw; lp.fh = item.fh;
            v.requestLayout();
        }
        resizeFrame = new WidgetResizeFrame(this, item, v, cl, true);
        root.addView(resizeFrame, new FrameLayout.LayoutParams(-1, -1));
        Ui.toast(this, "Drag the handles to crop \u2014 drag them back out to show more. Tap outside when done");
    }

    /** A click-through widget under a long-press on "empty" space: its menu opens instead of the home menu. */
    private LauncherWidgetView clickThroughAt(float rawX, float rawY) {
        CellLayout cl = pageLayout(currentHomePage());
        if (cl == null) return null;
        int[] loc = new int[2];
        for (int i = cl.getChildCount() - 1; i >= 0; i--) {
            View v = cl.getChildAt(i);
            if (!(v instanceof LauncherWidgetView) || !((LauncherWidgetView) v).clickThrough) continue;
            v.getLocationOnScreen(loc);
            if (rawX >= loc[0] && rawX < loc[0] + v.getWidth() && rawY >= loc[1] && rawY < loc[1] + v.getHeight()) return (LauncherWidgetView) v;
        }
        return null;
    }

    @Override public void onWidgetDragStart(LauncherWidgetView v) {
        DragInfo di = new DragInfo();
        di.item = (HomeItem) v.getTag();
        startDrag(v, di);
    }

    private void startDrag(View v, DragInfo di) {
        dismissPopup();
        if (Prefs.desktopLocked(this)) {
            Ui.toast(this, "Desktop is locked \u2014 unlock it in Grove settings");
            return;
        }
        boolean isApp = di.app != null || (di.item != null && (di.item.type == HomeItem.APP || di.item.type == HomeItem.SHORTCUT));
        dropBar.show(di.item != null, isApp);
        dragging = true;
        currentDrag = di;
        di.source = v;
        boolean ok;
        try {
            ok = v.startDragAndDrop(ClipData.newPlainText("canopy", "item"), new View.DragShadowBuilder(v), di, 0);
        } catch (Exception e) {
            ok = false;
        }
        if (!ok) {
            dragging = false;
            currentDrag = null;
            dropBar.setVisibility(View.GONE);
            return;
        }
        if (di.app != null) drawer.close(false);
        if (folderView != null) folderView.setVisibility(View.INVISIBLE);
        if (di.item != null && di.folder == null) v.setVisibility(View.INVISIBLE);
    }

    private void onDragEnded() {
        if (!dragging) return;
        dragging = false;
        edgeDir = 0;
        root.removeCallbacks(edgeRunnable);
        dropBar.setVisibility(View.GONE);
        if (currentDrag != null && currentDrag.widgetInfo != null) dismissPopup();
        if (currentDrag != null && currentDrag.source != null) currentDrag.source.setVisibility(View.VISIBLE);
        currentDrag = null;
        if (folderView != null) closeFolder();
        if (store.version() != boundVersion) requestBind();
    }

    private final View.OnDragListener rootDrag = new View.OnDragListener() {
        @Override public boolean onDrag(View v, DragEvent e) {
            if (!(e.getLocalState() instanceof DragInfo)) return false;
            if (e.getAction() == DragEvent.ACTION_DRAG_ENDED) onDragEnded();
            return e.getAction() != DragEvent.ACTION_DROP;
        }
    };

    private int[] spanOf(DragInfo di) {
        if (di.widgetInfo != null) return widgetSpans(di.widgetInfo);
        if (di.item != null) return new int[]{di.item.spanX, di.item.spanY};
        return new int[]{1, 1};
    }

    private boolean draggedIsAppLike(DragInfo di) {
        return di.app != null || (di.item != null && di.item.isAppLike());
    }

    private boolean canMakeFolder(DragInfo di, HomeItem target) {
        if (target == null || target == di.item || !draggedIsAppLike(di)) return false;
        return target.isAppLike() || target.type == HomeItem.FOLDER;
    }

    private final View.OnDragListener cellDrag = new View.OnDragListener() {
        @Override public boolean onDrag(View v, DragEvent e) {
            if (!(e.getLocalState() instanceof DragInfo)) return false;
            CellLayout cl = (CellLayout) v;
            DragInfo di = (DragInfo) e.getLocalState();
            int[] span = spanOf(di);
            switch (e.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED:
                case DragEvent.ACTION_DRAG_ENTERED:
                    return true;
                case DragEvent.ACTION_DRAG_LOCATION: {
                    if (freeWidgetDrag(di, cl)) {
                        float[] r = freeRect(cl, di, e.getX(), e.getY());
                        cl.setHighlightPx(r[0], r[1], r[2], r[3]);
                        edgeCheck(cl, e.getX());
                        return true;
                    }
                    int sx = Math.min(span[0], cl.cols), sy = Math.min(span[1], cl.rows);
                    int[] c = cl.cellAt(e.getX(), e.getY(), sx, sy);
                    HomeItem t = store.itemAt(cl.container, cl.page, c[0], c[1]);
                    cl.setHighlight(c[0], c[1], sx, sy, sx == 1 && sy == 1 && canMakeFolder(di, t));
                    if (cl.container == HomeItem.DESKTOP) {
                        int edge = Ui.dp(LauncherActivity.this, 26);
                        int dir = e.getX() < edge ? -1 : e.getX() > cl.getWidth() - edge ? 1 : 0;
                        if (dir != edgeDir) {
                            edgeDir = dir;
                            root.removeCallbacks(edgeRunnable);
                            if (dir != 0) root.postDelayed(edgeRunnable, 450);
                        }
                    }
                    return true;
                }
                case DragEvent.ACTION_DRAG_EXITED:
                    cl.clearHighlight();
                    return true;
                case DragEvent.ACTION_DROP: {
                    cl.clearHighlight();
                    edgeDir = 0;
                    root.removeCallbacks(edgeRunnable);
                    if (freeWidgetDrag(di, cl)) return dropFree(cl, di, freeRect(cl, di, e.getX(), e.getY()));
                    int sx = Math.min(span[0], cl.cols), sy = Math.min(span[1], cl.rows);
                    int[] c = cl.cellAt(e.getX(), e.getY(), sx, sy);
                    return handleDrop(cl, c[0], c[1], sx, sy, di);
                }
                case DragEvent.ACTION_DRAG_ENDED:
                    cl.clearHighlight();
                    onDragEnded();
                    return true;
            }
            return false;
        }
    };

    // ------------------------------------------------------------------ free widget placement (no grid limits)

    boolean freeWidgets() { return Prefs.sp(this).getBoolean("widget_free", true); }
    /** Home-screen icons and folders placed and sized freely, like widgets (Settings → Home screen). */
    boolean freeIcons() { return Prefs.sp(this).getBoolean("icon_free", false); }

    private boolean freeWidgetDrag(DragInfo di, CellLayout cl) {
        if (cl.container != HomeItem.DESKTOP) return false;
        if (di.widgetInfo != null || (di.item != null && di.item.type == HomeItem.WIDGET)) return freeWidgets();
        return freeIcons() && di.folder == null && (di.app != null || (di.item != null
                && (di.item.type == HomeItem.APP || di.item.type == HomeItem.FOLDER || di.item.type == HomeItem.SHORTCUT)));
    }

    private void edgeCheck(CellLayout cl, float x) {
        int edge = Ui.dp(this, 26);
        int dir = x < edge ? -1 : x > cl.getWidth() - edge ? 1 : 0;
        if (dir != edgeDir) {
            edgeDir = dir;
            root.removeCallbacks(edgeRunnable);
            if (dir != 0) root.postDelayed(edgeRunnable, 450);
        }
    }

    /** Where a widget dragged with its centre at (x, y) would land, in the page's pixels, kept on screen. */
    private float[] freeRect(CellLayout cl, DragInfo di, float x, float y) {
        float cw = Math.max(1, cl.cellWidth()), ch = Math.max(1, cl.cellHeight());
        float w, h;
        if (di.widgetInfo != null) {
            int[] s = widgetSpans(di.widgetInfo);
            w = s[0] * cw;
            h = s[1] * ch;
        } else if (di.source != null && di.source.getWidth() > 0) {
            w = di.source.getWidth();
            h = di.source.getHeight();
        } else {
            w = di.item.spanX * cw;
            h = di.item.spanY * ch;
        }
        int[] loc = new int[2], rl = new int[2];
        cl.getLocationInWindow(loc);
        root.getLocationInWindow(rl);
        float minL = rl[0] - loc[0], minT = rl[1] - loc[1];
        float maxL = minL + root.getWidth() - w, maxT = minT + root.getHeight() - h;
        float l = Math.max(minL, Math.min(maxL, x - w / 2f));
        float t = Math.max(minT, Math.min(maxT, y - h / 2f));
        return new float[]{l, t, l + w, t + h};
    }

    /** Applies a free pixel rectangle (page coordinates) to a widget item; also keeps an approximate cell position. */
    void applyFree(HomeItem it, CellLayout cl, float l, float t, float r, float b) {
        float cw = Math.max(1, cl.cellWidth()), ch = Math.max(1, cl.cellHeight());
        it.fx = (l - cl.getPaddingLeft()) / cw;
        it.fy = (t - cl.getPaddingTop()) / ch;
        it.fw = (r - l) / cw;
        it.fh = (b - t) / ch;
        it.spanX = Math.max(1, Math.min(cl.cols, Math.round(it.fw)));
        it.spanY = Math.max(1, Math.min(cl.rows, Math.round(it.fh)));
        it.x = Math.max(0, Math.min(cl.cols - it.spanX, Math.round(it.fx)));
        it.y = Math.max(0, Math.min(cl.rows - it.spanY, Math.round(it.fy)));
    }

    private boolean dropFree(CellLayout cl, DragInfo di, float[] r) {
        if (di.widgetInfo != null) {
            pendingFree = r;
            pendingFreePage = cl.page;
            pendingCell = null;
            pendingToFeed = false;
            pendingDrawerAnchor = null;
            final AppWidgetProviderInfo info = di.widgetInfo;
            root.post(new Runnable() { @Override public void run() { addWidget(info); } });
            return true;
        }
        HomeItem it = di.item;
        if (di.folder != null) return false;
        if (it == null && di.app != null) it = HomeItem.app(store.newId(), di.app);
        if (it == null) return false;
        store.detach(it);
        it.container = HomeItem.DESKTOP;
        it.page = cl.page;
        applyFree(it, cl, r[0], r[1], r[2], r[3]);
        store.addTop(it);
        store.commit();
        requestBind();
        return true;
    }

    private boolean handleDrop(CellLayout cl, int cx, int cy, int sx, int sy, DragInfo di) {
        int container = cl.container, page = cl.page;
        HomeItem dragged = di.item;
        if (di.widgetInfo != null) return dropNewWidget(cl, cx, cy, sx, sy, di.widgetInfo);
        HomeItem target = store.itemAt(container, page, cx, cy);

        if (sx == 1 && sy == 1 && canMakeFolder(di, target)) {
            if (target.type == HomeItem.FOLDER && di.folder == target) return true;
            HomeItem moving = dragged != null ? dragged : HomeItem.app(store.newId(), di.app);
            if (dragged != null) store.detach(moving);
            moving.spanX = moving.spanY = 1;
            if (target.type == HomeItem.FOLDER) {
                target.children.add(moving);
            } else {
                HomeItem f = new HomeItem();
                f.id = store.newId();
                f.type = HomeItem.FOLDER;
                f.title = "Folder";
                f.container = target.container;
                f.page = target.page;
                f.x = target.x;
                f.y = target.y;
                store.detach(target);
                f.children.add(target);
                f.children.add(moving);
                store.addTop(f);
            }
            store.commit();
            requestBind();
            return true;
        }

        if (dragged != null && dragged.type != HomeItem.WIDGET) dragged.fx = dragged.fy = dragged.fw = dragged.fh = Float.NaN; // back on the grid
        HomeItem ignore = di.folder == null ? dragged : null;
        if (container == HomeItem.DOCK) {
            if (!store.isFree(container, page, cx, cy, sx, sy, ignore, cl.cols, cl.rows + cl.yOffset)) {
                Ui.toast(this, "No room here");
                return false;
            }
        } else if (!store.isFree(container, page, cx, cy, sx, sy, ignore, cl.cols, cl.rows)) {
            int[] f = store.findFreeNear(container, page, cx, cy, sx, sy, ignore, cl.cols, cl.rows);
            if (f == null) {
                Ui.toast(this, "No room here");
                return false;
            }
            cx = f[0];
            cy = f[1];
        }
        HomeItem moving = dragged != null ? dragged : HomeItem.app(store.newId(), di.app);
        if (dragged != null) store.detach(moving);
        moving.container = container;
        moving.page = container == HomeItem.DOCK ? 0 : page;
        moving.x = cx;
        moving.y = cy;
        moving.spanX = sx;
        moving.spanY = sy;
        store.addTop(moving);
        store.commit();
        requestBind();
        return true;
    }

    /** A new widget was dropped on a home page: bind it, then place it exactly where it was dropped. */
    private boolean dropNewWidget(CellLayout cl, int cx, int cy, int sx, int sy, final AppWidgetProviderInfo info) {
        if (cl.container != HomeItem.DESKTOP) { Ui.toast(this, "Widgets can't go in the dock"); return false; }
        int page = cl.page;
        if (!store.isFree(HomeItem.DESKTOP, page, cx, cy, sx, sy, null, cl.cols, cl.rows)) {
            int[] f = store.findFreeNear(HomeItem.DESKTOP, page, cx, cy, sx, sy, null, cl.cols, cl.rows);
            if (f == null) { Ui.toast(this, "No room here"); return false; }
            cx = f[0];
            cy = f[1];
        }
        pendingCell = new int[]{page, cx, cy, sx, sy};
        pendingToFeed = false;
        pendingDrawerAnchor = null;
        root.post(new Runnable() { @Override public void run() { addWidget(info); } });
        return true;
    }

    @Override public void onDropTarget(int which, DragInfo di) {
        ComponentName cn = null;
        UserHandle user = null;
        String pkg = null;
        if (di.app != null) { cn = di.app.component; user = di.app.user; pkg = di.app.pkg(); }
        else if (di.item != null && di.item.type == HomeItem.APP) {
            cn = ComponentName.unflattenFromString(di.item.component);
            user = repo.userFor(di.item.serial);
            pkg = cn != null ? cn.getPackageName() : null;
        } else if (di.item != null && di.item.type == HomeItem.SHORTCUT) {
            pkg = di.item.pkg;
            user = repo.userFor(di.item.serial);
            AppInfo a = repo.findByPackage(pkg);
            if (a != null) cn = a.component;
        }
        switch (which) {
            case DropTargetBar.REMOVE:
                if (di.item != null) removeItem(di.item);
                break;
            case DropTargetBar.INFO:
                if (cn != null) appInfo(cn, user);
                break;
            case DropTargetBar.UNINSTALL:
                if (pkg != null) uninstall(pkg, user);
                break;
        }
    }

    // ------------------------------------------------------------------ widgets

    private boolean pendingToFeed;
    /** page, x, y, spanX, spanY where a dragged-in widget was dropped; null = first free spot. */
    private int[] pendingCell;
    /** Free drop rectangle (page pixels) for a dragged-in widget, and its page. */
    private float[] pendingFree;
    private int pendingFreePage;

    /** toFeed: put the widget on the feed page (Microsoft Launcher style) instead of the home screen. */
    private WidgetPickerView widgetPicker;

    /** Nova-style widget page. toFeed: put the widget on the feed instead of the home screen. */
    public void showWidgetPicker(final boolean toFeed) {
        if (widgetPicker != null) return;
        if (awm.getInstalledProviders().isEmpty()) { Ui.toast(this, "No widgets installed"); return; }
        CellLayout cl = pageLayout(currentHomePage());
        int cols = Prefs.gridCols(this);
        int[] cell = {cl != null && cl.cellWidth() > 1 ? cl.cellWidth() : getResources().getDisplayMetrics().widthPixels / cols,
                cl != null && cl.cellHeight() > 1 ? cl.cellHeight() : Ui.dp(this, 100)};
        widgetPicker = new WidgetPickerView(this, new WidgetPickerView.Callback() {
            @Override public void onWidgetPicked(AppWidgetProviderInfo info) { pendingToFeed = toFeed; addWidget(info); }
            @Override public void onWidgetDrag(AppWidgetProviderInfo info, View card) {
                if (toFeed || pendingDrawerAnchor != null) { pendingToFeed = toFeed; addWidget(info); if (widgetPicker != null) widgetPicker.close(); return; }
                startWidgetDrag(card, info);
            }
            @Override public void onPickerClosed() {
                widgetPicker = null;
                if (pendingWidget == null) pendingDrawerAnchor = null;
                if (!drawer.isOpen()) setOverlay(false);
            }
        }, cell, toFeed ? "Add widget to feed" : "Widgets");
        root.addView(widgetPicker, new FrameLayout.LayoutParams(-1, -1));
        setOverlay(true);
    }

    /** A widget's size on your home screen grid, in pixels (its own preferred size). */
    public int[] widgetNaturalSize(AppWidgetProviderInfo info) {
        int cols = Prefs.gridCols(this);
        CellLayout cl = pageLayout(currentHomePage());
        int cw = cl != null && cl.cellWidth() > 1 ? cl.cellWidth() : getResources().getDisplayMetrics().widthPixels / cols;
        int ch = cl != null && cl.cellHeight() > 1 ? cl.cellHeight() : Ui.dp(this, 100);
        int[] s = widgetSpans(info);
        if (android.os.Build.VERSION.SDK_INT >= 31 && info.targetCellWidth > 0 && info.targetCellHeight > 0) {
            s = new int[]{Math.min(cols, info.targetCellWidth), Math.min(Prefs.gridRows(this), info.targetCellHeight)};
        }
        return new int[]{s[0] * cw, s[1] * ch};
    }

    private int[] widgetSpans(AppWidgetProviderInfo info) {
        int cols = Prefs.gridCols(this), rows = Prefs.gridRows(this);
        CellLayout cl = pageLayout(currentHomePage());
        int cw = cl != null && cl.cellWidth() > 1 ? cl.cellWidth() : getResources().getDisplayMetrics().widthPixels / cols;
        int ch = cl != null && cl.cellHeight() > 1 ? cl.cellHeight() : Ui.dp(this, 100);
        int sx = Math.max(1, Math.min(cols, (int) Math.ceil(info.minWidth / (double) cw)));
        int sy = Math.max(1, Math.min(rows, (int) Math.ceil(info.minHeight / (double) ch)));
        return new int[]{sx, sy};
    }

    private void addWidget(AppWidgetProviderInfo info) {
        pendingWidget = info;
        pendingWidgetId = widgetHost.allocateAppWidgetId();
        pendingPage = currentHomePage();
        boolean bound = false;
        try { bound = awm.bindAppWidgetIdIfAllowed(pendingWidgetId, info.getProfile(), info.provider, null); }
        catch (Exception ignored) { }
        if (bound) {
            configureOrPlace();
        } else {
            Intent i = new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND);
            i.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidgetId);
            i.putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider);
            i.putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.getProfile());
            try { startActivityForResult(i, REQ_BIND_WIDGET); }
            catch (Exception e) { cancelPendingWidget(); }
        }
    }

    private void configureOrPlace() {
        if (pendingWidget.configure != null) {
            try {
                widgetHost.startAppWidgetConfigureActivityForResult(this, pendingWidgetId, 0, REQ_CONFIG_WIDGET, null);
                return;
            } catch (Exception ignored) { }
        }
        placePendingWidget();
    }

    private void cancelPendingWidget() {
        if (pendingWidgetId != -1) {
            try { widgetHost.deleteAppWidgetId(pendingWidgetId); } catch (Exception ignored) { }
        }
        pendingWidgetId = -1;
        pendingWidget = null;
        pendingCell = null;
        pendingFree = null;
    }

    public LauncherWidgetView feedWidgetView(int id, AppWidgetProviderInfo info) {
        try {
            LauncherWidgetView wv = widgetViews.get(id);
            if (wv == null) {
                wv = (LauncherWidgetView) widgetHost.createView(this, id, info);
                widgetViews.put(id, wv);
            }
            if (wv.getParent() != null) ((ViewGroup) wv.getParent()).removeView(wv);
            return wv;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ web cards in the feed

    /** Kept here (not in the feed page, which is rebuilt often) so pages and sign-ins survive feed refreshes. */
    /** Shared by every home screen instance, so websites never close when Canopy rebuilds itself. */
    private static final java.util.Map<String, android.webkit.WebView> webViews = new java.util.HashMap<>();

    /** Point the open web tabs at this (new) home screen. */
    private void adoptWebViews() {
        for (android.webkit.WebView wv : webViews.values()) {
            if (wv.getContext() instanceof android.content.MutableContextWrapper) ((android.content.MutableContextWrapper) wv.getContext()).setBaseContext(this);
            if (wv.getParent() != null) ((ViewGroup) wv.getParent()).removeView(wv);
        }
    }

    public android.webkit.WebView feedWebView(String id) {
        android.webkit.WebView wv = webViews.get(id);
        if (wv == null) {
            wv = FeedWeb.create(this, id);
            webViews.put(id, wv);
            if (feedAmt > 0.02f) { wv.onResume(); wv.resumeTimers(); webAsleep = false; webForFeed = true; } // never start a tab frozen on screen
        }
        return wv;
    }

    public android.webkit.WebView peekFeedWebView(String id) { return webViews.get(id); }

    /** A tab's favicon arrived: refresh the tab strip. */
    public void onWebTabChanged() { if (feed != null) feed.buildTabs(); }

    public void deleteFeedWebView(String id) {
        WebMedia.forget(id);
        if (WebMedia.current() == null) MediaService.stop(this);
        android.webkit.WebView wv = webViews.remove(id);
        if (wv == null) return;
        if (wv.getParent() != null) ((ViewGroup) wv.getParent()).removeView(wv);
        try { wv.destroy(); } catch (Exception ignored) { }
    }

    public void deleteFeedWidget(int id) {
        try { widgetHost.deleteAppWidgetId(id); } catch (Exception ignored) { }
        widgetViews.remove(id);
    }

    private void placePendingWidget() {
        if (pendingWidget == null) return;
        if (pendingDrawerAnchor != null) {
            int id = pendingWidgetId;
            String anchor = pendingDrawerAnchor;
            pendingWidget = null;
            pendingWidgetId = -1;
            pendingDrawerAnchor = null;
            drawer.addWidget(id, anchor);
            drawer.open(false);
            return;
        }
        if (pendingToFeed) {
            int id = pendingWidgetId;
            pendingWidget = null;
            pendingWidgetId = -1;
            pendingToFeed = false;
            if (feed != null) { feed.addWidget(id); workspace.snapToPage(0); }
            return;
        }
        int[] s = widgetSpans(pendingWidget);
        int cols = Prefs.gridCols(this), rows = Prefs.gridRows(this);
        int page = -1;
        int[] spot = null;
        int[] dropped = pendingCell;
        pendingCell = null;
        float[] free = pendingFree;
        pendingFree = null;
        if (free != null && pendingFreePage < store.pageCount() && pageLayout(pendingFreePage) != null) {
            CellLayout fcl = pageLayout(pendingFreePage);
            HomeItem it = new HomeItem();
            it.id = store.newId();
            it.type = HomeItem.WIDGET;
            it.widgetId = pendingWidgetId;
            it.page = pendingFreePage;
            applyFree(it, fcl, free[0], free[1], free[2], free[3]);
            store.addTop(it);
            store.commit();
            pendingWidget = null;
            pendingWidgetId = -1;
            bindAll();
            workspace.snapToPage(it.page + glanceOffset());
            return;
        }
        if (dropped != null && dropped[0] < store.pageCount()
                && store.isFree(HomeItem.DESKTOP, dropped[0], dropped[1], dropped[2], dropped[3], dropped[4], null, cols, rows)) {
            page = dropped[0];
            spot = new int[]{dropped[1], dropped[2]};
            s = new int[]{dropped[3], dropped[4]};
        }
        for (int i = 0; i < store.pageCount() && spot == null; i++) {
            int p = (pendingPage + i) % store.pageCount();
            spot = store.findFirstFree(HomeItem.DESKTOP, p, s[0], s[1], cols, rows);
            if (spot != null) page = p;
        }
        if (spot == null) {
            store.addPage();
            page = store.pageCount() - 1;
            spot = new int[]{0, 0};
        }
        HomeItem it = new HomeItem();
        it.id = store.newId();
        it.type = HomeItem.WIDGET;
        it.widgetId = pendingWidgetId;
        it.page = page;
        it.x = spot[0];
        it.y = spot[1];
        it.spanX = s[0];
        it.spanY = s[1];
        store.addTop(it);
        store.commit();
        pendingWidget = null;
        pendingWidgetId = -1;
        bindAll();
        workspace.snapToPage(page + glanceOffset());
    }

    private WidgetResizeFrame resizeFrame;

    /** An icon or folder: becomes free-placed (keeping where it is), then moves and resizes like a widget. */
    private void resizeIcon(HomeItem item, View v) {
        if (!(v.getParent() instanceof CellLayout)) return;
        CellLayout cl = (CellLayout) v.getParent();
        if (!item.isFree()) {
            float m = Ui.dp(this, 8);
            applyFree(item, cl, v.getLeft(), v.getTop(), v.getRight(), v.getBottom());
            CellLayout.LP lp = (CellLayout.LP) v.getLayoutParams();
            lp.fx = item.fx; lp.fy = item.fy; lp.fw = item.fw; lp.fh = item.fh;
            if (v instanceof IconView) ((IconView) v).freeSized = true;
            v.requestLayout();
        }
        finishWidgetResize();
        resizeFrame = new WidgetResizeFrame(this, item, v, cl);
        root.addView(resizeFrame, new FrameLayout.LayoutParams(-1, -1));
        Ui.toast(this, "Drag the icon to move it anywhere, drag the handles to resize \u2014 tap outside when done");
    }

    /** Nova-style: drag the handles to resize. */
    private void resizeWidget(final HomeItem item) {
        finishWidgetResize();
        LauncherWidgetView wv = widgetViews.get(item.widgetId);
        if (wv == null || !(wv.getParent() instanceof CellLayout)) return;
        resizeFrame = new WidgetResizeFrame(this, item, wv, (CellLayout) wv.getParent());
        root.addView(resizeFrame, new FrameLayout.LayoutParams(-1, -1));
        Ui.toast(this, freeWidgets() && item.container == HomeItem.DESKTOP
                ? "Drag the widget to move it anywhere, drag the handles to resize \u2014 tap outside when done"
                : "Drag the handles to resize \u2014 tap outside when done");
    }

    public void finishWidgetResize() {
        if (resizeFrame == null) return;
        root.removeView(resizeFrame);
        resizeFrame = null;
        store.commit();
        requestBind();
    }

    public boolean canPlaceWidget(HomeItem item, int x, int y, int sx, int sy) {
        return store.isFree(item.container, item.page, x, y, sx, sy, item, Prefs.gridCols(this), Prefs.gridRows(this));
    }

    /** Mic in the drawer search bar: uses the phone's own speech recognizer (Canopy never touches the audio). */
    public void startVoiceSearch() {
        Intent i = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Say an app, contact or search \u2014 or \u201Copen\u2026\u201D, \u201Ccall\u2026\u201D");
        i.putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        try {
            startActivityForResult(i, REQ_VOICE);
        } catch (Exception e) {
            // No recognizer: fall back to the phone's voice assistant, if any.
            try { startActivity(new Intent(Intent.ACTION_VOICE_COMMAND).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
            catch (Exception e2) { Ui.toast(this, "No voice input app on this phone"); }
        }
    }

    /** Simple voice commands: "open/launch X", "call X", "search X"; anything else is searched in the drawer. */
    private void handleVoice(String said) {
        if (said == null) return;
        String t = said.trim();
        String lower = t.toLowerCase(java.util.Locale.getDefault());
        String[] openWords = {"open ", "launch ", "start ", "buka "};
        for (String w : openWords) {
            if (!lower.startsWith(w)) continue;
            String name = lower.substring(w.length()).trim();
            AppInfo best = null;
            for (AppInfo a : repo.visible()) {
                String l = a.label.toLowerCase(java.util.Locale.getDefault());
                if (l.equals(name)) { best = a; break; }
                if (best == null && (l.startsWith(name) || l.contains(name))) best = a;
            }
            if (best != null) { drawer.close(false); launchApp(best, null); return; }
            t = t.substring(w.length()).trim();
            break;
        }
        if (lower.startsWith("call ") || lower.startsWith("phone ")) t = t.substring(t.indexOf(' ') + 1).trim();
        else if (lower.startsWith("search for ")) t = t.substring(11).trim();
        else if (lower.startsWith("search ")) t = t.substring(7).trim();
        drawer.setQuery(t);
    }

    private String scanWith;

    /** Feed → an antivirus app: pick a file and hand it to the app's scanner (its "share to scan" screen). */
    public void scanFileWith(String pkg) {
        scanWith = pkg;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        try { startActivityForResult(i, REQ_SCAN_FILE); } catch (Exception e) { Ui.toast(this, "No file picker found"); }
    }

    @Override protected void onActivityResult(int req, int result, Intent data) {
        AppLock.onResult(this, req, result, null);
        super.onActivityResult(req, result, data);
        if (req == REQ_SCAN_FILE) {
            if (result == RESULT_OK && data != null && data.getData() != null && scanWith != null) {
                Intent send = new Intent(Intent.ACTION_SEND).setType("*/*").setPackage(scanWith)
                        .putExtra(Intent.EXTRA_STREAM, data.getData())
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                send.setClipData(android.content.ClipData.newRawUri("file", data.getData()));
                safeStart(send);
            }
            return;
        }
        if (req == REQ_VOICE) {
            if (result == RESULT_OK && data != null) {
                java.util.ArrayList<String> r = data.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS);
                if (r != null && !r.isEmpty()) handleVoice(r.get(0));
            }
            return;
        }
        if (req == REQ_ICON_IMAGE) {
            if (result == RESULT_OK && data != null && data.getData() != null && pendingIconKey != null) {
                if (IconEditor.saveImageIcon(this, pendingIconKey, data.getData())) {
                    if (FeedPage.PROFILE_KEY.equals(pendingIconKey)) { if (feed != null) feed.refresh(); }
                    else repo.applyEdit(pendingIconKey);
                }
                else Ui.toast(this, "Couldn't use that image");
            }
            pendingIconKey = null;
            return;
        }
        if (req == REQ_BIND_WIDGET) {
            if (result == RESULT_OK && pendingWidget != null) configureOrPlace();
            else cancelPendingWidget();
        } else if (req == REQ_CONFIG_WIDGET) {
            if (result == RESULT_OK && pendingWidget != null) placePendingWidget();
            else cancelPendingWidget();
        }
    }

    @SuppressWarnings("unused")
    private static List<String> list(String... s) { return Arrays.asList(s); }
}
