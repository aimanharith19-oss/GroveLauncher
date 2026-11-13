/*
 * Copyright 2026 The Canopy Launcher authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.canopy.launcher;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** App shortcuts and browser/web shortcuts placed in the app drawer (alongside apps). */
public final class DrawerShortcuts {
    public static final class Entry {
        public String pkg, id, title;
        public long serial;
        public Drawable icon;

        public HomeItem asHomeItem() {
            HomeItem it = new HomeItem();
            it.type = HomeItem.SHORTCUT;
            it.pkg = pkg;
            it.shortcutId = id;
            it.serial = serial;
            it.title = title;
            return it;
        }
    }

    private DrawerShortcuts() {}

    public static List<Entry> all(Context c) {
        List<Entry> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(Prefs.sp(c).getString("drawer_shortcuts", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Entry e = new Entry();
                e.pkg = o.getString("pkg");
                e.id = o.getString("id");
                e.title = o.optString("title", "Shortcut");
                e.serial = o.optLong("serial");
                File f = AddShortcutActivity.iconFile(c, e.asHomeItem());
                if (f.exists()) e.icon = new BitmapDrawable(c.getResources(), BitmapFactory.decodeFile(f.getPath()));
                if (e.icon == null) e.icon = c.getPackageManager().getDefaultActivityIcon();
                out.add(e);
            }
        } catch (Exception ignored) { }
        return out;
    }

    private static void save(Context c, List<Entry> l) {
        try {
            JSONArray a = new JSONArray();
            for (Entry e : l) a.put(new JSONObject().put("pkg", e.pkg).put("id", e.id).put("title", e.title).put("serial", e.serial));
            Prefs.sp(c).edit().putString("drawer_shortcuts", a.toString()).apply();
        } catch (Exception ignored) { }
    }

    public static void add(Context c, HomeItem shortcut) {
        List<Entry> l = all(c);
        for (Entry e : l) if (e.pkg.equals(shortcut.pkg) && e.id.equals(shortcut.shortcutId)) return;
        Entry e = new Entry();
        e.pkg = shortcut.pkg;
        e.id = shortcut.shortcutId;
        e.title = shortcut.title;
        e.serial = shortcut.serial;
        l.add(e);
        save(c, l);
    }

    public static void remove(Context c, Entry target) {
        List<Entry> l = all(c);
        for (int i = l.size() - 1; i >= 0; i--) {
            if (l.get(i).pkg.equals(target.pkg) && l.get(i).id.equals(target.id)) l.remove(i);
        }
        save(c, l);
    }
}
