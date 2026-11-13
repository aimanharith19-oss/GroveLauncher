/*
 * Copyright 2026 The Canopy Launcher authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.canopy.launcher;

import android.content.Context;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** "Remove / App info / Uninstall" targets shown at the top while dragging (as in Nova). */
public class DropTargetBar extends LinearLayout {
    public static final int REMOVE = 0, INFO = 1, UNINSTALL = 2;

    public interface Callback { void onDropTarget(int which, LauncherActivity.DragInfo info); }

    private final TextView[] targets = new TextView[3];

    public DropTargetBar(Context c, final Callback cb) {
        super(c);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER);
        String[] labels = {"Remove", "App info", "Uninstall"};
        int[] icons = {R.drawable.ic_close, R.drawable.ic_info, R.drawable.ic_delete};
        for (int i = 0; i < 3; i++) {
            final int which = i;
            final TextView t = Ui.text(c, labels[i], 14, Ui.onWallpaper(c));
            t.setShadowLayer(Ui.dp(c, 3), 0, 1, 0xAA000000);
            t.setGravity(Gravity.CENTER);
            t.setCompoundDrawablesRelativeWithIntrinsicBounds(Ui.tinted(c, icons[i], Ui.onWallpaper(c)), null, null, null);
            t.setCompoundDrawablePadding(Ui.dp(c, 6));
            int p = Ui.dp(c, 14);
            t.setPadding(p, p, p, p);
            t.setOnDragListener(new OnDragListener() {
                @Override public boolean onDrag(View v, DragEvent e) {
                    if (!(e.getLocalState() instanceof LauncherActivity.DragInfo)) return false;
                    switch (e.getAction()) {
                        case DragEvent.ACTION_DRAG_STARTED: return true;
                        case DragEvent.ACTION_DRAG_ENTERED:
                            t.setBackground(Ui.round(which == REMOVE ? 0x66FFFFFF : 0x99E53935, Ui.dp(getContext(), 20)));
                            if (which == INFO) t.setBackground(Ui.round(0x661E88E5, Ui.dp(getContext(), 20)));
                            return true;
                        case DragEvent.ACTION_DRAG_EXITED:
                        case DragEvent.ACTION_DRAG_ENDED:
                            t.setBackground(null);
                            return true;
                        case DragEvent.ACTION_DROP:
                            t.setBackground(null);
                            cb.onDropTarget(which, (LauncherActivity.DragInfo) e.getLocalState());
                            return true;
                    }
                    return true;
                }
            });
            targets[i] = t;
            addView(t, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        }
        setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x99000000, 0x00000000}));
    }

    public void show(boolean canRemove, boolean isApp) {
        targets[REMOVE].setVisibility(canRemove ? VISIBLE : GONE);
        targets[INFO].setVisibility(isApp ? VISIBLE : GONE);
        targets[UNINSTALL].setVisibility(isApp ? VISIBLE : GONE);
        setVisibility(VISIBLE);
    }
}
