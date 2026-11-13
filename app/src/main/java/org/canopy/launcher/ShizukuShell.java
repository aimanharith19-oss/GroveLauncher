/*
 * Copyright 2026 The Canopy Launcher authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.canopy.launcher;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;

/**
 * Talks to Shizuku (https://shizuku.rikka.app) so Canopy can run a shell command — "lock the screen" — with
 * ADB-level rights instead of needing an accessibility service. Speaks Shizuku's public binder protocol
 * directly (from its MIT-licensed API definitions), so no library is bundled. Only used for the actions you pick.
 */
public final class ShizukuShell {
    private ShizukuShell() { }

    static final String MANAGER = "moe.shizuku.privileged.api";
    private static final String SERVICE = "moe.shizuku.server.IShizukuService";
    private static final String APPLICATION = "moe.shizuku.server.IShizukuApplication";
    private static final String PROCESS = "moe.shizuku.server.IRemoteProcess";
    private static final String EXTRA_BINDER = "moe.shizuku.privileged.api.intent.extra.BINDER";
    // transaction codes = FIRST_CALL_TRANSACTION (1) + the ids in IShizukuService.aidl
    private static final int T_VERSION = 3, T_NEW_PROCESS = 8, T_REQUEST_PERMISSION = 15, T_CHECK_SELF = 16, T_ATTACH = 18;
    private static final int P_WAIT_FOR = 4;

    private static volatile IBinder binder;
    private static volatile boolean granted;
    private static Runnable pending;

    /** Receives what Shizuku sends to our app: bind info and the answer to the permission request. */
    private static final Binder CLIENT = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code == INTERFACE_TRANSACTION) { reply.writeString(APPLICATION); return true; }
            try {
                data.enforceInterface(APPLICATION);
                if (code == 2) { // bindApplication(Bundle)
                    Bundle b = data.readInt() != 0 ? Bundle.CREATOR.createFromParcel(data) : null;
                    if (b != null) granted = b.getBoolean("shizuku:attach-reply-permission-granted", granted);
                    return true;
                }
                if (code == 3) { // dispatchRequestPermissionResult(int, Bundle)
                    data.readInt();
                    Bundle b = data.readInt() != 0 ? Bundle.CREATOR.createFromParcel(data) : null;
                    granted = b != null && b.getBoolean("shizuku:request-permission-reply-allowed", false);
                    final Runnable r = pending;
                    pending = null;
                    App.MAIN.post(new Runnable() {
                        @Override public void run() {
                            if (granted && r != null) r.run();
                            else if (!granted) Ui.toast(App.get(), "Shizuku permission denied");
                        }
                    });
                    return true;
                }
            } catch (Throwable ignored) { }
            return true;
        }
    };

    static void onBinder(IBinder b, String pkg) {
        if (b == null || b == binder) return;
        binder = b;
        try {
            b.linkToDeath(new IBinder.DeathRecipient() {
                @Override public void binderDied() { binder = null; granted = false; }
            }, 0);
        } catch (Throwable ignored) { }
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            Bundle args = new Bundle();
            args.putInt("shizuku:attach-api-version", 13);
            args.putString("shizuku:attach-package-name", pkg);
            data.writeInterfaceToken(SERVICE);
            data.writeStrongBinder(CLIENT);
            data.writeInt(1);
            args.writeToParcel(data, 0);
            b.transact(T_ATTACH, data, reply, 0);
            reply.readException();
        } catch (Throwable ignored) {
        } finally { data.recycle(); reply.recycle(); }
        checkGranted();
    }

    public static boolean installed(Context c) {
        try { c.getPackageManager().getPackageInfo(MANAGER, 0); return true; } catch (PackageManager.NameNotFoundException e) { return false; }
    }

    /** Shizuku is running and has reached Canopy. */
    public static boolean running() { IBinder b = binder; return b != null && b.pingBinder(); }

    public static boolean granted() { return running() && (granted || checkGranted()); }

    private static boolean checkGranted() {
        IBinder b = binder;
        if (b == null) return false;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SERVICE);
            b.transact(T_CHECK_SELF, data, reply, 0);
            reply.readException();
            granted = reply.readInt() != 0;
        } catch (Throwable t) {
            granted = false;
        } finally { data.recycle(); reply.recycle(); }
        return granted;
    }

    /** Shows Shizuku's "allow Grove?" prompt; runs {@code then} once allowed. */
    public static void requestPermission(Runnable then) {
        IBinder b = binder;
        if (b == null) return;
        pending = then;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SERVICE);
            data.writeInt(1);
            b.transact(T_REQUEST_PERMISSION, data, reply, 0);
            reply.readException();
        } catch (Throwable ignored) {
        } finally { data.recycle(); reply.recycle(); }
    }

    /** Runs a command as Shizuku's shell user (in the background). */
    public static void exec(final String... cmd) {
        final IBinder b = binder;
        if (b == null) return;
        App.IO.execute(new Runnable() {
            @Override public void run() {
                Parcel data = Parcel.obtain(), reply = Parcel.obtain();
                IBinder proc = null;
                try {
                    data.writeInterfaceToken(SERVICE);
                    data.writeStringArray(cmd);
                    data.writeStringArray(null);
                    data.writeString(null);
                    b.transact(T_NEW_PROCESS, data, reply, 0);
                    reply.readException();
                    proc = reply.readStrongBinder();
                } catch (Throwable ignored) {
                } finally { data.recycle(); reply.recycle(); }
                if (proc == null) return;
                Parcel d2 = Parcel.obtain(), r2 = Parcel.obtain();
                try { // wait for it to finish so the process is cleaned up
                    d2.writeInterfaceToken(PROCESS);
                    proc.transact(P_WAIT_FOR, d2, r2, 0);
                    r2.readException();
                } catch (Throwable ignored) {
                } finally { d2.recycle(); r2.recycle(); }
            }
        });
    }

    /** Locks the screen through Shizuku. Returns false if Shizuku can't be used right now. */
    public static boolean lock(Context c) {
        if (!running()) return false;
        Runnable go = new Runnable() { @Override public void run() { exec("input", "keyevent", "223"); } }; // KEYCODE_SLEEP
        if (granted()) go.run();
        else requestPermission(go);
        return true;
    }

    public static String status(Context c) {
        if (running()) return granted() ? "Running — Grove is allowed (lock screen uses Shizuku)" : "Running — tap to allow Grove";
        return installed(c) ? "Installed but not running — start it in the Shizuku app" : "Not installed (optional)";
    }

    /** Shizuku delivers its binder here when it starts, and whenever Canopy starts. */
    public static final class Provider extends ContentProvider {
        @Override public boolean onCreate() { return true; }

        @Override public Bundle call(String method, String arg, Bundle extras) {
            if ("sendBinder".equals(method) && extras != null) {
                try {
                    extras.setClassLoader(moe.shizuku.api.BinderContainer.class.getClassLoader());
                    moe.shizuku.api.BinderContainer bc = extras.getParcelable(EXTRA_BINDER);
                    if (bc != null && getContext() != null) onBinder(bc.binder, getContext().getPackageName());
                } catch (Throwable ignored) { }
            }
            return new Bundle();
        }

        @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
        @Override public String getType(Uri u) { return null; }
        @Override public Uri insert(Uri u, ContentValues v) { return null; }
        @Override public int delete(Uri u, String s, String[] a) { return 0; }
        @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
    }
}
