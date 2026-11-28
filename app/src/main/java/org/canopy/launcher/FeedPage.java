/*
 * Copyright 2026 The Canopy Launcher authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.canopy.launcher;

import android.Manifest;
import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ContentUris;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Environment;
import android.os.Process;
import android.os.StatFs;
import android.provider.CalendarContract;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.view.MotionEvent;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * The feed, left of the home screen, in the style of Microsoft Launcher: a column of cards
 * (agenda, to-do, screen time, frequent apps, notes, device) plus any app widgets you add.
 * Cards can be reordered, hidden or removed ("Edit feed" or long-press a card's title).
 */
public class FeedPage extends LinearLayout {
    static final String[] BUILTIN = {"bookmarks", "music", "calendar", "tasks", "screentime", "apps", "notes", "device", "guard", "notifs", "dns", "media"};
    static final String[] BUILTIN_LABELS = {"Bookmarks", "Your music", "Agenda", "To-do", "Screen time today", "Most used", "Notes", "Device",
            "Grove Guard", "Notification history", "Private DNS", "GroveMedia stats"};
    private static final String[] SIZES = {"Small", "Medium", "Large", "Extra large"};
    private static final int[] SIZE_DP = {110, 180, 280, 400};

    private final LauncherActivity launcher;
    private final Ui.Palette pal;
    /** Card colour: tinted with the wallpaper's colours (Material You) so the feed's cards visibly match it. */
    private final int surf;
    private final LinearLayout cards;
    /** Browser-style tabs: "feed" (the cards) or a web tab id. */
    private final LinearLayout tabBar;
    private View selChip; // the tab (or the icon inside a group) the strip's pill sits on
    private final android.widget.HorizontalScrollView tabScroll;
    private final ScrollView cardsScroll;
    private final LinearLayout webPane;
    private String current = "feed";
    private final SharedPreferences gp, sp;

    public FeedPage(LauncherActivity c) {
        super(c);
        launcher = c;
        pal = Ui.palette(c);
        int sc = pal.surface;
        if (Prefs.dynamicColor(c)) { // Material You: solid cards in the wallpaper's secondary tone (peach, blue… whatever your wallpaper gives)
            try { sc = 0xFF000000 | (c.getColor(pal.night ? android.R.color.system_accent2_800 : android.R.color.system_accent2_50) & 0xFFFFFF); } catch (Throwable ignored) { }
        }
        surf = sc;
        gp = c.getSharedPreferences("glance", Context.MODE_PRIVATE);
        sp = Prefs.sp(c);
        setOrientation(VERTICAL);
        tabScroll = new android.widget.HorizontalScrollView(c);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabBar = new PillStrip(c);
        // one selection ring that glides from tab to tab (drawn over the tabs, so it also works inside group pills)
        android.graphics.drawable.GradientDrawable ring = new android.graphics.drawable.GradientDrawable();
        ring.setColor(Ui.alpha(pal.accent, 0x26));
        ring.setStroke(Ui.dp(c, 2), pal.accent);
        ring.setCornerRadius(Ui.dp(c, 18));
        ((PillStrip) tabBar).setPill(ring, true);
        tabBar.setGravity(Gravity.CENTER_VERTICAL);
        tabBar.setPadding(Ui.dp(c, 12), Ui.dp(c, 6), Ui.dp(c, 12), Ui.dp(c, 6));
        tabScroll.addView(tabBar);
        addView(tabScroll, new LinearLayout.LayoutParams(-1, -2));
        android.widget.FrameLayout content = new android.widget.FrameLayout(c);
        addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        cardsScroll = new ScrollView(c);
        cardsScroll.setVerticalScrollBarEnabled(false);
        cardsScroll.setClipToPadding(false);
        cardsScroll.setOverScrollMode(OVER_SCROLL_NEVER);
        cards = new LinearLayout(c);
        cards.setOrientation(LinearLayout.VERTICAL);
        cards.setPadding(Ui.dp(c, 14), Ui.dp(c, 2), Ui.dp(c, 14), Ui.dp(c, 28));
        cardsScroll.addView(cards);
        content.addView(cardsScroll, new android.widget.FrameLayout.LayoutParams(-1, -1));
        webPane = new LinearLayout(c);
        webPane.setOrientation(VERTICAL);
        webPane.setVisibility(GONE);
        content.addView(webPane, new android.widget.FrameLayout.LayoutParams(-1, -1));
        migrateWebCards();
        dropAppTabs();
        String saved = FeedWeb.prefs(c).getString("current", "feed");
        current = FeedWeb.tabs(c).contains(saved) ? saved : "feed";
        refresh();
    }

    // ------------------------------------------------------------------ order / visibility

    List<String> order() {
        List<String> out = new ArrayList<>();
        String saved = sp.getString("feed_order", null);
        if (saved != null) for (String s : saved.split(",")) if (!s.isEmpty() && !out.contains(s)) out.add(s);
        for (String b : BUILTIN) if (!out.contains(b)) {
            if ("bookmarks".equals(b)) out.add(0, b);
            else if ("music".equals(b) || "notifs".equals(b)) { int i = out.indexOf("bookmarks"); out.add(i + 1, b); } // new card: near the top
            else out.add(b);
        }
        return out;
    }

    void saveOrder(List<String> o) { sp.edit().putString("feed_order", TextUtils.join(",", o)).apply(); }

    /** Old web cards ("b" + a timestamp). Not "bookmarks", which also starts with b. */
    static boolean isWeb(String id) { return id.length() > 1 && id.charAt(0) == 'b' && Character.isDigit(id.charAt(1)); }

    boolean visible(String id) {
        if (id.startsWith("w")) return true;
        boolean def = true;
        return sp.getBoolean("card_" + id, def);
    }

    // ------------------------------------------------------------------ profile header (you + a motivating line)

    static final String PROFILE_KEY = "__feed_profile__";
    private TextView quoteView;

    /** Short motivating lines, written for Canopy. A new one shows each time you swipe to the feed. */
    private static final String[] QUOTES = {
            "Small steps still move you forward.",
            "Do the next right thing. Then the next.",
            "Progress, not perfection.",
            "You've handled hard days before. You'll handle this one.",
            "Start where you are. Use what you have.",
            "One focused hour beats a distracted day.",
            "Be kind to yourself today.",
            "Your future self is cheering you on.",
            "Discipline is remembering what you want most.",
            "Make today a little better than yesterday.",
            "Rest is part of the work.",
            "Hard things become easy by doing them.",
            "Show up. That's half of it.",
            "Choose courage over comfort.",
            "Little by little, a little becomes a lot.",
            "Focus on what you can control.",
            "You are allowed to begin again.",
            "Done is better than perfect.",
            "Breathe. Then begin.",
            "Every expert was once a beginner.",
            "Quiet effort builds loud results.",
            "Be the energy you want to attract.",
            "Your pace is still progress.",
            "Today is a good day to try.",
            "Keep going. You're closer than you think.",
            "Don't count the days; make the days count.",
            "Consistency beats intensity.",
            "Dream big, start small, act now.",
            "Mistakes are proof you are trying.",
            "Stay curious. Stay kind. Keep moving.",
            "The best time to start was yesterday. The next best is now.",
            "You don't have to see the whole road to take a step.",
            "Grow through what you go through.",
            "Be proud of how far you've come.",
            "Make it simple, then make it happen.",
            "Energy flows where focus goes.",
            "A calm mind sees the way.",
            "Win the morning, win the day.",
            "Believe you can, then prove it quietly.",
            "Good things take time \u2014 and effort.",
    };

    private String nextQuote() {
        int i = (gp.getInt("quote_i", -1) + 1 + new java.util.Random().nextInt(3)) % QUOTES.length;
        gp.edit().putInt("quote_i", i).apply();
        return QUOTES[i];
    }

    /** Called each time the feed slides into view: a fresh line. */
    public void onShown() {
        if (quoteView == null) return;
        final String q = nextQuote();
        quoteView.animate().alpha(0f).setDuration(Anim.dur(getContext(), 120)).withEndAction(new Runnable() {
            @Override public void run() {
                quoteView.setText(q);
                quoteView.setTranslationY(Ui.dp(getContext(), 6));
                quoteView.animate().alpha(1f).translationY(0).setDuration(Anim.dur(getContext(), 260)).start();
            }
        }).start();
    }

    private View profileHeader() {
        final Context c = getContext();
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(c, 10), 0, Ui.dp(c, 8));

