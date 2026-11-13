/*
 * Copyright 2026 The Canopy Launcher authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.canopy.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** The home screen layout, saved as a small JSON file in app-private storage. */
public final class HomeStore {
    private static HomeStore sInstance;

    private final Context ctx;
    private final File file;
    private final List<HomeItem> items = new ArrayList<>();
    private int pageCount = 2;
    private long nextId = 1;
    private int version;

    /** The classic and the Windows Phone home screens each keep their own icons and widgets. */
    public static synchronized HomeStore get(Context c) {
        String name = "winphone".equals(Prefs.homeStyle(c)) ? "home_wp.json" : "home.json";
        if (sInstance == null || !sInstance.file.getName().equals(name)) sInstance = new HomeStore(c.getApplicationContext(), name);
        return sInstance;
    }

    private HomeStore(Context c, String name) {
        ctx = c;
        file = new File(c.getFilesDir(), name);
        if (file.exists()) {
            try { parse(readFile(file)); } catch (Exception ignored) { }
        }
    }

    public boolean initialized() { return file.exists(); }
    public List<HomeItem> items() { return items; }
    public int pageCount() { return pageCount; }
    public int version() { return version; }
    public long newId() { return nextId++; }

    private void parse(String json) throws Exception {
        JSONObject o = new JSONObject(json);
        items.clear();
        pageCount = Math.max(1, o.optInt("pages", 2));
        nextId = Math.max(1, o.optLong("nextId", 1));
        JSONArray a = o.optJSONArray("items");
        if (a != null) for (int i = 0; i < a.length(); i++) {
            HomeItem it = HomeItem.fromJson(a.getJSONObject(i));
            items.add(it);
            nextId = Math.max(nextId, maxId(it) + 1);
        }
    }

    private static long maxId(HomeItem it) {
        long m = it.id;
        for (HomeItem c : it.children) m = Math.max(m, maxId(c));
        return m;
    }

