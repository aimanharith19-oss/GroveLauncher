package org.canopy.launcher;

import android.content.Context;
import android.os.Looper;
import android.os.SystemClock;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * Settings > Record lag: times every job on the launcher's main thread. A job that takes longer than ~50 ms
 * is a visible stutter; those are kept (last 150) with what was running and, when it hung long enough,
 * where in the code it was stuck. "Share lag report" sends the list, so a stutter can be fixed exactly.
 */
final class LagMonitor {
    private LagMonitor() { }

    private static volatile boolean on;
    private static volatile long startedAt, seq;
    private static volatile String current;
    private static volatile String stack;
    private static long sampledSeq = -1;
    private static final ArrayDeque<String> log = new ArrayDeque<>();
    private static Thread watch;

    static synchronized void sync(Context c) {
        boolean want = Prefs.sp(c).getBoolean("lag_record", false);
        if (want == on) return;
        on = want;
        if (!want) { Looper.getMainLooper().setMessageLogging(null); return; }
        final Thread main = Looper.getMainLooper().getThread();
        Looper.getMainLooper().setMessageLogging(new android.util.Printer() {
            @Override public void println(String x) {
                if (x.startsWith(">")) { current = x; stack = null; startedAt = SystemClock.uptimeMillis(); seq++; }
                else if (startedAt > 0) {
                    long d = SystemClock.uptimeMillis() - startedAt;
                    startedAt = 0;
                    if (d >= 50) add(d, current, stack);
                }
            }
        });
        if (watch == null) {
            watch = new Thread(new Runnable() {
                @Override public void run() {
                    while (true) {
                        try { Thread.sleep(30); } catch (InterruptedException e) { return; }
                        if (!on) continue;
                        long s = startedAt, q = seq;
                        if (s > 0 && q != sampledSeq && SystemClock.uptimeMillis() - s > 70) {
                            sampledSeq = q;
                            StringBuilder b = new StringBuilder();
                            int n = 0;
                            for (StackTraceElement e : main.getStackTrace()) {
                                if (n++ >= 14) break;
                                b.append("\n      at ").append(e.toString());
                            }
                            stack = b.toString();
                        }
                    }
                }
            }, "grove-lag");
            watch.setDaemon(true);
            watch.start();
        }
    }

    private static synchronized void add(long ms, String what, String where) {
        String w = what == null ? "?" : what.replace(">>>>> Dispatching to ", "");
        String line = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + "  " + ms + " ms  " + w
                + (where != null ? where : "");
        log.addLast(line);
        while (log.size() > 150) log.removeFirst();
    }

    static synchronized String report(Context c) {
        StringBuilder b = new StringBuilder("Grove Launcher lag report\n");
        b.append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL)
                .append(", Android ").append(android.os.Build.VERSION.RELEASE).append('\n');
        try { b.append("Launcher ").append(c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName).append('\n'); } catch (Throwable ignored) { }
        b.append("Jobs on the main thread that took 50 ms or more (newest last):\n\n");
        if (log.isEmpty()) b.append("(nothing recorded yet)\n");
        for (String s : log) b.append(s).append("\n\n");
        return b.toString();
    }
}
