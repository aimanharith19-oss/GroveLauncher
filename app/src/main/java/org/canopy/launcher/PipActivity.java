/*
 * Copyright 2026 The Canopy Launcher authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.canopy.launcher;

import android.app.Activity;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.util.Rational;
import android.view.ViewGroup;
import android.webkit.ValueCallback;
import android.webkit.WebView;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * Pop-up player: the video from a feed web tab floats in a small window over your other apps
 * (Android picture-in-picture), with play/pause and skip buttons. Close it and the page goes back to its tab.
 */
public class PipActivity extends Activity {
    static final String EXTRA_TAB = "tab";
    private static final String ACTION = "org.canopy.launcher.pip.ACTION";

    private String tab;
    private WebView wv;
    private FrameLayout box;
    private Rational aspect = new Rational(16, 9);
    private boolean registered;

    /** JS helper: the page's main video — searched through shadow DOM and same-site frames — or else its main video frame. */
    static final String FIND = "function cnpFind(){var vs=[],fs=[];function walk(r,d){if(!r||d>6)return;var l=r.querySelectorAll('*');for(var i=0;i<l.length;i++){var e=l[i];"
            + "if(e.tagName=='VIDEO')vs.push(e);else if(e.tagName=='IFRAME'){fs.push(e);try{if(e.contentDocument)walk(e.contentDocument,d+1);}catch(x){}}"
            + "if(e.shadowRoot)walk(e.shadowRoot,d+1);}}walk(document,0);var b=null,bs=-1;for(var j=0;j<vs.length;j++){var v=vs[j],r=v.getBoundingClientRect();"
            + "var s=(!v.paused?1e12:0)+(v.readyState>0?1e9:0)+r.width*r.height;if(s>bs){bs=s;b=v;}}if(b)return b;"
            + "for(var k=0;k<fs.length;k++){var f=fs[k],q=f.getBoundingClientRect(),sc=q.width*q.height*(/youtube|vimeo|player|video|embed|twitch|dailymotion/i.test(f.src)?10:1);"
            + "if(q.width>80&&sc>bs){bs=sc;b=f;}}return b;}";

    /** JS: pick the main video, scroll it into the middle, and return "fx,fy,w,h" (its centre as fractions of the page). */
    static final String PREP_JS = "(function(){" + FIND + "var b=cnpFind();if(!b)return '';window.__cnpPipV=b;"
            + "try{b.scrollIntoView({block:'center'});}catch(x){}var r=b.getBoundingClientRect(),ox=0,oy=0;"
            + "try{var w=b.ownerDocument.defaultView;while(w&&w.frameElement){var fr=w.frameElement.getBoundingClientRect();ox+=fr.left;oy+=fr.top;w=w.parent;}}catch(x){}"
            + "var vw=b.videoWidth||r.width,vh=b.videoHeight||r.height;"
            + "return ((ox+r.left+r.width/2)/innerWidth)+','+((oy+r.top+r.height/2)/innerHeight)+','+Math.round(vw)+','+Math.round(vh);})()";

    /** JS: the next tap asks for just the video to go full screen (so only the video shows), and is swallowed. */
    static final String ARM_JS = "(function(){var evs=['touchend','pointerup','mouseup','click'];var done=false;"
            + "function off(){for(var i=0;i<evs.length;i++)window.removeEventListener(evs[i],h,true);}"
            + "function h(e){e.preventDefault();e.stopImmediatePropagation();if(done)return;done=true;setTimeout(off,400);"
            + "var v=window.__cnpPipV;if(!v)return;try{if(v.requestFullscreen)v.requestFullscreen();else if(v.webkitRequestFullscreen)v.webkitRequestFullscreen();"
            + "else if(v.webkitEnterFullscreen)v.webkitEnterFullscreen();}catch(x){}if(v.paused&&v.play)try{v.play();}catch(x){}}"
            + "for(var i=0;i<evs.length;i++)window.addEventListener(evs[i],h,true);setTimeout(off,3000);})()";

