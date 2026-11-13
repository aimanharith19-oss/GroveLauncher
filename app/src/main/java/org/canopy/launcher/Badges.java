/*
 * Copyright 2026 The Canopy Launcher authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.canopy.launcher;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Notification counts per package, filled by {@link BadgeService} (only if you grant notification access). */
public final class Badges {
    public interface Listener { void onBadgesChanged(); }

    private static final Map<String, Integer> counts = new HashMap<>();
    private static final List<Listener> listeners = new ArrayList<>();

    private Badges() {}


    /** One notification, for the app's long-press menu (memory only). */
    public static final class Note {
        public final String key, title, text;
        public final android.app.PendingIntent open;
        public final boolean autoCancel;
        public final long when;
        Note(String key, String title, String text, android.app.PendingIntent open, boolean autoCancel, long when) {
            this.key = key; this.title = title; this.text = text; this.open = open; this.autoCancel = autoCancel; this.when = when;
        }
    }

    private static final Map<String, List<Note>> notes = new HashMap<>();

    /** The app's current notifications, newest first (empty unless "notifications in app menus" is on). */
    public static List<Note> notes(String pkg) {
        List<Note> n = pkg == null ? null : notes.get(pkg);
        return n == null ? new ArrayList<Note>() : n;
    }

    private static final java.util.Set<String> ongoing = new java.util.HashSet<>();

    /** Apps showing an ongoing (running service) notification right now — package names only. */
    public static boolean hasOngoing(String pkg) { return ongoing.contains(pkg); }

    static void setOngoing(java.util.Set<String> next) {
        ongoing.clear();
        ongoing.addAll(next);
    }

    static void setNotes(Map<String, List<Note>> next) {
        notes.clear();
        notes.putAll(next);
    }


    public static int count(String pkg) {
        if (pkg == null) return 0;
        Integer n = counts.get(pkg);
        return n == null ? 0 : n;
    }

    static void set(Map<String, Integer> next) {
        counts.clear();
        counts.putAll(next);
        for (Listener l : new ArrayList<>(listeners)) l.onBadgesChanged();
    }

    public static void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    public static void removeListener(Listener l) { listeners.remove(l); }
}
