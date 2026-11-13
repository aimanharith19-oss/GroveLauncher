/*
 * Copyright 2026 The Canopy Launcher authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.canopy.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Website icons (favicons) for small things in the feed, like the Private DNS providers. Downloaded once from
 * the site itself (its apple-touch-icon / icon link, else /favicon.ico), then kept in Canopy's files. Nothing
 * else is sent; if a site can't be reached, the coloured initials are shown and it's retried a day later.
 */
final class SiteIcons {
    private SiteIcons() { }

    public interface Done { void done(); }

    private static final Map<String, Bitmap> mem = new HashMap<>();
    private static final Set<String> loading = new HashSet<>();
    private static final long RETRY = 24L * 3600 * 1000;
    private static final Pattern LINK = Pattern.compile("<link\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern REL = Pattern.compile("rel\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern HREF = Pattern.compile("href\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "siteicons");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    private static File file(Context c, String site) { return new File(dir(c), site.replaceAll("[^a-zA-Z0-9.-]", "_") + ".png"); }

    /** The cached icon, or null (then call {@link #fetch}). */
    static Bitmap get(Context c, String site) {
        if (site == null) return null;
        Bitmap b = mem.get(site);
        if (b != null) return b;
        File f = file(c, site);
        if (f.length() > 0) {
            b = BitmapFactory.decodeFile(f.getPath());
            if (b != null) mem.put(site, b);
        }
        return b;
    }

    /** Keeps an icon the page itself gave us, so the tab never loses it (while refreshing, frozen or offline). */
    static void put(Context c, String site, Bitmap b) {
        if (site == null || b == null || mem.containsKey(site)) return;
        mem.put(site, b);
        try {
            Bitmap s = b.getWidth() > 128 ? Bitmap.createScaledBitmap(b, 128, 128, true) : b;
            FileOutputStream out = new FileOutputStream(file(c, site));
            s.compress(Bitmap.CompressFormat.PNG, 100, out);
            out.close();
        } catch (Throwable ignored) { }
    }

    /** Downloads the site's icon in the background; {@code done} runs on the main thread if one was found. */
    static void fetch(final Context ctx, final String site, final Done done) {
        if (site == null) return;
        final Context c = ctx.getApplicationContext();
        final File f = file(c, site);
        if (f.exists() && f.length() == 0 && System.currentTimeMillis() - f.lastModified() < RETRY) return; // failed recently
        synchronized (loading) { if (!loading.add(site)) return; }
        App.IO.execute(new Runnable() {
            @Override public void run() {
                Bitmap b = null;
                try {
                    String base = "https://" + site + "/";
                    String html = text(base);
                    if (html != null) {
                        String best = null;
                        int bestScore = -1;
                        Matcher m = LINK.matcher(html);
                        while (m.find()) {
                            String tag = m.group();
                            Matcher r = REL.matcher(tag), h = HREF.matcher(tag);
                            if (!r.find() || !h.find()) continue;
                            String rel = r.group(1).toLowerCase(java.util.Locale.ROOT), href = h.group(1);
                            if (!rel.contains("icon") || href.toLowerCase(java.util.Locale.ROOT).endsWith(".svg") || href.startsWith("data:")) continue;
                            int score = rel.contains("apple-touch") ? 3 : tag.contains("192") || tag.contains("180") || tag.contains("96") ? 2 : 1;
                            if (score > bestScore) { bestScore = score; best = new URL(new URL(base), href.replace("&amp;", "&")).toString(); }
                        }
                        if (best != null) b = image(best);
                    }
                    if (b == null) b = image(base + "favicon.ico");
                } catch (Throwable ignored) { }
                try {
                    if (b != null) {
                        int s = 128;
                        if (b.getWidth() > s || b.getHeight() > s) b = Bitmap.createScaledBitmap(b, s, s, true);
                        FileOutputStream out = new FileOutputStream(f);
                        b.compress(Bitmap.CompressFormat.PNG, 100, out);
                        out.close();
                    } else {
                        new FileOutputStream(f).close(); // empty marker: don't retry for a day
                    }
                } catch (Throwable ignored) { }
                synchronized (loading) { loading.remove(site); }
                final Bitmap fb = b;
                if (fb != null) App.MAIN.post(new Runnable() {
                    @Override public void run() { mem.put(site, fb); if (done != null) done.done(); }
                });
            }
        });
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(8000);
        con.setReadTimeout(8000);
        con.setInstanceFollowRedirects(true);
        con.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Mobile Safari/537.36");
        return con;
    }

    private static byte[] bytes(String url, int max) throws Exception {
        HttpURLConnection con = open(url);
        try {
            if (con.getResponseCode() / 100 != 2) return null;
            InputStream in = con.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0 && out.size() < max) out.write(buf, 0, n);
            in.close();
            return out.toByteArray();
        } finally { con.disconnect(); }
    }

    private static String text(String url) {
        try { byte[] b = bytes(url, 300_000); return b == null ? null : new String(b, "UTF-8"); } catch (Throwable t) { return null; }
    }

    private static Bitmap image(String url) {
        try {
            byte[] b = bytes(url, 1_000_000);
            return b == null ? null : BitmapFactory.decodeByteArray(b, 0, b.length);
        } catch (Throwable t) { return null; }
    }

    /** The website behind a DNS hostname, e.g. abc.dns.nextdns.io → nextdns.io. */
    static String siteOf(String host) {
        if (host == null || !host.contains(".")) return null;
        String[] p = host.toLowerCase(java.util.Locale.ROOT).split("\\.");
        int n = p.length;
        if (n <= 2) return host.toLowerCase(java.util.Locale.ROOT);
        // country domains like example.co.uk / example.com.my keep three parts
        boolean cc = p[n - 1].length() == 2 && p[n - 2].length() <= 3;
        return cc ? p[n - 3] + "." + p[n - 2] + "." + p[n - 1] : p[n - 2] + "." + p[n - 1];
    }
}
