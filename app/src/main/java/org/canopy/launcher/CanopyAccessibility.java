/*
 * Copyright 2026 The Canopy Launcher authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.canopy.launcher;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

/**
 * Optional helper so gestures can lock the screen, open recents, quick settings or the power menu
 * (like Nova's "Screen lock" gesture). It listens to no events and reads no screen content.
 */
public class CanopyAccessibility extends AccessibilityService {
    private static CanopyAccessibility sInstance;

    public static boolean perform(int action) {
        CanopyAccessibility s = sInstance;
        return s != null && s.performGlobalAction(action);
    }

    public static boolean enabled() { return sInstance != null; }

    @Override protected void onServiceConnected() { sInstance = this; }
    @Override public boolean onUnbind(android.content.Intent i) { sInstance = null; return super.onUnbind(i); }
    @Override public void onDestroy() { sInstance = null; super.onDestroy(); }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }
}
