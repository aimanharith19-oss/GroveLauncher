/*
 * Copyright 2026 The Canopy Launcher authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.canopy.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewGroup;

/** A grid of cells (one home screen page, or the dock). Items may span several cells (widgets). */
public class CellLayout extends ViewGroup {
    public final int container;
    public int page;
    public int cols, rows;
    /** Row offset for the second dock row (Microsoft-style expandable dock). */
    public int yOffset;
    private int cellW, cellH;
    private final Paint hl = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF hlRect = new RectF();
    private boolean hlOn, hlFolder;
    private final int accent;

    public static class LP extends ViewGroup.LayoutParams {
        public int x, y, sx, sy;
        /** Free placement in cell units (NaN = use the cell position above). */
        public float fx = Float.NaN, fy, fw, fh;
        /** Reach the screen edge on the side(s) where it touches the first / last column. */
        public boolean edge;
        public boolean free() { return !Float.isNaN(fx); }
        public LP(int x, int y, int sx, int sy) {
            super(MATCH_PARENT, MATCH_PARENT);
            this.x = x; this.y = y; this.sx = sx; this.sy = sy;
        }
    }

    public CellLayout(Context c, int container, int page, int cols, int rows) {
        super(c);
        this.container = container;
        this.page = page;
        this.cols = Math.max(1, cols);
        this.rows = Math.max(1, rows);
        accent = Prefs.accent(c);
        setWillNotDraw(false);
        setClipChildren(false);
        setClipToPadding(false);
    }

    public void addItem(View v, int x, int y, int sx, int sy) {
        addView(v, new LP(x, y - yOffset, sx, sy));
    }

    public int cellWidth() { return cellW; }
    public int cellHeight() { return cellH; }

    /** Distance from this page's grid to the screen's left / right edge (its own and the home screen's side padding). */
    private int edgeL() { View p = (View) getParent(); return getPaddingLeft() + (p != null ? p.getPaddingLeft() : 0); }
    private int edgeR() { View p = (View) getParent(); return getPaddingRight() + (p != null ? p.getPaddingRight() : 0); }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec), h = MeasureSpec.getSize(hSpec);
        cellW = Math.max(1, (w - getPaddingLeft() - getPaddingRight()) / cols);
        cellH = Math.max(1, (h - getPaddingTop() - getPaddingBottom()) / rows);
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            LP lp = (LP) v.getLayoutParams();
            int mw = lp.free() ? Math.max(1, Math.round(lp.fw * cellW)) : lp.sx * cellW;
            if (lp.edge && !lp.free()) mw += (lp.x == 0 ? edgeL() : 0) + (lp.x + lp.sx >= cols ? edgeR() : 0);
            int mh = lp.free() ? Math.max(1, Math.round(lp.fh * cellH)) : lp.sy * cellH;
            v.measure(MeasureSpec.makeMeasureSpec(mw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(mh, MeasureSpec.EXACTLY));
        }
        setMeasuredDimension(w, h);
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            LP lp = (LP) v.getLayoutParams();
            if (lp.free()) {
                int left = getPaddingLeft() + Math.round(lp.fx * cellW), top = getPaddingTop() + Math.round(lp.fy * cellH);
                v.layout(left, top, left + v.getMeasuredWidth(), top + v.getMeasuredHeight());
                continue;
            }
            int left = getPaddingLeft() + lp.x * cellW, top = getPaddingTop() + lp.y * cellH;
            if (lp.edge) {
                int l2 = lp.x == 0 ? left - edgeL() : left;
                v.layout(l2, top, l2 + v.getMeasuredWidth(), top + lp.sy * cellH);
                continue;
            }
            v.layout(left, top, left + lp.sx * cellW, top + lp.sy * cellH);
        }
    }

    /** Top-left cell for an item of the given span centred under (px, py). */
    public int[] cellAt(float px, float py, int sx, int sy) {
        int cx = Math.round((px - getPaddingLeft()) / (float) cellW - sx / 2f);
        int cy = Math.round((py - getPaddingTop()) / (float) cellH - sy / 2f);
        cx = Math.max(0, Math.min(cols - sx, cx));
        cy = Math.max(0, Math.min(rows - sy, cy));
        return new int[]{cx, cy + yOffset};
    }

    public void setHighlight(int x, int y, int sx, int sy, boolean folder) {
        float inset = Ui.dp(getContext(), 4);
        y -= yOffset;
        hlRect.set(getPaddingLeft() + x * cellW + inset, getPaddingTop() + y * cellH + inset,
                getPaddingLeft() + (x + sx) * cellW - inset, getPaddingTop() + (y + sy) * cellH - inset);
        hlOn = true;
        hlFolder = folder;
        invalidate();
    }

    /** Highlight an exact pixel rectangle (free widget placement). */
    public void setHighlightPx(float l, float t, float r, float b) {
        hlRect.set(l, t, r, b);
        hlOn = true;
        hlFolder = false;
        invalidate();
    }

    public void clearHighlight() {
        if (!hlOn) return;
        hlOn = false;
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        if (!hlOn) return;
        if (hlFolder) {
            hl.setColor(Ui.alpha(accent, 0x99));
            float r = Math.min(hlRect.width(), hlRect.height()) / 2f;
            c.drawCircle(hlRect.centerX(), hlRect.centerY(), r, hl);
        } else {
            hl.setColor(Ui.alpha(0xFFFFFFFF, 0x33));
            c.drawRoundRect(hlRect, Ui.dp(getContext(), 16), Ui.dp(getContext(), 16), hl);
        }
    }

    @Override protected boolean checkLayoutParams(ViewGroup.LayoutParams p) { return p instanceof LP; }
    @Override protected ViewGroup.LayoutParams generateDefaultLayoutParams() { return new LP(0, 0, 1, 1); }
    @Override protected ViewGroup.LayoutParams generateLayoutParams(ViewGroup.LayoutParams p) { return new LP(0, 0, 1, 1); }
}
