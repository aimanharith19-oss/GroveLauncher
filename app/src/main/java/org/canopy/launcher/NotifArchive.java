/*
 * Copyright 2026 The Canopy Launcher authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.canopy.launcher;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Notifications older than the week the feed keeps are moved here: appended to a small file on the phone and written out
 * as a spreadsheet, Documents/Grove/Notification history.xlsx, which you can open in any spreadsheet app.
 */
final class NotifArchive {
    private NotifArchive() { }

    static final String NAME = "Notification history.xlsx", DIR = "Documents/Grove";
    private static final Object LOCK = new Object();
    private static final int MAX_ROWS = 1_000_000;

    private static File file(Context c) { return new File(c.getFilesDir(), "notification_archive.tsv"); }

    /** Adds these (oldest first) to the archive and refreshes the spreadsheet. Runs on the I/O thread. */
    static void add(final Context c, final List<NotifHistory.Entry> old) {
        if (old.isEmpty()) return;
        App.IO.execute(new Runnable() {
            @Override public void run() {
                synchronized (LOCK) {
                    try {
                        Writer w = new OutputStreamWriter(new FileOutputStream(file(c), true), "UTF-8");
                        for (NotifHistory.Entry e : old) {
                            w.write(e.when + "\t" + e.pkg + "\t" + esc(e.title) + "\t" + esc(e.text) + "\n");
                        }
                        w.close();
                    } catch (Throwable t) { return; }
                    export(c);
                }
            }
        });
    }

    private static String esc(String s) { return s == null ? "" : s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", ""); }

    private static String unesc(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\' && i + 1 < s.length()) { char n = s.charAt(++i); b.append(n == 't' ? '\t' : n == 'n' ? '\n' : n); }
            else b.append(ch);
        }
        return b.toString();
    }

    /** XML-safe text: drops characters a spreadsheet can't hold. */
    private static String x(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '&') b.append("&amp;"); else if (ch == '<') b.append("&lt;"); else if (ch == '>') b.append("&gt;");
            else if (ch == '"') b.append("&quot;");
            else if (ch == '\n' || ch == '\t' || (ch >= 0x20 && ch != 0xFFFE && ch != 0xFFFF && !(ch >= 0xD800 && ch <= 0xDFFF))) b.append(ch);
            else if (Character.isHighSurrogate(ch) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) { b.append(ch).append(s.charAt(++i)); }
        }
        return b.toString();
    }

    private static void cell(StringBuilder b, String ref, String v) {
        b.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">").append(x(v)).append("</t></is></c>");
    }

    private static String label(PackageManager pm, Map<String, String> cache, String pkg) {
        String l = cache.get(pkg);
        if (l == null) {
            try { l = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(); } catch (Exception e) { l = pkg; }
            cache.put(pkg, l);
        }
        return l;
    }

    /** Writes the whole archive out as an .xlsx (a zip of small XML files) into Documents/Grove. */
    private static void export(Context c) {
        File f = file(c);
        if (!f.exists()) return;
        File tmp = new File(c.getCacheDir(), "notification_archive.xlsx");
        try {
            PackageManager pm = c.getPackageManager();
            Map<String, String> labels = new HashMap<>();
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
            ZipOutputStream z = new ZipOutputStream(new FileOutputStream(tmp));
            put(z, "[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                    + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/></Types>");
            put(z, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
            put(z, "xl/workbook.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                    + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"Notifications\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
            put(z, "xl/_rels/workbook.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>");
            z.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
            Writer w = new OutputStreamWriter(z, "UTF-8");
            w.write("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                    + "<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>"
                    + "<cols><col min=\"1\" max=\"1\" width=\"18\" customWidth=\"1\"/><col min=\"2\" max=\"2\" width=\"22\" customWidth=\"1\"/><col min=\"3\" max=\"3\" width=\"32\" customWidth=\"1\"/>"
                    + "<col min=\"4\" max=\"4\" width=\"70\" customWidth=\"1\"/><col min=\"5\" max=\"5\" width=\"30\" customWidth=\"1\"/></cols><sheetData>");
            StringBuilder b = new StringBuilder();
            b.append("<row r=\"1\">");
            cell(b, "A1", "Date & time"); cell(b, "B1", "App"); cell(b, "C1", "Title"); cell(b, "D1", "Text"); cell(b, "E1", "Package");
            b.append("</row>");
            w.write(b.toString());
            BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            int r = 1;
            Date d = new Date();
            while ((line = br.readLine()) != null && r < MAX_ROWS) {
                String[] p = line.split("\t", -1);
                if (p.length < 4) continue;
                long when;
                try { when = Long.parseLong(p[0]); } catch (Exception e) { continue; }
                r++;
                d.setTime(when);
                b.setLength(0);
                b.append("<row r=\"").append(r).append("\">");
                cell(b, "A" + r, fmt.format(d)); cell(b, "B" + r, label(pm, labels, p[1])); cell(b, "C" + r, unesc(p[2]));
                cell(b, "D" + r, unesc(p[3])); cell(b, "E" + r, p[1]);
                b.append("</row>");
                w.write(b.toString());
            }
            br.close();
            w.write("</sheetData></worksheet>");
            w.flush();
            z.closeEntry();
            z.close();
            save(c, tmp);
        } catch (Throwable ignored) {
        } finally { //noinspection ResultOfMethodCallIgnored
            tmp.delete(); }
    }

    private static void put(ZipOutputStream z, String name, String text) throws Exception {
        z.putNextEntry(new ZipEntry(name));
        z.write(text.getBytes("UTF-8"));
        z.closeEntry();
    }

    /** Puts the file in Documents/Grove (replacing the last version). Falls back to the app's own Documents folder if the shared one can't be used. */
    private static void save(Context c, File src) throws Exception {
        ContentResolver cr = c.getContentResolver();
        Uri tbl = MediaStore.Files.getContentUri("external");
        Uri target = null;
        Cursor q = cr.query(tbl, new String[]{MediaStore.MediaColumns._ID}, MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?",
                new String[]{NAME, DIR + "%"}, null);
        if (q != null) { try { if (q.moveToFirst()) target = android.content.ContentUris.withAppendedId(tbl, q.getLong(0)); } finally { q.close(); } }
        OutputStream o = null;
        try {
            if (target == null) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, NAME);
                v.put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, DIR);
                target = cr.insert(tbl, v);
            }
            if (target != null) o = cr.openOutputStream(target, "wt");
        } catch (Exception ignored) { o = null; }
        if (o == null) { // fallback: Android/data/org.canopy.launcher/files/Documents
            File dir = c.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
            if (dir == null) return;
            o = new FileOutputStream(new File(dir, NAME));
        }
        try {
            FileInputStream in = new FileInputStream(src);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            in.close();
        } finally { o.close(); }
    }
}
