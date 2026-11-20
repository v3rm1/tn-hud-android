package com.tokyonight.hud.live;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.os.UserManager;
import android.text.Editable;
import android.text.InputType;
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
 *   swipe up       -> text-only app drawer, search field focused, keyboard up
 *   swipe down     -> notifications (left part of the screen) / quick settings (right part)
 *   long press     -> launcher menu
 * Swipe up / down, two-finger swipe down, double tap and the home button (while home) are remappable (Gestures).
 * Because the wallpaper is told a launcher is in front ("tnhud.launcher"), it ignores its own raw touches.
 */
public class HudLauncher extends Activity {
    static final int FG = 0xFFC0CAF5, COMMENT = 0xFF565F89, BLUE = 0xFF7AA2F7, CYAN = 0xFF7DCFFF, LINE = 0xFF3B4261, BG = 0xF0000000;

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
        String label, sortKey, key; ComponentName cn; UserHandle user; boolean work; int launches;
        float score; long last;                                   // frecency: launches decayed with a 14-day time constant
        float now(long t) { return last == 0 ? 0 : score * (float) Math.exp(-(t - last) / (14 * 86400000.0)); }
    }
    final ArrayList<App> apps = new ArrayList<App>();
    final ArrayList<Object> rows = new ArrayList<Object>();       // String = section header, App = row
    static final Object SHOW_HIDDEN = new Object();
    boolean showHidden, resumed, torchOn;
    String torchId;

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
    }

    @Override protected void onStart() {
        super.onStart();
        try { host.startListening(); } catch (Throwable ignored) { }
        HudBus.listen(new HudBus.WidgetListener() { public void onWidgetRect(int[] r) { placeWidget(r); } });
    }

    @Override protected void onStop() {
        HudBus.listen(null);
        try { host.stopListening(); } catch (Throwable ignored) { }
        super.onStop();
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
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("Widget for the HUD slot").setItems(names, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int w) { bindWidget(L.get(w)); }
        }).setNegativeButton("Cancel", null).show();
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
        if (drawer.getVisibility() == View.VISIBLE) closeDrawer();
        else if (resumed && hasWindowFocus() && (i.getFlags() & Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT) == 0) run(Gestures.HOME_PRESS);   // already home
    }

    @Override public void onBackPressed() {
        if (drawer.getVisibility() == View.VISIBLE) closeDrawer();
    }

    @Override protected void onDestroy() {
        try { la.unregisterCallback(appsChanged); } catch (Throwable ignored) { }
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
            case Gestures.HUD_SETTINGS: try { startActivity(new Intent(this, SetWallpaper.class)); } catch (Exception ignored) { } break;
            case Gestures.OPEN_APP: {
                for (App x : apps) if (x.key.equals(appKey)) { launch(x); return; }
                break;
            }
            default: break;
        }
    }

    void statusBar(boolean qs) {
        try {
            Object sbm = getSystemService("statusbar");
            Class.forName("android.app.StatusBarManager").getMethod(qs ? "expandSettingsPanel" : "expandNotificationsPanel").invoke(sbm);
        } catch (Throwable t) { /* hidden API refused: the normal swipe from the status bar still works */ }
    }

    /** torch via CameraManager (no permission needed); state tracked with a TorchCallback */
    void torch() {
        try {
            android.hardware.camera2.CameraManager cm = (android.hardware.camera2.CameraManager) getSystemService(Context.CAMERA_SERVICE);
            if (torchId == null) {
                for (String id : cm.getCameraIdList()) {
                    Boolean f = cm.getCameraCharacteristics(id).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE);
                    if (f != null && f) { torchId = id; break; }
                }
                if (torchId == null) return;
                cm.registerTorchCallback(new android.hardware.camera2.CameraManager.TorchCallback() {
                    @Override public void onTorchModeChanged(String id, boolean on) { if (id.equals(torchId)) torchOn = on; }
                }, h);
            }
            cm.setTorchMode(torchId, !torchOn);
        } catch (Throwable ignored) { }
    }

    void launcherMenu() {
        final boolean hasW = sp.getInt("wgId", -1) >= 0;
        final String[] items = hasW ? new String[]{"HUD settings", "Choose widget\u2026", "Remove widget", "Home app", "Android settings"}
                : new String[]{"HUD settings", "Choose widget\u2026", "Home app", "Android settings"};
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setItems(items, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int w) {
                String it = items[w];
                try {
                    if (it.startsWith("HUD")) startActivity(new Intent(HudLauncher.this, SetWallpaper.class));
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
        TextView title = tv("// APPS", 14, BLUE);
        top.addView(title);
        count = tv("", 14, COMMENT);
        top.addView(count);
        drawer.addView(top);

        search = new EditText(this);
        search.setTypeface(mono);
        search.setTextColor(FG);
        search.setHintTextColor(COMMENT);
        search.setHint("SEARCH · ENTER OPENS THE TOP MATCH");
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_FILTER);
        search.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        GradientDrawable bd = new GradientDrawable();
        bd.setColor(0xFF0B0E17); bd.setStroke(px(1.5f), BLUE); bd.setCornerRadius(0);
        search.setBackground(bd);
        search.setPadding(px(14), px(12), px(14), px(12));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = px(10); slp.bottomMargin = px(8); slp.rightMargin = px(12);
        drawer.addView(search, slp);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable s) { filter(); }
        });
        search.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int id, KeyEvent ev) {
                for (Object o : rows) if (o instanceof App) { launch((App) o); return true; }
                return true;
            }
        });

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        list = new ListView(this);
        list.setDivider(null);
        list.setSelector(new android.graphics.drawable.ColorDrawable(0x22_7AA2F7));
        list.setVerticalScrollBarEnabled(false);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setAdapter(adapter);
        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            public void onItemClick(android.widget.AdapterView<?> p, View v, int pos, long id) {
                Object o = rows.get(pos);
                if (o instanceof App) launch((App) o);
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
        list.setOnTouchListener(new View.OnTouchListener() {         // pull down at the top closes the drawer
            float y0; boolean atTop;
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) { y0 = e.getRawY(); atTop = listAtTop(); }
                else if (e.getActionMasked() == MotionEvent.ACTION_UP && atTop && e.getRawY() - y0 > 120 * dp) { closeDrawer(); return true; }
                return false;
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
                for (int k = 0; k < n; k++) ((TextView) rail.getChildAt(k)).setTextColor(k == i && e.getActionMasked() != MotionEvent.ACTION_UP ? CYAN : COMMENT);
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
        showHidden = false;
        search.setText("");
        filter();
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
        public void onPackageRemoved(String p, UserHandle u) { loadApps(); }
        public void onPackageAdded(String p, UserHandle u) { loadApps(); }
        public void onPackageChanged(String p, UserHandle u) { loadApps(); }
        public void onPackagesAvailable(String[] p, UserHandle u, boolean r) { loadApps(); }
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
                a.cn = i.getComponentName(); a.user = u; a.work = !u.equals(me);
                a.key = a.cn.flattenToShortString() + "#" + (um != null ? um.getSerialNumberForUser(u) : 0);
                String custom = sp.getString("ln_" + a.key, null);
                a.label = custom != null ? custom : String.valueOf(i.getLabel());
                a.sortKey = a.label.toUpperCase(Locale.ROOT);
                a.launches = sp.getInt("lc_" + a.key, 0);
                a.last = sp.getLong("lt_" + a.key, 0);
                a.score = sp.getFloat("lf_" + a.key, a.launches);
                if (a.last == 0 && a.launches > 0) a.last = System.currentTimeMillis();
                apps.add(a);
            }
        }
        final Collator col = Collator.getInstance();
        Collections.sort(apps, new Comparator<App>() { public int compare(App x, App y) { return col.compare(x.label, y.label); } });
        filter();
    }

    static String norm(String s) {
        return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    /** match score: 3 prefix, 2 word prefix, 1 substring, 0 none */
    static int score(String label, String q) {
        String l = norm(label);
        if (l.startsWith(q)) return 3;
        for (String w : l.split("[\\s\\-_.:]+")) if (w.startsWith(q)) return 2;
        return l.contains(q) ? 1 : 0;
    }

    void filter() {
        rows.clear();
        String q = norm(search == null ? "" : search.getText().toString().trim());
        boolean showHidden = q.length() > 0 && sp.getBoolean("lnSearchHidden", true);
        int visible = 0;
        if (q.isEmpty()) {
            if (sp.getBoolean("lnFrequent", true)) {
                ArrayList<App> fr = new ArrayList<App>();
                final long now = System.currentTimeMillis();
                for (App a : apps) if (a.now(now) > 0.05f && !hidden(a)) fr.add(a);
                Collections.sort(fr, new Comparator<App>() { public int compare(App x, App y) { return Float.compare(y.now(now), x.now(now)); } });
                if (!fr.isEmpty()) { rows.add("// FREQUENT"); rows.addAll(fr.subList(0, Math.min(5, fr.size()))); }
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
        count.setText("  · " + visible);
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

    final TextView.OnEditorActionListener openTop = new TextView.OnEditorActionListener() {
        public boolean onEditorAction(TextView v, int id, KeyEvent ev) {
            for (Object o : rows) if (o instanceof App) { launch((App) o); return true; }
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
        String[] base = {"App info", "Rename", hid ? "Unhide" : "Hide from list", "Reset launch count", "Uninstall"};
        String[] items = new String[sc.size() + base.length];
        for (int i = 0; i < sc.size(); i++) items[i] = "\u203A " + sc.get(i)[0];
        System.arraycopy(base, 0, items, sc.size(), base.length);
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle(a.label).setItems(items, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int w0) {
                if (w0 < sc.size()) { startShortcut(sc.get(w0)[1]); return; }
                int w = w0 - sc.size();
                if (w == 0) { try { la.startAppDetailsActivity(a.cn, a.user, null, null); } catch (Throwable ignored) { } }
                else if (w == 1) rename(a);
                else if (w == 2) { sp.edit().putBoolean("lh_" + a.key, !hid).apply(); filter(); }
                else if (w == 3) { sp.edit().remove("lc_" + a.key).remove("lf_" + a.key).remove("lt_" + a.key).apply(); a.launches = 0; a.score = 0; a.last = 0; filter(); }
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
        final EditText e = new EditText(this);
        e.setText(a.label); e.setSelectAllOnFocus(true); e.setSingleLine(true);
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("Rename").setView(e)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) {
                    String s = e.getText().toString().trim();
                    if (s.isEmpty()) sp.edit().remove("ln_" + a.key).apply(); else sp.edit().putString("ln_" + a.key, s).apply();
                    loadApps();
                } })
                .setNeutralButton("Original", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) {
                    sp.edit().remove("ln_" + a.key).apply(); loadApps();
                } })
                .setNegativeButton("Cancel", null).show();
    }

    final class AppAdapter extends BaseAdapter {
        public int getCount() { return rows.size(); }
        public Object getItem(int i) { return rows.get(i); }
        public long getItemId(int i) { return i; }
        public int getViewTypeCount() { return 2; }
        public int getItemViewType(int i) { return rows.get(i) instanceof App || rows.get(i) == SHOW_HIDDEN ? 0 : 1; }
        public boolean isEnabled(int i) { return rows.get(i) instanceof App || rows.get(i) == SHOW_HIDDEN; }
        public View getView(int i, View v, ViewGroup parent) {
            Object o = rows.get(i);
            TextView t = (TextView) v;
            if (o == SHOW_HIDDEN) {
                if (t == null) { t = tv("", 19, FG); t.setMinHeight(px(52)); t.setGravity(Gravity.CENTER_VERTICAL); t.setPadding(px(4), 0, px(8), 0); }
                t.setText(showHidden ? "\u2212 HIDE HIDDEN APPS" : "+ SHOW " + hiddenCount + " HIDDEN APP" + (hiddenCount > 1 ? "S" : ""));
                t.setTextColor(COMMENT);
                return t;
            }
            if (o instanceof App) {
                App a = (App) o;
                if (t == null) { t = tv("", 19, FG); t.setMinHeight(px(52)); t.setGravity(Gravity.CENTER_VERTICAL); t.setPadding(px(4), 0, px(8), 0); }
                boolean top = search.getText().length() > 0 && i == firstApp();
                t.setText(a.label.toUpperCase(Locale.ROOT) + (a.work ? "  · WORK" : "") + (hidden(a) ? "  · HIDDEN" : ""));
                t.setTextColor(top ? CYAN : FG);
            } else {
                if (t == null) { t = tv("", 13, COMMENT); t.setPadding(px(4), px(14), 0, px(4)); }
                t.setText((String) o);
                t.setTextColor(((String) o).startsWith("NO MATCH") ? COMMENT : BLUE);
            }
            return t;
        }
    }

    int firstApp() { for (int i = 0; i < rows.size(); i++) if (rows.get(i) instanceof App) return i; return -1; }
}
