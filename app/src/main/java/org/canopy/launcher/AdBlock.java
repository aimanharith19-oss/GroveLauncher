/*
 * Copyright 2026 The Canopy Launcher authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.canopy.launcher;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ad & tracker blocking now belongs to Grove Guard (its web tab holds the filter lists, your rules and the log).
 * This only asks it, remembering the answers so pages stay quick. Without Grove Guard nothing is blocked.
 */
public final class AdBlock {
    private AdBlock() { }

    private static final Map<String, Boolean> cache = new LinkedHashMap<String, Boolean>(256, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Entry<String, Boolean> e) { return size() > 2000; }
    };

    public static boolean enabled(Context c) { return Prefs.sp(c).getBoolean("web_adblock", true) && Guard.active(c); }

    public static boolean shouldBlock(Context c, String host, String pageHost) {
        if (host == null || !enabled(c)) return false;
        String key = host + "|" + pageHost;
        synchronized (cache) { Boolean v = cache.get(key); if (v != null) return v; }
        Bundle b = Guard.check(c, "https://" + host + "/", pageHost, false, "popup");
        if (b == null) return false;
        boolean r = b.getBoolean("block");
        synchronized (cache) { cache.put(key, r); }
        return r;
    }

    /** Tells Grove Guard a page was opened, for its web tab → History. */
    public static void visit(final Context c, final String url, final String title) {
        if (!Guard.active(c)) return;
        App.IO.execute(new Runnable() {
            @Override public void run() {
                Bundle x = new Bundle();
                x.putString("title", title);
                Guard.call(c, "visit", url, x);
            }
        });
    }
}
