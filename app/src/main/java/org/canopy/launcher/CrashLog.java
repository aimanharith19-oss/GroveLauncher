/*
 * Copyright 2026 The Canopy Launcher authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.canopy.launcher;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

/** Saves the last crash to a local file (never sent anywhere) so it can be shown in Settings. */
public final class CrashLog {
    private CrashLog() {}

    private static File file(Context c) { return new File(c.getFilesDir(), "last_crash.txt"); }

    public static void install(final Context app) {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread t, Throwable e) {
                try {
                    StringWriter sw = new StringWriter();
                    e.printStackTrace(new PrintWriter(sw));
                    String txt = "Grove crash on " + Build.MANUFACTURER + " " + Build.MODEL
                            + " (Android " + Build.VERSION.RELEASE + ", API " + Build.VERSION.SDK_INT + ")\n"
                            + "Thread: " + t.getName() + "\n\n" + sw;
                    FileOutputStream out = new FileOutputStream(file(app));
                    out.write(txt.getBytes(StandardCharsets.UTF_8));
                    out.close();
                } catch (Throwable ignored) { }
                if (prev != null) prev.uncaughtException(t, e);
            }
        });
    }

    public static String read(Context c) {
        File f = file(c);
        if (!f.exists()) return null;
        try { return HomeStore.readAll(new FileInputStream(f)); } catch (Exception e) { return null; }
    }

    public static void clear(Context c) { file(c).delete(); }
}
