/*
 * Copyright 2026 The Canopy Launcher authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.canopy.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/** All user settings live here, in one local SharedPreferences file. Nothing is synced anywhere. */
public final class Prefs {
    public static final String NAME = "canopy_prefs";

    /** Gesture actions, shared by every gesture slot. */
    public static final String[] ACTIONS = {"none", "drawer", "search", "notifications", "quick_settings",
            "glance", "lock", "recents", "power", "settings", "pages"};
    public static final String[] ACTION_LABELS = {"Do nothing", "Open app drawer", "Search apps", "Notifications",
            "Quick settings", "Open feed", "Lock screen", "Recent apps", "Power menu", "Grove settings", "Edit home screens"};
    public static final String[] TRANSITIONS = {"slide", "cube", "stack", "zoom", "rotate", "flip"};
    public static final String[] TRANSITION_LABELS = {"Simple slide", "Cube", "Card stack", "Zoom", "Rotate down", "Flip"};

    /** Windows Phone 8 accent colours. */
    public static final int[] WP_ACCENTS = {0xFFA4C400, 0xFF60A917, 0xFF008A00, 0xFF00ABA9, 0xFF1BA1E2, 0xFF0050EF, 0xFF6A00FF,
            0xFFAA00FF, 0xFFF472D0, 0xFFD80073, 0xFFA20025, 0xFFE51400, 0xFFFA6800, 0xFFF0A30A,
            0xFFE3C800, 0xFF825A2C, 0xFF6D8764, 0xFF647687, 0xFF76608A, 0xFF87794E};

    public static final int[] ACCENTS = {0xFF4CAF7D, 0xFF2E9E6B, 0xFF26A69A, 0xFF1E88E5, 0xFF5C6BC0, 0xFF8E24AA,
            0xFFEC407A, 0xFFE53935, 0xFFFB8C00, 0xFFFFB300, 0xFF8D6E63, 0xFF78909C};

    private Prefs() {}

    public static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    // Home screen
    public static int gridCols(Context c) { return sp(c).getInt("grid_cols", 5); }
    public static int gridRows(Context c) { return sp(c).getInt("grid_rows", 6); }
    public static boolean showLabels(Context c) { return sp(c).getBoolean("show_labels", true); }
    public static int iconScale(Context c) { return sp(c).getInt("icon_scale", 100); }
    public static boolean pageIndicator(Context c) { return sp(c).getBoolean("page_indicator", true); }
    public static boolean wallpaperScroll(Context c) { return sp(c).getBoolean("wallpaper_scroll", true); }
    public static boolean glanceEnabled(Context c) { return sp(c).getBoolean("glance", true); }
    public static String userName(Context c) { return sp(c).getString("user_name", ""); }