    /** Fallback JS: hide everything except the video (or video frame), which fills the window. */
    static final String FOCUS_JS = "(function(){" + FIND + "var b=window.__cnpPipV||cnpFind();if(!b)return '';"
            + "window.__cnpAnc=[[b,b.style.cssText]];var p=b;while(true){p=p.parentElement||(p.getRootNode&&p.getRootNode().host);if(!p)break;"
            + "window.__cnpAnc.push([p,p.style.cssText]);['transform','filter','contain','perspective'].forEach(function(k){p.style.setProperty(k,'none','important');});"
            + "p.style.setProperty('will-change','auto','important');p.style.setProperty('overflow','visible','important');}"
            + "var st=document.getElementById('cnp-pip-css');"
            + "if(!st){st=document.createElement('style');st.id='cnp-pip-css';(document.head||document.documentElement).appendChild(st);}"
            + "st.textContent='html,body{overflow:hidden!important;background:#000!important}body *{visibility:hidden!important}';"
            + "var css={visibility:'visible',position:'fixed',left:'0',top:'0',width:'100vw',height:'100vh','max-width':'none','max-height':'none',"
            + "'z-index':'2147483647',background:'#000','object-fit':'contain',transform:'none',margin:'0',border:'0'};"
            + "for(var k in css)b.style.setProperty(k,css[k],'important');b.setAttribute('data-cnp-pip','1');"
            + "if(b.paused&&b.play)try{b.play();}catch(x){}return (b.videoWidth||16)+'x'+(b.videoHeight||9);})()";

    static final String UNFOCUS_JS = "(function(){var st=document.getElementById('cnp-pip-css');if(st)st.remove();"
            + "var m=document.querySelectorAll('video[data-cnp-pip]');for(var i=0;i<m.length;i++)m[i].removeAttribute('data-cnp-pip');"
            + "var a=window.__cnpAnc||[];for(var j=0;j<a.length;j++)a[j][0].style.cssText=a[j][1];window.__cnpAnc=[];"
            + "try{if(document.fullscreenElement)document.exitFullscreen();}catch(x){}})()";

