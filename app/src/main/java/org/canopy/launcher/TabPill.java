package org.canopy.launcher;

import android.animation.ValueAnimator;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;

/**
 * YouTube-style tab highlight: the pill behind the selected tab slides (and stretches) from one tab to the next
 * instead of jumping. It is drawn in the tab strip's overlay; the target tab's own background is hidden until it lands.
 */
final class TabPill {
    private TabPill() { }

    static Rect rectOf(View chip) {
        if (chip == null) return null;
        return new Rect(chip.getLeft(), chip.getTop(), chip.getRight(), chip.getBottom());
    }

    static void lerp(Rect out, Rect a, Rect b, float t) {
        out.set(Math.round(a.left + (b.left - a.left) * t), Math.round(a.top + (b.top - a.top) * t),
                Math.round(a.right + (b.right - a.right) * t), Math.round(a.bottom + (b.bottom - a.bottom) * t));
    }

    /** Slide a copy of target's background from rect `from` to target. */
    static void slide(final ViewGroup bar, Rect from, final View target, long ms) {
        if (bar == null || from == null || target == null || target.getWidth() == 0) return;
        final Drawable bg = target.getBackground();
        if (bg == null || bg.getConstantState() == null) return;
        final Drawable pill = bg.getConstantState().newDrawable().mutate();
        final Rect a = new Rect(from), b = rectOf(target), cur = new Rect();
        if (a.equals(b)) return;
        pill.setState(new int[0]);
        pill.setBounds(a);
        bar.getOverlay().add(pill);
        target.setBackground(null);
        ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(Anim.dur(bar.getContext(), ms));
        va.setInterpolator(Anim.open(bar.getContext()));
        va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator v) {
                lerp(cur, a, b, (float) v.getAnimatedValue());
                pill.setBounds(cur);
                bar.invalidate();
            }
        });
        va.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator x) {
                bar.getOverlay().remove(pill);
                if (target.getBackground() == null) target.setBackground(bg);
            }
        });
        va.start();
    }
}
