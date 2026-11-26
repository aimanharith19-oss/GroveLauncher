/*
 * Copyright 2026 The Canopy Launcher authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.canopy.launcher;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

/** Small UI toolkit so the whole app can be built without XML layouts or support libraries. */
public final class Ui {
    private Ui() {}

    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    public static boolean night(Context c) {
        String t = Prefs.theme(c);
        if ("dark".equals(t)) return true;
        if ("light".equals(t)) return false;
        if ("time".equals(t)) {
            java.util.Calendar cal = java.util.Calendar.getInstance();
            int now = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE);
            int from = Prefs.sp(c).getInt("theme_from", 20 * 60), to = Prefs.sp(c).getInt("theme_to", 6 * 60);
            return from <= to ? now >= from && now < to : now >= from || now < to;
        }
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    public static final class Palette {
        public int bg, surface, surfaceAlt, text, subtext, divider, accent, onAccent;
        public boolean night;
    }

    private static Palette cached;
    private static long cachedAt;
    private static int cachedUiMode = -1;

    /** Forget the cached palette (called whenever a setting changes). */
    public static void clearPaletteCache() { cached = null; drawerPal = null; }

    /** The colours: built once and reused (it is asked for on every draw of some views); rebuilt when settings or dark mode change. */
    public static Palette palette(Context c) {
        int ui = c.getResources().getConfiguration().uiMode;
        long now = android.os.SystemClock.uptimeMillis();
        Palette k = cached;
        int sys = systemTone(c); // changes when the wallpaper (Material You) colours change
        if (k != null && ui == cachedUiMode && sys == cachedSys && now - cachedAt < 60000L) return k;
        k = buildPalette(c);
        cached = k;
        cachedUiMode = ui;
        cachedSys = sys;
        cachedAt = now;
        return k;
    }

    private static int cachedSys;

    // ------------------------------------------------------------------ app drawer background (Settings > App drawer > Background)
    private static Palette drawerPal;
    private static int drawerBgCached;

    /** What the drawer's background follows: "you" (Material You), or the feed's choice ("dark", "white", "color"). */
    static String drawerBgMode(Context c) {
        String m = Prefs.sp(c).getString("drawer_bg", "you");
        if ("feed".equals(m)) {
            String f = Prefs.feedBg(c);
            m = "you".equals(f) ? "you" : f;
        }
        return m;
    }

    /** The drawer's background colour (before its opacity is applied). */
    public static int drawerBg(Context c) {
        String m = drawerBgMode(c);
        if ("dark".equals(m)) return 0xFF0B0B0C;
        if ("white".equals(m)) return 0xFFFFFFFF;
        if ("color".equals(m)) return 0xFF000000 | Prefs.feedColor(c);
        return palette(c).bg;
    }

    /** Text and card colours for the drawer, readable on its background (a dark drawer gets light text even in light mode). */
    public static Palette drawerPalette(Context c) {
        int bg = drawerBg(c);
        Palette p = drawerPal;
        if (p != null && drawerBgCached == bg) return p;
        String m = drawerBgMode(c);
        boolean night;
        if ("you".equals(m)) night = night(c);
        else if (Prefs.drawerOpacity(c) < 45 && !"white".equals(m)) night = true; // mostly see-through: the dimmed, blurred wallpaper decides
        else night = !isLight(bg);
        p = night == night(c) ? palette(c) : buildPalette(c, night);
        drawerPal = p;
        drawerBgCached = bg;
        return p;
    }
    /** One of the system's wallpaper-derived colours: when it differs, the cached palette is stale. */
    public static int systemTone(Context c) {
        if (android.os.Build.VERSION.SDK_INT < 31) return 0;
        try { return c.getApplicationContext().getColor(android.R.color.system_accent2_50) ^ c.getApplicationContext().getColor(android.R.color.system_accent1_500); } catch (Throwable t) { return 0; }
    }

    private static Palette buildPalette(Context c) { return buildPalette(c, night(c)); }

