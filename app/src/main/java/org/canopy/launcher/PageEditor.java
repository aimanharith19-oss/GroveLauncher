/*
 * Copyright 2026 The Grove Launcher authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.canopy.launcher;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Edit home screens (zoom in with two fingers on the home screen, or choose it for any gesture):
 * every page as a small card — add a page, delete one, pick the main one — and the widgets underneath,
 * to put on the selected page with a tap.
 */
final class PageEditor extends FrameLayout {
    private final LauncherActivity host;
    private final HomeStore store;
    private final Ui.Palette pal;
    private final LinearLayout cards;
    private final LinearLayout widgetRow;
    private int selected;
    private int inTop, inBottom;

    PageEditor(LauncherActivity a, HomeStore s, int start) {
        super(a);
        host = a;
        store = s;
        pal = Ui.palette(a);
        selected = Math.max(0, Math.min(s.pageCount() - 1, start));
        setBackgroundColor(Ui.alpha(pal.bg, 0xF2));
        setClickable(true);

        final LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        addView(col, new LayoutParams(-1, -1));

        LinearLayout top = new LinearLayout(a);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(Ui.dp(a, 20), Ui.dp(a, 8), Ui.dp(a, 12), Ui.dp(a, 8));
        TextView title = Ui.text(a, "Home screens", 22, pal.text);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView done = Ui.text(a, "Done", 15, pal.onAccent);
        done.setBackground(Ui.round(pal.accent, Ui.dp(a, 20)));
        done.setPadding(Ui.dp(a, 20), Ui.dp(a, 8), Ui.dp(a, 20), Ui.dp(a, 8));
        done.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { host.closePageEditor(selected); } });
        top.addView(done);
        col.addView(top);

        HorizontalScrollView hs = new HorizontalScrollView(a);
        hs.setHorizontalScrollBarEnabled(false);
        cards = new LinearLayout(a);
        cards.setGravity(Gravity.CENTER_VERTICAL);
        cards.setPadding(Ui.dp(a, 16), 0, Ui.dp(a, 16), 0);
        hs.addView(cards, new HorizontalScrollView.LayoutParams(-2, -1));
        col.addView(hs, new LinearLayout.LayoutParams(-1, 0, 1));

        TextView wl = Ui.text(a, "Widgets — tap one to add it to the selected screen", 13, pal.subtext);
        wl.setPadding(Ui.dp(a, 20), Ui.dp(a, 4), Ui.dp(a, 20), Ui.dp(a, 6));
        col.addView(wl);
        HorizontalScrollView ws = new HorizontalScrollView(a);
        ws.setHorizontalScrollBarEnabled(false);
        widgetRow = new LinearLayout(a);
        widgetRow.setPadding(Ui.dp(a, 12), 0, Ui.dp(a, 12), 0);
        ws.addView(widgetRow, new HorizontalScrollView.LayoutParams(-2, -2));
        col.addView(ws, new LinearLayout.LayoutParams(-1, Ui.dp(a, 150)));

        setOnApplyWindowInsetsListener(new OnApplyWindowInsetsListener() {
            @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets in) {
                inTop = in.getSystemWindowInsetTop();
                inBottom = in.getSystemWindowInsetBottom();
                col.setPadding(0, inTop, 0, inBottom + Ui.dp(host, 8));
                return in;
            }
        });
        buildCards();
        loadWidgets();
    }

    private Bitmap thumb(int page, int w, int h) {
        CellLayout cl = host.pageLayoutFor(page);
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        if (cl == null || cl.getWidth() == 0) return b;
        Canvas c = new Canvas(b);
        c.drawColor(0x33FFFFFF);
        c.scale(w / (float) cl.getWidth(), h / (float) cl.getHeight());
        try { cl.draw(c); } catch (Throwable ignored) { }
        return b;
    }

    private void buildCards() {
        final Context a = host;
        cards.removeAllViews();
        int sw = getResources().getDisplayMetrics().widthPixels, sh = getResources().getDisplayMetrics().heightPixels;
        final int cw = Ui.dp(a, 150), ch = Math.min(Ui.dp(a, 300), Math.round(cw * (sh / (float) sw)));
        final int main = host.mainPage();
        for (int i = 0; i < store.pageCount(); i++) {
            final int page = i;
            FrameLayout card = new FrameLayout(a);
            ImageView iv = new ImageView(a);
            iv.setImageBitmap(thumb(page, cw, ch));
            iv.setScaleType(ImageView.ScaleType.FIT_XY);
            card.setBackground(Ui.round(Ui.alpha(pal.surface, 0xCC), Ui.dp(a, 18)));
            Ui.roundCorners(iv, Ui.dp(a, 16));
            iv.setClipToOutline(true);
            card.addView(iv, new LayoutParams(cw, ch));
            if (page == selected) {
                android.graphics.drawable.GradientDrawable ring = new android.graphics.drawable.GradientDrawable();
                ring.setCornerRadius(Ui.dp(a, 18));
                ring.setStroke(Ui.dp(a, 3), pal.accent);
                ring.setColor(0);
                card.setForeground(ring);
            }
            ImageView home = new ImageView(a);
            home.setImageDrawable(Ui.tinted(a, R.drawable.ic_home, page == main ? pal.onAccent : pal.text));
            home.setBackground(Ui.round(page == main ? pal.accent : Ui.alpha(pal.bg, 0xCC), Ui.dp(a, 16)));
            home.setPadding(Ui.dp(a, 6), Ui.dp(a, 6), Ui.dp(a, 6), Ui.dp(a, 6));
            home.setContentDescription("Make this the main home screen");
            home.setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) { Prefs.sp(a).edit().putInt("main_page", page).apply(); buildCards(); }
            });
            LayoutParams hlp = new LayoutParams(Ui.dp(a, 32), Ui.dp(a, 32), Gravity.BOTTOM | Gravity.START);
            hlp.setMargins(Ui.dp(a, 8), 0, 0, Ui.dp(a, 8));
            card.addView(home, hlp);
            if (store.pageCount() > 1) {
                ImageView x = new ImageView(a);
                x.setImageDrawable(Ui.tinted(a, R.drawable.ic_close, pal.text));
                x.setBackground(Ui.round(Ui.alpha(pal.bg, 0xCC), Ui.dp(a, 16)));
                x.setPadding(Ui.dp(a, 7), Ui.dp(a, 7), Ui.dp(a, 7), Ui.dp(a, 7));
                x.setContentDescription("Delete this home screen");
                x.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { delete(page); } });
                LayoutParams xlp = new LayoutParams(Ui.dp(a, 32), Ui.dp(a, 32), Gravity.TOP | Gravity.END);
                xlp.setMargins(0, Ui.dp(a, 8), Ui.dp(a, 8), 0);
                card.addView(x, xlp);
            }
            card.setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) { selected = page; buildCards(); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cw, ch);
            lp.rightMargin = Ui.dp(a, 12);
            cards.addView(card, lp);
        }
        FrameLayout plus = new FrameLayout(a);
        plus.setBackground(Ui.round(Ui.alpha(pal.surface, 0x99), Ui.dp(a, 18)));
        ImageView pi = new ImageView(a);
        pi.setImageDrawable(Ui.tinted(a, R.drawable.ic_add, pal.text));
        plus.addView(pi, new LayoutParams(Ui.dp(a, 36), Ui.dp(a, 36), Gravity.CENTER));
        plus.setContentDescription("Add a home screen");
        plus.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { store.addPage(); selected = store.pageCount() - 1; host.rebindPages(); post(new Runnable() { @Override public void run() { buildCards(); } }); }
        });
        cards.addView(plus, new LinearLayout.LayoutParams(Ui.dp(a, 80), ch));
    }

    private void delete(final int page) {
        if (store.pageEmpty(page)) { doDelete(page); return; }
        Ui.dialog(host).setTitle("Delete this home screen?")
                .setMessage("Everything on it is removed from the home screen (the apps stay installed).")
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { doDelete(page); }
                }).setNegativeButton("Cancel", null).show();
    }

    private void doDelete(int page) {
        int main = host.mainPage();
        store.removePage(page);
        if (main >= page && main > 0) Prefs.sp(host).edit().putInt("main_page", main - 1).apply();
        selected = Math.max(0, Math.min(selected, store.pageCount() - 1));
        host.rebindPages();
        post(new Runnable() { @Override public void run() { buildCards(); } });
    }

    private void loadWidgets() {
        final Context a = host;
        final AppWidgetManager awm = AppWidgetManager.getInstance(a);
        final List<AppWidgetProviderInfo> list = new ArrayList<>(awm.getInstalledProviders());
        final android.content.pm.PackageManager pm = a.getPackageManager();
        Collections.sort(list, new Comparator<AppWidgetProviderInfo>() {
            @Override public int compare(AppWidgetProviderInfo x, AppWidgetProviderInfo y) {
                return String.valueOf(x.loadLabel(pm)).compareToIgnoreCase(String.valueOf(y.loadLabel(pm)));
            }
        });
        for (final AppWidgetProviderInfo info : list) {
            LinearLayout tile = new LinearLayout(a);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER_HORIZONTAL);
            tile.setPadding(Ui.dp(a, 8), Ui.dp(a, 8), Ui.dp(a, 8), Ui.dp(a, 8));
            tile.setBackground(Ui.pressable(a, Ui.alpha(pal.surface, 0xCC), Ui.dp(a, 14)));
            final ImageView pv = new ImageView(a);
            pv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            tile.addView(pv, new LinearLayout.LayoutParams(Ui.dp(a, 96), Ui.dp(a, 72)));
            TextView t = Ui.text(a, String.valueOf(info.loadLabel(pm)), 11, pal.text);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setGravity(Gravity.CENTER);
            tile.addView(t, new LinearLayout.LayoutParams(Ui.dp(a, 96), -2));
            tile.setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) { host.addWidgetToPage(info, selected); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = Ui.dp(a, 8);
            widgetRow.addView(tile, lp);
            App.IO.execute(new Runnable() {
                @Override public void run() {
                    Drawable d = null;
                    try { d = info.loadPreviewImage(a, 0); if (d == null) d = info.loadIcon(a, 0); } catch (Throwable ignored) { }
                    final Drawable fd = d;
                    if (fd != null) App.MAIN.post(new Runnable() { @Override public void run() { pv.setImageDrawable(fd); } });
                }
            });
        }
    }
}
