/*
 * Copyright 2026 The Canopy Launcher authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.canopy.launcher;

import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.ViewParent;

/** A widget on the home screen that supports long-press (menu) and long-press-drag (move). */
public class LauncherWidgetView extends AppWidgetHostView {
    public interface Host {
        void onWidgetLongPress(LauncherWidgetView v);
        void onWidgetDragStart(LauncherWidgetView v);
    }

    public Host host;
    /** Click-through: the widget only shows; taps and swipes go to what's behind it. */
    public boolean clickThrough;

    /** Crop: the share of the widget (0..0.8) hidden on each side, so one part of a big widget can be used on its own. */
    public float cropL, cropT, cropR, cropB;

    public void setCrop(float l, float t, float r, float b) {
        cropL = l; cropT = t; cropR = Math.min(r, 0.9f - l); cropB = Math.min(b, 0.9f - t);
        requestLayout();
        removeCallbacks(sendSize);
        postDelayed(sendSize, 250);
    }

    public boolean cropped() { return cropL > 0 || cropT > 0 || cropR > 0 || cropB > 0; }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        if (!cropped()) { super.onMeasure(wSpec, hSpec); return; }
        int w = MeasureSpec.getSize(wSpec), h = MeasureSpec.getSize(hSpec);
        // lay the whole widget out at its full size, then show only the part that is left after cropping
        int fw = Math.round(w / (1f - cropL - cropR)), fh = Math.round(h / (1f - cropT - cropB));
        super.onMeasure(MeasureSpec.makeMeasureSpec(fw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(fh, MeasureSpec.EXACTLY));
        setMeasuredDimension(w, h);
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (!cropped()) return;
        for (int i = 0; i < getChildCount(); i++) {
            android.view.View c = getChildAt(i);
            int fw = c.getMeasuredWidth(), fh = c.getMeasuredHeight();
            int ox = Math.round(fw * cropL), oy = Math.round(fh * cropT);
            c.layout(-ox, -oy, fw - ox, fh - oy);
        }
    }

    @Override public boolean dispatchTouchEvent(MotionEvent ev) {
        if (clickThrough) return false;
        return super.dispatchTouchEvent(ev);
    }
    private boolean fired, dragStarted;
    private float downX, downY;
    private final int slop;
    private final Runnable longPress = new Runnable() {
        @Override public void run() {
            fired = true;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            ViewParent p = getParent();
            if (p != null) p.requestDisallowInterceptTouchEvent(true);
            if (host != null) host.onWidgetLongPress(LauncherWidgetView.this);
        }
    };

    public LauncherWidgetView(Context c) {
        super(c);
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
    }

    private void handle(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                fired = false;
                dragStarted = false;
                removeCallbacks(longPress);
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                break;
            case MotionEvent.ACTION_MOVE: {
                float d = (float) Math.hypot(ev.getX() - downX, ev.getY() - downY);
                if (!fired && d > slop) removeCallbacks(longPress);
                if (fired && !dragStarted && d > slop && host != null) {
                    dragStarted = true;
                    host.onWidgetDragStart(this);
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPress);
                break;
        }
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent ev) {
        handle(ev);
        return fired;
    }

    @Override public boolean onTouchEvent(MotionEvent ev) {
        // DOWN was already seen by onInterceptTouchEvent.
        if (ev.getActionMasked() != MotionEvent.ACTION_DOWN) handle(ev);
        return true;
    }

    private final Runnable sendSize = new Runnable() {
        @SuppressWarnings("deprecation")
        @Override public void run() {
            float d = getResources().getDisplayMetrics().density;
            int wd = (int) (getWidth() / (1f - cropL - cropR) / d), hd = (int) (getHeight() / (1f - cropT - cropB) / d);
            if (wd <= 0 || hd <= 0) return;
            try { updateAppWidgetSize(null, wd, hd, wd, hd); } catch (Exception ignored) { }
        }
    };

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        // Tell the widget its new size once resizing settles, not on every frame of a drag.
        removeCallbacks(sendSize);
        postDelayed(sendSize, 250);
    }

    /** Widget host that produces {@link LauncherWidgetView}s. */
    public static class WidgetHost extends AppWidgetHost {
        public WidgetHost(Context c, int id) { super(c, id); }

        @Override protected AppWidgetHostView onCreateView(Context context, int id, AppWidgetProviderInfo info) {
            return new LauncherWidgetView(context);
        }
    }
}
