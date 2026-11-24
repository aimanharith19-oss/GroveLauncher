package org.canopy.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Full backup: one .zip with the settings, the home layout, every other preference file (feed cards, usage,
 * custom icons), and the data files (song history, notification history and archive, website history,
 * custom and shortcut icons). Older .json backups (settings + layout only) can still be restored.
 */
final class Backup {
    private Backup() { }

    static final String FORMAT = "canopy-backup-2";
    private static final String[] PREF_FILES = {"usage", "glance", "web_cards", "custom_icons"};
    private static final String[] FILES = {"music_history.tsv", "notification_history.tsv", "notification_archive.tsv", "web_history.json"};
    private static final String[] DIRS = {"custom_icons", "shortcut_icons"};

    static void write(Context c, OutputStream os) throws Exception {
        ZipOutputStream z = new ZipOutputStream(os);
        JSONObject o = new JSONObject();
        o.put("format", FORMAT);
        o.put("prefs", prefsToJson(Prefs.sp(c)));
        JSONObject others = new JSONObject();
        for (String n : PREF_FILES) others.put(n, prefsToJson(c.getSharedPreferences(n, Context.MODE_PRIVATE)));
        o.put("otherPrefs", others);
        o.put("home", new JSONObject(HomeStore.get(c).toJson()));
        put(z, "backup.json", o.toString(2).getBytes(StandardCharsets.UTF_8));
        File dir = c.getFilesDir();
        for (String n : FILES) { File f = new File(dir, n); if (f.isFile()) put(z, "files/" + n, read(f)); }
        for (String d : DIRS) {
            File[] list = new File(dir, d).listFiles();
            if (list != null) for (File f : list) if (f.isFile()) put(z, "files/" + d + "/" + f.getName(), read(f));
        }
        z.finish();
        z.flush();
    }

    /** Restores a backup (zip or the old json). Returns true when data files were restored too. */
    static boolean restore(Context c, InputStream in) throws Exception {
        byte[] all = readAll(in);
        if (all.length > 1 && all[0] == 'P' && all[1] == 'K') {
            JSONObject o = null;
            java.util.Map<String, byte[]> files = new java.util.LinkedHashMap<>();
            ZipInputStream z = new ZipInputStream(new java.io.ByteArrayInputStream(all));
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                byte[] b = readAll(z);
                if ("backup.json".equals(e.getName())) o = new JSONObject(new String(b, StandardCharsets.UTF_8));
                else if (e.getName().startsWith("files/")) files.put(e.getName().substring(6), b);
            }
            if (o == null || !FORMAT.equals(o.optString("format"))) throw new Exception("Not a Grove backup");
            jsonToPrefs(Prefs.sp(c), o.getJSONObject("prefs"));
            JSONObject others = o.optJSONObject("otherPrefs");
            if (others != null) for (String n : PREF_FILES) if (others.has(n)) jsonToPrefs(c.getSharedPreferences(n, Context.MODE_PRIVATE), others.getJSONObject(n));
            File dir = c.getFilesDir();
            for (Map.Entry<String, byte[]> f : files.entrySet()) {
                String name = f.getKey();
                if (name.contains("..")) continue;
                File dst = new File(dir, name);
                File parent = dst.getParentFile();
                if (parent != null) parent.mkdirs();
                FileOutputStream fo = new FileOutputStream(dst);
                fo.write(f.getValue());
                fo.close();
            }
            HomeStore.get(c).importJson(o.getJSONObject("home").toString());
            return true;
        }
        JSONObject o = new JSONObject(new String(all, StandardCharsets.UTF_8));
        if (!"canopy-backup-1".equals(o.optString("format"))) throw new Exception("Not a Grove backup");
        jsonToPrefs(Prefs.sp(c), o.getJSONObject("prefs"));
        HomeStore.get(c).importJson(o.getJSONObject("home").toString());
        return false;
    }

    private static JSONObject prefsToJson(SharedPreferences sp) throws Exception {
        JSONObject prefs = new JSONObject();
        for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
            Object v = e.getValue();
            if (v instanceof Set) {
                JSONArray a = new JSONArray();
                for (Object s : (Set<?>) v) a.put(String.valueOf(s));
                prefs.put(e.getKey(), new JSONObject().put("set", a));
            } else prefs.put(e.getKey(), v);
        }
        return prefs;
    }

    private static void jsonToPrefs(SharedPreferences sp, JSONObject prefs) throws Exception {
        SharedPreferences.Editor ed = sp.edit().clear();
        Iterator<String> keys = prefs.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            Object v = prefs.get(k);
            if (v instanceof Boolean) ed.putBoolean(k, (Boolean) v);
            else if (v instanceof Integer) ed.putInt(k, (Integer) v);
            else if (v instanceof Long) ed.putLong(k, (Long) v);
            else if (v instanceof Double) ed.putFloat(k, ((Double) v).floatValue());
            else if (v instanceof JSONObject && ((JSONObject) v).has("set")) {
                JSONArray a = ((JSONObject) v).getJSONArray("set");
                Set<String> s = new HashSet<>();
                for (int i = 0; i < a.length(); i++) s.add(a.getString(i));
                ed.putStringSet(k, s);
            } else ed.putString(k, String.valueOf(v));
        }
        ed.commit();
    }

    private static void put(ZipOutputStream z, String name, byte[] data) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(data);
        z.closeEntry();
    }

    private static byte[] read(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        try { return readAll(in); } finally { in.close(); }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toByteArray();
    }
}
