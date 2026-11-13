/*
 * Copyright 2026 The Canopy Launcher authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.canopy.launcher;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads uBlock Origin / EasyList style filter lists (the same lists uBlock Origin uses) and keeps the parts
 * a WebView can apply: domain blocking ("||ads.example^"), exceptions ("@@||…^") and site-specific element
 * hiding ("example.com##.ad-banner"). Scriptlets and other extension-only filters are skipped.
 * Plain Java, so it can also be tested off-device.
 */
public final class AdRules {
    public final Set<String> block = new HashSet<>();
    public final Set<String> block3p = new HashSet<>();
    public final Set<String> allow = new HashSet<>();
    /** host -> CSS selectors to hide on that site (and its subdomains). Entries starting with '@' are whole CSS rules. */
    public final Map<String, List<String>> hide = new HashMap<>();
    /** host -> path patterns ("||host/path*^" rules), and the third-party-only ones. */
    public final Map<String, List<String>> paths = new HashMap<>(), paths3p = new HashMap<>();

    private static final Set<String> OK_OPTS = new HashSet<>();
    static {
        for (String o : new String[]{"third-party", "3p", "script", "image", "xmlhttprequest", "xhr", "subdocument",
                "frame", "all", "important", "media", "object", "ping", "stylesheet", "css", "font", "websocket", "other"})
            OK_OPTS.add(o);
    }

    public int size() { return block.size() + block3p.size() + count(paths) + count(paths3p); }

    private static int count(Map<String, List<String>> m) { int n = 0; for (List<String> l : m.values()) n += l.size(); return n; }

    private static void put(Map<String, List<String>> m, String k, String v) {
        List<String> l = m.get(k);
        if (l == null) m.put(k, l = new ArrayList<>());
        l.add(v);
    }

    public void parse(BufferedReader r) throws IOException {
        String line;
        while ((line = r.readLine()) != null) parseLine(line.trim());
    }

    public void parseLine(String l) {
        if (l.isEmpty() || l.startsWith("!") || l.startsWith("[") || l.startsWith("#")) return;
        // hosts-file format (Peter Lowe's list)
        if (l.startsWith("127.0.0.1 ") || l.startsWith("0.0.0.0 ")) {
            String h = l.substring(l.indexOf(' ') + 1).trim();
            if (isDomain(h)) block.add(h.toLowerCase());
            return;
        }
        int cos = l.indexOf("##");
        if (cos > 0 && l.indexOf("#@#") < 0 && l.indexOf("#?#") < 0 && l.indexOf("#$#") < 0) {
            cosmetic(l.substring(0, cos), l.substring(cos + 2));
            return;
        }
        if (cos >= 0 || l.contains("#@#") || l.contains("#?#") || l.contains("#$#") || l.contains("#%#")) return;
        boolean exception = l.startsWith("@@");
        if (exception) l = l.substring(2);
        if (!l.startsWith("||")) return;
        // host part ends at the first ^, / or *
        int end = 2;
        while (end < l.length() && "^/*$".indexOf(l.charAt(end)) < 0) end++;
        String host = l.substring(2, end);
        if (!isDomain(host) || end >= l.length()) return;
        int dollar = l.indexOf('$', end);
        String pattern = l.substring(end, dollar < 0 ? l.length() : dollar);
        if (!pattern.startsWith("^") || pattern.length() > 1) {
            // a path rule, e.g. ||redditstatic.com/shreddit/*/xpromo-modal-$script
            if (exception || pattern.length() < 2 || pattern.charAt(0) == '*') return;
            boolean tp = false;
            if (dollar >= 0) for (String o : l.substring(dollar + 1).split(",")) {
                if (!OK_OPTS.contains(o)) return;
                if (o.equals("third-party") || o.equals("3p")) tp = true;
            }
            put(tp ? paths3p : paths, host.toLowerCase(), pattern);
            return;
        }
        String rest = l.substring(end + 1);
        boolean thirdParty = false;
        if (!rest.isEmpty()) {
            if (rest.charAt(0) != '$') return; // a path after the domain: not a whole-domain rule
            for (String o : rest.substring(1).split(",")) {
                if (!OK_OPTS.contains(o)) return; // domain=, ~third-party, redirect, removeparam, badfilter…
                if (o.equals("third-party") || o.equals("3p")) thirdParty = true;
            }
        }
        host = host.toLowerCase();
        if (exception) allow.add(host);
        else if (thirdParty) block3p.add(host);
        else block.add(host);
    }

