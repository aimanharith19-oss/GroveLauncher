/*
 * Copyright 2026 The Canopy Launcher authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.canopy.launcher;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

/**
 * Nova-style widget resizing: a frame with four handles around the widget. Drag a handle to grow or
 * shrink it; it snaps to the grid as you go. Tap outside (or press Back / Home) to finish.
 */
public class WidgetResizeFrame extends FrameLayout {
    private static final int NONE = 0, LEFT = 1, TOP = 2, RIGHT = 3, BOTTOM = 4, MOVE = 5;

    private final LauncherActivity launcher;
    public final HomeItem item;
    private final View widget;
    private final CellLayout cl;
    private final RectF rect = new RectF();
    private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG), handle = new Paint(Paint.ANTI_ALIAS_FLAG),
            handleRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float handleR, touchR;
    private int edge = NONE;
    private float gridLeft, gridTop, offX, offY;
    private final boolean free;
    /** Crop mode: dragging a handle hides (or brings back) that side of the widget instead of resizing it. */
    private final boolean crop;
    private final RectF full = new RectF();
    private final Paint dash = new Paint(Paint.ANTI_ALIAS_FLAG);

    public WidgetResizeFrame(LauncherActivity c, HomeItem item, View widget, CellLayout cl) { this(c, item, widget, cl, false); }

    public WidgetResizeFrame(LauncherActivity c, HomeItem item, View widget, CellLayout cl, boolean crop) {
        super(c);
        this.crop = crop && widget instanceof LauncherWidgetView;
        launcher = c;
        this.item = item;
        this.widget = widget;
        this.cl = cl;
        free = this.crop || (item.type == HomeItem.WIDGET ? c.freeWidgets() : c.freeIcons()) && item.container == HomeItem.DESKTOP;
        setWillNotDraw(false);
        dash.setStyle(Paint.Style.STROKE);
        dash.setStrokeWidth(Ui.dp(c, 1.5f));
        dash.setColor(0x99FFFFFF);
        dash.setPathEffect(new android.graphics.DashPathEffect(new float[]{Ui.dp(c, 6), Ui.dp(c, 6)}, 0));
        int accent = Prefs.accent(c);
        frame.setStyle(Paint.Style.STROKE);
        frame.setStrokeWidth(Ui.dp(c, 2));
        frame.setColor(accent);
        handle.setColor(0xFFFFFFFF);
        handleRing.setStyle(Paint.Style.STROKE);
        handleRing.setStrokeWidth(Ui.dp(c, 2.5f));
        handleRing.setColor(accent);
        handleR = Ui.dp(c, 9);
        touchR = Ui.dp(c, 36);
        setBackgroundColor(0x33000000);
        post(new Runnable() { @Override public void run() { syncToWidget(); } });
    }

    private void syncToWidget() {
        int[] me = new int[2], g = new int[2], w = new int[2];
        getLocationInWindow(me);
        cl.getLocationInWindow(g);
        widget.getLocationInWindow(w);
        gridLeft = g[0] - me[0] + cl.getPaddingLeft();
        gridTop = g[1] - me[1] + cl.getPaddingTop();
        rect.set(w[0] - me[0], w[1] - me[1], w[0] - me[0] + widget.getWidth(), w[1] - me[1] + widget.getHeight());
        if (crop) { // the whole widget, as it would be without any crop
            LauncherWidgetView lw = (LauncherWidgetView) widget;
            float fw = rect.width() / (1f - lw.cropL - lw.cropR), fh = rect.height() / (1f - lw.cropT - lw.cropB);
            full.set(rect.left - lw.cropL * fw, rect.top - lw.cropT * fh, rect.left - lw.cropL * fw + fw, rect.top - lw.cropT * fh + fh);
        }
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        if (rect.isEmpty()) return;
        float r = Ui.dp(getContext(), 14);
        if (crop) c.drawRect(full, dash);
        c.drawRoundRect(rect, r, r, frame);
        drawHandle(c, rect.left, rect.centerY());
        drawHandle(c, rect.right, rect.centerY());
        drawHandle(c, rect.centerX(), rect.top);
        drawHandle(c, rect.centerX(), rect.bottom);
    }

    private void drawHandle(Canvas c, float x, float y) {
        c.drawCircle(x, y, handleR, handle);
        c.drawCircle(x, y, handleR, handleRing);
    }

    private int hit(float x, float y) {
        if (Math.hypot(x - rect.left, y - rect.centerY()) < touchR) return LEFT;
        if (Math.hypot(x - rect.right, y - rect.centerY()) < touchR) return RIGHT;
        if (Math.hypot(x - rect.centerX(), y - rect.top) < touchR) return TOP;
        if (Math.hypot(x - rect.centerX(), y - rect.bottom) < touchR) return BOTTOM;
        return NONE;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        int cw = Math.max(1, cl.cellWidth()), ch = Math.max(1, cl.cellHeight());
        float gridRight = gridLeft + cl.cols * cw, gridBottom = gridTop + cl.rows * ch;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                edge = hit(x, y);
                if (edge == NONE && free && !crop && rect.contains(x, y)) { edge = MOVE; offX = x - rect.left; offY = y - rect.top; }
                if (edge == NONE && !rect.contains(x, y)) launcher.finishWidgetResize();
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (edge == NONE) return true;
                if (free) { moveFree(x, y, cw, ch); return true; }
                switch (edge) {
                    case LEFT: rect.left = clamp(x, gridLeft, rect.right - cw * 0.6f); break;
                    case RIGHT: rect.right = clamp(x, rect.left + cw * 0.6f, gridRight); break;
                    case TOP: rect.top = clamp(y, gridTop, rect.bottom - ch * 0.6f); break;
                    case BOTTOM: rect.bottom = clamp(y, rect.top + ch * 0.6f, gridBottom); break;
                }
                int nx = Math.round((rect.left - gridLeft) / cw), nr = Math.round((rect.right - gridLeft) / cw);
                int ny = Math.round((rect.top - gridTop) / ch), nb = Math.round((rect.bottom - gridTop) / ch);
                nx = Math.max(0, Math.min(cl.cols - 1, nx));
                ny = Math.max(0, Math.min(cl.rows - 1, ny));
                int sx = Math.max(1, Math.min(cl.cols - nx, nr - nx)), sy = Math.max(1, Math.min(cl.rows - ny, nb - ny));
                if ((nx != item.x || ny != item.y || sx != item.spanX || sy != item.spanY) && launcher.canPlaceWidget(item, nx, ny, sx, sy)) {
                    item.x = nx;
                    item.y = ny;
                    item.spanX = sx;
                    item.spanY = sy;
                    CellLayout.LP lp = (CellLayout.LP) widget.getLayoutParams();
                    lp.x = nx;
                    lp.y = ny;
                    lp.sx = sx;
                    lp.sy = sy;
                    widget.requestLayout();
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                edge = NONE;
                postDelayed(new Runnable() { @Override public void run() { syncToWidget(); } }, 60);
                return true;
        }
        return true;
    }

    /** Free mode: no grid, no limits — the widget follows your finger anywhere on screen, edges included. */
    private void moveFree(float x, float y, int cw, int ch) {
        float min = Ui.dp(getContext(), 40), W = getWidth(), H = getHeight();
        if (crop) { // stay inside the whole widget, and keep at least a tenth of it
            min = Math.max(min, Math.min(full.width(), full.height()) * 0.1f);
            switch (edge) {
                case LEFT: rect.left = clamp(x, full.left, rect.right - min); break;
                case RIGHT: rect.right = clamp(x, rect.left + min, full.right); break;
                case TOP: rect.top = clamp(y, full.top, rect.bottom - min); break;
                case BOTTOM: rect.bottom = clamp(y, rect.top + min, full.bottom); break;
            }
            LauncherWidgetView lw = (LauncherWidgetView) widget;
            float fw = full.width(), fh = full.height();
            item.cropL = (rect.left - full.left) / fw; item.cropR = (full.right - rect.right) / fw;
            item.cropT = (rect.top - full.top) / fh; item.cropB = (full.bottom - rect.bottom) / fh;
            lw.setCrop(item.cropL, item.cropT, item.cropR, item.cropB);
        }
        if (!crop) switch (edge) {
            case LEFT: rect.left = clamp(x, 0, rect.right - min); break;
            case RIGHT: rect.right = clamp(x, rect.left + min, W); break;
            case TOP: rect.top = clamp(y, 0, rect.bottom - min); break;
            case BOTTOM: rect.bottom = clamp(y, rect.top + min, H); break;
            case MOVE: {
                float w = rect.width(), h = rect.height();
                rect.left = clamp(x - offX, 0, W - w);
                rect.top = clamp(y - offY, 0, H - h);
                rect.right = rect.left + w;
                rect.bottom = rect.top + h;
                break;
            }
        }
        float ox = gridLeft - cl.getPaddingLeft(), oy = gridTop - cl.getPaddingTop();
        launcher.applyFree(item, cl, rect.left - ox, rect.top - oy, rect.right - ox, rect.bottom - oy);
        CellLayout.LP lp = (CellLayout.LP) widget.getLayoutParams();
        lp.x = item.x; lp.y = item.y; lp.sx = item.spanX; lp.sy = item.spanY;
        lp.fx = item.fx; lp.fy = item.fy; lp.fw = item.fw; lp.fh = item.fh;
        widget.requestLayout();
        invalidate();
    }

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
}
