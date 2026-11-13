/*
 * Copyright 2026 The Grove Launcher authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.canopy.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * Optional: keeps Grove in memory so Android doesn't close it (and so web tabs never have to reload).
 * Shows one silent, minimised notification; does no work of its own, so it costs next to no battery.
 */
public class KeepAliveService extends Service {
    private static final String CHANNEL = "keep_alive";

    static boolean enabled(Context c) { return Prefs.sp(c).getBoolean("keep_alive", false); }

    /** Start or stop to match the setting. Safe to call any time. */
    static void sync(Context c) {
        Intent i = new Intent(c, KeepAliveService.class);
        try {
            if (enabled(c)) {
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
            } else c.stopService(i);
        } catch (Exception ignored) { }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        // Android requires startForeground() promptly after startForegroundService(); if it can't, stop quietly.
        Notification n;
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, "Keep Grove running", NotificationManager.IMPORTANCE_MIN);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
            PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, SettingsActivity.class), PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
            n = b.setSmallIcon(R.drawable.ic_home).setContentTitle("Grove is running")
                    .setContentText("Kept in memory so it never restarts").setOngoing(true).setContentIntent(pi).build();
        } catch (Throwable t) {
            n = new Notification();
        }
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(43, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(43, n);
        } catch (Throwable e) { stopSelf(); return START_NOT_STICKY; }
        return enabled(this) ? START_STICKY : START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
