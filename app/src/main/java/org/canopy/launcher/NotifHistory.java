/*
 * Copyright 2026 The Canopy Launcher authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.canopy.launcher;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.StatusBarNotification;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Notification history for the feed: every notification you get is kept here even after it's dismissed or
 * cleared from the shade, so nothing is lost. Stored only in a file on this phone (never sent anywhere),
 * kept for the period chosen in Settings, and cleared only when you clear it.
 */
public final class NotifHistory {
    private NotifHistory() { }

    public static final class Entry {
        public String pkg, title, text, key;
        public long when;
        /** Only while Canopy is running and the app hasn't cancelled it; otherwise the app is opened. */
        public PendingIntent open;
    }

    public interface Listener { void onHistoryChanged(); }

    private static final int MAX = 2000;
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final List<Entry> entries = new ArrayList<>(); // oldest first
    private static final Map<String, String> lastByKey = new HashMap<>();
    private static final List<Listener> listeners = new ArrayList<>();
    private static boolean loaded;
    private static Context app;

    public static boolean enabled(Context c) { return Prefs.sp(c).getBoolean("notif_history", true); }

    /** How long to keep notifications, in days (0 = forever, up to the size limit). */
    static int keepDays(Context c) {
        try { return Integer.parseInt(Prefs.sp(c).getString("notif_history_keep", "30")); } catch (Exception e) { return 30; }
    }

    private static File file(Context c) { return new File(c.getFilesDir(), "notification_history.tsv"); }

    public static synchronized void ensureLoaded(Context c) {
        if (app == null) app = c.getApplicationContext();
        if (loaded) return;
        loaded = true;
        File f = file(app);
        if (!f.exists()) return;
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                String[] p = line.split("\t", -1);
                if (p.length < 5) continue;
                Entry e = new Entry();
                e.when = Long.parseLong(p[0]);
                e.pkg = p[1];
                e.key = p[2];
                e.title = unesc(p[3]);
                e.text = unesc(p[4]);
                entries.add(e);
            }
            br.close();
        } catch (Exception ignored) { }
        prune();
    }

    private static String esc(String s) { return s == null ? "" : s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n"); }

    private static String unesc(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                b.append(n == 't' ? '\t' : n == 'n' ? '\n' : n);
            } else b.append(ch);
        }
        return b.toString();
    }

    /** Called by the notification listener for every new or updated notification. */
    static void record(Context c, StatusBarNotification sbn) {
        if (!enabled(c)) return;
        ensureLoaded(c);
        if (c.getPackageName().equals(sbn.getPackageName())) return;
        Notification n = sbn.getNotification();
        if (n == null || sbn.isOngoing() || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
        if (Notification.CATEGORY_TRANSPORT.equals(n.category) || Notification.CATEGORY_PROGRESS.equals(n.category)
                || Notification.CATEGORY_SERVICE.equals(n.category)) return;
        Bundle x = n.extras;
        if (x == null) return;
        CharSequence title = x.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = x.getCharSequence(Notification.EXTRA_BIG_TEXT);
        if (text == null) text = x.getCharSequence(Notification.EXTRA_TEXT);
        // chats: use the newest message
        Object[] msgs = null;
        try { msgs = (Object[]) x.getParcelableArray(Notification.EXTRA_MESSAGES); } catch (Exception ignored) { }
        if (msgs != null && msgs.length > 0 && msgs[msgs.length - 1] instanceof Bundle) {
            Bundle m = (Bundle) msgs[msgs.length - 1];
            CharSequence mt = m.getCharSequence("text"), who = m.getCharSequence("sender");
            if (mt != null) text = who != null && title != null && !title.toString().contains(who) ? who + ": " + mt : mt;
        }
        if (title == null && text == null) return;
        String t = title == null ? "" : title.toString().trim(), s = text == null ? "" : text.toString().trim();
        if (n.visibility == Notification.VISIBILITY_SECRET) s = "(hidden content)";
        final Entry e = new Entry();
        e.when = sbn.getPostTime() > 0 ? sbn.getPostTime() : System.currentTimeMillis();
        e.pkg = sbn.getPackageName();
        e.key = sbn.getKey();
        e.title = t;
        e.text = s;
        e.open = n.contentIntent;
        H.post(new Runnable() { @Override public void run() { add(e); } });
    }

    private static void add(Entry e) {
        String sig = e.title + "\u0001" + e.text;
        if (sig.equals(lastByKey.get(e.key))) { // same notification re-posted unchanged (progress, re-alert…)
            for (int i = entries.size() - 1; i >= 0 && i >= entries.size() - 50; i--) {
                if (entries.get(i).key.equals(e.key)) { entries.get(i).open = e.open; break; }
            }
            return;
        }
        lastByKey.put(e.key, sig);
        entries.add(e);
        prune();
        save();
        notifyListeners();
    }

    private static void prune() {
        int days = app == null ? 30 : keepDays(app);
        if (days > 0) {
            long since = System.currentTimeMillis() - days * 24L * 3600 * 1000;
            int drop = 0;
            while (drop < entries.size() && entries.get(drop).when < since) drop++;
            if (drop > 0) entries.subList(0, drop).clear();
        }
        if (entries.size() > MAX) entries.subList(0, entries.size() - MAX).clear();
    }

    private static final Runnable SAVE = new Runnable() {
        @Override public void run() {
            if (app == null) return;
            final List<Entry> copy = new ArrayList<>(entries);
            final File f = file(app);
            App.IO.execute(new Runnable() {
                @Override public void run() {
                    try {
                        File tmp = new File(f.getPath() + ".tmp");
                        Writer w = new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8");
                        for (Entry e : copy) w.write(e.when + "\t" + e.pkg + "\t" + esc(e.key) + "\t" + esc(e.title) + "\t" + esc(e.text) + "\n");
                        w.close();
                        //noinspection ResultOfMethodCallIgnored
                        tmp.renameTo(f);
                    } catch (Exception ignored) { }
                }
            });
        }
    };

    /** Batched so a burst of notifications writes the file once. */
    private static void save() {
        H.removeCallbacks(SAVE);
        H.postDelayed(SAVE, 1500);
    }

    /** Newest first, optionally only one app. */
    public static List<Entry> recent(String pkg, int max) {
        List<Entry> out = new ArrayList<>();
        for (int i = entries.size() - 1; i >= 0 && out.size() < max; i--) {
            Entry e = entries.get(i);
            if (pkg == null || pkg.equals(e.pkg)) out.add(e);
        }
        return out;
    }

    public static int size() { return entries.size(); }

    /** Apps in the history, most recent first. */
    public static List<String> apps() {
        List<String> out = new ArrayList<>();
        for (int i = entries.size() - 1; i >= 0; i--) if (!out.contains(entries.get(i).pkg)) out.add(entries.get(i).pkg);
        return out;
    }

    public static void remove(Entry e) {
        entries.remove(e);
        save();
        notifyListeners();
    }

    public static void clear(Context c, String pkg) {
        ensureLoaded(c);
        if (pkg == null) entries.clear();
        else for (int i = entries.size() - 1; i >= 0; i--) if (pkg.equals(entries.get(i).pkg)) entries.remove(i);
        if (pkg == null) lastByKey.clear();
        save();
        notifyListeners();
    }

    public static void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    public static void removeListener(Listener l) { listeners.remove(l); }

    private static final Runnable NOTIFY = new Runnable() {
        @Override public void run() { for (Listener l : new ArrayList<>(listeners)) l.onHistoryChanged(); }
    };

    private static void notifyListeners() {
        H.removeCallbacks(NOTIFY);
        H.postDelayed(NOTIFY, 200);
    }
}