    static boolean supported(Context c) {
        return c.getPackageManager().hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE);
    }

    static void open(LauncherActivity a, String tab) {
        if (!supported(a)) { Ui.toast(a, "This phone doesn't support pop-up players"); return; }
        a.startActivity(new Intent(a, PipActivity.class).putExtra(EXTRA_TAB, tab)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK));
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            LauncherActivity l = LauncherActivity.instance();
            if (l == null || tab == null) return;
            String cmd = i.getStringExtra("cmd");
            l.webMediaCommand(tab, cmd, 0);
            box.postDelayed(new Runnable() { @Override public void run() { updateParams(); } }, 400);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b); Ui.keepBack(this);
        tab = getIntent().getStringExtra(EXTRA_TAB);
        LauncherActivity l = LauncherActivity.instance();
        if (tab == null || l == null || l.peekFeedWebView(tab) == null) { finish(); return; }
        wv = l.peekFeedWebView(tab);
        l.setPipTab(tab);
        if (wv.getParent() != null) ((ViewGroup) wv.getParent()).removeView(wv);
        if (wv instanceof FeedWeb.CardWebView) ((FeedWeb.CardWebView) wv).pull = null; // no pull-to-refresh in the pop-up
        box = new FrameLayout(this);
        box.setBackgroundColor(0xFF000000);
        box.addView(wv, new FrameLayout.LayoutParams(-1, -1));
        setContentView(box);
        IntentFilter f = new IntentFilter(ACTION);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, f);
        registered = true;
        wv.onResume();
        cover = new android.view.View(this);
        cover.setBackgroundColor(0xFF000000);
        box.addView(cover, new FrameLayout.LayoutParams(-1, -1)); // hide the page while we pick out the video
        sWaiting = this;
        // wait until the page has its size in this window before tapping on the video
        box.postDelayed(new Runnable() { @Override public void run() { pickVideo(); } }, 350);
    }

    // ---------------------------------------------------------------- video only

    private android.view.View cover, fsView;
    private android.webkit.WebChromeClient.CustomViewCallback fsCb;
    private static PipActivity sWaiting;

    /** The launcher asks: is a pop-up waiting for the page's full-screen video? */
    static PipActivity waitingForVideo() { return sWaiting; }

    /**
     * Video only: we ask the page to put just its video in full screen (the same thing as pressing the video's
     * own full-screen button), and show that in the pop-up. A short tap on the video does the asking, since
     * pages only allow full screen after a tap; our script swallows that tap so nothing else on the page reacts.
     */
    private void pickVideo() {
        wv.evaluateJavascript(PREP_JS, new ValueCallback<String>() {
            @Override public void onReceiveValue(String v) {
                String s = v == null ? "" : v.replace("\"", "");
                String[] p = s.split(",");
                if (p.length < 4) {
                    Ui.toast(PipActivity.this, "No video found on this page \u2014 start the video, then try again");
                    finish();
                    return;
                }
                try {
                    final float fx = Float.parseFloat(p[0]), fy = Float.parseFloat(p[1]);
                    int w = Integer.parseInt(p[2]), h = Integer.parseInt(p[3]);
                    if (w > 0 && h > 0) {
                        float r = Math.max(1 / 2.39f, Math.min(2.39f, w / (float) h));
                        aspect = new Rational(Math.round(r * 1000), 1000);
                    }
                    wv.evaluateJavascript(ARM_JS, new ValueCallback<String>() {
                        @Override public void onReceiveValue(String ignored) { tap(fx, fy); }
                    });
                    box.postDelayed(new Runnable() {
                        @Override public void run() { if (sWaiting == PipActivity.this) fallback(); }
                    }, 1600);
                } catch (Exception e) { fallback(); }
            }
        });
    }

    private void tap(float fx, float fy) {
        float x = Math.max(1, Math.min(wv.getWidth() - 2, fx * wv.getWidth()));
        float y = Math.max(1, Math.min(wv.getHeight() - 2, fy * wv.getHeight()));
        long t = android.os.SystemClock.uptimeMillis();
        android.view.MotionEvent down = android.view.MotionEvent.obtain(t, t, android.view.MotionEvent.ACTION_DOWN, x, y, 0);
        down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        wv.dispatchTouchEvent(down);
        down.recycle();
        android.view.MotionEvent up = android.view.MotionEvent.obtain(t, t + 60, android.view.MotionEvent.ACTION_UP, x, y, 0);
        up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        wv.dispatchTouchEvent(up);
        up.recycle();
    }

    /** The page's video went full screen: show only that. */
    void onVideoView(android.view.View view, android.webkit.WebChromeClient.CustomViewCallback cb) {
        sWaiting = null;
        fsView = view;
        fsCb = cb;
        if (view.getParent() != null) ((ViewGroup) view.getParent()).removeView(view);
        box.addView(view, new FrameLayout.LayoutParams(-1, -1));
        enter();
    }

    /** The page left full screen by itself (e.g. the video ended): close the pop-up. */
    void onVideoViewGone() {
        fsView = null;
        fsCb = null;
        finish();
    }

    boolean showsVideoView() { return fsView != null; }

    /** Couldn't get the video on its own: show the page with everything but the video hidden. */
    private void fallback() {
        if (sWaiting == this) sWaiting = null;
        wv.evaluateJavascript(FOCUS_JS, new ValueCallback<String>() {
            @Override public void onReceiveValue(String v) {
                if (v == null || v.replace("\"", "").isEmpty()) {
                    Ui.toast(PipActivity.this, "No video found on this page");
                    finish();
                    return;
                }
                try {
                    String s = v.replace("\"", "");
                    int x = s.indexOf('x');
                    int w = Integer.parseInt(s.substring(0, x)), h = Integer.parseInt(s.substring(x + 1));
                    if (w > 0 && h > 0) {
                        float r = Math.max(1 / 2.39f, Math.min(2.39f, w / (float) h));
                        aspect = new Rational(Math.round(r * 1000), 1000);
                    }
                } catch (Exception ignored) { }
                if (cover != null) box.removeView(cover);
                enter();
            }
        });
    }

    private PendingIntent cmd(String c, int code) {
        Intent i = new Intent(ACTION).setPackage(getPackageName()).putExtra("cmd", c);
        return PendingIntent.getBroadcast(this, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PictureInPictureParams params() {
        WebMedia.Info i = WebMedia.current();
        boolean playing = i != null && tab.equals(i.tab) && i.playing;
        List<RemoteAction> acts = new ArrayList<>();
        acts.add(new RemoteAction(Icon.createWithResource(this, R.drawable.ic_skip_prev), "Back", "Back 10 seconds / previous", cmd("prev", 1)));
        acts.add(playing
                ? new RemoteAction(Icon.createWithResource(this, R.drawable.ic_pause), "Pause", "Pause", cmd("pause", 2))
                : new RemoteAction(Icon.createWithResource(this, R.drawable.ic_play), "Play", "Play", cmd("play", 3)));
        acts.add(new RemoteAction(Icon.createWithResource(this, R.drawable.ic_skip_next), "Next", "Forward 10 seconds / next", cmd("next", 4)));
        PictureInPictureParams.Builder p = new PictureInPictureParams.Builder().setAspectRatio(aspect).setActions(acts);
        if (Build.VERSION.SDK_INT >= 31) { p.setAutoEnterEnabled(true); p.setSeamlessResizeEnabled(true); }
        return p.build();
    }

    private void enter() {
        try {
            if (!enterPictureInPictureMode(params())) { Ui.toast(this, "Pop-up player is turned off for Grove in Android settings"); finish(); }
        } catch (Exception e) {
            Ui.toast(this, "Pop-up player isn't available here");
            finish();
        }
    }

    void updateParams() {
        try { setPictureInPictureParams(params()); } catch (Exception ignored) { }
    }

    /** The player buttons follow play / pause. */
    static void onMediaChanged() { if (sCurrent != null) sCurrent.updateParams(); }
    private static PipActivity sCurrent;
    static PipActivity current() { return sCurrent; }

    @Override protected void onResume() { super.onResume(); sCurrent = this; }

    @Override public void onPictureInPictureModeChanged(boolean inPip, Configuration c) {
        super.onPictureInPictureModeChanged(inPip, c);
        // expanded to full screen and back: keep playing; closed (X / swiped away): onStop finishes us
    }

    @Override protected void onStop() {
        super.onStop();
        // The pop-up was closed (or expanded and then left): put the page back in its tab.
        if (!isChangingConfigurations()) finish();
    }

    @Override protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (!isInPictureInPictureMode()) enter(); // leaving the expanded player: shrink back to the pop-up
    }

    @Override public void onBackPressed() {
        enter(); // back from the expanded view shrinks it to the pop-up again
    }

    @Override protected void onDestroy() {
        if (registered) { try { unregisterReceiver(receiver); } catch (Exception ignored) { } }
        if (sCurrent == this) sCurrent = null;
        if (sWaiting == this) sWaiting = null;
        if (fsView != null) {
            if (fsView.getParent() != null) ((ViewGroup) fsView.getParent()).removeView(fsView);
            android.webkit.WebChromeClient.CustomViewCallback cb = fsCb;
            fsView = null;
            fsCb = null;
            if (cb != null) { try { cb.onCustomViewHidden(); } catch (Exception ignored) { } }
        }
        if (wv != null) {
            wv.evaluateJavascript(UNFOCUS_JS, null);
            LauncherActivity l = LauncherActivity.instance();
            if (l != null) {
                l.webMediaCommand(tab, "pause", 0); // closing the pop-up stops the video, like YouTube
                if (wv.getParent() != null) ((ViewGroup) wv.getParent()).removeView(wv);
                l.onPipClosed(tab);
            }
        }
        super.onDestroy();
    }
}