    private static Palette buildPalette(Context c, boolean forceNight) {
        Palette p = new Palette();
        p.night = forceNight;
        if (p.night) {
            p.bg = 0xFF0F1412; p.surface = 0xFF19201C; p.surfaceAlt = 0xFF243029;
            p.text = 0xFFE8EFEA; p.subtext = 0xFF9DAEA4; p.divider = 0x22FFFFFF;
        } else {
            p.bg = 0xFFF4F7F5; p.surface = 0xFFFFFFFF; p.surfaceAlt = 0xFFE6EDE8;
            p.text = 0xFF15201A; p.subtext = 0xFF56685D; p.divider = 0x1F000000;
        }
        if (Prefs.dynamicColor(c)) {
            try {
                if (p.night) {
                    p.bg = c.getColor(android.R.color.system_accent2_900);
                    p.surface = c.getColor(android.R.color.system_accent2_800);
                    p.surfaceAlt = c.getColor(android.R.color.system_neutral2_700);
                    p.text = c.getColor(android.R.color.system_neutral1_50);
                    p.subtext = c.getColor(android.R.color.system_neutral2_200);
                } else {
                    p.bg = c.getColor(android.R.color.system_accent2_50);
                    p.surface = c.getColor(android.R.color.system_accent2_10);
                    p.surfaceAlt = c.getColor(android.R.color.system_accent2_100);
                    p.text = c.getColor(android.R.color.system_neutral1_900);
                    p.subtext = c.getColor(android.R.color.system_neutral2_700);
                }
            } catch (Exception ignored) { }
        }
        // Strong, readable text: near-black on light, white on dark.
        if (p.night) { p.text = 0xFFFFFFFF; p.subtext = 0xFFD2D6D3; }
        else { p.text = 0xFF0B0C0C; p.subtext = 0xFF3B403D; }
        p.accent = Prefs.accent(c);
        p.onAccent = isLight(p.accent) ? 0xFF101010 : 0xFFFFFFFF;
        return p;
    }

    /** Tablet or unfolded foldable (Material "medium / expanded": the shorter side is 600 dp or more). */
    public static boolean large(Context c) { return c.getResources().getConfiguration().smallestScreenWidthDp >= 600; }
    /** The window is wide right now (tablet, unfolded foldable, or any landscape that is 600 dp+ wide). */
    public static boolean wide(Context c) { return c.getResources().getConfiguration().screenWidthDp >= 600; }
    /** Width in dp of the current window. */
    public static int widthDp(Context c) { return c.getResources().getConfiguration().screenWidthDp; }
    /** Side space that keeps a bar / list a comfortable reading width on big screens (0 on phones). */
    public static int sideFor(Context c, int maxDp) {
        int w = widthDp(c);
        return w > maxDp ? dp(c, (w - maxDp) / 2f) : 0;
    }

    private static int smallPhone = -1;
    /** True on phones under 6.5 inches (screen diagonal): the home screen then uses slightly smaller icons and one row fewer by default. */
    public static boolean smallPhone(Context c) {
        if (smallPhone < 0) {
            android.util.DisplayMetrics m = c.getResources().getDisplayMetrics();
            double w = m.widthPixels / (double) m.xdpi, h = m.heightPixels / (double) m.ydpi;
            smallPhone = Math.sqrt(w * w + h * h) < 6.5 && !large(c) ? 1 : 0;
        }
        return smallPhone == 1;
    }

    /** Size factor for home icons: 0.9 on small phones, so the grid and dock don't feel cramped. */
    public static float compact(Context c) { return smallPhone(c) ? 0.9f : 1f; }

