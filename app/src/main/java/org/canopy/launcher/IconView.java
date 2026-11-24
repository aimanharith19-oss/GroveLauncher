/*
 * Copyright 2026 The Canopy Launcher authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.canopy.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;

import java.util.ArrayList;
import java.util.List;

/**
 * A single icon with a label. Tap to open; long-press for the menu; long-press then move to drag.
 * Drawn directly onto the canvas (no nested views) so long drawers scroll smoothly.
 */
public class IconView extends View {
    public interface Host {
        void onIconClick(IconView v);
        void onIconLongPress(IconView v);
        void onIconDragStart(IconView v);
    }

    public static final int STYLE_HOME = 0, STYLE_DOCK = 1, STYLE_DRAWER = 2, STYLE_FOLDER = 3, STYLE_GLANCE = 4;

    private final Host host;
    public final int style;
    private Drawable icon;
    private String label;
    private CharSequence shown;
    private int shownWidth = -1;
    private boolean folder;
    private final List<Drawable> previews = new ArrayList<>();
    private android.graphics.Shader folderShader;
    private int folderShaderSize = -1, folderShaderColor;
    private final Paint folderRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint folderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int iconSize;
    private final android.graphics.Matrix folderMatrix = new android.graphics.Matrix();
    private int folderChosen;
    private String folderPreview = "grid";
    /** Free-placed icons grow and shrink with their frame instead of stopping at the normal icon size. */
    public boolean freeSized;
    private final boolean showLabel;
    private final int slop;

    private final String folderShape, pressStyle;
    private int checkState = -1; // -1 not selecting, 0 unselected, 1 selected
    private final Paint checkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final boolean haptics, fluid;

    /** For items shown inside an open folder: the folder they belong to. */
    public HomeItem folderParent;

    private final List<String> badgePkgs = new ArrayList<>();
    private final String badgeMode;
    private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean newDot;

    /** A small dot before the name: a newly installed app you haven't opened yet. */
    public void setNew(boolean on) { if (newDot != on) { newDot = on; invalidate(); } }
    private final TextPaint badgeText = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    private float downX, downY;
    private boolean longFired, dragStarted, tracking;
    private final Runnable longPress = new Runnable() {
        @Override public void run() {
            longFired = true;
            if (haptics) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            ViewParent p = getParent();
            if (p != null) p.requestDisallowInterceptTouchEvent(true);
            animateScale(1f);
            host.onIconLongPress(IconView.this);
        }
    };

