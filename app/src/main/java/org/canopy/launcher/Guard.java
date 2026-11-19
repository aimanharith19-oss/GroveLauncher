/*
 * Copyright 2026 The Canopy Launcher authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.canopy.launcher;

import android.content.ContentProviderClient;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

/**
 * Connection to Groveguard (Grove's malware scanner / firewall / web filter). When it's installed and "Use Groveguard"
 * is on, the feed browser asks Groveguard about every page and request, so ads, trackers, 18+ sites, dangerous sites and
 * your own allow/block rules are all controlled — and logged — in Groveguard's web tab. Android tells Groveguard which
 * app is asking, so only Grove Launcher can use this.
 */
public final class Guard {
    private Guard() { }

    static final String PKG = "org.grove.guard";
    private static final Uri URI = Uri.parse("content://org.grove.guard.status");
    private static volatile ContentProviderClient client;
    private static volatile long failedAt;

    public static boolean wanted(Context c) { return Prefs.sp(c).getBoolean("use_groveguard", true); }

    private static boolean installed(Context c) {
        try { c.getPackageManager().getPackageInfo(PKG, 0); return true; } catch (Exception e) { return false; }
    }

    /** Groveguard handles web filtering right now (installed, switched on, and answering). */
    public static boolean active(Context c) {
        if (!wanted(c)) return false;
        if (System.currentTimeMillis() - failedAt < 30000) return false; // don't hammer it after a failure
        return installed(c);
    }

    static Bundle call(Context c, String method, String arg, Bundle extras) {
        // never wait for Groveguard on the main thread: a slow answer would freeze the launcher ("not responding")
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return null;
        try {
            ContentProviderClient cl = client;
            if (cl == null) client = cl = c.getContentResolver().acquireUnstableContentProviderClient(URI);
            if (cl == null) { failedAt = System.currentTimeMillis(); return null; }
            return cl.call(method, arg, extras);
        } catch (Exception e) {
            ContentProviderClient cl = client;
            client = null;
            if (cl != null) try { cl.close(); } catch (Exception ignored) { }
            failedAt = System.currentTimeMillis();
            return null;
        }
    }

    /** Asks Groveguard about one page or request. Null if Groveguard can't answer (then the launcher's own blocker is used). */
    public static Bundle check(Context c, String url, String pageHost, boolean main, String type) {
        Bundle e = new Bundle();
        e.putString("page", pageHost);
        e.putBoolean("main", main);
        if (type != null) e.putString("type", type);
        return call(c, "checkRequest", url, e);
    }

    /** CSS that hides known ads on this site, from Groveguard's lists. */
    public static String css(Context c, String pageHost) {
        Bundle b = call(c, "css", pageHost, null);
        return b == null ? null : b.getString("css");
    }

    /** Everything for one page from Groveguard 1.10+: "css", "js" (scriptlets that switch off ad scripts), "ex" (exceptions), "v". Null with older versions. */
    public static Bundle cosmetic(Context c, String pageHost) {
        Bundle b = call(c, "cosmetic", pageHost, null);
        return b == null || !b.containsKey("ex") ? null : b;
    }

    private static String surveyor;
    private static int surveyorV;

    /** Script that hides elements matching the generic cosmetic filters (same for every page; fetched again when the lists change). */
    public static String surveyor(final Context c, final int version) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            if (surveyor == null || version != surveyorV) App.IO.execute(new Runnable() { @Override public void run() { surveyor(c, version); } });
            return surveyor;
        }
        if (surveyor == null || version != surveyorV) {
            Bundle b = call(c, "surveyor", null, null);
            if (b == null) return null;
            surveyor = b.getString("js");
            surveyorV = b.getInt("v");
        }
        return surveyor;
    }

    /** Sends a control command (realtime on/off, scan, update…) without needing Groveguard's automation key. */
    public static boolean control(final Context c, final String action, final Bundle extras) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            App.IO.execute(new Runnable() { @Override public void run() { control(c, action, extras); } });
            return active(c);
        }
        Bundle b = call(c, "control", action, extras);
        return b != null && b.getBoolean("ok");
    }

    /** Windows-Phone-style page shown instead of a blocked website. */
    public static String blockPage(String kind, String host, String why) {
        String title = "adult".equals(kind) ? "18+ site blocked" : "you".equals(kind) ? "site blocked" : "dangerous site blocked";
        String text = "adult".equals(kind) ? "This site is on an adult-content list switched on in Groveguard."
                : "you".equals(kind) ? "You added this site to your block list in Groveguard."
                : "This site is on Groveguard's list of malware, phishing, tracker and ad sites. Don't enter passwords or card details.";
        String h = host == null ? "" : host.replace("&", "&amp;").replace("<", "&lt;").replace(".", "[.]");
        String w = why == null || why.isEmpty() ? ("adult".equals(kind) ? "18+ list" : "you".equals(kind) ? "your block list" : "dangerous-site list") : why;
        w = w.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
        return "<!doctype html><html><head><meta name=viewport content='width=device-width,initial-scale=1'><style>"
                + "body{margin:0;background:#000;color:#fff;font-family:sans-serif;font-weight:300;min-height:100vh;"
                + "background-image:linear-gradient(" + ("adult".equals(kind) ? "#3b1f5a" : "#5a0a04") + ",#000 55%)}"
                + "main{padding:28px 22px}.a{font-weight:600;font-size:15px}h1{font-weight:300;font-size:42px;margin:10px 0 16px}"
                + "p{color:#ddd;font-size:16px;line-height:1.45}.h{font-size:22px;color:#fff;word-break:break-all}"
                + ".w{border-left:3px solid " + ("adult".equals(kind) ? "#a05ad8" : "#e51400") + ";padding:4px 0 4px 12px;color:#fff}"
                + "button{font:inherit;font-size:18px;color:#fff;background:#e51400;border:0;padding:12px 22px;margin-top:14px}</style></head>"
                + "<body><main><div class=a>GROVEGUARD</div><h1>" + title + "</h1><div class=h>" + h + "</div><p>" + text
                + "</p><p class=w>Reason: " + w + "</p><p style='color:#8a8a8a'>Change this in Groveguard → web.</p>"
                + "<button onclick='history.length>1?history.back():location.replace(\"about:blank\")'>go back</button></main></body></html>";
    }
}