    private void cosmetic(String domains, String selector) {
        // "selector:style(css)" → a whole CSS rule (e.g. reddit.com##body:style(overflow: auto !important;))
        int st = selector.indexOf(":style(");
        if (st > 0 && selector.endsWith(")") && selector.indexOf(":style(", st + 1) < 0) {
            String sel = selector.substring(0, st), css = selector.substring(st + 7, selector.length() - 1);
            if (!sel.contains(":has-text(") && !sel.contains(":-abp-") && !sel.contains(":upward(") && !css.contains("{") && !css.contains("}")
                    && !css.contains("url(")) {
                for (String d : domains.split(",")) {
                    d = d.trim().toLowerCase();
                    if (d.isEmpty() || d.startsWith("~") || d.contains("*") || d.contains("/") || !isDomain(d)) continue;
                    put(hide, d, "@" + sel + "{" + css + "}");
                }
            }
            return;
        }
        if (selector.startsWith("+js(") || selector.contains(":has-text(") || selector.contains(":-abp-")
                || selector.contains(":upward(") || selector.contains(":remove(") || selector.contains(":style(")
                || selector.contains(":xpath(") || selector.contains(":matches-") || selector.contains(":watch-attr(")
                || selector.contains(":min-text-length(") || selector.contains(":others(") || selector.startsWith("^")
                || selector.contains(":matches-path(") || selector.contains(":remove-attr(") || selector.contains(":remove-class(")
                || selector.contains("{") || selector.isEmpty()) return;
        for (String d : domains.split(",")) {
            d = d.trim().toLowerCase();
            if (d.isEmpty() || d.startsWith("~") || d.contains("*") || d.contains("/") || !isDomain(d)) continue;
            List<String> l = hide.get(d);
            if (l == null) hide.put(d, l = new ArrayList<>());
            if (l.size() < 400) l.add(selector);
        }
    }

