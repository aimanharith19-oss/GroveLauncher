/*
 * Copyright 2026 The Canopy Launcher authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.canopy.launcher;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

/** Nova-style icon shapes: reshape adaptive icons and optionally wrap legacy (square) icons. */
public final class IconShapes {
    public static final String[] KEYS = {"system", "circle", "squircle", "rounded", "teardrop", "square"};
    public static final String[] LABELS = {"System default", "Circle", "Squircle", "Rounded square", "Teardrop", "Square"};

    private IconShapes() {}

    static Path path(String shape, float s) {
        Path p = new Path();
        switch (shape) {
            case "circle":
                p.addCircle(s / 2, s / 2, s / 2, Path.Direction.CW);
                break;
            case "squircle": {
                float c = s / 2, r = s / 2, k = 0.92f;
                p.moveTo(0, c);
                p.cubicTo(0, c - r * k, c - r * k, 0, c, 0);
                p.cubicTo(c + r * k, 0, s, c - r * k, s, c);
                p.cubicTo(s, c + r * k, c + r * k, s, c, s);
                p.cubicTo(c - r * k, s, 0, c + r * k, 0, c);
                p.close();
                break;
            }
            case "rounded":
                p.addRoundRect(new RectF(0, 0, s, s), s * 0.22f, s * 0.22f, Path.Direction.CW);
                break;
            case "teardrop":
                p.addRoundRect(new RectF(0, 0, s, s), new float[]{s / 2, s / 2, s / 2, s / 2, s * 0.12f, s * 0.12f, s / 2, s / 2}, Path.Direction.CW);
                break;
            default:
                p.addRect(0, 0, s, s, Path.Direction.CW);
        }
        return p;
    }

    /** Returns the original drawable for "system", otherwise a reshaped bitmap. */
    public static Drawable apply(Drawable d, String shape, boolean wrapLegacy, Resources res) {
        if (d == null || "system".equals(shape)) return d;
        final int s = 192;
        Bitmap out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Path clip = path(shape, s);
        if (d instanceof AdaptiveIconDrawable) {
            AdaptiveIconDrawable a = (AdaptiveIconDrawable) d;
            c.save();
            c.clipPath(clip);
            int pad = s / 4; // adaptive layers are 108dp with a 72dp visible area
            Drawable bg = a.getBackground(), fg = a.getForeground();
            if (bg != null) { bg.setBounds(-pad, -pad, s + pad, s + pad); bg.draw(c); }
            if (fg != null) { fg.setBounds(-pad, -pad, s + pad, s + pad); fg.draw(c); }
            c.restore();
        } else if (wrapLegacy) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(0xFFF4F4F4);
            c.drawPath(clip, p);
            int inset = (int) (s * 0.17f);
            d.setBounds(inset, inset, s - inset, s - inset);
            d.draw(c);
        } else {
            return d;
        }
        return new BitmapDrawable(res, out);
    }
}
