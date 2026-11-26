/*
 * Copyright 2026 The Canopy Launcher authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.canopy.launcher;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.view.View;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keeps the list of installed, launchable apps (including work profile apps) up to date.
 * Launch counts are stored locally only, to power "Most used" sorting and the Glance page.
 */
public final class AppRepository {
    public interface Listener { void onAppsChanged(); }

    private static AppRepository sInstance;

    private final Context ctx;
    private final LauncherApps la;
    private final UserManager um;
    private final SharedPreferences usage;
    private volatile List<AppInfo> apps = new ArrayList<>();
    private volatile Map<String, AppInfo> byKey = new HashMap<>();
    private final List<Listener> listeners = new ArrayList<>();
    private boolean loaded;
    private int generation;

    private final Runnable reloadRunnable = new Runnable() {
        @Override public void run() { reload(); }
    };

    public static synchronized AppRepository get(Context c) {
        if (sInstance == null) sInstance = new AppRepository(c.getApplicationContext());
        return sInstance;
    }

    private AppRepository(Context c) {
        ctx = c;
        la = (LauncherApps) c.getSystemService(Context.LAUNCHER_APPS_SERVICE);
        um = (UserManager) c.getSystemService(Context.USER_SERVICE);
        usage = c.getSharedPreferences("usage", Context.MODE_PRIVATE);
        la.registerCallback(new LauncherApps.Callback() {
            @Override public void onPackageRemoved(String p, UserHandle u) { schedule(); }
            @Override public void onPackageAdded(String p, UserHandle u) { schedule(); }
            @Override public void onPackageChanged(String p, UserHandle u) { schedule(); }
            @Override public void onPackagesAvailable(String[] p, UserHandle u, boolean r) { schedule(); }
            @Override public void onPackagesUnavailable(String[] p, UserHandle u, boolean r) { schedule(); }
        }, App.MAIN);
    }

    private void schedule() {
        App.MAIN.removeCallbacks(reloadRunnable);
        App.MAIN.postDelayed(reloadRunnable, 350);
    }

    public LauncherApps launcherApps() { return la; }
    public boolean isLoaded() { return loaded; }
    public List<AppInfo> all() { return apps; }

    public void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    public void reload() {
        final int gen = ++generation;
        App.IO.execute(new Runnable() {
            @Override public void run() {
                final List<AppInfo> list = build();
                final Map<String, AppInfo> map = new HashMap<>();
                for (AppInfo a : list) map.put(a.key, a);
                App.MAIN.post(new Runnable() {
                    @Override public void run() {
                        if (gen != generation) return;
                        apps = list;
                        byKey = map;
                        loaded = true;
                        for (Listener l : new ArrayList<>(listeners)) l.onAppsChanged();
                    }
                });
            }
        });
    }

    private List<AppInfo> build() {
        IconPackManager.IconPack pack = IconPackManager.load(ctx, Prefs.iconPack(ctx));
        String shape = Prefs.iconShape(ctx);
        boolean wrap = Prefs.wrapLegacy(ctx);
        int density = ctx.getResources().getDisplayMetrics().densityDpi;
        List<AppInfo> out = new ArrayList<>();
        for (UserHandle u : um.getUserProfiles()) {
            long serial = um.getSerialNumberForUser(u);
            List<LauncherActivityInfo> list;
            try {
                list = la.getActivityList(null, u);
            } catch (Exception e) {
                continue;
            }
            boolean mainUser = u.equals(Process.myUserHandle());
            for (LauncherActivityInfo info : list) {
                AppInfo a = new AppInfo(info.getComponentName(), u, serial);
                a.label = String.valueOf(info.getLabel());
                a.installTime = info.getFirstInstallTime();
                a.work = !mainUser;
                Drawable d = null;
                if (pack != null && mainUser) d = pack.iconFor(info.getComponentName());
                if (d == null) {
                    try { d = info.getBadgedIcon(density); } catch (Exception e) { d = ctx.getPackageManager().getDefaultActivityIcon(); }
                    if (pack != null && mainUser && pack.themesUnmatched()) {
                        try { d = pack.themeFallback(d, a.key, ctx.getResources()); } catch (Throwable ignored) { }
                    } else if (mainUser) {
                        try { d = IconShapes.apply(d, shape, wrap, ctx.getResources()); } catch (Throwable ignored) { }
                    }
                }
                Drawable custom = IconEditor.icon(ctx, a.key);
                if (custom != null) d = custom;
                String customLabel = IconEditor.label(ctx, a.key);
                if (customLabel != null) a.label = customLabel;
                a.icon = d;
                out.add(a);
            }
        }
        final Collator col = Collator.getInstance();
        col.setStrength(Collator.PRIMARY);
        Collections.sort(out, new Comparator<AppInfo>() {
            @Override public int compare(AppInfo x, AppInfo y) { return col.compare(x.label, y.label); }
        });
        return out;
    }

    /**
     * An app's icon or name was edited: show it at once (drawer, home screen, folders, dock), without waiting
     * for every icon to be rebuilt. A custom icon/name is applied straight away; a reset is filled in by the
     * background reload that follows.
     */
    public void applyEdit(String key) {
        AppInfo a = byKey.get(key);
        if (a != null) {
            Drawable custom = IconEditor.icon(ctx, key);
            if (custom != null) a.icon = custom;
            String customLabel = IconEditor.label(ctx, key);
            if (customLabel != null) a.label = customLabel;
            for (Listener l : new ArrayList<>(listeners)) l.onAppsChanged();
        }
        reload();
    }

