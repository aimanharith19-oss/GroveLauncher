/*
 * Copyright 2026 The Canopy Launcher authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.canopy.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.view.View;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * "3D wallpaper": your picture sits slightly behind the screen and shifts as you tilt the phone, while the
 * icons float a little in the opposite direction — a parallax depth effect (like iOS's perspective zoom).
 * Uses the motion sensor only while the home screen is on screen.
 */
public class Wallpaper3D extends View implements SensorEventListener {
    public interface DepthTarget { void onDepth(float dx, float dy); }

    private Bitmap bmp;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Matrix m = new Matrix();
    private final SensorManager sm;
    private Sensor sensor;
    private DepthTarget target;

    // tilt state (radians), relative to a slowly following "rest" position
    private float pitch, roll, basePitch = Float.NaN, baseRoll;
    private float x, y, vx, vy; // smoothed offset in -1..1
    private float page, lastPage = -1; // workspace page parallax 0..1
    private final float strength; // 0..1
    private final float[] rot = new float[9], ori = new float[3];
    private boolean listening;

    public Wallpaper3D(Context c) {
        super(c);
        sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
        if (sm != null) {
            sensor = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
            if (sensor == null) sensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        }
        strength = Prefs.sp(c).getInt("wall3d_depth", 60) / 100f;
        reload();
    }

    static File file(Context c) { return new File(c.getFilesDir(), "wallpaper3d.jpg"); }
    static File fgFile(Context c) { return new File(c.getFilesDir(), "wallpaper3d_subject.png"); }

    /** On by default: whatever wallpaper you set becomes 3D (once Canopy may read it). */
    public static boolean wanted(Context c) { return Prefs.sp(c).getBoolean("wall3d", true); }
    public static boolean enabled(Context c) { return wanted(c) && file(c).exists() && !(usesSystem(c) && liveWallpaper(c)); }

    /** A live wallpaper (KLWP, etc.) is set: it draws itself, so Canopy doesn't cover it. */
    static boolean liveWallpaper(Context c) {
        try { return android.app.WallpaperManager.getInstance(c).getWallpaperInfo() != null; } catch (Throwable t) { return false; }
    }
    static boolean usesSystem(Context c) { return !"picked".equals(Prefs.sp(c).getString("wall3d_source", "system")); }

    /** Can Canopy read the phone's wallpaper? (Android asks for "All files access" for that since Android 13.) */
    public static boolean canReadSystem(Context c) {
        try {
            android.app.WallpaperManager wm = android.app.WallpaperManager.getInstance(c);
            if (wm.getWallpaperInfo() != null) return false; // live wallpaper: leave it to the system
            wm.peekFastDrawable();
            return true;
        } catch (Throwable t) { return false; }
    }

