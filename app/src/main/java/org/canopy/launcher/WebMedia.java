/*
 * Copyright 2026 The Canopy Launcher authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.canopy.launcher;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * What's playing in the feed's web tabs: title, artist/site, artwork, position and duration, read from the
 * page (the same info a site gives Chrome for its media notification). Drives the player notification.
 */
final class WebMedia {
    private WebMedia() { }

    static final class Info {
        String tab, title, artist, art;
        boolean playing, has, next, prev;
        double pos, dur, rate = 1;
        long at;      // when pos was reported
        Bitmap artwork;
    }

    private static final Map<String, Info> byTab = new HashMap<>();
    private static final Set<String> playingTabs = new HashSet<>();
    private static Info current;
    private static final Map<String, Bitmap> artCache = new HashMap<>();

    static boolean anyPlaying() { return !playingTabs.isEmpty(); }
    static boolean isPlaying(String tab) { return playingTabs.contains(tab); }
    static Info current() { return current; }

    /**
     * Runs in every web tab. It reports the main video/audio's state, remembers the site's own media buttons
     * (next track etc.), and answers commands from the notification through window.__cnpCmd(action, value).
     */
    static String js(boolean keepVisible) {
        return "(function(){if(window.__cnpMedia)return;window.__cnpMedia=1;"
                + "var H={};try{var ms=navigator.mediaSession;if(ms&&ms.setActionHandler){var o=ms.setActionHandler.bind(ms);"
                + "ms.setActionHandler=function(a,h){H[a]=h;try{o(a,h);}catch(e){}};}}catch(e){}"
                + "function main(){var m=document.querySelectorAll('video,audio'),b=null,bs=-1;for(var i=0;i<m.length;i++){var e=m[i];"
                + "var s=(!e.paused?1e9:0)+(e.duration>0?1e6:0)+((e.videoWidth||1)*(e.videoHeight||1));if(s>bs){bs=s;b=e;}}return b;}"
                + "var last=0;function upd(f){var now=Date.now();if(!f&&now-last<900)return;last=now;var e=main();var d={playing:false,has:false};"
                + "if(e){d.has=true;d.playing=!e.paused&&!e.ended&&!e.muted;d.pos=e.currentTime||0;d.dur=isFinite(e.duration)?e.duration:0;d.rate=e.playbackRate||1;}"
                + "try{var md=navigator.mediaSession&&navigator.mediaSession.metadata;if(md){d.title=md.title;d.artist=md.artist||md.album;"
                + "if(md.artwork&&md.artwork.length)d.art=md.artwork[md.artwork.length-1].src;}}catch(x){}"
                + "if(!d.title)d.title=document.title;if(!d.art){var og=document.querySelector('meta[property=\"og:image\"]');if(og)d.art=og.content;}"
                + "if(!d.art&&e&&e.poster)d.art=e.poster;d.next=!!H.nexttrack;d.prev=!!H.previoustrack;"
                + "try{CanopyMedia.update(JSON.stringify(d));}catch(x){}}"
                + "['play','playing','pause','ended','emptied','seeked','loadedmetadata','durationchange','ratechange','volumechange']"
                + ".forEach(function(t){document.addEventListener(t,function(){upd(true)},true);});"
                + "setInterval(function(){var e=main();if(e&&!e.paused)upd(false);},4000);"
                + "window.__cnpCmd=function(a,v){var e=main();"
                + "if(a=='play'){if(H.play)H.play({action:'play'});else if(e)e.play();}"
                + "else if(a=='pause'){if(H.pause)H.pause({action:'pause'});else if(e)e.pause();}"
                + "else if(a=='seek'){if(e)e.currentTime=v;}"
                + "else if(a=='next'){if(H.nexttrack)H.nexttrack({action:'nexttrack'});else if(e)e.currentTime=Math.min(e.duration||1e9,e.currentTime+10);}"
                + "else if(a=='prev'){if(H.previoustrack)H.previoustrack({action:'previoustrack'});else if(e)e.currentTime=Math.max(0,e.currentTime-10);}"
                + "else if(a=='stop'){if(e)e.pause();}"
                + "setTimeout(function(){upd(true)},300);};"
                + (keepVisible
                ? "try{Object.defineProperty(document,'hidden',{get:function(){return false},configurable:true});"
                + "Object.defineProperty(document,'visibilityState',{get:function(){return 'visible'},configurable:true});}catch(e){}"
                + "document.addEventListener('visibilitychange',function(e){e.stopImmediatePropagation();},true);"
                + "window.addEventListener('pagehide',function(e){e.stopImmediatePropagation();},true);"
                : "")
                + "upd(true);})();";
    }

    static String command(String action, double value) {
        return "window.__cnpCmd&&window.__cnpCmd('" + action + "'," + value + ")";
    }

    /** A page reported its media state (main thread). */
    static void onUpdate(final LauncherActivity a, String tab, String json) {
        Info i;
        try {
            JSONObject o = new JSONObject(json);
            i = byTab.get(tab);
            if (i == null) { i = new Info(); i.tab = tab; byTab.put(tab, i); }
            i.has = o.optBoolean("has");
            i.playing = o.optBoolean("playing");
            i.title = o.optString("title", "");
            i.artist = o.optString("artist", "");
            if (i.artist.isEmpty()) i.artist = FeedWeb.title(a, tab);
            String art = o.optString("art", "");
            if (!art.equals(i.art)) { i.art = art; i.artwork = artCache.get(art); if (i.artwork == null && !art.isEmpty()) loadArt(a, i, art); }
            i.pos = o.optDouble("pos", 0);
            i.dur = o.optDouble("dur", 0);
            i.rate = o.optDouble("rate", 1);
            i.next = o.optBoolean("next");
            i.prev = o.optBoolean("prev");
            i.at = android.os.SystemClock.elapsedRealtime();
        } catch (Exception e) { return; }
        if (i.playing) { playingTabs.add(tab); current = i; }
        else playingTabs.remove(tab);
        if (!i.has && current == i) current = null;
        a.onWebMediaChanged();
        PipActivity.onMediaChanged();
    }

    static void forget(String tab) {
        byTab.remove(tab);
        playingTabs.remove(tab);
        if (current != null && tab.equals(current.tab)) current = null;
    }

    private static void loadArt(final LauncherActivity a, final Info i, final String url) {
        if (!url.startsWith("http")) return;
        App.IO.execute(new Runnable() {
            @Override public void run() {
                Bitmap b = null;
                HttpURLConnection con = null;
                try {
                    con = (HttpURLConnection) new URL(url).openConnection();
                    con.setConnectTimeout(8000);
                    con.setReadTimeout(10000);
                    InputStream in = con.getInputStream();
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    b = BitmapFactory.decodeStream(in, null, o);
                    in.close();
                    if (b != null && Math.max(b.getWidth(), b.getHeight()) > 512) {
                        float k = 512f / Math.max(b.getWidth(), b.getHeight());
                        b = Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * k), Math.round(b.getHeight() * k), true);
                    }
                } catch (Exception ignored) {
                } finally {
                    if (con != null) con.disconnect();
                }
                final Bitmap fb = b;
                if (fb != null) App.MAIN.post(new Runnable() {
                    @Override public void run() {
                        if (artCache.size() > 12) artCache.clear();
                        artCache.put(url, fb);
                        if (url.equals(i.art)) { i.artwork = fb; MediaService.refresh(); }
                    }
                });
            }
        });
    }
}
