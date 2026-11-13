/*
 * Copyright 2026 The Canopy Launcher authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.canopy.launcher;

import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.LinearLayout;

/** An open folder, drawn in the launcher window so its icons can be dragged straight out. */
public class FolderView extends FrameLayout {
    public final HomeItem folder;
    /** Set when this is an app-drawer folder rather than a home-screen folder. */
    public final DrawerGroups.Group drawerGroup;
    private final java.util.List<Object> entries = new java.util.ArrayList<>();
    private final EditText title;
    private final LinearLayout card;

    public FolderView(final LauncherActivity launcher, final HomeItem folder) {
        this(launcher, folder, null);
    }

    public FolderView(final LauncherActivity launcher, final DrawerGroups.Group group) {
        this(launcher, null, group);
    }

    private FolderView(final LauncherActivity launcher, final HomeItem folder, final DrawerGroups.Group group) {
        super(launcher);
        this.folder = folder;
        this.drawerGroup = group;
        if (folder != null) entries.addAll(folder.children);
        else for (AppInfo a : AppRepository.get(launcher).all()) if (group.apps.contains(a.key)) entries.add(a);
        Ui.Palette pal = Ui.palette(launcher);
        setBackgroundColor(0x55000000);
        setClickable(true);
        setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { launcher.closeFolder(); }
        });

        card = new LinearLayout(launcher);
        card.setOrientation(LinearLayout.VERTICAL);
        bubble = new Bubble(Prefs.folderBg(launcher), Ui.dp(launcher, 26), Ui.dp(launcher, 12));
        card.setBackground(bubble);
        card.setElevation(Ui.dp(launcher, 10));
        int p = Ui.dp(launcher, 14);
        pad = p;
        card.setPadding(p, p, p, p);
        card.setClickable(true);

        title = new EditText(launcher);

        Ui.tintField(launcher, title);
        title.setText(folder != null ? (folder.title == null ? "Folder" : folder.title) : group.name);
        title.setTextColor(Prefs.folderBgColor(launcher) != 0 ? Ui.textOn(Prefs.folderBgColor(launcher)) : pal.text);
        title.setTextSize(18);
        title.setGravity(Gravity.CENTER);
        title.setBackground(null);
        title.setSingleLine(true);
        title.setSelectAllOnFocus(true);
        title.setImeOptions(EditorInfo.IME_ACTION_DONE);
        card.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final int n = Math.max(1, entries.size());
        final int fixed = Prefs.sp(launcher).getInt("folder_cols", 0); // 0 = automatic
        final int cols = fixed > 0 ? fixed : Math.max(1, Math.min(5, n <= 4 ? n : (n <= 9 ? 3 : n <= 16 ? 4 : 5)));
        GridView grid = new GridView(launcher);
        grid.setNumColumns(cols);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setSelector(new ColorDrawable(Color.TRANSPARENT));
        grid.setVerticalScrollBarEnabled(false);
        final IconView probe = new IconView(launcher, launcher, IconView.STYLE_FOLDER);
        final int cellH = probe.desiredHeight();
        grid.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return entries.size(); }
            @Override public Object getItem(int i) { return entries.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View convert, ViewGroup parent) {
                IconView v = convert instanceof IconView ? (IconView) convert : new IconView(launcher, launcher, IconView.STYLE_FOLDER);
                Object e = entries.get(i);
                if (e instanceof HomeItem) {
                    HomeItem child = (HomeItem) e;
                    v.bind(launcher.iconFor(child), launcher.labelFor(child));
                    v.setTag(child);
                    v.folderParent = folder;
                } else {
                    AppInfo a = (AppInfo) e;
                    v.bind(a.icon, a.label);
                    v.setBadgePackage(a.pkg());
                    v.setTag(a);
                    v.folderParent = DRAWER_MARK;
                }
                v.setLayoutParams(new AbsListView.LayoutParams(AbsListView.LayoutParams.MATCH_PARENT, cellH));
                return v;
            }
        });
        // no cap on how many apps: every row shows, scrolling only if the folder is taller than the screen
        int rows = (int) Math.ceil(n / (double) cols);
        int maxRows = Math.max(2, (int) ((launcher.getResources().getDisplayMetrics().heightPixels * 0.62f) / cellH));
        int gridH = Math.min(rows, maxRows) * cellH;
        card.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, gridH));

        int screenW = launcher.getResources().getDisplayMetrics().widthPixels;
        int cardW = Math.min(screenW - Ui.dp(launcher, 32), Ui.dp(launcher, 96) * cols + 2 * p);
        LayoutParams lp = new LayoutParams(Math.max(cardW, Ui.dp(launcher, 220)), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        addView(card, lp);
        card.setAlpha(0f);
        post(new Runnable() { @Override public void run() { placeBubble(); } });
    }

    private final Bubble bubble;
    private final int pad;
    private View anchor;

    /** The icon it opened from: the folder pops out of it like a chat bubble, its tail pointing at the icon. */
    public void setAnchor(View v) { anchor = v; }

    private void placeBubble() {
        int[] me = new int[2];
        getLocationOnScreen(me);
        int w = getWidth(), h = getHeight(), cw = card.getWidth(), ch = card.getHeight();
        int margin = Ui.dp(getContext(), 12), tail = bubble.tailH;
        if (anchor == null || !anchor.isAttachedToWindow() || cw == 0) {
            bubble.setTail(0, false, false);
            Anim.popIn(card);
            card.setAlpha(1f);
            return;
        }
        int[] a = new int[2];
        anchor.getLocationOnScreen(a);
        float ax = a[0] - me[0] + anchor.getWidth() / 2f, ayTop = a[1] - me[1], ayBottom = ayTop + anchor.getHeight();
        boolean below = h - ayBottom - margin >= ch + tail || ayTop < h / 2f;
        // leave room for the tail on the side that points at the icon
        card.setPadding(pad, pad + (below ? tail : 0), pad, pad + (below ? 0 : tail));
        card.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.AT_MOST));
        ch = card.getMeasuredHeight();
        float x = Math.max(margin, Math.min(w - cw - margin, ax - cw / 2f));
        float y = below ? Math.min(h - ch - margin, ayBottom - Ui.dp(getContext(), 4))
                : Math.max(margin, ayTop - ch + Ui.dp(getContext(), 4));
        LayoutParams lp = (LayoutParams) card.getLayoutParams();
        lp.gravity = Gravity.TOP | Gravity.START;
        card.setLayoutParams(lp);
        card.setTranslationX(x);
        card.setTranslationY(y);
        float tx = Math.max(bubble.radius + tail, Math.min(cw - bubble.radius - tail, ax - x));
        bubble.setTail(tx, below, true);
        // grow out of the tail
        card.setPivotX(tx);
        card.setPivotY(below ? 0 : ch);
        card.setScaleX(0.3f);
        card.setScaleY(0.3f);
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Anim.dur(getContext(), 260))
                .setInterpolator(new android.view.animation.OvershootInterpolator(1.1f)).start();
    }

    /** Rounded card with a small tail, like a conversation bubble. */
    static final class Bubble extends android.graphics.drawable.Drawable {
        final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        final float radius;
        final int tailH;
        private float tailX;
        private boolean tailTop, hasTail;
        private final android.graphics.Path path = new android.graphics.Path();

        Bubble(int color, float radius, int tailH) {
            p.setColor(color);
            this.radius = radius;
            this.tailH = tailH;
        }

        void setTail(float x, boolean top, boolean show) { tailX = x; tailTop = top; hasTail = show; invalidateSelf(); }

        @Override public void draw(android.graphics.Canvas c) {
            android.graphics.Rect b = getBounds();
            float l = b.left, r = b.right, t = b.top + (hasTail && tailTop ? tailH : 0), bt = b.bottom - (hasTail && !tailTop ? tailH : 0);
            path.reset();
            path.addRoundRect(l, t, r, bt, radius, radius, android.graphics.Path.Direction.CW);
            if (hasTail) {
                android.graphics.Path tp = new android.graphics.Path();
                float half = tailH * 1.1f, x = b.left + tailX;
                if (tailTop) {
                    tp.moveTo(x - half, t + 1);
                    tp.quadTo(x - half * 0.25f, t - tailH * 0.4f, x, b.top);
                    tp.quadTo(x + half * 0.25f, t - tailH * 0.4f, x + half, t + 1);
                } else {
                    tp.moveTo(x - half, bt - 1);
                    tp.quadTo(x - half * 0.25f, bt + tailH * 0.4f, x, b.bottom);
                    tp.quadTo(x + half * 0.25f, bt + tailH * 0.4f, x + half, bt - 1);
                }
                tp.close();
                path.op(tp, android.graphics.Path.Op.UNION);
            }
            c.drawPath(path, p);
        }

        @Override public void getOutline(android.graphics.Outline o) {
            android.graphics.Rect b = getBounds();
            int t = b.top + (hasTail && tailTop ? tailH : 0), bt = b.bottom - (hasTail && !tailTop ? tailH : 0);
            o.setRoundRect(b.left, t, b.right, bt, radius); // shadow follows the card
        }

        @Override public void setAlpha(int a) { p.setAlpha(a); }
        @Override public void setColorFilter(android.graphics.ColorFilter f) { p.setColorFilter(f); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    /** Marks icons that live in an app-drawer folder. */
    public static final HomeItem DRAWER_MARK = new HomeItem();

    public String currentTitle() {
        String t = title.getText().toString().trim();
        return t.isEmpty() ? "Folder" : t;
    }
}