    public static boolean isLight(int color) {
        double l = 0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color);
        return l > 170;
    }

    /** Readable text for a background colour: near-black on light, white on dark. */
    public static int textOn(int bg) { return isLight(bg) ? 0xFF111111 : 0xFFFFFFFF; }

    private static Boolean wallLight;

    /** Is the home wallpaper light (so text drawn straight on it should be dark)? Cached; call resetWallpaper() on change. */
    public static boolean wallpaperIsLight(Context c) {
        if (wallLight != null) return wallLight;
        boolean light = false;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 27) {
                android.app.WallpaperColors wc = android.app.WallpaperManager.getInstance(c)
                        .getWallpaperColors(android.app.WallpaperManager.FLAG_SYSTEM);
                if (wc != null) {
                    if (android.os.Build.VERSION.SDK_INT >= 31) {
                        light = (wc.getColorHints() & android.app.WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0;
                    } else {
                        light = isLight(wc.getPrimaryColor().toArgb());
                    }
                }
            }
        } catch (Throwable ignored) { }
        wallLight = light;
        return light;
    }

    public static void resetWallpaper() { wallLight = null; }

    /** Colour for text drawn directly on the wallpaper (home labels, feed header…), following the wallpaper. */
    public static int onWallpaper(Context c) { return wallpaperIsLight(c) ? 0xFF111111 : 0xFFFFFFFF; }

    /** Text colour for the feed's own text (greeting, quote…), matching its background setting. */
    public static int feedFg(Context c) {
        String bg = Prefs.feedBg(c);
        if ("white".equals(bg)) return 0xFF111111;
        if ("color".equals(bg) || "you".equals(bg)) return isLight(Prefs.feedColor(c)) ? 0xFF111111 : 0xFFFFFFFF;
        return onWallpaper(c);
    }

    public static int feedShadow(Context c) {
        return "dark".equals(Prefs.feedBg(c)) ? onWallpaperShadow(c) : 0x00000000;
    }

    /** Matching soft shadow for that text. */
    public static int onWallpaperShadow(Context c) { return wallpaperIsLight(c) ? 0x55FFFFFF : 0x99000000; }

    public static int alpha(int color, int a) { return (color & 0x00FFFFFF) | (a << 24); }

    public static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    /** Pressable background with individual corner radii (top-left, top-right, bottom-right, bottom-left). */
    public static Drawable pressable(Context c, int color, float tl, float tr, float br, float bl) {
        float[] r = {tl, tl, tr, tr, br, br, bl, bl};
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadii(r);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadii(r);
        return new RippleDrawable(ColorStateList.valueOf(0x22888888), bg, mask);
    }

    public static Drawable pressable(Context c, int color, float radiusPx) {
        return new RippleDrawable(ColorStateList.valueOf(0x33888888), round(color, radiusPx), round(0xFFFFFFFF, radiusPx));
    }

    public static TextView text(Context c, CharSequence s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    public static TextView button(Context c, String label, int bg, int fg) {
        TextView t = text(c, label, 15, fg);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        int p = dp(c, 12);
        t.setPadding(p + p / 2, p - 2, p + p / 2, p - 2);
        t.setBackground(pressable(c, bg, dp(c, 22)));
        t.setClickable(true);
        return t;
    }

    public static ImageView iconButton(Context c, int res, int tint, View.OnClickListener l) {
        ImageView iv = new ImageView(c);
        iv.setImageResource(res);
        iv.setImageTintList(ColorStateList.valueOf(tint));
        int p = dp(c, 9);
        iv.setPadding(p, p, p, p);
        iv.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33888888), null, null));
        iv.setOnClickListener(l);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        return iv;
    }

    public static Drawable tinted(Context c, int res, int color) {
        Drawable d = c.getDrawable(res).mutate();
        d.setTint(color);
        return d;
    }

    /** Text fields made with the launcher's context would inherit its dark theme (white text) even inside a
     *  light dialog. This gives them the palette's readable colours: near-black on light, white on dark. */
    public static EditText tintField(Context c, EditText e) {
        Palette p = palette(c);
        e.setTextColor(p.text);
        e.setHintTextColor(p.subtext);
        e.setBackgroundTintList(ColorStateList.valueOf(p.accent));
        e.setHighlightColor(alpha(p.accent, 0x55));
        return e;
    }

    public static void styleField(Context c, EditText e, Palette p) {
        e.setTextColor(p.text);
        e.setHintTextColor(p.subtext);
        e.setBackground(round(p.surfaceAlt, dp(c, 24)));
        int h = dp(c, 16);
        e.setPadding(h, dp(c, 10), h, dp(c, 10));
        e.setSingleLine(true);
    }

    public static void roundCorners(View v, final float radius) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        v.setClipToOutline(true);
    }

    public static AlertDialog.Builder dialog(Context c) {
        return new AlertDialog.Builder(c, night(c)
                ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert);
    }

    public static void toast(Context c, CharSequence s) {
        Toast.makeText(c.getApplicationContext(), s, Toast.LENGTH_SHORT).show();
    }

    /** Edge-to-edge, transparent system bars (home screen). */
    @SuppressWarnings("deprecation")
    /** Targeting Android 16+ turns Activity.onBackPressed() off (back goes to the system, which sent the launcher away and left its web tabs blank): route back to it again. */
    public static void keepBack(final android.app.Activity a) {
        if (android.os.Build.VERSION.SDK_INT < 33) return;
        a.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            new android.window.OnBackInvokedCallback() { @Override public void onBackInvoked() { a.onBackPressed(); } });
    }

    public static void edgeToEdge(Window w) {
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    /** Solid system bars matching the palette (settings and library pages). */
    @SuppressWarnings("deprecation")
    public static void solidBars(Window w, Palette p) {
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        w.setStatusBarColor(p.bg);
        w.setNavigationBarColor(p.bg);
        int flags = 0;
        if (!p.night) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        w.getDecorView().setSystemUiVisibility(flags);
    }
}
