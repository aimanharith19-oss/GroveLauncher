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
            int wd = (int) (getWidth() / d), hd = (int) (getHeight() / d);
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
