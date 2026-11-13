/*
 * Copyright 2026 The Canopy Launcher authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.canopy.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.UserManager;

import java.io.File;
import java.io.FileOutputStream;

/** Handles "Add to home screen" from other apps (e.g. a browser pinning a web page shortcut). */
public class AddShortcutActivity extends Activity {

    public static File iconFile(Context c, HomeItem it) {
        File dir = new File(c.getFilesDir(), "shortcut_icons");
        dir.mkdirs();
        String name = (it.pkg + "_" + it.shortcutId).replaceAll("[^A-Za-z0-9._-]", "_");
        return new File(dir, name + ".png");
    }

    private LauncherApps.PinItemRequest req;
    private ShortcutInfo si;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            LauncherApps la = (LauncherApps) getSystemService(Context.LAUNCHER_APPS_SERVICE);
            req = la.getPinItemRequest(getIntent());
            if (req == null || !req.isValid() || req.getRequestType() != LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT
                    || (si = req.getShortcutInfo()) == null) { finish(); return; }
        } catch (Exception e) { finish(); return; }
        String where = Prefs.sp(this).getString("pinned_target", "ask");
        if (!"ask".equals(where)) { place(where); finish(); return; }
        CharSequence label = si.getShortLabel() != null ? si.getShortLabel() : si.getLongLabel();
        Ui.dialog(this).setTitle("Add \u201C" + label + "\u201D to\u2026")
                .setItems(new String[]{"Home screen", "App drawer", "Both"}, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) { place(w == 0 ? "home" : w == 1 ? "drawer" : "both"); }
                })
                .setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
                    @Override public void onDismiss(android.content.DialogInterface d) { finish(); }
                }).show();
    }

    private void place(String where) {
        try {
            HomeItem it = prepare(this, si);
            if (!req.accept()) return;
            if (!"drawer".equals(where)) addToHome(this, it);
            if (!"home".equals(where)) DrawerShortcuts.add(this, it);
            Ui.toast(this, "Added \u201C" + it.title + "\u201D");
        } catch (Exception e) {
            Ui.toast(this, "Couldn't add shortcut");
        }
    }

    /** Builds the home item for a shortcut and saves its icon (we may not be allowed to ask for it later). */
    static HomeItem prepare(Context c, ShortcutInfo si) throws Exception {
        LauncherApps la = (LauncherApps) c.getSystemService(Context.LAUNCHER_APPS_SERVICE);
        UserManager um = (UserManager) c.getSystemService(Context.USER_SERVICE);
        HomeItem it = new HomeItem();
        it.type = HomeItem.SHORTCUT;
        it.pkg = si.getPackage();
        it.shortcutId = si.getId();
        it.serial = um.getSerialNumberForUser(si.getUserHandle());
        CharSequence label = si.getShortLabel() != null ? si.getShortLabel() : si.getLongLabel();
        it.title = label != null ? label.toString() : "Shortcut";
        Drawable d = null;
        try { d = la.getShortcutIconDrawable(si, c.getResources().getDisplayMetrics().densityDpi); } catch (Exception ignored) { }
        if (d != null) {
            int s = 192;
            Bitmap bm = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(bm);
            d.setBounds(0, 0, s, s);
            d.draw(cv);
            FileOutputStream out = new FileOutputStream(iconFile(c, it));
            bm.compress(Bitmap.CompressFormat.PNG, 100, out);
            out.close();
        }
        return it;
    }

    /** Puts a prepared shortcut on the first free home cell (adds a page if needed). Returns the page. */
    static int addToHome(Context c, HomeItem it) {
        HomeStore store = HomeStore.get(c);
        int cols = Prefs.gridCols(c), rows = Prefs.gridRows(c);
        int[] spot = null;
        int page = 0;
        for (int p = 0; p < store.pageCount() && spot == null; p++) {
            spot = store.findFirstFree(HomeItem.DESKTOP, p, 1, 1, cols, rows);
            if (spot != null) page = p;
        }
        if (spot == null) {
            store.addPage();
            page = store.pageCount() - 1;
            spot = new int[]{0, 0};
        }
        it.id = store.newId();
        it.container = HomeItem.DESKTOP;
        it.page = page;
        it.x = spot[0];
        it.y = spot[1];
        store.addTop(it);
        store.commit();
        return page;
    }
}
