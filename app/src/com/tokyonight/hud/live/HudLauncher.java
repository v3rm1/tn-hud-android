package com.tokyonight.hud.live;

import android.app.Activity;
import android.app.PendingIntent;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.os.UserManager;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * TN-HUD as a home app. The HUD itself is still drawn by the live wallpaper (one renderer, one set of data jobs);
 * this activity is a transparent shell over it that owns the touches:
 *   tap            -> forwarded to the wallpaper ("tnhud.tap") so HUD targets work exactly as before
 *   swipe left/right -> previous / next HUD tab ("tnhud.page")
 *   swipe up       -> app drawer (text list, icon list or icon grid), search field focused, keyboard up
 *   swipe down     -> notifications (left part of the screen) / quick settings (right part)
 *   long press     -> launcher menu
 * Swipe up / down, two-finger swipe down, double tap and the home button (while home) are remappable (Gestures).
 * Because the wallpaper is told a launcher is in front ("tnhud.launcher"), it ignores its own raw touches.
 */
public class HudLauncher extends Activity {
    static final int FG = 0xFFC0CAF5, COMMENT = 0xFF737AA2, BLUE = 0xFF7AA2F7, CYAN = 0xFF7DCFFF, LINE = 0xFF3B4261, BG = 0xF5000000,
            ACCENT = 0xFFBB9AF7, GREEN = 0xFF9ECE6A, YELLOW = 0xFFE0AF68, RED = 0xFFF7768E, INK = 0xFF05070D, TEXT2 = 0xFFA9B1D6;

    SharedPreferences sp;
    LauncherApps la;
    Typeface mono;
    float dp;
    final Handler h = new Handler(Looper.getMainLooper());

    FrameLayout root;
    View touch;
    LinearLayout drawer;
    EditText search;
    ListView list;
    LinearLayout rail;
    TextView count;
    final AppAdapter adapter = new AppAdapter();

    static final class App {
        String label, sortKey, key; ComponentName cn; UserHandle user; boolean work; int launches; LauncherActivityInfo info;
        float score; long last;                                   // frecency: launches decayed with a 14-day time constant
        volatile int elColor;                                     // element-icon category colour, 0 = not known yet
        float now(long t) { return last == 0 ? 0 : score * (float) Math.exp(-(t - last) / (14 * 86400000.0)); }
    }
    final ArrayList<App> apps = new ArrayList<App>();
    final ArrayList<Object> rows = new ArrayList<Object>();       // String = section header, App = row
    static final Object SHOW_HIDDEN = new Object();
    boolean showHidden, resumed;

    // ------------------------------------------------------------------ lifecycle
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences("hud", Context.MODE_PRIVATE);
        la = (LauncherApps) getSystemService(Context.LAUNCHER_APPS_SERVICE);
        dp = getResources().getDisplayMetrics().density;
        try { mono = Typeface.createFromAsset(getAssets(), "ShareTechMono-Regular.ttf"); } catch (Exception e) { mono = Typeface.MONOSPACE; }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER | WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        root = new FrameLayout(this);
        root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        touch = new GestureLayer(this);
        root.addView(touch, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        buildDrawer();
        setContentView(root);
        la.registerCallback(appsChanged, h);
        loadApps();
        host = new android.appwidget.AppWidgetHost(this, 4711);
        awm = android.appwidget.AppWidgetManager.getInstance(this);
        handleOpen(getIntent());
    }

    final Link.Listener linkL = new Link.Listener() { public void onLink() {
        if (drawer.getVisibility() == View.VISIBLE) adapter.notifyDataSetChanged();
        if (panelOpen()) shade.fill();
    } };

    @Override protected void onStart() {
        super.onStart();
        Link.listen(linkL, true);
        if (Link.installed(this)) Link.send(this, "refresh", null, 0);
        try { host.startListening(); } catch (Throwable ignored) { }
        HudBus.listen(new HudBus.WidgetListener() { public void onWidgetRect(int[] r) { placeWidget(r); } });
    }

    @Override protected void onStop() {
        Link.listen(linkL, false);
        HudBus.listen(null);
        try { host.stopListening(); } catch (Throwable ignored) { }
        super.onStop();
    }

    // ------------------------------------------------------------------ HUD notification panel (Shade; needs TN-HUD Link)
    Shade shade;

    boolean panelOpen() { return shade != null && shade.view.getVisibility() == View.VISIBLE; }


    /** frosts the HUD wallpaper behind the launcher window while the panel is open (Android 12+, blur enabled) */
    void windowBlur(boolean on) {
        if (!Glass.blurAvailable(this) || Build.VERSION.SDK_INT < 31) return;
        try {
            android.view.Window w = getWindow();
            if (on) w.addFlags(0x4 /* FLAG_BLUR_BEHIND */); else w.clearFlags(0x4);
            WindowManager.LayoutParams a = w.getAttributes();
            WindowManager.LayoutParams.class.getMethod("setBlurBehindRadius", int.class).invoke(a, on ? px(32) : 0);
            w.setAttributes(a);
        } catch (Throwable ignored) { }
    }

    void openPanel() {
        if (shade == null) {
            shade = new Shade(this, new Shade.Host() { public void closeShade() { closePanel(); } }, mono, false);
            shade.view.setVisibility(View.GONE);
            root.addView(shade.view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        if (Link.installed(this)) Link.send(this, "refresh", null, 0);
        shade.fill();
        View panel = shade.view;
        panel.setAlpha(0f); panel.setTranslationY(-80 * dp);
        panel.setVisibility(View.VISIBLE);
        panel.animate().alpha(1f).translationY(0).setDuration(160).start();
        windowBlur(true);
        command("tnhud.drawer", 0, 0, 1);
        if (wView != null) wView.setVisibility(View.GONE);
    }

    void closePanel() {
        if (!panelOpen()) return;
        final View panel = shade.view;
        shade.hideKeyboard(panel);
        panel.animate().alpha(0f).translationY(-60 * dp).setDuration(120).withEndAction(new Runnable() {
            public void run() { panel.setVisibility(View.GONE); placeWidget(lastRect); }
        }).start();
        windowBlur(false);
        command("tnhud.drawer", 0, 0, 0);
    }

    // ------------------------------------------------------------------ hosted widget (stage 3)
    android.appwidget.AppWidgetHost host;
    android.appwidget.AppWidgetManager awm;
    android.appwidget.AppWidgetHostView wView;
    int pendingId = -1;
    static final int RC_BIND = 41, RC_CONFIGURE = 42;

    /** move / show / hide the widget over the HUD's widget frame (rect in screen pixels, null = slot not on screen) */
    int[] lastRect;

    void placeWidget(int[] r) {
        lastRect = r;
        int id = sp.getInt("wgId", -1);
        if (r == null || id < 0 || drawer.getVisibility() == View.VISIBLE) { if (wView != null) wView.setVisibility(View.GONE); return; }
        android.appwidget.AppWidgetProviderInfo info = awm.getAppWidgetInfo(id);
        if (info == null) { sp.edit().remove("wgId").remove("wgLabel").apply(); return; }
        if (wView == null || wView.getAppWidgetId() != id) {
            if (wView != null) root.removeView(wView);
            wView = host.createView(getApplicationContext(), id, info);
            wView.setAppWidget(id, info);
            root.addView(wView, 1);                               // above the gesture layer, below the drawer
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(r[2] - r[0], r[3] - r[1]);
        lp.leftMargin = r[0]; lp.topMargin = r[1];
        wView.setLayoutParams(lp);
        wView.setVisibility(View.VISIBLE);
        int wd = Math.round((r[2] - r[0]) / dp), hd = Math.round((r[3] - r[1]) / dp);
        try { wView.updateAppWidgetSize(null, wd, hd, wd, hd); } catch (Throwable ignored) { }
    }

    void pickWidget() {
        final List<android.appwidget.AppWidgetProviderInfo> L = awm.getInstalledProviders();
        final android.content.pm.PackageManager pm = getPackageManager();
        Collections.sort(L, new Comparator<android.appwidget.AppWidgetProviderInfo>() {
            public int compare(android.appwidget.AppWidgetProviderInfo a, android.appwidget.AppWidgetProviderInfo b) {
                return wLabel(a, pm).compareToIgnoreCase(wLabel(b, pm));
            }
        });
        String[] names = new String[L.size()];
        for (int i = 0; i < names.length; i++) names[i] = wLabel(L.get(i), pm);
        glass().list("Widget for the HUD slot", names, -1, new Glass.Pick() {
            public void on(int w) { bindWidget(L.get(w)); }
        }).show();
    }

    String wLabel(android.appwidget.AppWidgetProviderInfo i, android.content.pm.PackageManager pm) {
        String app;
        try { app = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(i.provider.getPackageName(), 0))); } catch (Exception e) { app = i.provider.getPackageName(); }
        String w = String.valueOf(i.loadLabel(pm));
        return app.equals(w) ? app : app + " \u00B7 " + w;
    }

    void bindWidget(android.appwidget.AppWidgetProviderInfo info) {
        removeWidget();
        pendingId = host.allocateAppWidgetId();
        sp.edit().putString("wgLabelPending", wLabel(info, getPackageManager()).toUpperCase(Locale.ROOT)).apply();
        if (awm.bindAppWidgetIdIfAllowed(pendingId, info.provider)) configureWidget(info);
        else {
            Intent i = new Intent(android.appwidget.AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, pendingId)
                    .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider);
            try { startActivityForResult(i, RC_BIND); } catch (Exception e) { host.deleteAppWidgetId(pendingId); pendingId = -1; }
        }
    }

    void configureWidget(android.appwidget.AppWidgetProviderInfo info) {
        if (info.configure != null) {
            try { host.startAppWidgetConfigureActivityForResult(this, pendingId, 0, RC_CONFIGURE, null); return; }
            catch (Throwable t) { /* no configure screen reachable: use as is */ }
        }
        commitWidget();
    }

    void commitWidget() {
        sp.edit().putInt("wgId", pendingId).putString("wgLabel", sp.getString("wgLabelPending", "WIDGET")).apply();
        pendingId = -1;
        command("tnhud.launcher", 0, 0, 1);                       // the wallpaper re-reads prefs and redraws the slot
    }

    void removeWidget() {
        int id = sp.getInt("wgId", -1);
        if (id >= 0) { try { host.deleteAppWidgetId(id); } catch (Throwable ignored) { } }
        sp.edit().remove("wgId").remove("wgLabel").apply();
        if (wView != null) { root.removeView(wView); wView = null; }
        command("tnhud.launcher", 0, 0, 1);
    }

    @Override protected void onActivityResult(int rc, int res, Intent data) {
        super.onActivityResult(rc, res, data);
        if (pendingId < 0) return;
        if (res != RESULT_OK) { host.deleteAppWidgetId(pendingId); pendingId = -1; return; }
        if (rc == RC_BIND) configureWidget(awm.getAppWidgetInfo(pendingId));
        else if (rc == RC_CONFIGURE) commitWidget();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        command("tnhud.launcher", 0, 0, 1);
        h.postDelayed(new Runnable() { public void run() { command("tnhud.launcher", 0, 0, 1); } }, 400);   // window token may not exist yet
    }

    @Override protected void onPause() {
        resumed = false;
        command("tnhud.launcher", 0, 0, 0);
        super.onPause();
    }

    @Override protected void onNewIntent(Intent i) {          // home pressed while we are in front
        super.onNewIntent(i);
        if (handleOpen(i)) return;
        if (panelOpen()) closePanel();
        else if (drawer.getVisibility() == View.VISIBLE) closeDrawer();
        else if (resumed && hasWindowFocus() && (i.getFlags() & Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT) == 0) run(Gestures.HOME_PRESS);   // already home
    }

    /** quick settings tiles and the shade start the launcher with "tnhud.open": panel / popup */
    boolean handleOpen(Intent i) {
        final String what = i == null ? null : i.getStringExtra("tnhud.open");
        if (what == null) return false;
        i.removeExtra("tnhud.open");
        h.postDelayed(new Runnable() { public void run() {
            if ("panel".equals(what)) { if (drawer.getVisibility() == View.VISIBLE) closeDrawer(); openPanel(); }
            else if ("popup".equals(what)) new Rofi(HudLauncher.this, "").show();
        } }, 200);
        return true;
    }

