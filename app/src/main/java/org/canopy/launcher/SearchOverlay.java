/*
 * Copyright 2026 The Canopy Launcher authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.canopy.launcher;

import android.Manifest;
import android.app.SearchManager;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.CalendarContract;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Universal search in the style of Nova's: apps, contacts, phone settings, calendar events, your
 * shortcuts, a calculator, and one-tap searches in your browser, YouTube, YouTube Music, Play Store
 * and Maps. Canopy itself never connects to the internet — web searches open in the app you choose.
 */
public class SearchOverlay extends FrameLayout {
    private final LauncherActivity launcher;
    private final Ui.Palette pal;
    private final EditText field;
    private final LinearLayout results;
    private final ScrollView resultsScroll;
    /** true when the results are shown inline under another search field (the app drawer's). */
    private final boolean embedded;
    private Runnable onClose;
    private final Runnable run = new Runnable() { @Override public void run() { render(); } };

    // label, keywords, action
    private static final String[][] SETTINGS = {
            {"Wi‑Fi", "wifi wi-fi wireless internet network", Settings.ACTION_WIFI_SETTINGS},
            {"Bluetooth", "bluetooth bt pair headphones", Settings.ACTION_BLUETOOTH_SETTINGS},
            {"Mobile network & SIM", "mobile data sim cellular network roaming hotspot tethering", Settings.ACTION_WIRELESS_SETTINGS},
            {"Airplane mode", "airplane aeroplane flight mode", Settings.ACTION_AIRPLANE_MODE_SETTINGS},
            {"Data usage", "data usage limit", "android.settings.DATA_USAGE_SETTINGS"},
            {"Display & brightness", "display brightness dark theme screen timeout font size night", Settings.ACTION_DISPLAY_SETTINGS},
            {"Night light", "night light blue light eye comfort", "android.settings.NIGHT_DISPLAY_SETTINGS"},
            {"Sound & vibration", "sound volume ringtone vibration vibrate mute", Settings.ACTION_SOUND_SETTINGS},
            {"Do not disturb", "do not disturb dnd silent focus", "android.settings.ZEN_MODE_SETTINGS"},
            {"Notifications", "notifications alerts", "android.settings.NOTIFICATION_SETTINGS"},
            {"Battery", "battery power saver charging", Intent.ACTION_POWER_USAGE_SUMMARY},
            {"Storage", "storage space memory free up", Settings.ACTION_INTERNAL_STORAGE_SETTINGS},
            {"Apps", "apps applications app info permissions uninstall", Settings.ACTION_APPLICATION_SETTINGS},
            {"Default apps", "default apps browser launcher home sms phone", Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS},
            {"Home app (launcher)", "home launcher default canopy", Settings.ACTION_HOME_SETTINGS},
            {"Location", "location gps", Settings.ACTION_LOCATION_SOURCE_SETTINGS},
            {"Security & lock screen", "security lock screen password pin fingerprint face unlock", Settings.ACTION_SECURITY_SETTINGS},
            {"Privacy", "privacy permissions", Settings.ACTION_PRIVACY_SETTINGS},
            {"Accessibility", "accessibility talkback magnify", Settings.ACTION_ACCESSIBILITY_SETTINGS},
            {"Date & time", "date time clock timezone", Settings.ACTION_DATE_SETTINGS},
            {"Language", "language locale", Settings.ACTION_LOCALE_SETTINGS},
            {"Keyboard", "keyboard input ime", Settings.ACTION_INPUT_METHOD_SETTINGS},
            {"Accounts", "accounts sync google", Settings.ACTION_SYNC_SETTINGS},
            {"NFC", "nfc tap pay contactless", Settings.ACTION_NFC_SETTINGS},
            {"VPN", "vpn private network", Settings.ACTION_VPN_SETTINGS},
            {"Cast", "cast screen mirror chromecast", Settings.ACTION_CAST_SETTINGS},
            {"Developer options", "developer options usb debugging", Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS},
            {"About phone", "about phone device info model android version build", Settings.ACTION_DEVICE_INFO_SETTINGS},
            {"Wallpaper", "wallpaper background", Intent.ACTION_SET_WALLPAPER},
            {"Grove settings", "canopy launcher settings home screen", "canopy"},
    };

