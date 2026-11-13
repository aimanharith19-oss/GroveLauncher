/*
 * Copyright 2026 The Canopy Launcher authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.canopy.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/**
 * A vertical A–Z scrubber. While your finger is on it, the letters near the finger swell and bow
 * outwards in a wave and the selected letter pops out to the side.
 */
public class AlphabetWave extends View {
    public interface Listener { void onLetter(int section); }

    private String[] letters = new String[0];
    private Listener listener;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), big = new Paint(Paint.ANTI_ALIAS_FLAG), bubble = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float touchY = -1;
    private int current = -1;
    private final float wave, base;

    public AlphabetWave(Context c, int textColor, int accent) {
        super(c);
        base = Ui.dp(c, 11);
        wave = Ui.dp(c, 46);
        p.setColor(textColor);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(base);
        big.setColor(Ui.isLight(accent) ? 0xFF101010 : 0xFFFFFFFF);
        big.setTextAlign(Paint.Align.CENTER);
        big.setTextSize(Ui.dp(c, 30));
        big.setFakeBoldText(true);
        bubble.setColor(accent);
    }

    public void setLetters(String[] l) { letters = l; invalidate(); }
    public void setListener(Listener l) { listener = l; }

    public boolean isActive() { return touchY >= 0; }


    private float slot() { return letters.length == 0 ? 0 : (getHeight() - getPaddingTop() - getPaddingBottom()) / (float) letters.length; }

    @Override protected void onDraw(Canvas c) {
        if (letters.length == 0) return;
        float s = slot(), w = getWidth();
        for (int i = 0; i < letters.length; i++) {
            float y = getPaddingTop() + s * i + s / 2;
            float shift = 0, scale = 1f;
            if (touchY >= 0) {
                float d = (y - touchY) / (s * 3.2f);
                float g = (float) Math.exp(-d * d);
                shift = g * wave;
                scale = 1f + g * 0.9f;
            }
            p.setTextSize(base * scale);
            p.setFakeBoldText(i == current);
            p.setAlpha(touchY >= 0 ? (int) (140 + 115 * Math.min(1f, scale - 1f)) : 200);
            c.drawText(letters[i], w - Ui.dp(getContext(), 12) - shift, y + p.getTextSize() / 3f, p);
        }
        if (current >= 0 && current < letters.length && touchY >= 0) {
            float cx = w - wave - Ui.dp(getContext(), 46), r = Ui.dp(getContext(), 26);
            c.drawCircle(cx, touchY, r, bubble);
            c.drawText(letters[current], cx, touchY + big.getTextSize() / 3f, big);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (letters.length == 0) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (e.getX() < getWidth() - Ui.dp(getContext(), 32)) return false;
                getParent().requestDisallowInterceptTouchEvent(true);
                // fall through
            case MotionEvent.ACTION_MOVE: {
                touchY = e.getY();
                int idx = (int) ((touchY - getPaddingTop()) / Math.max(1f, slot()));
                idx = Math.max(0, Math.min(letters.length - 1, idx));
                if (idx != current) {
                    current = idx;
                    if (Anim.haptics(getContext())) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    if (listener != null) listener.onLetter(idx);
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                touchY = -1;
                current = -1;
                invalidate();
                return true;
        }
        return true;
    }
}
