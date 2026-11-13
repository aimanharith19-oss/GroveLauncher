/*
 * Copyright 2026 The Canopy Launcher authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.canopy.launcher;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

/** Shown (in its own process) when the launcher crashes, so a startup crash can never lock you out. */
public class CrashActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        final String report = CrashLog.read(this) == null ? "No report saved." : CrashLog.read(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF101418);
        int p = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(p, p * 2, p, p);
        TextView t = new TextView(this);
        t.setText("Grove crashed");
        t.setTextColor(Color.WHITE);
        t.setTextSize(22);
        root.addView(t);
        TextView d = new TextView(this);
        d.setText("Copy the report and send it to me. If it keeps crashing, reset the settings.");
        d.setTextColor(0xFFB0B8C0);
        d.setPadding(0, p / 2, 0, p / 2);
        root.addView(d);
        ScrollView sv = new ScrollView(this);
        TextView r = new TextView(this);
        r.setText(report);
        r.setTextColor(0xFFD0D6DC);
        r.setTextSize(11);
        r.setTextIsSelectable(true);
        sv.addView(r);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        root.addView(btn("Copy report", new Runnable() { @Override public void run() {
            ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Grove crash", report));
            Toast.makeText(CrashActivity.this, "Copied", Toast.LENGTH_SHORT).show();
        } }));
        root.addView(btn("Reset settings and open", new Runnable() { @Override public void run() {
            new File(getApplicationInfo().dataDir, "shared_prefs/" + Prefs.NAME + ".xml").delete();
            relaunch();
        } }));
        root.addView(btn("Reset settings and home layout", new Runnable() { @Override public void run() {
            new File(getApplicationInfo().dataDir, "shared_prefs/" + Prefs.NAME + ".xml").delete();
            new File(getFilesDir(), "home.json").delete();
            new File(getFilesDir(), "home_wp.json").delete();
            relaunch();
        } }));
        root.addView(btn("Open Grove", new Runnable() { @Override public void run() { relaunch(); } }));
        setContentView(root);
    }

    private Button btn(String s, final Runnable r) {
        Button b = new Button(this);
        b.setText(s);
        b.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { r.run(); } });
        return b;
    }

    private void relaunch() {
        // the launcher process may still be alive with bad state: kill it so it starts fresh
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setPackage(getPackageName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
        android.os.Process.killProcess(android.os.Process.myPid());
    }
}