    public static int labelSize(Context c) { return sp(c).getInt("label_size", 12); }
    public static boolean desktopLocked(Context c) { return sp(c).getBoolean("desktop_lock", false); }
    public static boolean infiniteScroll(Context c) { return sp(c).getBoolean("infinite_scroll", false); }
    public static String transition(Context c) { return sp(c).getString("transition", "slide"); }
    /** surface: home, feed or drawer. Each has its own choice; the old single switch is the default. */
    public static boolean statusBarHidden(Context c, String surface) {
        return sp(c).getBoolean("hide_bar_" + surface, hideStatusBar(c));
    }
    public static boolean hideStatusBar(Context c) { return sp(c).getBoolean("hide_statusbar", false); }
    /** "none", "top" or "bottom" */
    /** Feed background: "dark" (dark blur, default), "white" (white blur) or "color" (a plain colour). */
    /** Favourite apps, pinned at the top of the app drawer (after folders), in the order you added them. */
    public static java.util.List<String> drawerFavs(Context c) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String k : sp(c).getString("drawer_favs", "").split("\n")) if (!k.isEmpty() && !out.contains(k)) out.add(k);
        return out;
    }

    public static void setDrawerFav(Context c, String key, boolean fav) {
        java.util.List<String> l = drawerFavs(c);
        l.remove(key);
        if (fav) l.add(key);
        sp(c).edit().putString("drawer_favs", android.text.TextUtils.join("\n", l)).apply();
    }

    /** Folder icon colour (0 = your accent colour). */
    public static int folderIconColor(Context c) { return sp(c).getInt("folder_icon_color", 0); }
    /** Open-folder background (0 = the normal light/dark card), and how solid it is (0–100). */
    public static int folderBgColor(Context c) { return sp(c).getInt("folder_bg_color", 0); }
    public static int folderBgOpacity(Context c) { return sp(c).getInt("folder_bg_alpha", 100); }
    /** The open folder's background as drawn. */
    public static int folderBg(Context c) {
        int col = folderBgColor(c);
        if (col == 0) col = Ui.palette(c).surface;
        return Ui.alpha(col, Math.round(Math.max(20, Math.min(100, folderBgOpacity(c))) * 2.55f));
    }

    public static String feedBg(Context c) { return sp(c).getString("feed_bg", "dark"); }
    public static int feedColor(Context c) { return sp(c).getInt("feed_color", 0xFF101418); }

    public static String searchBar(Context c) { return sp(c).getString("search_bar", "bottom"); }
    public static String iconShape(Context c) { return sp(c).getString("icon_shape", "system"); }
    public static boolean wrapLegacy(Context c) { return sp(c).getBoolean("wrap_legacy", true); }
    /** "dots", "count" or "off" */
    public static String badges(Context c) { return sp(c).getString("badges", "count"); }

    // Dock
    public static boolean dockEnabled(Context c) { return sp(c).getBoolean("dock_enabled", true); }
    public static int dockCount(Context c) { return sp(c).getInt("dock_count", 5); }
    public static boolean dockBackground(Context c) { return sp(c).getBoolean("dock_bg", true); }

    public static boolean dockExpandable(Context c) { return sp(c).getBoolean("dock_expand", true); }

    // Drawer
    public static int drawerRows(Context c) { return sp(c).getInt("drawer_rows", 0); }
    public static int drawerIconScale(Context c) { return sp(c).getInt("drawer_icon_scale", 100); }
    public static boolean widgetOverlap(Context c) { return sp(c).getBoolean("widget_overlap", false); }
    public static boolean drawerLabels(Context c) { return sp(c).getBoolean("drawer_labels", true); }
    public static boolean drawerFrequent(Context c) { return sp(c).getBoolean("drawer_frequent", true); }
    public static int drawerCols(Context c) { return sp(c).getInt("drawer_cols", 5); }
    public static int drawerOpacity(Context c) { return sp(c).getInt("drawer_opacity", 92); }
    public static String drawerSort(Context c) { return sp(c).getString("drawer_sort", "az"); }
    /** The A–Z wave scroller beside the drawer. */
    public static boolean drawerAlphabet(Context c) { return sp(c).getBoolean("drawer_alphabet", false); }

    /** "classic" (pages side by side, swipe up for the drawer) or "winphone" (pages top to bottom, swipe left for the app list). */
    public static String homeStyle(Context c) { return sp(c).getString("home_style", "classic"); }

    // Look
    public static String theme(Context c) { return sp(c).getString("theme", "auto"); }
    /** Material You (Android 12+): follow the wallpaper colours unless a fixed accent is chosen. */
    public static boolean dynamicColor(Context c) {
        return android.os.Build.VERSION.SDK_INT >= 31 && sp(c).getBoolean("dynamic_color", true);
    }

    public static int accent(Context c) {
        if (dynamicColor(c)) {
            try {
                return c.getColor(Ui.night(c) ? android.R.color.system_accent1_200 : android.R.color.system_accent1_600);
            } catch (Exception ignored) { }
        }
        return sp(c).getInt("accent", Ui.wp(c) ? WP_ACCENTS[4] : ACCENTS[0]);
    }

    /** Hide apps that belong to a drawer tab from the "All" list. */
    public static boolean tabsHideFromAll(Context c) { return sp(c).getBoolean("tabs_hide_from_all", true); }
    public static String iconPack(Context c) { return sp(c).getString("icon_pack", ""); }

    // Gestures
    public static String gesture(Context c, String slot) {
        String def;
        if ("g_up".equals(slot)) def = "drawer";
        else if ("g_down".equals(slot)) def = "notifications";
        else if ("g_double".equals(slot)) def = "lock";
        else if ("g_pinch".equals(slot)) def = "settings";
        else if ("g_spread".equals(slot)) def = "pages";
        else if ("g_two_up".equals(slot)) def = "search";
        else if ("g_two_down".equals(slot)) def = "quick_settings";
        else def = "none";
        return sp(c).getString(slot, def);
    }

    public static Set<String> hidden(Context c) {
        return new HashSet<>(sp(c).getStringSet("hidden_apps", new HashSet<String>()));
    }

    public static void setHidden(Context c, Set<String> s) {
        sp(c).edit().putStringSet("hidden_apps", new HashSet<>(s)).apply();
    }

    public static String actionLabel(String key) {
        for (int i = 0; i < ACTIONS.length; i++) if (ACTIONS[i].equals(key)) return ACTION_LABELS[i];
        return ACTION_LABELS[0];
    }
}
