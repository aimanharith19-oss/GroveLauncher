/*
 * Copyright 2026 The Canopy Launcher authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.canopy.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The app drawer grid. Widgets sit at any cell with any size; apps flow into the remaining cells in
 * order, so when you move or resize a widget the apps rearrange around it automatically.
 */
public class DrawerGrid extends ViewGroup {
    public static final class W {
        public int id, col, row, sx, sy;
        /** Drawer tab it lives on: "all", "personal", "work" or "t" + tab id. */
        public String tab = "all";
    }

    public interface Host {
        IconView newIcon();
        void bindIcon(IconView v, Object item);
        View widgetView(W w);
        void onWidgetsChanged();
        void onWidgetRemove(W w);
        boolean editingAllowed();
    }

    private final Host host;
    private List<Object> items = new ArrayList<>();
    private List<W> widgets = new ArrayList<>();
    private final List<IconView> icons = new ArrayList<>();
    private final Map<Integer, View> widgetViews = new HashMap<>();
    private int cols = 5, cellH = 100, cellW = 1;
    private int[] itemRow = new int[0], itemCol = new int[0];
    private int rowsUsed;

    // edit mode (move / resize a widget)
    private W editing;
    private static final int NONE = 0, MOVE = 1, LEFT = 2, TOP = 3, RIGHT = 4, BOTTOM = 5, REMOVE = 6;
    private int mode = NONE;
    private float offX, offY;
    private final RectF frame = new RectF();
    private final Paint framePaint = new Paint(Paint.ANTI_ALIAS_FLAG), handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG),
            ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG), xPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float handleR, touchR;

    public DrawerGrid(Context c, Host host) {
        super(c);
        this.host = host;
        setWillNotDraw(false);
        setClipChildren(false);
        int accent = Prefs.accent(c);
        framePaint.setStyle(Paint.Style.STROKE);
        framePaint.setStrokeWidth(Ui.dp(c, 2));
        framePaint.setColor(accent);
        handlePaint.setColor(0xFFFFFFFF);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(Ui.dp(c, 2.5f));
        ringPaint.setColor(accent);
        xPaint.setColor(0xFFE53935);
        xPaint.setStrokeWidth(Ui.dp(c, 2));
        xPaint.setStrokeCap(Paint.Cap.ROUND);
        handleR = Ui.dp(c, 9);
        touchR = Ui.dp(c, 34);
    }

    public void setData(List<Object> items, List<W> widgets, int cols, int cellH) {
        this.items = items;
        this.widgets = widgets;
        this.cols = Math.max(1, cols);
        this.cellH = Math.max(1, cellH);
        if (editing != null) {
            W still = null;
            for (W w : widgets) if (w.id == editing.id) still = w;
            editing = still;
        }
        removeAllViews();
        Map<Integer, View> keep = new HashMap<>();
        for (W w : widgets) {
            View v = widgetViews.get(w.id);
            if (v == null) v = host.widgetView(w);
            if (v == null) continue;
            if (v.getParent() != null) ((ViewGroup) v.getParent()).removeView(v);
            keep.put(w.id, v);
            addView(v);
        }
        widgetViews.clear();
        widgetViews.putAll(keep);
        while (icons.size() < items.size()) icons.add(host.newIcon());
        for (int i = 0; i < items.size(); i++) {
            IconView v = icons.get(i);
            v.setAlpha(1f);
            v.setTranslationY(0);
            host.bindIcon(v, items.get(i));
            addView(v);
        }
        requestLayout();
        invalidate();
    }

    public void rebindIcons() {
        for (int i = 0; i < items.size() && i < icons.size(); i++) host.bindIcon(icons.get(i), items.get(i));
    }

    public List<W> widgets() { return widgets; }
    public int cellHeight() { return cellH; }

    /** Top (in grid pixels) of the row holding item i, for the A–Z scrubber. */
    public int topOfItem(int i) {
        if (i < 0 || i >= itemRow.length) return 0;
        return getPaddingTop() + itemRow[i] * cellH;
    }

    public int rowOfItem(int i) { return i >= 0 && i < itemRow.length ? itemRow[i] : 0; }

    // ------------------------------------------------------------------ layout

    private void place() {
        List<boolean[]> occ = new ArrayList<>();
        for (W w : widgets) {
            w.sx = Math.max(1, Math.min(cols, w.sx));
            w.sy = Math.max(1, w.sy);
            w.col = Math.max(0, Math.min(cols - w.sx, w.col));
            w.row = Math.max(0, w.row);
            for (int r = w.row; r < w.row + w.sy; r++) {
                while (occ.size() <= r) occ.add(new boolean[cols]);
                for (int c = w.col; c < w.col + w.sx; c++) occ.get(r)[c] = true;
            }
        }
        itemRow = new int[items.size()];
        itemCol = new int[items.size()];
        int r = 0, c = 0;
        for (int i = 0; i < items.size(); i++) {
            while (true) {
                while (occ.size() <= r) occ.add(new boolean[cols]);
                if (!occ.get(r)[c]) break;
                c++;
                if (c >= cols) { c = 0; r++; }
            }
            occ.get(r)[c] = true;
            itemRow[i] = r;
            itemCol[i] = c;
            c++;
            if (c >= cols) { c = 0; r++; }
        }
        rowsUsed = occ.size();
    }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        cellW = Math.max(1, (w - getPaddingLeft() - getPaddingRight()) / cols);
        place();
        for (int i = 0; i < widgets.size(); i++) {
            View v = widgetViews.get(widgets.get(i).id);
            if (v == null) continue;
            W ww = widgets.get(i);
            v.measure(MeasureSpec.makeMeasureSpec(ww.sx * cellW, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ww.sy * cellH, MeasureSpec.EXACTLY));
        }
        for (int i = 0; i < items.size() && i < icons.size(); i++) {
            icons.get(i).measure(MeasureSpec.makeMeasureSpec(cellW, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(cellH, MeasureSpec.EXACTLY));
        }
        int extra = editing != null ? cellH * 3 : 0; // room to drag a widget further down
        setMeasuredDimension(w, getPaddingTop() + getPaddingBottom() + rowsUsed * cellH + extra);
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int pl = getPaddingLeft(), pt = getPaddingTop();
        for (W w : widgets) {
            View v = widgetViews.get(w.id);
            if (v == null) continue;
            int x = pl + w.col * cellW, y = pt + w.row * cellH;
            v.layout(x, y, x + w.sx * cellW, y + w.sy * cellH);
        }
        for (int i = 0; i < items.size() && i < icons.size(); i++) {
            int x = pl + itemCol[i] * cellW, y = pt + itemRow[i] * cellH;
            icons.get(i).layout(x, y, x + cellW, y + cellH);
        }
        if (editing != null && mode == NONE) syncFrame();
    }

    // ------------------------------------------------------------------ edit mode

    public boolean isEditing() { return editing != null; }

    public void startEdit(W w) {
        if (!host.editingAllowed()) return;
        editing = w;
        mode = NONE;
        if (Anim.haptics(getContext())) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        requestLayout();
        post(new Runnable() { @Override public void run() { syncFrame(); invalidate(); } });
        Ui.toast(getContext(), "Drag to move • drag a handle to resize • tap outside when done");
    }

    public void finishEdit() {
        if (editing == null) return;
        editing = null;
        mode = NONE;
        host.onWidgetsChanged();
        requestLayout();
        invalidate();
    }

    private void syncFrame() {
        if (editing == null) return;
        int pl = getPaddingLeft(), pt = getPaddingTop();
        frame.set(pl + editing.col * cellW, pt + editing.row * cellH,
                pl + (editing.col + editing.sx) * cellW, pt + (editing.row + editing.sy) * cellH);
    }

    @Override protected void dispatchDraw(Canvas c) {
        super.dispatchDraw(c);
        if (editing == null || frame.isEmpty()) return;
        float r = Ui.dp(getContext(), 16);
        c.drawRoundRect(frame, r, r, framePaint);
        handle(c, frame.left, frame.centerY());
        handle(c, frame.right, frame.centerY());
        handle(c, frame.centerX(), frame.top);
        handle(c, frame.centerX(), frame.bottom);
        // remove button (top-right corner)
        float cx = frame.right - Ui.dp(getContext(), 4), cy = frame.top + Ui.dp(getContext(), 4), rr = Ui.dp(getContext(), 12);
        c.drawCircle(cx, cy, rr, handlePaint);
        float k = rr * 0.45f;
        c.drawLine(cx - k, cy - k, cx + k, cy + k, xPaint);
        c.drawLine(cx - k, cy + k, cx + k, cy - k, xPaint);
    }

    private void handle(Canvas c, float x, float y) {
        c.drawCircle(x, y, handleR, handlePaint);
        c.drawCircle(x, y, handleR, ringPaint);
    }

    private int hit(float x, float y) {
        if (Math.hypot(x - (frame.right - Ui.dp(getContext(), 4)), y - (frame.top + Ui.dp(getContext(), 4))) < Ui.dp(getContext(), 18)) return REMOVE;
        if (Math.hypot(x - frame.left, y - frame.centerY()) < touchR) return LEFT;
        if (Math.hypot(x - frame.right, y - frame.centerY()) < touchR) return RIGHT;
        if (Math.hypot(x - frame.centerX(), y - frame.top) < touchR) return TOP;
        if (Math.hypot(x - frame.centerX(), y - frame.bottom) < touchR) return BOTTOM;
        if (frame.contains(x, y)) return MOVE;
        return NONE;
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent ev) { return editing != null; }

    private void edgeScroll(float y) {
        // find the scrolling ancestor and put y in its content coordinates
        View p = this;
        float off = 0;
        while (p.getParent() instanceof View && !(p.getParent() instanceof ScrollView)) {
            off += p.getTop();
            p = (View) p.getParent();
        }
        if (!(p.getParent() instanceof ScrollView)) return;
        off += p.getTop();
        y += off;
        ScrollView sv = (ScrollView) p.getParent();
        float visTop = sv.getScrollY() + sv.getPaddingTop(), visBottom = sv.getScrollY() + sv.getHeight(), edge = Ui.dp(getContext(), 72);
        if (y < visTop + edge) sv.scrollBy(0, -Ui.dp(getContext(), 14));
        else if (y > visBottom - edge) sv.scrollBy(0, Ui.dp(getContext(), 14));
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (editing == null) return false;
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                mode = hit(x, y);
                if (mode == REMOVE) {
                    W w = editing;
                    editing = null;
                    mode = NONE;
                    host.onWidgetRemove(w);
                    return true;
                }
                if (mode == NONE) { finishEdit(); return true; }
                offX = x - frame.left;
                offY = y - frame.top;
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (mode == NONE) return true;
                int pl = getPaddingLeft(), pt = getPaddingTop();
                float right = pl + cols * cellW;
                if (mode == MOVE) {
                    float w = frame.width(), h = frame.height();
                    frame.left = Math.max(pl, Math.min(right - w, x - offX));
                    frame.top = Math.max(pt, y - offY);
                    frame.right = frame.left + w;
                    frame.bottom = frame.top + h;
                } else if (mode == LEFT) frame.left = Math.max(pl, Math.min(frame.right - cellW * 0.6f, x));
                else if (mode == RIGHT) frame.right = Math.min(right, Math.max(frame.left + cellW * 0.6f, x));
                else if (mode == TOP) frame.top = Math.max(pt, Math.min(frame.bottom - cellH * 0.6f, y));
                else if (mode == BOTTOM) frame.bottom = Math.max(frame.top + cellH * 0.6f, y);
                int nc = Math.round((frame.left - pl) / cellW), nr = Math.round((frame.top - pt) / cellH);
                int nsx = Math.max(1, Math.round(frame.width() / cellW)), nsy = Math.max(1, Math.round(frame.height() / cellH));
                nsx = Math.min(cols, nsx);
                nc = Math.max(0, Math.min(cols - nsx, nc));
                nr = Math.max(0, nr);
                if (nc != editing.col || nr != editing.row || nsx != editing.sx || nsy != editing.sy) {
                    editing.col = nc;
                    editing.row = nr;
                    editing.sx = nsx;
                    editing.sy = nsy;
                    if (Anim.haptics(getContext())) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    requestLayout(); // apps reflow around the widget
                }
                edgeScroll(y);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mode = NONE;
                syncFrame();
                invalidate();
                host.onWidgetsChanged();
                return true;
        }
        return true;
    }
}
