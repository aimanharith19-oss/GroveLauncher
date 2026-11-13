/*
 * Copyright 2026 The Canopy Launcher authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.canopy.launcher;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** User-made app drawer tabs and drawer folders (Nova "drawer groups"). Stored locally as JSON. */
public final class DrawerGroups {
    public static final class Group {
        public long id;
        public String name;
        public final List<String> apps = new ArrayList<>();
        /** Folders only: the drawer tab it lives in (0 = the All tab). */
        public long tab;
    }

    /** The folders shown in one drawer tab (0 = All). */
    public static List<Group> foldersIn(List<Group> folders, long tab) {
        List<Group> out = new ArrayList<>();
        for (Group g : folders) if (g.tab == tab) out.add(g);
        return out;
    }

    private DrawerGroups() {}

    private static JSONObject root(Context c) {
        try { return new JSONObject(Prefs.sp(c).getString("drawer_groups", "{}")); } catch (Exception e) { return new JSONObject(); }
    }

    private static List<Group> read(Context c, String kind) {
        List<Group> out = new ArrayList<>();
        JSONArray a = root(c).optJSONArray(kind);
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Group g = new Group();
            g.id = o.optLong("id");
            g.name = o.optString("name", "Untitled");
            g.tab = o.optLong("tab", 0);
            JSONArray apps = o.optJSONArray("apps");
            if (apps != null) for (int j = 0; j < apps.length(); j++) g.apps.add(apps.optString(j));
            out.add(g);
        }
        return out;
    }

    private static void write(Context c, String kind, List<Group> groups) {
        try {
            JSONObject r = root(c);
            JSONArray a = new JSONArray();
            for (Group g : groups) {
                JSONArray apps = new JSONArray();
                for (String k : g.apps) apps.put(k);
                JSONObject o = new JSONObject().put("id", g.id).put("name", g.name).put("apps", apps);
                if (g.tab != 0) o.put("tab", g.tab);
                a.put(o);
            }
            r.put(kind, a);
            Prefs.sp(c).edit().putString("drawer_groups", r.toString()).apply();
        } catch (Exception ignored) { }
    }

    public static List<Group> tabs(Context c) { return read(c, "tabs"); }
    public static List<Group> folders(Context c) { return read(c, "folders"); }
    public static void saveTabs(Context c, List<Group> l) { write(c, "tabs", l); }
    public static void saveFolders(Context c, List<Group> l) { write(c, "folders", l); }

    public static Group create(Context c, boolean tab, String name, List<String> apps) { return create(c, tab, name, apps, 0); }

    /** {@code inTab}: for a folder, the drawer tab it's made in (0 = All). */
    public static Group create(Context c, boolean tab, String name, List<String> apps, long inTab) {
        List<Group> l = tab ? tabs(c) : folders(c);
        Group g = new Group();
        if (!tab) g.tab = inTab;
        g.id = System.currentTimeMillis();
        g.name = name == null || name.trim().isEmpty() ? (tab ? "New tab" : "Folder") : name.trim();
        if (apps != null) g.apps.addAll(apps);
        if (!tab) { // an app lives in at most one folder per tab
            for (Group o : l) if (o.tab == g.tab) o.apps.removeAll(g.apps);
        }
        l.add(g);
        if (tab) saveTabs(c, l); else saveFolders(c, l);
        return g;
    }

    public static Group find(List<Group> l, long id) {
        for (Group g : l) if (g.id == id) return g;
        return null;
    }

    public static Group folderOf(List<Group> folders, String appKey) {
        for (Group g : folders) if (g.apps.contains(appKey)) return g;
        return null;
    }
}
