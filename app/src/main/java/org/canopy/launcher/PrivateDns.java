/*
 * Copyright 2026 The Canopy Launcher authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.canopy.launcher;

import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;

/**
 * Switches Android's Private DNS (DNS-over-TLS) from the feed: a compact row of provider icons. Provider list in the spirit of ADNS
 * (github.com/eyalm2000/adns, MIT) — hostnames are the providers' public DoT addresses; no ADNS code is used.
 * Changing a system setting needs Shizuku, or a one-time ADB grant of WRITE_SECURE_SETTINGS.
 */
final class PrivateDns {
    private PrivateDns() { }

    /** {short name, hostname ("off" / "auto" for the modes), what it does, fallback colour, website for its icon}. */
    static final String[][] PROVIDERS = {
            {"Off", "off", "Normal DNS from your network", "#7A7F87", ""},
            {"Auto", "auto", "Encrypt when the network supports it", "#5B8DEF", ""},
            {"AdGuard", "dns.adguard-dns.com", "Blocks ads & trackers", "#68BC71", "adguard-dns.io"},
            {"AG Family", "family.adguard-dns.com", "Ads, trackers & adult sites", "#4E9A57", "adguard-dns.io"},
            {"AG Plain", "unfiltered.adguard-dns.com", "No filtering", "#8FCB95", "adguard-dns.io"},
            {"Cloudflare", "one.one.one.one", "Fast, no filtering", "#F38020", "one.one.one.one"},
            {"CF Secure", "security.cloudflare-dns.com", "Blocks malware", "#E06A10", "one.one.one.one"},
            {"CF Family", "family.cloudflare-dns.com", "Malware & adult sites", "#C85A08", "one.one.one.one"},
            {"Google", "dns.google", "No filtering", "#4285F4", "dns.google"},
            {"Quad9", "dns.quad9.net", "Blocks malware", "#D6285A", "quad9.net"},
            {"Quad9 Plain", "dns10.quad9.net", "No filtering", "#E0607F", "quad9.net"},
            {"OpenDNS", "dns.opendns.com", "Blocks phishing", "#F5A623", "opendns.com"},
            {"OD Family", "dns.familyshield.opendns.com", "Adult sites", "#D98E14", "opendns.com"},
    };

    // ---- providers you add yourself (e.g. your NextDNS address)

    static java.util.List<String[]> custom(Context c) {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        try {
            org.json.JSONArray a = new org.json.JSONArray(Prefs.sp(c).getString("pdns_providers", "[]"));
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONObject o = a.getJSONObject(i);
                out.add(new String[]{o.optString("name"), o.optString("host")});
            }
        } catch (Exception ignored) { }
        return out;
    }

    private static void saveCustom(Context c, java.util.List<String[]> l) {
        org.json.JSONArray a = new org.json.JSONArray();
        try { for (String[] p : l) a.put(new org.json.JSONObject().put("name", p[0]).put("host", p[1])); } catch (Exception ignored) { }
        Prefs.sp(c).edit().putString("pdns_providers", a.toString()).apply();
    }

    static void addCustom(Context c, String name, String host) {
        java.util.List<String[]> l = custom(c);
        for (String[] p : l) if (p[1].equalsIgnoreCase(host)) { p[0] = name; saveCustom(c, l); return; }
        l.add(new String[]{name, host});
        saveCustom(c, l);
    }

    static void removeCustom(Context c, String host) {
        java.util.List<String[]> l = custom(c);
        for (int i = l.size() - 1; i >= 0; i--) if (l.get(i)[1].equalsIgnoreCase(host)) l.remove(i);
        saveCustom(c, l);
    }

    static final String KEY_MODE = "private_dns_mode", KEY_HOST = "private_dns_specifier";

    /** Current setting as "off", "auto" or a hostname (or null if Android won't say). */
    static String current(Context c) {
        try {
            String mode = Settings.Global.getString(c.getContentResolver(), KEY_MODE);
            if (mode == null) return null;
            if ("off".equals(mode)) return "off";
            if ("opportunistic".equals(mode)) return "auto";
            String h = Settings.Global.getString(c.getContentResolver(), KEY_HOST);
            return h == null ? null : h.trim();
        } catch (Throwable t) {
            return Prefs.sp(c).getString("pdns_last", null); // some phones hide it: show what we last set
        }
    }

    static boolean canWriteDirectly(Context c) {
        return c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED;
    }

    static boolean canChange(Context c) { return canWriteDirectly(c) || ShizukuShell.running(); }

    /** Applies "off", "auto" or a hostname. Returns false if Canopy has no way to change it. */
    static boolean apply(final Context c, final String target) {
        final String mode = "off".equals(target) ? "off" : "auto".equals(target) ? "opportunistic" : "hostname";
        Prefs.sp(c).edit().putString("pdns_last", target).apply();
        if (canWriteDirectly(c)) {
            try {
                if ("hostname".equals(mode)) Settings.Global.putString(c.getContentResolver(), KEY_HOST, target);
                Settings.Global.putString(c.getContentResolver(), KEY_MODE, mode);
                return true;
            } catch (Throwable ignored) { }
        }
        if (!ShizukuShell.running()) return false;
        Runnable go = new Runnable() {
            @Override public void run() {
                String cmd = ("hostname".equals(mode) ? "settings put global " + KEY_HOST + " '" + target.replace("'", "") + "'; " : "")
                        + "settings put global " + KEY_MODE + " " + mode
                        // let Canopy change it directly next time, even without Shizuku running
                        + "; pm grant " + c.getPackageName() + " android.permission.WRITE_SECURE_SETTINGS";
                ShizukuShell.exec("sh", "-c", cmd);
            }
        };
        if (ShizukuShell.granted()) go.run();
        else ShizukuShell.requestPermission(go);
        return true;
    }

    static String adbCommand(Context c) {
        return "adb shell pm grant " + c.getPackageName() + " android.permission.WRITE_SECURE_SETTINGS";
    }
}
