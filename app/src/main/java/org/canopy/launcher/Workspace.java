/*
 * Copyright 2026 The Canopy Launcher authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.canopy.launcher;

import android.app.WallpaperManager;
import android.content.Context;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.OverScroller;

/**
 * Horizontally paged home screens with Nova-style gestures:
 * swipe up / swipe down / double-tap / pinch on empty space, long-press empty space for the home menu.
 */
public class Workspace extends ViewGroup {
    public interface Callbacks {
        /** x, y: where the swipe started, in workspace coordinates (for per-icon swipe actions). */
        void onSwipeUp(float x, float y);
        void onSwipeDown();
        void onTwoFingerSwipe(boolean up);
        void onDoubleTap();
        void onPinchIn();
        void onPinchOut();
        void onEmptyLongPress(float rawX, float rawY);
        void onPageScrolled(float pageFloat);
        boolean isDragging();
        boolean isGlancePage(int index);
    }

    private static final int NONE = 0, HORIZ = 1, VERT = 2, PINCH = 3;

    private final Callbacks cb;
    private final OverScroller scroller;
    private VelocityTracker vt;
    private final int slop, minFling, maxFling, swipeDist;
    private int mode = NONE;
    private float downX, downY, lastX;
    private boolean ownsStream, caughtFling;
    private int current;
    private final GestureDetector gd;
    private final ScaleGestureDetector sgd;
    private float pinchScale = 1f;
    private final WallpaperManager wm;
    private final boolean wallpaperScroll, infinite;
    private float lastY;
    private final String transition;
    private float twoStartY, twoLastY;
    private boolean twoTracking;

