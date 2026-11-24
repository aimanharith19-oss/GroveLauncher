/*
 * Copyright 2026 The Canopy Launcher authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.canopy.launcher;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/** Application singleton: shared background executor and main-thread handler. */
public class App extends Application {
    public static final Handler MAIN = new Handler(Looper.getMainLooper());
    public static final ExecutorService IO = Executors.newFixedThreadPool(3, new ThreadFactory() {
        private int n;
        @Override public synchronized Thread newThread(Runnable r) {
            Thread t = new Thread(r, "canopy-io-" + (n++));
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        }
    });

    private static App sInstance;

    public static App get() { return sInstance; }

    // a strong reference: SharedPreferences only holds its listeners weakly
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener prefListener =
            new android.content.SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override public void onSharedPreferenceChanged(android.content.SharedPreferences sp, String key) { Ui.clearPaletteCache(); }
            };

    @Override public void onCreate() {
        super.onCreate();
        sInstance = this;
        if (CrashLog.isCrashProcess(this)) return; // the crash screen runs on its own, bare
        CrashLog.install(this);
        Prefs.sp(this).registerOnSharedPreferenceChangeListener(prefListener);
        KeepAliveService.sync(this);
        // once a week, empty the launcher's cache (a little after start-up, so it never slows opening)
        MAIN.postDelayed(new Runnable() { @Override public void run() { weeklyCacheClear(App.this); } }, 30000);
        registerReceiver(new android.content.BroadcastReceiver() {
            @Override public void onReceive(android.content.Context c, android.content.Intent i) { AppLock.relock(); }
        }, new android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF));
    }

    /** Clears the cache folders once every 7 days; files touched in the last hour are left alone as they may be in use. */
    static void weeklyCacheClear(final android.content.Context c) {
        final long now = System.currentTimeMillis();
        if (now - Prefs.getLong(Prefs.sp(c), "cache_cleared_at", 0) < 7L * 24 * 3600 * 1000) return;
        Prefs.sp(c).edit().putLong("cache_cleared_at", now).apply();
        IO.execute(new Runnable() {
            @Override public void run() {
                try { wipe(c.getCacheDir(), now - 3600 * 1000L, false); } catch (Throwable ignored) { }
                try { wipe(c.getExternalCacheDir(), now - 3600 * 1000L, false); } catch (Throwable ignored) { }
            }
        });
    }

    private static void wipe(java.io.File dir, long before, boolean self) {
        if (dir == null) return;
        java.io.File[] kids = dir.listFiles();
        if (kids != null) for (java.io.File f : kids) {
            if (f.isDirectory()) wipe(f, before, true);
            else if (f.lastModified() < before) //noinspection ResultOfMethodCallIgnored
                f.delete();
        }
        if (self) //noinspection ResultOfMethodCallIgnored
            dir.delete(); // only succeeds if it ended up empty
    }
}
