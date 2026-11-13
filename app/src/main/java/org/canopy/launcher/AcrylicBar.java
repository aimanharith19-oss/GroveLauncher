/*
 * Copyright 2026 The Canopy Launcher authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.canopy.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;

/**
 * Fluent-style "acrylic" bar: it floats over a scrolling list, and whatever scrolls underneath shows
 * through blurred and tinted. A soft shadow fades in under it once the list has scrolled.
 * Real blur on Android 12+ (RenderNode + RenderEffect); a frosted tint on older versions.
 */
public class AcrylicBar extends LinearLayout {
    private View source;
    private Object node; // android.graphics.RenderNode on API 29+
    private final Paint tint = new Paint(), shadow = new Paint();
    private final int tintColor, shadowH;
    private final float blurRadius;
    private float shadowAmt, cut;
    private boolean effectSet;
    private int shaderH = -1;
    private final int[] me = new int[2], src = new int[2];

    private final ViewTreeObserver.OnScrollChangedListener onScroll = new ViewTreeObserver.OnScrollChangedListener() {
        @Override public void onScrollChanged() { if (isShown()) invalidate(); } // only re-blur while visible
    };

    public AcrylicBar(Context c, int surfaceColor) {
        super(c);
        setOrientation(VERTICAL);
        setWillNotDraw(false);
        boolean blur = Build.VERSION.SDK_INT >= 31;
        tintColor = Ui.alpha(surfaceColor, blur ? 0xA6 : 0xEB);
        tint.setColor(tintColor);
        shadowH = Ui.dp(c, 14);
        blurRadius = Ui.dp(c, 28);
        if (blur) node = new android.graphics.RenderNode("acrylic");
    }

    /** The view that scrolls underneath (switch it when another list is shown). */
    public void setSource(View v) {
        source = v;
        invalidate();
    }

    /** 0 = list at the top (flat), 1 = scrolled (shadow fully on). */
    public void setShadow(float amt) {
        amt = Math.max(0f, Math.min(1f, amt));
        if (Math.abs(amt - shadowAmt) < 0.01f) return;
        shadowAmt = amt;
        invalidate();
    }

    /** Visually trims this many pixels off the bottom (used while the search bar folds away, without relayout). */
    public void setCut(float px) {
        if (px == cut) return;
        cut = px;
        invalidate();
    }

    /** The bar's height without its shadow strip. */
    public int barHeight() { return getHeight() - shadowH; }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnScrollChangedListener(onScroll);
    }

    @Override protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnScrollChangedListener(onScroll);
        super.onDetachedFromWindow();
    }

    @Override protected void onMeasure(int w, int h) {
        super.onMeasure(w, h);
        // reserve a strip below the content for the shadow
        setMeasuredDimension(getMeasuredWidth(), getMeasuredHeight() + shadowH);
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b - shadowH);
    }

    @Override public void draw(Canvas c) {
        int w = getWidth(), h = Math.max(0, barHeight() - Math.round(cut));
        if (w > 0 && h > 0) {
            boolean drew = false;
            if (Build.VERSION.SDK_INT >= 31 && node != null && source != null && source.getVisibility() == VISIBLE
                    && c.isHardwareAccelerated()) {
                drew = drawBlur(c, w, h);
            }
            if (!drew) tint.setColor(Ui.alpha(tintColor, 0xEB));
            c.drawRect(0, 0, w, h, tint);
            if (!drew) tint.setColor(tintColor);
            if (shadowAmt > 0.01f) {
                if (shaderH != h) {
                    shadow.setShader(new LinearGradient(0, h, 0, h + shadowH, 0x38000000, 0x00000000, Shader.TileMode.CLAMP));
                    shaderH = h;
                }
                shadow.setAlpha((int) (255 * shadowAmt));
                c.drawRect(0, h, w, h + shadowH, shadow);
            }
        }
        super.draw(c);
    }

    @android.annotation.TargetApi(31)
    private boolean drawBlur(Canvas c, int w, int h) {
        try {
            android.graphics.RenderNode n = (android.graphics.RenderNode) node;
            getLocationInWindow(me);
            source.getLocationInWindow(src);
            n.setPosition(0, 0, w, h);
            android.graphics.RecordingCanvas rc = n.beginRecording(w, h);
            rc.clipRect(0, 0, w, h); // only the strip under the bar: skip every app icon outside it
            rc.translate(src[0] - me[0], src[1] - me[1]);
            source.draw(rc);
            n.endRecording();
            if (!effectSet) {
                n.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(blurRadius, blurRadius, Shader.TileMode.CLAMP));
                effectSet = true;
            }
            c.save();
            c.clipRect(0, 0, w, h);
            c.drawRenderNode(n);
            c.restore();
            return true;
        } catch (Throwable t) {
            node = null; // give up on blur for this session, keep the frosted tint
            return false;
        }
    }
}
