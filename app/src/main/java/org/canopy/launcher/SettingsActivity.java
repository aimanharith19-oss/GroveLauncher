/*
 * Copyright 2026 The Canopy Launcher authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.canopy.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Nova-style settings, built in code. */
public class SettingsActivity extends Activity {
    private static final int REQ_EXPORT = 1, REQ_IMPORT = 2, REQ_WALL3D = 3, REQ_SUBJECT = 4;

    private Ui.Palette pal;
    private LinearLayout list;
    private SharedPreferences sp;

    @Override protected void onCreate(Bundle b) {
        setTheme(Ui.night(this) ? R.style.Theme_Canopy_SettingsDark : R.style.Theme_Canopy_Settings);
        super.onCreate(b);
        pal = Ui.palette(this);
        sp = Prefs.sp(this);
        // Opened by the installer's "Open" button: go to the home screen instead of settings.
        if (b == null && openedByInstaller()) {
            startActivity(new Intent(this, LauncherActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
            return;
        }
        // Like the feed: the wallpaper shows through, blurred and tinted.
        boolean blur = false;
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            try {
                if (getWindowManager().isCrossWindowBlurEnabled()) { getWindow().setBackgroundBlurRadius(Ui.dp(this, 40)); blur = true; }
            } catch (Throwable ignored) { }
        }
        android.view.Window win = getWindow();
        win.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        win.setStatusBarColor(0x00000000);
        win.setNavigationBarColor(0x00000000);
        // Full screen: the page reaches from under the status bar (notification area) down under the navigation bar.
        int ui = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if (!pal.night) ui |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        win.getDecorView().setSystemUiVisibility(ui);
        if (android.os.Build.VERSION.SDK_INT >= 29) { win.setNavigationBarContrastEnforced(false); win.setStatusBarContrastEnforced(false); }
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            android.view.WindowManager.LayoutParams wl = win.getAttributes();
            wl.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            win.setAttributes(wl);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.alpha(pal.bg, blur ? 0xB0 : 0xE0));

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 16), Ui.dp(this, 4));
        bar.addView(Ui.iconButton(this, R.drawable.ic_back, pal.text, new View.OnClickListener() {
            @Override public void onClick(View v) { onBackPressed(); }
        }), new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        titleView = Ui.text(this, "Grove settings", 28, pal.text);
        titleView.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        bar.addView(titleView);
        root.addView(bar);

        searchField = new EditText(this);

        Ui.tintField(this, searchField);
        searchField.setHint("Search settings");
        Ui.styleField(this, searchField, pal);
        searchField.setTextColor(pal.text);
        searchField.setTextAppearance(android.R.style.TextAppearance_Material_Subhead);
        searchField.setTextColor(pal.text);
        searchField.setHintTextColor(pal.subtext);
        searchField.setHighlightColor(Ui.alpha(pal.accent, 0x55));
        searchField.setCompoundDrawablesRelativeWithIntrinsicBounds(Ui.tinted(this, R.drawable.ic_search, pal.subtext), null, null, null);
        searchField.setCompoundDrawablePadding(Ui.dp(this, 10));
        LinearLayout.LayoutParams sflp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 48));
        sflp.setMargins(Ui.dp(this, 16), Ui.dp(this, 2), Ui.dp(this, 16), Ui.dp(this, 8));
        root.addView(searchField, sflp);
        searchField.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable s) { query = s.toString().trim(); build(); }
        });

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 32));
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        scroll.setClipToPadding(false);
        final ScrollView fScroll = scroll;
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public android.view.WindowInsets onApplyWindowInsets(View v, android.view.WindowInsets in) {
                // top: below the status bar; bottom: the list scrolls on under the navigation bar
                v.setPadding(in.getSystemWindowInsetLeft(), in.getSystemWindowInsetTop(), in.getSystemWindowInsetRight(), 0);
                fScroll.setPadding(0, 0, 0, in.getSystemWindowInsetBottom());
                return in;
            }
        });
        setContentView(root);
        build();
    }

    // Nova-style navigation: a main page of categories, each opening its own page.
    private String page;
    private String query = "";
    private TextView titleView;
    private EditText searchField;
    private final List<Object[]> sections = new java.util.ArrayList<>();

    private static final String[][] CATEGORIES = {
            {"Home screen", "Grid, main page, transitions, search bar, widgets", "home"},
            {"Dock", "Icons, background, swipe-up panel", "dock"},
            {"App drawer", "Grid size, tabs, folders, sorting, hidden apps", "apps"},
            {"Feed (swipe right from home)", "Cards, widgets, greeting", "feed"},
            {"Look & feel", "Theme, colours, icons, labels, folders, blur", "palette"},
            {"Animations", "Style, speed, haptics", "motion"},
            {"Gestures", "Swipes, pinch, double tap, accessibility", "gesture"},
            {"Feed browser", "Web tabs: autoplay, background play, ad blocker", "browser"},
            {"Search", "Search engine, contacts, settings, calendar, history", "search"},
            {"Notification badges", "Dots or counts on icons", "bell"},
            {"System", "Default launcher, backup & restore", "backup"},
            {"Privacy & about", "Privacy promise, licences, version", "lock"},
    };

    private int iconFor(String key) {
        switch (key) {
            case "home": return R.drawable.ic_home;
            case "dock": return R.drawable.ic_dock;
            case "apps": return R.drawable.ic_apps;
            case "feed": return R.drawable.ic_feed;
            case "palette": return R.drawable.ic_palette;
            case "motion": return R.drawable.ic_motion;
            case "gesture": return R.drawable.ic_gesture;
            case "bell": return R.drawable.ic_bell;
            case "search": return R.drawable.ic_search;
            case "browser": return R.drawable.ic_network;
            case "shortcut": return R.drawable.ic_shortcut;
            case "hide": return R.drawable.ic_hide;
            case "layers": return R.drawable.ic_layers;
            case "wallpaper": return R.drawable.ic_wallpaper;
            case "widgets": return R.drawable.ic_widgets;
            case "folder": return R.drawable.ic_folder;
            case "resize": return R.drawable.ic_resize;
            case "backup": return R.drawable.ic_refresh;
            default: return R.drawable.ic_lock;
        }
    }

    @Override public void onBackPressed() {
        if (page != null) { page = null; build(); return; }
        if (searchField != null && searchField.length() > 0) { searchField.setText(""); return; }
        super.onBackPressed();
    }

    private static final int[] TILE_COLOURS = {0xFF2E7D6B, 0xFF5C6BC0, 0xFF00897B, 0xFFEF6C00, 0xFFAD1457, 0xFF6A1B9A,
            0xFF1565C0, 0xFF0277BD, 0xFF558B2F, 0xFFC62828, 0xFF455A64, 0xFF8D6E63};

    /** The main page: a two-column grid of big tiles, each with its own colour, so a category is found at a glance. */
    private void buildMain() {
        for (int i = 0; i < CATEGORIES.length; i += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setBaselineAligned(false);
            for (int k = 0; k < 2; k++) {
                int idx = i + k;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
                lp.leftMargin = k == 1 ? Ui.dp(this, 5) : 0;
                lp.rightMargin = k == 0 ? Ui.dp(this, 5) : 0;
                if (idx < CATEGORIES.length) row.addView(tile(CATEGORIES[idx], TILE_COLOURS[idx % TILE_COLOURS.length]), lp);
                else row.addView(new View(this), lp);
            }
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
            rp.topMargin = Ui.dp(this, 10);
            list.addView(row, rp);
        }
    }

    private View tile(String[] cat, int colour) {
        final String name = cat[0];
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 16);
        t.setPadding(p, p, p, p);
        t.setBackground(Ui.pressable(this, pal.surface, Ui.dp(this, 24)));
        android.widget.ImageView iv = new android.widget.ImageView(this);
        iv.setImageDrawable(Ui.tinted(this, iconFor(cat[2]), 0xFFFFFFFF));
        int ip = Ui.dp(this, 11);
        iv.setPadding(ip, ip, ip, ip);
        iv.setBackground(Ui.round(colour, Ui.dp(this, 26)));
        t.addView(iv, new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52)));
        TextView title = Ui.text(this, name.replace(" (swipe right from home)", ""), 16, pal.text);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 2));
        t.addView(title);
        TextView sub = Ui.text(this, cat[1], 12, pal.subtext);
        sub.setMaxLines(3);
        t.addView(sub);
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { page = name; build(); }
        });
        return t;
    }

    /** Keep only the chosen category, or only rows matching the search. */
    private void prune() {
        List<View> keep = new java.util.ArrayList<>();
        String q = query.toLowerCase(java.util.Locale.getDefault());
        for (int i = 0; i < list.getChildCount(); i++) {
            View v = list.getChildAt(i);
            String section = null;
            boolean isHeader = false;
            for (Object[] s : sections) {
                int start = (Integer) s[0];
                if (i >= start) section = (String) s[1];
                if (i == start) isHeader = true;
            }
            if (page != null) {
                if (!isHeader && page.equals(section)) keep.add(v);
            } else {
                Object tag = v.getTag();
                if (!isHeader && tag instanceof String && ((String) tag).toLowerCase(java.util.Locale.getDefault()).contains(q)) keep.add(v);
            }
        }
        list.removeAllViews();
        for (View v : keep) list.addView(v);
        if (keep.isEmpty()) {
            TextView none = Ui.text(this, "No settings match \u201C" + query + "\u201D", 14, pal.subtext);
            none.setPadding(Ui.dp(this, 8), Ui.dp(this, 24), 0, 0);
            list.addView(none);
        }
    }

    /** Android 12+-style grouped cards: neighbouring rows share one rounded card. */
    private void groupRows() {
        float big = Ui.dp(this, 24), small = Ui.dp(this, 5);
        int n = list.getChildCount();
        for (int i = 0; i < n; i++) {
            View v = list.getChildAt(i);
            if (!(v.getTag() instanceof String)) continue;
            boolean prev = i > 0 && list.getChildAt(i - 1).getTag() instanceof String;
            boolean next = i < n - 1 && list.getChildAt(i + 1).getTag() instanceof String;
            float top = prev ? small : big, bottom = next ? small : big;
            v.setBackground(Ui.pressable(this, pal.surface, top, top, bottom, bottom));
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) v.getLayoutParams();
            lp.topMargin = prev ? Ui.dp(this, 2) : Ui.dp(this, 10);
            v.setLayoutParams(lp);
            v.setMinimumHeight(Ui.dp(this, 64));
            int p = Ui.dp(this, 18);
            v.setPadding(p, Ui.dp(this, 14), p, Ui.dp(this, 14));
        }
    }

    /** "Open websites in" (any installed browser) and "Downloads go to" (any downloader app that takes web links). */
    private void browserChoices() {
        android.content.pm.PackageManager pm = getPackageManager();
        List<String> bv = new ArrayList<>(), bl = new ArrayList<>(), dv = new ArrayList<>(), dl = new ArrayList<>();
        bv.add("app"); bl.add("In Grove's feed (default)");
        bv.add("ask"); bl.add("Ask every time");
        dv.add("system"); dl.add("Android's download manager (default)");
        dv.add("ask"); dl.add("Ask every time");
        try {
            Intent web = new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE);
            for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(web, 0)) {
                String p = ri.activityInfo.packageName;
                if (p.equals(getPackageName()) || bv.contains(p)) continue;
                bv.add(p); bl.add(p.equals(WebHistory.BROWSER_PKG) ? "Grove Browser (Gecko)" : String.valueOf(ri.loadLabel(pm)));
            }
            Intent file = new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("https://example.com/file.bin"), "application/octet-stream");
            for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(file, 0)) {
                String p = ri.activityInfo.packageName;
                if (p.equals(getPackageName()) || dv.contains(p)) continue;
                dv.add(p); dl.add(String.valueOf(ri.loadLabel(pm)));
            }
            for (String p : new String[]{"com.dv.adm", "com.dv.adm.pay", "idm.internet.download.manager", "idm.internet.download.manager.plus", "org.freedownloadmanager.fdm"}) {
                if (dv.contains(p)) continue;
                try { pm.getPackageInfo(p, 0); dv.add(p); dl.add(String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(p, 0)))); } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
        choice("Open websites in", "web_open", bv.toArray(new String[0]), bl.toArray(new String[0]), "app");
        choice("Downloads go to", "web_downloader", dv.toArray(new String[0]), dl.toArray(new String[0]), "system");
    }

    private static String clock(int m) { return String.format(java.util.Locale.US, "%02d:%02d", m / 60, m % 60); }

    private void pickTime(final String key, int def) {
        int v = sp.getInt(key, def);
        new android.app.TimePickerDialog(this, new android.app.TimePickerDialog.OnTimeSetListener() {
            @Override public void onTimeSet(android.widget.TimePicker p, int h, int m) { sp.edit().putInt(key, h * 60 + m).apply(); }
        }, v / 60, v % 60, true).show();
    }

    /** Builds the current page; a failure in one row can never leave the page blank. */
    private void build() {
        list.removeAllViews();
        sections.clear();
        titleView.setText(page == null ? "Grove settings" : page.replace(" (swipe right from home)", ""));
        searchField.setVisibility(page == null ? View.VISIBLE : View.GONE);
        if (page == null && query.isEmpty()) {
            buildMain();
            addCrashRows();
            return;
        }
        try {
            buildRows();
            prune();
            groupRows();
        } catch (Throwable t) {
            TextView err = Ui.text(this, "Some settings couldn't be shown: " + t, 14, 0xFFE53935);
            err.setPadding(Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16));
            list.addView(err);
        }
    }

    private void addCrashRows() {
        String crash = CrashLog.read(this);
        if (crash != null) {
            header("Last crash report");
            final String report = crash;
            action("Copy crash report", "Tap to copy it, then send it to the developer", new Runnable() {
                @Override public void run() {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Grove crash", report));
                    Ui.toast(SettingsActivity.this, "Copied");
                }
            });
            action("Clear crash report", null, new Runnable() {
                @Override public void run() { CrashLog.clear(SettingsActivity.this); build(); }
            });
        }
    }

    private void buildRows() {

        header("Home screen");
        final int pages = HomeStore.get(this).pageCount();
        String[] pageKeys = new String[pages], pageLabels = new String[pages];
        for (int i = 0; i < pages; i++) { pageKeys[i] = String.valueOf(i); pageLabels[i] = "Page " + (i + 1); }
        intChoice("Main home page (Home button returns here)", "main_page", pageLabels, 0);
        toggle("Allow home screen rotation", "allow_rotation", false);
        slider("Grid columns", "grid_cols", 2, 12, 5, "");
        slider("Grid rows", "grid_rows", 2, 12, 6, "");
        slider("Icon size", "icon_scale", 60, 140, 100, "%");
        toggle("Show icon labels", "show_labels", true);
        slider("Label size", "label_size", 9, 16, 12, " sp");
        choice("Page transition", "transition", Prefs.TRANSITIONS, Prefs.TRANSITION_LABELS, "slide");
        toggle("Infinite scroll", "infinite_scroll", false);
        toggle("Page indicator", "page_indicator", true);
        toggle("Scroll wallpaper with pages", "wallpaper_scroll", true);
        choice("Search bar", "search_bar", new String[]{"bottom", "top", "none"}, new String[]{"Above the dock", "Top of screen", "Hidden"}, "bottom");
        toggle("Place icons freely (move and resize like widgets)", "icon_free", false);
        toggle("Hide status bar on the home screen", "hide_bar_home", Prefs.hideStatusBar(this));
        toggle("Hide status bar on the feed", "hide_bar_feed", Prefs.hideStatusBar(this));
        toggle("Hide status bar in the app drawer", "hide_bar_drawer", Prefs.hideStatusBar(this));
        toggle("Lock desktop (prevent changes)", "desktop_lock", false);
        toggle("Free widget placement (anywhere, even the screen edge)", "widget_free", true);
        toggle("Widgets can overlap", "widget_overlap", false);
        choice("Shortcuts added from browsers & apps go to", "pinned_target", new String[]{"ask", "home", "drawer", "both"},
                new String[]{"Ask every time", "Home screen", "App drawer", "Home screen and app drawer"}, "ask");
        TextView shortcutTip = Ui.text(this, "Add app shortcuts: long-press an app and tap + next to a shortcut, or long-press empty space \u2192 App shortcut.", 13, pal.subtext);
        shortcutTip.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8), 0);
        list.addView(shortcutTip);

        header("Dock");
        toggle("Show labels in dock", "dock_labels", false);
        toggle("Show dock", "dock_enabled", true);
        slider("Dock icons", "dock_count", 3, 7, 5, "");
        toggle("Dock background", "dock_bg", true);
        toggle("Swipe up dock for quick toggles & 2nd row", "dock_expand", true);

        header("App drawer");
        slider("Columns", "drawer_cols", 3, 8, 5, "");
        slider("Rows on screen (0 = automatic)", "drawer_rows", 0, 10, 0, "");
        slider("Icon size", "drawer_icon_scale", 60, 140, 100, "%");
        TextView drawerTip = Ui.text(this, "Tip: pinch inside the app drawer to change the number of columns.", 13, pal.subtext);
        drawerTip.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8), 0);
        list.addView(drawerTip);
        slider("Background opacity", "drawer_opacity", 40, 100, 92, "%");
        choice("Sort apps", "drawer_sort", new String[]{"az", "usage", "installed"},
                new String[]{"Alphabetical", "Most used first", "Newest installed first"}, "az");
        toggle("Lock drawer layout (long-press shows only shortcuts & widgets)", "drawer_locked", false);
        toggle("Show labels in drawer", "drawer_labels", true);
        toggle("Frequent apps row", "drawer_frequent", true);
        toggle("Wave A\u2013Z scroller", "drawer_alphabet", false);
        toggle("Live blur on the search bar (can make scrolling stutter)", "acrylic_blur", false);
        toggle("Apps in a tab only show in that tab", "tabs_hide_from_all", true);
        toggle("Open keyboard when drawer opens", "drawer_keyboard", false);
        action("Drawer tabs", groupSummary(true), new Runnable() { @Override public void run() { manageGroups(true); } });
        action("Drawer folders", groupSummary(false), new Runnable() { @Override public void run() { manageGroups(false); } });
        action("Hidden apps", hiddenSummary(), new Runnable() { @Override public void run() { hiddenApps(); } });

        header("Animations");
        if (android.os.Build.VERSION.SDK_INT >= 26 && !android.animation.ValueAnimator.areAnimatorsEnabled()) {
            action("\u26A0 Animations are turned off on this phone", "Android's \u201CRemove animations\u201D or \u201CAnimator duration scale: off\u201D is on, so no animation can play. Tap to open accessibility settings.", new Runnable() {
                @Override public void run() { startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
            });
        }
        choice("Animation style", "anim_style", new String[]{"fluid", "classic", "minimal"},
                new String[]{"Fluid (springy, Niagara-like)", "Classic (Nova-like)", "Minimal (reduced motion)"}, "fluid");
        slider("Animation speed", "anim_speed", 50, 200, 100, "%");
        choice("Page transition", "transition", Prefs.TRANSITIONS, Prefs.TRANSITION_LABELS, "slide");
        if (sp.getInt("home_pages", 1) < 2) {
            info("Page transitions play when you swipe between home screen pages. You have one page: long-press an empty spot on the home screen \u2192 Add page.");
        }
        toggle("Haptic feedback", "haptics", true);

        header("Look & feel");
        if (android.os.Build.VERSION.SDK_INT >= 31) toggle("Material You colours (from wallpaper)", "dynamic_color", true);
        toggle("Blur & dim behind feed, drawer and folders", "depth_blur", true);
        toggle("3D wallpaper (tilts as you move your phone)", "wall3d", true);
        if (Wallpaper3D.usesSystem(this) && !Wallpaper3D.canReadSystem(this)) {
            action("Allow Grove to read your wallpaper", "Needed to make the phone's wallpaper 3D (Android shows it as \u201CAll files access\u201D)", new Runnable() {
                @Override public void run() {
                    if (android.os.Build.VERSION.SDK_INT >= 33) {
                        try { startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName()))); }
                        catch (Exception e) { startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)); }
                    } else requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE}, 20);
                }
            });
        }
        slider("3D depth", "wall3d_depth", 10, 100, 60, "%");
        toggle("Stop the tilt effect in Battery Saver", "wall3d_powersave", true);
        action("Use a different picture for 3D", Wallpaper3D.usesSystem(this) ? "Now: your phone's wallpaper" : "Now: a picture you chose", new Runnable() {
            @Override public void run() { pick(REQ_WALL3D); }
        });
        if (!Wallpaper3D.usesSystem(this)) action("Use my phone's wallpaper again", null, new Runnable() {
            @Override public void run() {
                sp.edit().putString("wall3d_source", "system").apply();
                Wallpaper3D.file(SettingsActivity.this).delete();
                Ui.toast(SettingsActivity.this, "Back to your phone's wallpaper");
                needsRecreateLauncher();
                build();
            }
        });
        action(Wallpaper3D.hasSubject(this) ? "Change wallpaper subject layer" : "Add wallpaper subject layer",
                "A transparent PNG of the wallpaper's subject (use \u201CCopy subject\u201D / \u201CExtract object\u201D in your gallery). "
                        + "Then long-press a widget \u2192 Put behind wallpaper subject.", new Runnable() {
            @Override public void run() { pick(REQ_SUBJECT); }
        });
        if (Wallpaper3D.hasSubject(this)) action("Remove wallpaper subject layer", null, new Runnable() {
            @Override public void run() {
                Wallpaper3D.fgFile(SettingsActivity.this).delete();
                needsRecreateLauncher();
                build();
            }
        });
        choice("Home label colour", "label_color", new String[]{"auto", "light", "dark"}, new String[]{"Automatic (black on light wallpapers, white on dark)", "Always white", "Always black"}, "auto");
        toggle("Label shadow", "label_shadow", true);
        choice("Folder shape", "folder_shape", new String[]{"circle", "rounded", "square", "none"},
                new String[]{"Circle", "Rounded square", "Square", "No background"}, "circle");
        choice("Folder icon preview", "folder_preview", new String[]{"grid", "fan", "stack", "line"},
                new String[]{"Grid (2 \u00D7 2)", "Fan", "Stack", "Line"}, "grid");
        slider("Folder window columns (0 = automatic)", "folder_cols", 0, 5, 0, "");
        folderColours();
        choice("Dark mode", "theme", new String[]{"auto", "time", "light", "dark"},
                new String[]{"Follow system", "Follow the time of day", "Always light", "Always dark"}, "auto");
        if ("time".equals(Prefs.theme(this))) {
            action("Dark from", clock(sp.getInt("theme_from", 20 * 60)), new Runnable() { @Override public void run() { pickTime("theme_from", 20 * 60); } });
            action("Light again at", clock(sp.getInt("theme_to", 6 * 60)), new Runnable() { @Override public void run() { pickTime("theme_to", 6 * 60); } });
        }
        action("Accent colour", Prefs.dynamicColor(this) ? "Following your wallpaper (Material You)" : null, new Runnable() { @Override public void run() { accentPicker(); } });
        action("Icon pack", iconPackName(), new Runnable() { @Override public void run() { iconPacks(); } });
        choice("Icon shape", "icon_shape", IconShapes.KEYS, IconShapes.LABELS, "system");
        toggle("Reshape legacy icons", "wrap_legacy", true);

        header("Feed browser");
        toggle("Use Groveguard for ad blocking & web protection", "use_groveguard", true);
        info("When Groveguard is installed, it checks every page and request: ad, tracker, 18+ and dangerous-site blocking, your own "
                + "allow/block rules and the log are all in Groveguard \u2192 web. Without Groveguard, Grove's own ad blocker is used.");
        toggle("Show only the website icon on tabs", "web_icon_only", false);
        toggle("Play videos automatically", "web_autoplay", true);
        browserChoices();
        toggle("Link bookmarks & history with Grove Browser", "browser_link", true);
        action("Sync with Grove Browser now", WebHistory.browserInstalled(this) ? "Shares bookmarks and history both ways. Nothing is deleted."
                : "Install Grove Browser (the Gecko browser) to use this", new Runnable() {
            @Override public void run() {
                Ui.toast(SettingsActivity.this, "Linking\u2026");
                final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                App.IO.execute(new Runnable() {
                    @Override public void run() {
                        final String m = WebHistory.sync(SettingsActivity.this);
                        h.post(new Runnable() { @Override public void run() { Ui.toast(SettingsActivity.this, m); } });
                    }
                });
            }
        });
        toggle("Keep Grove running (never restarts, tabs never reload)", "keep_alive", false);
        if (sp.getBoolean("keep_alive", false)) action("Allow unrestricted battery use", "Stops Android from closing Grove to save power", new Runnable() {
            @Override public void run() {
                try { startActivity(new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()))); }
                catch (Exception e) { startActivity(new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
            }
        });
        choice("Websites in the background", "web_bg_mode", new String[]{"later", "always", "now"},
                new String[]{"Run 2 minutes, then freeze (saves battery)", "Keep running (uses more battery)", "Freeze right away (best battery)"},
                FeedWeb.bgMode(this));
        toggle("Keep videos & music playing in the background", "web_background", true);
        toggle("Block ads & trackers (needs Grove Guard)", "web_adblock", true);
        toggle("Block pop-ups, ad redirects and pages opening other apps", "web_block_popups", true);
        action("Filter lists, rules & history", "Ad blocking and website history live in Grove Guard \u2192 web. Tap to open it.", new Runnable() {
            @Override public void run() {
                try { startActivity(new Intent().setClassName("org.grove.guard", "org.grove.guard.MainActivity")); }
                catch (Exception e) { Ui.toast(SettingsActivity.this, "Grove Guard isn't installed"); }
            }
        });

        header("Search");
        choice("Web search engine", "search_engine", new String[]{"google", "duckduckgo", "bing", "brave", "startpage"},
                new String[]{"Google", "DuckDuckGo", "Bing", "Brave Search", "Startpage"}, "google");
        toggle("Search contacts", "search_contacts", true);
        toggle("Search phone settings", "search_settings", true);
        toggle("Search actions inside your apps (app shortcuts)", "search_app_actions", true);
        toggle("Search calendar events", "search_calendar", true);
        toggle("Remember recent searches", "search_history", true);
        action("Clear search history", null, new Runnable() {
            @Override public void run() { sp.edit().remove("recent_searches").apply(); Ui.toast(SettingsActivity.this, "Search history cleared"); }
        });
        TextView searchTip = Ui.text(this, "Web, YouTube, YouTube Music, Play Store and Maps searches open in those apps or your browser. "
                + "Grove itself sends no data anywhere.", 13, pal.subtext);
        searchTip.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8), 0);
        list.addView(searchTip);

        header("Notification badges");
        choice("Badges on icons", "badges", new String[]{"count", "dots", "off"}, new String[]{"Numbers", "Dots", "Off"}, Prefs.badges(this));
        toggle("Show notifications in the app's long-press menu (kept in memory only)", "menu_notifications", true);
        toggle("Keep notification history in the feed (saved on this phone, kept even after clearing)", "notif_history", true);
        choice("Keep history for", "notif_history_keep", new String[]{"7", "30", "90", "0"}, new String[]{"7 days", "30 days", "90 days", "Forever (latest 2000)"}, "30");
        action("Notification access", badgeAccessSummary(), new Runnable() {
            @Override public void run() { safeStart(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")); }
        });

        header("Gestures");
        choice("Swipe up", "g_up", Prefs.ACTIONS, Prefs.ACTION_LABELS, "drawer");
        choice("Swipe down", "g_down", Prefs.ACTIONS, Prefs.ACTION_LABELS, "notifications");
        choice("Two-finger swipe up", "g_two_up", Prefs.ACTIONS, Prefs.ACTION_LABELS, "search");
        choice("Two-finger swipe down", "g_two_down", Prefs.ACTIONS, Prefs.ACTION_LABELS, "quick_settings");
        choice("Double tap", "g_double", Prefs.ACTIONS, Prefs.ACTION_LABELS, "lock");
        choice("Pinch in", "g_pinch", Prefs.ACTIONS, Prefs.ACTION_LABELS, "settings");
        choice("Zoom in (two fingers apart)", "g_spread", Prefs.ACTIONS, Prefs.ACTION_LABELS, "pages");
        action("Shizuku (lock screen without accessibility)", ShizukuShell.status(this), new Runnable() {
            @Override public void run() {
                if (ShizukuShell.running()) {
                    if (ShizukuShell.granted()) Ui.toast(SettingsActivity.this, "Shizuku is ready \u2014 double tap the home screen to lock");
                    else ShizukuShell.requestPermission(new Runnable() {
                        @Override public void run() { Ui.toast(SettingsActivity.this, "Allowed \u2014 double tap the home screen to lock"); recreate(); }
                    });
                } else if (ShizukuShell.installed(SettingsActivity.this)) {
                    Intent i = getPackageManager().getLaunchIntentForPackage(ShizukuShell.MANAGER);
                    if (i != null) safeStart(i);
                } else safeStart(new Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")));
            }
        });
        action("Grove gestures (accessibility)", CanopyAccessibility.enabled() ? "On \u2014 lock screen, recents & power menu work"
                : "Off \u2014 needed only for lock screen, recents & power menu", new Runnable() {
            @Override public void run() { safeStart(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
        });
        TextView tip = Ui.text(this, "Tip: long-press any home-screen icon \u2192 \u201CSwipe-up action\u201D to open a second app by swiping up on it.", 13, pal.subtext);
        tip.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8), 0);
        list.addView(tip);

        header("Feed (swipe right from home)");
        textPref("Your name", "user_name", "Used for the greeting on the feed");
        choice("Background", "feed_bg", new String[]{"dark", "white", "color"},
                new String[]{"Dark blur", "White blur", "Plain colour"}, Prefs.feedBg(this));
        if ("color".equals(Prefs.feedBg(this))) {
            action("Background colour", String.format("#%06X", Prefs.feedColor(this) & 0xFFFFFF), new Runnable() {
                @Override public void run() { FeedBackground.pickColor(SettingsActivity.this, new Runnable() { @Override public void run() { build(); } }); }
            });
        }
        TextView feedTip = Ui.text(this, "Add widgets, reorder, hide or show cards with \u201CEdit feed\u201D at the bottom of the feed, "
                + "or tap \u22EE on any card.", 13, pal.subtext);
        feedTip.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8), 0);
        list.addView(feedTip);
        toggle("Agenda card", "card_calendar", true);
        toggle("To-do card", "card_tasks", true);
        toggle("Screen time card", "card_screentime", true);
        toggle("Most used apps card", "card_apps", true);
        toggle("Notes card", "card_notes", true);
        toggle("Device card", "card_device", true);

        header("System");
        action("Set Grove as default home app", null, new Runnable() {
            @Override public void run() {
                try { startActivity(new Intent(Settings.ACTION_HOME_SETTINGS)); }
                catch (Exception e) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
            }
        });
        action("Back up layout & settings", "Saves a .json file wherever you choose", new Runnable() {
            @Override public void run() {
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/json").putExtra(Intent.EXTRA_TITLE, "canopy-backup.json");
                startActivityForResult(i, REQ_EXPORT);
            }
        });
        action("Restore backup", null, new Runnable() {
            @Override public void run() {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
                startActivityForResult(i, REQ_IMPORT);
            }
        });

        header("App lock");
        choice("Lock Grove", "applock", new String[]{"off", "settings", "all"},
                new String[]{"Off", "Settings only (can't be changed or opened without unlocking)", "Launcher and settings"}, "off");
        if (!AppLock.secure(this)) info("Set a screen lock (PIN, pattern or fingerprint) in Android settings first, or the lock stays off.");
        header("Privacy & about");
        TextView privacy = Ui.text(this, "Grove only uses the internet for web tabs you open in the feed. The launcher itself sends no "
                + "data anywhere: there are no accounts, trackers, analytics or crash reporters, and web tabs block "
                + "ads and trackers with uBlock Origin's filter lists. Your layout, notes and settings stay on this device.", 14, pal.subtext);
        privacy.setPadding(Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 8));
        list.addView(privacy);
        action("Open-source licences", "GNU GPL v3.0 or later", new Runnable() { @Override public void run() { licences(); } });
        String ver = "";
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            ver = pi.versionName;
        } catch (Exception ignored) { }
        action("Grove Launcher " + ver, "Inspired by Nova Launcher and Microsoft Launcher", null);
    }

    // ------------------------------------------------------------------ row builders

    private void header(String t) {
        sections.add(new Object[]{list.getChildCount(), t});
        TextView h = Ui.text(this, t, 14, pal.accent);
        h.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        h.setPadding(Ui.dp(this, 8), Ui.dp(this, 22), 0, Ui.dp(this, 6));
        list.addView(h);
    }

    private boolean openedByInstaller() {
        try {
            if (!Intent.ACTION_MAIN.equals(getIntent().getAction())) return false;
            android.net.Uri ref = getReferrer();
            String host = ref != null ? ref.getHost() : null;
            return host != null && host.toLowerCase(java.util.Locale.ROOT).contains("installer");
        } catch (Exception e) { return false; }
    }

    private void info(String text) { row("Tip", text); }

    /** The colour of the tile this page was opened from (so a category's rows match its tile). */
    private int pageColour() {
        for (int i = 0; i < CATEGORIES.length; i++) if (CATEGORIES[i][0].equals(page)) return TILE_COLOURS[i % TILE_COLOURS.length];
        return pal.accent;
    }

    /** An icon for a row, from what its title is about; else the icon of its category. */
    private int rowIcon(String title) {
        String t = title.toLowerCase(java.util.Locale.ROOT);
        String[][] map = {
                {"status bar", "hide"}, {"hidden", "hide"}, {"lock", "lock"}, {"password", "lock"}, {"privacy", "lock"},
                {"dark", "palette"}, {"theme", "palette"}, {"colour", "palette"}, {"color", "palette"}, {"accent", "palette"},
                {"wallpaper", "wallpaper"}, {"background", "wallpaper"}, {"blur", "layers"}, {"depth", "layers"}, {"parallax", "layers"},
                {"dock", "dock"}, {"widget", "widgets"}, {"folder", "folder"}, {"search", "search"},
                {"notif", "bell"}, {"badge", "bell"}, {"dot", "bell"},
                {"animation", "motion"}, {"transition", "motion"}, {"speed", "motion"}, {"haptic", "motion"}, {"scroll", "motion"},
                {"gesture", "gesture"}, {"swipe", "gesture"}, {"pinch", "gesture"}, {"tap", "gesture"}, {"zoom", "gesture"},
                {"size", "resize"}, {"column", "apps"}, {"row", "apps"}, {"grid", "apps"}, {"sort", "apps"}, {"drawer", "apps"}, {"tab", "apps"},
                {"feed", "feed"}, {"browser", "browser"}, {"website", "browser"}, {"download", "browser"}, {"web", "browser"}, {"video", "browser"},
                {"backup", "backup"}, {"restore", "backup"}, {"reset", "backup"}, {"default launcher", "home"}, {"home", "home"}, {"page", "home"},
                {"icon", "shortcut"}, {"label", "shortcut"}, {"music", "motion"}, {"dns", "browser"}, {"guard", "lock"}, {"battery", "bell"},
        };
        for (String[] m : map) if (t.contains(m[0])) return iconFor(m[1]);
        for (String[] c : CATEGORIES) if (c[0].equals(page)) return iconFor(c[2]);
        return R.drawable.ic_settings;
    }

    private LinearLayout row(String title, String summary) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        int p = Ui.dp(this, 12);
        r.setPadding(p, p, p, p);
        r.setBackground(Ui.pressable(this, pal.surface, Ui.dp(this, 14)));
        int tint = pageColour();
        android.widget.ImageView ic = new android.widget.ImageView(this);
        ic.setImageDrawable(Ui.tinted(this, rowIcon(title), tint));
        int ipad = Ui.dp(this, 8);
        ic.setPadding(ipad, ipad, ipad, ipad);
        ic.setBackground(Ui.round(Ui.alpha(tint, 0x2A), Ui.dp(this, 20)));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(Ui.dp(this, 38), Ui.dp(this, 38));
        ilp.rightMargin = Ui.dp(this, 12);
        r.addView(ic, ilp);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(Ui.text(this, title, 16, pal.text));
        if (!TextUtils.isEmpty(summary)) {
            TextView s = Ui.text(this, summary, 13, pal.subtext);
            s.setTag("summary");
            col.addView(s);
        }
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(this, 4);
        r.setLayoutParams(lp);
        r.setTag(title);
        list.addView(r);
        return r;
    }

    private void toggle(String title, final String key, boolean def) {
        LinearLayout r = row(title, null);
        final Switch sw = new Switch(this);
        sw.setChecked(sp.getBoolean(key, def));
        if (android.os.Build.VERSION.SDK_INT < 31) { // Android 12+ draws its own Material You switch
            sw.setThumbTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{pal.accent, 0xFFBDBDBD}));
            sw.setTrackTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{Ui.alpha(pal.accent, 0x80), 0x55888888}));
        }
        sw.setClickable(false);
        r.addView(sw);
        r.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                sw.setChecked(!sw.isChecked());
                sp.edit().putBoolean(key, sw.isChecked()).apply();
            }
        });
    }

    private void slider(String title, final String key, final int min, int max, int def, final String unit) {
        LinearLayout r = row(title, null);
        ViewGroup col = (ViewGroup) r.getChildAt(1); // child 0 is the icon
        final TextView value = Ui.text(this, sp.getInt(key, def) + unit, 13, pal.subtext);
        col.addView(value);
        SeekBar sb = new SeekBar(this);
        sb.setMin(min);
        sb.setMax(max);
        sb.setProgress(sp.getInt(key, def));
        sb.setProgressTintList(ColorStateList.valueOf(pal.accent));
        sb.setThumbTintList(ColorStateList.valueOf(pal.accent));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) { value.setText(p + unit); }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { sp.edit().putInt(key, s.getProgress()).apply(); }
        });
        col.addView(sb, new LinearLayout.LayoutParams(-1, -2));
    }

    /** "Try it" row: shows the chosen icon-press, app-open and return-home animations right here. */
    private void animationPreview() {
        final LinearLayout r = row("Preview", "Tap the icon: press + app-open animation. Tap Home: return-to-home animation.");
        final android.widget.ImageView icon = new android.widget.ImageView(this);
        icon.setImageResource(R.mipmap.ic_launcher);
        r.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        icon.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(final View v) {
                String press = sp.getString("anim_icon_press", "squish"), open = sp.getString("anim_app_open", "auto");
                if ("auto".equals(open)) open = Anim.fluid(SettingsActivity.this) ? "scale" : Anim.minimal(SettingsActivity.this) ? "fade" : "reveal";
                final String fOpen = open;
                final Runnable openFx = new Runnable() {
                    @Override public void run() {
                        long d = Anim.dur(SettingsActivity.this, 220);
                        android.view.ViewPropertyAnimator a = v.animate().setDuration(d);
                        if ("scale".equals(fOpen)) a.scaleX(1.5f).scaleY(1.5f).alpha(0f);
                        else if ("reveal".equals(fOpen)) a.scaleX(0.6f).scaleY(0.6f).alpha(0f);
                        else if ("fade".equals(fOpen)) a.alpha(0f);
                        else if ("slide".equals(fOpen)) a.translationY(-Ui.dp(SettingsActivity.this, 28)).alpha(0f);
                        a.withEndAction(new Runnable() {
                            @Override public void run() {
                                v.setScaleX(0.7f); v.setScaleY(0.7f); v.setTranslationY(0);
                                v.animate().scaleX(1f).scaleY(1f).alpha(1f).setStartDelay(250).setDuration(300).start();
                            }
                        }).start();
                    }
                };
                v.animate().cancel();
                if ("none".equals(press)) { openFx.run(); return; }
                if ("dim".equals(press)) { v.animate().alpha(0.5f).setDuration(90).withEndAction(openFx).start(); return; }
                float s = "bounce".equals(press) ? 1.15f : 0.84f;
                v.animate().scaleX(s).scaleY(s).setDuration(90).withEndAction(openFx).start();
            }
        });
        TextView home = Ui.text(this, "Home", 14, pal.accent);
        home.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10));
        home.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                final View target = list;
                String a = sp.getString("anim_home_enter", "zoom");
                if ("none".equals(a)) return;
                if ("zoom".equals(a)) { target.setScaleX(1.12f); target.setScaleY(1.12f); }
                else if ("rise".equals(a)) target.setTranslationY(Ui.dp(SettingsActivity.this, 90));
                else if ("shrink".equals(a)) { target.setScaleX(0.82f); target.setScaleY(0.82f); }
                target.setAlpha(0f);
                target.animate().scaleX(1f).scaleY(1f).translationY(0).alpha(1f).setDuration(Anim.dur(SettingsActivity.this, 420))
                        .setInterpolator(Anim.gentle(SettingsActivity.this)).start();
            }
        });
        r.addView(home);
    }

    private void choice(String title, final String key, final String[] values, final String[] labels, String def) {
        String cur = sp.getString(key, def);
        int idx = 0;
        for (int i = 0; i < values.length; i++) if (values[i].equals(cur)) idx = i;
        final LinearLayout r = row(title, labels[idx]);
        final int selected = idx;
        r.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Ui.dialog(SettingsActivity.this).setTitle(((TextView) ((ViewGroup) r.getChildAt(1)).getChildAt(0)).getText())
                        .setSingleChoiceItems(labels, selected, new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int which) {
                                sp.edit().putString(key, values[which]).apply();
                                d.dismiss();
                                if ("theme".equals(key)) { recreate(); return; }
                                build();
                            }
                        }).show();
            }
        });
    }

    private void textPref(final String title, final String key, String summary) {
        String cur = sp.getString(key, "");
        LinearLayout r = row(title, TextUtils.isEmpty(cur) ? summary : cur);
        r.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                final EditText e = new EditText(SettingsActivity.this);
                Ui.tintField(SettingsActivity.this, e);
                e.setText(sp.getString(key, ""));
                e.setSingleLine(true);
                FrameLayout wrap = new FrameLayout(SettingsActivity.this);
                int p = Ui.dp(SettingsActivity.this, 20);
                wrap.setPadding(p, Ui.dp(SettingsActivity.this, 8), p, 0);
                wrap.addView(e);
                Ui.dialog(SettingsActivity.this).setTitle(title).setView(wrap)
                        .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                sp.edit().putString(key, e.getText().toString().trim()).apply();
                                build();
                            }
                        }).setNegativeButton("Cancel", null).show();
            }
        });
    }

    private void action(String title, String summary, final Runnable r) {
        LinearLayout row = row(title, summary);
        if (r != null) row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { r.run(); }
        });
    }

    private void intChoice(String title, final String key, final String[] labels, int def) {
        final int cur = Math.max(0, Math.min(labels.length - 1, sp.getInt(key, def)));
        LinearLayout r = row(title, labels[cur]);
        r.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Ui.dialog(SettingsActivity.this).setSingleChoiceItems(labels, cur, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        sp.edit().putInt(key, w).apply();
                        d.dismiss();
                        build();
                    }
                }).show();
            }
        });
    }

    private String groupSummary(boolean tabs) {
        int n = (tabs ? DrawerGroups.tabs(this) : DrawerGroups.folders(this)).size();
        if (n == 0) return tabs ? "None \u2014 tap to create, or use + in the drawer" : "None \u2014 tap to create, or use + in the drawer";
        return n + (tabs ? (n == 1 ? " tab" : " tabs") : (n == 1 ? " folder" : " folders"));
    }

    private void manageGroups(final boolean tabs) {
        final List<DrawerGroups.Group> all = tabs ? DrawerGroups.tabs(this) : DrawerGroups.folders(this);
        String[] names = new String[all.size() + 1];
        for (int i = 0; i < all.size(); i++) names[i] = all.get(i).name + "  (" + all.get(i).apps.size() + ")";
        names[all.size()] = tabs ? "+ New tab" : "+ New folder";
        Ui.dialog(this).setTitle(tabs ? "Drawer tabs" : "Drawer folders").setItems(names, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) {
                if (w == all.size()) { nameGroup(tabs, null); return; }
                final DrawerGroups.Group g = all.get(w);
                Ui.dialog(SettingsActivity.this).setTitle(g.name)
                        .setItems(new String[]{"Choose apps", "Rename", "Delete"}, new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d2, int w2) {
                                if (w2 == 0) chooseGroupApps(tabs, g.id);
                                else if (w2 == 1) nameGroup(tabs, g);
                                else {
                                    List<DrawerGroups.Group> l = tabs ? DrawerGroups.tabs(SettingsActivity.this) : DrawerGroups.folders(SettingsActivity.this);
                                    l.remove(DrawerGroups.find(l, g.id));
                                    if (tabs) DrawerGroups.saveTabs(SettingsActivity.this, l); else DrawerGroups.saveFolders(SettingsActivity.this, l);
                                    build();
                                }
                            }
                        }).show();
            }
        }).show();
    }

    private void nameGroup(final boolean tabs, final DrawerGroups.Group existing) {
        final EditText e = new EditText(this);
        Ui.tintField(this, e);
        e.setSingleLine(true);
        if (existing != null) e.setText(existing.name);
        e.setHint(tabs ? "Tab name" : "Folder name");
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), 0);
        wrap.addView(e);
        Ui.dialog(this).setTitle(existing == null ? (tabs ? "New tab" : "New folder") : "Rename").setView(wrap)
                .setPositiveButton(existing == null ? "Next" : "Save", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        if (existing == null) {
                            DrawerGroups.Group g = DrawerGroups.create(SettingsActivity.this, tabs, e.getText().toString(), null);
                            chooseGroupApps(tabs, g.id);
                        } else {
                            List<DrawerGroups.Group> l = tabs ? DrawerGroups.tabs(SettingsActivity.this) : DrawerGroups.folders(SettingsActivity.this);
                            DrawerGroups.Group g = DrawerGroups.find(l, existing.id);
                            if (g != null && !e.getText().toString().trim().isEmpty()) g.name = e.getText().toString().trim();
                            if (tabs) DrawerGroups.saveTabs(SettingsActivity.this, l); else DrawerGroups.saveFolders(SettingsActivity.this, l);
                        }
                        build();
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private void chooseGroupApps(final boolean tabs, final long id) {
        final List<AppInfo> apps = AppRepository.get(this).visible();
        if (apps.isEmpty()) { AppRepository.get(this).reload(); Ui.toast(this, "Loading apps, try again"); return; }
        final List<DrawerGroups.Group> l = tabs ? DrawerGroups.tabs(this) : DrawerGroups.folders(this);
        final DrawerGroups.Group g = DrawerGroups.find(l, id);
        if (g == null) return;
        String[] labels = new String[apps.size()];
        final boolean[] checked = new boolean[apps.size()];
        for (int i = 0; i < apps.size(); i++) { labels[i] = apps.get(i).label; checked[i] = g.apps.contains(apps.get(i).key); }
        Ui.dialog(this).setTitle("Apps in \u201C" + g.name + "\u201D")
                .setMultiChoiceItems(labels, checked, new DialogInterface.OnMultiChoiceClickListener() {
                    @Override public void onClick(DialogInterface d, int which, boolean on) { checked[which] = on; }
                })
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        g.apps.clear();
                        for (int i = 0; i < apps.size(); i++) if (checked[i]) g.apps.add(apps.get(i).key);
                        if (!tabs) for (DrawerGroups.Group o : l) if (o != g) o.apps.removeAll(g.apps);
                        if (tabs) DrawerGroups.saveTabs(SettingsActivity.this, l); else DrawerGroups.saveFolders(SettingsActivity.this, l);
                        build();
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private void safeStart(Intent i) {
        try { startActivity(i); } catch (Exception e) { Ui.toast(this, "Not available on this phone"); }
    }

    private String badgeAccessSummary() {
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        boolean on = flat != null && flat.contains(getPackageName());
        return on ? "Granted" : "Not granted \u2014 tap to allow (needed for badges)";
    }

    @Override protected void onResume() {
        super.onResume();
        AppLock.guard(this, true);
        if (list != null) build();
    }

    // ------------------------------------------------------------------ dialogs

    private String hiddenSummary() {
        int n = Prefs.hidden(this).size();
        return n == 0 ? "None" : n + (n == 1 ? " app" : " apps");
    }

    private void hiddenApps() {
        final List<AppInfo> apps = new ArrayList<>(AppRepository.get(this).all());
        if (apps.isEmpty()) { AppRepository.get(this).reload(); Ui.toast(this, "Loading apps, try again"); return; }
        final Set<String> hidden = Prefs.hidden(this);
        String[] labels = new String[apps.size()];
        final boolean[] checked = new boolean[apps.size()];
        for (int i = 0; i < apps.size(); i++) {
            labels[i] = apps.get(i).label;
            checked[i] = hidden.contains(apps.get(i).key);
        }
        Ui.dialog(this).setTitle("Hide apps from the drawer")
                .setMultiChoiceItems(labels, checked, new DialogInterface.OnMultiChoiceClickListener() {
                    @Override public void onClick(DialogInterface d, int which, boolean isChecked) { checked[which] = isChecked; }
                })
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        hidden.clear();
                        for (int i = 0; i < apps.size(); i++) if (checked[i]) hidden.add(apps.get(i).key);
                        Prefs.setHidden(SettingsActivity.this, hidden);
                        build();
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private void accentPicker() {
        GridLayout g = new GridLayout(this);
        g.setColumnCount(4);
        int p = Ui.dp(this, 16);
        g.setPadding(p, p, p, p);
        final android.app.AlertDialog[] dlg = new android.app.AlertDialog[1];
        int size = Ui.dp(this, 52);
        for (final int color : Prefs.ACCENTS) {
            View v = new View(this);
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(color);
            if (color == Prefs.accent(this)) d.setStroke(Ui.dp(this, 4), pal.text);
            v.setBackground(d);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = size;
            lp.height = size;
            lp.setMargins(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
            v.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    sp.edit().putInt("accent", color).putBoolean("dynamic_color", false).apply();
                    if (dlg[0] != null) dlg[0].dismiss();
                    recreate();
                }
            });
            g.addView(v, lp);
        }
        dlg[0] = Ui.dialog(this).setTitle("Accent colour").setView(g).show();
    }

    private String iconPackName() {
        String pkg = Prefs.iconPack(this);
        if (TextUtils.isEmpty(pkg)) return "System default";
        try { return String.valueOf(getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0))); }
        catch (Exception e) { return pkg; }
    }

    private void iconPacks() {
        final List<IconPackManager.PackInfo> packs = IconPackManager.listPacks(this);
        final String[] labels = new String[packs.size() + 1];
        labels[0] = "System default";
        int sel = 0;
        String cur = Prefs.iconPack(this);
        for (int i = 0; i < packs.size(); i++) {
            labels[i + 1] = packs.get(i).label;
            if (packs.get(i).pkg.equals(cur)) sel = i + 1;
        }
        Ui.dialog(this).setTitle(packs.isEmpty() ? "No icon packs installed" : "Icon pack")
                .setSingleChoiceItems(labels, sel, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        sp.edit().putString("icon_pack", which == 0 ? "" : packs.get(which - 1).pkg).apply();
                        d.dismiss();
                        build();
                    }
                }).show();
    }

    /** Swatches + a hex field for the feed's plain background colour. */
    /** Folders: icon colour, open-folder background colour and how see-through it is. */
    private void folderColours() {
        int ic = Prefs.folderIconColor(this), bg = Prefs.folderBgColor(this);
        action("Folder icon colour", ic == 0 ? "Accent colour" : String.format("#%06X", ic & 0xFFFFFF), new Runnable() {
            @Override public void run() {
                Ui.dialog(SettingsActivity.this).setTitle("Folder icon colour").setItems(new String[]{"Accent colour (default)", "Choose a colour\u2026"},
                        new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface d, int w) {
                                if (w == 0) { sp.edit().remove("folder_icon_color").apply(); build(); }
                                else FeedBackground.pickColor(SettingsActivity.this, "Folder icon colour", "folder_icon_color",
                                        Prefs.folderIconColor(SettingsActivity.this) == 0 ? Ui.palette(SettingsActivity.this).accent : Prefs.folderIconColor(SettingsActivity.this),
                                        new Runnable() { @Override public void run() { build(); } });
                            }
                        }).show();
            }
        });
        action("Open folder background", bg == 0 ? "Default (follows light / dark theme)" : String.format("#%06X", bg & 0xFFFFFF), new Runnable() {
            @Override public void run() {
                Ui.dialog(SettingsActivity.this).setTitle("Open folder background").setItems(new String[]{"Default (follows light / dark theme)", "Choose a colour\u2026"},
                        new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface d, int w) {
                                if (w == 0) { sp.edit().remove("folder_bg_color").apply(); build(); }
                                else FeedBackground.pickColor(SettingsActivity.this, "Open folder background", "folder_bg_color",
                                        Prefs.folderBgColor(SettingsActivity.this) == 0 ? Ui.palette(SettingsActivity.this).surface : Prefs.folderBgColor(SettingsActivity.this),
                                        new Runnable() { @Override public void run() { build(); } });
                            }
                        }).show();
            }
        });
        slider("Open folder opacity", "folder_bg_alpha", 20, 100, 100, "%");
    }

    private void licences() {
        String text = "Grove Launcher\nCopyright 2026 The Grove Launcher authors\n"
                + "Grove is free software: you can redistribute it and/or modify it under the terms of the GNU General "
                + "Public License as published by the Free Software Foundation, either version 3 of the License, or (at "
                + "your option) any later version. It is distributed WITHOUT ANY WARRANTY. Full text: gnu.org/licenses/gpl-3.0. "
                + "Source code: the source zip that comes with each release.\n\n"
                + "This is original code. It re-implements ideas and features popularised by Nova Launcher and "
                + "Microsoft Launcher; it contains no code from either (both are closed source).\n\n"
                + "UI icons: Material Design Icons by Google, Apache License 2.0.\n\n"
                + "Ad blocking in web tabs uses filter lists from the uBlock Origin project (github.com/uBlockOrigin/uAssets): "
                + "uBlock filters (GPL-3.0, Raymond Hill and contributors), EasyList and EasyPrivacy (GPL-3.0 / CC BY-SA 3.0, "
                + "The EasyList authors) and Peter Lowe's ad server list (pgl.yoyo.org). They are separate data files, "
                + "downloaded fresh from GitHub when you update them.\n\n"
                + "Optional Shizuku support speaks the protocol defined by Shizuku-API (github.com/RikkaApps/Shizuku-API, "
                + "MIT License, \u00A9 2021 RikkaW); Grove includes only a small compatible class, no Shizuku library.\n\n"
                + "Full licence text: https://www.apache.org/licenses/LICENSE-2.0";
        TextView t = Ui.text(this, text, 14, pal.text);
        int p = Ui.dp(this, 22);
        t.setPadding(p, Ui.dp(this, 10), p, 0);
        t.setTextIsSelectable(true);
        ScrollView s = new ScrollView(this);
        s.addView(t);
        Ui.dialog(this).setTitle("Open-source licences").setView(s).setPositiveButton("OK", null).show();
    }

    // ------------------------------------------------------------------ backup

    private void pick(int req) {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
        try { startActivityForResult(i, req); } catch (Exception e) { Ui.toast(this, "No picture picker on this phone"); }
    }

    /** Wallpaper files changed: the home screen rebuilds next time it shows. */
    private void needsRecreateLauncher() {
        sp.edit().putLong("wall3d_stamp", System.currentTimeMillis()).apply();
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        AppLock.onResult(this, req, res, new Runnable() { @Override public void run() { finish(); } });
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (req == REQ_WALL3D || req == REQ_SUBJECT) {
            boolean ok = req == REQ_WALL3D ? Wallpaper3D.save(this, uri) : Wallpaper3D.saveSubject(this, uri);
            if (ok && req == REQ_WALL3D) sp.edit().putString("wall3d_source", "picked").putBoolean("wall3d", true).apply();
            Ui.toast(this, ok ? (req == REQ_WALL3D ? "3D wallpaper set" : "Subject layer added \u2014 long-press a widget to put it behind") : "Couldn't open that picture");
            needsRecreateLauncher();
            build();
            return;
        }
        try {
            if (req == REQ_EXPORT) {
                JSONObject o = new JSONObject();
                JSONObject prefs = new JSONObject();
                for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
                    Object v = e.getValue();
                    if (v instanceof Set) {
                        org.json.JSONArray a = new org.json.JSONArray();
                        for (Object s : (Set<?>) v) a.put(String.valueOf(s));
                        prefs.put(e.getKey(), new JSONObject().put("set", a));
                    } else {
                        prefs.put(e.getKey(), v);
                    }
                }
                o.put("format", "canopy-backup-1");
                o.put("prefs", prefs);
                o.put("home", new JSONObject(HomeStore.get(this).toJson()));
                OutputStream out = getContentResolver().openOutputStream(uri);
                out.write(o.toString(2).getBytes(StandardCharsets.UTF_8));
                out.close();
                Ui.toast(this, "Backup saved");
            } else if (req == REQ_IMPORT) {
                InputStream in = getContentResolver().openInputStream(uri);
                JSONObject o = new JSONObject(HomeStore.readAll(in));
                in.close();
                if (!"canopy-backup-1".equals(o.optString("format"))) throw new Exception("Not a Grove backup");
                JSONObject prefs = o.getJSONObject("prefs");
                SharedPreferences.Editor ed = sp.edit().clear();
                Iterator<String> keys = prefs.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    Object v = prefs.get(k);
                    if (v instanceof Boolean) ed.putBoolean(k, (Boolean) v);
                    else if (v instanceof Integer) ed.putInt(k, (Integer) v);
                    else if (v instanceof Long) ed.putLong(k, (Long) v);
                    else if (v instanceof Double) ed.putFloat(k, ((Double) v).floatValue());
                    else if (v instanceof JSONObject && ((JSONObject) v).has("set")) {
                        org.json.JSONArray a = ((JSONObject) v).getJSONArray("set");
                        java.util.HashSet<String> s = new java.util.HashSet<>();
                        for (int i = 0; i < a.length(); i++) s.add(a.getString(i));
                        ed.putStringSet(k, s);
                    } else ed.putString(k, String.valueOf(v));
                }
                ed.apply();
                HomeStore.get(this).importJson(o.getJSONObject("home").toString());
                Ui.toast(this, "Backup restored (widgets need to be re-added)");
                build();
            }
        } catch (Exception e) {
            Ui.toast(this, "Failed: " + e.getMessage());
        }
    }

    @SuppressWarnings("unused")
    private static Context unused(Context c) { return c; }
}
