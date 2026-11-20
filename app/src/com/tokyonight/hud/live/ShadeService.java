package com.tokyonight.hud.live;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;

/**
 * Optional pull-down over any app. An accessibility service is the one kind of ordinary app allowed to draw over
 * the status bar (TYPE_ACCESSIBILITY_OVERLAY); it puts a thin, invisible strip over part of the status bar and,
 * on a tap there, shows the HUD panel (Shade) as a full-screen overlay. A swipe down still opens Android's own
 * shade: One UI's status bar watches that gesture itself and takes it over (the strip only sees a cancel), so the
 * tap is the trigger; a downward drag counts too where the system leaves it alone (e.g. with its shade open). The service reads no screen content and reacts to no accessibility events.
 *
 * Prefs: shOn (strip on), shEdge (0 left half, 1 full width, 2 right half), shShow (tint the strip to see where it is).
 *
 * Since Android 11 an overlay window is laid out below the status bar unless it opts out of fitting the system
 * bars, and the camera cutout is excluded unless asked for; coverBars() does both, or the strip would sit just under
 * the status bar while Android's own shade kept the real top edge.
 */
public class ShadeService extends AccessibilityService {
    static final int TYPE_OVERLAY = 2032;                         // WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
    static ShadeService instance;

    WindowManager wm;
    SharedPreferences sp;
    Typeface mono;
    View strip;
    FrameLayout win;
    Shade shade;
    float dp;

    final Link.Listener linkL = new Link.Listener() { public void onLink() { if (shade != null) shade.fill(); } };
    final SharedPreferences.OnSharedPreferenceChangeListener prefL = new SharedPreferences.OnSharedPreferenceChangeListener() {
        public void onSharedPreferenceChanged(SharedPreferences s, String k) { if ("shOn".equals(k) || "shEdge".equals(k) || "shShow".equals(k)) addStrip(); }
    };

    @Override protected void onServiceConnected() {
        instance = this;
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        sp = getSharedPreferences("hud", Context.MODE_PRIVATE);
        dp = getResources().getDisplayMetrics().density;
        try { mono = Typeface.createFromAsset(getAssets(), "ShareTechMono-Regular.ttf"); } catch (Exception e) { mono = Typeface.MONOSPACE; }
        sp.registerOnSharedPreferenceChangeListener(prefL);
        Link.listen(linkL, true);
        addStrip();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) { }
    @Override public void onInterrupt() { }

    @Override public void onDestroy() {
        instance = null;
        Link.listen(linkL, false);
        try { sp.unregisterOnSharedPreferenceChangeListener(prefL); } catch (Throwable ignored) { }
        removeStrip();
        if (win != null) try { wm.removeView(win); } catch (Throwable ignored) { }
        win = null; shade = null;
        super.onDestroy();
    }

    int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : Math.round(24 * dp);
    }

    void removeStrip() { if (strip != null) try { wm.removeView(strip); } catch (Throwable ignored) { } strip = null; }

    void addStrip() {
        removeStrip();
        if (!sp.getBoolean("shOn", true)) return;
        int edge = sp.getInt("shEdge", 0);
        strip = new View(this);
        if (sp.getBoolean("shShow", false)) strip.setBackgroundColor(0x667DCFFF);
        strip.setOnTouchListener(new View.OnTouchListener() {
            float x0, y0; long t0; boolean fired;
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: x0 = e.getRawX(); y0 = e.getRawY(); t0 = e.getEventTime(); fired = false; break;
                    case MotionEvent.ACTION_MOVE:
                        if (!fired && e.getRawY() - y0 > 28 * dp) { fired = true; open(); }
                        break;
                    case MotionEvent.ACTION_UP:                       // a tap (the system never cancelled it)
                        if (!fired && Math.hypot(e.getRawX() - x0, e.getRawY() - y0) < 24 * dp && e.getEventTime() - t0 < 500) {
                            fired = true; v.performHapticFeedback(1); open();
                        }
                        break;
                    default: break;                                   // ACTION_CANCEL: Android's shade took the swipe
                }
                return true;
            }
        });
        int w = getResources().getDisplayMetrics().widthPixels;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(edge == 1 ? ViewGroup.LayoutParams.MATCH_PARENT : w / 2,
                statusBarHeight(), TYPE_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | (edge == 2 ? Gravity.END : Gravity.START);
        coverBars(lp);
        try { wm.addView(strip, lp); } catch (Throwable t) { strip = null; }
    }

    /** lay the window out over the status bar and the camera cutout (API 28 / 30 fields, reached by reflection) */
    static void coverBars(WindowManager.LayoutParams lp) {
        try { WindowManager.LayoutParams.class.getMethod("setFitInsetsTypes", int.class).invoke(lp, 0); } catch (Throwable ignored) { }
        try { WindowManager.LayoutParams.class.getMethod("setFitInsetsIgnoringVisibility", boolean.class).invoke(lp, true); } catch (Throwable ignored) { }
        try {
            int mode = android.os.Build.VERSION.SDK_INT >= 30 ? 3 : 1;   // LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS / SHORT_EDGES
            WindowManager.LayoutParams.class.getField("layoutInDisplayCutoutMode").setInt(lp, mode);
        } catch (Throwable ignored) { }
    }

    boolean isOpen() { return win != null; }

    void open() {
        if (win != null) return;
        win = new FrameLayout(this) {
            @Override public boolean dispatchKeyEvent(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) { if (e.getAction() == KeyEvent.ACTION_UP) close(); return true; }
                return super.dispatchKeyEvent(e);
            }
        };
        Context themed = new android.view.ContextThemeWrapper(this, android.R.style.Theme_Material);
        shade = new Shade(themed, new Shade.Host() { public void closeShade() { close(); } }, mono, true);
        win.addView(shade.view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, TYPE_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT);
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        coverBars(lp);
        if (Glass.blurAvailable(this)) {
            try {
                lp.flags |= 0x4;                                      // FLAG_BLUR_BEHIND: frost the app underneath
                WindowManager.LayoutParams.class.getMethod("setBlurBehindRadius", int.class).invoke(lp, Math.round(32 * dp));
            } catch (Throwable ignored) { }
        }
        try { wm.addView(win, lp); } catch (Throwable t) { win = null; shade = null; return; }
        if (Link.installed(this)) Link.send(this, "refresh", null, 0);
        shade.fill();
        shade.view.setAlpha(0f); shade.view.setTranslationY(-80 * dp);
        shade.view.animate().alpha(1f).translationY(0).setDuration(160).start();
    }

    void close() {
        final FrameLayout w = win;
        if (w == null) return;
        win = null;
        if (shade != null) shade.hideKeyboard(shade.view);
        final Shade s = shade;
        shade = null;
        s.view.animate().alpha(0f).translationY(-60 * dp).setDuration(120).withEndAction(new Runnable() {
            public void run() { try { wm.removeView(w); } catch (Throwable ignored) { } }
        }).start();
    }
}