    public Workspace(Context c, Callbacks callbacks) {
        super(c);
        cb = callbacks;
        // Free-placed widgets may hang past a page's grid, right up to the screen edge.
        setClipChildren(false);
        setClipToPadding(false);
        scroller = new OverScroller(c, Anim.fluid(c) ? new Anim.Spring(8.5f, 5.5f) : new DecelerateInterpolator(1.6f));
        ViewConfiguration vc = ViewConfiguration.get(c);
        slop = vc.getScaledTouchSlop();
        minFling = vc.getScaledMinimumFlingVelocity() * 6;
        maxFling = vc.getScaledMaximumFlingVelocity();
        swipeDist = Ui.dp(c, 56);
        wm = WallpaperManager.getInstance(c);
        wallpaperScroll = Prefs.wallpaperScroll(c);
        infinite = Prefs.infiniteScroll(c);
        transition = Prefs.transition(c);
        setHapticFeedbackEnabled(true);
        setChildrenDrawingOrderEnabled(true);
        gd = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onDoubleTap(MotionEvent e) { cb.onDoubleTap(); return true; }
            @Override public boolean onSingleTapUp(MotionEvent e) {
                // Live wallpapers (KLWP, etc.) react to taps on empty home screen space
                if (mode == NONE && !cb.isGlancePage(current) && getWindowToken() != null) {
                    try {
                        wm.sendWallpaperCommand(getWindowToken(), WallpaperManager.COMMAND_TAP, (int) e.getRawX(), (int) e.getRawY(), 0, null);
                    } catch (Exception ignored) { }
                }
                return false;
            }
            @Override public void onLongPress(MotionEvent e) {
                if (mode == NONE && !cb.isGlancePage(current)) {
                    if (Anim.haptics(getContext())) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    cb.onEmptyLongPress(e.getRawX(), e.getRawY());
                }
            }
        });
        sgd = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector d) { pinchScale = 1f; mode = PINCH; return true; }
            @Override public boolean onScale(ScaleGestureDetector d) { pinchScale *= d.getScaleFactor(); return true; }
            @Override public void onScaleEnd(ScaleGestureDetector d) { if (pinchScale < 0.8f) { twoTracking = false; cb.onPinchIn(); } else if (pinchScale > 1.25f) { twoTracking = false; cb.onPinchOut(); } }
        });
    }

    public int getCurrentPage() { return current; }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec), h = MeasureSpec.getSize(hSpec);
        int cw = Math.max(0, w - getPaddingLeft() - getPaddingRight());
        int ch = Math.max(0, h - getPaddingTop() - getPaddingBottom());
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            // The feed doesn't share the home pages' bottom space (dock, search bar): it runs down to the navigation bar.
            int vh = v instanceof FeedPage ? Math.max(0, h - getPaddingTop() - feedBottom) : ch;
            v.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(vh, MeasureSpec.EXACTLY));
        }
        setMeasuredDimension(w, h);
    }

    private int feedBottom;

    /** Space kept free below the feed: just the navigation bar. */
    public void setFeedBottom(int px) {
        if (px == feedBottom) return;
        feedBottom = px;
        requestLayout();
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l;
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            int left = i * w + getPaddingLeft();
            v.layout(left, getPaddingTop(), left + v.getMeasuredWidth(), getPaddingTop() + v.getMeasuredHeight());
        }
        if (scroller.isFinished() && mode != HORIZ) scrollTo(current * getWidth(), 0);
        updateWallpaperOffsets();
    }

    public void setCurrentPageImmediate(int p) {
        current = clampPage(p);
        scroller.forceFinished(true);
        if (getWidth() > 0) scrollTo(current * getWidth(), 0);
        else requestLayout();
    }

    public void snapToPage(int p) {
        p = clampPage(p);
        int dx = p * getWidth() - getScrollX();
        current = p;
        int dist = Math.abs(dx);
        int dur = (int) Anim.dur(getContext(), Anim.fluid(getContext()) ? Math.min(560, 300 + dist / 3) : Math.min(420, 180 + dist / 3));
        scroller.startScroll(getScrollX(), 0, dx, 0, dur);
        postInvalidateOnAnimation();
    }

    private int clampPage(int p) { return Math.max(0, Math.min(getChildCount() - 1, p)); }

    @Override public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.getCurrX(), scroller.getCurrY());
            postInvalidateOnAnimation();
        }
    }

    @Override protected void onScrollChanged(int l, int t, int ol, int ot) {
        super.onScrollChanged(l, t, ol, ot);
        int w = getWidth();
        if (w <= 0) return;
        cb.onPageScrolled(l / (float) w);
        applyTransition();
        int n = getChildCount();
        updateWallpaperOffsets();
    }

    /** The feed (page 0) is drawn last so it slides in on top of the home screen. */
    @Override protected int getChildDrawingOrder(int count, int i) {
        if (count > 1 && cb.isGlancePage(0)) return i == count - 1 ? 0 : i + 1;
        return i;
    }


    /** Tells the wallpaper (and live wallpapers like KLWP) which home page you're on. */
    void updateWallpaperOffsets() {
        int w = getWidth(), n = getChildCount(), l = getScrollX();
        if (w <= 0) return;
        if (wallpaperScroll && getWindowToken() != null) {
            // Offsets count only the home pages (not the feed), one step per page — what live wallpapers
            // like KLWP expect to animate per screen.
            int glance = cb.isGlancePage(0) ? 1 : 0;
            int pages = n - glance;
            float off = pages > 1 ? Math.max(0f, Math.min(1f, (l - glance * w) / (float) ((pages - 1) * w))) : 0f;
            try {
                wm.setWallpaperOffsetSteps(pages > 1 ? 1f / (pages - 1) : 1f, 1f);
                wm.setWallpaperOffsets(getWindowToken(), off, 0.5f);
            } catch (Exception ignored) { }
        }
    }

    private int lastHomeBlur = -1;
    private String liveTransition;

    /** Read live, so a new page transition works at once (no restart needed). */
    private String transition() {
        if (liveTransition == null) liveTransition = transition;
        return liveTransition;
    }

    public void setTransition(String t) { liveTransition = t; }

    /** Nova-style page transition effects. */
    private void applyTransition() {
        int w = getWidth();
        if (w <= 0) return;
        float dens = getResources().getDisplayMetrics().density;
        if (getScrollX() >= w && lastHomeBlur > 0 && getChildCount() > 1 && android.os.Build.VERSION.SDK_INT >= 31) {
            getChildAt(1).setRenderEffect(null);
            lastHomeBlur = 0;
        }
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            float off = (getScrollX() - i * w) / (float) w; // > 0: page is to the left of the screen
            v.setRotationY(0); v.setRotation(0); v.setScaleX(1); v.setScaleY(1); v.setAlpha(1); v.setTranslationX(0);
            // Microsoft-style feed: the feed slides over the first home page, which stays put and recedes.
            if (i == 1 && cb.isGlancePage(0) && getScrollX() < w) {
                float f = 1f - getScrollX() / (float) w; // 0 = home, 1 = feed fully open
                v.setTranslationX(getScrollX() - w);
                float sc = 1f - 0.07f * f;
                v.setScaleX(sc);
                v.setScaleY(sc);
                v.setAlpha(1f - 0.8f * f);
                if (android.os.Build.VERSION.SDK_INT >= 31 && Prefs.sp(getContext()).getBoolean("depth_blur", true)) {
                    int br = f >= 0.999f ? Math.round(dens * 20) : 0; // blur only once settled: re-blurring a whole page every frame was a stutter
                    if (Math.abs(br - lastHomeBlur) >= 2 || (br == 0 && lastHomeBlur != 0)) {
                        lastHomeBlur = br;
                        v.setRenderEffect(br > 0 ? android.graphics.RenderEffect.createBlurEffect(br, br,
                                android.graphics.Shader.TileMode.CLAMP) : null);
                    }
                }
                continue;
            }
            if (i == 0 && cb.isGlancePage(0)) {
                // a soft shadow edge on the feed while it slides
                float f = 1f - Math.min(1f, getScrollX() / (float) w);
                v.setElevation(f > 0f && f < 1f ? 12 * dens : 0);
                if (f > 0f && f < 1f && !Anim.minimal(getContext())) {
                    // swinging away to the home screen: the feed hinges on its right edge, shrinks and fades
                    float p = 1f - f;
                    v.setCameraDistance(9000 * dens);
                    v.setPivotX(v.getWidth());
                    v.setPivotY(v.getHeight() / 2f);
                    v.setRotationY(-24f * p);
                    float sc = 1f - 0.08f * p;
                    v.setScaleX(sc);
                    v.setScaleY(sc);
                    v.setAlpha(1f - 0.55f * p);
                }
                continue;
            }
            if ("slide".equals(transition()) || Math.abs(off) >= 1f || off == 0f) continue;
            float vw = v.getWidth(), vh = v.getHeight();
            switch (transition()) {
                case "cube":
                    v.setCameraDistance(8000 * dens);
                    v.setPivotX(off > 0 ? vw : 0);
                    v.setPivotY(vh / 2f);
                    v.setRotationY(-90f * off);
                    break;
                case "stack":
                    if (off < 0) {
                        v.setTranslationX(w * off);
                        float sc = 0.8f + 0.2f * (1 + off);
                        v.setScaleX(sc); v.setScaleY(sc);
                        v.setAlpha(1 + off);
                    }
                    break;
                case "zoom": {
                    float sc = 1f - 0.18f * Math.abs(off);
                    v.setScaleX(sc); v.setScaleY(sc);
                    v.setAlpha(1f - 0.6f * Math.abs(off));
                    break;
                }
                case "rotate":
                    v.setPivotX(vw / 2f);
                    v.setPivotY(vh);
                    v.setRotation(15f * off);
                    break;
                case "flip":
                    v.setCameraDistance(12000 * dens);
                    v.setPivotX(vw / 2f);
                    v.setPivotY(vh / 2f);
                    v.setTranslationX(w * off);
                    v.setRotationY(-180f * off);
                    v.setAlpha(Math.abs(off) < 0.5f ? 1f : 0f);
                    break;
            }
        }
    }

    private static boolean canScrollH(View v, int dir, float x, float y) {
        if (v.getVisibility() != VISIBLE) return false;
        if (v instanceof ViewGroup && !(v instanceof CellLayout)) {
            ViewGroup g = (ViewGroup) v;
            for (int i = g.getChildCount() - 1; i >= 0; i--) {
                View c = g.getChildAt(i);
                float cx = x + g.getScrollX() - c.getLeft(), cy = y + g.getScrollY() - c.getTop();
                if (cx >= 0 && cy >= 0 && cx < c.getWidth() && cy < c.getHeight() && canScrollH(c, dir, cx, cy)) return true;
            }
        }
        return v.canScrollHorizontally(dir);
    }

    private void track(MotionEvent ev) {
        if (vt == null) vt = VelocityTracker.obtain();
        vt.addMovement(ev);
    }

    private void startTwo(MotionEvent ev) {
        twoTracking = true;
        twoStartY = twoLastY = (ev.getY(0) + ev.getY(1)) / 2f;
        pinchScale = 1f;
    }

    private void resetTracking() {
        if (vt != null) { vt.recycle(); vt = null; }
        mode = NONE;
        ownsStream = false;
        caughtFling = false;
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (cb.isDragging()) return false;
        int a = ev.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) {
            resetTracking();
            downX = lastX = ev.getX();
            downY = ev.getY();
            track(ev);
            if (!scroller.isFinished()) {
                int remaining = Math.max(Math.abs(scroller.getFinalX() - getScrollX()), Math.abs(scroller.getFinalY() - getScrollY()));
                scroller.abortAnimation();
                if (remaining < getWidth() * 0.35f) {
                    // The page is basically settled (e.g. just came back from the feed): finish it and let this
                    // touch be a normal gesture, so a quick swipe up still opens the drawer.
                    scrollTo(scroller.getFinalX(), scroller.getFinalY());
                    return false;
                }
                // Caught a page mid-flight: keep it moving with your finger.
                caughtFling = true;
                mode = HORIZ;
                return true;
            }
            return false;
        }
        track(ev);
        if (a == MotionEvent.ACTION_POINTER_DOWN && ev.getPointerCount() == 2 && !cb.isGlancePage(current)) {
            mode = PINCH;
            startTwo(ev);
            return true;
        }
        if (a == MotionEvent.ACTION_MOVE && ev.getPointerCount() == 1) {
            float dx = ev.getX() - downX, dy = ev.getY() - downY;
            int dir = classify(dx, dy);
            if (dir == VERT) {
                mode = VERT;
                lastY = ev.getY();
                return true;
            }
            if (dir == HORIZ) {
                // Let horizontally scrollable content (e.g. horizontal widgets on the feed) scroll first.
                View page = current < getChildCount() ? getChildAt(current) : null;
                if (page != null && canScrollH(page, dx < 0 ? 1 : -1,
                        downX + getScrollX() - page.getLeft(), downY + getScrollY() - page.getTop())) return false;
                mode = HORIZ;
                lastX = ev.getX();
                return true;
            }
        }
        return false;
    }

    /**
     * Decides between a page swipe and an up/down gesture. Thumb swipes are rarely straight, so vertical
     * wins unless the movement is clearly sideways (angle under ~35°).
     */
    private int classify(float dx, float dy) {
        float ax = Math.abs(dx), ay = Math.abs(dy);
        boolean vertOk = !cb.isGlancePage(current);
        if (vertOk && ay > slop && ay >= ax * 0.7f) return VERT;
        if (ax > slop && ax > ay * 1.45f) return HORIZ;
        return NONE;
    }

    @Override public boolean onTouchEvent(MotionEvent ev) {
        int a = ev.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) {
            ownsStream = true;
            downX = lastX = ev.getX();
            downY = ev.getY();
            mode = caughtFling ? HORIZ : NONE;
            if (vt == null) track(ev);
        } else {
            track(ev);
        }
        if (ownsStream && mode != HORIZ) gd.onTouchEvent(ev);
        sgd.onTouchEvent(ev);
        switch (a) {
            case MotionEvent.ACTION_POINTER_DOWN:
                if (ev.getPointerCount() == 2 && mode != HORIZ && !cb.isGlancePage(current)) { mode = PINCH; startTwo(ev); }
                break;
            case MotionEvent.ACTION_POINTER_UP:
                if (mode == PINCH && twoTracking) {
                    twoTracking = false;
                    float dy = twoLastY - twoStartY;
                    if (Math.abs(dy) > swipeDist && pinchScale > 0.82f && pinchScale < 1.2f) cb.onTwoFingerSwipe(dy < 0);
                }
                break;
            case MotionEvent.ACTION_MOVE: {
                if (mode == PINCH) {
                    if (ev.getPointerCount() >= 2) twoLastY = (ev.getY(0) + ev.getY(1)) / 2f;
                    break;
                }
                float dx = ev.getX() - downX, dy = ev.getY() - downY;
                if (mode == NONE && ev.getPointerCount() == 1) {
                    int dir = classify(dx, dy);
                    if (dir == HORIZ) {
                        mode = HORIZ;
                        lastX = ev.getX();
                    } else if (dir == VERT) {
                        mode = VERT;
                        lastY = ev.getY();
                    }
                }
                if (mode == HORIZ) {
                    float d = lastX - ev.getX();
                    lastX = ev.getX();
                    int max = Math.max(0, (getChildCount() - 1) * getWidth());
                    int nx = getScrollX() + (int) d;
                    if (nx < 0 || nx > max) nx = getScrollX() + (int) (d * 0.3f);
                    scrollTo(nx, 0);
                }
                break;
            }
            case MotionEvent.ACTION_UP: {
                if (mode == HORIZ) {
                    vt.computeCurrentVelocity(1000, maxFling);
                    float v = vt.getXVelocity();
                    float pos = getScrollX() / (float) Math.max(1, getWidth());
                    int target;
                    if (v < -minFling) target = (int) Math.floor(pos) + 1;
                    else if (v > minFling) target = (int) Math.ceil(pos) - 1;
                    else target = Math.round(pos);
                    int n = getChildCount();
                    if (infinite && n > 1 && target >= n && pos > n - 1.08f) target = cb.isGlancePage(0) ? 1 : 0;
                    else if (infinite && n > 1 && target < 0 && pos < 0.08f) target = n - 1;
                    snapToPage(target);
                } else if (mode == VERT) {
                    vt.computeCurrentVelocity(1000, maxFling);
                    float vy = vt.getYVelocity();
                    float dy = ev.getY() - downY;
                    if (dy < -swipeDist || vy < -minFling) cb.onSwipeUp(downX, downY);
                    else if (dy > swipeDist || vy > minFling) cb.onSwipeDown();
                }
                resetTracking();
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                if (mode == HORIZ) snapToPage(current);
                resetTracking();
                break;
        }
        return true;
    }
}
