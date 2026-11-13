/*
 * Copyright 2026 The Canopy Launcher authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.canopy.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Learns the music you listen to most, for the feed's "Your music" card. It watches what's playing in any
 * music app (Spotify, YouTube Music, etc.) through Android's media sessions — available because Canopy has
 * notification access — and counts a song once it has played for 30 seconds. Only music: video apps (YouTube,
 * Netflix, TikTok…), browsers and anything marked as a movie are ignored. The history (song, artist, app,
 * play count, last played) stays in a file on this device; nothing is sent anywhere. Clear it in the card.
 */
public final class MusicHistory {
    private MusicHistory() { }

    public static final class Track {
        public String key, title, artist, pkg;
        public int plays;
        public long last;
    }

    public interface Listener { void onMusicChanged(); }

    private static final long COUNT_AFTER = 30_000L, WINDOW = 30L * 24 * 3600 * 1000;
    private static final int MAX = 400;
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final Map<String, Track> tracks = new LinkedHashMap<>();
    private static final List<Listener> listeners = new ArrayList<>();
    private static boolean loaded;
    private static Context app;
    private static MediaSessionManager msm;
    private static final List<MediaController> controllers = new ArrayList<>();
    private static final Map<MediaController, MediaController.Callback> callbacks = new HashMap<>();
    private static MediaController current;
    private static String candidate, lastCounted;

    private static final MediaSessionManager.OnActiveSessionsChangedListener SESSIONS = new MediaSessionManager.OnActiveSessionsChangedListener() {
        @Override public void onActiveSessionsChanged(List<MediaController> list) { setControllers(list); }
    };

    private static final Runnable COUNT = new Runnable() {
        @Override public void run() {
            MediaController c = current;
            if (c == null || !playing(c) || !isMusic(c) || candidate == null || !candidate.equals(keyOf(c))) return;
            record(c);
            lastCounted = candidate;
        }
    };

    // ------------------------------------------------------------------ watching (started by the notification listener)

    static void start(Context svc) {
        app = svc.getApplicationContext();
        load();
        try {
            msm = (MediaSessionManager) svc.getSystemService(Context.MEDIA_SESSION_SERVICE);
            ComponentName cn = new ComponentName(svc, BadgeService.class);
            msm.addOnActiveSessionsChangedListener(SESSIONS, cn, H);
            setControllers(msm.getActiveSessions(cn));
        } catch (Throwable t) { msm = null; }
    }

    static void stop() {
        try { if (msm != null) msm.removeOnActiveSessionsChangedListener(SESSIONS); } catch (Throwable ignored) { }
        msm = null;
        setControllers(new ArrayList<MediaController>());
    }

    public static boolean watching() { return msm != null; }

    private static void setControllers(List<MediaController> list) {
        for (Map.Entry<MediaController, MediaController.Callback> e : callbacks.entrySet()) {
            try { e.getKey().unregisterCallback(e.getValue()); } catch (Throwable ignored) { }
        }
        callbacks.clear();
        controllers.clear();
        if (list != null) for (final MediaController c : list) {
            if (app != null && app.getPackageName().equals(c.getPackageName())) continue; // Canopy's own web tabs
            MediaController.Callback cb = new MediaController.Callback() {
                @Override public void onPlaybackStateChanged(PlaybackState s) { update(); }
                @Override public void onMetadataChanged(MediaMetadata m) { update(); }
                @Override public void onSessionDestroyed() { update(); }
            };
            try { c.registerCallback(cb, H); } catch (Throwable t) { continue; }
            callbacks.put(c, cb);
            controllers.add(c);
        }
        update();
    }

    // ------------------------------------------------------------------ music only, no video

    /** Video apps and browsers: never counted, never shown. */
    private static final java.util.Set<String> VIDEO = new java.util.HashSet<>(java.util.Arrays.asList(
            "com.google.android.youtube", "com.google.android.apps.youtube.kids", "app.revanced.android.youtube",
            "app.rvx.android.youtube", "com.vanced.android.youtube", "org.schabi.newpipe", "free.rm.skytube.oss",
            "com.netflix.mediaclient", "com.amazon.avod.thirdpartyclient", "com.disney.disneyplus", "com.hbo.hbonow",
            "com.wbd.stream", "com.hulu.plus", "com.apple.atve.androidtv.appletv", "com.google.android.videos",
            "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.ss.android.ugc.aweme", "tv.twitch.android.app",
            "com.instagram.android", "com.facebook.katana", "com.facebook.lite", "com.twitter.android", "com.reddit.frontpage",
            "com.vimeo.android.videoapp", "com.dailymotion.dailymotion", "com.mxtech.videoplayer.ad", "com.mxtech.videoplayer.pro",
            "org.videolan.vlc", "is.xyz.mpv", "com.plexapp.android", "org.jellyfin.mobile", "com.viu.phone", "com.iqiyi.i18n",
            "com.tencent.qqlivei18n", "com.bilibili.app.in", "tv.danmaku.bili", "com.snapchat.android",
            "com.android.chrome", "com.chrome.beta", "com.brave.browser", "org.mozilla.firefox", "com.microsoft.emmx",
            "com.sec.android.app.sbrowser", "com.opera.browser", "com.kiwibrowser.browser", "com.vivaldi.browser",
            "com.google.android.apps.photos", "com.google.android.apps.youtube.creator"));

