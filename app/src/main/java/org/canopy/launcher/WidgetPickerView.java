/*
 * Copyright 2026 The Canopy Launcher authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.canopy.launcher;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Full-screen widget page in the style of Nova: search at the top, widgets grouped by app,
 * tap an app to expand its widgets with previews and sizes, tap a widget to add it, or pull it out and drop it where you want it.
 */
public class WidgetPickerView extends FrameLayout {
    public interface Callback {
        void onWidgetPicked(AppWidgetProviderInfo info);
        void onPickerClosed();
        /** The user pulled a widget preview out of the page: drag it to where it should go. */
        void onWidgetDrag(AppWidgetProviderInfo info, View card);
    }

    private static final class Group {
        String pkg, name;
        Drawable icon;
        final List<AppWidgetProviderInfo> widgets = new ArrayList<>();
    }

    private final Callback cb;
    private final Ui.Palette pal;
    private final LinearLayout list;
    private final EditText search;
    private final List<Group> groups = new ArrayList<>();
    private final Map<AppWidgetProviderInfo, String> labels = new java.util.HashMap<>();
    private String expanded;
    private final int[] cell;

    public WidgetPickerView(Context c, Callback cb, int[] cellSize, String title) {
        super(c);
        this.cb = cb;
        this.cell = cellSize;
        pal = Ui.palette(c);
        setBackgroundColor(Ui.alpha(pal.bg, 0xF5));
        setClickable(true);

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        addView(col, new LayoutParams(-1, -1));

        LinearLayout bar = new LinearLayout(c);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(c, 4), Ui.dp(c, 4), Ui.dp(c, 16), Ui.dp(c, 4));
        bar.addView(Ui.iconButton(c, R.drawable.ic_back, pal.text, new OnClickListener() {
            @Override public void onClick(View v) { close(); }
        }), new LinearLayout.LayoutParams(Ui.dp(c, 48), Ui.dp(c, 48)));
        TextView t = Ui.text(c, title, 22, pal.text);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        bar.addView(t);
        col.addView(bar);

        search = new EditText(c);

