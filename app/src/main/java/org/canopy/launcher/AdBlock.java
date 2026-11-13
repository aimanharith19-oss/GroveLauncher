/*
 * Copyright 2026 The Canopy Launcher authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.canopy.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Ad & tracker blocking for the feed's web tabs, using uBlock Origin's own filter lists from GitHub
 * (uBlock filters, EasyList, EasyPrivacy, Peter Lowe's list). A copy ships with the app so it works at once;
 * fresh lists are downloaded from GitHub weekly or when you tap "Update filter lists".
 */
public final class AdBlock {
    private AdBlock() { }

    static final String BASE = "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/";
    static final String[] LISTS = {
            "filters/filters.txt", "filters/privacy.txt", "filters/badware.txt", "filters/quick-fixes.txt",
            "thirdparties/easylist/easylist.txt", "thirdparties/easylist/easyprivacy.txt",
            // annoyances: "open in app" nags, app-only walls (e.g. Reddit forcing its app), pop-overs
            "filters/annoyances-others.txt", "thirdparties/easylist/easylist-annoyances.txt",
            "thirdparties/easylist/easylist-notifications.txt",
            "thirdparties/pgl.yoyo.org/as/serverlist"};
    private static final long WEEK = 7L * 24 * 3600 * 1000;
    private static final int BUNDLE = 2;

    private static volatile AdRules rules;
    private static boolean loading, updating;

    public interface Done { void done(boolean ok, String message); }

    static SharedPreferences sp(Context c) { return Prefs.sp(c); }
    public static boolean enabled(Context c) { return sp(c).getBoolean("web_adblock", true); }

    private static File stored(Context c) { return new File(c.getFilesDir(), "adblock-rules.gz"); }

    public static int ruleCount() { AdRules r = rules; return r == null ? 0 : r.size(); }

    /** Loads the rules in the background (saved update if there is one, else the bundled copy). */
    public static synchronized void init(final Context ctx) {
        if (rules != null || loading) return;
        loading = true;
        final Context c = ctx.getApplicationContext();
        App.IO.execute(new Runnable() {
            @Override public void run() {
                AdRules r = new AdRules();
                InputStream in = null;
                // a newer app version bundles newer lists (now with annoyance filters): drop older downloads
                if (sp(c).getInt("web_adblock_bundle", 0) < BUNDLE) {
                    //noinspection ResultOfMethodCallIgnored
                    stored(c).delete();
                    sp(c).edit().putInt("web_adblock_bundle", BUNDLE).apply();
                }
                try {
                    File f = stored(c);
                    in = f.exists() ? new FileInputStream(f) : c.getAssets().open("adblock.gz");
                    r.readCompact(new BufferedReader(new InputStreamReader(new GZIPInputStream(in), "UTF-8")));
                } catch (Exception ignored) {
                } finally {
                    try { if (in != null) in.close(); } catch (Exception ignored) { }
                }
                rules = r;
                synchronized (AdBlock.class) { loading = false; }
                long last = sp(c).getLong("web_adblock_updated", 0);
                if (enabled(c) && System.currentTimeMillis() - last > WEEK) update(c, null);
            }
        });
    }

    /** Downloads the latest lists from GitHub and swaps them in. */
    public static void update(final Context ctx, final Done done) {
        synchronized (AdBlock.class) {
            if (updating) { if (done != null) done.done(false, "Already updating"); return; }
            updating = true;
        }
        final Context c = ctx.getApplicationContext();
        App.IO.execute(new Runnable() {
            @Override public void run() {
                AdRules r = new AdRules();
                int ok = 0;
                java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>(java.util.Arrays.asList(LISTS));
                java.util.Set<String> seen = new java.util.HashSet<>();
                while (!queue.isEmpty()) {
                    String path = queue.poll();
                    if (!seen.add(path)) continue;
                    HttpURLConnection con = null;
                    try {
                        con = (HttpURLConnection) new URL(BASE + path).openConnection();
                        con.setConnectTimeout(15000);
                        con.setReadTimeout(30000);
                        if (con.getResponseCode() != 200) continue;
                        BufferedReader br = new BufferedReader(new InputStreamReader(con.getInputStream(), "UTF-8"));
                        String dir = path.contains("/") ? path.substring(0, path.lastIndexOf('/') + 1) : "";
                        String line;
                        while ((line = br.readLine()) != null) {
                            // uBlock lists pull in sub-lists with "!#include file.txt" (like uBlock Origin does)
                            if (line.startsWith("!#include ")) { queue.add(dir + line.substring(10).trim()); continue; }
                            r.parseLine(line.trim());
                        }
                        if (java.util.Arrays.asList(LISTS).contains(path)) ok++;
                    } catch (Exception ignored) {
                    } finally {
                        if (con != null) con.disconnect();
                    }
                }
                final boolean success = ok >= LISTS.length - 1 && r.size() > 1000;
                if (success) {
                    try {
                        File tmp = new File(c.getFilesDir(), "adblock-rules.tmp");
                        Writer w = new OutputStreamWriter(new GZIPOutputStream(new FileOutputStream(tmp)), "UTF-8");
                        r.write(w);
                        w.close();
                        //noinspection ResultOfMethodCallIgnored
                        tmp.renameTo(stored(c));
                    } catch (Exception ignored) { }
                    rules = r;
                    sp(c).edit().putLong("web_adblock_updated", System.currentTimeMillis()).apply();
                }
                synchronized (AdBlock.class) { updating = false; }
                final int count = r.size();
                final int got = ok;
                if (done != null) App.MAIN.post(new Runnable() {
                    @Override public void run() {
                        done.done(success, success ? "Updated: " + count + " blocking rules from " + got + " lists"
                                : "Couldn't reach GitHub \u2014 kept the current lists");
                    }
                });
            }
        });
    }

    /** Full check, including path rules (e.g. Reddit's "use the app" modal scripts). */
    public static boolean shouldBlock(Context c, android.net.Uri u, String pageHost) {
        AdRules r = rules;
        if (r == null || !enabled(c) || u == null) return false;
        String path = u.getEncodedPath();
        if (u.getEncodedQuery() != null) path = (path == null ? "" : path) + "?" + u.getEncodedQuery();
        return r.shouldBlock(u.getHost(), path == null ? "/" : path, pageHost);
    }

    public static boolean shouldBlock(Context c, String host, String pageHost) {
        AdRules r = rules;
        return r != null && enabled(c) && r.shouldBlock(host, pageHost);
    }

    /** JavaScript that adds this site's element-hiding CSS, or null. */
    public static String cosmeticJs(Context c, String pageHost) {
        AdRules r = rules;
        if (r == null || !enabled(c)) return null;
        String css = r.cssFor(pageHost);
        if (css == null) return null;
        String esc = css.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ");
        return "(function(){if(document.getElementById('cnp-ab'))return;var s=document.createElement('style');s.id='cnp-ab';"
                + "s.textContent='" + esc + "';(document.head||document.documentElement).appendChild(s);})();";
    }
}