    /** Results only, shown directly under someone else's search field (the app drawer). */
    public SearchOverlay(LauncherActivity c, EditText external, Runnable onClose) {
        super(c);
        launcher = c;
        pal = Ui.palette(c);
        embedded = true;
        this.onClose = onClose;
        field = external;
        resultsScroll = new ScrollView(c);
        resultsScroll.setClipToPadding(false);
        resultsScroll.setOverScrollMode(OVER_SCROLL_NEVER);
        results = new LinearLayout(c);
        results.setOrientation(LinearLayout.VERTICAL);
        results.setPadding(0, 0, 0, Ui.dp(c, 40));
        resultsScroll.addView(results);
        addView(resultsScroll, new LayoutParams(-1, -1));
        loadAppShortcuts();
    }

    private int insetTopPx, sidePx;

    public void setBottomInset(int px) { results.setPadding(sidePx, 0, sidePx, px + Ui.dp(getContext(), 24)); }

    /** Results start below a floating bar (the drawer's acrylic search bar) and scroll underneath it. */
    public void setTopInset(int top, int side) {
        insetTopPx = top;
        sidePx = side;
        resultsScroll.setPadding(0, top, 0, 0);
        results.setPadding(side, 0, side, results.getPaddingBottom());
    }

    public void setScrollListener(View.OnScrollChangeListener l) { resultsScroll.setOnScrollChangeListener(l); }

    public boolean canScrollUp() { return resultsScroll.canScrollVertically(-1); }

    /** Debounced: call on every keystroke. */
    public void queryChanged() {
        removeCallbacks(run);
        postDelayed(run, 90);
        loadAppShortcuts();
    }

    public void scrollToTop() { resultsScroll.scrollTo(0, 0); }

