/*
 * Copyright 2026 The Canopy Launcher authors
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
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

/**
 * A proper media notification for video / music playing in a feed web tab (like a music app or Netflix):
 * artwork, title and site, play / pause, previous / next (or skip 10 s), a seek bar, and lock-screen and
 * headphone-button controls through a MediaSession. It also keeps playback going in the background.
 */
public class MediaService extends Service {
    static final String ACTION_STOP = "org.canopy.launcher.media.STOP";
    static final String ACTION_PLAY = "org.canopy.launcher.media.PLAY";
    static final String ACTION_PAUSE = "org.canopy.launcher.media.PAUSE";
    static final String ACTION_NEXT = "org.canopy.launcher.media.NEXT";
    static final String ACTION_PREV = "org.canopy.launcher.media.PREV";
    private static final String CHANNEL = "web_media";
    private static final int ID = 42;

    /*
     * Android kills the app if a service started with startForegroundService() is stopped before it has
     * called startForeground(). So nothing outside ever stops it while it's starting: we ask it to stop,
     * and it does so itself once it's safely in the foreground.
     */
    private static boolean running, stopWanted;
    private static MediaService sSelf;
    private MediaSession session;
    private boolean foreground, started;

    /** Show / update the player for what's playing now. */
    static void show(Context c) {
        stopWanted = false;
        if (running) { refresh(); return; }
        Intent i = new Intent(c, MediaService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
            running = true;
        } catch (Exception ignored) { } // Android may refuse while in the background; nothing to show then
    }

    static void refresh() { if (sSelf != null) sSelf.update(); }

    static void stop(Context c) {
        if (!running) return;
        stopWanted = true;
        if (sSelf != null && sSelf.started) sSelf.finish();
    }

    private void finish() {
        try { stopForeground(true); } catch (Exception ignored) { }
        stopSelf();
    }

    @Override public void onCreate() {
        super.onCreate();
        sSelf = this;
        session = new MediaSession(this, "canopy-web");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { send("play", 0); }
            @Override public void onPause() { send("pause", 0); }
            @Override public void onSkipToNext() { send("next", 0); }
            @Override public void onSkipToPrevious() { send("prev", 0); }
            @Override public void onFastForward() { send("next", 0); }
            @Override public void onRewind() { send("prev", 0); }
            @Override public void onSeekTo(long ms) { send("seek", ms / 1000.0); }
            @Override public void onStop() { send("pause", 0); finish(); }
        });
        session.setActive(true);
    }

    @Override public void onDestroy() {
        running = false;
        stopWanted = false;
        foreground = false;
        if (sSelf == this) sSelf = null;
        if (session != null) { session.setActive(false); session.release(); session = null; }
        super.onDestroy();
    }

    private void send(String cmd, double v) {
        WebMedia.Info i = WebMedia.current();
        LauncherActivity l = LauncherActivity.instance();
        if (i != null && l != null) l.webMediaCommand(i.tab, cmd, v);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        sSelf = this;
        running = true;
        // Always go (back) to the foreground first — required when started with startForegroundService().
        Notification n = build();
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(ID, n);
            foreground = true;
            started = true;
        } catch (Exception e) {
            running = false;
            stopSelf();
            return START_NOT_STICKY;
        }
        String a = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(a)) { send("pause", 0); finish(); return START_NOT_STICKY; }
        if (ACTION_PLAY.equals(a)) send("play", 0);
        else if (ACTION_PAUSE.equals(a)) send("pause", 0);
        else if (ACTION_NEXT.equals(a)) send("next", 0);
        else if (ACTION_PREV.equals(a)) send("prev", 0);
        if (stopWanted || WebMedia.current() == null) { finish(); return START_NOT_STICKY; }
        update();
        return START_NOT_STICKY;
    }

    private PendingIntent action(String a, int code) {
        Intent i = new Intent(this, MediaService.class).setAction(a);
        if (Build.VERSION.SDK_INT >= 26) return PendingIntent.getForegroundService(this, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Updates the session (lock screen, seek bar, headphone buttons) and the notification. */
    private void update() {
        WebMedia.Info i = WebMedia.current();
        if (i == null) { finish(); return; }
        if (session != null) {
            MediaMetadata.Builder md = new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, i.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, i.artist)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, i.dur > 0 ? (long) (i.dur * 1000) : -1);
            if (i.artwork != null) md.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, i.artwork);
            session.setMetadata(md.build());
            long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                    | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_STOP
                    | PlaybackState.ACTION_FAST_FORWARD | PlaybackState.ACTION_REWIND;
            if (i.dur > 0) actions |= PlaybackState.ACTION_SEEK_TO;
            session.setPlaybackState(new PlaybackState.Builder()
                    .setActions(actions)
                    .setState(i.playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                            (long) (i.pos * 1000), i.playing ? (float) i.rate : 0f, i.at > 0 ? i.at : SystemClock.elapsedRealtime())
                    .build());
        }
        Notification n = build();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (i.playing) {
            try {
                if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                else startForeground(ID, n);
                foreground = true;
            } catch (Exception e) { nm.notify(ID, n); }
        } else {
            // paused: like other players, the notification stays (with Play) but can be swiped away
            try {
                if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_DETACH); else stopForeground(false);
            } catch (Exception ignored) { }
            foreground = false;
            nm.notify(ID, n);
        }
    }

    private Notification build() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Media playing in web tabs", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(ch);
        }
        WebMedia.Info i = WebMedia.current();
        boolean playing = i != null && i.playing;
        boolean tracks = i != null && (i.next || i.prev);
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, LauncherActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = (Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this))
                .setSmallIcon(R.drawable.ic_play)
                .setContentTitle(i != null && i.title != null && !i.title.isEmpty() ? i.title : "Web tab")
                .setContentText(i != null ? i.artist : "")
                .setContentIntent(open)
                .setDeleteIntent(action(ACTION_STOP, 5))
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setShowWhen(false)
                .setOngoing(playing)
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_skip_prev),
                        tracks ? "Previous" : "Back 10 seconds", action(ACTION_PREV, 1)).build())
                .addAction(playing
                        ? new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_pause), "Pause", action(ACTION_PAUSE, 2)).build()
                        : new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_play), "Play", action(ACTION_PLAY, 3)).build())
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_skip_next),
                        tracks ? "Next" : "Forward 10 seconds", action(ACTION_NEXT, 4)).build());
        if (i != null && PipActivity.supported(this)) {
            PendingIntent pip = PendingIntent.getActivity(this, 6, new Intent(this, PipActivity.class)
                    .putExtra(PipActivity.EXTRA_TAB, i.tab).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            b.addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_pip), "Pop-up player", pip).build());
        }
        if (i != null && i.artwork != null) b.setLargeIcon(i.artwork);
        Notification.MediaStyle style = new Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2);
        if (session != null) style.setMediaSession(session.getSessionToken());
        b.setStyle(style);
        return b.build();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