    @Override public void onBackPressed() {
        if (panelOpen()) closePanel();
        else if (drawer.getVisibility() == View.VISIBLE) closeDrawer();
    }

    @Override protected void onDestroy() {
        try { la.unregisterCallback(appsChanged); } catch (Throwable ignored) { }
        iconExec.shutdownNow();
        super.onDestroy();
    }

    void command(String action, int x, int y, int z) {
        try {
            View d = getWindow().getDecorView();
            if (d.getWindowToken() != null) WallpaperManager.getInstance(this).sendWallpaperCommand(d.getWindowToken(), action, x, y, z, null);
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ gestures on the HUD
    final class GestureLayer extends View {
        float x0, y0; long t0; boolean longFired, two;
        float lastTapX, lastTapY; long lastTapT;
        final ArrayList<float[]> stroke = new ArrayList<float[]>();
        final android.graphics.Path trail = new android.graphics.Path();
        final android.graphics.Paint tp = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        float trailA; String label; int labelCol;
        final Runnable fade = new Runnable() { public void run() {
            trailA -= 0.08f;
            if (trailA > 0) { invalidate(); postDelayed(this, 16); } else { trailA = 0; label = null; trail.reset(); invalidate(); }
        } };

        @Override protected void onDraw(android.graphics.Canvas c) {
            if (trailA <= 0 || !sp.getBoolean("lnTrail", true)) return;
            tp.setStyle(android.graphics.Paint.Style.STROKE); tp.setStrokeCap(android.graphics.Paint.Cap.ROUND); tp.setStrokeJoin(android.graphics.Paint.Join.ROUND);
            tp.setColor(CYAN); tp.setAlpha((int) (60 * trailA)); tp.setStrokeWidth(14 * dp); c.drawPath(trail, tp);
            tp.setAlpha((int) (230 * trailA)); tp.setStrokeWidth(3 * dp); c.drawPath(trail, tp);
            if (label != null) {
                tp.setStyle(android.graphics.Paint.Style.FILL); tp.setTypeface(mono); tp.setTextSize(22 * dp); tp.setTextAlign(android.graphics.Paint.Align.CENTER);
                tp.setColor(labelCol); tp.setAlpha((int) (255 * Math.min(1, trailA * 1.5f)));
                c.drawText(label, getWidth() / 2f, getHeight() * 0.62f, tp);
            }
        }

        /** a stroke that bends (chord well short of its length) is a glyph attempt, a straight one a swipe */
        boolean glyphLike() {
            if (stroke.size() < 6 || !sp.getBoolean("lnGlyphs", true)) return false;
            float L = Glyph.pathLen(stroke);
            float[] a = stroke.get(0), b = stroke.get(stroke.size() - 1);
            return L > 110 * dp && Math.hypot(b[0] - a[0], b[1] - a[1]) < 0.8f * L;
        }
        final int slop = ViewConfiguration.get(HudLauncher.this).getScaledTouchSlop();
        final Runnable longPress = new Runnable() { public void run() { longFired = true; performHapticFeedback(0); launcherMenu(); } };

        GestureLayer(Context c) { super(c); }

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    x0 = e.getRawX(); y0 = e.getRawY(); t0 = e.getEventTime(); longFired = false; two = false;
                    removeCallbacks(fade); stroke.clear(); stroke.add(new float[]{e.getX(), e.getY()});
                    trail.reset(); trail.moveTo(e.getX(), e.getY()); label = null; trailA = 0;
                    postDelayed(longPress, ViewConfiguration.getLongPressTimeout() + 150);
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                    two = true; removeCallbacks(longPress);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (Math.hypot(e.getRawX() - x0, e.getRawY() - y0) > slop) removeCallbacks(longPress);
                    if (!two) {
                        for (int k = 0; k < e.getHistorySize(); k++) { stroke.add(new float[]{e.getHistoricalX(k), e.getHistoricalY(k)}); trail.lineTo(e.getHistoricalX(k), e.getHistoricalY(k)); }
                        stroke.add(new float[]{e.getX(), e.getY()}); trail.lineTo(e.getX(), e.getY());
                        if (Math.hypot(e.getRawX() - x0, e.getRawY() - y0) > slop) { trailA = 1; invalidate(); }
                    }
                    return true;
                case MotionEvent.ACTION_UP: {
                    removeCallbacks(longPress);
                    if (longFired) return true;
                    float dx = e.getRawX() - x0, dy = e.getRawY() - y0, ad = 48 * dp;
                    if (trailA > 0) postDelayed(fade, 260);
                    if (!two && glyphLike()) {
                        Object[] r = Glyph.recognise(sp, stroke);
                        String g = (String) r[0];
                        int act = g == null ? Gestures.NONE : Gestures.glyphAction(sp, g);
                        if (act != Gestures.NONE) {
                            label = g + "  \u2192  " + actionName(act, Gestures.glyphApp(sp, g)); labelCol = CYAN;
                            runAction(act, Gestures.glyphApp(sp, g));
                        } else { label = g == null ? "?  NOT RECOGNISED" : g + "  \u00B7  NOT ASSIGNED"; labelCol = COMMENT; }
                        trailA = 1; invalidate();
                        return true;
                    }
                    if (two) {
                        if (dy > ad && Math.abs(dy) > 1.4f * Math.abs(dx)) run(Gestures.TWO_DOWN);
                    } else if (Math.hypot(dx, dy) < slop * 1.5f && e.getEventTime() - t0 < 400) {
                        boolean dbl = Gestures.get(sp, Gestures.DOUBLE_TAP) != Gestures.NONE && e.getEventTime() - lastTapT < 320
                                && Math.hypot(x0 - lastTapX, y0 - lastTapY) < 48 * dp;
                        if (dbl) { lastTapT = 0; run(Gestures.DOUBLE_TAP); }
                        else { lastTapT = e.getEventTime(); lastTapX = x0; lastTapY = y0; command("tnhud.tap", (int) x0, (int) y0, 0); }
                    } else if (Math.abs(dy) > ad && Math.abs(dy) > 1.4f * Math.abs(dx)) {
                        if (dy < 0) run(Gestures.SWIPE_UP);
                        else run(x0 > getResources().getDisplayMetrics().widthPixels * 0.6f ? Gestures.DOWN_RIGHT : Gestures.DOWN_LEFT);
                    } else if (Math.abs(dx) > ad && Math.abs(dx) > 1.4f * Math.abs(dy)) {
                        command("tnhud.page", 0, 0, dx < 0 ? 1 : -1);
                    }
                    return true;
                }
                case MotionEvent.ACTION_CANCEL:
                    removeCallbacks(longPress);
                    return true;
            }
            return true;
        }
    }

    /** run the action mapped to a gesture */
    void run(String gesture) { runAction(Gestures.get(sp, gesture), Gestures.app(sp, gesture)); }

    String actionName(int a, String appKey) {
        if (a == Gestures.OPEN_APP) { for (App x : apps) if (x.key.equals(appKey)) return x.label.toUpperCase(Locale.ROOT); return "APP"; }
        return Gestures.ACTION_NAMES[a].toUpperCase(Locale.ROOT);
    }

    void runAction(int a, String appKey) {
        if (a != Gestures.NONE) touch.performHapticFeedback(1);
        switch (a) {
            case Gestures.DRAWER: openDrawer(); break;
            case Gestures.NOTIFICATIONS: statusBar(false); break;
            case Gestures.QUICK_SETTINGS: statusBar(true); break;
            case Gestures.TORCH: torch(); break;
            case Gestures.CAMERA:
                try { startActivity(new Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
                catch (Exception ignored) { }
                break;
            case Gestures.NEXT_TAB: command("tnhud.page", 0, 0, 1); break;
            case Gestures.HUD_SETTINGS: openSettings(); break;
            case Gestures.HUD_NOTIFICATIONS: openPanel(); break;
            case Gestures.COMMAND_LINE: new Rofi(this, "").show(); break;
            case Gestures.CALL_PROMPT: new Rofi(this, "> call ").show(); break;
            case Gestures.OPEN_APP: {
                for (App x : apps) if (x.key.equals(appKey)) { launch(x); return; }
                break;
            }
            default: break;
        }
    }

    /** the settings app runs in its own task (the launcher's task is the home task, which Android keeps separate) */
    void openSettings() {
        try { startActivity(new Intent(this, SetWallpaper.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)); }
        catch (Exception ignored) { }
        h.postDelayed(new Runnable() { public void run() { if (drawer.getVisibility() == View.VISIBLE) { drawer.setVisibility(View.GONE); hideKeyboard(); command("tnhud.drawer", 0, 0, 0); } } }, 300);
    }

    void statusBar(boolean qs) { Shade.statusBar(this, qs); }

    /** torch via CameraManager, state shared with the panel (Toggles) */
    void torch() { Toggles.torch(this); }

    View.OnClickListener panelIntent(final String action) {
        return new View.OnClickListener() { public void onClick(View v) {
            try { startActivity(new Intent(action)); } catch (Exception e) { statusBar(true); }
        } };
    }

    Glass glass() { return new Glass(this, true, mono); }

    void launcherMenu() {
        final boolean hasW = sp.getInt("wgId", -1) >= 0;
        final String[] items = hasW ? new String[]{"HUD settings", "Choose widget\u2026", "Remove widget", "Home app", "Android settings"}
                : new String[]{"HUD settings", "Choose widget\u2026", "Home app", "Android settings"};
        glass().list(null, items, -1, new Glass.Pick() {
            public void on(int w) {
                String it = items[w];
                try {
                    if (it.startsWith("HUD")) openSettings();
                    else if (it.startsWith("Choose")) pickWidget();
                    else if (it.startsWith("Remove")) removeWidget();
                    else if (it.startsWith("Home")) startActivity(new Intent(android.provider.Settings.ACTION_HOME_SETTINGS));
                    else startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS));
                } catch (Exception ignored) { }
            }
        }).show();
    }

    // ------------------------------------------------------------------ drawer
    int px(float v) { return Math.round(v * dp); }

