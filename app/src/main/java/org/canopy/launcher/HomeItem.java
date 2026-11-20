/*
 * Copyright 2026 The Canopy Launcher authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.canopy.launcher;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Something placed on the home screen or dock: an app, folder, widget or pinned shortcut. */
public class HomeItem {
    public static final int APP = 0, FOLDER = 1, WIDGET = 2, LIBRARY = 3, SHORTCUT = 4, WEB = 5;
    public static final int DESKTOP = -100, DOCK = -101;

    public long id;
    public int type;
    public int container = DESKTOP;
    public int page, x, y, spanX = 1, spanY = 1;
    public String component;   // APP: flattened ComponentName
    public long serial;        // APP / SHORTCUT: user profile serial
    public String title;       // custom label / folder name
    public int widgetId = -1;  // WIDGET
    /** WIDGET with free placement: position and size in cell units (may be fractional or past the grid edge). NaN = snapped to cells. */
    public float fx = Float.NaN, fy = Float.NaN, fw = Float.NaN, fh = Float.NaN;
    /** WIDGET that lets touches pass through to whatever is behind it (icons, KLWP…). */
    public boolean clickThrough;
    /** WIDGET crop: share hidden on the left, top, right and bottom (0..0.8). */
    public float cropL, cropT, cropR, cropB;
    public boolean isFree() { return !Float.isNaN(fx) && !Float.isNaN(fw); }
    /** WEB: a website shortcut (address in {@code component}, name in {@code title}), shown with the site's icon. */
    public String pkg, shortcutId; // SHORTCUT
    public String swipe;       // Nova-style swipe-up action: "component#serial" of an app to open
    public final List<HomeItem> children = new ArrayList<>(); // FOLDER

    public boolean isAppLike() { return type == APP || type == SHORTCUT || type == LIBRARY || type == WEB; }

    public static HomeItem app(long id, AppInfo a) {
        HomeItem it = new HomeItem();
        it.id = id;
        it.type = APP;
        it.component = a.component.flattenToString();
        it.serial = a.serial;
        return it;
    }

    JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id).put("type", type).put("container", container).put("page", page)
                .put("x", x).put("y", y).put("sx", spanX).put("sy", spanY).put("serial", serial).put("wid", widgetId);
        if (component != null) o.put("cmp", component);
        if (title != null) o.put("title", title);
        if (pkg != null) o.put("pkg", pkg);
        if (shortcutId != null) o.put("sid", shortcutId);
        if (swipe != null) o.put("swipe", swipe);
        if (clickThrough) o.put("ct", true);
        if (cropL > 0 || cropT > 0 || cropR > 0 || cropB > 0) o.put("crop", new JSONArray().put(cropL).put(cropT).put(cropR).put(cropB));
        if (isFree()) o.put("fx", fx).put("fy", fy).put("fw", fw).put("fh", fh);
        if (!children.isEmpty()) {
            JSONArray a = new JSONArray();
            for (HomeItem c : children) a.put(c.toJson());
            o.put("children", a);
        }
        return o;
    }

    static HomeItem fromJson(JSONObject o) {
        HomeItem it = new HomeItem();
        it.id = o.optLong("id");
        it.type = o.optInt("type");
        it.container = o.optInt("container", DESKTOP);
        it.page = o.optInt("page");
        it.x = o.optInt("x");
        it.y = o.optInt("y");
        it.spanX = Math.max(1, o.optInt("sx", 1));
        it.spanY = Math.max(1, o.optInt("sy", 1));
        it.serial = o.optLong("serial");
        it.widgetId = o.optInt("wid", -1);
        it.component = o.optString("cmp", null);
        it.title = o.optString("title", null);
        it.pkg = o.optString("pkg", null);
        it.shortcutId = o.optString("sid", null);
        it.swipe = o.optString("swipe", null);
        it.clickThrough = o.optBoolean("ct", false);
        JSONArray cr = o.optJSONArray("crop");
        if (cr != null && cr.length() == 4) { it.cropL = (float) cr.optDouble(0); it.cropT = (float) cr.optDouble(1); it.cropR = (float) cr.optDouble(2); it.cropB = (float) cr.optDouble(3); }
        if (o.has("fx")) {
            it.fx = (float) o.optDouble("fx", Double.NaN);
            it.fy = (float) o.optDouble("fy", Double.NaN);
            it.fw = (float) o.optDouble("fw", Double.NaN);
            it.fh = (float) o.optDouble("fh", Double.NaN);
        }
        JSONArray a = o.optJSONArray("children");
        if (a != null) for (int i = 0; i < a.length(); i++) {
            JSONObject c = a.optJSONObject(i);
            if (c != null) it.children.add(fromJson(c));
        }
        return it;
    }
}
