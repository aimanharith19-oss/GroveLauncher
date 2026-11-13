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

    @Override public void onCreate() {
        super.onCreate();
        sInstance = this;
        CrashLog.install(this);
        KeepAliveService.sync(this);
        registerReceiver(new android.content.BroadcastReceiver() {
            @Override public void onReceive(android.content.Context c, android.content.Intent i) { AppLock.relock(); }
        }, new android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF));
    }
}
