/*
 * Copyright 2026 The Canopy Launcher authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.canopy.launcher;

import android.animation.TimeInterpolator;
import android.content.Context;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;

/**
 * Animation styles. "fluid" (default) is an original take on the springy, physical motion people like in
 * minimalist launchers such as Niagara; "classic" is the plain Nova-like slide; "minimal" barely moves.
 */
public final class Anim {
    private Anim() {}

    public static String style(Context c) {
        android.content.SharedPreferences p = Prefs.sp(c);
        // 1.61: "Calm" (smooth Material motion, no bounce) is the new default; the old springy default moves to it once
        if (!p.getBoolean("anim_calm_set", false)) {
            p.edit().putBoolean("anim_calm_set", true).putString("anim_style", "fluid".equals(p.getString("anim_style", "fluid")) ? "calm" : p.getString("anim_style", "calm")).apply();
        }
        return p.getString("anim_style", "calm");
    }
    /** Springy, bouncy motion (the "Bouncy" choice). */
    public static boolean fluid(Context c) { return "fluid".equals(style(c)); }
    /** Smooth Material 3 motion: quick to start, long gentle settle, never overshoots. */
    public static boolean calm(Context c) { return "calm".equals(style(c)); }

    // Material 3 easing curves
    static final Interpolator EMPHASIZED_DECEL = new android.view.animation.PathInterpolator(0.05f, 0.7f, 0.1f, 1f);
    static final Interpolator STANDARD = new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f);
    static final Interpolator EMPHASIZED_ACCEL = new android.view.animation.PathInterpolator(0.3f, 0f, 0.8f, 0.15f);
    public static boolean minimal(Context c) { return "minimal".equals(style(c)); }

    /** Scales a base duration by the user's speed setting (100% = normal). */
    public static long dur(Context c, long base) {
        int speed = Math.max(25, Prefs.sp(c).getInt("anim_speed", 100));
        if (minimal(c)) base = base / 2;
        return Math.max(1, base * 100 / speed);
    }

    /** A damped spring: quick start, slight overshoot, settles. */
    public static final class Spring implements Interpolator {
        private final float damping, freq;
        public Spring(float damping, float freq) { this.damping = damping; this.freq = freq; }
        @Override public float getInterpolation(float t) {
            if (t >= 1f) return 1f;
            return (float) (1 - Math.exp(-damping * t) * Math.cos(freq * t));
        }
    }

    public static Interpolator open(Context c) {
        if (fluid(c)) return new Spring(7.5f, 9f);
        if (calm(c)) return EMPHASIZED_DECEL;
        return new DecelerateInterpolator(minimal(c) ? 1f : 1.8f);
    }

    public static Interpolator gentle(Context c) {
        if (fluid(c)) return new Spring(9f, 7f);
        if (calm(c)) return STANDARD;
        return new DecelerateInterpolator(1.5f);
    }

    public static TimeInterpolator close(Context c) { return calm(c) ? STANDARD : new DecelerateInterpolator(1.4f); }

    /** Springy "pop in" for folders, menus and cards. */
    public static void popIn(View v) {
        Context c = v.getContext();
        if (minimal(c)) { v.setAlpha(0f); v.animate().alpha(1f).setDuration(dur(c, 120)).start(); return; }
        if (calm(c)) {
            v.setScaleX(0.94f);
            v.setScaleY(0.94f);
            v.setAlpha(0f);
            v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(dur(c, 260)).setInterpolator(EMPHASIZED_DECEL).start();
            return;
        }
        v.setScaleX(fluid(c) ? 0.72f : 0.88f);
        v.setScaleY(fluid(c) ? 0.72f : 0.88f);
        v.setAlpha(0f);
        v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(dur(c, fluid(c) ? 420 : 200)).setInterpolator(open(c)).start();
    }

    /** Items rise into place one after another (drawer, feed). */
    public static void stagger(View v, int index) {
        Context c = v.getContext();
        if (calm(c)) { // a soft rise, all close together
            v.setAlpha(0f);
            v.setTranslationY(Ui.dp(c, 12));
            v.animate().alpha(1f).translationY(0).setStartDelay(Math.min(index, 12) * dur(c, 12))
                    .setDuration(dur(c, 300)).setInterpolator(EMPHASIZED_DECEL).start();
            return;
        }
        if (!fluid(c)) return;
        v.setAlpha(0f);
        v.setTranslationY(Ui.dp(c, 26));
        v.animate().alpha(1f).translationY(0).setStartDelay(Math.min(index, 24) * dur(c, 14))
                .setDuration(dur(c, 380)).setInterpolator(new Spring(8f, 8f)).start();
    }

    public static boolean haptics(Context c) { return Prefs.sp(c).getBoolean("haptics", true); }

    /** How long to hold before an icon or widget lifts: quicker than the system default (often 500 ms), so moving things feels immediate. */
    public static int longPress() { return Math.max(180, Math.min(280, android.view.ViewConfiguration.getLongPressTimeout())); }
}
