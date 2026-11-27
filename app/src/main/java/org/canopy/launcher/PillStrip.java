package org.canopy.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewParent;
import android.widget.LinearLayout;

/**
 * A tab strip with one selection pill that belongs to the strip itself (YouTube-style): it glides and stretches
 * from tab to tab, follows a swipe, and survives the tabs being rebuilt, so it never flashes or jumps.
 */
final class PillStrip extends LinearLayout {
    private Drawable pill;
    private boolean onTop;
    private View sel;
    private final RectF cur = new RectF();
    private final RectF from = new RectF(), to = new RectF();
    private boolean has, dragging;
    private ValueAnimator anim;
    private final Rect tmp = new Rect();

    PillStrip(Context c) { super(c); setWillNotDraw(false); }

    /** The pill's look; onTop draws it over the tabs (a translucent ring) instead of behind them. */
    void setPill(Drawable d, boolean onTop) { pill = d; this.onTop = onTop; invalidate(); }

    /** Mark the selected tab (any view inside the strip). The pill glides there; the first time it just appears. */
    void select(View v) {
        sel = v;
        if (!dragging) requestLayout();
    }

    /** During a swipe: put the pill part of the way (t 0..1) from one tab to another. */
    void drag(View a, View b, float t) {
        if (!rectOf(a, from)) return;
        dragging = true;
        if (anim != null) anim.cancel();
        if (b == null || !rectOf(b, to)) to.set(from);
        lerp(cur, from, to, t);
        has = true;
        invalidate();
    }

    /** Swipe ended: glide to the selected tab (the old one if the swipe was cancelled). */
    void endDrag() { dragging = false; glide(); }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (!dragging) glide();
    }

    private void glide() {
        if (sel == null || !rectOf(sel, to)) return;
        if (!has || getVisibility() != VISIBLE || !isAttachedToWindow()) { cur.set(to); has = true; invalidate(); return; }
        if (cur.equals(to)) return;
        if (anim != null && anim.isRunning() && to.equals(endRect)) return; // already heading there
        if (anim != null) anim.cancel();
        from.set(cur);
        endRect.set(to);
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(Anim.dur(getContext(), 560)); // slow and smooth, like YouTube's
        anim.setInterpolator(Anim.STANDARD); // even glide, no fast snap or overshoot
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator v) {
                lerp(cur, from, endRect, (float) v.getAnimatedValue());
                invalidate();
            }
        });
        anim.start();
    }
    private final RectF endRect = new RectF();

    private static void lerp(RectF out, RectF a, RectF b, float t) {
        out.set(a.left + (b.left - a.left) * t, a.top + (b.top - a.top) * t, a.right + (b.right - a.right) * t, a.bottom + (b.bottom - a.bottom) * t);
    }

    /** v's bounds in this strip's coordinates (v may sit inside a group chip). */
    private boolean rectOf(View v, RectF out) {
        if (v == null || v.getWidth() == 0) return false;
        float x = v.getLeft(), y = v.getTop();
        ViewParent p = v.getParent();
        while (p instanceof View && p != this) {
            View pv = (View) p;
            x += pv.getLeft() - pv.getScrollX();
            y += pv.getTop() - pv.getScrollY();
            p = pv.getParent();
        }
        if (p != this) return false;
        out.set(x, y, x + v.getWidth(), y + v.getHeight());
        return true;
    }

    private void drawPill(Canvas c) {
        if (pill == null || !has || cur.width() <= 0) return;
        tmp.set(Math.round(cur.left), Math.round(cur.top), Math.round(cur.right), Math.round(cur.bottom));
        pill.setBounds(tmp);
        pill.draw(c);
    }

    @Override protected void dispatchDraw(Canvas c) {
        if (!onTop) drawPill(c);
        super.dispatchDraw(c);
        if (onTop) drawPill(c);
    }
}