    public String toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("pages", pageCount).put("nextId", nextId);
            JSONArray a = new JSONArray();
            for (HomeItem it : items) a.put(it.toJson());
            o.put("items", a);
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    /** Persist and bump the version so the launcher knows to rebind. */
    public void commit() {
        version++;
        final String json = toJson();
        App.IO.execute(new Runnable() {
            @Override public void run() {
                synchronized (HomeStore.class) {
                    try {
                        File tmp = new File(file.getPath() + ".tmp");
                        OutputStream out = new FileOutputStream(tmp);
                        out.write(json.getBytes(StandardCharsets.UTF_8));
                        out.close();
                        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file); }
                    } catch (Exception ignored) { }
                }
            }
        });
    }

    /** Restore a backup; widgets can't be restored because their ids belong to the old install. */
    public void importJson(String json) throws Exception {
        parse(json);
        Iterator<HomeItem> i = items.iterator();
        while (i.hasNext()) if (i.next().type == HomeItem.WIDGET) i.remove();
        commit();
    }

    /** When true, widgets may overlap each other (and apps may sit on top of widgets). */
    public boolean overlapWidgets;

    /** The item covering a cell; icons win over widgets underneath them. */
    public HomeItem itemAt(int container, int page, int x, int y) {
        HomeItem widget = null;
        for (HomeItem it : items) {
            if (it.container != container) continue;
            if (container == HomeItem.DESKTOP && it.page != page) continue;
            if (x >= it.x && x < it.x + it.spanX && y >= it.y && y < it.y + it.spanY) {
                if (it.type != HomeItem.WIDGET) return it;
                if (widget == null) widget = it;
            }
        }
        return widget;
    }

    private boolean blocks(HomeItem o, HomeItem moving) {
        if (!overlapWidgets) return true;
        return o.type != HomeItem.WIDGET && (moving == null || moving.type != HomeItem.WIDGET);
    }

    public boolean isFree(int container, int page, int x, int y, int sx, int sy, HomeItem ignore, int cols, int rows) {
        if (x < 0 || y < 0 || x + sx > cols || y + sy > rows) return false;
        for (HomeItem o : items) {
            if (o == ignore || o.container != container) continue;
            if (container == HomeItem.DESKTOP && o.page != page) continue;
            boolean hit = x < o.x + o.spanX && x + sx > o.x && y < o.y + o.spanY && y + sy > o.y;
            if (hit && blocks(o, ignore)) return false;
        }
        return true;
    }

    /** Nearest free spot to (cx, cy), or null if the page is full. */
    public int[] findFreeNear(int container, int page, int cx, int cy, int sx, int sy, HomeItem ignore, int cols, int rows) {
        int[] best = null;
        long bestD = Long.MAX_VALUE;
        for (int y = 0; y <= rows - sy; y++) for (int x = 0; x <= cols - sx; x++) {
            if (!isFree(container, page, x, y, sx, sy, ignore, cols, rows)) continue;
            long d = (long) (x - cx) * (x - cx) + (long) (y - cy) * (y - cy);
            if (d < bestD) { bestD = d; best = new int[]{x, y}; }
        }
        return best;
    }

    public int[] findFirstFree(int container, int page, int sx, int sy, int cols, int rows) {
        for (int y = 0; y <= rows - sy; y++) for (int x = 0; x <= cols - sx; x++) {
            if (isFree(container, page, x, y, sx, sy, null, cols, rows)) return new int[]{x, y};
        }
        return null;
    }

    public void addTop(HomeItem it) {
        if (it.id == 0) it.id = newId();
        items.add(it);
    }

    public HomeItem parentFolder(HomeItem child) {
        for (HomeItem it : items) if (it.type == HomeItem.FOLDER && it.children.contains(child)) return it;
        return null;
    }

    /** Remove an item from wherever it lives. A folder left with one item turns back into that item. */
    public void detach(HomeItem it) {
        if (items.remove(it)) return;
        HomeItem f = parentFolder(it);
        if (f == null) return;
        f.children.remove(it);
        if (f.children.size() == 1) {
            HomeItem only = f.children.remove(0);
            only.container = f.container;
            only.page = f.page;
            only.x = f.x;
            only.y = f.y;
            only.spanX = only.spanY = 1;
            items.remove(f);
            items.add(only);
        } else if (f.children.isEmpty()) {
            items.remove(f);
        }
    }

    public boolean pageEmpty(int page) {
        for (HomeItem it : items) if (it.container == HomeItem.DESKTOP && it.page == page) return false;
        return true;
    }

    public void addPage() { pageCount++; commit(); }

    public void removePage(int page) {
        if (pageCount <= 1) return;
        Iterator<HomeItem> i = items.iterator();
        while (i.hasNext()) {
            HomeItem it = i.next();
            if (it.container != HomeItem.DESKTOP) continue;
            if (it.page == page) i.remove();
            else if (it.page > page) it.page--;
        }
        pageCount--;
        commit();
    }

    public List<HomeItem> widgets() {
        List<HomeItem> out = new ArrayList<>();
        for (HomeItem it : items) if (it.type == HomeItem.WIDGET) out.add(it);
        return out;
    }

    /** First run: a sensible dock (phone, messages, library, browser, camera) and an empty second page. */
    public void createDefaults(AppRepository repo, int cols, int rows, int dockCount) {
        items.clear();
        pageCount = 2;
        PackageManager pm = ctx.getPackageManager();
        List<HomeItem> dock = new ArrayList<>();
        Intent[] intents = {
                new Intent(Intent.ACTION_DIAL),
                new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
                new Intent(Intent.ACTION_VIEW, Uri.parse("https://")),
                new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        };
        List<String> used = new ArrayList<>();
        for (Intent in : intents) {
            ResolveInfo ri = pm.resolveActivity(in, PackageManager.MATCH_DEFAULT_ONLY);
            if (ri == null || ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if ("android".equals(pkg) || used.contains(pkg)) continue;
            AppInfo a = repo.findByPackage(pkg);
            if (a == null) continue;
            used.add(pkg);
            dock.add(HomeItem.app(newId(), a));
        }
        int n = Math.min(dock.size(), dockCount);
        for (int i = 0; i < n; i++) {
            HomeItem it = dock.get(i);
            it.container = HomeItem.DOCK;
            it.x = i;
            it.y = 0;
            items.add(it);
        }
        // A few handy apps on the bottom row of the first page.
        String[] pkgs = {"com.android.vending", "com.google.android.apps.photos", "com.android.settings", ctx.getPackageName()};
        int x = 0;
        for (String p : pkgs) {
            if (x >= cols) break;
            AppInfo a = p.equals(ctx.getPackageName())
                    ? repo.find(new android.content.ComponentName(ctx, SettingsActivity.class).flattenToString(), repo.mySerial())
                    : repo.findByPackage(p);
            if (a == null) continue;
            HomeItem it = HomeItem.app(newId(), a);
            it.container = HomeItem.DESKTOP;
            it.page = 0;
            it.x = x++;
            it.y = rows - 1;
            items.add(it);
        }
        commit();
    }

    static String readFile(File f) throws Exception {
        InputStream in = new FileInputStream(f);
        try { return readAll(in); } finally { in.close(); }
    }

    public static String readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }
}