    static boolean isDomain(String h) {
        if (h.length() < 3 || h.indexOf('.') < 0) return false;
        for (int i = 0; i < h.length(); i++) {
            char c = h.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '_')) return false;
        }
        return true;
    }

    /** example: "a.b.c.com" → "c.com" (rough: good enough to tell first- from third-party). */
    static String site(String host) {
        if (host == null) return "";
        String[] p = host.split("\\.");
        int n = p.length;
        if (n <= 2) return host;
        String tld2 = p[n - 2];
        boolean sld = tld2.length() <= 3 && (tld2.equals("co") || tld2.equals("com") || tld2.equals("net") || tld2.equals("org")
                || tld2.equals("gov") || tld2.equals("edu") || tld2.equals("ac") || tld2.equals("or") || tld2.equals("ne"));
        int keep = sld && p[n - 1].length() == 2 ? 3 : 2;
        StringBuilder sb = new StringBuilder();
        for (int i = n - keep; i < n; i++) { if (sb.length() > 0) sb.append('.'); sb.append(p[i]); }
        return sb.toString();
    }

    /** Like shouldBlock, but also checks path rules against the request's path (+ query). */
    public boolean shouldBlock(String requestHost, String path, String pageHost) {
        if (shouldBlock(requestHost, pageHost)) return true;
        if (requestHost == null || path == null || (paths.isEmpty() && paths3p.isEmpty())) return false;
        String h = requestHost.toLowerCase();
        boolean third = pageHost == null || !site(h).equals(site(pageHost.toLowerCase()));
        String cur = h;
        while (true) {
            if (allow.contains(cur)) return false;
            if (matchAny(paths.get(cur), path) || (third && matchAny(paths3p.get(cur), path))) return true;
            int dot = cur.indexOf('.');
            if (dot < 0 || cur.indexOf('.', dot + 1) < 0) break;
            cur = cur.substring(dot + 1);
        }
        return false;
    }

    private static boolean matchAny(List<String> pats, String path) {
        if (pats == null) return false;
        for (String p : pats) if (glob(p, 0, path, 0)) return true;
        return false;
    }

    /** Adblock pattern match, anchored at the start of the path: '*' = anything, '^' = a separator or the end. */
    static boolean glob(String p, int pi, String s, int si) {
        while (pi < p.length()) {
            char c = p.charAt(pi);
            if (c == '*') {
                while (pi < p.length() && p.charAt(pi) == '*') pi++;
                if (pi == p.length()) return true;
                for (int k = si; k <= s.length(); k++) if (glob(p, pi, s, k)) return true;
                return false;
            }
            if (c == '^') {
                if (si == s.length()) { pi++; continue; }
                char x = s.charAt(si);
                if (Character.isLetterOrDigit(x) || x == '_' || x == '-' || x == '.' || x == '%') return false;
                pi++; si++;
                continue;
            }
            if (si >= s.length() || s.charAt(si) != c) return false;
            pi++; si++;
        }
        return true; // a pattern may end before the URL does
    }

    /** Should a sub-resource from requestHost, loaded by a page on pageHost, be blocked? */
    public boolean shouldBlock(String requestHost, String pageHost) {
        if (requestHost == null) return false;
        String h = requestHost.toLowerCase();
        boolean third = pageHost == null || !site(h).equals(site(pageHost.toLowerCase()));
        String cur = h;
        boolean hit = false;
        while (true) {
            if (allow.contains(cur)) return false;
            if (!hit && (block.contains(cur) || (third && block3p.contains(cur)))) hit = true;
            int dot = cur.indexOf('.');
            if (dot < 0 || cur.indexOf('.', dot + 1) < 0) break;
            cur = cur.substring(dot + 1);
        }
        return hit;
    }

    /** CSS that hides the known ad/annoyance elements on this site, or null. */
    public String cssFor(String pageHost) {
        if (pageHost == null) return null;
        StringBuilder sb = new StringBuilder(), raw = new StringBuilder();
        String cur = pageHost.toLowerCase();
        while (true) {
            List<String> l = hide.get(cur);
            if (l != null) for (String s : l) {
                if (s.startsWith("@")) { raw.append(s.substring(1)).append('\n'); continue; }
                if (sb.length() > 0) sb.append(",\n");
                sb.append(s);
            }
            int dot = cur.indexOf('.');
            if (dot < 0) break;
            cur = cur.substring(dot + 1);
        }
        if (sb.length() == 0 && raw.length() == 0) return null;
        if (sb.length() > 0) sb.append("{display:none!important}\n");
        return sb.append(raw).toString();
    }

    /** Compact form for storing / bundling: one rule per line. */
    public void write(Appendable out) throws IOException {
        for (String d : block) out.append("b ").append(d).append('\n');
        for (String d : block3p) out.append("t ").append(d).append('\n');
        for (String d : allow) out.append("a ").append(d).append('\n');
        for (Map.Entry<String, List<String>> e : hide.entrySet())
            for (String s : e.getValue()) out.append("c ").append(e.getKey()).append(' ').append(s).append('\n');
        for (Map.Entry<String, List<String>> e : paths.entrySet())
            for (String s : e.getValue()) out.append("p ").append(e.getKey()).append(' ').append(s).append('\n');
        for (Map.Entry<String, List<String>> e : paths3p.entrySet())
            for (String s : e.getValue()) out.append("q ").append(e.getKey()).append(' ').append(s).append('\n');
    }

    public void readCompact(BufferedReader r) throws IOException {
        String l;
        while ((l = r.readLine()) != null) {
            if (l.length() < 3 || l.charAt(1) != ' ') continue;
            String v = l.substring(2);
            switch (l.charAt(0)) {
                case 'b': block.add(v); break;
                case 't': block3p.add(v); break;
                case 'a': allow.add(v); break;
                case 'p': case 'q': {
                    int sp = v.indexOf(' ');
                    if (sp > 0) put(l.charAt(0) == 'p' ? paths : paths3p, v.substring(0, sp), v.substring(sp + 1));
                    break;
                }
                case 'c': {
                    int sp = v.indexOf(' ');
                    if (sp <= 0) break;
                    String host = v.substring(0, sp);
                    List<String> list = hide.get(host);
                    if (list == null) hide.put(host, list = new ArrayList<>());
                    list.add(v.substring(sp + 1));
                    break;
                }
            }
        }
    }
}
