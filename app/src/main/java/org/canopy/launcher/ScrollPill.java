/*
 * Copyright 2026 The Canopy Launcher authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.canopy.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.AbsListView;

/**
 * A floating, rounded scroll indicator: a slim accent pill that grows and brightens while you scroll,
 * fades back when idle, and can be grabbed and dragged.
 */
public class ScrollPill extends View implements AbsListView.OnScrollListener {
    private AbsListView list;
    private android.widget.ScrollView sv;
    private int first, visible, total;
    private float active;          // 0 idle .. 1 scrolling / dragging
    private boolean dragging;
    private final Paint thumb = new Paint(Paint.ANTI_ALIAS_FLAG), glow = new Paint(Paint.ANTI_ALIAS_FLAG),
            track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final int accent;
    private ValueAnimator fade;
    private final Runnable idle = new Runnable() { @Override public void run() { if (!dragging) animateTo(0f); } };

    public ScrollPill(Context c, int accent, int trackColor) {
        super(c);
        this.accent = accent;
        glow.setColor(Ui.alpha(accent, 0x40));
        track.setColor(Ui.alpha(trackColor, 0x22));
    }

    public void attach(AbsListView l) {
        list = l;
        l.setOnScrollListener(this);
        l.setVerticalScrollBarEnabled(false);
    }

    /** Pixel-based tracking for a ScrollView. */
    public void attachScroll(final android.widget.ScrollView s) {
        sv = s;
        s.setVerticalScrollBarEnabled(false);
        s.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override public void onScrollChange(View v, int x, int y, int ox, int oy) { syncScroll(); if (y != oy) poke(); }
        });
        s.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) { syncScroll(); }
        });
    }

    public void syncScroll() {
        if (sv == null) return;
        first = sv.getScrollY();
        visible = sv.getHeight();
        total = sv.getChildCount() > 0 ? sv.getChildAt(0).getHeight() : 0;
        invalidate();
    }

    private void animateTo(float target) {
        if (fade != null) fade.cancel();
        fade = ValueAnimator.ofFloat(active, target);
        fade.setDuration(Anim.dur(getContext(), target > active ? 140 : 420));
        fade.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) { active = (Float) a.getAnimatedValue(); invalidate(); }
        });
        fade.start();
    }

    private void poke() {
        if (active < 1f && (fade == null || !fade.isRunning())) animateTo(1f);
        removeCallbacks(idle);
        postDelayed(idle, 1100);
    }

    @Override public void onScrollStateChanged(AbsListView v, int state) {
        if (state != SCROLL_STATE_IDLE) poke();
    }

    @Override public void onScroll(AbsListView v, int f, int vis, int tot) {
        boolean moved = f != first;
        first = f;
        visible = vis;
        total = tot;
        if (moved) poke();
        invalidate();
    }

    private boolean scrollable() { return total > 0 && visible > 0 && total > visible; }

    private float thumbH() {
        float h = getHeight() - getPaddingTop() - getPaddingBottom();
        return Math.max(Ui.dp(getContext(), 44), h * visible / (float) Math.max(1, total));
    }

    @Override protected void onDraw(Canvas c) {
        if (!scrollable()) return;
        float h = getHeight() - getPaddingTop() - getPaddingBottom();
        float th = thumbH();
        float frac = first / (float) Math.max(1, total - visible);
        float top = getPaddingTop() + (h - th) * Math.min(1f, frac);
        float width = Ui.dp(getContext(), 4) + active * Ui.dp(getContext(), 4);
        float right = getWidth() - Ui.dp(getContext(), 4);
        r.set(right - width, top, right, top + th);
        if (active > 0.02f) {
            float g = Ui.dp(getContext(), 5) * active;
            glow.setAlpha((int) (0x40 * active));
            c.drawRoundRect(r.left - g, r.top - g, r.right + g, r.bottom + g, width, width, glow);
        }
        thumb.setShader(new LinearGradient(0, r.top, 0, r.bottom, Ui.alpha(accent, 0xFF),
                Ui.alpha(accent, 0xB0), Shader.TileMode.CLAMP));
        thumb.setAlpha((int) (110 + 145 * active));
        c.drawRoundRect(r, width, width, thumb);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (!scrollable() || (list == null && sv == null)) return false;
        float h = getHeight() - getPaddingTop() - getPaddingBottom(), th = thumbH();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                float frac = first / (float) Math.max(1, total - visible);
                float top = getPaddingTop() + (h - th) * Math.min(1f, frac);
                boolean onThumb = e.getX() > getWidth() - Ui.dp(getContext(), 22)
                        && e.getY() > top - Ui.dp(getContext(), 16) && e.getY() < top + th + Ui.dp(getContext(), 16);
                if (!onThumb) return false;
                dragging = true;
                getParent().requestDisallowInterceptTouchEvent(true);
                if (Anim.haptics(getContext())) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                animateTo(1f);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (!dragging) return false;
                float f = (e.getY() - getPaddingTop() - th / 2) / Math.max(1f, h - th);
                f = Math.max(0f, Math.min(1f, f));
                if (sv != null) sv.scrollTo(0, Math.round(f * (total - visible)));
                else list.setSelection(Math.round(f * (total - visible)));
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                removeCallbacks(idle);
                postDelayed(idle, 900);
                return true;
        }
        return dragging;
    }
}