    /** Copies the phone's current wallpaper so it can be drawn in 3D. Returns false if it can't be read. */
    public static boolean copySystem(Context c) {
        try {
            android.app.WallpaperManager wm = android.app.WallpaperManager.getInstance(c);
            if (wm.getWallpaperInfo() != null) return false;
            android.graphics.drawable.Drawable d = wm.getDrawable();
            if (d == null) return false;
            android.util.DisplayMetrics dm = c.getResources().getDisplayMetrics();
            int target = (int) (Math.max(dm.widthPixels, dm.heightPixels) * 1.35f);
            int iw = Math.max(1, d.getIntrinsicWidth()), ih = Math.max(1, d.getIntrinsicHeight());
            float k = Math.min(1f, target / (float) Math.max(iw, ih));
            Bitmap b = Bitmap.createBitmap(Math.max(1, (int) (iw * k)), Math.max(1, (int) (ih * k)), Bitmap.Config.RGB_565);
            Canvas cv = new Canvas(b);
            d.setBounds(0, 0, b.getWidth(), b.getHeight());
            d.draw(cv);
            FileOutputStream out = new FileOutputStream(file(c));
            b.compress(Bitmap.CompressFormat.JPEG, 92, out);
            out.close();
            Prefs.sp(c).edit().putString("wall3d_source", "system").apply();
            Ui.resetWallpaper();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------------------------------------------------------- subject layer (widgets can sit behind it)

    private Bitmap fg;

    public static boolean hasSubject(Context c) { return fgFile(c).exists(); }

    /** A transparent PNG of the wallpaper's subject (e.g. "Copy subject" / "Extract object" in your gallery). */
    public static boolean saveSubject(Context c, Uri uri) {
        try {
            InputStream in = c.getContentResolver().openInputStream(uri);
            Bitmap b = BitmapFactory.decodeStream(in);
            if (in != null) in.close();
            if (b == null) return false;
            android.util.DisplayMetrics dm = c.getResources().getDisplayMetrics();
            int target = (int) (Math.max(dm.widthPixels, dm.heightPixels) * 1.35f);
            if (Math.max(b.getWidth(), b.getHeight()) > target) {
                float k = target / (float) Math.max(b.getWidth(), b.getHeight());
                b = Bitmap.createScaledBitmap(b, (int) (b.getWidth() * k), (int) (b.getHeight() * k), true);
            }
            FileOutputStream out = new FileOutputStream(fgFile(c));
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
            out.close();
            return true;
        } catch (Exception e) { return false; }
    }

    /**
     * Draws the subject layer over a page, at exactly the spot it has in the wallpaper. Called by a home page
     * between its "behind the subject" widgets and everything else. (ox, oy) = that page's position on screen.
     */
    public void drawSubject(Canvas c, float ox, float oy) {
        if (fg == null || bmp == null) return;
        c.save();
        c.translate(-ox, -oy);
        c.concat(m);
        float k = bmp.getWidth() / (float) fg.getWidth();
        c.scale(k, k);
        c.drawBitmap(fg, 0, 0, paint);
        c.restore();
    }

    public boolean hasSubjectLoaded() { return fg != null && bmp != null; }

    public void setDepthTarget(DepthTarget t) { target = t; }

    private Runnable subjectListener;
    /** Called whenever the picture moved (so pages with widgets behind the subject redraw it). */
    public void setOnMoved(Runnable r) { subjectListener = r; }

    /** Workspace scroll position as 0..1 across the pages: the picture pans a little with it too. */
    public void setPage(float fraction) {
        page = Math.max(0f, Math.min(1f, fraction));
        invalidate();
    }

    public void reload() {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            bmp = BitmapFactory.decodeFile(file(getContext()).getAbsolutePath(), o);
        } catch (Throwable t) { bmp = null; }
        try {
            fg = fgFile(getContext()).exists() ? BitmapFactory.decodeFile(fgFile(getContext()).getAbsolutePath()) : null;
        } catch (Throwable t) { fg = null; }
        invalidate();
    }

    /** Average brightness, so text on top can be black or white. */
    public static boolean isLight(Context c) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 32;
            Bitmap b = BitmapFactory.decodeFile(file(c).getAbsolutePath(), o);
            if (b == null) return false;
            Bitmap one = Bitmap.createScaledBitmap(b, 1, 1, true);
            return Ui.isLight(one.getPixel(0, 0));
        } catch (Throwable t) { return false; }
    }

    /** Copies a picked picture into Canopy (scaled to a bit more than the screen, for the depth margin). */
    public static boolean save(Context c, Uri uri) {
        try {
            BitmapFactory.Options b = new BitmapFactory.Options();
            b.inJustDecodeBounds = true;
            InputStream in = c.getContentResolver().openInputStream(uri);
            BitmapFactory.decodeStream(in, null, b);
            if (in != null) in.close();
            android.util.DisplayMetrics dm = c.getResources().getDisplayMetrics();
            int target = (int) (Math.max(dm.widthPixels, dm.heightPixels) * 1.35f);
            int sample = 1;
            while (Math.max(b.outWidth, b.outHeight) / (sample * 2) >= target) sample *= 2;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            in = c.getContentResolver().openInputStream(uri);
            Bitmap bmp = BitmapFactory.decodeStream(in, null, o);
            if (in != null) in.close();
            if (bmp == null) return false;
            FileOutputStream out = new FileOutputStream(file(c));
            bmp.compress(Bitmap.CompressFormat.JPEG, 92, out);
            out.close();
            Ui.resetWallpaper();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // Battery: ~30 updates a second while the phone moves; when it has been held still for a few seconds the
    // sensor drops to ~5 a second just to notice the next movement, and nothing is redrawn while still.
    private static final int FAST_US = 33_000, SLOW_US = 200_000;
    private static final float STILL = 0.0025f;
    private boolean slow;
    private int stillCount;
    private float lastRawPitch, lastRawRoll;

    public void start() {
        if (listening || sm == null || sensor == null) return;
        slow = false;
        stillCount = 0;
        listening = sm.registerListener(this, sensor, FAST_US);
        basePitch = Float.NaN;
    }

    public void stop() {
        if (!listening) return;
        sm.unregisterListener(this);
        listening = false;
        removeCallbacks(rate);
    }

    private final Runnable rate = new Runnable() {
        @Override public void run() {
            if (!listening) return;
            sm.unregisterListener(Wallpaper3D.this);
            listening = sm.registerListener(Wallpaper3D.this, sensor, slow ? SLOW_US : FAST_US);
        }
    };

    private void setSlow(boolean s) {
        if (slow == s) return;
        slow = s;
        stillCount = 0;
        removeCallbacks(rate);
        post(rate);
    }

    @Override protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override public void onSensorChanged(SensorEvent e) {
        SensorManager.getRotationMatrixFromVector(rot, e.values);
        SensorManager.getOrientation(rot, ori);
        pitch = ori[1];
        roll = ori[2];
        if (slow) {
            // just watching for the phone to move again
            if (Math.abs(pitch - lastRawPitch) > 0.01f || Math.abs(roll - lastRawRoll) > 0.01f) setSlow(false);
            lastRawPitch = pitch; lastRawRoll = roll;
            return;
        }
        lastRawPitch = pitch; lastRawRoll = roll;
        if (Float.isNaN(basePitch)) { basePitch = pitch; baseRoll = roll; }
        // the "rest" angle slowly follows how you hold the phone, so any angle feels centred
        basePitch += (pitch - basePitch) * 0.033f;
        baseRoll += (roll - baseRoll) * 0.033f;
        float maxTilt = 0.35f; // ~20°
        float tx = clamp((roll - baseRoll) / maxTilt), ty = clamp((pitch - basePitch) / maxTilt);
        // spring towards the target for a fluid, physical feel
        vx = (vx + (tx - x) * 0.26f) * 0.64f;
        vy = (vy + (ty - y) * 0.26f) * 0.64f;
        if (Math.abs(vx) < STILL && Math.abs(vy) < STILL && Math.abs(tx - x) < STILL && Math.abs(ty - y) < STILL) {
            // settled: don't redraw the same picture again; after ~4 s of stillness, slow the sensor down
            if (++stillCount > 120) setSlow(true);
            return;
        }
        stillCount = 0;
        x += vx;
        y += vy;
        invalidate();
        if (subjectListener != null) subjectListener.run();
        if (target != null) {
            float d = Ui.dp(getContext(), 7) * strength;
            target.onDepth(-x * d, y * d);
        }
    }

    private static float clamp(float v) { return Math.max(-1f, Math.min(1f, v)); }

    @Override public void onAccuracyChanged(Sensor s, int a) { }

    @Override protected void onDraw(Canvas c) {
        if (bmp == null) { c.drawColor(0xFF101418); return; }
        int w = getWidth(), h = getHeight();
        float margin = 0.04f + 0.08f * strength; // how far "behind" the screen the picture sits
        float scale = Math.max(w / (float) bmp.getWidth(), h / (float) bmp.getHeight()) * (1f + 2f * margin);
        float bw = bmp.getWidth() * scale, bh = bmp.getHeight() * scale;
        float extraX = (bw - w) / 2f, extraY = (bh - h) / 2f;
        // tilt uses up to 70% of the hidden margin, page parallax the rest, so an edge never shows
        float dx = -extraX + (x * 0.7f - (page - 0.5f) * 0.5f) * extraX, dy = -extraY - y * 0.9f * extraY;
        m.reset();
        m.setScale(scale, scale);
        m.postTranslate(dx, dy);
        c.drawBitmap(bmp, m, paint);
        if (subjectListener != null && page != lastPage) { lastPage = page; subjectListener.run(); }
    }
}
