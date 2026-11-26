/*
 * Copyright 2026 The Canopy Launcher authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.canopy.launcher;

import android.accessibilityservice.AccessibilityService;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * The dock. Swipe it up (like Microsoft Launcher) to reveal quick toggles, a brightness slider and a
 * second row of apps; swipe down or press Home to collapse.
 */
public class DockView extends LinearLayout {
    private final LauncherActivity launcher;
    private final Ui.Palette pal;
    public final CellLayout row1, row2;
    private final LinearLayout expanded;
    private final View handle;
    private final boolean expandable, background;
    private boolean isExpanded;
    private float downX, downY;
    private boolean swiping;
    private final int slop;
    private LinearLayout toggles;
    private SeekBar brightness;
    private boolean torchOn;
    private String torchId;
    private CameraManager.TorchCallback torchCb;

    public DockView(LauncherActivity c, int cols, int cellHeight, OnDragListener drag) {
        super(c);
        launcher = c;
        pal = Ui.palette(c);
        setOrientation(VERTICAL);
        expandable = Prefs.dockExpandable(c);
        background = Prefs.dockBackground(c);
        slop = ViewConfiguration.get(c).getScaledTouchSlop();

        handle = new View(c);
        handle.setBackground(Ui.round(0x99FFFFFF, Ui.dp(c, 2)));
        LayoutParams hlp = new LayoutParams(Ui.dp(c, 32), Ui.dp(c, 4));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        hlp.topMargin = Ui.dp(c, 6);
        hlp.bottomMargin = Ui.dp(c, 2);
        handle.setVisibility(expandable ? VISIBLE : GONE);
        handle.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { setExpanded(!isExpanded); } });
        addView(handle, hlp);

        expanded = new LinearLayout(c);
        expanded.setOrientation(VERTICAL);
        expanded.setVisibility(GONE);
        addView(expanded, new LayoutParams(-1, -2));
        buildExpanded(c);

        row2 = new CellLayout(c, HomeItem.DOCK, 0, cols, 1);
        row2.yOffset = 1;
        row2.setOnDragListener(drag);
        row2.setPadding(Ui.dp(c, 6), 0, Ui.dp(c, 6), 0);
        expanded.addView(row2, new LayoutParams(-1, cellHeight));

        row1 = new CellLayout(c, HomeItem.DOCK, 0, cols, 1);
        row1.setOnDragListener(drag);
        row1.setPadding(Ui.dp(c, 6), 0, Ui.dp(c, 6), 0);
        addView(row1, new LayoutParams(-1, cellHeight));
        updateBackground();
    }

    private void updateBackground() {
        if (isExpanded) setBackground(Ui.round(Ui.alpha(pal.surface, 0xF0), Ui.dp(getContext(), 28)));
        else setBackground(background ? Ui.round(0x33FFFFFF, Ui.dp(getContext(), 28)) : null);
        int label = isExpanded ? pal.text : Ui.onWallpaper(getContext());
        if (toggles != null) for (int i = 0; i < toggles.getChildCount(); i++) {
            View t = toggles.getChildAt(i);
            if (t instanceof LinearLayout && ((LinearLayout) t).getChildCount() > 1) ((TextView) ((LinearLayout) t).getChildAt(1)).setTextColor(label);
        }
    }

    public boolean isExpanded() { return isExpanded; }

    public void setExpanded(boolean e) {
        if (!expandable && e) return;
        if (isExpanded == e) return;
        isExpanded = e;
        expanded.setVisibility(e ? VISIBLE : GONE);
        if (e) refreshToggles();
        updateBackground();
        if (e) {
            expanded.setAlpha(0f);
            expanded.setTranslationY(Ui.dp(getContext(), 30));
            expanded.animate().alpha(1f).translationY(0).setDuration(Anim.dur(getContext(), Anim.fluid(getContext()) ? 420 : Anim.calm(getContext()) ? 300 : 200))
                    .setInterpolator(Anim.open(getContext())).start();
        }
    }

    // ------------------------------------------------------------------ swipe up / down

    @Override public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                swiping = false;
                break;
            case MotionEvent.ACTION_MOVE: {
                float dy = ev.getY() - downY, dx = ev.getX() - downX;
                if (Math.abs(dy) > slop * 2 && Math.abs(dy) > Math.abs(dx) * 1.3f) {
                    if (ev.getY() < row1.getTop() && isExpanded && dy < 0) break; // let sliders / scrolls work
                    swiping = true;
                    return true;
                }
                break;
            }
        }
        return false;
    }

    @Override public boolean onTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                return true;
            case MotionEvent.ACTION_UP: {
                float dy = ev.getY() - downY;
                if (dy < -slop * 2) {
                    if (expandable) setExpanded(true);
                    else launcher.onSwipeUp(-1, -1);
                } else if (dy > slop * 2) {
                    if (isExpanded) setExpanded(false);
                    else launcher.onSwipeDown();
                }
                swiping = false;
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                swiping = false;
                return true;
        }
        return true;
    }

    // ------------------------------------------------------------------ quick toggles

    private void buildExpanded(final Context c) {
        HorizontalScrollView hs = new HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        toggles = new LinearLayout(c);
        toggles.setPadding(Ui.dp(c, 8), Ui.dp(c, 10), Ui.dp(c, 8), Ui.dp(c, 4));
        hs.addView(toggles);
        expanded.addView(hs, new LayoutParams(-1, -2));

        LinearLayout br = new LinearLayout(c);
        br.setGravity(Gravity.CENTER_VERTICAL);
        br.setPadding(Ui.dp(c, 16), Ui.dp(c, 4), Ui.dp(c, 16), Ui.dp(c, 4));
        ImageView bi = new ImageView(c);
        bi.setImageDrawable(Ui.tinted(c, R.drawable.ic_brightness, pal.subtext));
        br.addView(bi, new LayoutParams(Ui.dp(c, 20), Ui.dp(c, 20)));
        brightness = new SeekBar(c);
        brightness.setMax(255);
        brightness.setProgressTintList(ColorStateList.valueOf(pal.accent));
        brightness.setThumbTintList(ColorStateList.valueOf(pal.accent));
        brightness.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (!user || !Settings.System.canWrite(c)) return;
                try {
                    Settings.System.putInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                    Settings.System.putInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, Math.max(1, p));
                } catch (Exception ignored) { }
            }
            @Override public void onStartTrackingTouch(SeekBar s) { if (!Settings.System.canWrite(c)) askWriteSettings(); }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        br.addView(brightness, new LayoutParams(0, -2, 1));
        expanded.addView(br, new LayoutParams(-1, -2));
    }

    private void addToggle(int icon, String label, boolean active, OnClickListener l) {
        Context c = getContext();
        LinearLayout t = new LinearLayout(c);
        t.setOrientation(VERTICAL);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setPadding(Ui.dp(c, 6), 0, Ui.dp(c, 6), 0);
        ImageView iv = new ImageView(c);
        iv.setImageDrawable(Ui.tinted(c, icon, active ? pal.onAccent : pal.text));
        int p = Ui.dp(c, 12);
        iv.setPadding(p, p, p, p);
        iv.setBackground(Ui.pressable(c, active ? pal.accent : pal.surfaceAlt, Ui.dp(c, 24)));
        t.addView(iv, new LayoutParams(Ui.dp(c, 48), Ui.dp(c, 48)));
        TextView tv = Ui.text(c, label, 11, pal.text);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, Ui.dp(c, 4), 0, 0);
        t.addView(tv, new LayoutParams(Ui.dp(c, 60), -2));
        iv.setOnClickListener(l);
        toggles.addView(t);
    }

    @SuppressWarnings("deprecation")
    public void refreshToggles() {
        final Context c = getContext();
        toggles.removeAllViews();
        boolean wifi = false;
        try {
            WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            wifi = wm != null && wm.isWifiEnabled();
        } catch (Exception ignored) { }
        addToggle(R.drawable.ic_wifi, "Wi‑Fi", wifi, new OnClickListener() {
            @Override public void onClick(View v) {
                launcher.safeStart(new Intent(Build.VERSION.SDK_INT >= 29 ? Settings.Panel.ACTION_WIFI : Settings.ACTION_WIFI_SETTINGS));
            }
        });
        addToggle(R.drawable.ic_network, "Internet", false, new OnClickListener() {
            @Override public void onClick(View v) {
                launcher.safeStart(new Intent(Build.VERSION.SDK_INT >= 29 ? Settings.Panel.ACTION_INTERNET_CONNECTIVITY : Settings.ACTION_WIRELESS_SETTINGS));
            }
        });
        addToggle(R.drawable.ic_bluetooth, "Bluetooth", false, new OnClickListener() {
            @Override public void onClick(View v) { launcher.safeStart(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); }
        });
        addToggle(R.drawable.ic_flashlight, "Torch", torchOn, new OnClickListener() {
            @Override public void onClick(View v) { toggleTorch(); }
        });
        boolean rot = false;
        try { rot = Settings.System.getInt(c.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION) == 1; } catch (Exception ignored) { }
        final boolean rotNow = rot;
        addToggle(R.drawable.ic_rotate, "Auto-rotate", rot, new OnClickListener() {
            @Override public void onClick(View v) {
                if (!Settings.System.canWrite(c)) { askWriteSettings(); return; }
                Settings.System.putInt(c.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, rotNow ? 0 : 1);
                refreshToggles();
            }
        });
        final NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        final boolean dnd = nm.getCurrentInterruptionFilter() > NotificationManager.INTERRUPTION_FILTER_ALL;
        addToggle(R.drawable.ic_dnd, "Do not disturb", dnd, new OnClickListener() {
            @Override public void onClick(View v) {
                if (!nm.isNotificationPolicyAccessGranted()) {
                    Ui.toast(c, "Allow Grove to change Do Not Disturb");
                    launcher.safeStart(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS));
                    return;
                }
                nm.setInterruptionFilter(dnd ? NotificationManager.INTERRUPTION_FILTER_ALL : NotificationManager.INTERRUPTION_FILTER_PRIORITY);
                refreshToggles();
            }
        });
        addToggle(R.drawable.ic_lock, "Lock", false, new OnClickListener() {
            @Override public void onClick(View v) {
                setExpanded(false);
                launcher.runAction("lock");
            }
        });
        addToggle(R.drawable.ic_settings, "Settings", false, new OnClickListener() {
            @Override public void onClick(View v) { launcher.safeStart(new Intent(Settings.ACTION_SETTINGS)); }
        });
        updateBackground();
        try {
            brightness.setProgress(Settings.System.getInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS));
        } catch (Exception ignored) { }
    }

    private void askWriteSettings() {
        Ui.toast(getContext(), "Allow Grove to modify system settings for brightness and rotation");
        launcher.safeStart(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + getContext().getPackageName())));
    }

    private void toggleTorch() {
        try {
            CameraManager cm = (CameraManager) getContext().getSystemService(Context.CAMERA_SERVICE);
            if (torchId == null) {
                for (String id : cm.getCameraIdList()) {
                    Boolean f = cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                    if (f != null && f) { torchId = id; break; }
                }
                if (torchCb == null) {
                    torchCb = new CameraManager.TorchCallback() {
                        @Override public void onTorchModeChanged(String id, boolean enabled) {
                            if (id.equals(torchId)) { torchOn = enabled; if (isExpanded) refreshToggles(); }
                        }
                    };
                    cm.registerTorchCallback(torchCb, App.MAIN);
                }
            }
            if (torchId == null) { Ui.toast(getContext(), "No flashlight found"); return; }
            cm.setTorchMode(torchId, !torchOn);
        } catch (Exception e) {
            Ui.toast(getContext(), "Flashlight is busy");
        }
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (torchCb != null) {
            try { ((CameraManager) getContext().getSystemService(Context.CAMERA_SERVICE)).unregisterTorchCallback(torchCb); } catch (Exception ignored) { }
            torchCb = null;
        }
    }

    @SuppressWarnings("unused")
    private static int lockAction() { return AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN; }
}
