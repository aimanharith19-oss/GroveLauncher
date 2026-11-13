/*
 * Copyright 2026 The Canopy Launcher authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.canopy.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads third-party icon packs using the widely supported ADW/Nova "appfilter.xml" format:
 * &lt;item component="ComponentInfo{pkg/cls}" drawable="name"/&gt; plus optional
 * iconback / iconmask / iconupon / scale for theming apps the pack doesn't cover.
 */
public final class IconPackManager {
    private static final String[] PACK_ACTIONS = {"org.adw.launcher.THEMES", "com.novalauncher.THEME"};

    private IconPackManager() {}

    public static final class PackInfo {
        public String pkg, label;
        public Drawable icon;
    }

    public static List<PackInfo> listPacks(Context c) {
        PackageManager pm = c.getPackageManager();
        Map<String, PackInfo> out = new LinkedHashMap<>();
        for (String action : PACK_ACTIONS) {
            List<ResolveInfo> l = pm.queryIntentActivities(new Intent(action), 0);
            for (ResolveInfo ri : l) {
                String pkg = ri.activityInfo.packageName;
                if (out.containsKey(pkg)) continue;
                PackInfo p = new PackInfo();
                p.pkg = pkg;
                p.label = String.valueOf(ri.loadLabel(pm));
                try { p.icon = ri.loadIcon(pm); } catch (Exception ignored) { }
                out.put(pkg, p);
            }
        }
        return new ArrayList<>(out.values());
    }

    public static final class IconPack {
        final String pkg;
        final Resources res;
        final Map<String, String> map = new HashMap<>();
        final List<Bitmap> backs = new ArrayList<>();
        Bitmap mask, upon;
        float scale = 1f;

        IconPack(String pkg, Resources res) { this.pkg = pkg; this.res = res; }

        public Drawable iconFor(ComponentName cn) {
            String name = map.get(cn.getPackageName() + "/" + cn.getClassName());
            if (name == null) return null;
            return drawable(name);
        }

        Drawable drawable(String name) {
            try {
                int id = res.getIdentifier(name, "drawable", pkg);
                if (id == 0) id = res.getIdentifier(name, "mipmap", pkg);
                if (id == 0) return null;
                return res.getDrawable(id, null);
            } catch (Exception e) {
                return null;
            }
        }

        Bitmap bitmap(String name) {
            Drawable d = drawable(name);
            if (d == null) return null;
            if (d instanceof BitmapDrawable) return ((BitmapDrawable) d).getBitmap();
            return render(d, 192);
        }

        public boolean themesUnmatched() { return !backs.isEmpty(); }

        /** Draw an unmatched app icon onto the pack's backplate, like Nova does. */
        public Drawable themeFallback(Drawable original, String key, Resources outRes) {
            final int size = 192;
            Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            Bitmap back = backs.get(Math.abs(key.hashCode()) % backs.size());
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
            c.drawBitmap(Bitmap.createScaledBitmap(back, size, size, true), 0, 0, p);
            int inner = Math.round(size * scale);
            int off = (size - inner) / 2;
            Bitmap icon = render(original, inner);
            Bitmap layer = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas lc = new Canvas(layer);
            lc.drawBitmap(icon, off, off, p);
            if (mask != null) {
                Paint mp = new Paint(Paint.FILTER_BITMAP_FLAG);
                mp.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
                lc.drawBitmap(Bitmap.createScaledBitmap(mask, size, size, true), 0, 0, mp);
            }
            c.drawBitmap(layer, 0, 0, p);
            if (upon != null) c.drawBitmap(Bitmap.createScaledBitmap(upon, size, size, true), 0, 0, p);
            return new BitmapDrawable(outRes, out);
        }
    }

