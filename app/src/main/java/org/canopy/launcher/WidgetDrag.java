/*
 * Copyright 2026 The Canopy Launcher authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.canopy.launcher;

import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * Makes a widget preview card draggable: touch, hold a moment (it lifts slightly) and drag it out.
 * A quick tap adds it; a quick swipe scrolls the page or the row of previews as usual.
 */
final class WidgetDrag {
    interface Starter { void startDrag(View card); }

    private WidgetDrag() { }

    static void attach(final View card, final Runnable tap, final Starter starter) {
        final int slop = ViewConfiguration.get(card.getContext()).getScaledTouchSlop();
        // Hold briefly, then drag. A quick swipe still scrolls the list / strip like normal.
        final int hold = 180;
        card.setOnTouchListener(new View.OnTouchListener() {
            float dx0, dy0;
            boolean armed, started, cancelled;
            final Runnable arm = new Runnable() {
                @Override public void run() {
                    if (started || cancelled) return;
                    armed = true;
                    if (card.getParent() != null) card.getParent().requestDisallowInterceptTouchEvent(true);
                    card.animate().scaleX(1.06f).scaleY(1.06f).setDuration(120).start();
                    if (Anim.haptics(card.getContext())) card.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                }
            };

            private void reset(View v) {
                v.removeCallbacks(arm);
                v.setPressed(false);
                v.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
            }

            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        dx0 = e.getRawX();
                        dy0 = e.getRawY();
                        armed = started = cancelled = false;
                        v.setPressed(true);
                        v.postDelayed(arm, hold);
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        if (started || cancelled) return true;
                        float d = (float) Math.hypot(e.getRawX() - dx0, e.getRawY() - dy0);
                        if (d <= slop) return true;
                        if (armed) {
                            started = true;
                            reset(v);
                            starter.startDrag(v);
                        } else {
                            // moved before the hold: it's a scroll, hand it back to the list
                            cancelled = true;
                            reset(v);
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        reset(v);
                        if (!started && !cancelled) tap.run();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        reset(v);
                        cancelled = true;
                        return true;
                }
                return true;
            }
        });
    }
}
