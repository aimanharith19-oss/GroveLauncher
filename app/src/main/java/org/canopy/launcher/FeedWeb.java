/*
 * Copyright 2026 The Canopy Launcher authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.canopy.launcher;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;

/**
 * Browser tabs in the feed: one website per tab (e.g. X/Twitter, Reddit, a news site), next to the Feed tab.
 * You sign in once inside a tab; cookies stay on this phone. Only these tabs use the internet.
 */
final class FeedWeb {
    private FeedWeb() { }

    static final String[] QUICK = {"X (Twitter)", "Reddit", "YouTube", "Instagram", "Threads", "Facebook", "Google News", "Other website…"};
    static final String[] QUICK_URL = {"https://x.com/home", "https://www.reddit.com/", "https://m.youtube.com/",
            "https://www.instagram.com/", "https://www.threads.net/", "https://m.facebook.com/", "https://news.google.com/", ""};

    static SharedPreferences prefs(Context c) {
        // Separate file: changes here never trigger the launcher's settings listener (no reload loops).
        return c.getApplicationContext().getSharedPreferences("web_cards", Context.MODE_PRIVATE);
    }

    static String url(Context c, String id) { return prefs(c).getString("url_" + id, "https://x.com/home"); }
    static String last(Context c, String id) { return prefs(c).getString("last_" + id, url(c, id)); }

    static String title(Context c, String id) {
        String t = prefs(c).getString("title_" + id, null);
        if (t != null && !t.trim().isEmpty()) return FeedPage.looksLikeAddress(t) ? FeedPage.siteName(t) : t;
        if (isAppTab(id)) { AppInfo a = appOf(c, id); return a != null ? a.label : "App"; }
        return FeedPage.siteName(url(c, id)); // the website's name (e.g. "Reddit"), not its address
    }