    public AppInfo find(String flatComponent, long serial) {
        if (flatComponent == null) return null;
        return byKey.get(AppInfo.keyOf(flatComponent, serial));
    }

    public AppInfo findByPackage(String pkg) {
        if (pkg == null) return null;
        long me = mySerial();
        for (AppInfo a : apps) if (a.pkg().equals(pkg) && a.serial == me) return a;
        return null;
    }

    public boolean hasWorkProfile() {
        for (AppInfo a : apps) if (a.work) return true;
        return false;
    }

    public long mySerial() { return um.getSerialNumberForUser(Process.myUserHandle()); }

    public UserHandle userFor(long serial) {
        UserHandle u = um.getUserForSerialNumber(serial);
        return u != null ? u : Process.myUserHandle();
    }

    public List<AppInfo> visible() {
        Set<String> hidden = Prefs.hidden(ctx);
        List<AppInfo> out = new ArrayList<>();
        for (AppInfo a : apps) if (!hidden.contains(a.key)) out.add(a);
        return out;
    }

    public List<AppInfo> topUsed(int n) {
        List<AppInfo> list = visible();
        final Map<String, Integer> counts = new HashMap<>();
        for (AppInfo a : list) counts.put(a.key, usage.getInt(a.key, 0));
        Collections.sort(list, new Comparator<AppInfo>() {
            @Override public int compare(AppInfo x, AppInfo y) { return counts.get(y.key) - counts.get(x.key); }
        });
        List<AppInfo> out = new ArrayList<>();
        for (AppInfo a : list) {
            if (out.size() >= n || counts.get(a.key) == 0) break;
            out.add(a);
        }
        return out;
    }

    public int usage(String key) { return usage.getInt(key, 0); }

    /**
     * The icon itself shows the chosen app-open animation too. Newer Android versions often replace the
     * window animation a launcher asks for with their own, so this makes the setting visible everywhere.
     */
    private void launchEffect(final View v, String style) {
        if ("system".equals(style)) return;
        final String st = style;
        // start right after the icon's press effect, so you see both
        App.MAIN.postDelayed(new Runnable() { @Override public void run() { launchEffectNow(v, st); } }, 90);
    }

    private void launchEffectNow(final View v, String style) {
        v.animate().cancel();
        long d = Anim.dur(ctx, 200);
        switch (style) {
            case "scale": v.animate().scaleX(1.35f).scaleY(1.35f).alpha(0f).setDuration(d).start(); break;
            case "reveal": v.animate().scaleX(0.7f).scaleY(0.7f).alpha(0f).setDuration(d).start(); break;
            case "fade": v.animate().alpha(0f).setDuration(d).start(); break;
            case "slide": v.animate().translationY(-Ui.dp(ctx, 28)).alpha(0f).setDuration(d).start(); break;
            default: return;
        }
        // put the icon back once the app has opened
        App.MAIN.postDelayed(new Runnable() {
            @Override public void run() {
                v.animate().cancel();
                v.setScaleX(1f); v.setScaleY(1f); v.setAlpha(1f); v.setTranslationY(0f);
            }
        }, 650);
    }

    /** True while the launcher shares the screen with another app: apps then open in its half. */
    public volatile boolean adjacent;

    /** Start an app next to the current one (split screen). False if it couldn't be done that way. */
    public boolean launchAdjacent(AppInfo a) {
        if (a.user != null && !a.user.equals(android.os.Process.myUserHandle())) return false; // work apps: normal launch
        try {
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_LAUNCHER).setComponent(a.component)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT
                            | android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            ctx.startActivity(i);
            usage.edit().putInt(a.key, usage.getInt(a.key, 0) + 1).apply();
            return true;
        } catch (Exception e) { return false; }
    }

    public void launch(AppInfo a, View from) {
        if (adjacent && launchAdjacent(a)) return;
        usage.edit().putInt(a.key, usage.getInt(a.key, 0) + 1).apply();
        Rect bounds = null;
        Bundle opts = null;
        if (from != null && from.isAttachedToWindow()) {
            int[] loc = new int[2];
            from.getLocationOnScreen(loc);
            bounds = new Rect(loc[0], loc[1], loc[0] + from.getWidth(), loc[1] + from.getHeight());
            // App-open animation: the phone's own (Canopy's custom ones were removed).
            String open = "system";
            if ("auto".equals(open)) open = Anim.fluid(ctx) ? "scale" : Anim.minimal(ctx) ? "fade" : "reveal";
            switch (open) {
                case "scale": opts = ActivityOptions.makeScaleUpAnimation(from, 0, 0, from.getWidth(), from.getHeight()).toBundle(); break;
                case "reveal": opts = ActivityOptions.makeClipRevealAnimation(from, 0, 0, from.getWidth(), from.getHeight()).toBundle(); break;
                case "fade": opts = ActivityOptions.makeCustomAnimation(ctx, android.R.anim.fade_in, android.R.anim.fade_out).toBundle(); break;
                case "slide": opts = ActivityOptions.makeCustomAnimation(ctx, android.R.anim.slide_in_left, android.R.anim.fade_out).toBundle(); break;
                case "system": default: opts = null; break;
            }
            launchEffect(from, open);
        }
        try {
            la.startMainActivity(a.component, a.user, bounds, opts);
        } catch (Exception e) {
            Ui.toast(ctx, "Can't open " + a.label);
        }
    }
}