    public SearchOverlay(LauncherActivity c, String initial) {
        super(c);
        launcher = c;
        embedded = false;
        pal = Ui.palette(c);
        setBackgroundColor(Ui.alpha(pal.bg, 0xF5));
        setClickable(true);

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setFitsSystemWindows(true);
        addView(col, new LayoutParams(-1, -1));

        LinearLayout bar = new LinearLayout(c);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(Ui.round(pal.surface, Ui.dp(c, 28)));
        bar.setElevation(Ui.dp(c, 3));
        bar.addView(Ui.iconButton(c, R.drawable.ic_back, pal.text, new OnClickListener() {
            @Override public void onClick(View v) { close(); }
        }), new LinearLayout.LayoutParams(Ui.dp(c, 48), Ui.dp(c, 48)));
        field = new EditText(c);
        Ui.tintField(c, field);
        field.setHint("Search apps, contacts, settings, web…");
        field.setTextColor(pal.text);
        field.setHintTextColor(pal.subtext);
        field.setBackground(null);
        field.setSingleLine(true);
        field.setTextSize(17);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        bar.addView(field, new LinearLayout.LayoutParams(0, Ui.dp(c, 56), 1));
        bar.addView(Ui.iconButton(c, R.drawable.ic_close, pal.subtext, new OnClickListener() {
            @Override public void onClick(View v) { if (field.length() > 0) field.setText(""); else close(); }
        }), new LinearLayout.LayoutParams(Ui.dp(c, 48), Ui.dp(c, 48)));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.setMargins(Ui.dp(c, 14), Ui.dp(c, 12), Ui.dp(c, 14), Ui.dp(c, 8));
        col.addView(bar, blp);

        // Search-on chips (like Nova's provider row)
        HorizontalScrollView hs = new HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(c);
        chips.setPadding(Ui.dp(c, 14), 0, Ui.dp(c, 14), Ui.dp(c, 4));
        chips.addView(chip(engineName(), "web"));
        chips.addView(chip("YouTube", "youtube"));
        chips.addView(chip("YouTube Music", "ytmusic"));
        chips.addView(chip("Play Store", "play"));
        chips.addView(chip("Maps", "maps"));
        hs.addView(chips);
        col.addView(hs);

        ScrollView scroll = new ScrollView(c);
        resultsScroll = scroll;
        results = new LinearLayout(c);
        results.setOrientation(LinearLayout.VERTICAL);
        results.setPadding(Ui.dp(c, 12), Ui.dp(c, 4), Ui.dp(c, 12), Ui.dp(c, 40));
        scroll.addView(results);
        col.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        loadAppShortcuts();

        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void afterTextChanged(Editable s) { removeCallbacks(run); postDelayed(run, 110); }
        });
        field.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int id, KeyEvent e) {
                String q = query();
                if (q.isEmpty()) return true;
                List<AppInfo> apps = matchApps(q, 1);
                if (!apps.isEmpty() && apps.get(0).label.toLowerCase(Locale.getDefault()).startsWith(q.toLowerCase(Locale.getDefault()))) {
                    remember(q);
                    launcher.launchApp(apps.get(0), null);
                    close();
                } else provider("web");
                return true;
            }
        });
        if (initial != null) { field.setText(initial); field.setSelection(field.length()); }
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void afterTextChanged(Editable s) { loadAppShortcuts(); }
        });
        render();
        Anim.popIn(col);
        field.requestFocus();
        postDelayed(new Runnable() {
            @Override public void run() {
                InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
            }
        }, 150);
    }

    private String query() { return field.getText().toString().trim(); }

    private TextView chip(String label, final String provider) {
        TextView t = Ui.text(getContext(), label, 13, pal.text);
        t.setPadding(Ui.dp(getContext(), 14), Ui.dp(getContext(), 7), Ui.dp(getContext(), 14), Ui.dp(getContext(), 7));
        t.setBackground(Ui.pressable(getContext(), pal.surfaceAlt, Ui.dp(getContext(), 18)));
        t.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { provider(provider); } });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = Ui.dp(getContext(), 8);
        t.setLayoutParams(lp);
        return t;
    }

    // ------------------------------------------------------------------ providers (open other apps)

    private String engine() { return Prefs.sp(getContext()).getString("search_engine", "google"); }

    private String engineName() {
        switch (engine()) {
            case "duckduckgo": return "DuckDuckGo";
            case "bing": return "Bing";
            case "brave": return "Brave Search";
            case "startpage": return "Startpage";
            default: return "Google";
        }
    }

    private static String webUrl(String engine, String q) {
        String e = Uri.encode(q);
        switch (engine) {
            case "duckduckgo": return "https://duckduckgo.com/?q=" + e;
            case "bing": return "https://www.bing.com/search?q=" + e;
            case "brave": return "https://search.brave.com/search?q=" + e;
            case "startpage": return "https://www.startpage.com/do/search?q=" + e;
            default: return "https://www.google.com/search?q=" + e;
        }
    }

    private void provider(String which) {
        String q = query();
        if (q.isEmpty()) { field.requestFocus(); return; }
        remember(q);
        Context c = getContext();
        Intent i;
        switch (which) {
            case "youtube":
                i = new Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.youtube").putExtra(SearchManager.QUERY, q);
                if (!start(i)) start(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))));
                break;
            case "ytmusic":
                i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/search?q=" + Uri.encode(q)))
                        .setPackage("com.google.android.apps.youtube.music");
                if (!start(i)) start(new Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/search?q=" + Uri.encode(q))));
                break;
            case "play":
                if (!start(new Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=" + Uri.encode(q)))))
                    start(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/search?q=" + Uri.encode(q))));
                break;
            case "maps":
                start(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(q))));
                break;
            case "url": {
                String u = q.contains("://") ? q : "https://" + q;
                start(new Intent(Intent.ACTION_VIEW, Uri.parse(u)));
                break;
            }
            default:
                start(new Intent(Intent.ACTION_VIEW, Uri.parse(webUrl(engine(), q))));
        }
        close();
    }

    private boolean start(Intent i) {
        try {
            getContext().startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ history

    private List<String> history() {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(Prefs.sp(getContext()).getString("recent_searches", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (Exception ignored) { }
        return out;
    }

    private void remember(String q) {
        if (!Prefs.sp(getContext()).getBoolean("search_history", true)) return;
        List<String> h = history();
        h.remove(q);
        h.add(0, q);
        while (h.size() > 8) h.remove(h.size() - 1);
        JSONArray a = new JSONArray();
        for (String s : h) a.put(s);
        Prefs.sp(getContext()).edit().putString("recent_searches", a.toString()).apply();
    }

    // ------------------------------------------------------------------ results

    private LinearLayout section(String title) {
        Context c = getContext();
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.round(pal.surface, Ui.dp(c, 24)));
        card.setPadding(Ui.dp(c, 8), Ui.dp(c, 10), Ui.dp(c, 8), Ui.dp(c, 8));
        TextView t = Ui.text(c, title, 13, pal.accent);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(Ui.dp(c, 10), 0, 0, Ui.dp(c, 4));
        card.addView(t);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(c, 10);
        results.addView(card, lp);
        return card;
    }

    private View resultRow(int icon, android.graphics.drawable.Drawable iconD, String title, String sub, OnClickListener l) {
        Context c = getContext();
        LinearLayout r = new LinearLayout(c);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(c, 10), Ui.dp(c, 10), Ui.dp(c, 10), Ui.dp(c, 10));
        r.setBackground(Ui.pressable(c, 0x00000000, Ui.dp(c, 16)));
        ImageView iv = new ImageView(c);
        if (iconD != null) iv.setImageDrawable(iconD);
        else {
            iv.setImageDrawable(Ui.tinted(c, icon, pal.accent));
            int p = Ui.dp(c, 8);
            iv.setPadding(p, p, p, p);
            iv.setBackground(Ui.round(Ui.alpha(pal.accent, 0x22), Ui.dp(c, 20)));
        }
        r.addView(iv, new LinearLayout.LayoutParams(Ui.dp(c, 38), Ui.dp(c, 38)));
        LinearLayout t = new LinearLayout(c);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(Ui.dp(c, 14), 0, 0, 0);
        TextView a = Ui.text(c, title, 15, pal.text);
        a.setSingleLine(true);
        a.setEllipsize(TextUtils.TruncateAt.END);
        t.addView(a);
        if (sub != null && !sub.isEmpty()) {
            TextView b = Ui.text(c, sub, 12, pal.subtext);
            b.setSingleLine(true);
            b.setEllipsize(TextUtils.TruncateAt.END);
            t.addView(b);
        }
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        r.setOnClickListener(l);
        return r;
    }

    private List<AppInfo> matchApps(String q, int max) {
        String lq = q.toLowerCase(Locale.getDefault());
        List<AppInfo> starts = new ArrayList<>(), contains = new ArrayList<>();
        for (AppInfo a : AppRepository.get(getContext()).visible()) {
            String l = a.label.toLowerCase(Locale.getDefault());
            if (l.startsWith(lq)) starts.add(a);
            else if (l.contains(lq)) contains.add(a);
        }
        starts.addAll(contains);
        return starts.size() > max ? starts.subList(0, max) : starts;
    }

    private void appsGrid(List<AppInfo> apps) {
        LinearLayout card = section("Apps");
        // Same column count as the drawer, so icons don't shrink while you search.
        int cols = embedded ? Math.max(3, Prefs.drawerCols(getContext())) : 4;
        if (embedded) card.setPadding(0, card.getPaddingTop(), 0, card.getPaddingBottom());
        LinearLayout row = null;
        for (int i = 0; i < apps.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(getContext());
                card.addView(row);
            }
            IconView v = new IconView(getContext(), launcher, IconView.STYLE_DRAWER);
            v.bind(apps.get(i).icon, apps.get(i).label);
            v.setTag(apps.get(i));
            row.addView(v, new LinearLayout.LayoutParams(0, v.desiredHeight(), 1));
        }
        if (row != null) for (int i = row.getChildCount(); i < cols; i++) row.addView(new View(getContext()), new LinearLayout.LayoutParams(0, 1, 1));
    }

    private void render() {
        final Context c = getContext();
        results.removeAllViews();
        final String q = query();
        if (q.isEmpty() && embedded) return;
        if (q.isEmpty()) {
            List<AppInfo> top = AppRepository.get(c).topUsed(8);
            if (!top.isEmpty()) appsGrid(top);
            List<String> h = history();
            if (!h.isEmpty()) {
                LinearLayout card = section("Recent searches");
                for (final String s : h) {
                    card.addView(resultRow(R.drawable.ic_refresh, null, s, null, new OnClickListener() {
                        @Override public void onClick(View v) { field.setText(s); field.setSelection(s.length()); }
                    }));
                }
            }
            return;
        }
        String lq = q.toLowerCase(Locale.getDefault());

        // Calculator
        Double val = Calc.eval(q);
        if (val != null) {
            final String out = Calc.format(val);
            LinearLayout card = section("Calculator");
            card.addView(resultRow(R.drawable.ic_check, null, "= " + out, "Tap to copy", new OnClickListener() {
                @Override public void onClick(View v) {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("result", out));
                    Ui.toast(c, "Copied " + out);
                }
            }));
        }

        List<AppInfo> apps = matchApps(q, embedded ? Math.max(3, Prefs.drawerCols(c)) * 2 : 8);
        if (!apps.isEmpty()) appsGrid(apps);

        // Actions inside other apps (their app shortcuts: "New message", "Navigate home", "New tab"…)
        if (Prefs.sp(c).getBoolean("search_app_actions", true)) appActions(lq);

        // Shortcuts saved to the drawer
        List<View> sc = new ArrayList<>();
        for (final DrawerShortcuts.Entry e : DrawerShortcuts.all(c)) {
            if (!e.title.toLowerCase(Locale.getDefault()).contains(lq)) continue;
            sc.add(resultRow(0, e.icon, e.title, "Shortcut", new OnClickListener() {
                @Override public void onClick(View v) { launcher.launchDrawerShortcut(e, v); close(); }
            }));
            if (sc.size() >= 4) break;
        }
        if (!sc.isEmpty()) { LinearLayout card = section("Shortcuts"); for (View v : sc) card.addView(v); }

        if (Prefs.sp(c).getBoolean("search_contacts", true)) contacts(q);

        if (Prefs.sp(c).getBoolean("search_settings", true)) {
            List<View> rows = new ArrayList<>();
            for (final String[] s : SETTINGS) {
                if (!(s[0].toLowerCase(Locale.getDefault()).contains(lq) || s[1].contains(lq))) continue;
                rows.add(resultRow(R.drawable.ic_settings, null, s[0], "Phone settings", new OnClickListener() {
                    @Override public void onClick(View v) {
                        if ("canopy".equals(s[2])) launcher.openSettings();
                        else start(new Intent(s[2]));
                        close();
                    }
                }));
                if (rows.size() >= 5) break;
            }
            if (!rows.isEmpty()) { LinearLayout card = section("Settings"); for (View v : rows) card.addView(v); }
        }

        if (Prefs.sp(c).getBoolean("search_calendar", true)) calendar(q);

        LinearLayout web = section("Search on");
        if (q.matches("^(https?://)?[\\w.-]+\\.[a-z]{2,}(/\\S*)?$") && !q.contains(" ")) {
            web.addView(resultRow(R.drawable.ic_shortcut, null, "Open " + q, "Website", new OnClickListener() {
                @Override public void onClick(View v) { provider("url"); }
            }));
        }
        web.addView(resultRow(R.drawable.ic_search, null, "Search " + engineName() + " for “" + q + "”", "Opens in your browser", new OnClickListener() {
            @Override public void onClick(View v) { provider("web"); }
        }));
        web.addView(resultRow(R.drawable.ic_play, null, "Search YouTube", null, new OnClickListener() {
            @Override public void onClick(View v) { provider("youtube"); }
        }));
        web.addView(resultRow(R.drawable.ic_bell, null, "Search YouTube Music", null, new OnClickListener() {
            @Override public void onClick(View v) { provider("ytmusic"); }
        }));
        web.addView(resultRow(R.drawable.ic_apps, null, "Search Play Store", null, new OnClickListener() {
            @Override public void onClick(View v) { provider("play"); }
        }));
    }

    // ------------------------------------------------------------------ results from other apps

    private static volatile List<Object[]> appShortcuts; // {ShortcutInfo, UserHandle, appLabel}
    private static long appShortcutsAt;
    private static boolean appShortcutsLoading;

    /** Loads every app's shortcuts in the background (cached for a minute) so they can be searched as you type. */
    private void loadAppShortcuts() {
        if (appShortcutsLoading || (appShortcuts != null && System.currentTimeMillis() - appShortcutsAt < 60000)) return;
        appShortcutsLoading = true;
        final Context app = getContext().getApplicationContext();
        App.IO.execute(new Runnable() {
            @Override public void run() {
                final List<Object[]> out = new ArrayList<>();
                try {
                    LauncherApps la = (LauncherApps) app.getSystemService(Context.LAUNCHER_APPS_SERVICE);
                    if (la.hasShortcutHostPermission()) {
                        UserManager um = (UserManager) app.getSystemService(Context.USER_SERVICE);
                        PackageManager pm = app.getPackageManager();
                        java.util.Map<String, String> labels = new java.util.HashMap<>();
                        for (UserHandle u : um.getUserProfiles()) {
                            LauncherApps.ShortcutQuery sq = new LauncherApps.ShortcutQuery();
                            sq.setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC | LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST
                                    | LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED);
                            List<ShortcutInfo> list;
                            try { list = la.getShortcuts(sq, u); } catch (Exception e) { continue; }
                            if (list == null) continue;
                            for (ShortcutInfo si : list) {
                                if (!si.isEnabled()) continue;
                                String lbl = labels.get(si.getPackage());
                                if (lbl == null) {
                                    try { lbl = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(si.getPackage(), 0))); }
                                    catch (Exception e) { lbl = si.getPackage(); }
                                    labels.put(si.getPackage(), lbl);
                                }
                                out.add(new Object[]{si, u, lbl});
                            }
                        }
                    }
                } catch (Exception ignored) { }
                App.MAIN.post(new Runnable() {
                    @Override public void run() {
                        appShortcuts = out;
                        appShortcutsAt = System.currentTimeMillis();
                        appShortcutsLoading = false;
                        if (isAttachedToWindow() && !query().isEmpty()) render();
                    }
                });
            }
        });
    }

    private void appActions(String lq) {
        final Context c = getContext();
        List<Object[]> all = appShortcuts;
        if (all == null || lq.isEmpty()) return;
        final LauncherApps la = (LauncherApps) c.getSystemService(Context.LAUNCHER_APPS_SERVICE);
        int density = getResources().getDisplayMetrics().densityDpi;
        List<Object[]> first = new ArrayList<>(), second = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Object[] o : all) {
            ShortcutInfo si = (ShortcutInfo) o[0];
            String app = ((String) o[2]).toLowerCase(Locale.getDefault());
            String sl = si.getShortLabel() != null ? si.getShortLabel().toString().toLowerCase(Locale.getDefault()) : "";
            String ll = si.getLongLabel() != null ? si.getLongLabel().toString().toLowerCase(Locale.getDefault()) : "";
            if (!seen.add(si.getPackage() + "/" + si.getId() + "/" + o[1])) continue;
            if (sl.startsWith(lq) || ll.startsWith(lq) || sl.contains(" " + lq)) first.add(o);
            else if (sl.contains(lq) || ll.contains(lq) || app.startsWith(lq)) second.add(o);
        }
        first.addAll(second);
        List<View> rows = new ArrayList<>();
        for (Object[] o : first) {
            if (rows.size() >= 6) break;
            final ShortcutInfo si = (ShortcutInfo) o[0];
            final UserHandle user = (UserHandle) o[1];
            android.graphics.drawable.Drawable d = null;
            try { d = la.getShortcutIconDrawable(si, density); } catch (Exception ignored) { }
            CharSequence label = si.getLongLabel() != null && si.getLongLabel().length() <= 40 ? si.getLongLabel() : si.getShortLabel();
            View row = resultRow(R.drawable.ic_shortcut, d, String.valueOf(label), (String) o[2], new OnClickListener() {
                @Override public void onClick(View v) {
                    remember(query());
                    try { la.startShortcut(si.getPackage(), si.getId(), null, null, user); }
                    catch (Exception e) { Ui.toast(c, "Can't open that"); return; }
                    close();
                }
            });
            if (row instanceof LinearLayout) {
                ImageView plus = Ui.iconButton(c, R.drawable.ic_add, pal.accent, new OnClickListener() {
                    @Override public void onClick(View v) { launcher.pinShortcutToHome(si, user); }
                });
                plus.setContentDescription("Add to home screen or drawer");
                ((LinearLayout) row).addView(plus, new LinearLayout.LayoutParams(Ui.dp(c, 40), Ui.dp(c, 40)));
            }
            rows.add(row);
        }
        if (!rows.isEmpty()) { LinearLayout card = section("In your apps"); for (View v : rows) card.addView(v); }
    }

    private void contacts(String q) {
        final Context c = getContext();
        if (c.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            LinearLayout card = section("Contacts");
            card.addView(resultRow(R.drawable.ic_info, null, "Search your contacts too", "Tap to allow (stays on this phone)", new OnClickListener() {
                @Override public void onClick(View v) { launcher.requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, 17); }
            }));
            return;
        }
        List<View> rows = new ArrayList<>();
        Cursor cur = null;
        try {
            Uri uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_FILTER_URI, Uri.encode(q));
            cur = c.getContentResolver().query(uri, new String[]{ContactsContract.Contacts._ID, ContactsContract.Contacts.LOOKUP_KEY,
                    ContactsContract.Contacts.DISPLAY_NAME, ContactsContract.Contacts.PHOTO_THUMBNAIL_URI}, null, null, null);
            while (cur != null && cur.moveToNext() && rows.size() < 5) {
                final long id = cur.getLong(0);
                final String lookup = cur.getString(1);
                String name = cur.getString(2);
                String photo = cur.getString(3);
                android.graphics.drawable.Drawable d = null;
                if (photo != null) {
                    try {
                        java.io.InputStream in = c.getContentResolver().openInputStream(Uri.parse(photo));
                        android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeStream(in);
                        if (in != null) in.close();
                        if (bm != null) d = new android.graphics.drawable.BitmapDrawable(c.getResources(), bm);
                    } catch (Exception ignored) { }
                }
                final String number = phoneOf(id);
                View row = resultRow(R.drawable.ic_info, d, name, number != null ? number : "Contact", new OnClickListener() {
                    @Override public void onClick(View v) {
                        start(new Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.getLookupUri(id, lookup)));
                        close();
                    }
                });
                if (number != null && row instanceof LinearLayout) {
                    ((LinearLayout) row).addView(Ui.iconButton(c, R.drawable.ic_shortcut, pal.accent, new OnClickListener() {
                        @Override public void onClick(View v) {
                            start(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))));
                            close();
                        }
                    }), new LinearLayout.LayoutParams(Ui.dp(c, 40), Ui.dp(c, 40)));
                }
                rows.add(row);
            }
        } catch (Exception ignored) {
        } finally {
            if (cur != null) cur.close();
        }
        if (!rows.isEmpty()) { LinearLayout card = section("Contacts"); for (View v : rows) card.addView(v); }
    }

    private String phoneOf(long contactId) {
        Cursor p = null;
        try {
            p = getContext().getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[]{ContactsContract.CommonDataKinds.Phone.NUMBER},
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID + "=?", new String[]{String.valueOf(contactId)}, null);
            if (p != null && p.moveToFirst()) return p.getString(0);
        } catch (Exception ignored) {
        } finally {
            if (p != null) p.close();
        }
        return null;
    }

    private void calendar(String q) {
        final Context c = getContext();
        if (c.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return;
        long now = System.currentTimeMillis();
        Uri.Builder b = CalendarContract.Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(b, now - 30 * DateUtils.DAY_IN_MILLIS);
        ContentUris.appendId(b, now + 365 * DateUtils.DAY_IN_MILLIS);
        List<View> rows = new ArrayList<>();
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(b.build(), new String[]{CalendarContract.Instances.TITLE,
                    CalendarContract.Instances.BEGIN, CalendarContract.Instances.EVENT_ID},
                    CalendarContract.Instances.TITLE + " LIKE ?", new String[]{"%" + q + "%"}, CalendarContract.Instances.BEGIN + " ASC");
            while (cur != null && cur.moveToNext() && rows.size() < 4) {
                String title = cur.getString(0);
                long begin = cur.getLong(1);
                final long ev = cur.getLong(2);
                rows.add(resultRow(R.drawable.ic_feed, null, title, DateUtils.formatDateTime(c, begin,
                        DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_ABBREV_MONTH), new OnClickListener() {
                    @Override public void onClick(View v) {
                        start(new Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, ev)));
                        close();
                    }
                }));
            }
        } catch (Exception ignored) {
        } finally {
            if (cur != null) cur.close();
        }
        if (!rows.isEmpty()) { LinearLayout card = section("Calendar"); for (View v : rows) card.addView(v); }
    }

    public void refreshResults() { render(); }

    public void close() {
        if (embedded) { if (onClose != null) onClose.run(); return; }
        if (getParent() == null) return;
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(getWindowToken(), 0);
        launcher.onSearchClosed(this);
        animate().alpha(0f).translationY(Ui.dp(getContext(), 30)).setDuration(Anim.dur(getContext(), 160))
                .withEndAction(new Runnable() {
                    @Override public void run() { if (getParent() != null) ((android.view.ViewGroup) getParent()).removeView(SearchOverlay.this); }
                }).start();
    }

    /** Tiny, safe arithmetic evaluator for the calculator result (+ − × ÷ ^ % and brackets). */
    static final class Calc {
        private final String s;
        private int i;

        private Calc(String s) { this.s = s; }

        static Double eval(String q) {
            String t = q.replace(" ", "").replace('×', '*').replace('x', '*').replace('÷', '/').replace(',', '.');
            if (!t.matches("[0-9.+\\-*/^%()]+") || !t.matches(".*[0-9].*") || !t.matches(".*[+\\-*/^%].*") || t.length() > 80) return null;
            try {
                Calc c = new Calc(t);
                double v = c.expr();
                if (c.i != t.length() || Double.isNaN(v) || Double.isInfinite(v)) return null;
                return v;
            } catch (Exception e) {
                return null;
            }
        }

        static String format(double v) {
            if (v == Math.rint(v) && Math.abs(v) < 1e15) return String.valueOf((long) v);
            return String.format(Locale.ROOT, "%.10f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
        }

        private double expr() {
            double v = term();
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '+') { i++; v += term(); } else if (c == '-') { i++; v -= term(); } else break;
            }
            return v;
        }

        private double term() {
            double v = power();
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '*') { i++; v *= power(); }
                else if (c == '/') { i++; v /= power(); }
                else if (c == '%') { i++; v = v / 100.0 * (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '(') ? power() : 1); }
                else break;
            }
            return v;
        }

        private double power() {
            double b = unary();
            if (i < s.length() && s.charAt(i) == '^') { i++; return Math.pow(b, power()); }
            return b;
        }

        private double unary() {
            if (i < s.length() && s.charAt(i) == '-') { i++; return -unary(); }
            if (i < s.length() && s.charAt(i) == '+') { i++; return unary(); }
            if (i < s.length() && s.charAt(i) == '(') {
                i++;
                double v = expr();
                if (i < s.length() && s.charAt(i) == ')') i++;
                else throw new IllegalArgumentException();
                return v;
            }
            int st = i;
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
            if (st == i) throw new IllegalArgumentException();
            return Double.parseDouble(s.substring(st, i));
        }
    }
}