    /** Apps that are for music, counted even if they don't say what they're playing is music. */
    private static final java.util.Set<String> MUSIC = new java.util.HashSet<>(java.util.Arrays.asList(
            "com.spotify.music", "com.google.android.apps.youtube.music", "app.revanced.android.apps.youtube.music",
            "com.apple.android.music", "com.amazon.mp3", "deezer.android.app", "com.aspiro.tidal", "com.soundcloud.android",
            "com.joox.android", "com.joox.android.lite", "com.shazam.android", "com.pandora.android", "com.qobuz.music",
            "com.anghami", "com.gaana", "com.jio.media.jiobeats", "com.bsbportal.music", "com.maxmpz.audioplayer",
            "com.musixmatch.android.lyrify", "com.sec.android.app.music", "com.miui.player", "com.google.android.music",
            "com.samsung.android.app.music.chn", "code.name.monkey.retromusic", "com.kapp.youtube.final",
            "it.fast4x.rimusic", "com.zionhuang.music", "com.metrolist.music", "com.nubia.music", "cn.nubia.music",
            "com.zte.music", "com.oppo.music", "com.heytap.music", "com.vivo.music", "com.huawei.music",
            "com.audiomack", "com.napster.android", "com.melodis.midomiMusicIdentifier.freemium"));

    static boolean isVideoApp(String pkg) { return pkg == null || VIDEO.contains(pkg); }

    /** Music, not video: known music apps, or an app that marks its audio as music — never video apps. */
    private static boolean isMusic(MediaController c) {
        String pkg = c.getPackageName();
        if (isVideoApp(pkg)) return false;
        int type = -1;
        try {
            MediaController.PlaybackInfo pi = c.getPlaybackInfo();
            if (pi != null && pi.getAudioAttributes() != null) type = pi.getAudioAttributes().getContentType();
        } catch (Throwable ignored) { }
        if (type == android.media.AudioAttributes.CONTENT_TYPE_MOVIE) return false;
        if (MUSIC.contains(pkg)) return true;
        if (type == android.media.AudioAttributes.CONTENT_TYPE_MUSIC) return true;
        // other apps: only songs that name an artist and look like a song length (not a long video)
        MediaMetadata m = c.getMetadata();
        if (m == null) return false;
        String a = artist(m);
        long dur = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
        return a != null && !a.trim().isEmpty() && (dur <= 0 || dur < 15 * 60 * 1000L);
    }

    private static boolean playing(MediaController c) {
        PlaybackState s = c.getPlaybackState();
        return s != null && s.getState() == PlaybackState.STATE_PLAYING;
    }

    private static String keyOf(MediaController c) {
        MediaMetadata m = c.getMetadata();
        if (m == null) return null;
        String t = title(m), a = artist(m);
        if (t == null || t.trim().isEmpty()) return null;
        return (t.trim() + "\u0001" + (a == null ? "" : a.trim())).toLowerCase(java.util.Locale.ROOT);
    }