        Ui.tintField(c, search);
        search.setHint("Search widgets");
        Ui.styleField(c, search, pal);
        search.setCompoundDrawablesRelativeWithIntrinsicBounds(Ui.tinted(c, R.drawable.ic_search, pal.subtext), null, null, null);
        search.setCompoundDrawablePadding(Ui.dp(c, 10));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, Ui.dp(c, 48));
        slp.setMargins(Ui.dp(c, 16), Ui.dp(c, 4), Ui.dp(c, 16), Ui.dp(c, 8));
        col.addView(search, slp);

        ScrollView scroll = new ScrollView(c);
        scroll.setClipToPadding(false);
        list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(Ui.dp(c, 10), 0, Ui.dp(c, 10), Ui.dp(c, 32));
        scroll.addView(list);
        col.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        // Like the app drawer: the list runs under the navigation bar instead of stopping above it.
        final ScrollView fs = scroll;
        col.setOnApplyWindowInsetsListener(new OnApplyWindowInsetsListener() {
            @Override public android.view.WindowInsets onApplyWindowInsets(View v, android.view.WindowInsets in) {
                v.setPadding(in.getSystemWindowInsetLeft(), in.getSystemWindowInsetTop(), in.getSystemWindowInsetRight(), 0);
                fs.setPadding(0, 0, 0, in.getSystemWindowInsetBottom());
                return in;
            }
        });
        col.requestApplyInsets();

        load(c);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void afterTextChanged(Editable s) { render(); }
        });
        render();
        Anim.popIn(col);
    }

    private void load(Context c) {
        PackageManager pm = c.getPackageManager();
        Map<String, Group> byPkg = new LinkedHashMap<>();
        for (AppWidgetProviderInfo i : AppWidgetManager.getInstance(c).getInstalledProviders()) {
            String pkg = i.provider.getPackageName();
            Group g = byPkg.get(pkg);
            if (g == null) {
                g = new Group();
                g.pkg = pkg;
                try {
                    g.name = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
                    g.icon = pm.getApplicationIcon(pkg);
                } catch (Exception e) { g.name = pkg; }
                byPkg.put(pkg, g);
            }
            g.widgets.add(i);
            labels.put(i, String.valueOf(i.loadLabel(pm)));
        }
        groups.addAll(byPkg.values());
        final Collator col = Collator.getInstance();
        col.setStrength(Collator.PRIMARY);
        Collections.sort(groups, new Comparator<Group>() {
            @Override public int compare(Group a, Group b) { return col.compare(a.name, b.name); }
        });
    }

    private void render() {
        final Context c = getContext();
        list.removeAllViews();
        String q = search.getText().toString().trim().toLowerCase(Locale.getDefault());
        int shown = 0;
        for (final Group g : groups) {
            List<AppWidgetProviderInfo> match = new ArrayList<>();
            boolean appMatch = q.isEmpty() || g.name.toLowerCase(Locale.getDefault()).contains(q);
            for (AppWidgetProviderInfo i : g.widgets) {
                if (appMatch || labels.get(i).toLowerCase(Locale.getDefault()).contains(q)) match.add(i);
            }
            if (match.isEmpty()) continue;
            shown++;
            boolean open = !q.isEmpty() || g.pkg.equals(expanded);

            LinearLayout card = new LinearLayout(c);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(Ui.round(pal.surface, Ui.dp(c, 18)));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
            clp.topMargin = Ui.dp(c, 8);

            LinearLayout head = new LinearLayout(c);
            head.setGravity(Gravity.CENTER_VERTICAL);
            int p = Ui.dp(c, 14);
            head.setPadding(p, p, p, p);
            head.setBackground(Ui.pressable(c, 0x00000000, Ui.dp(c, 18)));
            ImageView iv = new ImageView(c);
            iv.setImageDrawable(g.icon);
            head.addView(iv, new LinearLayout.LayoutParams(Ui.dp(c, 36), Ui.dp(c, 36)));
            LinearLayout tt = new LinearLayout(c);
            tt.setOrientation(LinearLayout.VERTICAL);
            tt.setPadding(Ui.dp(c, 14), 0, 0, 0);
            TextView name = Ui.text(c, g.name, 16, pal.text);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            tt.addView(name);
            tt.addView(Ui.text(c, match.size() + (match.size() == 1 ? " widget" : " widgets"), 12, pal.subtext));
            head.addView(tt, new LinearLayout.LayoutParams(0, -2, 1));
            ImageView chev = new ImageView(c);
            chev.setImageDrawable(Ui.tinted(c, R.drawable.ic_expand, pal.subtext));
            chev.setRotation(open ? 0 : 180);
            head.addView(chev, new LinearLayout.LayoutParams(Ui.dp(c, 24), Ui.dp(c, 24)));
            head.setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) {
                    expanded = g.pkg.equals(expanded) ? null : g.pkg;
                    render();
                }
            });
            card.addView(head);

            if (open) {
                HorizontalScrollView hs = new HorizontalScrollView(c);
                hs.setHorizontalScrollBarEnabled(false);
                LinearLayout row = new LinearLayout(c);
                row.setPadding(Ui.dp(c, 10), 0, Ui.dp(c, 10), Ui.dp(c, 14));
                int density = getResources().getDisplayMetrics().densityDpi;
                int idx = 0;
                for (final AppWidgetProviderInfo info : match) {
                    LinearLayout w = new LinearLayout(c);
                    w.setOrientation(LinearLayout.VERTICAL);
                    w.setGravity(Gravity.CENTER_HORIZONTAL);
                    w.setPadding(Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 8));
                    w.setBackground(Ui.pressable(c, pal.surfaceAlt, Ui.dp(c, 16)));
                    ImageView prev = new ImageView(c);
                    prev.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    Drawable d = null;
                    try { d = info.loadPreviewImage(c, density); } catch (Exception ignored) { }
                    if (d == null) try { d = info.loadIcon(c, density); } catch (Exception ignored) { }
                    prev.setImageDrawable(d);
                    w.addView(prev, new LinearLayout.LayoutParams(Ui.dp(c, 150), Ui.dp(c, 110)));
                    TextView l = Ui.text(c, labels.get(info), 13, pal.text);
                    l.setMaxLines(2);
                    l.setEllipsize(TextUtils.TruncateAt.END);
                    l.setGravity(Gravity.CENTER);
                    l.setMaxWidth(Ui.dp(c, 150));
                    l.setPadding(0, Ui.dp(c, 6), 0, 0);
                    w.addView(l);
                    int sx = Math.max(1, (int) Math.ceil(info.minWidth / (double) Math.max(1, cell[0])));
                    int sy = Math.max(1, (int) Math.ceil(info.minHeight / (double) Math.max(1, cell[1])));
                    w.addView(Ui.text(c, sx + " × " + sy, 12, pal.subtext));
                    WidgetDrag.attach(w, new Runnable() {
                        @Override public void run() { cb.onWidgetPicked(info); close(); }
                    }, new WidgetDrag.Starter() {
                        @Override public void startDrag(View card) { cb.onWidgetDrag(info, card); }
                    });
                    LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(-2, -2);
                    wlp.rightMargin = Ui.dp(c, 8);
                    row.addView(w, wlp);
                    Anim.stagger(w, idx++);
                }
                hs.addView(row);
                card.addView(hs);
            }
            list.addView(card, clp);
        }
        if (shown == 0) {
            TextView none = Ui.text(c, q.isEmpty() ? "No widgets installed" : "No widgets match “" + q + "”", 14, pal.subtext);
            none.setGravity(Gravity.CENTER);
            none.setPadding(0, Ui.dp(c, 40), 0, 0);
            list.addView(none);
        }
    }

    public void close() {
        if (getParent() == null) return;
        android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
                getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(getWindowToken(), 0);
        animate().alpha(0f).translationY(Ui.dp(getContext(), 40)).setDuration(Anim.dur(getContext(), 180))
                .withEndAction(new Runnable() {
                    @Override public void run() {
                        if (getParent() != null) ((android.view.ViewGroup) getParent()).removeView(WidgetPickerView.this);
                    }
                }).start();
        cb.onPickerClosed();
    }
}
