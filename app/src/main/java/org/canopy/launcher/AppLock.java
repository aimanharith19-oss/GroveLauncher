/*
 * Copyright 2026 The Grove Launcher authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.canopy.launcher;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.CancellationSignal;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * App lock: fingerprint / face, with the phone's PIN, pattern or password as the way in when they don't work.
 * Uses only the system's own prompt; nothing is stored or sent. "Settings only" keeps Grove's settings closed
 * (so the lock can't be switched off by someone else, wherever the settings are opened from, including the
 * notification panel); "Launcher and settings" also covers the home screen.
 */
final class AppLock {
    private AppLock() { }

    static final int REQ_CREDENTIAL = 9191;
    private static final long GRACE = 30_000;
    private static long unlockedAt;
    private static Runnable pendingOk;

    static String mode(Context c) { return Prefs.sp(c).getString("applock", "off"); }

    static boolean secure(Context c) {
        KeyguardManager km = (KeyguardManager) c.getSystemService(Context.KEYGUARD_SERVICE);
        return km != null && km.isDeviceSecure();
    }

    private static boolean wanted(Context c, boolean settings) {
        String m = mode(c);
        if ("off".equals(m) || !secure(c)) return false;
        return settings || "all".equals(m);
    }

    static void relock() { unlockedAt = 0; }

    /** Shows an opaque cover over the activity until the person unlocks; closes a settings page if they give up. */
    static void guard(final Activity a, final boolean settings) {
        final ViewGroup decor = (ViewGroup) a.getWindow().getDecorView();
        View old = decor.findViewWithTag("grove:lock");
        if (!wanted(a, settings) || System.currentTimeMillis() - unlockedAt < GRACE) {
            if (old != null) decor.removeView(old);
            return;
        }
        if (old != null) return; // already asking
        final Ui.Palette pal = Ui.palette(a);
        final FrameLayout cover = new FrameLayout(a);
        cover.setTag("grove:lock");
        cover.setBackgroundColor(pal.bg);
        cover.setClickable(true);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        TextView t = Ui.text(a, "Grove is locked", 22, pal.text);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        col.addView(t);
        TextView b = Ui.text(a, "Unlock", 16, pal.onAccent);
        b.setBackground(Ui.round(pal.accent, Ui.dp(a, 24)));
        b.setPadding(Ui.dp(a, 28), Ui.dp(a, 12), Ui.dp(a, 28), Ui.dp(a, 12));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
        blp.topMargin = Ui.dp(a, 24);
        col.addView(b, blp);
        cover.addView(col, new FrameLayout.LayoutParams(-1, -1));
        decor.addView(cover, new ViewGroup.LayoutParams(-1, -1));
        final Runnable ok = new Runnable() {
            @Override public void run() { unlockedAt = System.currentTimeMillis(); decor.removeView(cover); }
        };
        final Runnable fail = new Runnable() {
            @Override public void run() { if (settings) a.finish(); }
        };
        b.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { ask(a, ok, fail); } });
        ask(a, ok, fail);
    }

    @SuppressWarnings("deprecation")
    static void ask(final Activity a, final Runnable ok, final Runnable fail) {
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            android.hardware.biometrics.BiometricPrompt.Builder bb = new android.hardware.biometrics.BiometricPrompt.Builder(a)
                    .setTitle("Unlock Grove");
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                bb.setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK
                        | android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL);
            } else if (android.os.Build.VERSION.SDK_INT == 29) {
                bb.setDeviceCredentialAllowed(true);
            } else {
                bb.setNegativeButton("Cancel", a.getMainExecutor(), new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) { if (fail != null) fail.run(); }
                });
            }
            try {
                bb.build().authenticate(new CancellationSignal(), a.getMainExecutor(),
                        new android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                            @Override public void onAuthenticationSucceeded(android.hardware.biometrics.BiometricPrompt.AuthenticationResult r) { ok.run(); }
                            @Override public void onAuthenticationError(int code, CharSequence msg) { if (fail != null) fail.run(); }
                        });
            } catch (Throwable t) { if (fail != null) fail.run(); }
        } else {
            KeyguardManager km = (KeyguardManager) a.getSystemService(Context.KEYGUARD_SERVICE);
            Intent i = km == null ? null : km.createConfirmDeviceCredentialIntent("Unlock Grove", null);
            if (i == null) { if (fail != null) fail.run(); return; }
            pendingOk = ok;
            a.startActivityForResult(i, REQ_CREDENTIAL);
        }
    }

    /** For Android 8: call from onActivityResult. */
    static void onResult(Activity a, int req, int result, Runnable fail) {
        if (req != REQ_CREDENTIAL) return;
        Runnable ok = pendingOk;
        pendingOk = null;
        if (result == Activity.RESULT_OK && ok != null) ok.run(); else if (fail != null) fail.run();
    }
}
