/*
 * Copyright 2026 The Canopy Launcher authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.canopy.launcher;

import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Nova-style "Edit" for any app: custom name, and a custom icon picked from an installed icon pack
 * or from an image on the phone. Applied everywhere (home, dock, folders, drawer).
 */
public final class IconEditor {
    private IconEditor() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("custom_icons", Context.MODE_PRIVATE);
    }

    private static File file(Context c, String key) {
        File d = new File(c.getFilesDir(), "custom_icons");
        d.mkdirs();
        return new File(d, Integer.toHexString(key.hashCode()) + "_" + key.length() + ".png");
    }

    public static String label(Context c, String key) { return sp(c).getString("l:" + key, null); }

    public static boolean hasIcon(Context c, String key) { return sp(c).contains("i:" + key); }

    public static Drawable icon(Context c, String key) {
        String v = sp(c).getString("i:" + key, null);
        if (v == null) return null;
        try {
            if ("file".equals(v)) {
                File f = file(c, key);
                if (!f.exists()) return null;
                Bitmap b = BitmapFactory.decodeFile(f.getPath());
                return b == null ? null : new BitmapDrawable(c.getResources(), b);
            }
            if (v.startsWith("pack:")) {
                String[] p = v.substring(5).split("\\|", 2);
                Resources res = c.getPackageManager().getResourcesForApplication(p[0]);
                int id = res.getIdentifier(p[1], "drawable", p[0]);
                if (id == 0) id = res.getIdentifier(p[1], "mipmap", p[0]);
                return id == 0 ? null : res.getDrawable(id, null);
            }
        } catch (Exception ignored) { }
        return null;
    }

    static void setLabel(Context c, String key, String label) {
        if (label == null || label.trim().isEmpty()) sp(c).edit().remove("l:" + key).apply();
        else sp(c).edit().putString("l:" + key, label.trim()).apply();
    }

    static void setPackIcon(Context c, String key, String pkg, String name) {
        sp(c).edit().putString("i:" + key, "pack:" + pkg + "|" + name).apply();
    }

    static void resetIcon(Context c, String key) {
        sp(c).edit().remove("i:" + key).apply();
        file(c, key).delete();
    }

    /** Centre-crops the chosen image to a square icon. */
    public static boolean saveImageIcon(Context c, String key, Uri uri) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            InputStream in = c.getContentResolver().openInputStream(uri);
            BitmapFactory.decodeStream(in, null, o);
            in.close();
            int s = 1;
            while (Math.min(o.outWidth, o.outHeight) / (s * 2) >= 256) s *= 2;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = s;
            in = c.getContentResolver().openInputStream(uri);
            Bitmap src = BitmapFactory.decodeStream(in, null, d);
            in.close();
            if (src == null) return false;
            int side = Math.min(src.getWidth(), src.getHeight());
            Rect from = new Rect((src.getWidth() - side) / 2, (src.getHeight() - side) / 2,
                    (src.getWidth() + side) / 2, (src.getHeight() + side) / 2);
            Bitmap out = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888);
            new Canvas(out).drawBitmap(src, from, new Rect(0, 0, 192, 192), new Paint(Paint.FILTER_BITMAP_FLAG));
            FileOutputStream fo = new FileOutputStream(file(c, key));
            out.compress(Bitmap.CompressFormat.PNG, 100, fo);
            fo.close();
            sp(c).edit().putString("i:" + key, "file").apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ UI

    public interface Named { void save(String name); }

    /** A picture of an icon as it looks on screen (folders, shortcuts), for the edit dialog. */
    static Drawable snapshot(View v) {
        if (v == null || v.getWidth() <= 0 || v.getHeight() <= 0) return null;
        Bitmap b = Bitmap.createBitmap(v.getWidth(), v.getHeight(), Bitmap.Config.ARGB_8888);
        v.draw(new android.graphics.Canvas(b));
        return new BitmapDrawable(v.getResources(), b);
    }

    /**
     * Same look as "Edit icon & name" for things whose name you can change (folders, shortcuts, drawer folders):
     * the icon on top, the name under it, Save / Reset / Cancel.
     */
    public static void nameDialog(final Context L, String title, Drawable preview, String current, final String defaultName, final Named done) {
        final Ui.Palette pal = Ui.palette(L);
        LinearLayout box = new LinearLayout(L);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        int p = Ui.dp(L, 20);
        box.setPadding(p, Ui.dp(L, 12), p, 0);
        if (preview != null) {
            ImageView iv = new ImageView(L);
            iv.setImageDrawable(preview);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setBackground(Ui.round(pal.surfaceAlt, Ui.dp(L, 20)));
            int ip = Ui.dp(L, 6);
            iv.setPadding(ip, ip, ip, ip);
            box.addView(iv, new LinearLayout.LayoutParams(Ui.dp(L, 84), Ui.dp(L, 84)));
        }
        TextView hint = Ui.text(L, "Type a new name", 12, pal.subtext);
        hint.setPadding(0, Ui.dp(L, 6), 0, Ui.dp(L, 10));
        box.addView(hint);
        final EditText name = new EditText(L);
        Ui.tintField(L, name);
        name.setSingleLine(true);
        name.setText(current);
        name.setSelectAllOnFocus(true);
        box.addView(name, new LinearLayout.LayoutParams(-1, -2));
        android.app.AlertDialog.Builder b = Ui.dialog(L).setTitle(title).setView(box)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { done.save(name.getText().toString().trim()); }
                })
                .setNegativeButton("Cancel", null);
        if (defaultName != null) b.setNeutralButton("Reset", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) { done.save(defaultName); }
        });
        b.show();
        name.requestFocus();
    }

    public static void show(final LauncherActivity L, final AppInfo app) {
        final Ui.Palette pal = Ui.palette(L);
        LinearLayout box = new LinearLayout(L);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        int p = Ui.dp(L, 20);
        box.setPadding(p, Ui.dp(L, 12), p, 0);
        ImageView iv = new ImageView(L);
        iv.setImageDrawable(app.icon);
        iv.setBackground(Ui.pressable(L, pal.surfaceAlt, Ui.dp(L, 20)));
        int ip = Ui.dp(L, 10);
        iv.setPadding(ip, ip, ip, ip);
        box.addView(iv, new LinearLayout.LayoutParams(Ui.dp(L, 84), Ui.dp(L, 84)));
        TextView hint = Ui.text(L, "Tap the icon to change it", 12, pal.subtext);
        hint.setPadding(0, Ui.dp(L, 6), 0, Ui.dp(L, 10));
        box.addView(hint);
        final EditText name = new EditText(L);
        Ui.tintField(L, name);
        name.setSingleLine(true);
        name.setText(app.label);
        name.setSelectAllOnFocus(true);
        box.addView(name, new LinearLayout.LayoutParams(-1, -2));

        final android.app.AlertDialog dlg = Ui.dialog(L).setTitle("Edit app").setView(box)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        setLabel(L, app.key, name.getText().toString());
                        L.onAppEdited(app);
                    }
                })
                .setNeutralButton("Reset", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        setLabel(L, app.key, null);
                        resetIcon(L, app.key);
                        L.onAppEdited(app);
                    }
                })
                .setNegativeButton("Cancel", null).create();
        iv.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                setLabel(L, app.key, name.getText().toString());
                dlg.dismiss();
                chooseSource(L, app);
            }
        });
        dlg.show();
    }

    private static void chooseSource(final LauncherActivity L, final AppInfo app) {
        Ui.dialog(L).setTitle("Icon for " + app.label)
                .setItems(new String[]{"From an icon pack", "From an image on this phone", "Default icon"}, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        if (w == 0) choosePack(L, app);
                        else if (w == 1) L.pickIconImage(app.key);
                        else { resetIcon(L, app.key); L.onAppEdited(app); }
                    }
                }).show();
    }

    private static void choosePack(final LauncherActivity L, final AppInfo app) {
        final List<IconPackManager.PackInfo> packs = IconPackManager.listPacks(L);
        if (packs.isEmpty()) {
            Ui.toast(L, "No icon packs installed. Any Nova/ADW-compatible icon pack works.");
            return;
        }
        if (packs.size() == 1) { pickFromPack(L, app, packs.get(0).pkg); return; }
        String[] names = new String[packs.size()];
        for (int i = 0; i < names.length; i++) names[i] = packs.get(i).label;
        Ui.dialog(L).setTitle("Choose icon pack").setItems(names, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) { pickFromPack(L, app, packs.get(w).pkg); }
        }).show();
    }

    private static void pickFromPack(final LauncherActivity L, final AppInfo app, final String pkg) {
        final Resources res;
        try { res = L.getPackageManager().getResourcesForApplication(pkg); } catch (Exception e) { Ui.toast(L, "Can't open icon pack"); return; }
        final List<String> all = IconPackManager.drawableNames(L, pkg, app.component);
        if (all.isEmpty()) { Ui.toast(L, "This icon pack has no icon list"); return; }
        final Ui.Palette pal = Ui.palette(L);
        final List<String> shown = new ArrayList<>(all);
        LinearLayout box = new LinearLayout(L);
        box.setOrientation(LinearLayout.VERTICAL);
        final EditText search = new EditText(L);
        Ui.tintField(L, search);
        search.setHint("Search " + all.size() + " icons");
        Ui.styleField(L, search, pal);
        // not focused when the dialog opens: no keyboard popping up and moving the grid, so the first icon is where it looks like it is
        search.setFocusableInTouchMode(false);
        search.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, android.view.MotionEvent e) {
                if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) v.setFocusableInTouchMode(true);
                return false;
            }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, Ui.dp(L, 44));
        slp.setMargins(Ui.dp(L, 16), Ui.dp(L, 8), Ui.dp(L, 16), Ui.dp(L, 8));
        box.addView(search, slp);
        final GridView grid = new GridView(L);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(Ui.dp(L, 64));
        // centred: the spare width is shared evenly at both edges and between the columns
        grid.setStretchMode(GridView.STRETCH_SPACING_UNIFORM);
        grid.setGravity(Gravity.CENTER);
        grid.setPadding(Ui.dp(L, 12), Ui.dp(L, 6), Ui.dp(L, 12), Ui.dp(L, 8));
        grid.setClipToPadding(false);
        grid.setVerticalSpacing(Ui.dp(L, 4));
        grid.setSelector(new ColorDrawable(0));
        final int cell = Ui.dp(L, 64);
        final View.OnClickListener[] pick = new View.OnClickListener[1];
        final BaseAdapter ad = new BaseAdapter() {
            @Override public int getCount() { return shown.size(); }
            @Override public Object getItem(int i) { return shown.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View convert, ViewGroup parent) {
                ImageView v = convert instanceof ImageView ? (ImageView) convert : new ImageView(L);
                int pp = Ui.dp(L, 8);
                v.setPadding(pp, pp, pp, pp);
                v.setLayoutParams(new AbsListView.LayoutParams(cell, cell));
                v.setScaleType(ImageView.ScaleType.FIT_CENTER);
                String name = shown.get(i);
                int id = res.getIdentifier(name, "drawable", pkg);
                if (id == 0) id = res.getIdentifier(name, "mipmap", pkg);
                try { v.setImageDrawable(id != 0 ? res.getDrawable(id, null) : null); } catch (Exception e) { v.setImageDrawable(null); }
                v.setTag(name); // the icon this cell really shows
                // each icon handles its own tap: the one under your finger when you lift it is the one you get
                v.setBackground(Ui.pressable(L, 0x00000000, Ui.dp(L, 14)));
                v.setOnClickListener(pick[0]);
                return v;
            }
        };
        grid.setAdapter(ad);
        box.addView(grid, new LinearLayout.LayoutParams(-1, Ui.dp(L, 420)));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                String q = s.toString().trim().toLowerCase(Locale.ROOT).replace(' ', '_');
                shown.clear();
                for (String n : all) if (q.isEmpty() || n.toLowerCase(Locale.ROOT).contains(q)) shown.add(n);
                ad.notifyDataSetChanged();
            }
        });
        final android.app.AlertDialog dlg = Ui.dialog(L).setTitle("Pick an icon").setView(box).setNegativeButton("Cancel", null).create();
        pick[0] = new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!(v.getTag() instanceof String)) return;
                setPackIcon(L, app.key, pkg, (String) v.getTag());
                dlg.dismiss();
                L.onAppEdited(app);
            }
        };
        // Searching opens the keyboard; the dialog used to move when it closed, so a tap landed on the icon
        // below the one you pressed. Keep the dialog still and close the keyboard as soon as you touch the icons.
        grid.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, android.view.MotionEvent e) {
                if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN && search.hasFocus()) {
                    search.clearFocus();
                    android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) L.getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
                }
                return false;
            }
        });
        dlg.show();
        if (dlg.getWindow() != null) dlg.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
    }
}