    static Bitmap render(Drawable d, int size) {
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, size, size);
        d.draw(c);
        return b;
    }

    /** Returns null for "system default" or when the pack can't be read. */
    public static IconPack load(Context c, String pkg) {
        if (TextUtils.isEmpty(pkg)) return null;
        try {
            Resources res = c.getPackageManager().getResourcesForApplication(pkg);
            IconPack pack = new IconPack(pkg, res);
            XmlPullParser parser = null;
            InputStream in = null;
            int xmlId = res.getIdentifier("appfilter", "xml", pkg);
            if (xmlId != 0) {
                parser = res.getXml(xmlId);
            } else {
                try {
                    in = res.getAssets().open("appfilter.xml");
                    XmlPullParserFactory f = XmlPullParserFactory.newInstance();
                    parser = f.newPullParser();
                    parser.setInput(in, "UTF-8");
                } catch (Exception ignored) { }
            }
            if (parser == null) return null;
            List<String> backNames = new ArrayList<>();
            String maskName = null, uponName = null;
            int ev = parser.getEventType();
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    String tag = parser.getName();
                    if ("item".equals(tag)) {
                        String comp = parser.getAttributeValue(null, "component");
                        String draw = parser.getAttributeValue(null, "drawable");
                        String key = normalize(comp);
                        if (key != null && draw != null && !pack.map.containsKey(key)) pack.map.put(key, draw);
                    } else if ("iconback".equals(tag)) {
                        for (int i = 0; i < parser.getAttributeCount(); i++) {
                            if (parser.getAttributeName(i).startsWith("img")) backNames.add(parser.getAttributeValue(i));
                        }
                    } else if ("iconmask".equals(tag)) {
                        maskName = parser.getAttributeValue(null, "img1");
                    } else if ("iconupon".equals(tag)) {
                        uponName = parser.getAttributeValue(null, "img1");
                    } else if ("scale".equals(tag)) {
                        try { pack.scale = Float.parseFloat(parser.getAttributeValue(null, "factor")); } catch (Exception ignored) { }
                    }
                }
                ev = parser.next();
            }
            if (in != null) in.close();
            for (String b : backNames) {
                Bitmap bm = pack.bitmap(b);
                if (bm != null) pack.backs.add(bm);
            }
            if (maskName != null) pack.mask = pack.bitmap(maskName);
            if (uponName != null) pack.upon = pack.bitmap(uponName);
            if (pack.scale <= 0f || pack.scale > 1.5f) pack.scale = 1f;
            return pack;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Every icon name a pack offers (from appfilter.xml and drawable.xml), with the pack's own
     * match for {@code first} (if any) at the top.
     */
    public static List<String> drawableNames(Context c, String pkg, ComponentName first) {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        IconPack pack = load(c, pkg);
        if (pack != null) {
            if (first != null) {
                String m = pack.map.get(first.getPackageName() + "/" + first.getClassName());
                if (m != null) names.add(m);
            }
            List<String> vals = new ArrayList<>(new java.util.TreeSet<>(pack.map.values()));
            names.addAll(vals);
        }
        try {
            Resources res = c.getPackageManager().getResourcesForApplication(pkg);
            int id = res.getIdentifier("drawable", "xml", pkg);
            if (id != 0) {
                XmlPullParser p = res.getXml(id);
                int ev = p.getEventType();
                java.util.TreeSet<String> extra = new java.util.TreeSet<>();
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG && "item".equals(p.getName())) {
                        String d = p.getAttributeValue(null, "drawable");
                        if (d != null) extra.add(d);
                    }
                    ev = p.next();
                }
                names.addAll(extra);
            }
        } catch (Exception ignored) { }
        return new ArrayList<>(names);
    }

    /** "ComponentInfo{com.foo/com.foo.Bar}" or ".../.Bar" -> "com.foo/com.foo.Bar" */
    static String normalize(String comp) {
        if (comp == null) return null;
        int s = comp.indexOf('{'), e = comp.indexOf('}');
        if (s < 0 || e < s) return null;
        String inner = comp.substring(s + 1, e);
        int slash = inner.indexOf('/');
        if (slash <= 0) return null;
        String pkg = inner.substring(0, slash);
        String cls = inner.substring(slash + 1);
        if (cls.startsWith(".")) cls = pkg + cls;
        return pkg + "/" + cls;
    }
}
