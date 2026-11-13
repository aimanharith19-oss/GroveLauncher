/*
 * Copyright 2026 The Canopy Launcher authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.canopy.launcher;

import android.content.Context;
import android.content.res.XmlResourceParser;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Finds an app's website, so an app tab in the feed can open inside the feed like a browser tab
 * (Android doesn't let one app show another app's screens inside it). Well-known apps use their
 * proper web app; for others, the web links the app itself declares in its manifest are used.
 */
final class AppWeb {
    private AppWeb() { }

    private static final Map<String, String> KNOWN = new HashMap<>();
    static {
        String[][] k = {
                {"com.twitter.android", "https://x.com/home"},
                {"com.google.android.youtube", "https://m.youtube.com/"},
                {"com.google.android.apps.youtube.music", "https://music.youtube.com/"},
                {"com.instagram.android", "https://www.instagram.com/"},
                {"com.instagram.barcelona", "https://www.threads.net/"},
                {"com.facebook.katana", "https://m.facebook.com/"},
                {"com.facebook.lite", "https://m.facebook.com/"},
                {"com.facebook.orca", "https://www.messenger.com/"},
                {"com.reddit.frontpage", "https://www.reddit.com/"},
                {"com.zhiliaoapp.musically", "https://www.tiktok.com/"},
                {"com.ss.android.ugc.trill", "https://www.tiktok.com/"},
                {"com.linkedin.android", "https://www.linkedin.com/"},
                {"com.spotify.music", "https://open.spotify.com/"},
                {"com.netflix.mediaclient", "https://www.netflix.com/"},
                {"com.google.android.gm", "https://mail.google.com/"},
                {"com.google.android.apps.maps", "https://www.google.com/maps"},
                {"com.google.android.keep", "https://keep.google.com/"},
                {"com.google.android.calendar", "https://calendar.google.com/"},
                {"com.google.android.apps.docs", "https://drive.google.com/"},
                {"com.google.android.apps.photos", "https://photos.google.com/"},
                {"com.google.android.apps.translate", "https://translate.google.com/"},
                {"com.google.android.googlequicksearchbox", "https://www.google.com/"},
                {"com.google.android.apps.magazines", "https://news.google.com/"},
                {"org.telegram.messenger", "https://web.telegram.org/"},
                {"com.whatsapp", "https://web.whatsapp.com/"},
                {"com.discord", "https://discord.com/app"},
                {"com.pinterest", "https://www.pinterest.com/"},
                {"tv.twitch.android.app", "https://m.twitch.tv/"},
                {"com.snapchat.android", "https://web.snapchat.com/"},
                {"com.microsoft.office.outlook", "https://outlook.live.com/"},
                {"com.microsoft.teams", "https://teams.microsoft.com/"},
                {"com.github.android", "https://github.com/"},
                {"com.medium.reader", "https://medium.com/"},
                {"com.quora.android", "https://www.quora.com/"},
                {"com.tumblr", "https://www.tumblr.com/"},
                {"com.amazon.mShop.android.shopping", "https://www.amazon.com/"},
                {"com.shopee.my", "https://shopee.com.my/"},
                {"com.lazada.android", "https://www.lazada.com.my/"},
                {"com.openai.chatgpt", "https://chatgpt.com/"},
                {"com.anthropic.claude", "https://claude.ai/"},
                {"com.duolingo", "https://www.duolingo.com/"},
                {"com.soundcloud.android", "https://m.soundcloud.com/"},
                {"com.imdb.mobile", "https://m.imdb.com/"},
                {"com.wikipedia", "https://en.m.wikipedia.org/"},
                {"org.wikipedia", "https://en.m.wikipedia.org/"},
        };
        for (String[] e : k) KNOWN.put(e[0], e[1]);
    }

    private static final String NS = "http://schemas.android.com/apk/res/android";

    /** The app's website, or null when it doesn't have one. */
    static String urlFor(Context c, String pkg) {
        if (pkg == null) return null;
        String known = KNOWN.get(pkg);
        if (known != null) return known;
        String host = fromManifest(c, pkg);
        return host == null ? null : "https://" + host + "/";
    }

    /** Reads the web links (https hosts) the app opens, from its own manifest, and picks its main one. */
    private static String fromManifest(Context c, String pkg) {
        XmlResourceParser p = null;
        try {
            p = c.createPackageContext(pkg, 0).getAssets().openXmlResourceParser("AndroidManifest.xml");
            Map<String, Integer> score = new LinkedHashMap<>();
            boolean inFilter = false, view = false, browsable = false, verified = false;
            java.util.List<String> hosts = new java.util.ArrayList<>();
            int ev;
            while ((ev = p.next()) != XmlResourceParser.END_DOCUMENT) {
                String n = p.getName();
                if (ev == XmlResourceParser.START_TAG) {
                    if ("intent-filter".equals(n)) {
                        inFilter = true; view = browsable = false;
                        verified = "true".equals(p.getAttributeValue(NS, "autoVerify"));
                        hosts.clear();
                    } else if (inFilter && "action".equals(n)) {
                        if ("android.intent.action.VIEW".equals(p.getAttributeValue(NS, "name"))) view = true;
                    } else if (inFilter && "category".equals(n)) {
                        if ("android.intent.category.BROWSABLE".equals(p.getAttributeValue(NS, "name"))) browsable = true;
                    } else if (inFilter && "data".equals(n)) {
                        String h = p.getAttributeValue(NS, "host");
                        if (h != null && !h.startsWith("@")) {
                            if (h.startsWith("*.")) h = h.substring(2);
                            if (h.indexOf('.') > 0 && !h.contains("*")) hosts.add(h.toLowerCase());
                        }
                    }
                } else if (ev == XmlResourceParser.END_TAG && "intent-filter".equals(n)) {
                    if (view && browsable) for (String h : hosts) {
                        int s = (verified ? 5 : 1) + (h.startsWith("www.") || h.startsWith("m.") ? 1 : 0);
                        Integer old = score.get(h);
                        score.put(h, (old == null ? 0 : old) + s);
                    }
                    inFilter = false;
                }
            }
            String best = null;
            int bs = 0;
            for (Map.Entry<String, Integer> e : score.entrySet()) {
                // short links (t.co, goo.gl…) and deep-link-only hosts aren't websites to browse
                String h = e.getKey();
                if (h.length() <= 6 || h.startsWith("link.") || h.startsWith("links.") || h.contains("onelink")) continue;
                if (e.getValue() > bs) { bs = e.getValue(); best = h; }
            }
            return best;
        } catch (Throwable t) {
            return null;
        } finally {
            if (p != null) p.close();
        }
    }
}
