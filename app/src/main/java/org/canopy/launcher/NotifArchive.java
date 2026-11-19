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
 * Notifications older than the 3 days the feed keeps are moved here: appended to a small file on the phone and written out
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

    /** Makes sure the spreadsheet exists in Documents/Grove (empty at first), so you can find it. */
    static void ensure(final Context c) {
        App.IO.execute(new Runnable() {
            @Override public void run() {
                synchronized (LOCK) {
                    if (!exists(c)) export(c);
                }
            }
        });
    }

    private static boolean exists(Context c) {
        try {
            Cursor q = c.getContentResolver().query(MediaStore.Files.getContentUri("external"), new String[]{MediaStore.MediaColumns._ID},
                    MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?", new String[]{NAME, DIR + "%"}, null);
            if (q == null) return false;
            try { return q.moveToFirst(); } finally { q.close(); }
        } catch (Throwable t) { return false; }
    }

    /** Writes the spreadsheet now with everything: the archive plus the notifications still in the feed. */
    static void exportNow(final Context c, final List<NotifHistory.Entry> current, final Runnable done) {
        App.IO.execute(new Runnable() {
            @Override public void run() {
                synchronized (LOCK) {
                    File all = new File(c.getCacheDir(), "notification_all.tsv");
                    try {
                        FileOutputStream o = new FileOutputStream(all);
                        File a = file(c);
                        if (a.exists()) { FileInputStream in = new FileInputStream(a); byte[] buf = new byte[16384]; int n; while ((n = in.read(buf)) > 0) o.write(buf, 0, n); in.close(); }
                        Writer w = new OutputStreamWriter(o, "UTF-8");
                        for (NotifHistory.Entry e : current) w.write(e.when + "\t" + e.pkg + "\t" + esc(e.title) + "\t" + esc(e.text) + "\n");
                        w.close();
                        export(c, all);
                    } catch (Throwable ignored) { }
                    all.delete();
                }
                if (done != null) App.MAIN.post(done);
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

    private static final int ICON_ROWS = 30000; // rows that get an app icon (keeps the file a sensible size)

    private static byte[] icon(PackageManager pm, String pkg) {
        try {
            android.graphics.drawable.Drawable d = pm.getApplicationIcon(pkg);
            android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, 64, 64);
            d.draw(new android.graphics.Canvas(bm));
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            bm.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, o);
            bm.recycle();
            return o.toByteArray();
        } catch (Throwable t) { return null; }
    }

    /** Writes the whole archive out as an .xlsx (a zip of small XML files) into Documents/Grove, each row with its app's icon. */
    private static void export(Context c) { export(c, file(c)); }

    private static void export(Context c, File f) {
        if (!f.exists()) { try { f.createNewFile(); } catch (Exception e) { return; } } // nothing archived yet: still write the (empty) spreadsheet so it can be found
        File tmp = new File(c.getCacheDir(), "notification_archive.xlsx");
        try {
            PackageManager pm = c.getPackageManager();
            Map<String, String> labels = new HashMap<>();
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
            // pass 1: which apps are in the archive, and their icons
            Map<String, Integer> iconIdx = new java.util.LinkedHashMap<>();
            List<byte[]> pngs = new ArrayList<>();
            BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            int seen = 0;
            while ((line = br.readLine()) != null && seen < ICON_ROWS) {
                String[] p = line.split("\t", -1);
                if (p.length < 4) continue;
                seen++;
                if (!iconIdx.containsKey(p[1])) {
                    byte[] png = icon(pm, p[1]);
                    if (png == null) { iconIdx.put(p[1], -1); continue; }
                    iconIdx.put(p[1], pngs.size());
                    pngs.add(png);
                }
            }
            br.close();
            boolean pics = !pngs.isEmpty();
            ZipOutputStream z = new ZipOutputStream(new FileOutputStream(tmp));
            put(z, "[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Default Extension=\"png\" ContentType=\"image/png\"/>"
                    + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                    + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                    + (pics ? "<Override PartName=\"/xl/drawings/drawing1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.drawing+xml\"/>" : "") + "</Types>");
            put(z, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
            put(z, "xl/workbook.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                    + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"Notifications\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
            put(z, "xl/_rels/workbook.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>");
            for (int k = 0; k < pngs.size(); k++) {
                z.putNextEntry(new ZipEntry("xl/media/image" + (k + 1) + ".png"));
                z.write(pngs.get(k));
                z.closeEntry();
            }
            if (pics) put(z, "xl/worksheets/_rels/sheet1.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/drawing\" Target=\"../drawings/drawing1.xml\"/></Relationships>");
            // sheet
            z.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
            Writer w = new OutputStreamWriter(z, "UTF-8");
            w.write("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                    + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                    + "<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>"
                    + "<cols><col min=\"1\" max=\"1\" width=\"7\" customWidth=\"1\"/><col min=\"2\" max=\"2\" width=\"18\" customWidth=\"1\"/><col min=\"3\" max=\"3\" width=\"22\" customWidth=\"1\"/>"
                    + "<col min=\"4\" max=\"4\" width=\"32\" customWidth=\"1\"/><col min=\"5\" max=\"5\" width=\"70\" customWidth=\"1\"/><col min=\"6\" max=\"6\" width=\"30\" customWidth=\"1\"/></cols><sheetData>");
            StringBuilder b = new StringBuilder();
            b.append("<row r=\"1\">");
            cell(b, "A1", "Icon"); cell(b, "B1", "Date & time"); cell(b, "C1", "App"); cell(b, "D1", "Title"); cell(b, "E1", "Text"); cell(b, "F1", "Package");
            b.append("</row>");
            w.write(b.toString());
            StringBuilder dr = new StringBuilder();
            br = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            int r = 1;
            Date d = new Date();
            List<Integer> rowIcon = new ArrayList<>(); // row number -> icon index, for the drawing
            while ((line = br.readLine()) != null && r < MAX_ROWS) {
                String[] p = line.split("\t", -1);
                if (p.length < 4) continue;
                long when;
                try { when = Long.parseLong(p[0]); } catch (Exception e) { continue; }
                r++;
                d.setTime(when);
                Integer ix = iconIdx.get(p[1]);
                boolean has = pics && ix != null && ix >= 0 && r - 1 <= ICON_ROWS;
                b.setLength(0);
                b.append("<row r=\"").append(r).append("\"").append(has ? " ht=\"30\" customHeight=\"1\"" : "").append(">");
                cell(b, "B" + r, fmt.format(d)); cell(b, "C" + r, label(pm, labels, p[1])); cell(b, "D" + r, unesc(p[2]));
                cell(b, "E" + r, unesc(p[3])); cell(b, "F" + r, p[1]);
                b.append("</row>");
                w.write(b.toString());
                if (has) { rowIcon.add(r); rowIcon.add(ix); }
            }
            br.close();
            w.write("</sheetData>" + (pics ? "<drawing r:id=\"rId1\"/>" : "") + "</worksheet>");
            w.flush();
            z.closeEntry();
            if (pics) {
                // one picture per row, all pointing at the app's single image file
                StringBuilder rels = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
                for (int k = 0; k < pngs.size(); k++)
                    rels.append("<Relationship Id=\"rId").append(k + 1).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"../media/image").append(k + 1).append(".png\"/>");
                rels.append("</Relationships>");
                put(z, "xl/drawings/_rels/drawing1.xml.rels", rels.toString());
                z.putNextEntry(new ZipEntry("xl/drawings/drawing1.xml"));
                Writer dw = new OutputStreamWriter(z, "UTF-8");
                dw.write("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><xdr:wsDr xmlns:xdr=\"http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing\" "
                        + "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">");
                int id = 1;
                for (int k = 0; k < rowIcon.size(); k += 2) {
                    dr.setLength(0);
                    dr.append("<xdr:oneCellAnchor><xdr:from><xdr:col>0</xdr:col><xdr:colOff>76200</xdr:colOff><xdr:row>").append(rowIcon.get(k) - 1)
                            .append("</xdr:row><xdr:rowOff>63500</xdr:rowOff></xdr:from><xdr:ext cx=\"333375\" cy=\"333375\"/><xdr:pic><xdr:nvPicPr><xdr:cNvPr id=\"").append(++id)
                            .append("\" name=\"i").append(id).append("\"/><xdr:cNvPicPr/></xdr:nvPicPr><xdr:blipFill><a:blip r:embed=\"rId").append(rowIcon.get(k + 1) + 1)
                            .append("\"/><a:stretch><a:fillRect/></a:stretch></xdr:blipFill><xdr:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"333375\" cy=\"333375\"/></a:xfrm>")
                            .append("<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></xdr:spPr></xdr:pic><xdr:clientData/></xdr:oneCellAnchor>");
                    dw.write(dr.toString());
                }
                dw.write("</xdr:wsDr>");
                dw.flush();
                z.closeEntry();
            }
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