    TextView tv(String s, float sp_, int col) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextColor(col); t.setTypeface(mono);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp_);
        return t;
    }

    void buildDrawer() {
        drawer = new LinearLayout(this);
        drawer.setOrientation(LinearLayout.VERTICAL);
        drawer.setBackgroundColor(BG);
        drawer.setVisibility(View.GONE);
        drawer.setFitsSystemWindows(true);
        drawer.setPadding(px(20), px(12), px(8), 0);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        prompt = tv("", 13, COMMENT);
        prompt.setSingleLine(true); prompt.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(prompt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView set = tv("\u2699", 20, COMMENT);
        set.setGravity(Gravity.CENTER);
        set.setContentDescription("HUD settings");
        set.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { openSettings(); } });
        top.addView(set, new LinearLayout.LayoutParams(px(48), px(44)));
        drawer.addView(top);

        // fzf-style query line: ❯ prompt, the field, match count
        LinearLayout sbox = new LinearLayout(this);
        sbox.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bd = new GradientDrawable();
        bd.setColor(INK); bd.setStroke(px(1.5f), ACCENT); bd.setCornerRadius(0);
        sbox.setBackground(bd);
        sbox.setPadding(px(12), 0, px(12), 0);
        sbox.addView(tv("\u276F", 18, GREEN));
        search = new EditText(this);
        search.setTypeface(mono);
        search.setTextColor(FG);
        search.setHintTextColor(COMMENT);
        search.setHint("filter \u00B7 > command");
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_FILTER);
        search.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        search.setBackground(null);
        search.setPadding(px(10), px(12), px(10), px(12));
        sbox.addView(search, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        count = tv("", 12, COMMENT);
        sbox.addView(count);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(52));
        slp.topMargin = px(6); slp.rightMargin = px(12);
        drawer.addView(sbox, slp);

        // shortcuts of the top match (fzf preview), then key hints / category chips
        scStrip = new LinearLayout(this);
        scStrip.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable sb = new GradientDrawable(); sb.setColor(0); sb.setStroke(px(1), LINE);
        scStrip.setBackground(sb);
        scStrip.setPadding(px(10), px(6), px(10), px(6));
        scStrip.setVisibility(View.GONE);
        LinearLayout.LayoutParams sclp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sclp.topMargin = px(8); sclp.rightMargin = px(12);
        drawer.addView(scStrip, sclp);
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        chips = new LinearLayout(this);
        chips.setGravity(Gravity.CENTER_VERTICAL);
        hs.addView(chips);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = px(8); clp.bottomMargin = px(4); clp.rightMargin = px(12);
        drawer.addView(hs, clp);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable s) { filter(); }
        });
        search.setOnEditorActionListener(openTop);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        list = new DrawerList(this);
        list.setDivider(null);
        list.setSelector(new android.graphics.drawable.ColorDrawable(0x22_BB9AF7));
        list.setVerticalScrollBarEnabled(false);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setAdapter(adapter);
        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            public void onItemClick(android.widget.AdapterView<?> p, View v, int pos, long id) {
                Object o = rows.get(pos);
                if (o instanceof App) launch((App) o);
                else if (o instanceof Commands.Cmd) pickCommand((Commands.Cmd) o);
                else if (o == SHOW_HIDDEN) { showHidden = !showHidden; filter(); }
            }
        });
        list.setOnItemLongClickListener(new android.widget.AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(android.widget.AdapterView<?> p, View v, int pos, long id) {
                Object o = rows.get(pos);
                if (o instanceof App) appMenu((App) o);
                return true;
            }
        });
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            public void onScrollStateChanged(AbsListView v, int s) { if (s == SCROLL_STATE_TOUCH_SCROLL) hideKeyboard(); }
            public void onScroll(AbsListView v, int a, int b, int c) { }
        });
        body.addView(list, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        rail = new LinearLayout(this);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setGravity(Gravity.CENTER_HORIZONTAL);
        rail.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent e) {
                int n = rail.getChildCount();
                if (n == 0) return true;
                int i = Math.max(0, Math.min(n - 1, (int) (e.getY() / rail.getHeight() * n)));
                String letter = ((TextView) rail.getChildAt(i)).getText().toString();
                for (int k = 0; k < rows.size(); k++) if (letter.equals(rows.get(k))) { list.setSelectionFromTop(k, 0); break; }
                for (int k = 0; k < n; k++) ((TextView) rail.getChildAt(k)).setTextColor(k == i && e.getActionMasked() != MotionEvent.ACTION_UP ? ACCENT : COMMENT);
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) { hideKeyboard(); v.performHapticFeedback(1); }
                return true;
            }
        });
        body.addView(rail, new LinearLayout.LayoutParams(px(28), ViewGroup.LayoutParams.MATCH_PARENT));
        drawer.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(drawer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    boolean listAtTop() {
        return list.getChildCount() == 0 || (list.getFirstVisiblePosition() == 0 && list.getChildAt(0).getTop() >= list.getPaddingTop());
    }

    void openDrawer() {
        showHidden = false; catSel = 0;
        search.setText("");
        filter();
        checkIconSig();
        if (sp.getInt("lnStyle", 0) > 0) prefetchIcons();
        drawer.setAlpha(0f); drawer.setTranslationY(80 * dp);
        drawer.setVisibility(View.VISIBLE);
        drawer.animate().alpha(1f).translationY(0).setDuration(160).start();
        command("tnhud.drawer", 0, 0, 1);
        if (wView != null) wView.setVisibility(View.GONE);
        search.requestFocus();
        h.postDelayed(new Runnable() { public void run() {
            InputMethodManager im = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (im != null) im.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT);
        } }, 120);
    }

    void closeDrawer() {
        hideKeyboard();
        if (drawer.getVisibility() != View.VISIBLE) return;     // pop-up commands run with the drawer closed
        drawer.animate().alpha(0f).translationY(60 * dp).setDuration(120).withEndAction(new Runnable() {
            public void run() { drawer.setVisibility(View.GONE); placeWidget(lastRect); }
        }).start();
        command("tnhud.drawer", 0, 0, 0);
    }

    void hideKeyboard() {
        InputMethodManager im = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(search.getWindowToken(), 0);
    }

    // ------------------------------------------------------------------ apps
    final LauncherApps.Callback appsChanged = new LauncherApps.Callback() {
        public void onPackageRemoved(String p, UserHandle u) { dropDisk(p); loadApps(); }
        public void onPackageAdded(String p, UserHandle u) { dropDisk(p); loadApps(); }
        public void onPackageChanged(String p, UserHandle u) { dropDisk(p); loadApps(); }
        public void onPackagesAvailable(String[] p, UserHandle u, boolean r) { for (String x : p) dropDisk(x); loadApps(); }
        public void onPackagesUnavailable(String[] p, UserHandle u, boolean r) { loadApps(); }
    };

    void loadApps() {
        apps.clear();
        UserManager um = (UserManager) getSystemService(Context.USER_SERVICE);
        UserHandle me = android.os.Process.myUserHandle();
        List<UserHandle> users = um != null ? um.getUserProfiles() : Collections.singletonList(me);
        for (UserHandle u : users) {
            List<LauncherActivityInfo> L;
            try { L = la.getActivityList(null, u); } catch (Throwable t) { continue; }
            for (LauncherActivityInfo i : L) {
                App a = new App();
                a.cn = i.getComponentName(); a.user = u; a.work = !u.equals(me); a.info = i;
                a.key = a.cn.flattenToShortString() + "#" + (um != null ? um.getSerialNumberForUser(u) : 0);
                String custom = sp.getString("ln_" + a.key, null);
                a.label = custom != null ? custom : String.valueOf(i.getLabel());
                a.sortKey = a.label.toUpperCase(Locale.ROOT);
                a.launches = sp.getInt("lc_" + a.key, 0);
                a.last = sp.getLong("lt_" + a.key, 0);
                a.score = sp.getFloat("lf_" + a.key, a.launches);
                if (a.last == 0 && a.launches > 0) a.last = System.currentTimeMillis();
                a.elColor = sp.getInt("ec_" + a.key, 0);
                apps.add(a);
            }
        }
        final Collator col = Collator.getInstance();
        Collections.sort(apps, new Comparator<App>() { public int compare(App x, App y) { return col.compare(x.label, y.label); } });
        iconGen++; icons.evictAll(); iconPending.clear();          // package changes may come with new icons
        filter();
    }

    static String norm(String s) {
        return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    /**
     * match score: 4 prefix, 3 word prefix, 2 substring or initials ("gm" = Google Maps), 1 loose (letters in order,
     * or one typo in a word start, for queries of 3+ letters), 0 none
     */
    static int score(String label, String q) {
        if (q.isEmpty()) return 0;
        String l = norm(label);
        if (l.startsWith(q)) return 4;
        String[] words = l.split("[\\s\\-_.:]+");
        for (String w : words) if (w.startsWith(q)) return 3;
        if (l.contains(q)) return 2;
        if (q.length() >= 2 && words.length >= q.length()) {
            StringBuilder ini = new StringBuilder();
            for (String w : words) if (!w.isEmpty()) ini.append(w.charAt(0));
            if (ini.toString().startsWith(q)) return 2;
        }
        if (q.length() < 3) return 0;
        int k = 0;
        for (int i = 0; i < l.length() && k < q.length(); i++) if (l.charAt(i) == q.charAt(k)) k++;
        if (k == q.length() && l.length() <= q.length() * 3) return 1;
        if (q.length() >= 4) for (String w : words) if (w.length() >= q.length() - 1 && oneTypo(w.substring(0, Math.min(w.length(), q.length())), q)) return 1;
        return 0;
    }

    /** edit distance <= 1 (one substitution, insertion or deletion) */
    static boolean oneTypo(String a, String b) {
        if (Math.abs(a.length() - b.length()) > 1) return false;
        int i = 0, j = 0, d = 0;
        while (i < a.length() && j < b.length()) {
            if (a.charAt(i) == b.charAt(j)) { i++; j++; continue; }
            if (++d > 1) return false;
            if (a.length() > b.length()) i++; else if (b.length() > a.length()) j++; else { i++; j++; }
        }
        return d + (a.length() - i) + (b.length() - j) <= 1;
    }

    void filter() {
        rows.clear();
        String raw = search == null ? "" : search.getText().toString().trim();
        if (Commands.isCommandLine(raw)) { filterCommands(raw); return; }
        String q = norm(raw);
        lastQ = q;
        boolean showHidden = q.length() > 0 && sp.getBoolean("lnSearchHidden", true);
        int visible = 0;
        style = Math.max(0, Math.min(3, sp.getInt("lnStyle", 0)));
        boolean periodic = style >= 2 && sp.getBoolean("lnIconElement", false);
        if (!periodic) catSel = 0;
        if (style == ST_TEXT) rows.add(COLS);
        if (q.isEmpty() && catSel != 0) {                          // element grid filtered by a category chip
            ArrayList<App> m = new ArrayList<App>();
            final long now = System.currentTimeMillis();
            for (App a : apps) if (!hidden(a) && (catSel == -1 ? a.now(now) > 0.05f : a.elColor == catSel)) m.add(a);
            if (catSel == -1) Collections.sort(m, new Comparator<App>() { public int compare(App x, App y) { return Float.compare(y.now(now), x.now(now)); } });
            rows.addAll(m);
            visible = m.size();
            if (m.isEmpty()) rows.add("NOTHING IN THIS GROUP YET");
        } else if (q.isEmpty()) {
            if (sp.getBoolean("lnFrequent", true)) {
                ArrayList<App> fr = new ArrayList<App>();
                final long now = System.currentTimeMillis();
                for (App a : apps) if (a.now(now) > 0.05f && !hidden(a)) fr.add(a);
                Collections.sort(fr, new Comparator<App>() { public int compare(App x, App y) { return Float.compare(y.now(now), x.now(now)); } });
                if (!fr.isEmpty()) { rows.add("\u2605 FREQ"); rows.addAll(fr.subList(0, Math.min(5, fr.size()))); }
            }
            String cur = null;
            int nHidden = 0;
            for (App a : apps) if (hidden(a)) nHidden++;
            for (App a : apps) {
                if (hidden(a) && !showHidden) continue;
                visible++;
                String L = a.sortKey.isEmpty() ? "#" : a.sortKey.substring(0, 1);
                if (!Character.isLetter(L.charAt(0))) L = "#";
                if (!L.equals(cur)) { rows.add(L); cur = L; }
                rows.add(a);
            }
            if (nHidden > 0) rows.add(SHOW_HIDDEN);
            hiddenCount = nHidden;
        } else {
            ArrayList<App> m = new ArrayList<App>();
            final ArrayList<Integer> sc = new ArrayList<Integer>();
            for (App a : apps) {
                if (hidden(a) && !showHidden) continue;
                int s = score(a.label, q);
                if (s > 0) { m.add(a); sc.add(s * 100000 + Math.min(99999, Math.round(a.now(System.currentTimeMillis()) * 100))); }
            }
            Integer[] idx = new Integer[m.size()];
            for (int i = 0; i < idx.length; i++) idx[i] = i;
            java.util.Arrays.sort(idx, new Comparator<Integer>() { public int compare(Integer a, Integer b) { return sc.get(b) - sc.get(a); } });
            for (Integer i : idx) rows.add(m.get(i));
            visible = m.size();
            if (m.isEmpty()) rows.add("NO MATCH · ENTER SEARCHES THE WEB");
        }
        cols = Math.max(3, Math.min(6, sp.getInt("lnCols", 4)));
        if (style >= 2) {                                         // grid: runs of apps become rows of 'cols' cells
            ArrayList<Object> g = new ArrayList<Object>();
            ArrayList<App> run = new ArrayList<App>();
            for (Object o : rows) {
                if (o instanceof App) { run.add((App) o); if (run.size() == cols) { g.add(run.toArray(new App[0])); run.clear(); } continue; }
                if (!run.isEmpty()) { g.add(run.toArray(new App[0])); run.clear(); }
                g.add(o);
            }
            if (!run.isEmpty()) g.add(run.toArray(new App[0]));
            rows.clear(); rows.addAll(g);
        }
        int total = 0;
        for (App a : apps) if (!hidden(a)) total++;
        count.setText(visible + "/" + total);
        setPrompt(periodic ? "periodic --apps" : style == ST_TEXT ? "ls -lt | fzf" : "ls --icons | fzf");
        buildChips(periodic && q.isEmpty());
        showShortcuts(q.isEmpty() ? null : topApp());
        adapter.notifyDataSetChanged();
        list.setSelection(0);
        // A-Z rail (only without a query)
        rail.removeAllViews();
        if (q.isEmpty()) for (Object o : rows) if (o instanceof String && ((String) o).length() == 1) {
            TextView t = tv((String) o, 11, COMMENT);
            t.setGravity(Gravity.CENTER);
            rail.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        }
        boolean noMatch = !q.isEmpty() && visible == 0;
        search.setOnEditorActionListener(noMatch ? webSearch : openTop);
    }

    // ------------------------------------------------------------------ command line ('>' in the search field)
    void filterCommands(String raw) {
        java.util.ArrayList<Commands.Cmd> m = Commands.match(Commands.split(raw)[0]);
        rows.add(m.isEmpty() ? "UNKNOWN COMMAND · > HELP LISTS THEM" : "// COMMANDS");
        rows.addAll(m);
        count.setText(m.size() + " cmd");
        setPrompt("tnhud " + raw.substring(1).trim());
        buildChips(false); showShortcuts(null);
        adapter.notifyDataSetChanged();
        list.setSelection(0);
        rail.removeAllViews();
        search.setOnEditorActionListener(runTop);
    }

    final TextView.OnEditorActionListener runTop = new TextView.OnEditorActionListener() {
        public boolean onEditorAction(TextView v, int id, KeyEvent ev) {
            for (Object o : rows) if (o instanceof Commands.Cmd) { pickCommand((Commands.Cmd) o); break; }
            return true;
        }
    };

    /** a command that needs an argument and has none gets typed into the field instead of run */
    void pickCommand(Commands.Cmd c) {
        String arg = Commands.split(search.getText().toString().trim())[1];
        if (c.needsArgs() && arg.isEmpty()) {
            String t = "> " + c.name + " ";
            search.setText(t); search.setSelection(t.length());
            return;
        }
        runCommand(c, arg);
    }

    /** best label match for a command argument, ties broken by frecency */
    App findApp(String arg) {
        String q = norm(arg);
        if (q.isEmpty()) return null;
        App best = null; int bs = 0; float bf = -1; long now = System.currentTimeMillis();
        for (App a : apps) {
            int sc = score(a.label, q);
            if (sc > bs || (sc == bs && sc > 0 && a.now(now) > bf)) { best = a; bs = sc; bf = a.now(now); }
        }
        return bs > 0 ? best : null;
    }

    void toast(String s) { android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show(); }

    void runCommand(Commands.Cmd c, String arg) {
        String n = c.name;
        try {
            if (n.equals("help")) { search.setText(">"); search.setSelection(1); return; }
            if (n.equals("hide") || n.equals("unhide")) {
                App a = findApp(arg);
                if (a == null) { toast("No app matches \"" + arg + "\""); return; }
                sp.edit().putBoolean("lh_" + a.key, n.equals("hide")).apply();
                toast((n.equals("hide") ? "Hidden: " : "Shown: ") + a.label);
                search.setText("");
                return;
            }
            if (n.equals("settings")) { openSettings(); return; }
            Intent i = null;
            if (n.equals("torch")) torch();
            else if (n.equals("wifi")) panelIntent("android.settings.panel.action.WIFI").onClick(search);
            else if (n.equals("net")) panelIntent("android.settings.panel.action.INTERNET_CONNECTIVITY").onClick(search);
            else if (n.equals("bt")) panelIntent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS).onClick(search);
            else if (n.equals("vol")) panelIntent("android.settings.panel.action.VOLUME").onClick(search);
            else if (n.equals("qs")) statusBar(true);
            else if (n.equals("camera")) runAction(Gestures.CAMERA, null);
            else if (n.equals("tab")) command("tnhud.page", 0, 0, 1);
            else if (n.equals("android")) i = new Intent(android.provider.Settings.ACTION_SETTINGS);
            else if (n.equals("call") || n.equals("sms")) {
                if (isNumber(arg)) i = numberIntent(n, arg);
                else { contactCommand(c, arg); return; }
            }
            else if (Commands.mime(n) != null) { contactCommand(c, arg); return; }
            else if (n.equals("web")) i = new Intent(Intent.ACTION_WEB_SEARCH).putExtra(android.app.SearchManager.QUERY, arg);
            else if (n.equals("maps")) i = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(arg)));
            else if (n.equals("timer")) {
                int sec;
                try { sec = Math.round(Float.parseFloat(arg.replace(',', '.')) * 60); } catch (Exception e) { toast("Timer needs minutes, e.g. > timer 5"); return; }
                if (sec <= 0 || sec > 86400) { toast("Timer must be between 1 second and 24 hours"); return; }
                i = new Intent(android.provider.AlarmClock.ACTION_SET_TIMER).putExtra(android.provider.AlarmClock.EXTRA_LENGTH, sec)
                        .putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, false);
            } else if (n.equals("alarm")) {
                int[] t = Commands.parseTime(arg);
                if (t == null) { toast("Alarm needs a time, e.g. > alarm 7:30"); return; }
                i = new Intent(android.provider.AlarmClock.ACTION_SET_ALARM).putExtra(android.provider.AlarmClock.EXTRA_HOUR, t[0])
                        .putExtra(android.provider.AlarmClock.EXTRA_MINUTES, t[1]).putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, false);
            } else if (n.equals("info")) {
                App a = findApp(arg);
                if (a == null) { toast("No app matches \"" + arg + "\""); return; }
                la.startAppDetailsActivity(a.cn, a.user, null, null);
            }
            if (i != null) startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            closeDrawer();
            if (n.equals("notif")) openPanel();
        } catch (Exception e) { toast("Nothing on this phone handles \"" + n + "\""); }
    }

    // ---- call / sms by contact name (READ_CONTACTS, asked the first time a name is used)
    static final String P_CONTACTS = "android.permission.READ_CONTACTS";
    static final int RC_CONTACTS = 43, RC_TERMUX = 44;
    Commands.Cmd pendingCmd; String pendingArg;
    Rofi rofi;                                                   // open launcher pop-up, refreshed after a permission grant

    static boolean isNumber(String s) { return s.matches("[+\\d\\s()\\-#*.]+") && s.matches(".*\\d.*"); }

    static Intent numberIntent(String cmd, String number) {
        return cmd.equals("call") ? new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number)))
                : new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number)));
    }

    void contactCommand(Commands.Cmd c, String name) {
        if (checkSelfPermission(P_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingCmd = c; pendingArg = name;
            requestPermissions(new String[]{P_CONTACTS}, RC_CONTACTS);
            return;
        }
        final String cmd = c.name;
        final java.util.ArrayList<String[]> hits = contactHits(cmd, name);    // {display name, number or data id, label}
        if (hits.isEmpty()) { toast(Commands.mime(cmd) == null ? "No contact with a number matches \"" + name + "\""
                : "No " + c.help.split(" ")[0] + " contact matches \"" + name + "\" (is the app's contact sync on?)"); return; }
        if (hits.size() == 1) { contactAction(cmd, hits.get(0)[1]); return; }
        String[] items = new String[hits.size()];
        for (int k = 0; k < items.length; k++) items[k] = hits.get(k)[0] + "  \u00B7  " + hits.get(k)[2];
        glass().list(c.help + ": " + name, items, -1, new Glass.Pick() {
            public void on(int w) { contactAction(cmd, hits.get(w)[1]); }
        }).show();
    }

    boolean hasContacts() { return checkSelfPermission(P_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED; }

    java.util.ArrayList<String[]> contactHits(String cmd, String name) {
        String m = Commands.mime(cmd);
        return m == null ? contactNumbers(name) : messengerContacts(name, m);
    }

    void contactAction(String cmd, String target) {
        if (Commands.mime(cmd) == null) dialContact(cmd, target);
        else messengerCall(cmd, Long.parseLong(target));
    }

    /** contacts carrying a messenger's call entry: {display name, data row id, label} */
    java.util.ArrayList<String[]> messengerContacts(String name, String mime) {
        java.util.ArrayList<String[]> out = new java.util.ArrayList<String[]>();
        android.database.Cursor cu = null;
        try {
            cu = getContentResolver().query(android.provider.ContactsContract.Data.CONTENT_URI,
                    new String[]{android.provider.ContactsContract.Data._ID, android.provider.ContactsContract.Data.DISPLAY_NAME,
                            android.provider.ContactsContract.Data.DATA3},
                    android.provider.ContactsContract.Data.MIMETYPE + "=? AND " + android.provider.ContactsContract.Data.DISPLAY_NAME + " LIKE ?",
                    new String[]{mime, "%" + name.replace("%", "") + "%"},
                    android.provider.ContactsContract.Data.STARRED + " DESC, " + android.provider.ContactsContract.Data.DISPLAY_NAME + " ASC");
            while (cu != null && cu.moveToNext() && out.size() < 12) {
                String label = cu.getString(2);                   // e.g. "Voice call +47 …" as the messenger wrote it
                out.add(new String[]{cu.getString(1), String.valueOf(cu.getLong(0)), label == null ? "" : label});
            }
        } catch (Exception ignored) {
        } finally { if (cu != null) cu.close(); }
        return out;
    }

    void messengerCall(String cmd, long dataId) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(android.content.ContentUris.withAppendedId(android.provider.ContactsContract.Data.CONTENT_URI, dataId), Commands.mime(cmd));
            i.setPackage(Commands.pkg(cmd));
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            closeDrawer();
        } catch (Exception e) { toast("Couldn't start the call in " + (cmd.equals("tg") ? "Telegram" : "WhatsApp")); }
    }

    void dialContact(String cmd, String number) {
        try { startActivity(numberIntent(cmd, number).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); closeDrawer(); }
        catch (Exception e) { toast("Nothing on this phone handles \"" + cmd + "\""); }
    }

    /** phone numbers of contacts whose name matches, starred and most-contacted first, duplicates folded */
    java.util.ArrayList<String[]> contactNumbers(String name) {
        java.util.ArrayList<String[]> out = new java.util.ArrayList<String[]>();
        java.util.HashSet<String> seen = new java.util.HashSet<String>();
        android.database.Cursor cu = null;
        try {
            Uri u = Uri.withAppendedPath(android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(name));
            cu = getContentResolver().query(u, new String[]{
                    android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER,
                    android.provider.ContactsContract.CommonDataKinds.Phone.TYPE,
                    android.provider.ContactsContract.CommonDataKinds.Phone.LABEL},
                    null, null,
                    android.provider.ContactsContract.CommonDataKinds.Phone.STARRED + " DESC, "
                            + android.provider.ContactsContract.CommonDataKinds.Phone.TIMES_CONTACTED + " DESC, "
                            + android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC");
            while (cu != null && cu.moveToNext() && out.size() < 12) {
                String dn = cu.getString(0), num = cu.getString(1);
                if (num == null) continue;
                String key = (dn + "|" + num.replaceAll("[^+\\d]", "")).toLowerCase(Locale.ROOT);
                if (!seen.add(key)) continue;
                CharSequence type = android.provider.ContactsContract.CommonDataKinds.Phone.getTypeLabel(getResources(), cu.getInt(2), cu.getString(3));
                out.add(new String[]{dn == null ? num : dn, num, String.valueOf(type).toLowerCase(Locale.ROOT)});
            }
        } catch (Exception ignored) {
        } finally { if (cu != null) cu.close(); }
        return out;
    }

    @Override public void onRequestPermissionsResult(int rc, String[] p, int[] r) {
        if (rc == RC_TERMUX) { if (rofi != null) rofi.refresh(); return; }
        if (rc != RC_CONTACTS) { super.onRequestPermissionsResult(rc, p, r); return; }
        Commands.Cmd c = pendingCmd; String a = pendingArg;
        pendingCmd = null; pendingArg = null;
        if (rofi != null) rofi.refresh();
        if (c == null) return;
        if (r.length > 0 && r[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) contactCommand(c, a);
        else toast("Without contacts access, " + c.name + " takes a number");
    }

    CharSequence commandLabel(Commands.Cmd c, boolean top) {
        String head = "\u203A " + c.name.toUpperCase(Locale.ROOT), args = c.args == null ? "" : " " + c.args, tail = "   " + c.help;
        android.text.SpannableString ss = new android.text.SpannableString(head + args + tail);
        ss.setSpan(new android.text.style.ForegroundColorSpan(top ? ACCENT : FG), 0, head.length(), 0);
        ss.setSpan(new android.text.style.ForegroundColorSpan(BLUE), head.length(), head.length() + args.length(), 0);
        ss.setSpan(new android.text.style.ForegroundColorSpan(COMMENT), head.length() + args.length(), ss.length(), 0);
        ss.setSpan(new android.text.style.RelativeSizeSpan(0.72f), head.length() + args.length(), ss.length(), 0);
        return ss;
    }

    final TextView.OnEditorActionListener openTop = new TextView.OnEditorActionListener() {
        public boolean onEditorAction(TextView v, int id, KeyEvent ev) {
            App a = topApp();
            if (a != null) launch(a);
            return true;
        }
    };
    final TextView.OnEditorActionListener webSearch = new TextView.OnEditorActionListener() {
        public boolean onEditorAction(TextView v, int id, KeyEvent ev) {
            try {
                Intent i = new Intent(Intent.ACTION_WEB_SEARCH).putExtra(android.app.SearchManager.QUERY, search.getText().toString());
                startActivity(i);
                closeDrawer();
            } catch (Exception ignored) { }
            return true;
        }
    };

    boolean hidden(App a) { return sp.getBoolean("lh_" + a.key, false); }
    int hiddenCount;

    void launch(App a) {
        try {
            Rect r = new Rect();
            la.startMainActivity(a.cn, a.user, r, null);
            long now = System.currentTimeMillis();
            a.score = a.now(now) + 1; a.last = now; a.launches++;
            sp.edit().putInt("lc_" + a.key, a.launches).putFloat("lf_" + a.key, a.score).putLong("lt_" + a.key, now).apply();
            h.postDelayed(new Runnable() { public void run() { drawer.setVisibility(View.GONE); hideKeyboard(); command("tnhud.drawer", 0, 0, 0); } }, 250);
        } catch (Throwable t) {
            loadApps();
        }
    }

    /** every app's shortcuts for the pop-up search: {label, ShortcutInfo, app label}; reflection as below */
    List<Object[]> allShortcuts() {
        List<Object[]> out = new ArrayList<Object[]>();
        try {
            if (!(Boolean) LauncherApps.class.getMethod("hasShortcutHostPermission").invoke(la)) return out;
            Class<?> q = Class.forName("android.content.pm.LauncherApps$ShortcutQuery");
            Object query = q.newInstance();
            q.getMethod("setQueryFlags", int.class).invoke(query, 1 | 2 | 8);          // dynamic | pinned | manifest
            List<?> L = (List<?>) LauncherApps.class.getMethod("getShortcuts", q, UserHandle.class).invoke(la, query, android.os.Process.myUserHandle());
            if (L == null) return out;
            Class<?> si = Class.forName("android.content.pm.ShortcutInfo");
            java.util.HashMap<String, String> names = new java.util.HashMap<String, String>();
            for (App a : apps) if (!a.work) names.put(a.cn.getPackageName(), a.label);
            for (Object s : L) {
                if (!(Boolean) si.getMethod("isEnabled").invoke(s)) continue;
                CharSequence lb = (CharSequence) si.getMethod("getShortLabel").invoke(s);
                String pkg = (String) si.getMethod("getPackage").invoke(s);
                String an = names.get(pkg);
                if (lb == null || an == null) continue;
                out.add(new Object[]{lb.toString(), s, an});
            }
        } catch (Throwable ignored) { }
        return out;
    }

    /** app shortcuts (API 25+, default launcher only), reached by reflection: {label, ShortcutInfo} */
    List<Object[]> shortcuts(App a) {
        List<Object[]> out = new ArrayList<Object[]>();
        try {
            if (!(Boolean) LauncherApps.class.getMethod("hasShortcutHostPermission").invoke(la)) return out;
            Class<?> q = Class.forName("android.content.pm.LauncherApps$ShortcutQuery");
            Object query = q.newInstance();
            q.getMethod("setPackage", String.class).invoke(query, a.cn.getPackageName());
            q.getMethod("setActivity", ComponentName.class).invoke(query, a.cn);
            q.getMethod("setQueryFlags", int.class).invoke(query, 1 | 2 | 8);          // dynamic | pinned | manifest
            List<?> L = (List<?>) LauncherApps.class.getMethod("getShortcuts", q, UserHandle.class).invoke(la, query, a.user);
            if (L == null) return out;
            Class<?> si = Class.forName("android.content.pm.ShortcutInfo");
            for (Object s : L) {
                if (!(Boolean) si.getMethod("isEnabled").invoke(s)) continue;
                CharSequence lb = (CharSequence) si.getMethod("getShortLabel").invoke(s);
                out.add(new Object[]{lb == null ? "?" : lb.toString(), s});
                if (out.size() >= 5) break;
            }
        } catch (Throwable ignored) { }
        return out;
    }

    void startShortcut(Object info) {
        try {
            Class<?> si = Class.forName("android.content.pm.ShortcutInfo");
            LauncherApps.class.getMethod("startShortcut", si, Rect.class, Bundle.class).invoke(la, info, new Rect(), null);
            h.postDelayed(new Runnable() { public void run() { drawer.setVisibility(View.GONE); hideKeyboard(); command("tnhud.drawer", 0, 0, 0); } }, 250);
        } catch (Throwable ignored) { }
    }

    void appMenu(final App a) {
        final boolean hid = hidden(a);
        final List<Object[]> sc = shortcuts(a);
        final boolean orig = sp.getBoolean("lo_" + a.key, false);
        String[] base = {"App info", "Rename", hid ? "Unhide" : "Hide from list", "Reset launch count", "Uninstall",
                orig ? "Icon: use the drawer style" : "Icon: keep the original"};
        String[] items = new String[sc.size() + base.length];
        for (int i = 0; i < sc.size(); i++) items[i] = "\u203A " + sc.get(i)[0];
        System.arraycopy(base, 0, items, sc.size(), base.length);
        glass().list(a.label, items, -1, new Glass.Pick() {
            public void on(int w0) {
                if (w0 < sc.size()) { startShortcut(sc.get(w0)[1]); return; }
                int w = w0 - sc.size();
                if (w == 0) { try { la.startAppDetailsActivity(a.cn, a.user, null, null); } catch (Throwable ignored) { } }
                else if (w == 1) rename(a);
                else if (w == 2) { sp.edit().putBoolean("lh_" + a.key, !hid).apply(); filter(); }
                else if (w == 3) { sp.edit().remove("lc_" + a.key).remove("lf_" + a.key).remove("lt_" + a.key).apply(); a.launches = 0; a.score = 0; a.last = 0; filter(); }
                else if (w == 5) setOriginalIcon(a, !orig);
                else {
                    try {
                        Intent i = new Intent(Intent.ACTION_DELETE, Uri.fromParts("package", a.cn.getPackageName(), null));
                        i.putExtra("android.intent.extra.USER", a.user);
                        startActivity(i);
                    } catch (Exception ignored) { }
                }
            }
        }).show();
    }

    void rename(final App a) {
        Glass g = glass();
        final EditText e = g.field(a.label);
        e.setSelectAllOnFocus(true); e.setSingleLine(true);
        android.app.Dialog d = g.sheet("Rename", g.padded(e), new String[]{"Original", "Cancel", "Save"}, new Glass.Pick() {
            public void on(int w) {
                if (w == 1) return;
                String s = w == 0 ? "" : e.getText().toString().trim();
                if (s.isEmpty()) sp.edit().remove("ln_" + a.key).apply(); else sp.edit().putString("ln_" + a.key, s).apply();
                loadApps();
            }
        });
        if (d.getWindow() != null) d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        d.show();
    }

    // ------------------------------------------------------------------ drawer styles and icons
    static final int ST_TEXT = 0, ST_LIST = 1, ST_GRID = 2, ST_GRID_BARE = 3;
    int style, cols = 4;

    /** pull down at the top closes the drawer; done in dispatch so it also works when a grid cell took the DOWN */
    final class DrawerList extends ListView {
        float y0; boolean atTop;
        DrawerList(Context c) { super(c); }
        @Override public boolean dispatchTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) { y0 = e.getRawY(); atTop = listAtTop(); }
            else if (e.getActionMasked() == MotionEvent.ACTION_UP && atTop && e.getRawY() - y0 > 120 * dp) {
                MotionEvent c = MotionEvent.obtain(e); c.setAction(MotionEvent.ACTION_CANCEL); super.dispatchTouchEvent(c); c.recycle();
                closeDrawer(); return true;
            }
            return super.dispatchTouchEvent(e);
        }
    }

    /** icons are rendered once to bitmaps (shareable between the frequent row and the A-Z list), off the main thread */
    final android.util.LruCache<String, Bitmap> icons = new android.util.LruCache<String, Bitmap>(
            (int) Math.min(64L << 20, Runtime.getRuntime().maxMemory() / 6)) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };
    final java.util.HashSet<String> iconPending = new java.util.HashSet<String>();
    final java.util.concurrent.ExecutorService iconExec = java.util.concurrent.Executors.newSingleThreadExecutor();
    int iconGen;

    int iconPx() { return px(52); }

    void loadIcon(final App a, final ImageView into) {
        if (a.info == null || !iconPending.add(a.key)) return;
        final int gen = iconGen, size = iconPx(), dpi = getResources().getDisplayMetrics().densityDpi;
        final boolean frame = iconSigFrame;
        final ColorMatrixColorFilter tint = iconFilter();
        final boolean original = sp.getBoolean("lo_" + a.key, false);   // per-app: keep the app's own icon, no pack or colour
        final boolean neon = !original && sp.getInt("lnIconTint", 0) == 2;
        final boolean element = !original && iconSigElement;
        final String label = a.label;
        final ColorMatrixColorFilter tintF = original ? null : tint;
        final LauncherActivityInfo info = a.info;
        final boolean work = a.work;
        final String sig = iconSig, key = element ? a.key + "|" + a.label : a.key, pkg = a.cn.getPackageName();
        final ComponentName cn = a.cn;
        iconExec.execute(new Runnable() { public void run() {
            Bitmap bm = null;
            java.io.File f = iconFile(sig, pkg, key);
            try { if (f.exists()) bm = BitmapFactory.decodeFile(f.getPath()); } catch (Throwable ignored) { }
            if (element && a.elColor == 0) bm = null;               // cached tile but colour unknown (prefs cleared): render again
            if (bm == null || bm.getWidth() != size) {
                try {
                    if (element) {
                        int ec = elementColor(info, dpi);
                        if (ec != a.elColor) { a.elColor = ec; sp.edit().putInt("ec_" + a.key, ec).apply(); }
                        bm = renderElement(label, ec, size, work);
                    }
                    else {
                        IconPacks pack = original || work ? null : pack();
                        Drawable d = pack == null ? null : pack.icon(cn, dpi);
                        bm = renderIcon(d != null ? d : info.getBadgedIcon(dpi), size, frame && loadFrames(), tintF, neon, work);
                    }
                    if (bm != null) saveIcon(f, bm);
                } catch (Throwable ignored) { }
            }
            final Bitmap fb = bm;
            h.post(new Runnable() { public void run() {
                if (gen != iconGen) return;
                iconPending.remove(a.key);
                if (fb == null) return;
                icons.put(a.key, fb);
                if (into != null && a.key.equals(into.getTag())) into.setImageBitmap(fb);
                else if (into == null && drawer.getVisibility() == View.VISIBLE) refreshSoon();
            } });
        } });
    }

    // ---- TN-HUD icon frames (same art and ADW rules as TN-HUD-Icons: back, icon at 0.92 cut by the mask, upon)
    static final float FRAME_SCALE = 0.92f;
    Bitmap fBack, fMask, fUpon;
    boolean framesTried;

    /** worker thread only */
    boolean loadFrames() {
        if (!framesTried) {
            framesTried = true;
            try {
                fBack = BitmapFactory.decodeStream(getAssets().open("frame/iconback.png"));
                fMask = BitmapFactory.decodeStream(getAssets().open("frame/iconmask.png"));
                fUpon = BitmapFactory.decodeStream(getAssets().open("frame/iconupon.png"));
            } catch (Exception e) { fBack = fMask = fUpon = null; }
        }
        return fBack != null && fMask != null && fUpon != null;
    }

    /** adaptive icons are drawn full-bleed (both layers, no system mask) inside a frame, so the octagon is the only shape */
    static boolean drawFullBleed(Drawable d, Canvas c, RectF r, Paint p) {
        if (Build.VERSION.SDK_INT < 26 || d == null || !"android.graphics.drawable.AdaptiveIconDrawable".equals(d.getClass().getName())) return false;
        try {
            Drawable bg = (Drawable) d.getClass().getMethod("getBackground").invoke(d);
            Drawable fg = (Drawable) d.getClass().getMethod("getForeground").invoke(d);
            float pad = r.width() * 0.25f;                        // 108 dp layers around the 72 dp safe-zone viewport
            int l = Math.round(r.left - pad), t = Math.round(r.top - pad), rr = Math.round(r.right + pad), bb = Math.round(r.bottom + pad);
            int n = c.saveLayer(r, p, Canvas.ALL_SAVE_FLAG);
            c.clipRect(r);
            if (bg != null) { bg.setBounds(l, t, rr, bb); bg.draw(c); }
            if (fg != null) { fg.setBounds(l, t, rr, bb); fg.draw(c); }
            c.restoreToCount(n);
            return true;
        } catch (Throwable e) { return false; }
    }

    Bitmap renderIcon(Drawable d, int size, boolean frame, ColorMatrixColorFilter tint, boolean neon, boolean work) {
        if (d == null) return null;
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint ip = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        ip.setColorFilter(tint);
        if (!frame) {
            int n = c.saveLayer(0, 0, size, size, ip, Canvas.ALL_SAVE_FLAG);
            d.setBounds(0, 0, size, size); d.draw(c);
            c.restoreToCount(n);
            if (neon) neonize(out);
            return out;
        }
        RectF full = new RectF(0, 0, size, size);
        Paint fp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        c.drawBitmap(fBack, null, full, fp);
        Bitmap layer = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas lc = new Canvas(layer);
        float in = size * (1 - FRAME_SCALE) / 2;
        RectF ir = new RectF(in, in, size - in, size - in);
        if (work || !drawFullBleed(d, lc, ir, ip)) {               // keep the work badge: draw the badged icon as is
            int n = lc.saveLayer(ir, ip, Canvas.ALL_SAVE_FLAG);
            d.setBounds(Math.round(ir.left), Math.round(ir.top), Math.round(ir.right), Math.round(ir.bottom)); d.draw(lc);
            lc.restoreToCount(n);
        }
        if (neon) neonize(layer);
        Paint mp = new Paint(Paint.FILTER_BITMAP_FLAG);
        mp.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));    // opaque mask pixels cut the icon
        lc.drawBitmap(fMask, null, full, mp);
        c.drawBitmap(layer, 0, 0, fp);
        layer.recycle();
        c.drawBitmap(fUpon, null, full, fp);
        return out;
    }

    // ---- "element" icons: the concept's periodic-table tile (chamfered 20 % top-left / bottom-right),
    //      a two-letter symbol in the category colour; no app artwork
    static final int EL_DEV = 0xFF9ECE6A, EL_COMMS = 0xFF7DCFFF, EL_MEDIA = 0xFFBB9AF7, EL_SYS = 0xFF7AA2F7,
            EL_NAV = 0xFFE0AF68, EL_GAME = 0xFFFF9E64, EL_ALERT = 0xFFF7768E;
    static final int[] EL_PAL = {EL_DEV, EL_COMMS, EL_MEDIA, EL_SYS, EL_NAV, EL_GAME, EL_ALERT};

    /** "WhatsApp" -> "Wa", "SSH Client" -> "Sc" */
    static String elementSymbol(String label) {
        String t = label == null ? "" : label.trim();
        StringBuilder b = new StringBuilder();
        for (String w : t.split("[\\s_\\-.]+")) if (w.length() > 0 && Character.isLetterOrDigit(w.charAt(0))) b.append(w.charAt(0));
        String sym;
        if (b.length() >= 2) sym = b.substring(0, 2);
        else { String l = t.replaceAll("[^\\p{L}\\p{N}]", ""); sym = l.length() >= 2 ? l.substring(0, 2) : l.length() == 1 ? l : "?"; }
        return sym.substring(0, 1).toUpperCase(Locale.getDefault()) + sym.substring(1).toLowerCase(Locale.getDefault());
    }

    /** category colour: the app's declared category (API 26+), else system apps blue, else the icon's dominant hue */
    int elementColor(LauncherActivityInfo info, int dpi) {
        android.content.pm.ApplicationInfo ai = info.getApplicationInfo();
        int cat = -1;
        if (Build.VERSION.SDK_INT >= 26) try { cat = ai.getClass().getField("category").getInt(ai); } catch (Throwable ignored) { }
        switch (cat) {
            case 0: return EL_GAME;                          // CATEGORY_GAME
            case 1: case 2: case 3: return EL_MEDIA;         // AUDIO, VIDEO, IMAGE
            case 4: return EL_COMMS;                         // SOCIAL
            case 5: return EL_NAV;                           // NEWS
            case 6: return EL_NAV;                           // MAPS
            case 7: return EL_DEV;                           // PRODUCTIVITY
            case 8: return EL_SYS;                           // ACCESSIBILITY
        }
        if ((ai.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                && (ai.flags & android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) return EL_SYS;
        try {
            Drawable d = info.getIcon(dpi);
            Bitmap b = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, 48, 48); d.draw(new Canvas(b));
            int[] px = new int[48 * 48]; b.getPixels(px, 0, 48, 0, 0, 48, 48); b.recycle();
            float[] bins = new float[36], hsv = new float[3];
            for (int p : px) {
                float a = (p >>> 24) / 255f;
                if (a < 0.5f) continue;
                Color.colorToHSV(p | 0xFF000000, hsv);
                bins[((int) (hsv[0] / 10)) % 36] += hsv[1] * hsv[2] * a;
            }
            int bb = 0;
            for (int k = 1; k < 36; k++) if (bins[k] > bins[bb]) bb = k;
            if (bins[bb] < 20) return EL_SYS;                  // grey / white icon
            float hue = bb * 10 + 5, best = 999; int col = EL_SYS;
            for (int c : EL_PAL) {
                Color.colorToHSV(c, hsv);
                float dh = Math.abs(hsv[0] - hue); dh = Math.min(dh, 360 - dh);
                if (dh < best) { best = dh; col = c; }
            }
            return col;
        } catch (Throwable e) { return EL_SYS; }
    }

    Bitmap renderElement(String label, int col, int size, boolean work) {
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        float m = size * 0.04f, a = m, b = size - m, k = (b - a) * 0.20f, bw = Math.max(1.5f, size * 0.026f);
        android.graphics.Path outer = new android.graphics.Path();
        outer.moveTo(a + k, a); outer.lineTo(b, a); outer.lineTo(b, b - k); outer.lineTo(b - k, b); outer.lineTo(a, b); outer.lineTo(a, a + k); outer.close();
        float i0 = a + bw, i1 = b - bw, ki = k - bw * 0.41f;
        android.graphics.Path inner = new android.graphics.Path();
        inner.moveTo(i0 + ki, i0); inner.lineTo(i1, i0); inner.lineTo(i1, i1 - ki); inner.lineTo(i1 - ki, i1); inner.lineTo(i0, i1); inner.lineTo(i0, i0 + ki); inner.close();
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(col); c.drawPath(outer, p);
        p.setColor(0xFF05070D); c.drawPath(inner, p);
        p.setTypeface(mono); p.setColor(col);
        String sym = elementSymbol(label);
        p.setTextSize(size * 0.40f);
        float tw = p.measureText(sym), tx = Math.max(i0 + size * 0.10f, (size - tw) / 2 - size * 0.04f);
        c.drawText(sym, tx, size * 0.66f, p);
        p.setTextSize(size * 0.13f); p.setColor(0xFF737AA2);
        if (work) c.drawText("WRK", i0 + size * 0.08f, i1 - size * 0.08f, p);
        else { c.drawRect(i0 + size * 0.08f, i1 - size * 0.13f, i0 + size * 0.30f, i1 - size * 0.10f, p); }
        return out;
    }

    boolean iconSigFrame, iconSigElement; int iconSigTint = -1; String iconSigPack = null, iconSig = "";
    /** frame / colour / pack changes re-render every icon (and drop the disk cache of the old style) */
    void checkIconSig() {
        boolean f = sp.getBoolean("lnIconFrame", true);
        int t = sp.getInt("lnIconTint", 0);
        String pk = sp.getString("lnIconPack", "");
        boolean el = sp.getBoolean("lnIconElement", false);
        if (f != iconSigFrame || t != iconSigTint || el != iconSigElement || !pk.equals(iconSigPack)) {
            iconSigFrame = f; iconSigTint = t; iconSigElement = el; iconSigPack = pk;
            iconSig = "v2_" + (el ? "e" : f ? "f" : "n") + t + "_" + Integer.toHexString(pk.hashCode()) + "_" + iconPx();
            iconGen++; icons.evictAll(); iconPending.clear();
            final String keep = iconSig;
            iconExec.execute(new Runnable() { public void run() {
                packLoaded = null; packObj = null;
                java.io.File[] all = iconRoot().listFiles();
                if (all != null) for (java.io.File d : all) if (!d.getName().equals(keep)) rmTree(d);
            } });
        }
    }

    // ---- icon pack (loaded on the icon thread) and the rendered-icon disk cache
    String packLoaded; IconPacks packObj;
    /** icon thread only */
    IconPacks pack() {
        String pk = iconSigPack == null ? "" : iconSigPack;
        if (!pk.equals(packLoaded)) { packLoaded = pk; packObj = IconPacks.load(this, pk); }
        return packObj;
    }

    java.io.File iconRoot() { return new java.io.File(getCacheDir(), "icons"); }
    java.io.File iconFile(String sig, String pkg, String key) {
        return new java.io.File(new java.io.File(new java.io.File(iconRoot(), sig), pkg), Integer.toHexString(key.hashCode()) + ".png");
    }

    static void saveIcon(java.io.File f, Bitmap b) {
        java.io.FileOutputStream o = null;
        try { f.getParentFile().mkdirs(); o = new java.io.FileOutputStream(f); b.compress(Bitmap.CompressFormat.PNG, 100, o); }
        catch (Throwable ignored) { }
        finally { if (o != null) try { o.close(); } catch (Exception ignored) { } }
    }

    static void rmTree(java.io.File f) {
        java.io.File[] k = f.listFiles();
        if (k != null) for (java.io.File x : k) rmTree(x);
        f.delete();
    }

    /** an updated / removed package's icons are rendered again */
    void dropDisk(final String pkg) {
        iconExec.execute(new Runnable() { public void run() {
            java.io.File[] all = iconRoot().listFiles();
            if (all != null) for (java.io.File d : all) rmTree(new java.io.File(d, pkg));
        } });
    }

    /** per-app "keep the original icon" from the app menu */
    void setOriginalIcon(final App a, boolean on) {
        sp.edit().putBoolean("lo_" + a.key, on).apply();
        final java.io.File f = iconFile(iconSig, a.cn.getPackageName(), a.key);
        iconExec.execute(new Runnable() { public void run() { f.delete(); } });
        icons.remove(a.key); iconPending.remove(a.key);
        refreshSoon();
    }

    final Runnable refresh = new Runnable() { public void run() { adapter.notifyDataSetChanged(); } };
    void refreshSoon() { h.removeCallbacks(refresh); h.postDelayed(refresh, 120); }

    void prefetchIcons() { for (App a : apps) if (icons.get(a.key) == null && !hidden(a)) loadIcon(a, null); }

    void bindIcon(ImageView iv, App a) {
        iv.setTag(a.key);
        Bitmap b = icons.get(a.key);
        iv.setImageBitmap(b);
        if (b == null) loadIcon(a, iv);
    }

    ColorMatrixColorFilter monoF;
    /** icon colour: 0 original, 1 mono, 2 neon (see neonize); only mono is a colour filter */
    ColorMatrixColorFilter iconFilter() {
        if (sp.getInt("lnIconTint", 0) != 1) return null;
        if (monoF == null) { ColorMatrix cm = new ColorMatrix(); cm.setSaturation(0); monoF = new ColorMatrixColorFilter(cm); }
        return monoF;
    }

    /**
     * Neon sign version of an icon, in the icon's own colours. The shapes become dark tinted glass, their outlines
     * (edges in brightness and in alpha) become bright tubes in the local hue, and a soft glow spreads around the tubes.
     * Grey or white parts take the icon's dominant hue (Tokyo Night cyan for an all-grey icon).
     */
    static void neonize(Bitmap b) {
        int w = b.getWidth(), hh = b.getHeight(), n = w * hh;
        int[] px = new int[n];
        b.getPixels(px, 0, w, 0, 0, w, hh);
        float[] lum = new float[n], al = new float[n], hue = new float[n], sat = new float[n], bins = new float[36], hsv = new float[3];
        for (int i = 0; i < n; i++) {
            int p = px[i];
            float a = (p >>> 24) / 255f;
            al[i] = a;
            if (a == 0) continue;
            Color.colorToHSV(p | 0xFF000000, hsv);
            hue[i] = hsv[0]; sat[i] = hsv[1]; lum[i] = lum(p) / 255f;
            bins[((int) (hsv[0] / 10)) % 36] += hsv[1] * hsv[2] * a;
        }
        int bb = 0;
        for (int k = 1; k < 36; k++) if (bins[k] > bins[bb]) bb = k;
        float dom = bins[bb] > n * 0.002f ? bb * 10 + 5 : 197;   // 197: hue of #7DCFFF

        // tube strength: Sobel edges of brightness (inside the shape) and of alpha (the silhouette)
        float[] e = new float[n];
        for (int y = 1; y < hh - 1; y++) for (int x = 1; x < w - 1; x++) {
            int i = y * w + x;
            float gl = sob(lum, al, w, i, true), ga = sob(lum, al, w, i, false);
            e[i] = Math.min(1f, Math.max(gl, ga) / 1.1f);
        }
        float[] glow = new float[n], tmp = new float[n];
        int r = Math.max(2, w / 36);
        boxBlur(e, tmp, glow, w, hh, r);
        boxBlur(glow, tmp, glow, w, hh, r);

        for (int i = 0; i < n; i++) {
            float a = al[i], ei = e[i], gi = Math.min(1f, glow[i] * 2.2f);
            boolean coloured = a > 0 && sat[i] > 0.22f;
            float h = coloured ? hue[i] : dom;
            // tube hue: the most saturated pixel around, so a white-on-colour edge takes the colour, not the dominant hue
            float th = h, best = coloured ? sat[i] : 0.22f;
            int x0 = i % w, y0 = i / w;
            for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                int x = x0 + dx, y = y0 + dy;
                if (x < 0 || y < 0 || x >= w || y >= hh) continue;
                int j = y * w + x;
                if (al[j] > 0 && sat[j] > best) { best = sat[j]; th = hue[j]; }
            }
            hsv[0] = h; hsv[1] = coloured ? 0.85f : 0.35f; hsv[2] = 0.10f + 0.24f * lum[i];   // grey / white parts stay mostly neutral glass
            int fill = Color.HSVToColor(hsv);
            hsv[0] = th; hsv[1] = 0.55f; hsv[2] = 1f;             // tube: bright, slightly washed towards white like a lit tube
            int tube = Color.HSVToColor(hsv);
            hsv[0] = a > 0.5f ? h : dom; hsv[1] = 0.9f; hsv[2] = 1f;
            int gc = Color.HSVToColor(hsv);
            // premultiplied: glass fill and tube by coverage, plus the glow added on top (and spilling past the shape)
            float fa = a * (1 - ei), ga = gi * 0.55f;
            float cov = Math.min(1f, fa + ei), oa = Math.min(1f, cov + ga * (1 - cov));
            if (oa <= 0.004f) { px[i] = 0; continue; }
            int out = 0;
            for (int sh = 16; sh >= 0; sh -= 8) {
                float pm = ((fill >> sh) & 255) * fa + ((tube >> sh) & 255) * ei + ((gc >> sh) & 255) * ga;
                out |= Math.min(255, Math.round(pm / oa)) << sh;
            }
            px[i] = (Math.round(oa * 255) << 24) | out;
        }
        b.setPixels(px, 0, w, 0, 0, w, hh);
    }

    /** Sobel magnitude at i of premultiplied brightness (bright) or of alpha */
    static float sob(float[] l, float[] a, int w, int i, boolean bright) {
        float tl = v(l, a, i - w - 1, bright), t = v(l, a, i - w, bright), tr = v(l, a, i - w + 1, bright);
        float ml = v(l, a, i - 1, bright), mr = v(l, a, i + 1, bright);
        float bl = v(l, a, i + w - 1, bright), bo = v(l, a, i + w, bright), br = v(l, a, i + w + 1, bright);
        float gx = (tr + 2 * mr + br) - (tl + 2 * ml + bl), gy = (bl + 2 * bo + br) - (tl + 2 * t + tr);
        return (float) Math.sqrt(gx * gx + gy * gy);
    }

    static float v(float[] l, float[] a, int i, boolean bright) { return bright ? l[i] * a[i] : a[i]; }

    /** separable box blur, radius r; dst may be src */
    static void boxBlur(float[] src, float[] tmp, float[] dst, int w, int h, int r) {
        float k = 1f / (2 * r + 1);
        for (int y = 0; y < h; y++) {
            float acc = 0;
            for (int x = -r; x <= r; x++) acc += src[y * w + Math.max(0, Math.min(w - 1, x))];
            for (int x = 0; x < w; x++) {
                tmp[y * w + x] = acc * k;
                acc += src[y * w + Math.min(w - 1, x + r + 1)] - src[y * w + Math.max(0, x - r)];
            }
        }
        for (int x = 0; x < w; x++) {
            float acc = 0;
            for (int y = -r; y <= r; y++) acc += tmp[Math.max(0, Math.min(h - 1, y)) * w + x];
            for (int y = 0; y < h; y++) {
                dst[y * w + x] = acc * k;
                acc += tmp[Math.min(h - 1, y + r + 1) * w + x] - tmp[Math.max(0, y - r) * w + x];
            }
        }
    }

    static int lum(int p) { return (54 * ((p >> 16) & 255) + 183 * ((p >> 8) & 255) + 19 * (p & 255)) >> 8; }

    String badgeText(App a) {
        int n = a.work ? 0 : Link.badge(a.cn.getPackageName());
        if (n <= 0 || !sp.getBoolean("lkBadges", true)) return null;
        return n > 99 ? "99+" : String.valueOf(n);
    }

    StateListDrawable pressBg(boolean top) {
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_pressed}, new android.graphics.drawable.ColorDrawable(0x33BB9AF7));
        if (top) { GradientDrawable g = new GradientDrawable(); g.setColor(0x1ABB9AF7); g.setStroke(px(1), ACCENT); sl.addState(new int[0], g); }
        return sl;
    }

    // ------------------------------------------------------------------ fzf look
    static final Object COLS = new Object();                      // column header row of the text list
    TextView prompt; LinearLayout scStrip, chips;
    String lastQ = "";
    int catSel;                                                   // 0 all, -1 frequent, else an element colour

    void setPrompt(String cmd) {
        String user = "tn@" + Build.MODEL.toLowerCase(Locale.ROOT).replace(' ', '-');
        android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder();
        span(b, user, ACCENT); span(b, ":", COMMENT); span(b, "/apps", BLUE); span(b, "$ ", COMMENT); span(b, cmd, FG);
        prompt.setText(b);
    }

    static void span(android.text.SpannableStringBuilder b, String t, int col) {
        int s0 = b.length(); b.append(t);
        b.setSpan(new android.text.style.ForegroundColorSpan(col), s0, b.length(), 0);
    }

    /** concept's square chip; on = accent fill */
    TextView chip(String label, int col, boolean on) {
        TextView t = tv(label, 11, on ? 0xFF000000 : col);
        t.setGravity(Gravity.CENTER);
        t.setPadding(px(9), 0, px(9), 0);
        GradientDrawable g = new GradientDrawable();
        if (on) g.setColor(ACCENT); else { g.setColor(0); g.setStroke(px(1), LINE); }
        t.setBackground(g);
        if (on) t.setTypeface(mono, Typeface.BOLD);
        return t;
    }

    /** element grid: ALL / DEV / COMMS / MEDIA / SYS / NAV / ★ FREQ; text styles: key hints */
    void buildChips(boolean periodic) {
        chips.removeAllViews();
        if (!periodic) {
            String[] hints = search.getText().length() > 0 ? new String[]{"\u21B5 open", "hold \u00B7 menu", "> command"}
                    : new String[]{"> command", "hold \u00B7 menu", "A-Z rail \u2192"};
            for (String hnt : hints) {
                TextView t = tv(hnt, 10, COMMENT);
                t.setPadding(px(6), px(2), px(6), px(2));
                GradientDrawable g = new GradientDrawable(); g.setColor(0); g.setStroke(px(1), LINE); t.setBackground(g);
                LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                l.rightMargin = px(6);
                chips.addView(t, l);
            }
            return;
        }
        String[] lb = {"ALL", "DEV", "COMMS", "MEDIA", "SYS", "NAV", "GAME", "\u2605 FREQ"};
        final int[] key = {0, EL_DEV, EL_COMMS, EL_MEDIA, EL_SYS, EL_NAV, EL_GAME, -1};
        int[] col = {FG, EL_DEV, EL_COMMS, EL_MEDIA, EL_SYS, EL_NAV, EL_GAME, YELLOW};
        for (int i = 0; i < lb.length; i++) {
            final int k = key[i];
            TextView t = chip(lb[i], col[i], catSel == k);
            t.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { catSel = k; hideKeyboard(); filter(); } });
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(32));
            l.rightMargin = px(6);
            chips.addView(t, l);
        }
    }

    /** "› shortcuts: WhatsApp" + up to 3 numbered shortcuts of the top match */
    void showShortcuts(final App a) {
        scStrip.removeAllViews();
        List<Object[]> L = a == null || a.work ? null : shortcuts(a);
        if (L == null || L.isEmpty()) { scStrip.setVisibility(View.GONE); return; }
        scStrip.setVisibility(View.VISIBLE);
        scStrip.addView(tv("\u203A shortcuts: " + a.label, 11, ACCENT));
        LinearLayout r = new LinearLayout(this);
        for (int i = 0; i < Math.min(3, L.size()); i++) {
            final Object info = L.get(i)[1];
            android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder();
            span(b, (i + 1) + " ", COMMENT); span(b, (String) L.get(i)[0], FG);
            TextView t = tv("", 12, FG);
            t.setText(b);
            t.setSingleLine(true); t.setEllipsize(TextUtils.TruncateAt.END);
            t.setPadding(0, px(8), px(14), px(8));
            t.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { startShortcut(info); } });
            r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        scStrip.addView(r);
    }

    /** label with the query's letters underlined in the accent (substring first, else letters in order) */
    CharSequence nameSpan(App a, boolean top) {
        String lab = a.label, nl = norm(lab);
        android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder();
        if (top) span(b, "\u25B6 ", ACCENT);
        int s0 = b.length();
        b.append(lab);
        b.setSpan(new android.text.style.ForegroundColorSpan(top ? ACCENT : hidden(a) ? COMMENT : FG), s0, b.length(), 0);
        String q = lastQ;
        if (q.length() > 0 && nl.length() == lab.length()) {
            int at = nl.indexOf(q);
            boolean[] hit = new boolean[lab.length()];
            if (at >= 0) for (int i = at; i < at + q.length(); i++) hit[i] = true;
            else { int k = 0; for (int i = 0; i < nl.length() && k < q.length(); i++) if (nl.charAt(i) == q.charAt(k)) { hit[i] = true; k++; } if (k < q.length()) hit = new boolean[lab.length()]; }
            for (int i = 0; i < hit.length; i++) if (hit[i]) {
                b.setSpan(new android.text.style.UnderlineSpan(), s0 + i, s0 + i + 1, 0);
                b.setSpan(new android.text.style.ForegroundColorSpan(top ? ACCENT : FG), s0 + i, s0 + i + 1, 0);
                b.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), s0 + i, s0 + i + 1, 0);
            }
        }
        if (a.work) span(b, "  (work)", COMMENT);
        if (hidden(a)) span(b, "  HIDDEN", COMMENT);
        String bd = badgeText(a);
        if (bd != null) {
            b.append("  ");
            int b0 = b.length(); b.append(" " + bd + " ");
            b.setSpan(new android.text.style.BackgroundColorSpan(ACCENT), b0, b.length(), 0);
            b.setSpan(new android.text.style.ForegroundColorSpan(0xFF000000), b0, b.length(), 0);
            b.setSpan(new android.text.style.RelativeSizeSpan(0.75f), b0, b.length(), 0);
        }
        return b;
    }

    static String age(long last) {
        if (last == 0) return "\u2014";
        long m = Math.max(0, System.currentTimeMillis() - last) / 60000;
        return m < 60 ? m + "m" : m < 1440 ? (m / 60) + "h" : m < 14 * 1440 ? (m / 1440) + "d" : (m / 10080) + "w";
    }

    /** selected-row look: faint accent fill with a 2 dp accent bar on the left */
    Drawable topBg() {
        android.graphics.drawable.LayerDrawable ld = new android.graphics.drawable.LayerDrawable(new Drawable[]{
                new android.graphics.drawable.ColorDrawable(0x1ABB9AF7), new android.graphics.drawable.ColorDrawable(ACCENT)});
        ld.setLayerGravity(1, Gravity.START | Gravity.FILL_VERTICAL);
        ld.setLayerWidth(1, px(2));
        return ld;
    }

    LinearLayout fzfRow() {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(px(48));
        r.setPadding(px(4), 0, px(8), 0);
        int[] w = {44, 40, 40};
        for (int k = 0; k < 3; k++) { TextView t = tv("", 13, COMMENT); t.setSingleLine(true); r.addView(t, new LinearLayout.LayoutParams(px(w[k]), ViewGroup.LayoutParams.WRAP_CONTENT)); }
        TextView n = tv("", 16, FG); n.setSingleLine(true); n.setEllipsize(TextUtils.TruncateAt.END);
        r.addView(n, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return r;
    }

    static final int T_TEXT = 0, T_HEAD = 1, T_ICON = 2, T_GRID = 3, T_HIDDEN = 4, T_CMD = 5, T_COLS = 6;

    final class AppAdapter extends BaseAdapter {
        public int getCount() { return rows.size(); }
        public Object getItem(int i) { return rows.get(i); }
        public long getItemId(int i) { return i; }
        public int getViewTypeCount() { return 7; }
        public int getItemViewType(int i) {
            Object o = rows.get(i);
            if (o == SHOW_HIDDEN) return T_HIDDEN;
            if (o == COLS) return T_COLS;
            if (o instanceof Commands.Cmd) return T_CMD;
            if (o instanceof App) return style == ST_LIST ? T_ICON : T_TEXT;
            if (o instanceof App[]) return T_GRID;
            return T_HEAD;
        }
        public boolean isEnabled(int i) { Object o = rows.get(i); return o instanceof App || o instanceof Commands.Cmd || o == SHOW_HIDDEN; }

        public View getView(int i, View v, ViewGroup parent) {
            Object o = rows.get(i);
            boolean query = search.getText().length() > 0;
            if (o == COLS) {
                LinearLayout r = (LinearLayout) v;
                if (r == null) {
                    r = fzfRow(); r.setMinimumHeight(px(28));
                    String[] h = {"FLAGS", "HITS", "AGE", "NAME"};
                    for (int k = 0; k < 4; k++) { TextView t = (TextView) r.getChildAt(k); t.setText(h[k]); t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10); t.setTextColor(COMMENT); }
                    GradientDrawable g = new GradientDrawable(); g.setColor(0); g.setStroke(px(1), LINE, px(4), px(3));
                    android.graphics.drawable.InsetDrawable bottom = new android.graphics.drawable.InsetDrawable(g, -px(2), -px(2), -px(2), 0);
                    r.setBackground(bottom);
                }
                return r;
            }
            if (o instanceof Commands.Cmd) {
                TextView t = (TextView) v;
                if (t == null) { t = tv("", 18, FG); t.setMinHeight(px(52)); t.setGravity(Gravity.CENTER_VERTICAL); t.setPadding(px(4), 0, px(8), 0); t.setSingleLine(true); t.setEllipsize(TextUtils.TruncateAt.END); }
                t.setText(commandLabel((Commands.Cmd) o, i == 1));
                return t;
            }
            if (o == SHOW_HIDDEN) {
                TextView t = (TextView) v;
                if (t == null) { t = tv("", 19, FG); t.setMinHeight(px(52)); t.setGravity(Gravity.CENTER_VERTICAL); t.setPadding(px(4), 0, px(8), 0); }
                t.setText(showHidden ? "− HIDE HIDDEN APPS" : "+ SHOW " + hiddenCount + " HIDDEN APP" + (hiddenCount > 1 ? "S" : ""));
                t.setTextColor(COMMENT);
                return t;
            }
            if (o instanceof App && style == ST_LIST) {
                App a = (App) o;
                LinearLayout r = (LinearLayout) v;
                if (r == null) {
                    r = new LinearLayout(HudLauncher.this);
                    r.setGravity(Gravity.CENTER_VERTICAL);
                    r.setMinimumHeight(px(56));
                    r.setPadding(px(2), 0, px(8), 0);
                    ImageView iv = new ImageView(HudLauncher.this);
                    iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    r.addView(iv, new LinearLayout.LayoutParams(px(36), px(36)));
                    TextView t = tv("", 18, FG);
                    t.setSingleLine(true); t.setEllipsize(TextUtils.TruncateAt.END);
                    t.setPadding(px(14), 0, 0, 0);
                    r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                }
                bindIcon((ImageView) r.getChildAt(0), a);
                boolean top = query && i == firstApp();
                ((TextView) r.getChildAt(1)).setText(nameSpan(a, top));
                r.setBackground(top ? topBg() : null);
                return r;
            }
            if (o instanceof App) {                                  // fzf table row: FLAGS HITS AGE NAME
                App a = (App) o;
                LinearLayout r = (LinearLayout) v;
                if (r == null) r = fzfRow();
                boolean top = query && i == firstApp(), hd = hidden(a);
                String fl = (badgeText(a) != null ? "n" : "-") + (a.work ? "w" : "-") + (hd ? "h" : "-");
                TextView f0 = (TextView) r.getChildAt(0), f1 = (TextView) r.getChildAt(1), f2 = (TextView) r.getChildAt(2);
                f0.setText(fl); f0.setTextColor(hd ? COMMENT : fl.equals("---") ? COMMENT : a.work ? ACCENT : GREEN);
                f1.setText(a.launches > 999 ? "999+" : String.valueOf(a.launches)); f1.setTextColor(hd ? COMMENT : YELLOW);
                f2.setText(age(a.last)); f2.setTextColor(COMMENT);
                ((TextView) r.getChildAt(3)).setText(nameSpan(a, top));
                r.setBackground(top ? topBg() : null);
                r.setContentDescription(a.label);
                return r;
            }
            if (o instanceof App[]) return gridRow((App[]) o, (LinearLayout) v, query && i == firstApp());
            TextView t = (TextView) v;
            if (t == null) { t = tv("", 13, COMMENT); t.setPadding(px(4), px(14), 0, px(4)); }
            t.setText((String) o);
            String hs = (String) o;
            t.setTextColor(hs.startsWith("NO MATCH") || hs.startsWith("UNKNOWN") || hs.startsWith("NOTHING") ? COMMENT : ACCENT);
            return t;
        }
    }

    /** one grid row: 'cols' equal cells, each icon (+ badge) over an optional one-line label */
    View gridRow(final App[] cells, LinearLayout r, boolean firstIsTop) {
        boolean labels = style == ST_GRID;
        if (r == null || r.getChildCount() != cols || !Boolean.valueOf(labels).equals(r.getTag())) {
            r = new LinearLayout(HudLauncher.this);
            r.setTag(labels);
            r.setPadding(0, px(4), px(4), px(4));
            for (int k = 0; k < cols; k++) {
                LinearLayout cell = new LinearLayout(this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER_HORIZONTAL);
                cell.setPadding(px(2), px(8), px(2), px(labels ? 6 : 8));
                FrameLayout box = new FrameLayout(this);
                ImageView iv = new ImageView(this);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                int is = cols >= 6 ? 44 : cols == 5 ? 48 : 52;
                box.addView(iv, new FrameLayout.LayoutParams(px(is), px(is), Gravity.CENTER));
                TextView bd = tv("", 10, 0xFF000000);
                GradientDrawable bg = new GradientDrawable(); bg.setColor(ACCENT); bg.setCornerRadius(0);
                bd.setBackground(bg);
                bd.setPadding(px(4), 0, px(4), 0);
                bd.setGravity(Gravity.CENTER);
                box.addView(bd, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(15), Gravity.TOP | Gravity.END));
                cell.addView(box, new LinearLayout.LayoutParams(px(is + 12), px(is + 6)));
                TextView t = tv("", cols >= 6 ? 10 : 11, FG);
                t.setSingleLine(true); t.setEllipsize(TextUtils.TruncateAt.END);
                t.setGravity(Gravity.CENTER);
                t.setPadding(0, px(4), 0, 0);
                t.setVisibility(labels ? View.VISIBLE : View.GONE);
                cell.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                r.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
        }
        for (int k = 0; k < cols; k++) {
            LinearLayout cell = (LinearLayout) r.getChildAt(k);
            FrameLayout box = (FrameLayout) cell.getChildAt(0);
            ImageView iv = (ImageView) box.getChildAt(0);
            TextView bd = (TextView) box.getChildAt(1), t = (TextView) cell.getChildAt(1);
            if (k >= cells.length) {
                cell.setVisibility(View.INVISIBLE); cell.setOnClickListener(null); cell.setOnLongClickListener(null); cell.setClickable(false);
                iv.setTag(null);
                continue;
            }
            final App a = cells[k];
            cell.setVisibility(View.VISIBLE);
            bindIcon(iv, a);
            String b = badgeText(a);
            bd.setText(b == null ? "" : b); bd.setVisibility(b == null ? View.GONE : View.VISIBLE);
            t.setText(a.label);
            t.setTextColor(a.work ? BLUE : hidden(a) ? COMMENT : FG);
            iv.setAlpha(hidden(a) ? 0.45f : 1f);
            cell.setBackground(pressBg(firstIsTop && k == 0));
            cell.setContentDescription(a.label + (b == null ? "" : ", " + b + " unread"));
            cell.setOnClickListener(new View.OnClickListener() { public void onClick(View x) { launch(a); } });
            cell.setOnLongClickListener(new View.OnLongClickListener() { public boolean onLongClick(View x) { x.performHapticFeedback(0); appMenu(a); return true; } });
        }
        return r;
    }

    App topApp() {
        for (Object o : rows) { if (o instanceof App) return (App) o; if (o instanceof App[] && ((App[]) o).length > 0) return ((App[]) o)[0]; }
        return null;
    }

    int firstApp() { for (int i = 0; i < rows.size(); i++) if (rows.get(i) instanceof App || rows.get(i) instanceof App[]) return i; return -1; }
}
