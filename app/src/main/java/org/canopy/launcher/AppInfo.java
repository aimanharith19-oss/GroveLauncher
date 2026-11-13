/*
 * Copyright 2026 The Canopy Launcher authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.canopy.launcher;

import android.content.ComponentName;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;

/** One launchable activity (an "app" in the drawer). Work-profile apps carry their own user handle. */
public class AppInfo {
    public final ComponentName component;
    public final UserHandle user;
    public final long serial;
    public final String key;
    public String label;
    public Drawable icon;
    public long installTime;
    public boolean work;

    public AppInfo(ComponentName cn, UserHandle user, long serial) {
        this.component = cn;
        this.user = user;
        this.serial = serial;
        this.key = keyOf(cn.flattenToString(), serial);
    }

    public static String keyOf(String flatComponent, long serial) {
        return flatComponent + "#" + serial;
    }

    public String pkg() { return component.getPackageName(); }
}
