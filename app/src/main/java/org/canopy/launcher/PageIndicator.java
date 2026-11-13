/*
 * Copyright 2026 The Canopy Launcher authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.canopy.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** Dots under the home screen. The Glance page is shown as a small ring. */
public class PageIndicator extends View {
    private int count;
    private boolean glance;
    private int home = -1;
    private float position;
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float r, gap;

    public PageIndicator(Context c) {
        super(c);
        r = Ui.dp(c, 3);
        gap = Ui.dp(c, 12);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(Ui.dp(c, 1.5f));
    }

    public void setup(int count, boolean glance, int homeIndex) {
        this.count = count;
        this.glance = glance;
        this.home = homeIndex;
        invalidate();
    }

    public void setPosition(float p) {
        position = p;
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        if (count <= 1) return;
        float total = (count - 1) * gap;
        float sx = (getWidth() - total) / 2f, cy = getHeight() / 2f;
        for (int i = 0; i < count; i++) {
            float d = Math.min(1f, Math.abs(position - i));
            int a = (int) (255 - 150 * d);
            float x = sx + i * gap;
            if (i == 0 && glance) {
                ring.setColor((a << 24) | 0xFFFFFF);
                c.drawCircle(x, cy, r, ring);
            } else {
                dot.setColor((a << 24) | 0xFFFFFF);
                float rr = r * (1.35f - 0.35f * d);
                if (i == home) {
                    // main page: a small rounded "house" square
                    c.drawRoundRect(x - rr, cy - rr, x + rr, cy + rr, rr * 0.4f, rr * 0.4f, dot);
                } else {
                    c.drawCircle(x, cy, rr, dot);
                }
            }
        }
    }
}
