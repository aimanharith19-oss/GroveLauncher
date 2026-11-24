/*
 * Copyright 2026 The Canopy Launcher authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.canopy.launcher;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/** The feed browser's history (kept on the phone only), and the two-way link with Grove Browser's bookmarks and history. */
final class WebHistory {
    private WebHistory() {}

    static final String BROWSER_PKG = "app.grove.browser";
    private static final String AUTH = "content://app.grove.browser.sync";
    private static final int MAX = 500;
    private static final List<String[]> items = new ArrayList<>(); // {url, title, time}, oldest first
    private static boolean loaded;

    private static File file(Context c) { return new File(c.getFilesDir(), "web_history.json"); }

    static synchronized void load(Context c) {
        if (loaded) return;
        loaded = true;
        File f = file(c);
        if (!f.exists() || f.length() > 4L * 1024 * 1024) return;
        try {
            JSONArray a = new JSONArray(HomeStore.readAll(new FileInputStream(f)));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                items.add(new String[]{o.optString("url"), o.optString("title"), String.valueOf(o.optLong("time"))});
            }
        } catch (Throwable ignored) { items.clear(); }
    }

    private static void save(Context c) {
        final JSONArray a = new JSONArray();
        try { for (String[] v : items) a.put(new JSONObject().put("url", v[0]).put("title", v[1]).put("time", Long.parseLong(v[2]))); } catch (Exception ignored) { }
        final File f = file(c);
        App.IO.execute(new Runnable() {
            @Override public void run() {
                try {
                    File tmp = new File(f.getPath() + ".tmp");
                    FileOutputStream o = new FileOutputStream(tmp);
                    o.write(a.toString().getBytes("UTF-8"));
                    o.close();
                    //noinspection ResultOfMethodCallIgnored
                    tmp.renameTo(f);
                } catch (Throwable ignored) { }
            }
        });
    }

    static synchronized void add(Context c, String url, String title) {
        if (url == null || !url.startsWith("http")) return;
        load(c);
        if (title == null) title = "";
        if (title.length() > 200) title = title.substring(0, 200);
        if (url.length() > 2000) return;
        String[] v = {url, title, String.valueOf(System.currentTimeMillis())};
        if (!items.isEmpty() && items.get(items.size() - 1)[0].equals(url)) items.set(items.size() - 1, v);
        else items.add(v);
        while (items.size() > MAX) items.remove(0);
        save(c);
    }

    /** Newest first. */
    static synchronized List<String[]> recent(Context c, int max) {
        load(c);
        List<String[]> out = new ArrayList<>();
        for (int i = items.size() - 1; i >= 0 && out.size() < max; i--) out.add(items.get(i));
        return out;
    }

    static synchronized void remove(Context c, String[] v) { load(c); items.remove(v); save(c); }

    static synchronized void clear(Context c) { load(c); items.clear(); save(c); }

    // ------------------------------------------------------------------ Grove Browser link

    static boolean browserInstalled(Context c) {
        try { c.getPackageManager().getPackageInfo(BROWSER_PKG, 0); return true; } catch (Exception e) { return false; }
    }

    /**
     * Two-way merge with Grove Browser (it has to be installed, and a version that offers the link). Nothing is deleted on
     * either side. Blocking: call it off the main thread. Returns a short message.
     */
    static synchronized String sync(Context c) {
        if (!browserInstalled(c)) return "Grove Browser isn't installed";
        try {
            Uri u = Uri.parse(AUTH);
            Bundle got = c.getContentResolver().call(u, "get", null, null);
            if (got == null) return "Update Grove Browser to link it";
            load(c);
            int addedB = 0, addedH = 0;
            // bookmarks
            List<String[]> mine = FeedWeb.bookmarks(c);
            JSONArray theirB = new JSONArray(got.getString("bookmarks", "[]"));
            for (int i = 0; i < theirB.length(); i++) {
                JSONObject o = theirB.getJSONObject(i);
                String url = o.optString("url");
                if (url.isEmpty() || !url.startsWith("http")) continue;
                boolean has = false;
                for (String[] b : mine) if (b[0].equals(url)) { has = true; break; }
                if (!has) { String t = o.optString("title"); mine.add(new String[]{url, t.isEmpty() ? FeedPage.siteName(url) : t}); addedB++; }
            }
            if (addedB > 0) FeedWeb.saveBookmarks(c, mine);
            // history
            JSONArray theirH = new JSONArray(got.getString("history", "[]"));
            for (int i = 0; i < theirH.length(); i++) {
                JSONObject o = theirH.getJSONObject(i);
                String url = o.optString("url");
                long time = o.optLong("time");
                if (!url.startsWith("http") || url.length() > 2000) continue;
                boolean has = false;
                for (String[] h : items) if (h[0].equals(url) && Long.parseLong(h[2]) == time) { has = true; break; }
                if (!has) { items.add(new String[]{url, o.optString("title"), String.valueOf(time)}); addedH++; }
            }
            java.util.Collections.sort(items, new java.util.Comparator<String[]>() {
                @Override public int compare(String[] a, String[] b) { return Long.compare(Long.parseLong(a[2]), Long.parseLong(b[2])); }
            });
            while (items.size() > MAX) items.remove(0);
            if (addedH > 0) save(c);
            // send ours back
            Bundle give = new Bundle();
            JSONArray mb = new JSONArray();
            for (String[] b : FeedWeb.bookmarks(c)) mb.put(new JSONObject().put("url", b[0]).put("title", b[1]));
            JSONArray mh = new JSONArray();
            for (String[] h : items) mh.put(new JSONObject().put("url", h[0]).put("title", h[1]).put("time", Long.parseLong(h[2])));
            give.putString("bookmarks", mb.toString());
            give.putString("history", mh.toString());
            c.getContentResolver().call(u, "put", null, give);
            return "Linked with Grove Browser: " + addedB + " new bookmarks, " + addedH + " history entries";
        } catch (Throwable e) {
            return "Couldn't reach Grove Browser (open it once, or update it)";
        }
    }

    /** Quietly links with the browser at most every 15 minutes while the launcher is used. */
    static void autoSync(final Context c) {
        if (!Prefs.sp(c).getBoolean("browser_link", true) || !browserInstalled(c)) return;
        long last = Prefs.getLong(Prefs.sp(c), "browser_link_at", 0);
        if (System.currentTimeMillis() - last < 15 * 60 * 1000L) return;
        Prefs.sp(c).edit().putLong("browser_link_at", System.currentTimeMillis()).apply();
        App.IO.execute(new Runnable() { @Override public void run() { sync(c); } });
    }
}
