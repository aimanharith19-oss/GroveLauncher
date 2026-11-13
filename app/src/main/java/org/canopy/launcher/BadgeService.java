/*
 * Copyright 2026 The Canopy Launcher authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.canopy.launcher;

import android.app.Notification;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.HashMap;
import java.util.Map;

/**
 * Notification dots / counts on icons (Nova "notification badges", Microsoft Launcher "badges").
 * Package names and counts are kept, in memory. For the app menus (long-press an app), each notification's
 * title and text are kept too — in memory only, never saved or sent anywhere. Both can be
 * turned off in settings.
 */
public class BadgeService extends NotificationListenerService {
    private static BadgeService instance;

    @Override public void onListenerConnected() { instance = this; recount(); MusicHistory.start(this); }

    /** Opened from an app menu: dismiss it like the notification shade does. */
    static void dismiss(String key) {
        try { if (instance != null && key != null) instance.cancelNotification(key); } catch (Exception ignored) { }
    }
    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        try { NotifHistory.record(this, sbn); } catch (Throwable ignored) { }
        recount();
    }
    @Override public void onNotificationRemoved(StatusBarNotification sbn) { recount(); }
    @Override public void onListenerDisconnected() { instance = null; MusicHistory.stop(); post(new HashMap<String, Integer>()); }

    private void recount() {
        final Map<String, Integer> m = new HashMap<>();
        boolean menu = Prefs.sp(this).getBoolean("menu_notifications", true);
        final Map<String, java.util.List<Badges.Note>> notes = new HashMap<>();
        final java.util.Set<String> ongoing = new java.util.HashSet<>();
        try {
            StatusBarNotification[] all = getActiveNotifications();
            if (all != null) for (StatusBarNotification n : all) {
                if (!n.isClearable() || n.isOngoing()) { ongoing.add(n.getPackageName()); continue; }
                Notification no = n.getNotification();
                if ((no.flags & Notification.FLAG_GROUP_SUMMARY) != 0) continue;
                int add = no.number > 0 ? no.number : 1;
                Integer cur = m.get(n.getPackageName());
                m.put(n.getPackageName(), (cur == null ? 0 : cur) + add);
                if (menu && no.extras != null) {
                    CharSequence t = no.extras.getCharSequence(Notification.EXTRA_TITLE), x = no.extras.getCharSequence(Notification.EXTRA_TEXT);
                    if ((t != null || x != null) && no.visibility != Notification.VISIBILITY_SECRET) {
                        java.util.List<Badges.Note> l = notes.get(n.getPackageName());
                        if (l == null) { l = new java.util.ArrayList<>(); notes.put(n.getPackageName(), l); }
                        l.add(new Badges.Note(n.getKey(), t == null ? "" : t.toString(), x == null ? "" : x.toString().replace('\n', ' '),
                                no.contentIntent, (no.flags & Notification.FLAG_AUTO_CANCEL) != 0, n.getPostTime()));
                    }
                }
            }
        } catch (Exception ignored) { }
        for (java.util.List<Badges.Note> l : notes.values()) java.util.Collections.sort(l, new java.util.Comparator<Badges.Note>() {
            @Override public int compare(Badges.Note a, Badges.Note b) { return Long.compare(b.when, a.when); }
        });
        App.MAIN.post(new Runnable() { @Override public void run() { Badges.setNotes(notes); Badges.setOngoing(ongoing); } });
        post(m);
    }

    private static void post(final Map<String, Integer> m) {
        App.MAIN.post(new Runnable() { @Override public void run() { Badges.set(m); } });
    }
}