        // your picture (or your initial), round
        ImageView avatar = new ImageView(c);
        android.graphics.drawable.Drawable pic = IconEditor.icon(c, PROFILE_KEY);
        String name = Prefs.userName(c);
        if (pic != null) avatar.setImageDrawable(pic);
        else avatar.setImageDrawable(initialDrawable(TextUtils.isEmpty(name) ? "+" : name.substring(0, 1).toUpperCase()));
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatar.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) { o.setOval(0, 0, v.getWidth(), v.getHeight()); }
        });
        avatar.setClipToOutline(true);
        avatar.setElevation(Ui.dp(c, 3));
        avatar.setContentDescription("Your picture");
        avatar.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                Ui.dialog(c).setTitle("Your picture").setItems(new String[]{"Choose a photo", "Remove photo"}, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        if (w == 0) launcher.pickIconImage(PROFILE_KEY);
                        else { IconEditor.resetIcon(c, PROFILE_KEY); refresh(); }
                    }
                }).show();
            }
        });
        int as = Ui.dp(c, 56);
        row.addView(avatar, new LinearLayout.LayoutParams(as, as));

        // your name (tap to edit)
        LinearLayout who = new LinearLayout(c);
        who.setOrientation(VERTICAL);
        who.setPadding(Ui.dp(c, 12), 0, Ui.dp(c, 10), 0);
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String g = h < 5 ? "Good night" : h < 12 ? "Good morning" : h < 18 ? "Good afternoon" : "Good evening";
        int fg = Ui.feedFg(c), sh = Ui.feedShadow(c);
        TextView hello = Ui.text(c, g, 13, Ui.alpha(fg, 0xDD));
        hello.setShadowLayer(Ui.dp(c, 3), 0, Ui.dp(c, 1), sh);
        who.addView(hello);
        TextView nm = Ui.text(c, TextUtils.isEmpty(name) ? "Tap to add your name" : name, 22, fg);
        nm.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        nm.setShadowLayer(Ui.dp(c, 4), 0, Ui.dp(c, 1), sh);
        nm.setSingleLine(true);
        nm.setEllipsize(TextUtils.TruncateAt.END);
        who.addView(nm);
        who.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { editName(); }
        });
        row.addView(who, new LinearLayout.LayoutParams(0, -2, 1.1f));

        // a motivating line on the right
        quoteView = Ui.text(c, QUOTES[Math.max(0, gp.getInt("quote_i", 0)) % QUOTES.length], 14, Ui.alpha(fg, 0xF2));
        quoteView.setTypeface(Typeface.create("serif", Typeface.ITALIC));
        quoteView.setShadowLayer(Ui.dp(c, 3), 0, Ui.dp(c, 1), sh);
        quoteView.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        quoteView.setMaxLines(4);
        quoteView.setEllipsize(TextUtils.TruncateAt.END);
        quoteView.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { onShown(); } });
        row.addView(quoteView, new LinearLayout.LayoutParams(0, -2, 1f));
        return row;
    }

    private void editName() {
        final Context c = getContext();
        final EditText e = Ui.tintField(c, new EditText(c));
        e.setSingleLine(true);
        e.setHint("Your name");
        e.setText(Prefs.userName(c));
        e.setSelectAllOnFocus(true);
        android.widget.FrameLayout wrap = new android.widget.FrameLayout(c);
        wrap.setPadding(Ui.dp(c, 20), Ui.dp(c, 8), Ui.dp(c, 20), 0);
        wrap.addView(e);
        Ui.dialog(c).setTitle("Your name").setView(wrap).setPositiveButton("Save", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) {
                sp.edit().putString("user_name", e.getText().toString().trim()).apply();
            }
        }).setNegativeButton("Cancel", null).show();
    }

    private android.graphics.drawable.Drawable initialDrawable(final String letter) {
        final int bg = pal.accent, fg = pal.onAccent;
        return new android.graphics.drawable.Drawable() {
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override public void draw(android.graphics.Canvas cv) {
                android.graphics.Rect r = getBounds();
                p.setColor(bg);
                cv.drawRect(r, p);
                p.setColor(fg);
                p.setTextSize(r.height() * 0.45f);
                p.setTextAlign(Paint.Align.CENTER);
                p.setTypeface(Typeface.DEFAULT_BOLD);
                cv.drawText(letter, r.exactCenterX(), r.exactCenterY() - (p.descent() + p.ascent()) / 2f, p);
            }
            @Override public void setAlpha(int a) { }
            @Override public void setColorFilter(android.graphics.ColorFilter f) { }
            @Override public int getOpacity() { return android.graphics.PixelFormat.OPAQUE; }
        };
    }

    private int bottomInset;

    /** The feed reaches the bottom of the screen; its content just keeps clear of the navigation bar. */
    public void setBottomInset(int px) {
        bottomInset = px;
        cardsScroll.setPadding(0, 0, 0, px);
        webPane.setPadding(0, 0, 0, px);
    }

    // ------------------------------------------------------------------ browser tabs

    /** 1.15 put web pages in cards; they become tabs now. */
    private void migrateWebCards() {
        List<String> o = new ArrayList<>(order());
        List<String> tabs = FeedWeb.tabs(getContext());
        boolean changed = false;
        for (String id : new ArrayList<>(o)) {
            if (!isWeb(id)) continue;
            o.remove(id);
            if (!tabs.contains(id)) tabs.add(id);
            changed = true;
        }
        // an older check mistook the Bookmarks card ("bookmarks" starts with b) for a web page and moved it
        // into the browser tabs: take it back out
        if (tabs.remove("bookmarks")) {
            changed = true;
            FeedWeb.prefs(getContext()).edit().remove("url_bookmarks").remove("last_bookmarks").remove("title_bookmarks")
                    .remove("grp_bookmarks").apply();
            if ("bookmarks".equals(FeedWeb.prefs(getContext()).getString("current", null))) {
                FeedWeb.prefs(getContext()).edit().putString("current", "feed").apply();
                current = "feed";
            }
            if (!o.contains("bookmarks")) o.add(0, "bookmarks");
        }
        if (changed) { FeedWeb.saveTabs(getContext(), tabs); saveOrder(o); }
    }

    /** "+": a website tab, an app tab, or a bookmark. */
    public void newWebTab() {
        FeedWeb.addDialog(launcher, new FeedWeb.Callback() {
            @Override public void onPicked(String url, String title) {
                String id = FeedWeb.newTab(getContext(), url, title);
                showTab(id);
            }
        });
    }

    /** App tabs were removed: drop any that were added. */
    private void dropAppTabs() {
        for (String id : FeedWeb.tabs(getContext())) if (FeedWeb.isAppTab(id)) FeedWeb.forget(getContext(), id);
    }

    /** Back on the feed: go back inside the open web page, if it can. */
    public boolean onBack() {
        if ("feed".equals(current)) return false;
        android.webkit.WebView wv = launcher.peekFeedWebView(current);
        if (wv != null && wv.canGoBack()) { wv.goBack(); return true; }
        showTab("feed");
        return true;
    }

    /** The site's icon, kept on disk so it is always there — also while the page reloads, is frozen or has no network. */
    private android.graphics.Bitmap stableIcon(final Context c, String id) {
        String url = FeedWeb.last(c, id);
        final String site = hostOf(url);
        android.webkit.WebView wv = launcher.peekFeedWebView(id);
        android.graphics.Bitmap live = wv != null ? wv.getFavicon() : null;
        if (live != null) SiteIcons.put(c, site, live);
        android.graphics.Bitmap b = SiteIcons.get(c, site);
        if (b == null) {
            b = live;
            SiteIcons.fetch(c, site, new SiteIcons.Done() { @Override public void done() { buildTabs(); } });
        }
        return b;
    }

    public void buildTabs() {
        Context c = getContext();
        tabBar.removeAllViews();
        selChip = null;
        View fc = tabChip("feed", FeedWeb.prefs(c).getString("feed_tab_name", "Feed"), null, R.drawable.ic_feed);
        fc.setTag(R.id.tab_id, "feed");
        tabBar.addView(fc);
        List<String> tabs = FeedWeb.tabs(c);
        java.util.Set<String> shownGroups = new java.util.HashSet<>();
        for (String id : tabs) {
            String g = FeedWeb.groupOf(c, id);
            if (g != null) {
                // Chrome-style group: its tabs shown as icons side by side in one pill
                if (shownGroups.add(g)) { View gc = groupChip(g, FeedWeb.groupMembers(c, g)); gc.setTag(R.id.tab_id, FeedWeb.groupMembers(c, g)); tabBar.addView(gc); }
                continue;
            }
            View tc = FeedWeb.isAppTab(id) ? appChip(id) : tabChip(id, FeedWeb.title(c, id), stableIcon(c, id), 0);
            tc.setTag(R.id.tab_id, id);
            tabBar.addView(tc);
        }
        ImageView plus = Ui.iconButton(c, R.drawable.ic_add, 0xFFFFFFFF, new OnClickListener() {
            @Override public void onClick(View v) { newWebTab(); }
        });
        plus.setContentDescription("New tab");
        plus.setTooltipText("New tab");
        plus.setBackground(Ui.pressable(c, Ui.alpha(surf, 0x66), Ui.dp(c, 18)));
        plus.setImageTintList(ColorStateList.valueOf(pal.text));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(Ui.dp(c, 36), Ui.dp(c, 36));
        plp.leftMargin = Ui.dp(c, 4);
        tabBar.addView(plus, plp);
        ((PillStrip) tabBar).select(selChip);
    }

    private android.graphics.drawable.Drawable tabIcon(String id, boolean on) {
        Context c = getContext();
        if (FeedWeb.isAppTab(id)) {
            AppInfo a = FeedWeb.appOf(c, id);
            return a != null ? a.icon : letterIcon("?", on);
        }
        android.webkit.WebView wv = launcher.peekFeedWebView(id);
        android.graphics.Bitmap fav = wv != null ? wv.getFavicon() : null;
        if (fav != null) return new android.graphics.drawable.BitmapDrawable(getResources(), fav);
        return letterIcon(FeedWeb.title(c, id), on);
    }

    /** A group: icons only, side by side; the open one gets a ring. Tap an icon to open that tab. */
    private View groupChip(final String gid, List<String> members) {
        Context c = getContext();
        boolean has = members.contains(current);
        LinearLayout pill = new LinearLayout(c);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(Ui.dp(c, 6), Ui.dp(c, 4), Ui.dp(c, 6), Ui.dp(c, 4));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(Ui.alpha(surf, has ? 0xC0 : 0x66));
        bg.setCornerRadius(Ui.dp(c, 18));
        bg.setStroke(Ui.dp(c, 1.5f), Ui.alpha(pal.accent, 0x99));
        pill.setBackground(bg);
        for (final String id : members) {
            boolean on = id.equals(current);
            ImageView iv = new ImageView(c);
            iv.setImageDrawable(tabIcon(id, on));
            int p = Ui.dp(c, on ? 4 : 5);
            iv.setPadding(p, p, p, p);
            if (on) selChip = iv;
            iv.setContentDescription(FeedWeb.title(c, id));
            iv.setTooltipText(FeedWeb.title(c, id));
            iv.setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) {
                    if (FeedWeb.isAppTab(id) && !FeedWeb.webMode(getContext(), id)) openAppTab(id);
                    else if (id.equals(current)) { android.webkit.WebView wv = launcher.peekFeedWebView(id); if (wv != null) wv.pageUp(true); }
                    else showTab(id);
                }
            });
            iv.setOnLongClickListener(new OnLongClickListener() {
                @Override public boolean onLongClick(View v) { tabMenu(id); return true; }
            });
            pill.addView(iv, new LinearLayout.LayoutParams(Ui.dp(c, 30), Ui.dp(c, 30)));
        }
        pill.setOnLongClickListener(new OnLongClickListener() {
            @Override public boolean onLongClick(View v) { groupMenu(gid); return true; }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = Ui.dp(c, 6);
        pill.setLayoutParams(lp);
        return pill;
    }

    /** An app tab: the app's icon; tap opens the app (in a window where the phone supports it). */
    private View appChip(final String id) {
        Context c = getContext();
        ImageView iv = new ImageView(c);
        iv.setImageDrawable(tabIcon(id, false));
        int p = Ui.dp(c, 6);
        iv.setPadding(p, p, p, p);
        iv.setBackground(Ui.pressable(c, Ui.alpha(surf, 0x66), Ui.dp(c, 18)));
        AppInfo a = FeedWeb.appOf(c, id);
        iv.setContentDescription(a != null ? a.label : "App");
        iv.setTooltipText(a != null ? a.label : "App");
        boolean on = id.equals(current);
        if (on) selChip = iv;
        iv.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                if (id.equals(current)) { android.webkit.WebView wv = launcher.peekFeedWebView(id); if (wv != null) wv.pageUp(true); }
                else showTab(id);
            }
        });
        iv.setOnLongClickListener(new OnLongClickListener() { @Override public boolean onLongClick(View v) { tabMenu(id); return true; } });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(c, 36), Ui.dp(c, 36));
        lp.rightMargin = Ui.dp(c, 6);
        iv.setLayoutParams(lp);
        return iv;
    }

    private void openAppTab(String id) {
        AppInfo a = FeedWeb.appOf(getContext(), id);
        if (a == null) { Ui.toast(getContext(), "That app isn't installed any more"); return; }
        launcher.launchInWindow(a);
    }

    private void groupMenu(final String gid) {
        final Context c = getContext();
        String name = FeedWeb.groupName(c, gid);
        Ui.dialog(c).setTitle(name).setItems(new String[]{"Name group", "Ungroup", "Close all tabs in group"}, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) {
                if (w == 0) {
                    final EditText e = Ui.tintField(c, new EditText(c));
                    e.setSingleLine(true);
                    e.setText(FeedWeb.groupName(c, gid));
                    e.setSelectAllOnFocus(true);
                    android.widget.FrameLayout wrap = new android.widget.FrameLayout(c);
                    wrap.setPadding(Ui.dp(c, 20), Ui.dp(c, 8), Ui.dp(c, 20), 0);
                    wrap.addView(e);
                    Ui.dialog(c).setTitle("Group name").setView(wrap).setPositiveButton("Save", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d2, int w2) { FeedWeb.prefs(c).edit().putString("gname_" + gid, e.getText().toString().trim()).apply(); }
                    }).setNegativeButton("Cancel", null).show();
                } else if (w == 1) {
                    for (String id : FeedWeb.groupMembers(c, gid)) FeedWeb.setGroup(c, id, null);
                    buildTabs();
                } else {
                    for (String id : FeedWeb.groupMembers(c, gid)) closeTab(id);
                }
            }
        }).show();
    }

    /** Close a tab (website or app) and show a neighbour if it was open. */
    void closeTab(String id) {
        Context c = getContext();
        List<String> before = FeedWeb.tabs(c);
        int i = before.indexOf(id);
        launcher.deleteFeedWebView(id);
        FeedWeb.forget(c, id);
        if (id.equals(current)) {
            List<String> left = new ArrayList<>();
            for (String t : FeedWeb.tabs(c)) if (!FeedWeb.isAppTab(t)) left.add(t);
            showTab(left.isEmpty() ? "feed" : left.get(Math.max(0, Math.min(left.size() - 1, i - 1))));
        } else buildTabs();
    }

    private View tabChip(final String id, String label, android.graphics.Bitmap favicon, int iconRes) {
        Context c = getContext();
        boolean on = id.equals(current);
        LinearLayout chip = new LinearLayout(c);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        final boolean iconOnly = FeedWeb.prefs(c).getBoolean("web_icon_only", false) && !"feed".equals(id);
        boolean closable = !"feed".equals(id) && !iconOnly; // icon-only tabs close from the long-press menu
        chip.setPadding(Ui.dp(c, iconOnly ? 10 : 12), Ui.dp(c, 7), Ui.dp(c, iconOnly ? 10 : closable ? 6 : 14), Ui.dp(c, 7));
        chip.setBackground(Ui.pressable(c, Ui.alpha(surf, on ? 0xC0 : 0x66), Ui.dp(c, 18)));
        if (on) selChip = chip;
        ImageView iv = new ImageView(c);
        if (favicon != null) iv.setImageBitmap(favicon);
        else if (iconRes != 0) iv.setImageDrawable(Ui.tinted(c, iconRes, on ? pal.accent : pal.text));
        else iv.setImageDrawable(letterIcon(label, on));
        chip.addView(iv, new LinearLayout.LayoutParams(Ui.dp(c, 18), Ui.dp(c, 18)));
        TextView t = Ui.text(c, label, 14, pal.text);
        t.setTypeface(Typeface.create("sans-serif-medium", on ? Typeface.BOLD : Typeface.NORMAL));
        t.setSingleLine(true);
        t.setMaxWidth(Ui.dp(c, 120));
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(Ui.dp(c, 8), 0, 0, 0);
        if (!iconOnly) chip.addView(t);
        else chip.setContentDescription(label);
        if (closable) {
            // ✕ to close the tab, like a browser
            ImageView x = new ImageView(c);
            x.setImageDrawable(Ui.tinted(c, R.drawable.ic_close, pal.subtext));
            int xp = Ui.dp(c, 3);
            x.setPadding(xp, xp, xp, xp);
            x.setBackground(Ui.pressable(c, 0x00000000, Ui.dp(c, 12)));
            x.setContentDescription("Close tab");
            x.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { closeTab(id); } });
            LinearLayout.LayoutParams xlp = new LinearLayout.LayoutParams(Ui.dp(c, 22), Ui.dp(c, 22));
            xlp.leftMargin = Ui.dp(c, 4);
            chip.addView(x, xlp);
        }
        chip.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                if (id.equals(current) && !"feed".equals(id)) {
                    // tapping the open tab again scrolls/returns its page to the top, like a browser
                    android.webkit.WebView wv = launcher.peekFeedWebView(id);
                    if (wv != null) wv.pageUp(true);
                } else showTab(id);
            }
        });
        // hold a tab to rename it (website tabs also get their other options)
        chip.setOnLongClickListener(new OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                if ("feed".equals(id)) renameFeedTab(); else tabMenu(id);
                return true;
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = Ui.dp(c, 6);
        chip.setLayoutParams(lp);
        return chip;
    }

    private android.graphics.drawable.Drawable letterIcon(String label, boolean on) {
        final String l = label == null || label.isEmpty() ? "?" : label.substring(0, 1).toUpperCase();
        final int bg = on ? pal.accent : Ui.alpha(pal.text, 0x55), fg = on ? pal.onAccent : Ui.textOn(Ui.alpha(pal.text, 0xFF));
        return new android.graphics.drawable.Drawable() {
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override public void draw(android.graphics.Canvas cv) {
                android.graphics.Rect r = getBounds();
                p.setColor(bg);
                cv.drawCircle(r.exactCenterX(), r.exactCenterY(), r.width() / 2f, p);
                p.setColor(fg);
                p.setTextSize(r.height() * 0.6f);
                p.setTextAlign(Paint.Align.CENTER);
                p.setTypeface(Typeface.DEFAULT_BOLD);
                cv.drawText(l, r.exactCenterX(), r.exactCenterY() - (p.descent() + p.ascent()) / 2f, p);
            }
            @Override public void setAlpha(int a) { }
            @Override public void setColorFilter(android.graphics.ColorFilter f) { }
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        };
    }

    private View chipFor(String id) {
        for (int i = 0; i < tabBar.getChildCount(); i++) {
            View v = tabBar.getChildAt(i);
            Object t = v.getTag(R.id.tab_id);
            if (id.equals(t) || (t instanceof List && ((List<?>) t).contains(id))) return v;
        }
        return null;
    }

    private int tabOrder(String id) { return "feed".equals(id) ? -1 : FeedWeb.tabs(getContext()).indexOf(id); }

    /** Switch tab: the feed cards, or one website filling the page. */
    public void showTab(String id) {
        final String before = current;
        Context c = getContext();
        // app tabs open the real app (in a floating window where the phone allows it) — unless you chose
        // "Open its website in the feed" for that tab
        if (FeedWeb.isAppTab(id) && !FeedWeb.webMode(c, id)) { openAppTab(id); return; }
        if (FeedWeb.isAppTab(id)) FeedWeb.ensureAppWeb(c, id);
        if (!"feed".equals(id) && !FeedWeb.tabs(c).contains(id)) id = "feed";
        current = id;
        FeedWeb.prefs(c).edit().putString("current", id).apply();
        buildTabs();
        // the strip's ring glides to the new tab; the page slides in from that side
        final String now = id;
        int dir = before == null || now.equals(before) ? 0 : (tabOrder(now) > tabOrder(before) ? 1 : -1);
        View in;
        if ("feed".equals(id)) {
            webPane.removeAllViews();
            webPane.setVisibility(GONE);
            cardsScroll.setVisibility(VISIBLE);
            in = cardsScroll;
        } else {
            buildWebPane(id);
            cardsScroll.setVisibility(GONE);
            webPane.setVisibility(VISIBLE);
            in = webPane;
        }
        float w = Math.max(1, getWidth());
        if (in == cardsScroll) {
            in.setTranslationX(dir * w * 0.35f);
            in.setAlpha(0f);
            in.animate().translationX(0).alpha(1f).setDuration(Anim.dur(c, 260)).setInterpolator(Anim.open(c)).start();
        } else {
            // a web page is shown at once: fading / sliding a WebView made it draw blank for seconds on some phones
            in.animate().cancel();
            in.setTranslationX(0);
            in.setAlpha(1f);
        }
        // keep the selected tab in view
        final View sel = tabBar.getChildAt(Math.max(0, "feed".equals(id) ? 0 : FeedWeb.tabs(c).indexOf(id) + 1));
        if (sel != null) post(new Runnable() { @Override public void run() { tabScroll.smoothScrollTo(Math.max(0, sel.getLeft() - Ui.dp(getContext(), 40)), 0); } });
    }

    private void buildWebPane(final String id) {
        final Context c = getContext();
        webPane.removeAllViews();
        if (id.equals(launcher.pipTab())) {
            // the page is in the pop-up player right now
            LinearLayout ph = new LinearLayout(c);
            ph.setOrientation(VERTICAL);
            ph.setGravity(Gravity.CENTER);
            ImageView pi = new ImageView(c);
            pi.setImageDrawable(Ui.tinted(c, R.drawable.ic_pip, Ui.feedFg(c)));
            ph.addView(pi, new LinearLayout.LayoutParams(Ui.dp(c, 48), Ui.dp(c, 48)));
            TextView pt = Ui.text(c, "Playing in the pop-up player", 16, Ui.feedFg(c));
            pt.setPadding(0, Ui.dp(c, 10), 0, Ui.dp(c, 14));
            ph.addView(pt);
            webPane.addView(ph, new LinearLayout.LayoutParams(-1, -1));
            return;
        }
        final android.webkit.WebView wv = launcher.feedWebView(id);
        if (wv.getParent() != null) ((ViewGroup) wv.getParent()).removeView(wv);

        LinearLayout bar = new LinearLayout(c);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(Ui.round(Ui.alpha(surf, 0xF2), Ui.dp(c, 22)));
        bar.setPadding(Ui.dp(c, 4), 0, Ui.dp(c, 4), 0);
        int b = Ui.dp(c, 38);
        bar.addView(FeedWeb.tool(c, R.drawable.ic_back, pal.text, "Back", new OnClickListener() {
            @Override public void onClick(View v) { if (wv.canGoBack()) wv.goBack(); }
        }), new LinearLayout.LayoutParams(b, b));
        bar.addView(FeedWeb.tool(c, R.drawable.ic_refresh, pal.text, "Reload", new OnClickListener() {
            @Override public void onClick(View v) { wv.reload(); }
        }), new LinearLayout.LayoutParams(b, b));
        final TextView address = Ui.text(c, siteName(wv.getUrl() != null ? wv.getUrl() : FeedWeb.last(c, id)), 14, pal.subtext);
        address.setSingleLine(true);
        address.setEllipsize(TextUtils.TruncateAt.END);
        address.setPadding(Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 8));
        address.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                FeedWeb.askUrl(c, wv.getUrl(), new FeedWeb.Callback() {
                    @Override public void onPicked(String url, String t) { wv.loadUrl(url); }
                });
            }
        });
        bar.addView(address, new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(FeedWeb.tool(c, R.drawable.ic_home, pal.text, "Start page", new OnClickListener() {
            @Override public void onClick(View v) { wv.loadUrl(FeedWeb.url(c, id)); }
        }), new LinearLayout.LayoutParams(b, b));
        if (PipActivity.supported(c)) bar.addView(FeedWeb.tool(c, R.drawable.ic_pip, pal.text, "Pop-up player", new OnClickListener() {
            @Override public void onClick(View v) { launcher.openPip(id); }
        }), new LinearLayout.LayoutParams(b, b));
        bar.addView(FeedWeb.tool(c, R.drawable.ic_shortcut, pal.text, "Open in browser", new OnClickListener() {
            @Override public void onClick(View v) {
                String u = wv.getUrl() != null ? wv.getUrl() : FeedWeb.url(c, id);
                launcher.safeStart(new Intent(Intent.ACTION_VIEW, Uri.parse(u)));
            }
        }), new LinearLayout.LayoutParams(b, b));
        bar.addView(FeedWeb.tool(c, R.drawable.ic_more, pal.text, "Tab options", new OnClickListener() {
            @Override public void onClick(View v) { tabMenu(id); }
        }), new LinearLayout.LayoutParams(b, b));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.setMargins(Ui.dp(c, 10), 0, Ui.dp(c, 10), Ui.dp(c, 4));
        webPane.addView(bar, blp);
        wv.setTag(R.drawable.ic_more, address); // the address text follows the page

        android.widget.ProgressBar pb = new android.widget.ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(100);
        pb.setProgressTintList(ColorStateList.valueOf(pal.accent));
        pb.setVisibility(wv.getProgress() >= 100 ? GONE : VISIBLE);
        wv.setTag(pb);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, Ui.dp(c, 3));
        plp.setMargins(Ui.dp(c, 18), 0, Ui.dp(c, 18), 0);
        webPane.addView(pb, plp);

        android.widget.FrameLayout frame = new android.widget.FrameLayout(c);
        frame.setBackground(Ui.round(surf, Ui.dp(c, 18)));
        frame.setClipToOutline(true);
        frame.addView(wv, new android.widget.FrameLayout.LayoutParams(-1, -1));
        attachPullToRefresh(frame, wv);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-1, 0, 1);
        flp.setMargins(Ui.dp(c, 6), 0, Ui.dp(c, 6), Ui.dp(c, 6));
        webPane.addView(frame, flp);
        launcher.showingWeb(wv);
    }

    /** Pull down at the top of a page to reload it: a round arrow follows your finger, then spins while loading. */
    private void attachPullToRefresh(android.widget.FrameLayout frame, final android.webkit.WebView w) {
        if (!(w instanceof FeedWeb.CardWebView)) return;
        final FeedWeb.CardWebView wv = (FeedWeb.CardWebView) w;
        final Context c = getContext();
        final int size = Ui.dp(c, 40), max = Ui.dp(c, 110), trigger = Ui.dp(c, 72), rest = Ui.dp(c, 18);
        final ImageView ind = new ImageView(c);
        ind.setImageDrawable(Ui.tinted(c, R.drawable.ic_refresh, pal.accent));
        int p = Ui.dp(c, 9);
        ind.setPadding(p, p, p, p);
        ind.setBackground(Ui.round(surf, size / 2));
        ind.setElevation(Ui.dp(c, 6));
        ind.setTranslationY(-size - Ui.dp(c, 8));
        ind.setVisibility(INVISIBLE);
        android.widget.FrameLayout.LayoutParams ilp = new android.widget.FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        frame.addView(ind, ilp);
        final android.animation.ObjectAnimator[] spin = new android.animation.ObjectAnimator[1];
        final boolean[] armed = {false}, loading = {false};
        final Runnable hide = new Runnable() {
            @Override public void run() {
                loading[0] = false;
                if (spin[0] != null) { spin[0].cancel(); spin[0] = null; }
                ind.animate().translationY(-size - Ui.dp(c, 8)).alpha(0f).setDuration(220).withEndAction(new Runnable() {
                    @Override public void run() { ind.setVisibility(INVISIBLE); }
                }).start();
            }
        };
        wv.pull = new FeedWeb.CardWebView.Pull() {
            @Override public void onPull(float d) {
                if (loading[0]) return;
                float y = Math.min(max, Math.max(0, d) * 0.5f); // a little resistance
                ind.animate().cancel();
                ind.setVisibility(VISIBLE);
                ind.setTranslationY(-size + y);
                ind.setAlpha(Math.min(1f, y / trigger));
                ind.setRotation(y * 2.2f);
                ind.setScaleX(y >= trigger ? 1.08f : 1f);
                ind.setScaleY(y >= trigger ? 1.08f : 1f);
                boolean now = y >= trigger;
                if (now && !armed[0] && Anim.haptics(c)) ind.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                armed[0] = now;
            }
            @Override public void onRelease() {
                if (loading[0]) return;
                if (!armed[0]) { hide.run(); return; }
                armed[0] = false;
                loading[0] = true;
                ind.setScaleX(1f);
                ind.setScaleY(1f);
                ind.animate().translationY(rest).alpha(1f).setDuration(180).start();
                spin[0] = android.animation.ObjectAnimator.ofFloat(ind, View.ROTATION, ind.getRotation(), ind.getRotation() + 360f);
                spin[0].setDuration(700);
                spin[0].setRepeatCount(android.animation.ValueAnimator.INFINITE);
                spin[0].setInterpolator(new android.view.animation.LinearInterpolator());
                spin[0].start();
                wv.reload();
                ind.removeCallbacks(hide);
                ind.postDelayed(hide, 12000); // never spin forever
            }
            @Override public void onLoaded() {
                if (!loading[0]) return;
                ind.removeCallbacks(hide);
                ind.postDelayed(hide, 250);
            }
        };
    }

    private static final java.util.Map<String, String> SITE_NAMES = new java.util.HashMap<>();
    static {
        String[] k = {"youtube", "YouTube", "github", "GitHub", "linkedin", "LinkedIn", "tiktok", "TikTok", "whatsapp", "WhatsApp",
                "chatgpt", "ChatGPT", "openai", "OpenAI", "paypal", "PayPal", "soundcloud", "SoundCloud", "duckduckgo", "DuckDuckGo",
                "stackoverflow", "Stack Overflow", "imdb", "IMDb", "bbc", "BBC", "cnn", "CNN", "nytimes", "NY Times", "x", "X",
                "netflix", "Netflix", "wikipedia", "Wikipedia", "threads", "Threads", "shopee", "Shopee",
                "lazada", "Lazada", "facebook", "Facebook", "instagram", "Instagram", "reddit", "Reddit", "twitch", "Twitch",
                "claude", "Claude", "medium", "Medium", "quora", "Quora", "pinterest", "Pinterest", "discord", "Discord"};
        for (int i = 0; i < k.length; i += 2) SITE_NAMES.put(k[i], k[i + 1]);
    }

    /** "https://www.reddit.com/r/android" → "Reddit": the website's name instead of its address. */
    static String siteName(String urlOrHost) {
        if (urlOrHost == null || urlOrHost.isEmpty()) return "Web";
        String h = urlOrHost.contains("://") ? hostOf(urlOrHost) : urlOrHost.replaceFirst("/.*$", "").replaceFirst("^(www|m|mobile)\\.", "");
        if (h == null || h.isEmpty()) return "Web";
        if (h.matches("[0-9.:]+")) return h; // an IP address has no name
        String[] p = h.toLowerCase(java.util.Locale.ROOT).split("\\.");
        if (p.length == 0) return "Web";
        // the main part: example.co.uk → example, news.google.com → google (with "News" kept for sub-sites people know)
        int n = p.length;
        boolean cc = n >= 3 && p[n - 1].length() == 2 && p[n - 2].length() <= 3;
        String main = n == 1 ? p[0] : cc ? p[n - 3] : p[n - 2];
        String sub = n > (cc ? 3 : 2) ? p[0] : null;
        String name = SITE_NAMES.get(main);
        if (name == null) name = main.isEmpty() ? h : Character.toUpperCase(main.charAt(0)) + main.substring(1);
        if (sub != null && ("news".equals(sub) || "music".equals(sub) || "maps".equals(sub) || "mail".equals(sub) || "drive".equals(sub)
                || "docs".equals(sub) || "photos".equals(sub) || "web".equals(sub) || "calendar".equals(sub))) {
            name = name + " " + Character.toUpperCase(sub.charAt(0)) + sub.substring(1);
        }
        return name;
    }

    /** True for text that is an address rather than a name (e.g. "reddit.com" or "https://…"). */
    static boolean looksLikeAddress(String s) {
        if (s == null) return true;
        String t = s.trim();
        return t.isEmpty() || t.contains("://") || (!t.contains(" ") && t.matches("[A-Za-z0-9.-]+\\.[A-Za-z]{2,}(/.*)?"));
    }

    static String hostOf(String u) {
        try { return Uri.parse(u).getHost().replaceFirst("^(www|m|mobile)\\.", ""); } catch (Exception e) { return u == null ? "" : u; }
    }

    private void renameFeedTab() {
        final Context c = getContext();
        final EditText e = Ui.tintField(c, new EditText(c));
        e.setSingleLine(true);
        e.setText(FeedWeb.prefs(c).getString("feed_tab_name", "Feed"));
        e.setSelectAllOnFocus(true);
        android.widget.FrameLayout wrap = new android.widget.FrameLayout(c);
        wrap.setPadding(Ui.dp(c, 20), Ui.dp(c, 8), Ui.dp(c, 20), 0);
        wrap.addView(e);
        Ui.dialog(c).setTitle("Tab name").setView(wrap).setPositiveButton("Save", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) {
                String n = e.getText().toString().trim();
                FeedWeb.prefs(c).edit().putString("feed_tab_name", n.isEmpty() ? "Feed" : n).apply();
                buildTabs();
            }
        }).setNegativeButton("Cancel", null).show();
    }

    /** The feed browser's history: tap to open it in this tab, long-press to remove, and a Clear button. */
    private void showHistory(final String id) {
        final Context c = getContext();
        final List<String[]> list = WebHistory.recent(c, 150);
        if (list.isEmpty()) { Ui.toast(c, "No history yet"); return; }
        String[] names = new String[list.size()];
        for (int i = 0; i < names.length; i++) {
            String[] h = list.get(i);
            names[i] = (h[1].isEmpty() ? hostOf(h[0]) : h[1]) + "\n" + hostOf(h[0]) + " \u2022 "
                    + android.text.format.DateUtils.getRelativeTimeSpanString(Long.parseLong(h[2]));
        }
        Ui.dialog(c).setTitle("History").setItems(names, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) { launcher.feedWebView(id).loadUrl(list.get(w)[0]); }
        }).setNeutralButton("Clear all", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) { WebHistory.clear(c); Ui.toast(c, "History cleared"); }
        }).setNegativeButton("Close", null).show();
    }

    private void tabMenu(final String id) {
        final Context c = getContext();
        final boolean app = FeedWeb.isAppTab(id) && !FeedWeb.webMode(c, id); // opens the real app
        final List<String> labels = new ArrayList<>();
        final List<Runnable> acts = new ArrayList<>();
        final String group = FeedWeb.groupOf(c, id);
        if (!app) {
            labels.add("Reload");
            acts.add(new Runnable() { @Override public void run() { launcher.feedWebView(id).reload(); } });
            labels.add("Bookmark this page");
            acts.add(new Runnable() {
                @Override public void run() {
                    android.webkit.WebView wv = launcher.feedWebView(id);
                    String u = wv.getUrl() != null ? wv.getUrl() : FeedWeb.url(c, id);
                    String t = wv.getTitle() != null && !wv.getTitle().isEmpty() ? wv.getTitle() : FeedWeb.title(c, id);
                    FeedWeb.addBookmark(c, u, t);
                    Ui.toast(c, "Bookmarked \u2014 it's in the Bookmarks card on the Feed tab");
                    refresh();
                }
            });
            labels.add("History");
            acts.add(new Runnable() { @Override public void run() { showHistory(id); } });
            if (WebHistory.browserInstalled(c)) {
                labels.add("Open in Grove Browser");
                acts.add(new Runnable() {
                    @Override public void run() {
                        android.webkit.WebView wv = launcher.feedWebView(id);
                        String u = wv.getUrl() != null ? wv.getUrl() : FeedWeb.url(c, id);
                        launcher.safeStart(new Intent(Intent.ACTION_VIEW, Uri.parse(u)).setPackage(WebHistory.BROWSER_PKG));
                    }
                });
            }
            labels.add("Change start page");
            acts.add(new Runnable() {
                @Override public void run() {
                    FeedWeb.askUrl(c, FeedWeb.url(c, id), new FeedWeb.Callback() {
                        @Override public void onPicked(String url, String t) {
                            FeedWeb.prefs(c).edit().putString("url_" + id, url).putString("last_" + id, url).apply();
                            launcher.feedWebView(id).loadUrl(url);
                            buildTabs();
                        }
                    });
                }
            });
            labels.add("Rename tab");
            acts.add(new Runnable() {
                @Override public void run() {
                    final EditText e = Ui.tintField(c, new EditText(c));
                    e.setSingleLine(true);
                    e.setText(FeedWeb.title(c, id));
                    e.setSelectAllOnFocus(true);
                    android.widget.FrameLayout wrap = new android.widget.FrameLayout(c);
                    wrap.setPadding(Ui.dp(c, 20), Ui.dp(c, 8), Ui.dp(c, 20), 0);
                    wrap.addView(e);
                    Ui.dialog(c).setTitle("Tab name").setView(wrap).setPositiveButton("Save", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d2, int w2) {
                            FeedWeb.prefs(c).edit().putString("title_" + id, e.getText().toString()).apply();
                            buildTabs();
                        }
                    }).setNegativeButton("Cancel", null).show();
                }
            });
        }
        if (group == null) {
            labels.add("Add to group\u2026");
            acts.add(new Runnable() { @Override public void run() { pickGroup(id); } });
        } else {
            labels.add("Remove from group");
            acts.add(new Runnable() { @Override public void run() { FeedWeb.setGroup(c, id, null); buildTabs(); } });
        }
        labels.add("Move left");
        acts.add(new Runnable() { @Override public void run() { moveTab(id, -1); } });
        labels.add("Move right");
        acts.add(new Runnable() { @Override public void run() { moveTab(id, 1); } });
        if (!app) {
            labels.add("Open in browser");
            acts.add(new Runnable() {
                @Override public void run() {
                    android.webkit.WebView wv = launcher.feedWebView(id);
                    String u = wv.getUrl() != null ? wv.getUrl() : FeedWeb.url(c, id);
                    launcher.safeStart(new Intent(Intent.ACTION_VIEW, Uri.parse(u)));
                }
            });
        }
        if (FeedWeb.isAppTab(id)) {
            final boolean web = FeedWeb.webMode(c, id);
            labels.add(web ? "Open the app instead (tap opens the app)" : "Open its website in the feed instead");
            acts.add(new Runnable() {
                @Override public void run() {
                    FeedWeb.prefs(c).edit().putBoolean("web_" + id, !web).apply();
                    if (web) { if (id.equals(current)) showTab("feed"); else buildTabs(); }
                    else showTab(id);
                }
            });
        }
        if (!app) {
            labels.add("Add to home screen");
            acts.add(new Runnable() {
                @Override public void run() {
                    android.webkit.WebView wv = launcher.feedWebView(id);
                    launcher.addWebToHome(wv.getUrl() != null ? wv.getUrl() : FeedWeb.url(c, id), FeedWeb.title(c, id));
                }
            });
        }
        labels.add("Close tab");
        acts.add(new Runnable() { @Override public void run() { closeTab(id); } });
        android.webkit.WebView peek = app ? null : launcher.peekFeedWebView(id);
        int blocked = peek == null ? 0 : FeedWeb.blockedOn(peek);
        String title = FeedWeb.title(c, id) + (!app && AdBlock.enabled(c) ? "  \u00B7  " + blocked + " ads/trackers blocked" : "");
        Ui.dialog(c).setTitle(title).setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) { acts.get(w).run(); }
        }).show();
    }

    private void moveTab(String id, int dir) {
        Context c = getContext();
        List<String> tabs = FeedWeb.tabs(c);
        int i = tabs.indexOf(id), j = i + dir;
        if (i < 0 || j < 0 || j >= tabs.size()) return;
        Collections.swap(tabs, i, j);
        FeedWeb.saveTabs(c, tabs);
        buildTabs();
    }

    /** Put a tab in a group: an existing one, or a new group. */
    private void pickGroup(final String id) {
        final Context c = getContext();
        final List<String> gids = FeedWeb.groups(c);
        String[] names = new String[gids.size() + 1];
        for (int i = 0; i < gids.size(); i++) names[i] = FeedWeb.groupName(c, gids.get(i));
        names[gids.size()] = "New group";
        Ui.dialog(c).setTitle("Add to group").setItems(names, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) {
                String g = w < gids.size() ? gids.get(w) : "g" + System.currentTimeMillis();
                FeedWeb.setGroup(c, id, g);
                FeedWeb.keepGroupsTogether(c);
                buildTabs();
            }
        }).show();
    }

    String labelOf(String id) {
        if (id.startsWith("w")) {
            AppWidgetProviderInfo info = AppWidgetManager.getInstance(getContext()).getAppWidgetInfo(widgetId(id));
            if (info == null) return "Widget";
            android.content.pm.PackageManager pm = getContext().getPackageManager();
            // Show the app's name (e.g. "Spotify") rather than the widget's own label.
            try { return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(info.provider.getPackageName(), 0))); }
            catch (Exception e) { return String.valueOf(info.loadLabel(pm)); }
        }
        for (int i = 0; i < BUILTIN.length; i++) if (BUILTIN[i].equals(id)) return BUILTIN_LABELS[i];
        return id;
    }

    static int widgetId(String id) {
        try { return Integer.parseInt(id.substring(1)); } catch (Exception e) { return -1; }
    }

    /** Called by the launcher once a widget has been bound (and configured). */
    /** Smartspacer support was removed: take its widget out of the feed once. */
    private void dropSmartspacer() {
        if (sp.getBoolean("smartspacer_dropped", false)) return;
        sp.edit().putBoolean("smartspacer_dropped", true).apply();
        android.appwidget.AppWidgetManager awm = android.appwidget.AppWidgetManager.getInstance(getContext());
        for (String id : order()) {
            if (!id.startsWith("w")) continue;
            try {
                android.appwidget.AppWidgetProviderInfo i = awm.getAppWidgetInfo(widgetId(id));
                if (i != null && "com.kieronquinn.app.smartspacer".equals(i.provider.getPackageName())) removeCard(id);
            } catch (Exception ignored) { }
        }
    }

    public void addWidget(int appWidgetId) {
        List<String> o = order();
        o.add(0, "w" + appWidgetId);
        saveOrder(o);
        refresh();
        cardsScroll.smoothScrollTo(0, 0);
    }

    private void move(String id, int delta) {
        List<String> o = order();
        int i = o.indexOf(id), j = i + delta;
        if (i < 0 || j < 0 || j >= o.size()) return;
        Collections.swap(o, i, j);
        saveOrder(o);
    }

    private void removeCard(String id) {
        if (id.startsWith("w")) {
            launcher.deleteFeedWidget(widgetId(id));
            List<String> o = order();
            o.remove(id);
            saveOrder(o);
            sp.edit().remove("fw_size_" + id).apply();
        } else {
            sp.edit().putBoolean("card_" + id, false).apply();
        }
    }

    /** Cards rise into place when the feed is opened (fluid animation style). */
    public void animateIn() {
        if (!Anim.fluid(getContext())) return;
        for (int i = 0; i < cards.getChildCount(); i++) Anim.stagger(cards.getChildAt(i), i);
    }

    // ------------------------------------------------------------------ build

    public void refresh() {
        Context c = getContext();
        cards.removeAllViews();
        dropSmartspacer();

        cards.addView(profileHeader());
        // big screens: a comfortable reading width, and two columns of cards when there's room (tablet / unfolded / landscape)
        int side = Math.max(Ui.dp(c, 14), Ui.sideFor(c, Ui.widthDp(c) >= 840 ? 1100 : 680));
        cards.setPadding(side, cards.getPaddingTop(), side, cards.getPaddingBottom());
        final boolean twoCols = Ui.widthDp(c) >= 840;
        LinearLayout colA = null, colB = null;
        if (twoCols) {
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(HORIZONTAL);
            colA = new LinearLayout(c); colA.setOrientation(VERTICAL);
            colB = new LinearLayout(c); colB.setOrientation(VERTICAL);
            LinearLayout.LayoutParams la = new LinearLayout.LayoutParams(0, -2, 1), lb = new LinearLayout.LayoutParams(0, -2, 1);
            la.rightMargin = Ui.dp(c, 8);
            lb.leftMargin = Ui.dp(c, 8);
            row.addView(colA, la);
            row.addView(colB, lb);
            cards.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
        int nCard = 0;

        for (String id : order()) {
            if (!visible(id)) continue;
            View body;
            if (isWeb(id)) continue;
            if (id.startsWith("w")) body = widgetBody(id);
            else if ("calendar".equals(id)) body = calendarBody();
            else if ("tasks".equals(id)) body = tasksBody();
            else if ("screentime".equals(id)) body = screenTimeBody();
            else if ("apps".equals(id)) body = appsBody();
            else if ("notes".equals(id)) body = notesBody();
            else if ("bookmarks".equals(id)) body = bookmarksBody();
            else if ("device".equals(id)) body = deviceBody();
            else if ("music".equals(id)) body = musicBody();
            else if ("guard".equals(id)) body = guardBody();
            else if ("media".equals(id)) body = mediaBody();
            else if ("notifs".equals(id)) body = notifsBody();
            else if ("dns".equals(id)) body = dnsBody();
            else continue;
            if (body == null) continue;
            if (twoCols) (nCard++ % 2 == 0 ? colA : colB).addView(card(id, body));
            else cards.addView(card(id, body));
        }

        LinearLayout actions = new LinearLayout(c);
        actions.setGravity(Gravity.CENTER);
        actions.setPadding(0, Ui.dp(c, 16), 0, 0);
        TextView add = Ui.button(c, "+ Add widget", Ui.alpha(surf, 0xEB), pal.text);
        add.setTextSize(14);
        add.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { launcher.showWidgetPicker(true); } });
        actions.addView(add);

        TextView edit = Ui.button(c, "Edit feed", Ui.alpha(surf, 0xEB), pal.text);
        edit.setTextSize(14);
        edit.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { editFeed(); } });
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(-2, -2);
        elp.leftMargin = Ui.dp(c, 10);
        actions.addView(edit, elp);
        // (the feed background is chosen in Settings > Feed > Background)
        cards.addView(actions);

        // tabs + the open web page (re-attached after a rebuild, without reloading it)
        if ("feed".equals(current) || !FeedWeb.tabs(c).contains(current)) {
            current = "feed";
            buildTabs();
            webPane.setVisibility(GONE);
            cardsScroll.setVisibility(VISIBLE);
        } else {
            buildTabs();
            android.webkit.WebView wv = launcher.peekFeedWebView(current);
            if (wv == null || wv.getParent() == null || ((View) wv.getParent()).getParent() != webPane) buildWebPane(current);
            cardsScroll.setVisibility(GONE);
            webPane.setVisibility(VISIBLE);
        }
    }

    private View card(final String id, View body) {
        Context c = getContext();
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.round(surf, Ui.dp(c, 22))); // solid, so the wallpaper behind never greys it
        int p = Ui.dp(c, 14);
        card.setPadding(p, Ui.dp(c, 6), Ui.dp(c, 4), p);
        LinearLayout head = new LinearLayout(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = Ui.text(c, labelOf(id).toUpperCase(), 12, pal.accent);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(0.08f);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        head.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        TextView more = Ui.text(c, "⋮", 18, pal.subtext);
        more.setPadding(Ui.dp(c, 12), Ui.dp(c, 4), Ui.dp(c, 12), Ui.dp(c, 4));
        more.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { cardMenu(id); } });
        head.addView(more);
        head.setOnLongClickListener(new OnLongClickListener() { @Override public boolean onLongClick(View v) { cardMenu(id); return true; } });
        card.addView(head);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.rightMargin = Ui.dp(c, 10);
        card.addView(body, blp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(c, 12);
        card.setLayoutParams(lp);
        return card;
    }

    void cardMenu(final String id) {
        final List<String> labels = new ArrayList<>();
        final List<Runnable> acts = new ArrayList<>();
        labels.add("Move up");
        acts.add(new Runnable() { @Override public void run() { move(id, -1); refresh(); } });
        labels.add("Move down");
        acts.add(new Runnable() { @Override public void run() { move(id, 1); refresh(); } });
        if (id.startsWith("w")) {
            labels.add("Resize");
            acts.add(new Runnable() {
                @Override public void run() {
                    int cur = sp.getInt("fw_size_" + id, -1);
                    String[] items = new String[SIZES.length + 1];
                    items[0] = "Widget's own size";
                    System.arraycopy(SIZES, 0, items, 1, SIZES.length);
                    Ui.dialog(getContext()).setTitle("Widget size").setSingleChoiceItems(items, cur + 1, new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            if (w == 0) sp.edit().remove("fw_size_" + id).apply();
                            else sp.edit().putInt("fw_size_" + id, w - 1).apply();
                            d.dismiss();
                            refresh();
                        }
                    }).show();
                }
            });
            labels.add("Remove widget");
        } else {
            labels.add("Hide card");
        }
        acts.add(new Runnable() { @Override public void run() { removeCard(id); refresh(); } });
        labels.add("Edit feed…");
        acts.add(new Runnable() { @Override public void run() { editFeed(); } });
        Ui.dialog(getContext()).setTitle(labelOf(id)).setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) { acts.get(w).run(); }
        }).show();
    }

    /** Reorder / show / hide / remove every card in one place. */
    void editFeed() {
        final Context c = getContext();
        final List<String> o = order();
        final ListView lv = new ListView(c);
        lv.setDivider(null);
        final BaseAdapter[] ad = new BaseAdapter[1];
        ad[0] = new BaseAdapter() {
            @Override public int getCount() { return o.size(); }
            @Override public Object getItem(int i) { return o.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(final int i, View convert, ViewGroup parent) {
                final String id = o.get(i);
                LinearLayout row = new LinearLayout(c);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(Ui.dp(c, 16), Ui.dp(c, 2), Ui.dp(c, 8), Ui.dp(c, 2));
                final CheckBox cb = new CheckBox(c);
                cb.setButtonTintList(ColorStateList.valueOf(pal.accent));
                cb.setChecked(visible(id));
                cb.setEnabled(!id.startsWith("w"));
                cb.setText(labelOf(id) + (id.startsWith("w") ? "  (widget)" : ""));
                cb.setTextColor(pal.text);
                cb.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                    @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                        sp.edit().putBoolean("card_" + id, on).apply();
                    }
                });
                row.addView(cb, new LinearLayout.LayoutParams(0, -2, 1));
                row.addView(arrow("↑", new OnClickListener() {
                    @Override public void onClick(View v) { if (i > 0) { Collections.swap(o, i, i - 1); saveOrder(o); ad[0].notifyDataSetChanged(); } }
                }));
                row.addView(arrow("↓", new OnClickListener() {
                    @Override public void onClick(View v) { if (i < o.size() - 1) { Collections.swap(o, i, i + 1); saveOrder(o); ad[0].notifyDataSetChanged(); } }
                }));
                if (id.startsWith("w")) row.addView(arrow("✕", new OnClickListener() {
                    @Override public void onClick(View v) { removeCard(id); o.remove(id); ad[0].notifyDataSetChanged(); }
                }));
                return row;
            }
        };
        lv.setAdapter(ad[0]);
        Ui.dialog(c).setTitle("Edit feed").setView(lv)
                .setPositiveButton("Done", null)
                .setNeutralButton("+ Add widget", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { launcher.showWidgetPicker(true); }
                })
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override public void onDismiss(DialogInterface d) { refresh(); }
                }).show();
    }

    private TextView arrow(String s, OnClickListener l) {
        TextView t = Ui.text(getContext(), s, 18, pal.accent);
        t.setGravity(Gravity.CENTER);
        t.setOnClickListener(l);
        t.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(getContext(), 40), Ui.dp(getContext(), 44)));
        return t;
    }

    // ------------------------------------------------------------------ widget cards

    private View widgetBody(final String id) {
        int wid = widgetId(id);
        AppWidgetProviderInfo info = AppWidgetManager.getInstance(getContext()).getAppWidgetInfo(wid);
        if (info == null) {
            List<String> o = order();
            o.remove(id);
            saveOrder(o);
            return null;
        }
        LauncherWidgetView wv = launcher.feedWidgetView(wid, info);
        if (wv == null) return null;
        wv.host = new LauncherWidgetView.Host() {
            @Override public void onWidgetLongPress(LauncherWidgetView v) { cardMenu(id); }
            @Override public void onWidgetDragStart(LauncherWidgetView v) { }
        };
        int size = sp.getInt("fw_size_" + id, -1);
        if (size >= 0 && size < SIZE_DP.length) {
            wv.setLayoutParams(new LinearLayout.LayoutParams(-1, Ui.dp(getContext(), SIZE_DP[size])));
            return wv;
        }
        // Widget's own size: the size it would have on your home screen (its width too, centred in the card)
        int[] nat = launcher.widgetNaturalSize(info);
        int avail = getResources().getDisplayMetrics().widthPixels - Ui.dp(getContext(), 60);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(nat[0] >= avail ? -1 : nat[0], Math.max(Ui.dp(getContext(), 48), nat[1]));
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        wv.setLayoutParams(lp);
        return wv;
    }

    // ------------------------------------------------------------------ built-in cards

    private TextView smallButton(String label, OnClickListener l) {
        TextView b = Ui.button(getContext(), label, pal.accent, pal.onAccent);
        b.setTextSize(13);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = Ui.dp(getContext(), 10);
        b.setLayoutParams(lp);
        return b;
    }

    private View calendarBody() {
        final Context c = getContext();
        LinearLayout body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        if (c.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            body.addView(Ui.text(c, "See your next events here. Grove only reads the calendar on this phone.", 14, pal.subtext));
            body.addView(smallButton("Show my agenda", new OnClickListener() {
                @Override public void onClick(View v) { launcher.requestCalendar(); }
            }));
            return body;
        }
        long now = System.currentTimeMillis();
        Uri.Builder b = CalendarContract.Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(b, now);
        ContentUris.appendId(b, now + 3 * DateUtils.DAY_IN_MILLIS);
        int shown = 0;
        try {
            Cursor cur = c.getContentResolver().query(b.build(), new String[]{CalendarContract.Instances.TITLE,
                    CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_ID,
                    CalendarContract.Instances.DISPLAY_COLOR}, null, null, CalendarContract.Instances.BEGIN + " ASC");
            if (cur != null) try {
                java.text.DateFormat tf = DateFormat.getTimeFormat(c);
                while (cur.moveToNext() && shown < 6) {
                    String title = cur.getString(0);
                    long begin = cur.getLong(1);
                    boolean allDay = cur.getInt(2) != 0;
                    final long eventId = cur.getLong(3);
                    int color = cur.isNull(4) ? pal.accent : cur.getInt(4);
                    LinearLayout row = new LinearLayout(c);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(0, Ui.dp(c, 5), 0, Ui.dp(c, 5));
                    View dot = new View(c);
                    dot.setBackground(Ui.round(color | 0xFF000000, Ui.dp(c, 3)));
                    row.addView(dot, new LinearLayout.LayoutParams(Ui.dp(c, 4), Ui.dp(c, 30)));
                    LinearLayout t = new LinearLayout(c);
                    t.setOrientation(LinearLayout.VERTICAL);
                    t.setPadding(Ui.dp(c, 10), 0, 0, 0);
                    TextView a = Ui.text(c, TextUtils.isEmpty(title) ? "(No title)" : title, 14, pal.text);
                    a.setSingleLine(true);
                    a.setEllipsize(TextUtils.TruncateAt.END);
                    t.addView(a);
                    String when = DateUtils.isToday(begin) ? "Today" : DateUtils.formatDateTime(c, begin,
                            DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_ABBREV_WEEKDAY);
                    t.addView(Ui.text(c, when + (allDay ? "  ·  all day" : "  ·  " + tf.format(new Date(begin))), 12, pal.subtext));
                    row.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
                    row.setOnClickListener(new OnClickListener() {
                        @Override public void onClick(View v) {
                            launcher.safeStart(new Intent(Intent.ACTION_VIEW,
                                    ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)));
                        }
                    });
                    body.addView(row);
                    shown++;
                }
            } finally {
                cur.close();
            }
        } catch (Exception ignored) { }
        if (shown == 0) body.addView(Ui.text(c, "Nothing in the next 3 days.", 14, pal.subtext));
        return body;
    }

    private List<JSONObject> tasks() {
        List<JSONObject> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(gp.getString("tasks", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getJSONObject(i));
        } catch (Exception ignored) { }
        return out;
    }

    private void saveTasks(List<JSONObject> l) {
        JSONArray a = new JSONArray();
        for (JSONObject o : l) a.put(o);
        gp.edit().putString("tasks", a.toString()).apply();
    }

    private final Runnable refreshLater = new Runnable() { @Override public void run() { refresh(); } };

    private View tasksBody() {
        final Context c = getContext();
        final LinearLayout body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        final List<JSONObject> list = tasks();
        boolean anyDone = false;
        for (final JSONObject o : list) {
            final CheckBox cb = new CheckBox(c);
            cb.setText(o.optString("t"));
            cb.setTextColor(o.optBoolean("d") ? pal.subtext : pal.text);
            cb.setButtonTintList(ColorStateList.valueOf(pal.accent));
            cb.setChecked(o.optBoolean("d"));
            if (cb.isChecked()) { cb.setPaintFlags(cb.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG); anyDone = true; }
            cb.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                    try { o.put("d", checked); } catch (Exception ignored) { }
                    saveTasks(list);
                    post(refreshLater);
                }
            });
            body.addView(cb);
        }
        final EditText add = new EditText(c);
        Ui.tintField(c, add);
        add.setHint("+ Add a task");
        add.setTextColor(pal.text);
        add.setHintTextColor(pal.subtext);
        add.setBackground(null);
        add.setSingleLine(true);
        add.setImeOptions(EditorInfo.IME_ACTION_DONE);
        add.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int id, KeyEvent e) {
                String t = add.getText().toString().trim();
                if (!t.isEmpty()) {
                    try { list.add(new JSONObject().put("t", t).put("d", false)); } catch (Exception ignored) { }
                    saveTasks(list);
                    post(refreshLater);
                }
                return true;
            }
        });
        body.addView(add);
        if (anyDone) {
            TextView clear = Ui.text(c, "Clear completed", 13, pal.accent);
            clear.setPadding(0, Ui.dp(c, 4), 0, 0);
            clear.setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) {
                    List<JSONObject> keep = new ArrayList<>();
                    for (JSONObject o : list) if (!o.optBoolean("d")) keep.add(o);
                    saveTasks(keep);
                    refresh();
                }
            });
            body.addView(clear);
        }
        return body;
    }

    private boolean usageAllowed() {
        try {
            AppOpsManager ops = (AppOpsManager) getContext().getSystemService(Context.APP_OPS_SERVICE);
            @SuppressWarnings("deprecation")
            int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getContext().getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    private View screenTimeBody() {
        final Context c = getContext();
        final LinearLayout body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        if (!usageAllowed()) {
            body.addView(Ui.text(c, "See how long you've used your phone today. Needs “Usage access”, which stays on this device.", 14, pal.subtext));
            body.addView(smallButton("Allow usage access", new OnClickListener() {
                @Override public void onClick(View v) { launcher.safeStart(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)); }
            }));
            return body;
        }
        final TextView total = Ui.text(c, "…", 26, pal.text);
        total.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        body.addView(total);
        final LinearLayout top = new LinearLayout(c);
        top.setOrientation(LinearLayout.VERTICAL);
        body.addView(top);
        App.IO.execute(new Runnable() {
            @Override public void run() {
                Calendar cal = Calendar.getInstance();
                cal.set(Calendar.HOUR_OF_DAY, 0);
                cal.set(Calendar.MINUTE, 0);
                cal.set(Calendar.SECOND, 0);
                UsageStatsManager usm = (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
                Map<String, UsageStats> map = usm.queryAndAggregateUsageStats(cal.getTimeInMillis(), System.currentTimeMillis());
                final List<UsageStats> list = new ArrayList<>();
                long sum = 0;
                for (UsageStats u : map.values()) {
                    if (u.getPackageName().equals(c.getPackageName())) continue;
                    if (AppRepository.get(c).findByPackage(u.getPackageName()) == null) continue;
                    if (u.getTotalTimeInForeground() < 60000) continue;
                    sum += u.getTotalTimeInForeground();
                    list.add(u);
                }
                Collections.sort(list, new Comparator<UsageStats>() {
                    @Override public int compare(UsageStats a, UsageStats b) { return Long.compare(b.getTotalTimeInForeground(), a.getTotalTimeInForeground()); }
                });
                final long fs = sum;
                App.MAIN.post(new Runnable() {
                    @Override public void run() {
                        total.setText(duration(fs));
                        for (int i = 0; i < Math.min(3, list.size()); i++) {
                            UsageStats u = list.get(i);
                            AppInfo a = AppRepository.get(c).findByPackage(u.getPackageName());
                            LinearLayout row = new LinearLayout(c);
                            row.setGravity(Gravity.CENTER_VERTICAL);
                            row.setPadding(0, Ui.dp(c, 4), 0, Ui.dp(c, 4));
                            ImageView iv = new ImageView(c);
                            if (a != null) iv.setImageDrawable(a.icon);
                            row.addView(iv, new LinearLayout.LayoutParams(Ui.dp(c, 24), Ui.dp(c, 24)));
                            TextView t = Ui.text(c, (a != null ? a.label : u.getPackageName()), 14, pal.text);
                            t.setPadding(Ui.dp(c, 10), 0, 0, 0);
                            row.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
                            row.addView(Ui.text(c, duration(u.getTotalTimeInForeground()), 13, pal.subtext));
                            top.addView(row);
                        }
                    }
                });
            }
        });
        return body;
    }

    static String duration(long ms) {
        long m = ms / 60000, h = m / 60;
        return h > 0 ? h + " h " + (m % 60) + " min" : m + " min";
    }

    private View appsBody() {
        Context c = getContext();
        LinearLayout row = new LinearLayout(c);
        List<AppInfo> top = AppRepository.get(c).topUsed(5);
        if (top.isEmpty()) {
            row.addView(Ui.text(c, "Apps you open most will appear here.", 14, pal.subtext));
            return row;
        }
        for (AppInfo a : top) {
            IconView v = new IconView(c, launcher, IconView.STYLE_GLANCE);
            v.bind(a.icon, a.label);
            v.setBadgePackage(a.pkg());
            v.setTag(a);
            row.addView(v, new LinearLayout.LayoutParams(0, v.desiredHeight(), 1));
        }
        for (int i = top.size(); i < 5; i++) row.addView(new View(c), new LinearLayout.LayoutParams(0, 1, 1));
        return row;
    }

    /** Your saved websites: tap one to open it in the feed browser. */
    private View bookmarksBody() {
        final Context c = getContext();
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(VERTICAL);
        final List<String[]> list = FeedWeb.bookmarks(c);
        if (list.isEmpty()) {
            TextView t = Ui.text(c, "Save websites here: tap + below, or in a web tab use \u22EE \u2192 Bookmark this page.", 13, pal.subtext);
            col.addView(t);
        }
        int perRow = 4;
        LinearLayout row = null;
        for (int i = 0; i < list.size(); i++) {
            final int idx = i;
            final String[] b = list.get(i);
            if (i % perRow == 0) {
                row = new LinearLayout(c);
                col.addView(row, new LinearLayout.LayoutParams(-1, -2));
            }
            LinearLayout cell = new LinearLayout(c);
            cell.setOrientation(VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setPadding(Ui.dp(c, 2), Ui.dp(c, 8), Ui.dp(c, 2), Ui.dp(c, 8));
            cell.setBackground(Ui.pressable(c, 0x00000000, Ui.dp(c, 14)));
            ImageView iv = new ImageView(c);
            final String shown = looksLikeAddress(b[1]) ? siteName(b[0]) : b[1]; // the website's name, never its address
            iv.setImageDrawable(letterIcon(shown, true));
            cell.addView(iv, new LinearLayout.LayoutParams(Ui.dp(c, 40), Ui.dp(c, 40)));
            TextView t = Ui.text(c, shown, 12, pal.text);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, Ui.dp(c, 6), 0, 0);
            cell.addView(t, new LinearLayout.LayoutParams(-1, -2));
            cell.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { launcher.openBookmark(b[0]); } });
            cell.setOnLongClickListener(new OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    Ui.dialog(c).setTitle(shown).setItems(new String[]{"Open", "Rename", "Remove", "Add to home screen"}, new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            if (w == 3) launcher.addWebToHome(b[0], shown);
                            else if (w == 0) launcher.openBookmark(b[0]);
                            else if (w == 1) {
                                final EditText e = Ui.tintField(c, new EditText(c));
                                e.setSingleLine(true);
                                e.setText(shown);
                                e.setSelectAllOnFocus(true);
                                android.widget.FrameLayout wrap = new android.widget.FrameLayout(c);
                                wrap.setPadding(Ui.dp(c, 20), Ui.dp(c, 8), Ui.dp(c, 20), 0);
                                wrap.addView(e);
                                Ui.dialog(c).setTitle("Bookmark name").setView(wrap).setPositiveButton("Save", new DialogInterface.OnClickListener() {
                                    @Override public void onClick(DialogInterface d2, int w2) {
                                        List<String[]> l = FeedWeb.bookmarks(c);
                                        if (idx < l.size()) { l.get(idx)[1] = e.getText().toString().trim(); FeedWeb.saveBookmarks(c, l); }
                                        refresh();
                                    }
                                }).setNegativeButton("Cancel", null).show();
                            } else {
                                List<String[]> l = FeedWeb.bookmarks(c);
                                if (idx < l.size()) { l.remove(idx); FeedWeb.saveBookmarks(c, l); }
                                refresh();
                            }
                        }
                    }).show();
                    return true;
                }
            });
            row.addView(cell, new LinearLayout.LayoutParams(0, -2, 1));
        }
        if (row != null) for (int i = row.getChildCount(); i < perRow; i++) row.addView(new View(c), new LinearLayout.LayoutParams(0, 1, 1));
        col.addView(smallButton("+ Add bookmark", new OnClickListener() {
            @Override public void onClick(View v) {
                FeedWeb.askUrl(c, null, new FeedWeb.Callback() {
                    @Override public void onPicked(String url, String title) { FeedWeb.addBookmark(c, url, null); refresh(); }
                });
            }
        }));
        return col;
    }

    private View notesBody() {
        final EditText notes = new EditText(getContext());
        notes.setHint("Jot something down…");
        notes.setTextColor(pal.text);
        notes.setHintTextColor(pal.subtext);
        notes.setBackground(null);
        notes.setMinLines(3);
        notes.setGravity(Gravity.TOP | Gravity.START);
        notes.setText(gp.getString("notes", ""));
        final Runnable save = new Runnable() {
            @Override public void run() { gp.edit().putString("notes", notes.getText().toString()).apply(); }
        };
        notes.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void afterTextChanged(Editable s) { removeCallbacks(save); postDelayed(save, 600); }
        });
        return notes;
    }

    private View deviceBody() {
        Context c = getContext();
        StringBuilder sb = new StringBuilder();
        Intent bat = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (bat != null) {
            int lvl = bat.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = bat.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int plugged = bat.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            if (lvl >= 0) sb.append("Battery ").append(lvl * 100 / Math.max(1, scale)).append('%').append(plugged != 0 ? " · charging" : "");
        }
        try {
            StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
            if (sb.length() > 0) sb.append('\n');
            sb.append("Storage ").append(Formatter.formatShortFileSize(c, fs.getAvailableBytes())).append(" free of ")
                    .append(Formatter.formatShortFileSize(c, fs.getTotalBytes()));
        } catch (Exception ignored) { }
        return Ui.text(c, sb, 14, pal.text);
    }

    // ------------------------------------------------------------------ notification history


    /** Every notification you got, kept even after it's cleared from the shade (see {@link NotifHistory}). */
    private View notifsBody() {
        final Context c = getContext();
        NotifHistory.ensureLoaded(c);
        final LinearLayout col = new LinearLayout(c);
        col.setOrientation(VERTICAL);
        // rebuilt only while the feed is on screen (otherwise when it next comes into view), and only if the list changed
        final NotifHistory.Listener l = new NotifHistory.Listener() { @Override public void onHistoryChanged() { refillNotifs(col); } };
        col.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() { if (Boolean.TRUE.equals(col.getTag(R.id.notif_dirty)) && notifsVisible(col)) refillNotifs(col); }
        });
        col.addOnAttachStateChangeListener(new OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { NotifHistory.addListener(l); refillNotifs(col); }
            @Override public void onViewDetachedFromWindow(View v) { NotifHistory.removeListener(l); }
        });
        fillNotifs(col);
        return col;
    }

    private final android.graphics.Rect visRect = new android.graphics.Rect();
    private boolean notifsVisible(View v) { return v.isShown() && v.getGlobalVisibleRect(visRect); }

    private void refillNotifs(LinearLayout col) {
        if (!notifsVisible(col)) { col.setTag(R.id.notif_dirty, Boolean.TRUE); return; }
        col.setTag(R.id.notif_dirty, null);
        List<NotifHistory.Entry> top = NotifHistory.recent(null, 1);
        String sig = NotifHistory.size() + ":" + (top.isEmpty() ? "" : top.get(0).when + top.get(0).key);
        if (sig.equals(col.getTag(R.id.notif_sig))) return; // nothing new
        col.setTag(R.id.notif_sig, sig);
        fillNotifs(col);
    }

    /** App icons for the list, loaded once per app (on some phones each load costs ~10 ms). */
    private static final java.util.HashMap<String, android.graphics.drawable.Drawable.ConstantState> ICONS = new java.util.HashMap<>();
    private static android.graphics.drawable.Drawable appIcon(Context c, String pkg) {
        android.graphics.drawable.Drawable.ConstantState cs = ICONS.get(pkg);
        if (cs == null) {
            try {
                android.graphics.drawable.Drawable d = c.getPackageManager().getApplicationIcon(pkg);
                // drawn once into a small picture: adaptive icons redraw their vector layers on every frame otherwise
                int px = Ui.dp(c, 32);
                android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888);
                android.graphics.Canvas cv = new android.graphics.Canvas(b);
                d.setBounds(0, 0, px, px);
                d.draw(cv);
                cs = new android.graphics.drawable.BitmapDrawable(c.getResources(), b).getConstantState();
                if (cs == null) return d;
                if (ICONS.size() > 80) ICONS.clear();
                ICONS.put(pkg, cs);
            } catch (Exception e) { return null; }
        }
        return cs.newDrawable(c.getResources());
    }

    /** The music card is rebuilt only while it's on screen and only when the song, play state or history changed. */
    private void refillMusic(LinearLayout col) {
        if (!notifsVisible(col)) { col.setTag(R.id.notif_dirty, Boolean.TRUE); return; }
        col.setTag(R.id.notif_dirty, null);
        android.media.session.MediaController mc = MusicHistory.current();
        android.media.MediaMetadata md = MusicHistory.metaOf(mc);
        android.media.session.PlaybackState ps = MusicHistory.stateOf(mc);
        String sig = (mc == null ? "" : mc.getPackageName()) + "|" + (md == null ? "" : MusicHistory.title(md) + MusicHistory.artist(md))
                + "|" + (ps == null ? -1 : ps.getState()) + "|" + MusicHistory.signature();
        if (sig.equals(col.getTag(R.id.notif_sig))) return;
        col.setTag(R.id.notif_sig, sig);
        fillMusic(col);
    }

    /** Compact: one icon per app (newest first) with how many it sent; tap to read them in full. */
    private void fillNotifs(final LinearLayout col) {
        final Context c = getContext();
        col.removeAllViews();
        if (!notificationAccess()) {
            col.addView(Ui.text(c, "Allow notification access and every notification you get is kept here \u2014 even after you clear it.", 13, pal.subtext));
            LinearLayout r = buttonRow();
            addButton(r, "Allow access", true, new Runnable() {
                @Override public void run() {
                    try { c.startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) { }
                }
            });
            col.addView(r);
            return;
        }
        if (!NotifHistory.enabled(c)) {
            col.addView(Ui.text(c, "Notification history is turned off in Settings \u203A Notification badges.", 13, pal.subtext));
            return;
        }
        List<String> apps = NotifHistory.apps();
        if (apps.isEmpty()) {
            col.addView(Ui.text(c, "Nothing yet \u2014 new notifications will be kept here, even after you clear them.", 13, pal.subtext));
            return;
        }
        // newest first, the app's icon beside each one; five rows show at a time and the list scrolls
        final List<NotifHistory.Entry> list = NotifHistory.recent(null, 30); // the card shows five at a time; tap an icon for all of that app's
        final int rowH = Ui.dp(c, 60);
        ScrollView sv = new ScrollView(c) {
            @Override public boolean onTouchEvent(MotionEvent ev) {
                if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) getParent().requestDisallowInterceptTouchEvent(true);
                return super.onTouchEvent(ev);
            }
            @Override public boolean onInterceptTouchEvent(MotionEvent ev) {
                if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) getParent().requestDisallowInterceptTouchEvent(true);
                return super.onInterceptTouchEvent(ev);
            }
        };
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(OVER_SCROLL_NEVER);
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(VERTICAL);
        sv.addView(box);
        java.text.DateFormat tf = android.text.format.DateFormat.getTimeFormat(c);
        final String today = dayLabel(System.currentTimeMillis());
        for (final NotifHistory.Entry e : list) {
            LinearLayout r = new LinearLayout(c);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(Ui.dp(c, 6), 0, Ui.dp(c, 6), 0);
            r.setBackground(Ui.pressable(c, 0x00000000, Ui.dp(c, 12)));
            ImageView ic = new ImageView(c);
            ic.setImageDrawable(appIcon(c, e.pkg));
            ic.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { showNotifs(e.pkg); } });
            r.addView(ic, new LinearLayout.LayoutParams(Ui.dp(c, 36), Ui.dp(c, 36)));
            LinearLayout tx = new LinearLayout(c);
            tx.setOrientation(VERTICAL);
            tx.setPadding(Ui.dp(c, 10), 0, Ui.dp(c, 6), 0);
            TextView t = Ui.text(c, e.title.isEmpty() ? appLabel(e.pkg) : e.title, 14, pal.text);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            tx.addView(t);
            if (!e.text.isEmpty()) {
                TextView b = Ui.text(c, e.text.replace('\n', ' '), 12, pal.subtext);
                b.setSingleLine(true);
                b.setEllipsize(TextUtils.TruncateAt.END);
                tx.addView(b);
            }
            r.addView(tx, new LinearLayout.LayoutParams(0, -2, 1));
            String dl = dayLabel(e.when);
            r.addView(Ui.text(c, dl.equals(today) ? tf.format(new java.util.Date(e.when)) : dl, 11, pal.subtext));
            r.setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) {
                    if (e.open != null) {
                        try {
                            if (android.os.Build.VERSION.SDK_INT >= 34) {
                                android.app.ActivityOptions o = android.app.ActivityOptions.makeBasic();
                                o.setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                                e.open.send(c, 0, null, null, null, null, o.toBundle());
                            } else e.open.send();
                            return;
                        } catch (Exception ignored) { }
                    }
                    openApp(e.pkg);
                }
            });
            r.setOnLongClickListener(new OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    Ui.dialog(c).setItems(new String[]{"Copy text", "Delete", "All from this app"}, new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            if (w == 0) {
                                android.content.ClipboardManager cm = (android.content.ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
                                if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("notification", (e.title + "\n" + e.text).trim()));
                                Ui.toast(c, "Copied");
                            } else if (w == 1) NotifHistory.remove(e);
                            else showNotifs(e.pkg);
                        }
                    }).show();
                    return true;
                }
            });
            box.addView(r, new LinearLayout.LayoutParams(-1, rowH));
        }
        col.addView(sv, new LinearLayout.LayoutParams(-1, Math.min(list.size(), 5) * rowH));
    }

    /** All of an app's saved notifications, in full. Tap one to open it; long-press to copy or delete. */
    private void showNotifs(final String pkg) {
        final Context c = getContext();
        List<NotifHistory.Entry> list = NotifHistory.recent(pkg, 200);
        ScrollView sv = new ScrollView(c);
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(VERTICAL);
        box.setPadding(Ui.dp(c, 18), Ui.dp(c, 6), Ui.dp(c, 18), Ui.dp(c, 6));
        sv.addView(box);
        final android.app.AlertDialog[] dlg = new android.app.AlertDialog[1];
        String lastDay = null;
        java.text.DateFormat tf = android.text.format.DateFormat.getTimeFormat(c);
        Ui.Palette dp = Ui.palette(c);
        for (final NotifHistory.Entry e : list) {
            String day = dayLabel(e.when);
            if (!day.equals(lastDay)) {
                lastDay = day;
                TextView d = Ui.text(c, day, 12, dp.accent);
                d.setTypeface(Typeface.DEFAULT_BOLD);
                d.setPadding(0, Ui.dp(c, 10), 0, Ui.dp(c, 2));
                box.addView(d);
            }
            LinearLayout r = new LinearLayout(c);
            r.setOrientation(VERTICAL);
            r.setPadding(Ui.dp(c, 10), Ui.dp(c, 8), Ui.dp(c, 10), Ui.dp(c, 8));
            r.setBackground(Ui.pressable(c, Ui.alpha(dp.text, 0x0C), Ui.dp(c, 12)));
            LinearLayout top = new LinearLayout(c);
            TextView t = Ui.text(c, e.title.isEmpty() ? appLabel(e.pkg) : e.title, 14, dp.text);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            top.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
            top.addView(Ui.text(c, tf.format(new java.util.Date(e.when)), 11, dp.subtext));
            r.addView(top);
            if (!e.text.isEmpty()) {
                TextView body = Ui.text(c, e.text, 13, dp.subtext);
                body.setTextIsSelectable(false);
                r.addView(body);
            }
            r.setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) {
                    if (dlg[0] != null) dlg[0].dismiss();
                    if (e.open != null) {
                        try {
                            if (android.os.Build.VERSION.SDK_INT >= 34) {
                                android.app.ActivityOptions o = android.app.ActivityOptions.makeBasic();
                                o.setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                                e.open.send(c, 0, null, null, null, null, o.toBundle());
                            } else e.open.send();
                            return;
                        } catch (Exception ignored) { }
                    }
                    openApp(e.pkg); // the notification's own link is gone: open the app
                }
            });
            r.setOnLongClickListener(new OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    Ui.dialog(c).setItems(new String[]{"Copy text", "Delete"}, new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            if (w == 0) {
                                android.content.ClipboardManager cm = (android.content.ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
                                if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("notification", (e.title + "\n" + e.text).trim()));
                                Ui.toast(c, "Copied");
                            } else {
                                NotifHistory.remove(e);
                                if (dlg[0] != null) { dlg[0].dismiss(); showNotifs(pkg); }
                            }
                        }
                    }).show();
                    return true;
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = Ui.dp(c, 6);
            box.addView(r, lp);
        }
        // The buttons sit at the top, inside the dialog, and the list has a limited height: with a long list the
        // dialog's own button bar could end up under the navigation bar on some phones, where taps don't reach it.
        LinearLayout outer = new LinearLayout(c);
        outer.setOrientation(VERTICAL);
        LinearLayout bar = new LinearLayout(c);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(c, 14), Ui.dp(c, 4), Ui.dp(c, 14), Ui.dp(c, 4));
        String[] labels = {"Open app", "Clear app", "Close"};
        for (int i = 0; i < labels.length; i++) {
            final int which = i;
            TextView b = Ui.button(c, labels[i], i == 0 ? Ui.alpha(dp.accent, 0x40) : Ui.alpha(dp.text, 0x14), dp.text);
            b.setTextSize(13);
            b.setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) {
                    if (dlg[0] != null) dlg[0].dismiss();
                    if (which == 0) openApp(pkg);
                    else if (which == 1) NotifHistory.clear(c, pkg);
                }
            });
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, -2, 1);
            if (i < labels.length - 1) blp.rightMargin = Ui.dp(c, 8);
            bar.addView(b, blp);
        }
        outer.addView(bar);
        int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.6f);
        outer.addView(sv, new LinearLayout.LayoutParams(-1, list.size() > 3 ? maxH : -2));
        dlg[0] = Ui.dialog(c).setTitle(appLabel(pkg) + " \u00B7 " + list.size()).setView(outer).show();
    }

    private String dayLabel(long t) {
        java.util.Calendar a = java.util.Calendar.getInstance(), b = java.util.Calendar.getInstance();
        b.setTimeInMillis(t);
        int da = a.get(java.util.Calendar.YEAR) * 400 + a.get(java.util.Calendar.DAY_OF_YEAR);
        int db = b.get(java.util.Calendar.YEAR) * 400 + b.get(java.util.Calendar.DAY_OF_YEAR);
        if (da == db) return "Today";
        if (da - db == 1) return "Yesterday";
        return android.text.format.DateFormat.format("EEEE, d MMM", b).toString();
    }

    // ------------------------------------------------------------------ Private DNS: one compact row of provider icons

    private View dnsBody() {
        final android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(getContext());
        hs.setHorizontalScrollBarEnabled(false);
        final LinearLayout row = new LinearLayout(getContext());
        hs.addView(row);
        fillDns(hs, row);
        return hs;
    }

    private void fillDns(final android.widget.HorizontalScrollView hs, final LinearLayout row) {
        final Context c = getContext();
        row.removeAllViews();
        String cur = PrivateDns.current(c);
        View selected = null;
        for (String[] p : PrivateDns.PROVIDERS) {
            View v = dnsIcon(hs, row, p[0], p[1], p[2], android.graphics.Color.parseColor(p[3]), p[4].isEmpty() ? null : p[4], false, p[1].equalsIgnoreCase(String.valueOf(cur)));
            if (p[1].equalsIgnoreCase(String.valueOf(cur))) selected = v;
        }
        for (String[] p : PrivateDns.custom(c)) {
            View v = dnsIcon(hs, row, p[0], p[1], p[1], pal.accent, SiteIcons.siteOf(p[1]), true, p[1].equalsIgnoreCase(String.valueOf(cur)));
            if (p[1].equalsIgnoreCase(String.valueOf(cur))) selected = v;
        }
        // "+" adds your own provider (e.g. your NextDNS address)
        LinearLayout add = dnsCell(row, "Add", null, Ui.alpha(pal.text, 0x22), null, false);
        add.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { askDnsProvider(hs, row); } });
        if (selected != null) {
            final View sel = selected;
            hs.post(new Runnable() { @Override public void run() { hs.smoothScrollTo(Math.max(0, sel.getLeft() - Ui.dp(c, 60)), 0); } });
        }
    }

    /** A round icon (the provider's website icon, or its initials until that's downloaded) and a short name. */
    private LinearLayout dnsCell(LinearLayout row, String name, final String initials, final int color, final String site, boolean on) {
        final Context c = getContext();
        LinearLayout cell = new LinearLayout(c);
        cell.setOrientation(VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(Ui.dp(c, 4), Ui.dp(c, 4), Ui.dp(c, 4), Ui.dp(c, 2));
        cell.setBackground(Ui.pressable(c, on ? Ui.alpha(pal.accent, 0x33) : 0x00000000, Ui.dp(c, 14)));
        final boolean plus = initials == null;
        final int ring = on ? pal.accent : 0;
        final ImageView iv = new ImageView(c);
        final int sizePx = Ui.dp(c, 42);
        iv.setImageDrawable(new android.graphics.drawable.Drawable() {
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            @Override public int getIntrinsicWidth() { return sizePx; }
            @Override public int getIntrinsicHeight() { return sizePx; }
            @Override public void draw(android.graphics.Canvas cv) {
                android.graphics.Rect r = getBounds();
                float cx = r.exactCenterX(), cy = r.exactCenterY(), rad = Math.min(r.width(), r.height()) / 2f;
                if (ring != 0) {
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(rad * 0.12f);
                    p.setColor(ring);
                    cv.drawCircle(cx, cy, rad * 0.94f, p);
                    rad *= 0.8f;
                }
                p.setStyle(Paint.Style.FILL);
                android.graphics.Bitmap icon = site == null ? null : SiteIcons.get(c, site);
                if (icon != null) {
                    // the website's icon on a white disc
                    p.setColor(0xFFFFFFFF);
                    cv.drawCircle(cx, cy, rad, p);
                    float in = rad * 0.68f;
                    cv.save();
                    android.graphics.Path clip = new android.graphics.Path();
                    clip.addCircle(cx, cy, rad, android.graphics.Path.Direction.CW);
                    cv.clipPath(clip);
                    cv.drawBitmap(icon, null, new android.graphics.RectF(cx - in, cy - in, cx + in, cy + in), p);
                    cv.restore();
                    return;
                }
                p.setColor(color);
                cv.drawCircle(cx, cy, rad, p);
                p.setColor(plus ? pal.text : Ui.textOn(color));
                p.setTypeface(Typeface.DEFAULT_BOLD);
                p.setTextAlign(Paint.Align.CENTER);
                String t = plus ? "+" : initials;
                p.setTextSize(rad * (plus ? 1.1f : t.length() > 1 ? 0.72f : 0.9f));
                cv.drawText(t, cx, cy - (p.descent() + p.ascent()) / 2f, p);
            }
            @Override public void setAlpha(int a) { }
            @Override public void setColorFilter(android.graphics.ColorFilter f) { }
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        });
        cell.addView(iv, new LinearLayout.LayoutParams(sizePx, sizePx));
        if (site != null && SiteIcons.get(c, site) == null) {
            SiteIcons.fetch(c, site, new SiteIcons.Done() { @Override public void done() { iv.invalidate(); iv.getDrawable().invalidateSelf(); } });
        }
        TextView t = Ui.text(c, name, 11, on ? pal.text : pal.subtext);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, Ui.dp(c, 3), 0, 0);
        if (on) t.setTypeface(Typeface.DEFAULT_BOLD);
        cell.addView(t, new LinearLayout.LayoutParams(Ui.dp(c, 60), -2));
        row.addView(cell);
        return cell;
    }

    private static String initialsOf(String name) {
        String[] w = name.trim().split("\\s+");
        if (w.length >= 2 && !w[1].isEmpty()) return (w[0].substring(0, 1) + w[1].substring(0, 1)).toUpperCase();
        return name.isEmpty() ? "?" : name.substring(0, Math.min(2, name.length())).toUpperCase();
    }

    private View dnsIcon(final android.widget.HorizontalScrollView hs, final LinearLayout row, final String name, final String host,
                         final String what, int color, String site, final boolean mine, boolean on) {
        String ini = "off".equals(host) ? "\u2715" : "auto".equals(host) ? "A" : initialsOf(name);
        LinearLayout cell = dnsCell(row, name, ini, color, site, on);
        cell.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { setDns(hs, row, host, name); } });
        cell.setOnLongClickListener(new OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                final Context c = getContext();
                android.app.AlertDialog.Builder b = Ui.dialog(c).setTitle(name).setMessage(what + ("off".equals(host) || "auto".equals(host) ? "" : "\n\n" + host));
                if (mine) b.setNeutralButton("Remove", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { PrivateDns.removeCustom(c, host); fillDns(hs, row); }
                });
                b.setPositiveButton("Use", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { setDns(hs, row, host, name); }
                }).setNegativeButton("Close", null).show();
                return true;
            }
        });
        return cell;
    }

    private void askDnsProvider(final android.widget.HorizontalScrollView hs, final LinearLayout row) {
        final Context c = getContext();
        final EditText name = Ui.tintField(c, new EditText(c));
        name.setSingleLine(true);
        name.setHint("Short name (e.g. NextDNS)");
        final EditText host = Ui.tintField(c, new EditText(c));
        host.setSingleLine(true);
        host.setHint("Hostname (e.g. abc123.dns.nextdns.io)");
        host.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(VERTICAL);
        box.setPadding(Ui.dp(c, 20), Ui.dp(c, 8), Ui.dp(c, 20), 0);
        box.addView(name);
        box.addView(host);
        Ui.dialog(c).setTitle("Add DNS provider").setView(box)
                .setPositiveButton("Add & use", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String h = host.getText().toString().trim().replaceFirst("^[a-zA-Z]+://", "").replaceAll("/.*$", "");
                        if (!h.matches("[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")) { Ui.toast(c, "That isn't a hostname"); return; }
                        String n = name.getText().toString().trim();
                        if (n.isEmpty()) n = h.split("\\.")[0];
                        PrivateDns.addCustom(c, n, h);
                        setDns(hs, row, h, n);
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private void setDns(final android.widget.HorizontalScrollView hs, final LinearLayout row, String target, String label) {
        final Context c = getContext();
        if (!PrivateDns.canChange(c)) {
            Ui.dialog(c).setTitle("Allow Grove to change Private DNS")
                    .setMessage("Android only lets apps change Private DNS with extra permission. Either start Shizuku "
                            + "(Grove will ask once), or run this once from a computer:\n\n" + PrivateDns.adbCommand(c))
                    .setPositiveButton("Copy command", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            android.content.ClipboardManager cm = (android.content.ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
                            if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("adb", PrivateDns.adbCommand(c)));
                            Ui.toast(c, "Copied");
                        }
                    })
                    .setNeutralButton("Android setting", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            try { c.startActivity(new Intent("android.settings.PRIVATE_DNS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
                            catch (Exception e) {
                                try { c.startActivity(new Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) { }
                            }
                        }
                    })
                    .setNegativeButton("Cancel", null).show();
            return;
        }
        PrivateDns.apply(c, target);
        Ui.toast(c, "off".equals(target) ? "Private DNS off" : "auto".equals(target) ? "Private DNS: automatic" : "Private DNS: " + label);
        fillDns(hs, row);
        // re-read once the change has landed (Shizuku runs it in the background)
        row.postDelayed(new Runnable() { @Override public void run() { fillDns(hs, row); } }, 900);
    }

    // ------------------------------------------------------------------ Grove Guard (your antivirus & firewall app)

    /** Grove Guard: malware scanner, web protection and firewall (Hypatia + ShizuWall, rebuilt). */
    static final String GUARD = "org.grove.guard";
    private static final Uri GUARD_STATUS = Uri.parse("content://org.grove.guard.status/status");
    private static final Uri GUARD_EVENTS = Uri.parse("content://org.grove.guard.status/events");
    private boolean installed(String pkg) {
        try { getContext().getPackageManager().getPackageInfo(pkg, 0); return true; } catch (Exception e) { return false; }
    }


    /** A card body that keeps itself up to date with the app's notifications. */
    private LinearLayout liveBody(final Runnable fill) {
        final LinearLayout col = new LinearLayout(getContext());
        col.setOrientation(VERTICAL);
        final Badges.Listener l = new Badges.Listener() { @Override public void onBadgesChanged() { fill.run(); } };
        col.addOnAttachStateChangeListener(new OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { Badges.addListener(l); }
            @Override public void onViewDetachedFromWindow(View v) { Badges.removeListener(l); }
        });
        return col;
    }

    private TextView statusLine(String text, boolean good) {
        TextView t = Ui.text(getContext(), (good ? "\u25CF  " : "\u25CB  ") + text, 14, good ? pal.text : pal.subtext);
        if (good) {
            android.text.SpannableString sp = new android.text.SpannableString(t.getText());
            sp.setSpan(new android.text.style.ForegroundColorSpan(0xFF2EAD5B), 0, 1, 0);
            t.setText(sp);
        }
        return t;
    }

    private LinearLayout buttonRow() {
        LinearLayout r = new LinearLayout(getContext());
        r.setPadding(0, Ui.dp(getContext(), 10), 0, 0);
        return r;
    }

    private void addButton(LinearLayout row, String text, boolean primary, final Runnable r) {
        Context c = getContext();
        TextView b = Ui.button(c, text, primary ? Ui.alpha(pal.accent, 0x40) : Ui.alpha(pal.text, 0x14), pal.text);
        b.setTextSize(13);
        b.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { r.run(); } });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = Ui.dp(c, 8);
        row.addView(b, lp);
    }

    /** Recent notifications from the app (e.g. "malware found"), tap to open. */
    private void addAlerts(LinearLayout col, String pkg) {
        List<Badges.Note> notes = Badges.notes(pkg);
        for (int i = 0; i < notes.size() && i < 3; i++) {
            final Badges.Note n = notes.get(i);
            TextView t = Ui.text(getContext(), (n.title.isEmpty() ? "" : n.title + ": ") + n.text, 13, pal.text);
            t.setMaxLines(2);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setPadding(Ui.dp(getContext(), 10), Ui.dp(getContext(), 6), Ui.dp(getContext(), 10), Ui.dp(getContext(), 6));
            t.setBackground(Ui.round(Ui.alpha(0xFFE5484D, 0x22), Ui.dp(getContext(), 10)));
            t.setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) { try { if (n.open != null) n.open.send(); } catch (Exception ignored) { } }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = Ui.dp(getContext(), 6);
            col.addView(t, lp);
        }
    }

    private void notInstalled(LinearLayout col, String what, final String url) {
        col.addView(Ui.text(getContext(), what + " isn't installed. It's a free, open-source app.", 13, pal.subtext));
        LinearLayout r = buttonRow();
        addButton(r, "Get it", true, new Runnable() {
            @Override public void run() { launcher.safeStart(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        });
        col.addView(r);
    }

    /**
     * Grove Guard's status, live (it tells Canopy when anything changes): realtime scanning with a switch, web
     * protection, threats, the firewall, and its latest activity — plus scan, update and firewall controls.
     */
    private View guardBody() {
        final Context c = getContext();
        final LinearLayout col = new LinearLayout(c);
        col.setOrientation(VERTICAL);
        final android.database.ContentObserver obs = new android.database.ContentObserver(new android.os.Handler(android.os.Looper.getMainLooper())) {
            // Groveguard signals at most every ~5 s; rebuild only while the card is on screen
            @Override public void onChange(boolean self) { if (notifsVisible(col)) fillGuard(col); else col.setTag(R.id.notif_dirty, Boolean.TRUE); }
        };
        col.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() { if (Boolean.TRUE.equals(col.getTag(R.id.notif_dirty)) && notifsVisible(col)) { col.setTag(R.id.notif_dirty, null); fillGuard(col); } }
        });
        col.addOnAttachStateChangeListener(new OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) {
                try { c.getContentResolver().registerContentObserver(GUARD_STATUS, true, obs); } catch (Exception ignored) { }
                fillGuard(col);
            }
            @Override public void onViewDetachedFromWindow(View v) {
                try { c.getContentResolver().unregisterContentObserver(obs); } catch (Exception ignored) { }
            }
        });
        fillGuard(col);
        return col;
    }

    private static final Uri GUARD_STATS = Uri.parse("content://org.grove.guard.status/stats");

    /** One row of Grove Guard's status, or null if it can't be read. */
    private android.content.ContentValues guardStatus() { return guardQuery(GUARD_STATUS); }

    private android.content.ContentValues guardQuery(Uri uri) {
        android.database.Cursor cur = null;
        try {
            cur = getContext().getContentResolver().query(uri, null, null, null, null);
            if (cur == null || !cur.moveToFirst()) return null;
            android.content.ContentValues v = new android.content.ContentValues();
            android.database.DatabaseUtils.cursorRowToContentValues(cur, v);
            return v;
        } catch (Exception e) {
            return null;
        } finally { if (cur != null) cur.close(); }
    }

    private static boolean on(android.content.ContentValues v, String k) {
        Integer i = v.getAsInteger(k);
        return i != null && i != 0;
    }

    private void guardSend(String action, Intent extras) {
        // the verified connection first (Groveguard 1.2+ refuses unsigned broadcasts), the old broadcast as a fallback
        if (Guard.control(getContext(), action, extras == null ? null : extras.getExtras())) return;
        Intent i = new Intent(action).setComponent(new android.content.ComponentName(GUARD, GUARD + ".fw.AutomationReceiver"));
        if (extras != null) i.putExtras(extras);
        try { getContext().sendBroadcast(i); } catch (Exception e) { Ui.toast(getContext(), "Couldn't reach Grove Guard"); }
    }

    private void fillGuard(final LinearLayout col) {
        final Context c = getContext();
        col.removeAllViews();
        if (!installed(GUARD)) {
            col.addView(Ui.text(c, "Grove Guard isn't installed. Install the Grove Guard APK to see its protection here.", 13, pal.subtext));
            return;
        }
        // ask Grove Guard off the main thread: waiting for it here froze the launcher when Guard was busy
        col.addView(Ui.text(c, "Reading Grove Guard\u2026", 13, pal.subtext));
        App.IO.execute(new Runnable() {
            @Override public void run() {
                final android.content.ContentValues got = guardStatus();
                final android.content.ContentValues stats = guardQuery(GUARD_STATS);
                App.MAIN.post(new Runnable() { @Override public void run() { col.removeAllViews(); fillGuardWith(col, got); if (got != null) fillGuardStats(col, stats); } });
            }
        });
    }

    // ------------------------------------------------------------------ GroveMedia stats

    static final String MEDIA = "com.grove.music";
    private static final Uri MEDIA_STATS = Uri.parse("content://com.grove.music.mediastats/stats");

    /** GroveMedia's simple overall stats: time on music, videos, comics and books for a period, with the top of each. */
    private View mediaBody() {
        final LinearLayout col = new LinearLayout(getContext());
        col.setOrientation(VERTICAL);
        col.addOnAttachStateChangeListener(new OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { fillMedia(col); }
            @Override public void onViewDetachedFromWindow(View v) { }
        });
        return col;
    }

    private void fillMedia(final LinearLayout col) {
        final Context c = getContext();
        if (!installed(MEDIA)) {
            col.removeAllViews();
            col.addView(Ui.text(c, "GroveMedia isn't installed. Install it to see your music, video, comic and book time here.", 13, pal.subtext));
            return;
        }
        final String period = sp.getString("media_period", "month");
        App.IO.execute(new Runnable() {
            @Override public void run() {
                final android.content.ContentValues v = queryRow(MEDIA_STATS, period);
                App.MAIN.post(new Runnable() { @Override public void run() { col.removeAllViews(); fillMediaWith(col, v, period); } });
            }
        });
    }

    private android.content.ContentValues queryRow(Uri uri, String selection) {
        android.database.Cursor cur = null;
        try {
            cur = getContext().getContentResolver().query(uri, null, selection, null, null);
            if (cur == null || !cur.moveToFirst()) return null;
            android.content.ContentValues v = new android.content.ContentValues();
            android.database.DatabaseUtils.cursorRowToContentValues(cur, v);
            return v;
        } catch (Exception e) {
            return null;
        } finally { if (cur != null) cur.close(); }
    }

    private static String hrs(long ms) {
        long m = ms / 60000;
        if (m < 60) return m + " min";
        return (m / 60) + " h " + (m % 60) + " min";
    }

    private void fillMediaWith(final LinearLayout col, android.content.ContentValues v, String period) {
        final Context c = getContext();
        if (v == null) {
            col.addView(Ui.text(c, "Update GroveMedia to share its stats with Grove Launcher.", 13, pal.subtext));
            LinearLayout r = buttonRow();
            addButton(r, "Open GroveMedia", true, new Runnable() { @Override public void run() { openApp(MEDIA); } });
            col.addView(r);
            return;
        }
        // period chips
        LinearLayout chips = new LinearLayout(c);
        String[] ids = {"today", "month", "year", "all"}, names = {"Today", "This month", "This year", "All time"};
        for (int i = 0; i < ids.length; i++) {
            final String id = ids[i];
            boolean on = id.equals(period);
            TextView ch = Ui.text(c, names[i], 12, on ? pal.accent : pal.subtext);
            ch.setPadding(Ui.dp(c, 10), Ui.dp(c, 6), Ui.dp(c, 10), Ui.dp(c, 6));
            ch.setBackground(Ui.pressable(c, on ? Ui.alpha(pal.accent, 0x26) : 0x00000000, Ui.dp(c, 14)));
            ch.setOnClickListener(new OnClickListener() {
                @Override public void onClick(View x) { sp.edit().putString("media_period", id).apply(); fillMedia(col); }
            });
            chips.addView(ch);
        }
        col.addView(chips);
        long music = val(v, "music_ms"), videos = val(v, "videos_ms"), comics = val(v, "comics_ms"), book = val(v, "book_ms");
        long total = music + videos + comics + book;
        // total + split bar
        LinearLayout head = new LinearLayout(c);
        head.setOrientation(VERTICAL);
        head.setPadding(Ui.dp(c, 12), Ui.dp(c, 10), Ui.dp(c, 12), Ui.dp(c, 10));
        head.setBackground(Ui.round(Ui.alpha(pal.accent, 0x1F), Ui.dp(c, 14)));
        TextView tt = Ui.text(c, hrs(total), 26, pal.text);
        tt.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(tt);
        head.addView(Ui.text(c, "total time", 12, pal.subtext));
        LinearLayout bar = new LinearLayout(c);
        long[] parts = {music, videos, comics, book};
        float[] shade = {1f, 0.65f, 0.4f, 0.22f};
        for (int i = 0; i < 4; i++) if (parts[i] > 0) {
            View seg = new View(c);
            seg.setBackgroundColor(Ui.alpha(pal.accent, Math.round(255 * shade[i])));
            bar.addView(seg, new LinearLayout.LayoutParams(0, -1, parts[i]));
        }
        if (total == 0) bar.setBackgroundColor(Ui.alpha(pal.text, 0x14));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, Ui.dp(c, 6));
        bl.topMargin = Ui.dp(c, 8);
        head.addView(bar, bl);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-1, -2);
        hl.topMargin = Ui.dp(c, 8);
        col.addView(head, hl);
        // four tiles
        String[] labels = {"Music \u00B7 " + val(v, "music_plays") + " plays", "Videos \u00B7 " + val(v, "videos_watched") + " watched",
                "Comics \u00B7 " + val(v, "comics_chapters") + " chapters", "Books \u00B7 " + val(v, "books") + " books"};
        for (int r = 0; r < 2; r++) {
            LinearLayout row = new LinearLayout(c);
            for (int j = 0; j < 2; j++) {
                int i = r * 2 + j;
                LinearLayout t = new LinearLayout(c);
                t.setOrientation(VERTICAL);
                t.setPadding(Ui.dp(c, 10), Ui.dp(c, 8), Ui.dp(c, 10), Ui.dp(c, 8));
                t.setBackground(Ui.round(Ui.alpha(pal.text, 0x0D), Ui.dp(c, 12)));
                TextView tv = Ui.text(c, hrs(parts[i]), 17, pal.text);
                t.addView(tv);
                TextView lb = Ui.text(c, labels[i], 11, pal.subtext);
                lb.setSingleLine(true);
                lb.setEllipsize(TextUtils.TruncateAt.END);
                t.addView(lb);
                LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1);
                if (j == 0) tl.rightMargin = Ui.dp(c, 4); else tl.leftMargin = Ui.dp(c, 4);
                row.addView(t, tl);
            }
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
            rl.topMargin = Ui.dp(c, 8);
            col.addView(row, rl);
        }
        // top of each
        String[][] tops = {{"Song", v.getAsString("top_song"), val(v, "top_song_plays") + " plays"},
                {"Video", v.getAsString("top_video"), hrs(val(v, "top_video_ms"))},
                {"Comic", v.getAsString("top_comic"), null}, {"Book", v.getAsString("top_book"), null}};
        boolean any = false;
        for (String[] t : tops) {
            if (t[1] == null || t[1].isEmpty()) continue;
            if (!any) {
                TextView h = Ui.text(c, "Top", 12, pal.accent);
                h.setTypeface(Typeface.DEFAULT_BOLD);
                h.setPadding(0, Ui.dp(c, 10), 0, Ui.dp(c, 2));
                col.addView(h);
                any = true;
            }
            TextView line = Ui.text(c, t[0] + " \u00B7 " + t[1] + (t[2] != null ? "  (" + t[2] + ")" : ""), 13, pal.text);
            line.setSingleLine(true);
            line.setEllipsize(TextUtils.TruncateAt.END);
            line.setPadding(0, Ui.dp(c, 2), 0, Ui.dp(c, 2));
            col.addView(line);
        }
        if (total == 0) {
            TextView e = Ui.text(c, "Nothing yet in this period", 13, pal.subtext);
            e.setPadding(0, Ui.dp(c, 8), 0, 0);
            col.addView(e);
        }
        col.setOnClickListener(new OnClickListener() { @Override public void onClick(View x) { openApp(MEDIA); } });
    }

    /** Groveguard 2.3+: what it blocked — DNS lookups today, the 4 busiest block lists, apps cut off from the internet. */
    private void fillGuardStats(final LinearLayout col, final android.content.ContentValues s) {
        if (s == null) return; // older Groveguard
        final Context c = getContext();
        java.util.Locale L = java.util.Locale.getDefault();
        int at = Math.max(0, col.getChildCount() - 1); // above the buttons
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(VERTICAL);
        box.setPadding(Ui.dp(c, 12), Ui.dp(c, 10), Ui.dp(c, 12), Ui.dp(c, 10));
        box.setBackground(Ui.round(Ui.alpha(pal.accent, 0x14), Ui.dp(c, 14)));
        // DNS
        long qT = val(s, "dns_queries_today"), bT = val(s, "dns_blocked_today"), bAll = val(s, "dns_blocked_total");
        Double pct = s.getAsDouble("dns_blocked_pct");
        boolean dnsOn = on(s, "dns_on");
        LinearLayout dnsRow = new LinearLayout(c);
        dnsRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView big = Ui.text(c, String.format(L, "%,d", bT), 24, pal.text);
        big.setTypeface(Typeface.DEFAULT_BOLD);
        dnsRow.addView(big);
        TextView dnsSub = Ui.text(c, dnsOn
                ? String.format(L, "  blocked today of %,d lookups%s\n  %,d blocked in total", qT, pct == null ? "" : String.format(L, " (%.1f%%)", pct), bAll)
                : "  DNS filter is off \u2014 tap to turn it on in Groveguard", 12, pal.subtext);
        dnsRow.addView(dnsSub, new LinearLayout.LayoutParams(0, -2, 1));
        dnsRow.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { openGuardTab(12); } });
        box.addView(dnsRow);
        // top block lists
        long max = 1;
        for (int i = 1; i <= 4; i++) max = Math.max(max, Math.max(val(s, "top" + i + "_today"), 1));
        boolean anyToday = false;
        for (int i = 1; i <= 4; i++) if (val(s, "top" + i + "_today") > 0) anyToday = true;
        if (!anyToday) { max = 1; for (int i = 1; i <= 4; i++) max = Math.max(max, val(s, "top" + i + "_total")); }
        boolean header = false;
        for (int i = 1; i <= 4; i++) {
            String name = s.getAsString("top" + i + "_name");
            if (name == null || name.isEmpty()) continue;
            if (!header) {
                TextView h = Ui.text(c, anyToday ? "Top block lists today" : "Top block lists", 12, pal.accent);
                h.setTypeface(Typeface.DEFAULT_BOLD);
                h.setPadding(0, Ui.dp(c, 10), 0, Ui.dp(c, 4));
                box.addView(h);
                header = true;
            }
            long n = anyToday ? val(s, "top" + i + "_today") : val(s, "top" + i + "_total");
            LinearLayout row = new LinearLayout(c);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView nm = Ui.text(c, name, 13, pal.text);
            nm.setSingleLine(true);
            nm.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(nm, new LinearLayout.LayoutParams(0, -2, 1));
            row.addView(Ui.text(c, String.format(L, "%,d", n), 13, pal.subtext));
            box.addView(row);
            // a bar for the share
            android.widget.FrameLayout track = new android.widget.FrameLayout(c);
            track.setBackground(Ui.round(Ui.alpha(pal.text, 0x14), Ui.dp(c, 3)));
            View fill = new View(c);
            fill.setBackground(Ui.round(pal.accent, Ui.dp(c, 3)));
            track.addView(fill, new android.widget.FrameLayout.LayoutParams(-1, -1));
            final float share = Math.max(0.02f, Math.min(1f, n / (float) max));
            fill.setPivotX(0);
            fill.setScaleX(share);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, Ui.dp(c, 5));
            tl.topMargin = Ui.dp(c, 3);
            tl.bottomMargin = Ui.dp(c, 6);
            box.addView(track, tl);
            row.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { openGuardTab(16); } });
        }
        // blocked apps
        long apps = val(s, "fw_apps_blocked");
        String names = s.getAsString("fw_apps");
        TextView ap = Ui.text(c, apps > 0
                ? apps + (apps == 1 ? " app" : " apps") + " cut off from the internet" + (names != null && !names.isEmpty() ? ": " + names + (apps > 4 ? "\u2026" : "") : "")
                : on(s, "fw_on") ? "No apps blocked by the firewall" : "Firewall off", 12, pal.subtext);
        ap.setPadding(0, Ui.dp(c, 6), 0, 0);
        ap.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { openGuardTab(1); } });
        box.addView(ap);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2);
        bl.topMargin = Ui.dp(c, 8);
        col.addView(box, at, bl);
    }

    private static long val(android.content.ContentValues v, String k) {
        Long l = v.getAsLong(k);
        return l == null ? 0 : l;
    }

    /** Open Groveguard on a tab: 12 DNS protection, 1 firewall, 16 block lists. */
    private void openGuardTab(int tab) {
        Intent i = new Intent().setComponent(new android.content.ComponentName(GUARD, GUARD + ".MainActivity"))
                .putExtra("tab", tab).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        launcher.safeStart(i);
    }

    private void fillGuardWith(final LinearLayout col, final android.content.ContentValues st) {
        final Context c = getContext();
        if (st == null) {
            col.addView(Ui.text(c, "Open Grove Guard once so it can share its status with Grove Launcher.", 13, pal.subtext));
            LinearLayout r = buttonRow();
            addButton(r, "Open Grove Guard", true, new Runnable() { @Override public void run() { openApp(GUARD); } });
            col.addView(r);
            return;
        }
        String summary = st.getAsString("summary");
        if (summary != null && !summary.isEmpty()) {
            TextView sum = Ui.text(c, summary, 15, pal.text);
            sum.setTypeface(Typeface.DEFAULT_BOLD);
            col.addView(sum);
        }
        // realtime scanning: a switch (no notification needed — the status lives here)
        final boolean rt = on(st, "realtime_wanted");
        col.addView(guardSwitch("Realtime scanning", on(st, "realtime") ? "Watching new and changed files" : rt ? "Starting\u2026" : "Off", rt, new Runnable() {
            @Override public void run() { guardSend("org.grove.guard.REALTIME", new Intent().putExtra("state", !rt)); }
        }));
        final boolean fw = on(st, "firewall_wanted");
        Integer blocked = st.getAsInteger("blocked");
        String profile = st.getAsString("profile");
        String fwSub = on(st, "firewall")
                ? ("allow".equals(st.getAsString("firewall_mode")) ? "Only allowed apps go online" : (blocked == null ? 0 : blocked) + " apps blocked")
                        + (profile != null && !profile.isEmpty() ? " \u00B7 " + profile : "")
                : fw ? "Waiting for Shizuku / root" : "Off";
        col.addView(guardSwitch("Firewall", fwSub, fw, new Runnable() {
            @Override public void run() { guardSend("shizuwall.CONTROL", new Intent().putExtra("state", !fw)); }
        }));
        // smaller facts
        StringBuilder facts = new StringBuilder();
        facts.append(on(st, "web") ? "Web protection on" : "Web protection off");
        Integer sigs = st.getAsInteger("signatures");
        if (sigs != null && sigs > 0) facts.append(" \u00B7 ").append(sigs >= 1_000_000 ? String.format(java.util.Locale.ROOT, "%.1fM", sigs / 1e6) : sigs >= 1000 ? (sigs / 1000) + "k" : String.valueOf(sigs)).append(" signatures");
        Long upd = st.getAsLong("db_updated");
        if (upd != null && upd > 0) facts.append(" \u00B7 updated ").append(android.text.format.DateUtils.getRelativeTimeSpanString(upd, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS));
        TextView f = Ui.text(c, facts.toString(), 12, pal.subtext);
        f.setPadding(0, Ui.dp(c, 4), 0, 0);
        col.addView(f);
        if (on(st, "scanning")) {
            Integer p = st.getAsInteger("scan_progress");
            TextView sc = Ui.text(c, "Scanning\u2026 " + (p == null ? 0 : p) + "%", 13, pal.accent);
            sc.setPadding(0, Ui.dp(c, 4), 0, 0);
            col.addView(sc);
        }
        Integer threats = st.getAsInteger("threats");
        if (threats != null && threats > 0) {
            String last = st.getAsString("last_threat");
            TextView t = Ui.text(c, "\u26A0 " + threats + (threats == 1 ? " threat" : " threats") + (last != null ? ": " + last : "") + " \u2014 tap to review", 13, pal.text);
            t.setPadding(Ui.dp(c, 10), Ui.dp(c, 6), Ui.dp(c, 10), Ui.dp(c, 6));
            t.setBackground(Ui.round(Ui.alpha(0xFFE5484D, 0x26), Ui.dp(c, 10)));
            t.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { openApp(GUARD); } });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = Ui.dp(c, 6);
            col.addView(t, lp);
        }
        LinearLayout r = buttonRow();
        addButton(r, "Scan", true, new Runnable() {
            @Override public void run() { guardSend("org.grove.guard.SCAN", null); Ui.toast(c, "Grove Guard is scanning"); }
        });
        addButton(r, "Update", false, new Runnable() {
            @Override public void run() { guardSend("org.grove.guard.UPDATE", null); Ui.toast(c, "Updating signatures"); }
        });
        addButton(r, "Profile\u2026", false, new Runnable() { @Override public void run() { guardProfile(); } });
        addButton(r, "Open", false, new Runnable() { @Override public void run() { openApp(GUARD); } });
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(r);
        col.addView(hs);
    }

    private View guardSwitch(String title, String sub, boolean checked, final Runnable toggle) {
        Context c = getContext();
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(c, 6), 0, Ui.dp(c, 2));
        LinearLayout tx = new LinearLayout(c);
        tx.setOrientation(VERTICAL);
        tx.addView(Ui.text(c, title, 14, pal.text));
        tx.addView(Ui.text(c, sub, 12, pal.subtext));
        row.addView(tx, new LinearLayout.LayoutParams(0, -2, 1));
        android.widget.Switch sw = new android.widget.Switch(c);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean v) { toggle.run(); }
        });
        row.addView(sw);
        return row;
    }

    private void guardProfile() {
        final Context c = getContext();
        final EditText in = Ui.tintField(c, new EditText(c));
        in.setSingleLine(true);
        in.setHint("Profile name, as in Grove Guard");
        String last = sp.getString("guard_profile", null);
        if (last != null) in.setText(last);
        android.widget.FrameLayout wrap = new android.widget.FrameLayout(c);
        wrap.setPadding(Ui.dp(c, 20), Ui.dp(c, 8), Ui.dp(c, 20), 0);
        wrap.addView(in);
        Ui.dialog(c).setTitle("Switch firewall profile").setView(wrap)
                .setPositiveButton("Switch", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String name = in.getText().toString().trim();
                        if (name.isEmpty()) return;
                        sp.edit().putString("guard_profile", name).apply();
                        guardSend("shizuwall.PROFILE", new Intent().putExtra("profile", name).putExtra("force_enable", true));
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    // ------------------------------------------------------------------ your music

    /** Now playing + the songs and artists you play most (learned on this device, see {@link MusicHistory}). */
    private View musicBody() {
        final Context c = getContext();
        MusicHistory.ensureLoaded(c);
        final LinearLayout col = new LinearLayout(c);
        col.setOrientation(VERTICAL);
        final MusicHistory.Listener l = new MusicHistory.Listener() {
            @Override public void onMusicChanged() { refillMusic(col); }
        };
        col.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() { if (Boolean.TRUE.equals(col.getTag(R.id.notif_dirty)) && notifsVisible(col)) refillMusic(col); }
        });
        col.addOnAttachStateChangeListener(new OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { MusicHistory.addListener(l); refillMusic(col); }
            @Override public void onViewDetachedFromWindow(View v) { MusicHistory.removeListener(l); }
        });
        fillMusic(col);
        return col;
    }

    private boolean notificationAccess() {
        String flat = android.provider.Settings.Secure.getString(getContext().getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(getContext().getPackageName());
    }

    private void fillMusic(final LinearLayout col) {
        final Context c = getContext();
        col.removeAllViews();
        if (!notificationAccess()) {
            col.addView(Ui.text(c, "Grove learns the music you play most from what's playing in your music apps. "
                    + "Allow notification access to turn this on (it stays on this phone).", 13, pal.subtext));
            TextView b = Ui.button(c, "Allow access", Ui.alpha(pal.accent, 0x33), pal.text);
            b.setTextSize(13);
            b.setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) {
                    try { c.startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) { }
                }
            });
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
            blp.topMargin = Ui.dp(c, 8);
            col.addView(b, blp);
            return;
        }
        // ---- now playing
        final android.media.session.MediaController mc = MusicHistory.current();
        android.media.MediaMetadata md = MusicHistory.metaOf(mc); // cached: asking the player app could block for a second
        if (md != null && MusicHistory.title(md) != null) {
            LinearLayout row = new LinearLayout(c);
            row.setGravity(Gravity.CENTER_VERTICAL);
            android.widget.ImageView art = new android.widget.ImageView(c);
            android.graphics.Bitmap bm = md.getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (bm == null) bm = md.getBitmap(android.media.MediaMetadata.METADATA_KEY_ART);
            if (bm == null) bm = md.getBitmap(android.media.MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            if (bm != null) art.setImageBitmap(bm);
            else {
                try { art.setImageDrawable(c.getPackageManager().getApplicationIcon(mc.getPackageName())); } catch (Exception ignored) { }
            }
            art.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            art.setBackground(Ui.round(Ui.alpha(pal.text, 0x14), Ui.dp(c, 10)));
            art.setClipToOutline(true);
            row.addView(art, new LinearLayout.LayoutParams(Ui.dp(c, 54), Ui.dp(c, 54)));
            LinearLayout info = new LinearLayout(c);
            info.setOrientation(VERTICAL);
            info.setPadding(Ui.dp(c, 12), 0, Ui.dp(c, 6), 0);
            TextView t = Ui.text(c, MusicHistory.title(md), 15, pal.text);
            t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            info.addView(t);
            String a = MusicHistory.artist(md);
            String appName = appLabel(mc.getPackageName());
            TextView sub = Ui.text(c, (a == null || a.isEmpty() ? "" : a + " · ") + appName, 13, pal.subtext);
            sub.setSingleLine(true);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            info.addView(sub);
            row.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
            android.media.session.PlaybackState ps = MusicHistory.stateOf(mc);
            final boolean isPlaying = ps != null && ps.getState() == android.media.session.PlaybackState.STATE_PLAYING;
            row.addView(control("⏮", new Runnable() { @Override public void run() { mc.getTransportControls().skipToPrevious(); } }));
            row.addView(control(isPlaying ? "⏸" : "▶", new Runnable() {
                @Override public void run() { if (isPlaying) mc.getTransportControls().pause(); else mc.getTransportControls().play(); }
            }));
            row.addView(control("⏭", new Runnable() { @Override public void run() { mc.getTransportControls().skipToNext(); } }));
            row.setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) { openApp(mc.getPackageName()); }
            });
            col.addView(row);
        }
        // ---- on repeat
        List<MusicHistory.Track> top = MusicHistory.topTracks(3);
        if (top.isEmpty()) {
            if (col.getChildCount() == 0) col.addView(Ui.text(c, "Play music in any app — the songs and artists you play most will show up here.", 13, pal.subtext));
            else col.addView(spaced(Ui.text(c, "Your most played songs will appear here as you listen.", 12, pal.subtext), 10));
            return;
        }
        col.addView(spaced(heading("On repeat · last 30 days"), col.getChildCount() > 0 ? 14 : 0));
        for (int i = 0; i < top.size(); i++) {
            final MusicHistory.Track tr = top.get(i);
            LinearLayout r = new LinearLayout(c);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(0, Ui.dp(c, 5), 0, Ui.dp(c, 5));
            r.setBackground(Ui.pressable(c, 0x00000000, Ui.dp(c, 10)));
            TextView n = Ui.text(c, String.valueOf(i + 1), 15, pal.accent);
            n.setGravity(Gravity.CENTER);
            r.addView(n, new LinearLayout.LayoutParams(Ui.dp(c, 26), -2));
            LinearLayout tx = new LinearLayout(c);
            tx.setOrientation(VERTICAL);
            TextView tt = Ui.text(c, tr.title, 14, pal.text);
            tt.setSingleLine(true);
            tt.setEllipsize(TextUtils.TruncateAt.END);
            tx.addView(tt);
            TextView ta = Ui.text(c, (tr.artist.isEmpty() ? "" : tr.artist + " · ") + tr.plays + (tr.plays == 1 ? " play" : " plays"), 12, pal.subtext);
            ta.setSingleLine(true);
            ta.setEllipsize(TextUtils.TruncateAt.END);
            tx.addView(ta);
            r.addView(tx, new LinearLayout.LayoutParams(0, -2, 1));
            r.setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) { playFromSearch(tr.pkg, tr.title, tr.artist, false); }
            });
            col.addView(r);
        }
        // ---- top artists
        List<String[]> artists = MusicHistory.topArtists(10);
        if (!artists.isEmpty()) {
            col.addView(spaced(heading("Top artists"), 12));
            android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(c);
            hs.setHorizontalScrollBarEnabled(false);
            LinearLayout chips = new LinearLayout(c);
            for (final String[] ar : artists) {
                TextView chip = Ui.button(c, ar[0] + "  " + ar[1], Ui.alpha(pal.accent, 0x26), pal.text);
                chip.setTextSize(13);
                chip.setOnClickListener(new OnClickListener() {
                    @Override public void onClick(View v) { playFromSearch(ar[2], null, ar[0], true); }
                });
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
                clp.rightMargin = Ui.dp(c, 8);
                chips.addView(chip, clp);
            }
            hs.addView(chips);
            col.addView(hs);
        }
        TextView clear = Ui.text(c, "Clear history", 12, pal.subtext);
        clear.setGravity(Gravity.END);
        clear.setPadding(0, Ui.dp(c, 10), 0, 0);
        clear.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                Ui.dialog(c).setTitle("Clear music history?").setMessage("Grove forgets the songs it counted. It will start learning again as you listen.")
                        .setPositiveButton("Clear", new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface d, int w) { MusicHistory.clear(c); }
                        }).setNegativeButton("Cancel", null).show();
            }
        });
        col.addView(clear, new LinearLayout.LayoutParams(-1, -2));
    }

    private TextView heading(String s) {
        TextView h = Ui.text(getContext(), s, 12, pal.accent);
        h.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return h;
    }

    private View spaced(View v, int topDp) {
        v.setPadding(v.getPaddingLeft(), Ui.dp(getContext(), topDp), v.getPaddingRight(), v.getPaddingBottom());
        return v;
    }

    private TextView control(String glyph, final Runnable r) {
        TextView b = Ui.text(getContext(), glyph, 20, pal.text);
        b.setGravity(Gravity.CENTER);
        b.setBackground(Ui.pressable(getContext(), 0x00000000, Ui.dp(getContext(), 20)));
        b.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { try { r.run(); } catch (Exception ignored) { } }
        });
        b.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(getContext(), 40), Ui.dp(getContext(), 40)));
        return b;
    }

    private String appLabel(String pkg) {
        try {
            android.content.pm.PackageManager pm = getContext().getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) { return pkg; }
    }

    private void openApp(String pkg) {
        Intent i = getContext().getPackageManager().getLaunchIntentForPackage(pkg);
        if (i != null) try { getContext().startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) { }
    }

    /** Asks the music app it was played in to play this song (or artist) again. */
    private void playFromSearch(String pkg, String title, String artist, boolean artistOnly) {
        Context c = getContext();
        String q = artistOnly ? artist : (title + (artist == null || artist.isEmpty() ? "" : " " + artist));
        Intent i = new Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                .putExtra(android.app.SearchManager.QUERY, q)
                .putExtra(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, artistOnly ? "vnd.android.cursor.item/artist" : "vnd.android.cursor.item/audio")
                .putExtra(android.provider.MediaStore.EXTRA_MEDIA_ARTIST, artist)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!artistOnly) i.putExtra(android.provider.MediaStore.EXTRA_MEDIA_TITLE, title);
        if (pkg != null) {
            try { c.startActivity(new Intent(i).setPackage(pkg)); return; } catch (Exception ignored) { }
        }
        try { c.startActivity(i); return; } catch (Exception ignored) { }
        if (pkg != null) openApp(pkg);
    }

    @SuppressWarnings("unused")
    private static List<String> builtins() { return Arrays.asList(BUILTIN); }
}