    public IconView(Context c, Host host, int style) {
        super(c);
        this.host = host;
        this.style = style;
        Ui.Palette pal = Ui.palette(c);
        float scale = (style == STYLE_DRAWER ? Prefs.drawerIconScale(c) : Prefs.iconScale(c)) / 100f;
        int base = style == STYLE_GLANCE ? 44 : style == STYLE_FOLDER ? 50 : 52;
        iconSize = Math.round(Ui.dp(c, base) * scale * (style == STYLE_DRAWER ? 1f : Ui.compact(c)));
        showLabel = (style == STYLE_DRAWER && Prefs.drawerLabels(c)) || style == STYLE_FOLDER || style == STYLE_GLANCE
                || (style == STYLE_HOME && Prefs.showLabels(c))
                || (style == STYLE_DOCK && Prefs.sp(c).getBoolean("dock_labels", false));
        folderShape = Prefs.sp(c).getString("folder_shape", "circle");
        haptics = Anim.haptics(c);
        pressStyle = "none"; // icon press effects were removed
        checkPaint.setStrokeWidth(Ui.dp(c, 2));
        checkPaint.setStrokeCap(Paint.Cap.ROUND);
        checkPaint.setStrokeJoin(Paint.Join.ROUND);
        fluid = Anim.fluid(c);
        folderChosen = Prefs.folderIconColor(c);
        folderPreview = Prefs.sp(c).getString("folder_preview", "grid");
        tp.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, style == STYLE_GLANCE ? 11 : Prefs.labelSize(c),
                c.getResources().getDisplayMetrics()));
        badgeMode = Prefs.badges(c);
        badgePaint.setColor(pal.accent);
        badgeText.setColor(pal.onAccent);
        badgeText.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10, c.getResources().getDisplayMetrics()));
        badgeText.setFakeBoldText(true);
        if (style == STYLE_HOME || style == STYLE_DOCK) {
            String lc = Prefs.sp(c).getString("label_color", "auto");
            boolean dark = "dark".equals(lc) || ("auto".equals(lc) && Ui.wallpaperIsLight(c));
            tp.setColor(dark ? 0xFF111111 : 0xFFFFFFFF);
            if (Prefs.sp(c).getBoolean("label_shadow", true)) {
                tp.setShadowLayer(Ui.dp(c, 3), 0, Ui.dp(c, 1), dark ? 0x66FFFFFF : 0xAA000000);
            }
            folderPaint.setColor(dark ? 0x55000000 : 0x66FFFFFF);
        } else if (style == STYLE_FOLDER && Prefs.folderBgColor(c) != 0) {
            tp.setColor(Ui.textOn(Prefs.folderBgColor(c)));
            folderPaint.setColor(pal.surfaceAlt);
        } else {
            tp.setColor(pal.text);
            folderPaint.setColor(pal.surfaceAlt);
        }
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        setHapticFeedbackEnabled(true);
        setFocusable(true);
    }

    public void bind(Drawable icon, String label) {
        this.icon = icon;
        this.label = label;
        this.folder = false;
        previews.clear();
        shown = null;
        setContentDescription(label);
        invalidate();
    }

    public void bindFolder(String title, List<Drawable> icons) {
        this.icon = null;
        this.label = title;
        this.folder = true;
        previews.clear();
        previews.addAll(icons);
        shown = null;
        setContentDescription("Folder: " + title);
        invalidate();
    }

    public int iconSize() { return iconSize; }

    /** Multi-select mark: -1 hidden, 0 empty circle, 1 checked. */
    public void setChecked(int state) {
        if (checkState == state) return;
        checkState = state;
        invalidate();
    }

    public void setBadgePackage(String pkg) {
        badgePkgs.clear();
        if (pkg != null) badgePkgs.add(pkg);
        invalidate();
    }

    public void setBadgePackages(List<String> pkgs) {
        badgePkgs.clear();
        badgePkgs.addAll(pkgs);
        invalidate();
    }

    private int badgeCount() {
        int n = 0;
        for (String p : badgePkgs) n += Badges.count(p);
        return n;
    }

    private float labelHeight() { return showLabel ? tp.descent() - tp.ascent() : 0; }

    public int desiredHeight() {
        return iconSize + Ui.dp(getContext(), 14) + (showLabel ? Math.round(labelHeight()) + Ui.dp(getContext(), 4) : 0);
    }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getMode(wSpec) == MeasureSpec.UNSPECIFIED
                ? iconSize + Ui.dp(getContext(), 16) : MeasureSpec.getSize(wSpec);
        int h = MeasureSpec.getMode(hSpec) == MeasureSpec.EXACTLY ? MeasureSpec.getSize(hSpec) : desiredHeight();
        setMeasuredDimension(w, h);
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        float th = labelHeight();
        float gap = showLabel ? Ui.dp(getContext(), 4) : 0;
        int is;
        float top, left;
        is = Math.min(freeSized ? Integer.MAX_VALUE : iconSize, Math.min(w - Ui.dp(getContext(), 4), (int) (h - th - gap - Ui.dp(getContext(), 4))));
        is = Math.max(is, Ui.dp(getContext(), 18));
        top = (h - (is + gap + th)) / 2f;
        left = (w - is) / 2f;
        if (folder) {
            float r = is / 2f;
            // a glossy, accent-tinted disc with a soft shadow and a light rim, so folders stand out from apps
            int accent = Ui.palette(getContext()).accent;
            int chosen = folderChosen; // Settings > Folders
            if (chosen != 0) accent = chosen;
            if (folderShaderSize != is || folderShaderColor != accent) {
                folderShaderColor = accent;
                folderShaderSize = is;
                folderShader = new android.graphics.LinearGradient(0, 0, 0, is,
                        new int[]{Ui.alpha(0xFFFFFFFF, 0x8C), Ui.alpha(accent, 0x99)}, null, android.graphics.Shader.TileMode.CLAMP);
            }
            folderMatrix.setTranslate(left, top);
            folderShader.setLocalMatrix(folderMatrix);
            folderPaint.setShader(folderShader);
            folderPaint.setShadowLayer(is * 0.07f, 0, is * 0.035f, 0x55000000);
            folderRim.setStyle(Paint.Style.STROKE);
            folderRim.setStrokeWidth(Math.max(1f, is * 0.025f));
            folderRim.setColor(0x80FFFFFF);
            if ("rounded".equals(folderShape)) {
                c.drawRoundRect(left, top, left + is, top + is, is * 0.28f, is * 0.28f, folderPaint);
                c.drawRoundRect(left, top, left + is, top + is, is * 0.28f, is * 0.28f, folderRim);
            } else if ("square".equals(folderShape)) {
                c.drawRect(left, top, left + is, top + is, folderPaint);
                c.drawRect(left, top, left + is, top + is, folderRim);
            } else if (!"none".equals(folderShape)) {
                c.drawCircle(left + r, top + r, r, folderPaint);
                c.drawCircle(left + r, top + r, r - folderRim.getStrokeWidth() / 2f, folderRim);
            }
            folderPaint.setShader(null);
            folderPaint.clearShadowLayer();
            String pv = folderPreview;
            if ("grid".equals(pv)) {
                int n = Math.min(4, previews.size());
                float cell = is * 0.37f, pad = is * 0.11f, inner = is * 0.05f;
                for (int i = 0; i < n; i++) {
                    Drawable d = previews.get(i);
                    if (d == null) continue;
                    float px = left + pad + (i % 2) * (cell + inner) + (is - 2 * pad - 2 * cell - inner) / 2f;
                    float py = top + pad + (i / 2) * (cell + inner) + (is - 2 * pad - 2 * cell - inner) / 2f;
                    d.setBounds((int) px, (int) py, (int) (px + cell), (int) (py + cell));
                    d.draw(c);
                }
            } else if ("line".equals(pv)) { // up to three icons side by side
                int n = Math.min(3, previews.size());
                float cell = is * 0.30f, gapx = is * 0.03f, total = n * cell + (n - 1) * gapx;
                float px = left + (is - total) / 2f, py = top + (is - cell) / 2f;
                for (int i = 0; i < n; i++) {
                    Drawable d = previews.get(i);
                    if (d != null) { d.setBounds((int) px, (int) py, (int) (px + cell), (int) (py + cell)); d.draw(c); }
                    px += cell + gapx;
                }
            } else { // "fan" (tilted like a hand of cards) and "stack" (piled with a small offset); first app on top
                int n = Math.min(3, previews.size());
                float cell = is * 0.52f, cx = left + is / 2f, cy = top + is / 2f;
                for (int i = n - 1; i >= 0; i--) {
                    Drawable d = previews.get(i);
                    if (d == null) continue;
                    boolean fan = "fan".equals(pv);
                    float ang = fan ? (i - (n - 1) / 2f) * 18f : 0f;
                    float ox = fan ? (i - (n - 1) / 2f) * is * 0.10f : i * is * 0.07f - (n - 1) * is * 0.035f;
                    float oy = fan ? Math.abs(i - (n - 1) / 2f) * is * 0.03f : -i * is * 0.07f + (n - 1) * is * 0.035f;
                    c.save();
                    c.rotate(ang, cx + ox, cy + oy);
                    d.setBounds((int) (cx + ox - cell / 2), (int) (cy + oy - cell / 2), (int) (cx + ox + cell / 2), (int) (cy + oy + cell / 2));
                    d.draw(c);
                    c.restore();
                }
            }
        } else if (icon != null) {
            icon.setBounds((int) left, (int) top, (int) (left + is), (int) (top + is));
            icon.draw(c);
        }
        if (checkState >= 0) {
            Ui.Palette pal = Ui.palette(getContext());
            float r = is * 0.17f, cx = left + r * 0.6f, cy = top + r * 0.6f;
            if (checkState == 1) {
                checkPaint.setStyle(Paint.Style.FILL);
                checkPaint.setColor(pal.accent);
                c.drawCircle(cx, cy, r, checkPaint);
                checkPaint.setStyle(Paint.Style.STROKE);
                checkPaint.setColor(pal.onAccent);
                android.graphics.Path p = new android.graphics.Path();
                p.moveTo(cx - r * 0.45f, cy);
                p.lineTo(cx - r * 0.1f, cy + r * 0.35f);
                p.lineTo(cx + r * 0.5f, cy - r * 0.35f);
                c.drawPath(p, checkPaint);
            } else {
                checkPaint.setStyle(Paint.Style.FILL);
                checkPaint.setColor(0x66000000);
                c.drawCircle(cx, cy, r, checkPaint);
                checkPaint.setStyle(Paint.Style.STROKE);
                checkPaint.setColor(0xFFFFFFFF);
                c.drawCircle(cx, cy, r, checkPaint);
            }
        }
        int bc = "off".equals(badgeMode) ? 0 : badgeCount();
        if (bc > 0) {
            float cx = left + is * 0.88f, cy = top + is * 0.12f;
            if ("count".equals(badgeMode)) {
                String t = bc > 99 ? "99+" : String.valueOf(bc);
                float r = Math.max(is * 0.17f, (badgeText.measureText(t) + Ui.dp(getContext(), 8)) / 2f);
                c.drawRoundRect(cx - r, cy - is * 0.17f, cx + r, cy + is * 0.17f, is * 0.17f, is * 0.17f, badgePaint);
                c.drawText(t, cx - badgeText.measureText(t) / 2f, cy - (badgeText.ascent() + badgeText.descent()) / 2f, badgeText);
            } else {
                c.drawCircle(cx, cy, is * 0.11f, badgePaint);
            }
        }
        if (showLabel && label != null) {
            int avail = w - Ui.dp(getContext(), 6);
            if (shown == null || shownWidth != avail) {
                shown = TextUtils.ellipsize(label, tp, avail, TextUtils.TruncateAt.END);
                shownWidth = avail;
            }
            float tw = tp.measureText(shown, 0, shown.length());
            float ty = top + is + gap - tp.ascent();
            c.drawText(shown, 0, shown.length(), (w - tw) / 2f, ty, tp);
            if (newDot) {
                float r = Ui.dp(getContext(), 3);
                c.drawCircle(Math.max(r, (w - tw) / 2f - r * 2.2f), ty + (tp.ascent() + tp.descent()) / 2f, r, badgePaint);
            }
        }
    }

    /** Quick taps end before the press animation is visible: play press + release in full. */
    private void pressThenRelease() {
        if (Anim.minimal(getContext()) || "none".equals(pressStyle)) return;
        animate().cancel();
        float target = "bounce".equals(pressStyle) ? 1.12f : fluid ? 0.86f : 0.9f;
        if ("dim".equals(pressStyle)) {
            animate().alpha(0.5f).setDuration(70).withEndAction(new Runnable() { @Override public void run() { animateScale(1f); } }).start();
            return;
        }
        animate().scaleX(target).scaleY(target).setDuration(80).setInterpolator(new android.view.animation.DecelerateInterpolator())
                .withEndAction(new Runnable() { @Override public void run() { animateScale(1f); } }).start();
    }

    private void animateScale(float s) {
        if (Anim.minimal(getContext()) || "none".equals(pressStyle)) return;
        if ("dim".equals(pressStyle)) {
            animate().alpha(s < 1 ? 0.5f : 1f).setDuration(Anim.dur(getContext(), s < 1 ? 80 : 220)).start();
            return;
        }
        if ("bounce".equals(pressStyle)) {
            animate().scaleX(s < 1 ? 1.12f : 1f).scaleY(s < 1 ? 1.12f : 1f)
                    .setDuration(Anim.dur(getContext(), s < 1 ? 120 : 420))
                    .setInterpolator(s < 1 ? new android.view.animation.DecelerateInterpolator() : new Anim.Spring(6f, 12f)).start();
            return;
        }
        if (fluid) {
            // squishy press, springy release
            animate().scaleX(s < 1 ? 0.86f : 1f).scaleY(s < 1 ? 0.86f : 1f)
                    .setDuration(Anim.dur(getContext(), s < 1 ? 140 : 380))
                    .setInterpolator(s < 1 ? new android.view.animation.DecelerateInterpolator() : new Anim.Spring(7f, 11f)).start();
        } else {
            animate().scaleX(s).scaleY(s).setDuration(110).start();
        }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                longFired = false;
                dragStarted = false;
                tracking = true;
                animateScale(0.9f);
                postDelayed(longPress, Anim.longPress());
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (!tracking) return true;
                float d = (float) Math.hypot(e.getX() - downX, e.getY() - downY);
                if (longFired) {
                    if (!dragStarted && d > slop) {
                        dragStarted = true;
                        host.onIconDragStart(this);
                    }
                } else if (d > slop) {
                    removeCallbacks(longPress);
                    animateScale(1f);
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                removeCallbacks(longPress);
                float d = (float) Math.hypot(e.getX() - downX, e.getY() - downY);
                boolean click = tracking && !longFired && d <= slop;
                if (click) pressThenRelease(); // a quick tap still shows the whole press effect
                else animateScale(1f);
                if (click) {
                    playSoundEffect(SoundEffectConstants.CLICK);
                    host.onIconClick(this);
                }
                tracking = false;
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPress);
                animateScale(1f);
                tracking = false;
                return true;
        }
        return true;
    }

    @Override public boolean performClick() {
        super.performClick();
        host.onIconClick(this);
        return true;
    }
}