    static String title(MediaMetadata m) {
        CharSequence t = m.getText(MediaMetadata.METADATA_KEY_TITLE);
        if (t == null) t = m.getText(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        return t == null ? null : t.toString();
    }

    static String artist(MediaMetadata m) {
        CharSequence a = m.getText(MediaMetadata.METADATA_KEY_ARTIST);
        if (a == null) a = m.getText(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        if (a == null) a = m.getText(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
        return a == null ? null : a.toString();
    }

    private static void update() {
        MediaController pick = null;
        for (MediaController c : controllers) if (playing(c) && c.getMetadata() != null && isMusic(c)) { pick = c; break; }
        if (pick == null) for (MediaController c : controllers) if (c.getMetadata() != null && isMusic(c)) { pick = c; break; }
        current = pick;
        String k = pick != null && playing(pick) ? keyOf(pick) : null;
        if (k == null) { H.removeCallbacks(COUNT); candidate = null; }
        else if (!k.equals(candidate)) {
            candidate = k;
            H.removeCallbacks(COUNT);
            if (!k.equals(lastCounted)) H.postDelayed(COUNT, COUNT_AFTER);
        }
        notifyListeners();
    }

    /** What's playing (or paused) right now, if any. */
    public static MediaController current() { return current; }

    // ------------------------------------------------------------------ history

    private static File file(Context c) { return new File(c.getFilesDir(), "music_history.tsv"); }

    private static synchronized void load() {
        if (loaded || app == null) return;
        loaded = true;
        File f = file(app);
        if (!f.exists()) return;
        boolean dropped = false;
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                String[] p = line.split("\t", -1);
                if (p.length < 6) continue;
                Track t = new Track();
                t.key = p[0]; t.title = p[1]; t.artist = p[2]; t.pkg = p[3];
                t.plays = Integer.parseInt(p[4]);
                t.last = Long.parseLong(p[5]);
                if (isVideoApp(t.pkg)) { dropped = true; continue; } // counted by an older version: videos are left out now
                tracks.put(t.key, t);
            }
            br.close();
        } catch (Exception ignored) { }
        if (dropped) save();
    }

    /** Lets the feed read the history even before the notification listener has started. */
    public static void ensureLoaded(Context c) {
        if (app == null) app = c.getApplicationContext();
        load();
    }

    private static void record(MediaController c) {
        MediaMetadata m = c.getMetadata();
        String k = keyOf(c);
        if (m == null || k == null) return;
        Track t = tracks.remove(k);
        if (t == null) { t = new Track(); t.key = k; }
        t.title = clean(title(m));
        t.artist = clean(artist(m));
        t.pkg = c.getPackageName();
        t.plays++;
        t.last = System.currentTimeMillis();
        tracks.put(k, t); // most recent at the end
        while (tracks.size() > MAX) { // forget the least-played old songs
            Track worst = null;
            for (Track x : tracks.values()) if (worst == null || x.plays < worst.plays || (x.plays == worst.plays && x.last < worst.last)) worst = x;
            tracks.remove(worst.key);
        }
        save();
        notifyListeners();
    }

    private static String clean(String s) { return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').trim(); }

    private static void save() {
        if (app == null) return;
        final List<Track> copy = new ArrayList<>(tracks.values());
        final File f = file(app);
        App.IO.execute(new Runnable() {
            @Override public void run() {
                try {
                    File tmp = new File(f.getPath() + ".tmp");
                    Writer w = new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8");
                    for (Track t : copy) w.write(t.key + "\t" + t.title + "\t" + t.artist + "\t" + t.pkg + "\t" + t.plays + "\t" + t.last + "\n");
                    w.close();
                    //noinspection ResultOfMethodCallIgnored
                    tmp.renameTo(f);
                } catch (Exception ignored) { }
            }
        });
    }

    public static void clear(Context c) {
        tracks.clear();
        lastCounted = null;
        //noinspection ResultOfMethodCallIgnored
        file(c).delete();
        notifyListeners();
    }

    /** Your most played songs in the last 30 days. */
    public static List<Track> topTracks(int n) {
        long since = System.currentTimeMillis() - WINDOW;
        List<Track> l = new ArrayList<>();
        for (Track t : tracks.values()) if (t.last >= since) l.add(t);
        Collections.sort(l, new Comparator<Track>() {
            @Override public int compare(Track a, Track b) { return a.plays != b.plays ? b.plays - a.plays : Long.compare(b.last, a.last); }
        });
        return l.size() > n ? new ArrayList<>(l.subList(0, n)) : l;
    }

    /** Your most played artists in the last 30 days: {name, plays, app package of their latest song}. */
    public static List<String[]> topArtists(int n) {
        long since = System.currentTimeMillis() - WINDOW;
        final Map<String, int[]> count = new HashMap<>();
        Map<String, String[]> info = new HashMap<>();
        for (Track t : tracks.values()) {
            if (t.last < since || t.artist == null || t.artist.isEmpty()) continue;
            String k = t.artist.toLowerCase(java.util.Locale.ROOT);
            int[] c = count.get(k);
            if (c == null) { c = new int[1]; count.put(k, c); }
            c[0] += t.plays;
            String[] i = info.get(k);
            if (i == null || Long.parseLong(i[2]) < t.last) info.put(k, new String[]{t.artist, t.pkg, String.valueOf(t.last)});
        }
        List<String> keys = new ArrayList<>(count.keySet());
        Collections.sort(keys, new Comparator<String>() {
            @Override public int compare(String a, String b) { return count.get(b)[0] - count.get(a)[0]; }
        });
        List<String[]> out = new ArrayList<>();
        for (int i = 0; i < keys.size() && i < n; i++) {
            String[] in = info.get(keys.get(i));
            out.add(new String[]{in[0], String.valueOf(count.get(keys.get(i))[0]), in[1]});
        }
        return out;
    }

    public static void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    public static void removeListener(Listener l) { listeners.remove(l); }

    private static final Runnable NOTIFY = new Runnable() {
        @Override public void run() { for (Listener l : new ArrayList<>(listeners)) l.onMusicChanged(); }
    };

    /** Batched: some players report their state many times a second. */
    private static void notifyListeners() {
        H.removeCallbacks(NOTIFY);
        H.postDelayed(NOTIFY, 250);
    }
}