    /** Open tabs, in order. */
    static java.util.List<String> tabs(Context c) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String t : prefs(c).getString("tabs", "").split(",")) if (!t.isEmpty() && !out.contains(t)) out.add(t);
        return out;
    }

    static void saveTabs(Context c, java.util.List<String> t) {
        prefs(c).edit().putString("tabs", android.text.TextUtils.join(",", t)).apply();
    }

    static String newTab(Context c, String url, String title) {
        String id = "b" + System.currentTimeMillis();
        SharedPreferences.Editor e = prefs(c).edit().putString("url_" + id, url).putString("last_" + id, url);
        if (title != null) e.putString("title_" + id, title);
        e.apply();
        java.util.List<String> t = tabs(c);
        t.add(id);
        saveTabs(c, t);
        return id;
    }

    static void forget(Context c, String id) {
        prefs(c).edit().remove("url_" + id).remove("last_" + id).remove("title_" + id).remove("app_" + id).remove("grp_" + id).remove("web_" + id).apply();
        java.util.List<String> t = tabs(c);
        t.remove(id);
        saveTabs(c, t);
    }

    // ---------------------------------------------------------------- app tabs

    static boolean isAppTab(String id) { return id != null && id.startsWith("a"); }

    /** App tabs open the real app; this is true only if you chose to show its website in the feed instead. */
    static boolean webMode(Context c, String id) { return prefs(c).getBoolean("web_" + id, false); }

    /** An app tab shows inside the feed like a browser tab, using the app's website. */
    static boolean hasWeb(Context c, String id) { return prefs(c).contains("url_" + id); }

    /** Finds (once) the website for an app tab. False if the app has none. */
    static boolean ensureAppWeb(Context c, String id) {
        if (hasWeb(c, id)) return true;
        AppInfo a = appOf(c, id);
        if (a == null) return false;
        String url = AppWeb.urlFor(c, a.pkg());
        // no known website: go to the app's official site via a search (never open the app itself)
        if (url == null) url = "https://duckduckgo.com/?q=" + Uri.encode("! " + a.label + " official site");
        prefs(c).edit().putString("url_" + id, url).putString("last_" + id, url).apply();
        return true;
    }

    static String newAppTab(Context c, String appKey) {
        String id = "a" + System.currentTimeMillis();
        prefs(c).edit().putString("app_" + id, appKey).apply();
        java.util.List<String> t = tabs(c);
        t.add(id);
        saveTabs(c, t);
        return id;
    }

    static AppInfo appOf(Context c, String id) {
        String key = prefs(c).getString("app_" + id, null);
        if (key == null) return null;
        for (AppInfo a : AppRepository.get(c).all()) if (a.key.equals(key)) return a;
        return null;
    }

    // ---------------------------------------------------------------- tab groups (like Chrome)

    static String groupOf(Context c, String id) { return prefs(c).getString("grp_" + id, null); }

    static void setGroup(Context c, String id, String gid) {
        if (gid == null) prefs(c).edit().remove("grp_" + id).apply();
        else prefs(c).edit().putString("grp_" + id, gid).apply();
    }

    static java.util.List<String> groupMembers(Context c, String gid) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String t : tabs(c)) if (gid.equals(groupOf(c, t))) out.add(t);
        return out;
    }

    static java.util.List<String> groups(Context c) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String t : tabs(c)) { String g = groupOf(c, t); if (g != null && !out.contains(g)) out.add(g); }
        return out;
    }

    static String groupName(Context c, String gid) {
        String n = prefs(c).getString("gname_" + gid, "");
        if (!n.isEmpty()) return n;
        java.util.List<String> m = groupMembers(c, gid);
        return m.isEmpty() ? "Group" : title(c, m.get(0)) + (m.size() > 1 ? " + " + (m.size() - 1) : "");
    }

    /** A group's tabs sit next to each other, where the group's first tab is. */
    static void keepGroupsTogether(Context c) {
        java.util.List<String> in = tabs(c), out = new java.util.ArrayList<>();
        for (String t : in) {
            if (out.contains(t)) continue;
            String g = groupOf(c, t);
            if (g == null) { out.add(t); continue; }
            for (String m : in) if (g.equals(groupOf(c, m)) && !out.contains(m)) out.add(m);
        }
        saveTabs(c, out);
    }

    /** A page opened a new window from a tab: the new tab goes right after it, in the same group. */
    static String newTabFrom(Context c, String opener, String url) {
        String id = newTab(c, url, null);
        if (opener != null && tabs(c).contains(opener)) {
            String g = groupOf(c, opener);
            if (g == null) { g = "g" + System.currentTimeMillis(); setGroup(c, opener, g); }
            setGroup(c, id, g);
            java.util.List<String> t = tabs(c);
            t.remove(id);
            int at = t.indexOf(opener) + 1;
            while (at < t.size() && g.equals(groupOf(c, t.get(at)))) at++;
            t.add(at, id);
            saveTabs(c, t);
        }
        return id;
    }

    // ---------------------------------------------------------------- bookmarks

    static java.util.List<String[]> bookmarks(Context c) {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        try {
            org.json.JSONArray a = new org.json.JSONArray(prefs(c).getString("bookmarks", "[]"));
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONObject o = a.getJSONObject(i);
                out.add(new String[]{o.optString("url"), o.optString("title")});
            }
        } catch (Exception ignored) { }
        return out;
    }

    static void saveBookmarks(Context c, java.util.List<String[]> l) {
        org.json.JSONArray a = new org.json.JSONArray();
        try { for (String[] b : l) a.put(new org.json.JSONObject().put("url", b[0]).put("title", b[1])); } catch (Exception ignored) { }
        prefs(c).edit().putString("bookmarks", a.toString()).apply();
    }

    static void addBookmark(Context c, String url, String title) {
        java.util.List<String[]> l = bookmarks(c);
        for (String[] b : l) if (b[0].equals(url)) return;
        l.add(new String[]{url, FeedPage.siteName(url)}); // named after the website; rename it any time
        saveBookmarks(c, l);
    }

    static String normalise(String u) {
        u = u == null ? "" : u.trim();
        if (u.isEmpty()) return "";
        if (!FeedPage.looksLikeAddress(u)) { // not an address: search Google for it
            try { return "https://www.google.com/search?q=" + java.net.URLEncoder.encode(u, "UTF-8"); } catch (Exception ignored) { }
        }
        if (!u.contains("://")) u = "https://" + u;
        return u;
    }

    // ------------------------------------------------------------------ links and downloads

    /** Settings → Feed browser → "Open websites in": "app" (the feed), "ask", or the package of a browser. */
    static String openIn(Context c) { return Prefs.sp(c).getString("web_open", "app"); }

    /** Opens a link in the browser chosen in Settings. False = open it inside the feed instead. */
    static boolean openOutside(Context c, String url) {
        String p = openIn(c);
        if ("app".equals(p) || url == null || !url.startsWith("http")) return false;
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            if ("ask".equals(p)) { c.startActivity(Intent.createChooser(i, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true; }
            c.getPackageManager().getPackageInfo(p, 0);
            i.setPackage(p);
            c.startActivity(i);
            return true;
        } catch (Exception e) { return false; } // browser gone: stay in the feed
    }

    /** Every download goes here: the downloader app chosen in Settings, else Android's download manager. */
    static void download(Context c, String url, String ua, String disposition, String mime) {
        String d = Prefs.sp(c).getString("web_downloader", "system");
        Uri u = Uri.parse(url);
        if (!"system".equals(d)) {
            Intent v = new Intent(Intent.ACTION_VIEW).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (mime != null && !mime.isEmpty()) v.setDataAndType(u, mime); else v.setData(u);
            try {
                if ("ask".equals(d)) { c.startActivity(Intent.createChooser(v, "Download with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return; }
                v.setPackage(d);
                c.startActivity(v);
                return;
            } catch (Exception e) {
                try {
                    Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
                            .setPackage(d).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    c.startActivity(send);
                    return;
                } catch (Exception ignored) { }
                Ui.toast(c, "The chosen downloader couldn't open this \u2014 using Android's");
            }
        }
        if (!url.startsWith("http")) { Ui.toast(c, "This kind of download can't be saved from here"); return; }
        try {
            android.app.DownloadManager.Request r = new android.app.DownloadManager.Request(u);
            r.addRequestHeader("User-Agent", ua);
            String ck = CookieManager.getInstance().getCookie(url);
            if (ck != null) r.addRequestHeader("Cookie", ck);
            r.setMimeType(mime);
            r.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            r.setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, android.webkit.URLUtil.guessFileName(url, disposition, mime));
            ((android.app.DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(r);
            Ui.toast(c, "Downloading\u2026");
        } catch (Exception e) { Ui.toast(c, "Couldn't start the download"); }
    }

    static boolean autoplay(Context c) { return Prefs.sp(c).getBoolean("web_autoplay", true); }
    static boolean background(Context c) { return Prefs.sp(c).getBoolean("web_background", true); }

    static boolean anyPlaying() { return WebMedia.anyPlaying(); }

    /** Web tabs outlive a rebuilt home screen: always talk to the current one. */
    static LauncherActivity cur(LauncherActivity fallback) {
        LauncherActivity l = LauncherActivity.instance();
        return l != null ? l : fallback;
    }

    /** Keep websites running while you're in other apps (they're never closed or reloaded). */
    // ------------------------------------------------------------------ refresh after install / restart

    private static Boolean fresh;
    private static final java.util.Set<String> refreshed = new java.util.HashSet<>();

    /**
     * True once per run of the app if it was just installed or updated, or the phone was restarted since the
     * last run — then every web tab reloads fresh (instead of showing an old, cached page) the first time it loads.
     */
    static synchronized boolean freshStart(Context c) {
        if (fresh != null) return fresh;
        android.content.SharedPreferences p = prefs(c);
        long boot = (System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()) / 60000L; // boot time, to the minute
        long ver = 0;
        try {
            android.content.pm.PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            ver = pi.lastUpdateTime;
        } catch (Exception ignored) { }
        long lastBoot = p.getLong("boot_min", -1), lastVer = p.getLong("installed_at", -1);
        fresh = Math.abs(boot - lastBoot) > 1 || ver != lastVer;
        p.edit().putLong("boot_min", boot).putLong("installed_at", ver).apply();
        return fresh;
    }

    static synchronized boolean shouldRefresh(Context c, String id) { return freshStart(c) && refreshed.add(id); }

    static boolean keepRunning(Context c) { return "always".equals(bgMode(c)); }

    /**
     * What websites do while you're away from the home screen. They are never closed or reloaded in any mode;
     * "later" (default) lets them run for a couple of minutes, then freezes their scripts to save battery
     * (anything playing keeps playing). "always" never freezes them, "now" freezes them as soon as you leave.
     */
    static String bgMode(Context c) {
        android.content.SharedPreferences sp = Prefs.sp(c);
        String m = sp.getString("web_bg_mode", null);
        if (m != null) return m;
        return sp.contains("web_keep_running") && !sp.getBoolean("web_keep_running", true) ? "now" : "later";
    }
    static final long BG_GRACE = 2 * 60 * 1000L;
    static boolean blockPopups(Context c) { return Prefs.sp(c).getBoolean("web_block_popups", true); }

    static final String PAUSE_JS = "(function(){var m=document.querySelectorAll('video,audio');for(var i=0;i<m.length;i++){try{m[i].pause();}catch(e){}}})();";

    /**
     * A WebView that keeps every touch for the page: inside a web tab, swipes never change the home screen
     * page (that would fight the website's own swiping). Swipe on the tab strip or toolbar, or press Home, to leave.
     */
    static final class CardWebView extends WebView {
        volatile String pageHost;
        final String id;
        final java.util.concurrent.atomic.AtomicInteger blocked = new java.util.concurrent.atomic.AtomicInteger();

        CardWebView(Context c, String id) {
            super(c);
            this.id = id;
            slop = android.view.ViewConfiguration.get(c).getScaledTouchSlop();
        }

        // ---- pull to refresh: drag down while the page is at its very top
        interface Pull { void onPull(float distance); void onRelease(); void onLoaded(); }
        Pull pull;
        volatile boolean jsTop = true, jsKnown;
        private float downX, downY;
        private boolean pulling, multi;
        private final int slop;

        private boolean atTop() {
            if (getScrollY() > 0) return false;
            // the page reports (on touchstart) whether the thing under your finger is scrolled down
            return jsKnown ? jsTop : !canScrollVertically(-1);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_POINTER_DOWN) {
                ViewParent p = getParent();
                if (p != null) p.requestDisallowInterceptTouchEvent(true);
            }
            return super.onTouchEvent(e);
        }

        @Override public boolean dispatchTouchEvent(MotionEvent e) {
            int a = e.getActionMasked();
            // claim the gesture before the launcher can look at it
            if (a == MotionEvent.ACTION_DOWN) {
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                downX = e.getX();
                downY = e.getY();
                pulling = false;
                multi = false;
                jsKnown = false;
            } else if (a == MotionEvent.ACTION_POINTER_DOWN) {
                multi = true;
            }
            if (pulling) {
                if (a == MotionEvent.ACTION_MOVE) pull.onPull(e.getY() - downY - slop);
                else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) { pulling = false; pull.onRelease(); }
                else if (a == MotionEvent.ACTION_POINTER_DOWN) { pulling = false; pull.onPull(0); pull.onRelease(); }
                return true;
            }
            if (a == MotionEvent.ACTION_MOVE && pull != null && !multi && e.getPointerCount() == 1) {
                float dy = e.getY() - downY, dx = Math.abs(e.getX() - downX);
                if (dy > slop && dy > dx * 1.6f && atTop()) {
                    pulling = true;
                    MotionEvent cancel = MotionEvent.obtain(e);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    super.dispatchTouchEvent(cancel); // the page lets go of the touch
                    cancel.recycle();
                    pull.onPull(dy - slop);
                    return true;
                }
            }
            return super.dispatchTouchEvent(e);
        }

        @Override protected void onWindowVisibilityChanged(int visibility) {
            // Background play: the page should not be told it was hidden while something is playing.
            if (visibility != VISIBLE && background(getContext()) && WebMedia.isPlaying(id)) return;
            super.onWindowVisibilityChanged(visibility);
        }
    }

    /** Page → app: is the touched content scrolled to its top? (for pull to refresh) */
    static final class PullBridge {
        final CardWebView wv;
        PullBridge(CardWebView wv) { this.wv = wv; }
        @android.webkit.JavascriptInterface public void top(boolean top) { wv.jsTop = top; wv.jsKnown = true; }
    }

    /** Pages often scroll an inner box instead of the page itself: check every scrollable thing under the finger. */
    static final String PULL_JS = "(function(){if(window.__cnpPull)return;window.__cnpPull=1;"
            + "document.addEventListener('touchstart',function(e){try{if(e.touches.length>1){CanopyPull.top(false);return;}"
            + "var se=document.scrollingElement||document.documentElement;var top=!se||se.scrollTop<=0;"
            + "var n=e.composedPath?e.composedPath()[0]:e.target;"
            + "while(top&&n&&n!==document.documentElement&&n!==document){"
            + "if(n.nodeType===1){if(n.scrollTop>0)top=false;else if(n.tagName==='CANVAS')top=false;"
            + "else{var ta=getComputedStyle(n).touchAction;if(ta==='none'||ta==='pan-x'||ta==='pinch-zoom')top=false;}}"
            + "n=n.parentNode||n.host;}"
            + "CanopyPull.top(top);}catch(x){}},{capture:true,passive:true});})();";

    /** Page → app bridge: only reports the page's media state (for the player notification). */
    static final class MediaBridge {
        final String id;
        final LauncherActivity a;
        MediaBridge(LauncherActivity a, String id) { this.a = a; this.id = id; }
        @android.webkit.JavascriptInterface public void update(final String json) {
            App.MAIN.post(new Runnable() { @Override public void run() { WebMedia.onUpdate(cur(a), id, json); } });
        }
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    static WebView create(final LauncherActivity a, final String id) {
        AdBlock.init(a);
        // A context wrapper whose base can be swapped: the page survives the home screen being rebuilt
        // (settings changes, theme…) instead of being closed and reloaded.
        final CardWebView wv = new CardWebView(new android.content.MutableContextWrapper(a), id);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(!autoplay(a));
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSaveFormData(false);
        // Pop-ups: pages may not open windows by themselves; a link you tap that opens a new window
        // becomes a new tab instead (see onCreateWindow).
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(!blockPopups(a));
        boolean night = Ui.night(a);
        if (night && Build.VERSION.SDK_INT >= 33) {
            try { s.setAlgorithmicDarkeningAllowed(true); } catch (Throwable ignored) { }
        }
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(wv, true); // needed for some sign-in pages
        wv.setBackgroundColor(Ui.palette(a).surface);
        wv.addJavascriptInterface(new MediaBridge(a, id), "CanopyMedia");
        wv.addJavascriptInterface(new PullBridge(wv), "CanopyPull");
        final android.webkit.WebResourceResponse empty = null;
        wv.setDownloadListener(new android.webkit.DownloadListener() {
            @Override public void onDownloadStart(String url, String ua, String cd, String mime, long len) { download(cur(a), url, ua, cd, mime); }
        });
        wv.setWebViewClient(new WebViewClient() {
            @Override public android.webkit.WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                // uBlock Origin lists: drop ad / tracker requests (never the page itself)
                if (r.isForMainFrame()) return null;
                Uri u = r.getUrl();
                if (AdBlock.shouldBlock(a, u, wv.pageHost)) {
                    wv.blocked.incrementAndGet();
                    return new android.webkit.WebResourceResponse("text/plain", "utf-8", new java.io.ByteArrayInputStream(new byte[0]));
                }
                return empty;
            }

            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                String scheme = u.getScheme() == null ? "" : u.getScheme();
                boolean gesture = r.hasGesture();
                String h = u.getHost() == null ? "" : u.getHost().toLowerCase();
                // "Open in app" links (Reddit's reddit.app.link and other app-link redirectors): stay in the browser
                if (blockPopups(a) && (h.endsWith(".app.link") || h.equals("app.link") || h.endsWith(".onelink.me")
                        || (!gesture && h.endsWith("play.google.com")))) {
                    wv.blocked.incrementAndGet();
                    return true;
                }
                if ("http".equals(scheme) || "https".equals(scheme)) {
                    // ad redirects: a page jumping to an ad/tracker site without you tapping anything
                    if (r.isForMainFrame() && !gesture && blockPopups(a) && AdBlock.shouldBlock(a, u.getHost(), wv.pageHost)) {
                        wv.blocked.incrementAndGet();
                        return true;
                    }
                    return false;
                }
                // pages opening other apps / the Play Store on their own are ads too
                if (!gesture && blockPopups(a)) { wv.blocked.incrementAndGet(); return true; }
                // app links like intent://, twitter://, mailto: open the matching app
                try {
                    Intent i = "intent".equals(scheme) ? Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME)
                            : new Intent(Intent.ACTION_VIEW, u);
                    i.addCategory(Intent.CATEGORY_BROWSABLE);
                    i.setComponent(null);
                    i.setSelector(null);
                    cur(a).safeStart(i);
                } catch (Exception ignored) { }
                return true;
            }

            @Override public void onPageStarted(WebView v, String u, android.graphics.Bitmap icon) {
                wv.pageHost = Uri.parse(u).getHost();
                wv.blocked.set(0);
                String fix = AdBlock.enabled(a) ? siteFix(wv.pageHost) : null;
                if (fix != null) v.evaluateJavascript(fix, null);
                Object t = v.getTag(R.drawable.ic_more);
                if (t instanceof android.widget.TextView) ((android.widget.TextView) t).setText(FeedPage.hostOf(u));
            }

            @Override public void doUpdateVisitedHistory(WebView v, String u, boolean reload) {
                if (u != null) wv.pageHost = Uri.parse(u).getHost();
                // remember where you are, so a restart comes back to this page (not the start page)
                if (u != null && u.startsWith("http")) prefs(a).edit().putString("last_" + id, u).apply();
            }

            @Override public void onPageCommitVisible(WebView v, String u) { inject(a, wv); }

            @Override public void onPageFinished(WebView v, String u) {
                // the fresh reload is done: back to normal caching
                if (v.getSettings().getCacheMode() == android.webkit.WebSettings.LOAD_NO_CACHE) v.getSettings().setCacheMode(android.webkit.WebSettings.LOAD_DEFAULT);
                inject(a, wv);
                if (u != null && u.startsWith("http")) prefs(a).edit().putString("last_" + id, u).apply();
                if (u != null && u.startsWith("http")) WebHistory.add(a, u, v.getTitle());
                CookieManager.getInstance().flush();
            }
        });
        wv.setWebChromeClient(new WebChromeClient() {
            @Override public void onReceivedIcon(WebView v, android.graphics.Bitmap icon) { cur(a).onWebTabChanged(); }

            @Override public void onProgressChanged(WebView v, int p) {
                if (p >= 100 && v instanceof CardWebView && ((CardWebView) v).pull != null) ((CardWebView) v).pull.onLoaded();
                Object bar = v.getTag();
                if (bar instanceof ProgressBar) {
                    ((ProgressBar) bar).setProgress(p);
                    ((ProgressBar) bar).setVisibility(p >= 100 ? View.GONE : View.VISIBLE);
                }
            }

            /** New windows: blocked unless you tapped something; then the page opens as a new tab. */
            @Override public boolean onCreateWindow(WebView v, boolean isDialog, boolean userGesture, android.os.Message resultMsg) {
                if (blockPopups(a) && !userGesture) { wv.blocked.incrementAndGet(); return false; }
                final WebView catcher = new WebView(cur(a));
                catcher.setWebViewClient(new WebViewClient() {
                    boolean done;
                    private boolean take(String url) {
                        if (done || url == null) return true;
                        done = true;
                        Uri u = Uri.parse(url);
                        if (AdBlock.shouldBlock(a, u.getHost(), wv.pageHost)) wv.blocked.incrementAndGet();
                        else if (url.startsWith("http")) cur(a).openWebTab(url, id);
                        App.MAIN.post(new Runnable() { @Override public void run() { catcher.destroy(); } });
                        return true;
                    }
                    @Override public boolean shouldOverrideUrlLoading(WebView w, WebResourceRequest r) { return take(r.getUrl().toString()); }
                    @Override public void onPageStarted(WebView w, String url, android.graphics.Bitmap f) { take(url); w.stopLoading(); }
                });
                WebView.WebViewTransport t = (WebView.WebViewTransport) resultMsg.obj;
                t.setWebView(catcher);
                resultMsg.sendToTarget();
                return true;
            }

            @Override public void onPermissionRequest(android.webkit.PermissionRequest request) { request.deny(); }

            @Override public void onGeolocationPermissionsShowPrompt(String origin, android.webkit.GeolocationPermissions.Callback cb) {
                cb.invoke(origin, false, false);
            }

            // The page's own full-screen button (e.g. on a video): play it full screen.
            @Override public void onShowCustomView(View view, CustomViewCallback cb) { cur(a).showVideoFullscreen(view, cb); }
            @Override public void onHideCustomView() { cur(a).hideVideoFullscreen(); }
        });
        // first load after the app was installed/updated or the phone restarted: fetch the page fresh
        if (shouldRefresh(a, id)) wv.getSettings().setCacheMode(android.webkit.WebSettings.LOAD_NO_CACHE);
        wv.loadUrl(last(a, id));
        return wv;
    }

    /** Site fixes from uBlock Origin's lists that need a script (we apply the few that matter). */
    static String siteFix(String host) {
        if (host == null) return null;
        if (host.endsWith("reddit.com")) {
            // uBO: reddit.com##+js(trusted-set-local-storage-item, xpromo-consolidation, $currentDate$)
            // — tells Reddit the "use the app" prompt was just shown, so it doesn't force the app.
            return "try{localStorage.setItem('xpromo-consolidation',String(Date.now()));}catch(e){}"
                    + "try{document.cookie='xpromo_persist=1; path=/; max-age=31536000';}catch(e){}";
        }
        return null;
    }

    private static void inject(Context c, CardWebView wv) {
        String fix = AdBlock.enabled(c) ? siteFix(wv.pageHost) : null;
        if (fix != null) wv.evaluateJavascript(fix, null);
        wv.evaluateJavascript(WebMedia.js(background(c)), null);
        wv.evaluateJavascript(PULL_JS, null);
        String css = AdBlock.cosmeticJs(c, wv.pageHost);
        if (css != null) wv.evaluateJavascript(css, null);
    }

    static int blockedOn(WebView wv) { return wv instanceof CardWebView ? ((CardWebView) wv).blocked.get() : 0; }

    static ImageView tool(Context c, int icon, int tint, String label, View.OnClickListener l) {
        ImageView iv = Ui.iconButton(c, icon, tint, l);
        int p = Ui.dp(c, 8);
        iv.setPadding(p, p, p, p);
        iv.setContentDescription(label);
        iv.setTooltipText(label);
        return iv;
    }

    /** "+ Add web page": pick a site or type an address. */
    static void addDialog(final LauncherActivity a, final Callback cb) {
        Ui.dialog(a).setTitle("New tab").setItems(QUICK, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) {
                if (QUICK_URL[w].isEmpty()) askUrl(a, null, cb);
                else cb.onPicked(QUICK_URL[w], QUICK[w]);
            }
        }).show();
    }

    interface Callback { void onPicked(String url, String title); }

    static void askUrl(final Context c, String current, final Callback cb) {
        final EditText e = new EditText(c);
        Ui.tintField(c, e);
        e.setHint("example.com");
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        if (current != null) { e.setText(current); e.setSelectAllOnFocus(true); }
        FrameLayout wrap = new FrameLayout(c);
        wrap.setPadding(Ui.dp(c, 20), Ui.dp(c, 8), Ui.dp(c, 20), 0);
        wrap.addView(e);
        Ui.dialog(c).setTitle("Web address").setView(wrap)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String u = normalise(e.getText().toString());
                        if (!u.isEmpty()) cb.onPicked(u, null);
                    }
                }).setNegativeButton("Cancel", null).show();
    }
}
