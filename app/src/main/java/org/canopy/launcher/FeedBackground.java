/*
 * Copyright 2026 The Canopy Launcher authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.canopy.launcher;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.view.View;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;

/** Feed background: dark blur, white blur or a plain colour — picker, plus a blurred wallpaper Canopy makes itself. */
final class FeedBackground {
    private FeedBackground() { }

    static final String[] KEYS = {"dark", "white", "color", "you"};
    static final String[] LABELS = {"Dark blur", "White blur", "Plain colour", "Material You (wallpaper colours)"};

    /** Quick chooser (from the feed). */
    static void choose(final Context c) {
        String cur = Prefs.feedBg(c);
        int sel = 0;
        for (int i = 0; i < KEYS.length; i++) if (KEYS[i].equals(cur)) sel = i;
        Ui.dialog(c).setTitle("Feed background").setSingleChoiceItems(LABELS, sel, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) {
                d.dismiss();
                if ("color".equals(KEYS[w])) pickColor(c, new Runnable() {
                    @Override public void run() { Prefs.sp(c).edit().putString("feed_bg", "color").apply(); }
                });
                else Prefs.sp(c).edit().putString("feed_bg", KEYS[w]).apply();
            }
        }).show();
    }

    static void pickColor(final Context c, final Runnable after) {
        pickColor(c, "Feed background colour", "feed_color", Prefs.feedColor(c), after);
    }

    /** Colour swatches + a #hex box; saves to {@code key}. */
    static void pickColor(final Context c, String title, final String key, int current, final Runnable after) {
        final int[] colors = {0xFF000000, 0xFF101418, 0xFF1E1E2E, 0xFF263238, 0xFF37474F, 0xFF3E2723, 0xFF1B5E20, 0xFF004D40,
                0xFF0D47A1, 0xFF311B92, 0xFF880E4F, 0xFFB71C1C, 0xFFFFFFFF, 0xFFF5F5F5, 0xFFFFF8E1, 0xFFE3F2FD,
                0xFFE8F5E9, 0xFFFCE4EC, 0xFFEDE7F6, 0xFFFFE0B2};
        final EditText hex = new EditText(c);
        Ui.tintField(c, hex);
        hex.setSingleLine(true);
        hex.setHint("#RRGGBB");
        hex.setText(String.format("#%06X", current & 0xFFFFFF));
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(c, 20), Ui.dp(c, 10), Ui.dp(c, 20), 0);
        GridLayout grid = new GridLayout(c);
        grid.setColumnCount(5);
        final AlertDialog[] dlg = new AlertDialog[1];
        int size = Ui.dp(c, 40), m = Ui.dp(c, 5);
        for (final int col : colors) {
            View sw = new View(c);
            android.graphics.drawable.GradientDrawable g = Ui.round(col, size / 2f);
            g.setStroke(Ui.dp(c, 1), 0x33888888);
            sw.setBackground(g);
            sw.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Prefs.sp(c).edit().putInt(key, col).apply();
                    if (dlg[0] != null) dlg[0].dismiss();
                    if (after != null) after.run();
                }
            });
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = size;
            lp.height = size;
            lp.setMargins(m, m, m, m);
            grid.addView(sw, lp);
        }
        box.addView(grid);
        box.addView(hex);
        dlg[0] = Ui.dialog(c).setTitle(title).setView(box)
                .setPositiveButton("Use", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        try {
                            String h = hex.getText().toString().trim();
                            int col = android.graphics.Color.parseColor(h.startsWith("#") ? h : "#" + h);
                            Prefs.sp(c).edit().putInt(key, col | 0xFF000000).apply();
                            if (after != null) after.run();
                        } catch (Exception e) { Ui.toast(c, "Use a colour like #1E1E2E"); }
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    // ------------------------------------------------------------------ blurred wallpaper (for phones without window blur)

    private static Bitmap cached;
    private static int cachedId = Integer.MIN_VALUE;

    /** A soft, blurred copy of your wallpaper, or null if it can't be read (live wallpaper, no permission). */
    static Bitmap blurredWallpaper(Context c) {
        int id = Integer.MIN_VALUE + 1;
        try { id = android.app.WallpaperManager.getInstance(c).getWallpaperId(android.app.WallpaperManager.FLAG_SYSTEM); } catch (Throwable ignored) { }
        if (cached != null && id == cachedId) return cached;
        Bitmap src = null;
        if (src == null) {
            try {
                android.app.WallpaperManager wm = android.app.WallpaperManager.getInstance(c);
                if (wm.getWallpaperInfo() == null) {
                    android.graphics.drawable.Drawable d = wm.getDrawable();
                    if (d != null) {
                        int w = Math.max(1, d.getIntrinsicWidth() / 8), h = Math.max(1, d.getIntrinsicHeight() / 8);
                        src = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                        Canvas cv = new Canvas(src);
                        d.setBounds(0, 0, w, h);
                        d.draw(cv);
                    }
                }
            } catch (Throwable ignored) { }
        }
        if (src == null) return null;
        // shrink a lot, then box-blur a few times: smooth when stretched back to full screen
        int tw = 60, th = Math.max(1, Math.round(60f * src.getHeight() / Math.max(1, src.getWidth())));
        Bitmap small = Bitmap.createScaledBitmap(src, tw, th, true).copy(Bitmap.Config.ARGB_8888, true);
        int[] px = new int[tw * th];
        small.getPixels(px, 0, tw, 0, 0, tw, th);
        for (int pass = 0; pass < 3; pass++) { boxH(px, tw, th, 2); boxV(px, tw, th, 2); }
        small.setPixels(px, 0, tw, 0, 0, tw, th);
        cached = small;
        cachedId = id;
        return small;
    }

    private static void boxH(int[] p, int w, int h, int r) {
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int a = 0, rr = 0, g = 0, b = 0, n = 0;
                for (int k = -r; k <= r; k++) {
                    int xx = Math.max(0, Math.min(w - 1, x + k));
                    int c = p[y * w + xx];
                    rr += (c >> 16) & 255; g += (c >> 8) & 255; b += c & 255; n++;
                }
                row[x] = 0xFF000000 | ((rr / n) << 16) | ((g / n) << 8) | (b / n);
            }
            System.arraycopy(row, 0, p, y * w, w);
        }
    }

    private static void boxV(int[] p, int w, int h, int r) {
        int[] col = new int[h];
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                int rr = 0, g = 0, b = 0, n = 0;
                for (int k = -r; k <= r; k++) {
                    int yy = Math.max(0, Math.min(h - 1, y + k));
                    int c = p[yy * w + x];
                    rr += (c >> 16) & 255; g += (c >> 8) & 255; b += c & 255; n++;
                }
                col[y] = 0xFF000000 | ((rr / n) << 16) | ((g / n) << 8) | (b / n);
            }
            for (int y = 0; y < h; y++) p[y * w + x] = col[y];
        }
    }
}
