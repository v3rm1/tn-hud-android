package com.tokyonight.hud.live;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Settings, built to Material 3 specs with framework views only (no AndroidX):
 * large top app bar, filled cards, list items with M3 switches, filled / tonal / outlined buttons,
 * filter chips, outlined text field, ripples, 48 dp targets, edge-to-edge, light + dark, optional dynamic colour.
 */
public class SetWallpaper extends Activity {
    // ---- M3 colour roles
    int primary, onPrimary, primaryC, onPrimaryC, secC, onSecC, error, surface, onSurface, onSurfaceVar,
            surfLow, surf, surfHigh, surfHighest, outline, outlineVar;
    float dp;
    boolean dark;
    SharedPreferences sp;
    LinearLayout col;
    TextView cityStatus, locStatus;
    final ArrayList<Runnable> onResumeHooks = new ArrayList<Runnable>();

    static final String[] MOD_NAME = {"Clock", "Weather", "Device", "Sky", "Sensors", "Calendar", "Search", "To-do", "Notes", "Media", "Widget"};
    static final String[] MOD_SUB = {"Time, date, next alarm", "Conditions, high/low, wind, humidity",
            "Battery, RAM, storage, diagnostics, linked devices, network", "Sky map, stars, Moon, planets, day length",
            "24 h cycle dial, magnetometer, barometer, light, motion graphs", "Day agenda, week and month; tap events to open them",
            "Search bar under the header; opens Google search, mic for voice",
            "(retired)", "Shortcuts: new Samsung note, new Keep note, open the apps",
            "Now playing (when the player broadcasts it) with previous / play-pause / next",
            "A real app widget (e.g. Samsung Music) hosted by the TN-HUD launcher; choose it with a long-press on home"};
    static final boolean[][] DEF_MODS = {
            {true, true, true, true, true, false, false, false, false, false, false}, {true, true, true, false, false, false, true, false, false, true, false},
            {true, true, false, true, true, false, false, false, false, false, false}, {true, true, false, false, false, true, false, false, true, false, false}};

    @Override protected void onCreate(Bundle b) {
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        setTheme(night ? android.R.style.Theme_Material_NoActionBar : android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(b);
        sp = getSharedPreferences("hud", MODE_PRIVATE);
        dp = getResources().getDisplayMetrics().density;
        build();
    }

    @Override protected void onResume() { super.onResume(); for (Runnable r : onResumeHooks) r.run(); }

    @Override public void onRequestPermissionsResult(int rc, String[] p, int[] r) { for (Runnable x : onResumeHooks) x.run(); }

    // ------------------------------------------------------------------ colour scheme
    void scheme() {
        dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        if (Build.VERSION.SDK_INT >= 31 && sp.getBoolean("dynamicColor", false) && dynamic()) return;
        if (dark) {   // tonal-spot scheme generated from seed #7AA2F7 (Tokyo Night blue)
            primary = 0xFFB7C6EE; onPrimary = 0xFF314060; primaryC = 0xFF435274; onPrimaryC = 0xFFD9E3FF;
            secC = 0xFF343B4D; onSecC = 0xFFB8BFD5; error = 0xFFFA746F; surface = 0xFF0D0E12; onSurface = 0xFFE3E5F0;
            onSurfaceVar = 0xFFA9ABB5; surfLow = 0xFF111318; surf = 0xFF17191F; surfHigh = 0xFF1D1F26; surfHighest = 0xFF23262D;
            outline = 0xFF73757F; outlineVar = 0xFF454850;
        } else {
            primary = 0xFF4A5E8B; onPrimary = 0xFFF9F8FF; primaryC = 0xFFB7CCFF; onPrimaryC = 0xFF2E436E;
            secC = 0xFFDBE2F9; onSecC = 0xFF4A5264; error = 0xFFA83836; surface = 0xFFFAF9FE; onSurface = 0xFF2F323A;
            onSurfaceVar = 0xFF5C5F68; surfLow = 0xFFF3F3FA; surf = 0xFFEDEDF6; surfHigh = 0xFFE7E7F1; surfHighest = 0xFFE1E2ED;
            outline = 0xFF787A84; outlineVar = 0xFFB0B1BC;
        }
    }

    /** Material You: map system tonal palettes to M3 roles (Android 12+) */
    boolean dynamic() {
        try {
            if (dark) {
                primary = sys("accent1_200"); onPrimary = sys("accent1_800"); primaryC = sys("accent1_700"); onPrimaryC = sys("accent1_100");
                secC = sys("accent2_700"); onSecC = sys("accent2_100"); surface = sys("neutral1_900"); onSurface = sys("neutral1_100");
                onSurfaceVar = sys("neutral2_200"); surfLow = blend(sys("neutral1_900"), sys("neutral1_800"), 0.25f);
                surf = blend(sys("neutral1_900"), sys("neutral1_800"), 0.5f); surfHigh = sys("neutral1_800");
                surfHighest = blend(sys("neutral1_800"), sys("neutral1_700"), 0.4f); outline = sys("neutral2_400"); outlineVar = sys("neutral2_700");
                error = 0xFFF2B8B5;
            } else {
                primary = sys("accent1_600"); onPrimary = sys("accent1_0"); primaryC = sys("accent1_100"); onPrimaryC = sys("accent1_900");
                secC = sys("accent2_100"); onSecC = sys("accent2_900"); surface = sys("neutral1_10"); onSurface = sys("neutral1_900");
                onSurfaceVar = sys("neutral2_700"); surfLow = sys("neutral1_10"); surf = sys("neutral1_50"); surfHigh = blend(sys("neutral1_50"), sys("neutral1_100"), 0.5f);
                surfHighest = sys("neutral1_100"); outline = sys("neutral2_500"); outlineVar = sys("neutral2_200"); error = 0xFFB3261E;
            }
            return true;
        } catch (Exception e) { return false; }
    }

    int sys(String n) throws Exception {
        int id = getResources().getIdentifier("system_" + n, "color", "android");
        if (id == 0) throw new Exception(n);
        return getColor(id);
    }

    static int blend(int a, int b, float t) {
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t), g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t),
                bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    static int withAlpha(int c, float a) { return (c & 0x00FFFFFF) | (Math.round(255 * a) << 24); }

    int px(float v) { return Math.round(v * dp); }

    // ------------------------------------------------------------------ screen
    void build() {
        scheme();
        onResumeHooks.clear();
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        int ui = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if (!dark) ui |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | (Build.VERSION.SDK_INT >= 26 ? 0x10 : 0);   // light nav bar
        getWindow().getDecorView().setSystemUiVisibility(ui);

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(surface);
        final FrameLayout host = new FrameLayout(this);
        root.addView(host, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        pageViews.clear(); pageCols.clear();

        page(host, "Home screen", "Dashboard, layout and weather");

        // ---- wallpaper
        LinearLayout c1 = card("Wallpaper", "Use TN-HUD on the home and lock screen. Choose “Home and lock screens” in the preview.");
        LinearLayout r1 = row();
        r1.addView(button("Apply wallpaper", 0, new View.OnClickListener() { public void onClick(View v) { applyWallpaper(); } }));
        c1.addView(r1);

        // ---- layout: tabbed switch + module matrix
        LinearLayout c2 = card("Layout", "Choose what appears on the single page and on each tab. A module can sit on several tabs; empty tabs are hidden.");
        c2.addView(switchItem("Tabbed views", "Tabs under the header switch between views. Off: one page.", "splitPages", true, null));
        divider(c2);
        for (int m = 0; m < MOD_NAME.length; m++) {
            if (m == 7) continue;                                  // retired: To-do
            LinearLayout block = new LinearLayout(this);
            block.setOrientation(LinearLayout.VERTICAL);
            block.setPadding(0, px(10), 0, px(10));
            block.addView(text(MOD_NAME[m], 16, onSurface, false));
            TextView s2 = text(MOD_SUB[m], 14, onSurfaceVar, false); s2.setPadding(0, px(2), 0, px(8));
            block.addView(s2);
            LinearLayout chips = new LinearLayout(this);
            chips.setOrientation(LinearLayout.HORIZONTAL);
            String[] cn = {"Single", "Tab 1", "Tab 2", "Tab 3"};
            for (int cc = 0; cc < 4; cc++) chips.addView(chip(cn[cc], "mod_" + cc + "_" + m, DEF_MODS[cc][m]));
            block.addView(chips);
            c2.addView(block);
        }
        TextView note = text("Modules that don’t fit are skipped and counted at the bottom of the page. Removed Samsung’s favourites bar? Turn on “No dock” under Display for more room.", 12, onSurfaceVar, false);
        note.setPadding(0, px(8), 0, 0);
        c2.addView(note);

        // ---- arrange: per-view order with live preview
        LinearLayout ca = card("Arrange", "Drag \u2261 to change the order of the modules under the header. Each view keeps its own order.");
        LinearLayout vrow = new LinearLayout(this);
        vrow.setOrientation(LinearLayout.HORIZONTAL);
        final String[] vn = {"Single", "Tab 1", "Tab 2", "Tab 3"};
        final TextView[] vchips = new TextView[4];
        final LinearLayout homeList = new LinearLayout(this);
        homeListV = homeList;
        homeList.setOrientation(LinearLayout.VERTICAL);
        homePrev = new PreviewView(this, false);
        for (int v = 0; v < 4; v++) {
            final int fv = v;
            vchips[v] = text(vn[v], 14, onSurfaceVar, true);
            vchips[v].setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, px(32), 1f);
            lp.rightMargin = px(8); lp.topMargin = px(4); lp.bottomMargin = px(8);
            vrow.addView(vchips[v], lp);
            vchips[v].setOnClickListener(new View.OnClickListener() { public void onClick(View x) {
                editView = fv; paintViewChips(vchips); fillHomeList(homeList); homePrev.reload(); } });
        }
        paintViewChips(vchips);
        ca.addView(vrow);
        ca.addView(previewFrame(homePrev));
        ca.addView(homeList);
        fillHomeList(homeList);
        LinearLayout ra = row();
        ra.addView(button("Reset order", 2, new View.OnClickListener() { public void onClick(View x) {
            sp.edit().remove("order_" + editView).apply(); fillHomeList(homeList); } }));
        ca.addView(ra);

        // ---- taps
        LinearLayout ck = card("Taps", "The HUD sits under your icons, so taps can land on it by accident. Taps are ignored after you swipe up to the app drawer, until you swipe down, go back or go home.");
        ck.addView(switchItem("HUD taps", "Off: the wallpaper ignores touches completely (view tabs too).", "hudTaps", true, null));
        ck.addView(switchItem("Haptic feedback", "A light tick when a HUD tap does something.", "haptic", true, null));
        ck.addView(chipItem("System shortcuts", "Battery, storage, RAM clean, devices and network open Settings or Device care.", "sysTaps", 2,
                new int[]{0, 1, 2}, new String[]{"Off", "Tap", "Double tap"}));

        // ---- shortcut row
        LinearLayout cs = card("Shortcuts", "Up to 4 text blocks above the dock area (or above the readouts with \u201cNo dock\u201d). One tap opens the app. Tap a row to change the app or its label; drag \u2261 to reorder.");
        cs.addView(switchItem("Shortcut row", null, "scOn", true, null));
        final LinearLayout scList = new LinearLayout(this);
        scList.setOrientation(LinearLayout.VERTICAL);
        cs.addView(scList);
        final LinearLayout scActs = row();
        cs.addView(scActs);
        fillShortcuts(scList, scActs);

        // ---- weather & location
        LinearLayout c3 = card("Weather & location", null);
        final EditText city = outlinedField(c3, "City", sp.getString("cityQuery", ""));
        LinearLayout r3 = row();
        r3.addView(button("Set city", 1, new View.OnClickListener() { public void onClick(View v) { geocode(city.getText().toString().trim()); } }));
        c3.addView(r3);
        cityStatus = text(sp.contains("city") ? "Saved: " + sp.getString("city", "") : "No city set", 14, onSurfaceVar, false);
        cityStatus.setPadding(px(4), px(8), 0, px(8));
        c3.addView(cityStatus);
        c3.addView(switchItem("Tap weather to open an app", "Off: tapping the weather shows the next 12 hours (temperature and chance of rain) for 20 s.", "wxTap", false, null));
        c3.addView(wxAppItem());
        divider(c3);
        c3.addView(switchItem("Live location", "Approximate location, passive first; the saved city is the fallback.", "liveloc", false,
                new Runnable() { public void run() { if (sp.getBoolean("liveloc", false)) requestPermissions(new String[]{"android.permission.ACCESS_COARSE_LOCATION"}, 3); } }));
        locStatus = text("", 12, onSurfaceVar, false);
        locStatus.setPadding(px(4), px(4), 0, 0);
        c3.addView(locStatus);
        onResumeHooks.add(new Runnable() { public void run() { updateLocStatus(); } });

        // ---- display
        LinearLayout c4 = card("Display", null);
        c4.addView(switchItem("Night dimming", "Fade the HUD between sunset and the end of civil twilight, then hold.", "nightDim", true, null));
        c4.addView(sliderItem("Dimming strength", "nightDimPct", 40, 30, 70, 5));
        divider(c4);
        c4.addView(switchItem("No dock", "You removed the favourites bar: modules use its space and the HUD readouts sit at the bottom.", "noDock", false, null));
        divider(c4);
        c4.addView(chipItem("Red night mode", "Deep red HUD that keeps your eyes dark-adapted outdoors. Applies to home, lock screen and screen saver.", "redMode", 0,
                new int[]{0, 1, 2, 3}, new String[]{"Off", "Night", "Aurora", "Always"}));
        c4.addView(body("Night: sun more than 6° below the horizon. Aurora: only when the aurora verdict is GO and it's dark."));

        // ---- automatic views
        LinearLayout cv = card("Automatic views", "Switch to the tab that has the module, when tabbed views are on. Tapping a tab yourself wins until the screen turns off. Checked in this order:");
        cv.addView(switchItem("Headphones \u2192 Media", "Bluetooth, wired or USB headphones connected.", "avHead", false, null));
        cv.addView(switchItem("Charging \u2192 Device", null, "avCharge", false, null));
        cv.addView(switchItem("After dark \u2192 Sky", "Sun more than 6° below the horizon.", "avNight", false, null));
        cv.addView(switchItem("Morning \u2192 Calendar", "05:00\u201310:00.", "avMorning", false, null));
        c4.addView(switchItem("Live sensors", "Magnetometer, barometer, light, motion graphs. Only while the Sensors module is on screen.", "motion", true, null));
        divider(c4);
        c4.addView(textSizeItem("textScale", 0, new int[]{0, 85, 100, 115, 125}, "System"));

        // ================================================================ launcher
        page(host, "Launcher", "Home app, app drawer, gestures and glyphs");
        LinearLayout cln = card("Home app", "Use the HUD as your home app: it owns the touches (no icons in the way), swipe left / right changes tabs, swipe up opens the app drawer with search, long-press for the menu. The lock screen stays the live wallpaper, so keep TN-HUD set as wallpaper too.");
        final TextView lnState = text("", 14, onSurfaceVar, true);
        cln.addView(lnState);
        onResumeHooks.add(new Runnable() { public void run() {
            Intent hi = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            android.content.pm.ResolveInfo ri = getPackageManager().resolveActivity(hi, 0);
            boolean ours = ri != null && ri.activityInfo != null && getPackageName().equals(ri.activityInfo.packageName);
            lnState.setText(ours ? "\u2713 TN-HUD is your home app" : "Not the home app");
            lnState.setTextColor(ours ? primary : onSurfaceVar);
        } });
        LinearLayout rln = row();
        rln.addView(button("Choose home app", 1, new View.OnClickListener() { public void onClick(View v) {
            try { startActivity(new Intent(android.provider.Settings.ACTION_HOME_SETTINGS)); }
            catch (Exception e) { try { startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS)); } catch (Exception ignored) { } }
        } }));
        cln.addView(rln);

        // ---- app drawer
        LinearLayout cdr = card("App drawer", "Swipe up (by default). The search field is focused with the keyboard up; Enter opens the top match.");
        cdr.addView(chipItem("Style", null, "lnStyle", 0, new int[]{0, 1, 2, 3}, new String[]{"Text", "Icon + text", "Grid", "Icons only"}));
        cdr.addView(chipItem("Grid columns", "Grid and Icons only.", "lnCols", 4, new int[]{4, 5, 6}, new String[]{"4", "5", "6"}));
        cdr.addView(switchItem("TN-HUD icon frames", "The icon pack's octagon frame, applied here, so TN-HUD-Icons isn't needed for the drawer.", "lnIconFrame", true, null));
        cdr.addView(chipItem("Icon colour", "Neon redraws each icon as glowing tubes in its own colours; the frame keeps its colour.", "lnIconTint", 0, new int[]{0, 1, 2}, new String[]{"Original", "Mono", "Neon"}));
        divider(cdr);
        cdr.addView(switchItem("Frequent apps", "Top 5 at the start of the drawer, counted on this phone.", "lnFrequent", true, null));
        cdr.addView(switchItem("Search finds hidden apps", null, "lnSearchHidden", true, null));
        LinearLayout rln2 = row();
        rln2.addView(button("Unhide all apps", 2, new View.OnClickListener() { public void onClick(View v) {
            SharedPreferences.Editor e = sp.edit();
            for (String k : sp.getAll().keySet()) if (k.startsWith("lh_")) e.remove(k);
            e.apply();
        } }));
        cdr.addView(rln2);
        divider(cdr);
        final TextView packState = text("", 14, onSurfaceVar, false);
        packState.setPadding(0, px(8), 0, px(4));
        cdr.addView(packState);
        final Runnable packPaint = new Runnable() { public void run() {
            String pk = sp.getString("lnIconPack", ""), name = null;
            for (String[] p : IconPacks.list(SetWallpaper.this)) if (p[1].equals(pk)) name = p[0];
            packState.setText("Icon pack: " + (pk.isEmpty() ? "none (apps' own icons)" : name == null ? pk + " (not installed)" : name)
                    + ". Frame and colour apply on top; long-press an app for \u201cIcon: keep the original\u201d.");
        } };
        onResumeHooks.add(packPaint);
        LinearLayout rpk = row();
        rpk.addView(button("Choose icon pack", 1, new View.OnClickListener() { public void onClick(View v) {
            final java.util.List<String[]> L = IconPacks.list(SetWallpaper.this);
            String[] names = new String[L.size() + 1];
            names[0] = "None";
            int cur = 0;
            String pk = sp.getString("lnIconPack", "");
            for (int i = 0; i < L.size(); i++) { names[i + 1] = L.get(i)[0]; if (L.get(i)[1].equals(pk)) cur = i + 1; }
            glass().list("Icon pack", names, cur, new Glass.Pick() { public void on(int w) {
                sp.edit().putString("lnIconPack", w == 0 ? "" : L.get(w - 1)[1]).apply();
                packPaint.run();
            } }).show();
        } }));
        cdr.addView(rpk);

        // ---- pull-down, quick toggles, tiles
        LinearLayout csh = card("Panel everywhere", "The HUD panel (notifications, toggles, media) over any app, and quick toggles that really switch.");
        final TextView shState = text("", 14, onSurfaceVar, false);
        shState.setPadding(0, px(4), 0, px(4));
        csh.addView(shState);
        LinearLayout rsh = row();
        rsh.addView(button("Accessibility settings", 1, new View.OnClickListener() { public void onClick(View v) {
            try { startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)); } catch (Exception ignored) { }
        } }));
        csh.addView(rsh);
        csh.addView(switchItem("Pull-down strip", "Drag down from the top edge of any app. Needs \u201cTN-HUD pull-down\u201d switched on in accessibility settings; it reads no screen content.", "shOn", true, null));
        csh.addView(chipItem("Pull-down area", "The rest of the top edge still opens Android's own shade.", "shEdge", 0, new int[]{0, 1, 2}, new String[]{"Left half", "Whole top", "Right half"}));
        csh.addView(switchItem("Show the pull-down area", "Tints the strip cyan, to check where it sits.", "shShow", false, null));
        divider(csh);
        final TextView wsState = text("", 14, onSurfaceVar, false);
        wsState.setPadding(0, px(8), 0, px(4));
        csh.addView(wsState);
        LinearLayout rws = row();
        rws.addView(button("Allow changing settings", 1, new View.OnClickListener() { public void onClick(View v) {
            try { startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:" + getPackageName()))); } catch (Exception ignored) { }
        } }));
        csh.addView(rws);
        final TextView ssState = text("", 14, onSurfaceVar, false);
        ssState.setPadding(0, px(8), 0, px(4));
        csh.addView(ssState);
        TextView adb = text(Toggles.ADB_GRANT, 12, onSurface, false);
        adb.setTypeface(Typeface.MONOSPACE);
        adb.setTextIsSelectable(true);
        csh.addView(adb);
        LinearLayout rss = row();
        rss.addView(button("Copy adb command", 2, new View.OnClickListener() { public void onClick(View v) {
            try {
                ((android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(android.content.ClipData.newPlainText("adb", Toggles.ADB_GRANT));
                android.widget.Toast.makeText(SetWallpaper.this, "Copied", android.widget.Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) { }
        } }));
        csh.addView(rss);
        divider(csh);
        TextView tiles = text("Quick settings tiles: open Android's quick settings, tap the edit (pencil) button and drag in \u201cHUD panel\u201d and \u201cHUD launcher\u201d.", 14, onSurfaceVar, false);
        tiles.setPadding(0, px(8), 0, px(8));
        csh.addView(tiles);
        onResumeHooks.add(new Runnable() { public void run() {
            boolean on = ShadeService.instance != null;
            shState.setText(on ? "\u2713 TN-HUD pull-down is on" : "TN-HUD pull-down is off (accessibility settings \u203A installed apps). On Android 13+, a sideloaded app may first need App info \u203A \u22EE \u203A Allow restricted settings.");
            shState.setTextColor(on ? primary : onSurfaceVar);
            boolean w = Toggles.canWrite(SetWallpaper.this), sc = Toggles.canSecure(SetWallpaper.this);
            wsState.setText((w ? "\u2713 " : "") + "Auto-rotate, adaptive brightness and the brightness slider" + (w ? " are on." : " need \u201cModify system settings\u201d."));
            wsState.setTextColor(w ? primary : onSurfaceVar);
            ssState.setText((sc ? "\u2713 Battery saver and location toggles are on." : "Battery saver and location need a one-time grant from a computer (USB debugging on):"));
            ssState.setTextColor(sc ? primary : onSurfaceVar);
        } });

        // ---- gestures
        LinearLayout cgs = card("Gestures", "Notifications and quick settings open Android's own panels; not every phone lets apps do that, in which case the swipe from the status bar still works.");
        for (int gi = 0; gi < Gestures.KEYS.length; gi++) cgs.addView(gestureItem(gi));

        // ---- glyphs
        LinearLayout cg = card("Glyphs", "Draw a letter on the home screen to run an action. Bent strokes are read as glyphs, straight ones stay swipes. The letters work out of the box; \u201cTeach\u201d adds your own handwriting (up to 5 samples each).");
        cg.addView(switchItem("Glyph gestures", null, "lnGlyphs", true, null));
        cg.addView(switchItem("Show the trail", "Neon trace of your finger and the recognised glyph.", "lnTrail", true, null));
        divider(cg);
        for (String gl : Glyph.NAMES) cg.addView(glyphItem(gl));

        // ================================================================ lock
        page(host, "Lock screen", "Space weather, sky events and tiles");
        LinearLayout cl = card("Lock screen", "Aurora verdict, Kp trace, solar wind, X-ray flares and NOAA scales; ISS, Tiangong and Starlink-train passes; twilight, darkness, meteors, moon; time scales. Sky events need a city or live location (Home screen tab).");
        cl.addView(switchItem("Motion alert", "Flag movement after the phone has been still for 3 s.", "lockMotion", true, null));
        divider(cl);
        final TextView dataStatus = text("", 14, onSurfaceVar, false);
        dataStatus.setPadding(0, px(8), 0, px(4));
        cl.addView(dataStatus);
        onResumeHooks.add(new Runnable() { public void run() { dataStatus.setText(dataStatusText()); } });

        LinearLayout ct = card("Arrange tiles", "Switch tiles on or off and drag \u2261 to reorder; they flow two per row under the ring.");
        lockPrev = new PreviewView(this, true);
        ct.addView(previewFrame(lockPrev));
        final LinearLayout lockList = new LinearLayout(this);
        lockList.setOrientation(LinearLayout.VERTICAL);
        ct.addView(lockList);
        fillLockList(lockList);
        LinearLayout rt = row();
        rt.addView(button("Reset tiles", 2, new View.OnClickListener() { public void onClick(View x) {
            SharedPreferences.Editor e = sp.edit().remove("lkOrder");
            for (int i = 0; i < HudRenderer.LK_DEF.length; i++) e.remove("lkOn_" + i);
            e.apply(); fillLockList(lockList); } }));
        ct.addView(rt);
        divider(ct);
        ct.addView(switchItem("Clock & weather header", "The home-screen header on the lock screen. Hide Samsung's own clock with Good Lock ClockFace (blank clock).", "lkClock", true, null));
        ct.addView(switchItem("Tonight timeline", "Next 12 h: twilight depth, Moon above the horizon, cloud cover, visible passes.", "lkNight", true, null));
        ct.addView(switchItem("Kp trace", "6-hour Kp seismograph left of the ring.", "lkKp", true, null));
        ct.addView(switchItem("Solar wind panel", "Speed, Bz, X-ray class and NOAA scales right of the ring.", "lkWind", true, null));
        ct.addView(switchItem("Time scales", "UTC, TAI, GPS, Unix, JD, sidereal and sundial time.", "lkTime", true, null));
        ct.addView(switchItem("Charging row", "Power, percentage and time to full while charging.", "lkCharge", true, null));
        divider(ct);
        ct.addView(textSizeItem("textScaleLock", -1, new int[]{-1, 85, 100, 115, 125}, "Home"));

        // ---- alerts
        LinearLayout cal = card("Alerts", "Notifications, checked every 15 minutes in the background (needs a connection). Satellite alerts arrive about 10 minutes before the visible part of the pass; Android may delay them a few minutes to save battery.");
        Runnable job = new Runnable() { public void run() { Alerts.ensureJob(SetWallpaper.this); } };
        cal.addView(switchItem("Aurora GO", "Once per night at most, when Kp, Bz, darkness and clouds line up.", "alAurora", false, job));
        cal.addView(switchItem("Geomagnetic storms", "NOAA G2 or stronger.", "alStorm", false, job));
        cal.addView(switchItem("ISS & Tiangong passes", "Visible passes only.", "alPass", false, job));
        cal.addView(switchItem("Starlink trains", "Freshly launched, still bunched up.", "alStarlink", false, job));
        divider(cal);
        cal.addView(switchItem("Quiet hours", null, "alQuiet", true, null));
        cal.addView(chipItem("Quiet from", null, "alQuietFrom", 23, new int[]{21, 22, 23, 0}, new String[]{"21:00", "22:00", "23:00", "00:00"}));
        cal.addView(chipItem("Quiet until", null, "alQuietTo", 7, new int[]{5, 6, 7, 8}, new String[]{"05:00", "06:00", "07:00", "08:00"}));
        final TextView alNote = text("", 14, error, false);
        cal.addView(alNote);
        onResumeHooks.add(new Runnable() { public void run() {
            boolean need = Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != 0;
            alNote.setText(need ? "Notifications are not allowed: see the Permissions tab." : "");
            alNote.setVisibility(need ? View.VISIBLE : View.GONE);
        } });
        LinearLayout ra2 = row();
        ra2.addView(button("Send test alert", 2, new View.OnClickListener() { public void onClick(View v) {
            Alerts.notify(SetWallpaper.this, Alerts.N_TEST, "TN-HUD test alert", "Sky alerts will look like this.");
        } }));
        cal.addView(ra2);

        // ================================================================ screen saver
        page(host, "Screen saver", "GNSS receiver and deep space, while charging");
        // ---- screen saver
        LinearLayout c4b = card("TN-HUD · GNSS", "Charging-only HUD: a live sky plot of every navigation satellite in view (GPS, Galileo, GLONASS, BeiDou, QZSS), their signal strengths, dual-band status, and the Voyagers' distance and light-time. GPS runs only while it is showing.");
        c4b.addView(body("Needs precise location to read satellite status, and possibly “all the time”: Android may treat a screen saver as a background app. Both are on the Permissions tab."));
        LinearLayout r4p = row();
        r4p.addView(button("Permissions", 2, new View.OnClickListener() { public void onClick(View v) { select(4); } }));
        c4b.addView(r4p);
        LinearLayout r4b = row();
        r4b.addView(button("Screen saver settings", 1, new View.OnClickListener() { public void onClick(View v) {
            try { startActivity(new Intent(android.provider.Settings.ACTION_DREAM_SETTINGS)); }
            catch (Exception e) { try { startActivity(new Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS)); } catch (Exception ignored) { } }
        } }));
        c4b.addView(r4b);
        c4b.addView(body("Then pick “TN-HUD · GNSS”. Samsung: Settings › Display › Screen saver."));
        divider(c4b);
        c4b.addView(textSizeItem("textScaleDream", -1, new int[]{-1, 85, 100, 115, 125}, "Home"));

        // ================================================================ permissions
        page(host, "Permissions", "Everything is optional; modules show a hint when access is missing");
        LinearLayout pl = card("Location", "Weather for where you are, sky events (aurora, twilight, passes) and the screen saver's satellite view.");
        pl.addView(permRow("Approximate", "Live location for weather and sky events.", new String[]{P_COARSE}, P_COARSE, 3));
        pl.addView(permRow("Precise", "Screen saver: satellite status from the GNSS receiver.", new String[]{P_FINE, P_COARSE}, P_FINE, 7));
        if (Build.VERSION.SDK_INT >= 29)
            pl.addView(permRow("All the time", "The wallpaper and screen saver run in the background. Android opens its own page for this one.",
                    new String[]{"android.permission.ACCESS_BACKGROUND_LOCATION"}, "android.permission.ACCESS_BACKGROUND_LOCATION", 4));
        // ---- companion: notification access lives in a separate app
        LinearLayout lk = card("Notification link", "Badges, track info from any player (Samsung Music too), media controls, the HUD notification panel and Do Not Disturb need notification access. Play Protect blocks that for sideloaded apps, so it lives in a tiny companion, TN-HUD Link, which only talks to this app (same signing key) and has no internet access.");
        final TextView lkState = text("", 14, onSurfaceVar, true);
        lk.addView(lkState);
        onResumeHooks.add(new Runnable() { public void run() {
            boolean inst = Link.installed(SetWallpaper.this);
            if (inst) Link.send(SetWallpaper.this, "refresh", null, 0);
            String st = !inst ? "TN-HUD Link not installed"
                    : Link.live() ? "\u2713 Linked \u00B7 last update " + ago(System.currentTimeMillis() - Link.at) + " ago"
                    : Link.at > 0 && !Link.access ? "Installed \u00B7 notification access not granted" : "Installed \u00B7 waiting for it to report";
            lkState.setText(st);
            lkState.setTextColor(Link.live() ? primary : inst ? error : onSurfaceVar);
        } });
        LinearLayout rlk = row();
        rlk.addView(button("Open TN-HUD Link", 1, new View.OnClickListener() { public void onClick(View v) {
            Intent i = getPackageManager().getLaunchIntentForPackage(Link.PKG);
            if (i != null) startActivity(i);
            else { try { startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/v3rm1/tn-hud-android/releases"))); } catch (Exception ignored) { } }
        } }));
        lk.addView(rlk);
        lk.addView(body("Install: adb install TN-HUD-Link.apk from a computer, or through Shizuku. Installed from a browser or file manager, Android greys out notification access: App info \u203A \u22EE \u203A Allow restricted settings, then grant it in TN-HUD Link."));
        lk.addView(switchItem("Badges", "Unread counts in the app drawer and on the shortcut row.", "lkBadges", true, null));

        if (Build.VERSION.SDK_INT >= 33) {
            LinearLayout pn = card("Notifications", null);
            pn.addView(permRow("Notifications", "Sky alerts (aurora, storms, satellite passes).", new String[]{"android.permission.POST_NOTIFICATIONS"}, "android.permission.POST_NOTIFICATIONS", 8));
        }
        LinearLayout pd = card("Data on this phone", null);
        pd.addView(permRow("Calendar", "Calendar module and next-event tile.", new String[]{"android.permission.READ_CALENDAR"}, "android.permission.READ_CALENDAR", 6));
        pd.addView(permRow("Termux commands", "Launcher pop-up: \u201c$ command\u201d runs in Termux. Termux also needs allow-external-apps = true in ~/.termux/termux.properties.", new String[]{"com.termux.permission.RUN_COMMAND"}, "com.termux.permission.RUN_COMMAND", 10));
        pd.addView(permRow("Contacts", "Launcher command line: \"> call\" and \"> sms\" by contact name.", new String[]{"android.permission.READ_CONTACTS"}, "android.permission.READ_CONTACTS", 9));
        if (Build.VERSION.SDK_INT >= 29)
            pd.addView(permRow("Physical activity", "Step count on the lock screen.", new String[]{"android.permission.ACTIVITY_RECOGNITION"}, "android.permission.ACTIVITY_RECOGNITION", 5));
        if (Build.VERSION.SDK_INT >= 31)
            pd.addView(permRow("Nearby devices", "Battery levels of connected Bluetooth devices.", new String[]{"android.permission.BLUETOOTH_CONNECT"}, "android.permission.BLUETOOTH_CONNECT", 1));
        LinearLayout ps = card("Always granted", "Internet and network state (weather, NOAA, CelesTrak) are install-time permissions and can't be switched off per app.");
        LinearLayout rs = row();
        rs.addView(button("System app settings", 2, new View.OnClickListener() { public void onClick(View v) { appSettings(); } }));
        ps.addView(rs);
        if (Build.VERSION.SDK_INT >= 33)
            ps.addView(body("Revoking from here restarts TN-HUD (the wallpaper reloads by itself): Android only removes an app's own permission when its process stops."));

        // ================================================================ about
        page(host, "About", "Appearance, privacy and credits");

        // ---- appearance
        if (Build.VERSION.SDK_INT >= 31) {
            LinearLayout c6 = card("Appearance", "Text sizes live on the Home screen, Lock screen and Screen saver tabs.");
            c6.addView(switchItem("Dynamic colour", "Use your system Material You colours for this screen.", "dynamicColor", false,
                    new Runnable() { public void run() { build(); } }));
        }

        // ---- backup
        LinearLayout cb = card("Backup", "Save every setting (layouts, tile order, shortcuts, sizes, taps, alerts) to a file, or load one. Your city and location are left out, so a file is safe to share with friends.");
        backupStatus = text("", 14, onSurfaceVar, false);
        cb.addView(backupStatus);
        LinearLayout rb = row();
        rb.addView(button("Export", 1, new View.OnClickListener() { public void onClick(View v) {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json")
                    .putExtra(Intent.EXTRA_TITLE, "tn-hud-settings.json");
            try { startActivityForResult(i, RC_EXPORT); } catch (Exception e) { backupStatus.setText("No file picker available."); }
        } }));
        rb.addView(button("Import", 2, new View.OnClickListener() { public void onClick(View v) {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            try { startActivityForResult(i, RC_IMPORT); } catch (Exception e) { backupStatus.setText("No file picker available."); }
        } }));
        rb.addView(button("Share", 2, new View.OnClickListener() { public void onClick(View v) {
            try {
                Intent i = new Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_TEXT, Backup.export(sp))
                        .putExtra(Intent.EXTRA_SUBJECT, "TN-HUD settings");
                startActivity(Intent.createChooser(i, "Share TN-HUD settings"));
            } catch (Exception e) { backupStatus.setText("Could not share: " + e.getMessage()); }
        } }));
        cb.addView(rb);

        // ---- diagnostics
        LinearLayout cdg = card("Diagnostics", null);
        final TextView dg = text("", 14, onSurfaceVar, false);
        dg.setTypeface(Typeface.MONOSPACE);
        dg.setTextIsSelectable(true);
        cdg.addView(dg);
        onResumeHooks.add(new Runnable() { public void run() { dg.setText(diagText()); } });
        LinearLayout rdg = row();
        rdg.addView(button("Refresh", 2, new View.OnClickListener() { public void onClick(View v) { dg.setText(diagText()); } }));
        rdg.addView(button("Copy report", 2, new View.OnClickListener() { public void onClick(View v) {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("TN-HUD diagnostics", diagText()));
            dg.setText(diagText() + "\n\nCopied.");
        } }));
        cdg.addView(rdg);

        // ---- about
        LinearLayout c7 = card("About & privacy", null);
        c7.addView(body("Network use: weather and cloud cover from Open-Meteo (rounded coordinates or your city), city search via Open-Meteo, and place names via Android’s geocoder. Space weather (Kp, solar wind, X-ray flux, NOAA scales) from NOAA SWPC every 15–30 min while the HUD is visible, and orbital elements for the ISS, Tiangong and recent launches from CelesTrak once a day. Those two receive only your IP address, never your location: aurora, twilight, meteors, moon and satellite passes are computed on the phone. No analytics, no accounts. Calendar, steps, sensors, GNSS, Bluetooth and settings never leave the phone."));
        c7.addView(body("On the home screen, taps work like widgets: clock \u2192 alarms, events \u2192 calendar, + \u2192 new event, search \u2192 Google; weather \u2192 weather app if switched on; battery / storage / diagnostics \u2192 settings, devices \u2192 Bluetooth, network \u2192 internet panel as set under Taps (off, tap or double tap). Chips switch views: UTC, DAY / WEEK / MONTH, NOW / 22:00 / PLANETS; the 24 h dial toggles Sun / Moon and the motion graphs 15 s / 60 s."));
        c7.addView(body("Credits: weather Open-Meteo.com (CC BY 4.0). Star data d3-celestial © Olaf Frohn (BSD-3-Clause), Hipparcos / Yale Bright Star. Clock font Share Tech Mono © Carrois Type Design (SIL OFL 1.1, licence bundled). Space weather: NOAA Space Weather Prediction Center (public domain). Orbital elements: CelesTrak. SGP4 after Vallado et al. (2006). Voyager distances: linear model calibrated to NASA/JPL figures."));

        // M3 navigation bar
        final LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setBackgroundColor(surf);
        String[] lab = {"Home", "Launcher", "Lock", "Dream", "Permissions", "About"};
        int[] icon = {0, 5, 1, 2, 3, 4};
        navItems.clear();
        for (int i = 0; i < lab.length; i++) {
            final int k = i;
            View it = navItem(lab[i], icon[i], new View.OnClickListener() { public void onClick(View v) { select(k); } });
            nav.addView(it, new LinearLayout.LayoutParams(0, px(80), 1f));
        }
        root.addView(nav, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            public WindowInsets onApplyWindowInsets(View v, WindowInsets in) {
                for (LinearLayout c : pageCols) c.setPadding(px(16), in.getSystemWindowInsetTop(), px(16), px(24));
                nav.setPadding(0, 0, 0, in.getSystemWindowInsetBottom());
                return in;
            }
        });
        if (prefL != null) sp.unregisterOnSharedPreferenceChangeListener(prefL);
        prefL = new SharedPreferences.OnSharedPreferenceChangeListener() {
            public void onSharedPreferenceChanged(SharedPreferences p, String k) {
                if (homePrev != null) homePrev.reload();
                if (lockPrev != null) lockPrev.reload();
                if (k != null && k.startsWith("mod_") && homeListV != null) fillHomeList(homeListV);
            }
        };
        sp.registerOnSharedPreferenceChangeListener(prefL);
        setContentView(root);
        if (sp.getInt("uiTabV", 0) < 2) {                          // v36 inserted the Launcher tab at 1
            int t = sp.getInt("uiTab", 0); sp.edit().putInt("uiTab", t >= 1 ? t + 1 : t).putInt("uiTabV", 2).apply();
        }
        select(Math.max(0, Math.min(pageViews.size() - 1, sp.getInt("uiTab", 0))));
        for (Runnable r : onResumeHooks) r.run();
    }

    // ------------------------------------------------------------------ arrange (v22)
    PreviewView homePrev, lockPrev;
    LinearLayout homeListV;
    int editView;
    SharedPreferences.OnSharedPreferenceChangeListener prefL;
    static final String[] CMOD_SUB = {"", "", "Battery, RAM, storage, diagnostics", "Star map, Moon, planets", "24 h dial, sensor lab",
            "Day / week / month", "Google search bar", "", "Samsung Notes and Keep shortcuts", "Media controls", "Hosted app widget (launcher only)"};

    void paintViewChips(TextView[] c) {
        for (int v = 0; v < 4; v++) {
            boolean on = v == editView;
            c[v].setText(on ? "✓ " + (v == 0 ? "Single" : "Tab " + v) : (v == 0 ? "Single" : "Tab " + v));
            c[v].setTextColor(on ? onSecC : onSurfaceVar);
            c[v].setBackground(ripple(shape(on ? secC : Color.TRANSPARENT, 8, outlineVar, on ? 0 : 1), 8, onSurface));
        }
    }

    static int[] ints(String csv) {
        if (csv == null || csv.isEmpty()) return null;
        String[] p = csv.split(",");
        int[] o = new int[p.length]; int n = 0;
        for (String q : p) try { o[n++] = Integer.parseInt(q.trim()); } catch (Exception ignored) { }
        return java.util.Arrays.copyOf(o, n);
    }

    static String csv(int[] a) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < a.length; i++) { if (i > 0) b.append(','); b.append(a[i]); }
        return b.toString();
    }

    void fillHomeList(final LinearLayout list) {
        list.removeAllViews();
        final int v = editView;
        int[] ord = HudRenderer.cleanOrder(ints(sp.getString("order_" + v, null)), HudRenderer.DEF_ORDER);
        for (final int m : ord) {
            boolean on = sp.getBoolean("mod_" + v + "_" + m, DEF_MODS[v][m]);
            View r = dragRow(MOD_NAME[m], on ? CMOD_SUB[m] : "Off in this view · turn on under Layout", on, null, m);
            list.addView(r);
        }
        reorderable(list, new Runnable() { public void run() {
            int[] o = new int[list.getChildCount()];
            for (int i = 0; i < o.length; i++) o[i] = (Integer) list.getChildAt(i).getTag();
            sp.edit().putString("order_" + v, csv(o)).apply();
        } });
    }

    void fillLockList(final LinearLayout list) {
        list.removeAllViews();
        int[] ord = HudRenderer.cleanOrder(ints(sp.getString("lkOrder", null)), HudRenderer.LK_DEF);
        for (final int id : ord) list.addView(dragRow(HudRenderer.LK_NAME[id], null, true, "lkOn_" + id, id));
        reorderable(list, new Runnable() { public void run() {
            int[] o = new int[list.getChildCount()];
            for (int i = 0; i < o.length; i++) o[i] = (Integer) list.getChildAt(i).getTag();
            sp.edit().putString("lkOrder", csv(o)).apply();
        } });
    }

    /** list row: [text][optional switch][drag handle]; tag = item id */
    View dragRow(String title, String sub, boolean enabled, String switchKey, int id) {
        LinearLayout r;
        if (switchKey != null) r = (LinearLayout) switchItem(title, sub, switchKey, switchKey.startsWith("lkOn_") ? HudRenderer.LK_ON_DEF[id] : true, null);
        else {
            r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setMinimumHeight(px(sub == null ? 56 : 64));
            r.setPadding(0, px(6), 0, px(6));
            LinearLayout tx = new LinearLayout(this); tx.setOrientation(LinearLayout.VERTICAL);
            tx.addView(text(title, 16, enabled ? onSurface : onSurfaceVar, false));
            if (sub != null && !sub.isEmpty()) tx.addView(text(sub, 14, onSurfaceVar, false));
            r.addView(tx, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        r.setBackgroundColor(surf);
        TextView hd = text("≡", 24, onSurfaceVar, false);
        hd.setGravity(Gravity.CENTER);
        hd.setContentDescription("Drag to reorder " + title);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(px(48), px(48)); hl.leftMargin = px(8);
        r.addView(hd, hl);
        r.setTag(id);
        hd.setTag("handle");
        return r;
    }

    /** drag-and-drop reordering: the handle starts a platform drag; rows move live under the finger */
    void reorderable(final LinearLayout list, final Runnable save) {
        for (int i = 0; i < list.getChildCount(); i++) {
            final View row = list.getChildAt(i);
            View hd = row.findViewWithTag("handle");
            hd.setOnTouchListener(new View.OnTouchListener() {
                public boolean onTouch(View h, android.view.MotionEvent e) {
                    if (e.getActionMasked() != android.view.MotionEvent.ACTION_DOWN) return false;
                    row.startDrag(null, new View.DragShadowBuilder(row), row, 0);   // = startDragAndDrop (API 24), same semantics
                    return true;
                }
            });
            // accessibility: custom actions to move up / down
            row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(0x7f0b0001, "Move up"));
                    info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(0x7f0b0002, "Move down"));
                }
                @Override public boolean performAccessibilityAction(View host, int action, Bundle args) {
                    if (action == 0x7f0b0001 || action == 0x7f0b0002) {
                        int i0 = list.indexOfChild(host), i1 = i0 + (action == 0x7f0b0001 ? -1 : 1);
                        if (i1 < 0 || i1 >= list.getChildCount()) return true;
                        list.removeView(host); list.addView(host, i1); save.run();
                        return true;
                    }
                    return super.performAccessibilityAction(host, action, args);
                }
            });
        }
        list.setOnDragListener(new View.OnDragListener() {
            public boolean onDrag(View v, android.view.DragEvent e) {
                Object st = e.getLocalState();
                if (!(st instanceof View) || ((View) st).getParent() != list) return false;
                View row = (View) st;
                switch (e.getAction()) {
                    case android.view.DragEvent.ACTION_DRAG_STARTED: row.setAlpha(0.25f); return true;
                    case android.view.DragEvent.ACTION_DRAG_LOCATION: {
                        float y = e.getY();
                        int target = list.getChildCount() - 1;
                        for (int i = 0; i < list.getChildCount(); i++) {
                            View c = list.getChildAt(i);
                            if (y < c.getTop() + c.getHeight() / 2f) { target = i; break; }
                        }
                        autoScroll(list, y);
                        int cur = list.indexOfChild(row);
                        if (target > cur) target--;
                        if (target != cur && target >= 0) { list.removeView(row); list.addView(row, target); }
                        return true;
                    }
                    case android.view.DragEvent.ACTION_DROP: return true;
                    case android.view.DragEvent.ACTION_DRAG_ENDED: row.setAlpha(1f); save.run(); return true;
                }
                return true;
            }
        });
    }

    /** scroll the page while a dragged row is near the top or bottom edge */
    void autoScroll(View list, float y) {
        View p = list;
        while (p != null && !(p instanceof ScrollView)) p = p.getParent() instanceof View ? (View) p.getParent() : null;
        if (p == null) return;
        int[] a = new int[2], b = new int[2];
        list.getLocationOnScreen(a); p.getLocationOnScreen(b);
        float ys = a[1] + y - b[1], edge = px(72);
        if (ys < edge) p.scrollBy(0, -px(12));
        else if (ys > p.getHeight() - edge) p.scrollBy(0, px(12));
    }

    View previewFrame(PreviewView pv) {
        FrameLayout f = new FrameLayout(this);
        f.setPadding(0, px(8), 0, px(12));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(px(200), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL);
        pv.setBackground(shape(0xFF000000, 16, outlineVar, 1));
        pv.setClipToOutline(true);
        f.addView(pv, lp);
        return f;
    }

    /** the real renderer drawing a scaled home or lock screen from the saved settings (sample data) */
    final class PreviewView extends View {
        final boolean lock;
        final HudRenderer r = new HudRenderer();
        final HudRenderer.Data d = new HudRenderer.Data();
        final float ratio;
        PreviewView(Context c, boolean lock) {
            super(c);
            this.lock = lock;
            android.util.DisplayMetrics m = new android.util.DisplayMetrics();
            getWindowManager().getDefaultDisplay().getRealMetrics(m);
            ratio = Math.max(m.heightPixels, m.widthPixels) / (float) Math.min(m.heightPixels, m.widthPixels);
            try { r.setClockTypeface(Typeface.createFromAsset(c.getAssets(), "ShareTechMono-Regular.ttf")); } catch (Exception ignored) { }
            try {
                InputStream in = c.getAssets().open("stars.bin");
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                in.close(); r.setStars(bo.toByteArray());
            } catch (Exception ignored) { }
            setContentDescription(lock ? "Lock screen preview" : "Home screen preview");
            reload();
        }
        @Override protected void onMeasure(int ws, int hs) {
            int w = MeasureSpec.getSize(ws);
            setMeasuredDimension(w, Math.round(w * ratio));
        }
        @Override protected void onSizeChanged(int w, int h, int ow, int oh) { if (w > 0 && h > 0) r.setSize(w, h); }
        void reload() {
            for (int v = 0; v < 4; v++) {
                for (int m = 0; m < HudRenderer.NMOD; m++) d.mods[v][m] = sp.getBoolean("mod_" + v + "_" + m, DEF_MODS[v][m]);
                d.order[v] = HudRenderer.cleanOrder(ints(sp.getString("order_" + v, null)), HudRenderer.DEF_ORDER);
            }
            d.lkOrder = HudRenderer.cleanOrder(ints(sp.getString("lkOrder", null)), HudRenderer.LK_DEF);
            for (int i = 0; i < d.lkOn.length; i++) d.lkOn[i] = sp.getBoolean("lkOn_" + i, HudRenderer.LK_ON_DEF[i]);
            d.lkClock = sp.getBoolean("lkClock", true); d.lkNight = sp.getBoolean("lkNight", true);
            float[] tsc = HudRenderer.textScales(sp.getInt("textScale", 0), sp.getInt("textScaleLock", -1), sp.getInt("textScaleDream", -1),
                    getResources().getConfiguration().fontScale);
            r.setTextScales(tsc[0], tsc[1]);
            d.lkKp = sp.getBoolean("lkKp", true); d.lkWind = sp.getBoolean("lkWind", true);
            d.lkTime = sp.getBoolean("lkTime", true); d.lkCharge = sp.getBoolean("lkCharge", true);
            d.calView = sp.getInt("calView", 0); d.skyMode = sp.getInt("skyMode", 0);
            d.nightDim = false; d.virtualPages = true; d.noDock = sp.getBoolean("noDock", false);
            String[][] sc = Shortcuts.load(sp);
            d.scOn = sp.getBoolean("scOn", true); d.scN = sc.length;
            for (int i = 0; i < HudRenderer.Data.SCMAX; i++) { d.scLabel[i] = i < sc.length ? sc[i][1] : null; d.scOk[i] = i < sc.length; } d.h24 = android.text.format.DateFormat.is24HourFormat(getContext());
            d.wxCity = sp.getString("city", null);
            try { d.obsLat = Double.parseDouble(sp.getString("lat", "")); d.obsLon = Double.parseDouble(sp.getString("lon", "")); } catch (Exception e) { d.obsLat = Double.NaN; d.obsLon = Double.NaN; }
            d.swUpd = sp.getLong("swUpd", 0); d.kp = sp.getFloat("kp", Float.NaN); d.bz = sp.getFloat("bz", Float.NaN);
            d.swSpeed = sp.getFloat("swV", Float.NaN); d.xrFlux = sp.getFloat("xr", Float.NaN);
            d.scR = sp.getInt("scR", -1); d.scS = sp.getInt("scS", -1); d.scG = sp.getInt("scG", -1);
            if (!lock) {
                boolean split = sp.getBoolean("splitPages", true);
                if (editView == 0) { d.splitPages = false; d.page = 0; }
                else {
                    d.splitPages = split;
                    int[] vt = new int[3]; int nt = d.visibleTabs(vt), pg = 0;
                    for (int i = 0; i < nt; i++) if (vt[i] == editView - 1) pg = i;
                    d.page = pg; d.pages = Math.max(1, nt);
                }
            }
            invalidate();
        }
        @Override protected void onDraw(Canvas c) {
            if (getWidth() == 0) return;
            d.wallMs = System.currentTimeMillis();
            try { r.draw(c, lock ? HudRenderer.LOCKED : HudRenderer.STATIC, 4000, 0, d); } catch (Throwable ignored) { }
            if (!lock && editView > 0 && !sp.getBoolean("splitPages", true)) {
                Paint pa = new Paint(Paint.ANTI_ALIAS_FLAG); pa.setColor(0xCC000000); c.drawRect(0, 0, getWidth(), getHeight(), pa);
                pa.setColor(0xFFC0CAF5); pa.setTextSize(12 * dp); pa.setTextAlign(Paint.Align.CENTER);
                c.drawText("Tabbed views are off", getWidth() / 2f, getHeight() / 2f, pa);
            }
        }
    }

    // ------------------------------------------------------------------ pages + navigation bar
    final ArrayList<View> pageViews = new ArrayList<View>();
    final ArrayList<LinearLayout> pageCols = new ArrayList<LinearLayout>();
    final ArrayList<View[]> navItems = new ArrayList<View[]>();
    int tab = -1;

    /** new scrolling page with a large top app bar; following card() calls land on it */
    void page(FrameLayout host, String title, String subtitle) {
        ScrollView sv = new ScrollView(this);
        sv.setClipToPadding(false);
        sv.setBackgroundColor(surface);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        sv.addView(col);
        TextView h = text(title, 28, onSurface, false);
        h.setPadding(px(4), px(40), 0, px(4));
        col.addView(h);
        TextView sub = text(subtitle, 14, onSurfaceVar, false);
        sub.setPadding(px(4), 0, 0, px(20));
        col.addView(sub);
        sv.setVisibility(View.GONE);
        host.addView(sv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        pageViews.add(sv); pageCols.add(col);
    }

    void select(int k) {
        if (k == tab && pageViews.get(k).getVisibility() == View.VISIBLE) { ((ScrollView) pageViews.get(k)).smoothScrollTo(0, 0); return; }
        tab = k;
        sp.edit().putInt("uiTab", k).apply();
        for (int i = 0; i < pageViews.size(); i++) pageViews.get(i).setVisibility(i == k ? View.VISIBLE : View.GONE);
        for (int i = 0; i < navItems.size(); i++) {
            boolean on = i == k;
            View[] v = navItems.get(i);
            v[0].setBackground(on ? shape(secC, 16, 0, 0) : null);
            ((NavIcon) v[1]).set(on ? onSecC : onSurfaceVar, on, secC);
            ((TextView) v[2]).setTextColor(on ? onSurface : onSurfaceVar);
            ((TextView) v[2]).setTypeface(Typeface.create(on ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
            v[3].setSelected(on);
        }
    }

    /** M3 navigation bar destination: 64x32 dp active indicator with a 24 dp icon, 12 sp label */
    View navItem(String label, int icon, View.OnClickListener l) {
        LinearLayout it = new LinearLayout(this);
        it.setOrientation(LinearLayout.VERTICAL);
        it.setGravity(Gravity.CENTER);
        it.setPadding(0, px(12), 0, px(16));
        FrameLayout ind = new FrameLayout(this);
        NavIcon ic = new NavIcon(this, icon);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(px(24), px(24), Gravity.CENTER);
        ind.addView(ic, ilp);
        ind.setForeground(ripple(new ColorDrawable(Color.TRANSPARENT), 16, onSurface));
        it.addView(ind, new LinearLayout.LayoutParams(px(64), px(32)));
        TextView t = text(label, 12, onSurfaceVar, true);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        float avail = getResources().getDisplayMetrics().widthPixels / 6f - px(4);   // six destinations: shrink long labels to fit
        float w = t.getPaint().measureText(label);
        if (w > avail) t.setTextSize(TypedValue.COMPLEX_UNIT_PX, t.getTextSize() * avail / w);
        t.setPadding(0, px(4), 0, 0);
        it.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        it.setOnClickListener(l);
        it.setContentDescription(label);
        it.setFocusable(true);
        navItems.add(new View[]{ind, ic, t, it});
        return it;
    }

    /** hand-drawn 24 dp icons (outlined when inactive, filled when active, as M3 does): home, lock, dream, permissions, about, launcher */
    static final class NavIcon extends View {
        final int kind; int col, hole; boolean filled;
        final Paint pa = new Paint(Paint.ANTI_ALIAS_FLAG);
        final android.graphics.Path pt = new android.graphics.Path();
        NavIcon(Context c, int kind) { super(c); this.kind = kind; }
        void set(int col, boolean filled, int hole) { this.col = col; this.filled = filled; this.hole = hole; invalidate(); }
        @Override protected void onDraw(Canvas c) {
            float u = getWidth() / 24f;
            c.save(); c.scale(u, u);
            pa.setColor(col); pa.setStrokeWidth(2f); pa.setStrokeJoin(Paint.Join.ROUND); pa.setStrokeCap(Paint.Cap.ROUND);
            pa.setStyle(filled ? Paint.Style.FILL_AND_STROKE : Paint.Style.STROKE);
            pt.reset();
            if (kind == 0) {                                             // home
                pt.moveTo(4, 10.5f); pt.lineTo(12, 4); pt.lineTo(20, 10.5f); pt.lineTo(20, 20); pt.lineTo(14.5f, 20);
                pt.lineTo(14.5f, 14.5f); pt.lineTo(9.5f, 14.5f); pt.lineTo(9.5f, 20); pt.lineTo(4, 20); pt.close();
                c.drawPath(pt, pa);
            } else if (kind == 1) {                                      // lock
                pa.setStyle(Paint.Style.STROKE);
                pt.moveTo(8, 10.5f); pt.lineTo(8, 8); pt.arcTo(new RectF(8, 4, 16, 12), 180, 180); pt.lineTo(16, 10.5f);
                c.drawPath(pt, pa);
                pa.setStyle(filled ? Paint.Style.FILL_AND_STROKE : Paint.Style.STROKE);
                c.drawRoundRect(new RectF(5.5f, 10.5f, 18.5f, 20.5f), 2, 2, pa);
                if (!filled) { pa.setStyle(Paint.Style.FILL); c.drawCircle(12, 15.5f, 1.4f, pa); }
            } else if (kind == 2) {                                      // dream: crescent
                android.graphics.Path a = new android.graphics.Path(), b = new android.graphics.Path();
                a.addCircle(12, 12, 8, android.graphics.Path.Direction.CW);
                b.addCircle(16.5f, 8.5f, 7, android.graphics.Path.Direction.CW);
                a.op(b, android.graphics.Path.Op.DIFFERENCE);
                c.drawPath(a, pa);
            } else if (kind == 3) {                                      // permissions: shield with check
                pt.moveTo(12, 3); pt.lineTo(19.5f, 6); pt.lineTo(19.5f, 11.5f);
                pt.cubicTo(19.5f, 16.5f, 16, 19.8f, 12, 21); pt.cubicTo(8, 19.8f, 4.5f, 16.5f, 4.5f, 11.5f); pt.lineTo(4.5f, 6); pt.close();
                c.drawPath(pt, pa);
                pa.setColor(filled ? hole : col); pa.setStyle(Paint.Style.STROKE);
                android.graphics.Path ck = new android.graphics.Path();
                ck.moveTo(8.8f, 12); ck.lineTo(11.2f, 14.4f); ck.lineTo(15.4f, 9.8f);
                c.drawPath(ck, pa);
            } else if (kind == 5) {                                      // launcher: 2x2 app grid
                c.drawRoundRect(new RectF(4, 4, 10.5f, 10.5f), 1.5f, 1.5f, pa);
                c.drawRoundRect(new RectF(13.5f, 4, 20, 10.5f), 1.5f, 1.5f, pa);
                c.drawRoundRect(new RectF(4, 13.5f, 10.5f, 20), 1.5f, 1.5f, pa);
                c.drawRoundRect(new RectF(13.5f, 13.5f, 20, 20), 1.5f, 1.5f, pa);
            } else {                                                     // about: info
                c.drawCircle(12, 12, 9, pa);
                pa.setColor(filled ? hole : col);
                pa.setStyle(Paint.Style.STROKE);
                c.drawLine(12, 11, 12, 16.5f, pa);
                pa.setStyle(Paint.Style.FILL);
                c.drawCircle(12, 7.8f, 1.3f, pa);
            }
            c.restore();
        }
    }

    /** M3 continuous-with-steps slider: 4 dp track (active primary / inactive secondary container), 20 dp handle, tick marks */
    View sliderItem(String headline, final String key, int def, final int min, final int max, final int step) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, px(8), 0, px(8));
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.addView(text(headline, 16, onSurface, false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final TextView val = text("", 14, primary, true);
        top.addView(val);
        box.addView(top);
        final TextView sub = text("", 14, onSurfaceVar, false);
        box.addView(sub);
        final int[] cur = {Math.max(min, Math.min(max, sp.getInt(key, def)))};
        final Runnable label = new Runnable() { public void run() {
            val.setText(cur[0] + "%");
            sub.setText("Night brightness " + (100 - cur[0]) + "% of day");
        } };
        label.run();
        final View sl = new View(this) {
            final Paint pa = new Paint(Paint.ANTI_ALIAS_FLAG);
            float frac() { return (cur[0] - min) / (float) (max - min); }
            @Override protected void onDraw(Canvas c) {
                float pad = 12 * dp, w = getWidth() - 2 * pad, cy = getHeight() / 2f, x = pad + w * frac();
                pa.setStrokeCap(Paint.Cap.ROUND); pa.setStrokeWidth(4 * dp);
                pa.setColor(secC); c.drawLine(x, cy, pad + w, cy, pa);
                pa.setColor(primary); c.drawLine(pad, cy, x, cy, pa);
                pa.setStyle(Paint.Style.FILL);
                for (int v = min; v <= max; v += step) {
                    float tx = pad + w * (v - min) / (float) (max - min);
                    pa.setColor(v <= cur[0] ? onPrimary : onSecC); c.drawCircle(tx, cy, 1.2f * dp, pa);
                }
                if (isPressed()) { pa.setColor(withAlpha(primary, 0.12f)); c.drawCircle(x, cy, 20 * dp, pa); }
                pa.setColor(primary); c.drawCircle(x, cy, 10 * dp, pa);
            }
            @Override public boolean onTouchEvent(android.view.MotionEvent e) {
                int a = e.getActionMasked();
                if (a == android.view.MotionEvent.ACTION_DOWN) { getParent().requestDisallowInterceptTouchEvent(true); setPressed(true); }
                float pad = 12 * dp, f = Math.max(0, Math.min(1, (e.getX() - pad) / (getWidth() - 2 * pad)));
                int v = min + Math.round(f * (max - min) / step) * step;
                if (v != cur[0]) { cur[0] = v; label.run(); invalidate(); }
                if (a == android.view.MotionEvent.ACTION_UP || a == android.view.MotionEvent.ACTION_CANCEL) {
                    setPressed(false); invalidate(); sp.edit().putInt(key, cur[0]).apply();
                }
                return true;
            }
        };
        sl.setFocusable(true);
        sl.setContentDescription(headline);
        sl.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View h, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(h, info);
                info.setClassName("android.widget.SeekBar");
                info.setRangeInfo(android.view.accessibility.AccessibilityNodeInfo.RangeInfo.obtain(
                        android.view.accessibility.AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, min, max, cur[0]));
                info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
                info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
            }
            @Override public boolean performAccessibilityAction(View h, int act, Bundle args) {
                int d = act == android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? step
                        : act == android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD ? -step : 0;
                if (d == 0) return super.performAccessibilityAction(h, act, args);
                cur[0] = Math.max(min, Math.min(max, cur[0] + d)); label.run(); h.invalidate(); sp.edit().putInt(key, cur[0]).apply();
                return true;
            }
        });
        box.addView(sl, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(44)));
        return box;
    }

    // ------------------------------------------------------------------ shortcuts (v30)
    void fillShortcuts(final LinearLayout list, final LinearLayout acts) {
        list.removeAllViews(); acts.removeAllViews();
        final String[][] e = Shortcuts.load(sp);
        for (int i = 0; i < e.length; i++) {
            final int idx = i;
            String app = Shortcuts.DIAL.equals(e[i][0]) ? "Phone (default dialer)" : appLabel(e[i][0], "Not installed: " + e[i][0]);
            View r = dragRow(e[i][1], app, true, null, i);
            LinearLayout rr = (LinearLayout) r;
            TextView del = text("✕", 18, onSurfaceVar, false);
            del.setGravity(Gravity.CENTER);
            del.setContentDescription("Remove " + e[i][1]);
            del.setBackground(ripple(new ColorDrawable(Color.TRANSPARENT), 24, onSurface));
            del.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
                ArrayList<String[]> l = new ArrayList<String[]>(java.util.Arrays.asList(Shortcuts.load(sp)));
                if (idx < l.size()) l.remove(idx);
                Shortcuts.save(sp, l); fillShortcuts(list, acts);
            } });
            rr.addView(del, rr.getChildCount() - 1, new LinearLayout.LayoutParams(px(48), px(48)));
            rr.setBackground(ripple(new ColorDrawable(surf), 8, onSurface));
            rr.setClickable(true);
            rr.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { editShortcut(idx, list, acts); } });
            list.addView(r);
        }
        reorderable(list, new Runnable() { public void run() {
            String[][] cur = Shortcuts.load(sp);
            ArrayList<String[]> l = new ArrayList<String[]>();
            for (int i = 0; i < list.getChildCount(); i++) { int k = (Integer) list.getChildAt(i).getTag(); if (k < cur.length) l.add(cur[k]); }
            Shortcuts.save(sp, l); fillShortcuts(list, acts);
        } });
        if (e.length < HudRenderer.Data.SCMAX)
            acts.addView(button("Add shortcut", 1, new View.OnClickListener() { public void onClick(View v) { editShortcut(-1, list, acts); } }));
        acts.addView(button("Reset", 2, new View.OnClickListener() { public void onClick(View v) { sp.edit().remove(Shortcuts.KEY).apply(); fillShortcuts(list, acts); } }));
    }

    /** dialog: optional label + app list (phone first); slot -1 adds a new shortcut */
    void editShortcut(final int slot, final LinearLayout list, final LinearLayout acts) {
        final String[][] cur = Shortcuts.load(sp);
        final android.app.Dialog dlg = new android.app.Dialog(this);
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(shape(surfHigh, 28, 0, 0));
        box.setPadding(px(24), px(24), px(24), px(16));
        TextView h = text(slot < 0 ? "Add shortcut" : "Edit shortcut", 24, onSurface, false); h.setPadding(0, 0, 0, px(12));
        box.addView(h);
        final EditText lab = outlinedField(box, "Label (optional, max 12)", slot >= 0 && slot < cur.length ? cur[slot][1] : "");
        lab.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(12), new android.text.InputFilter.AllCaps()});
        TextView hint = text("Pick the app; leave the label empty to use the app name.", 14, onSurfaceVar, false);
        hint.setPadding(0, px(4), 0, px(8));
        box.addView(hint);
        ScrollView sc = new ScrollView(this);
        LinearLayout lst = new LinearLayout(this); lst.setOrientation(LinearLayout.VERTICAL);
        sc.addView(lst);
        Intent q = new Intent(Intent.ACTION_MAIN); q.addCategory(Intent.CATEGORY_LAUNCHER);
        java.util.List<android.content.pm.ResolveInfo> apps = getPackageManager().queryIntentActivities(q, 0);
        final ArrayList<String[]> items = new ArrayList<String[]>();
        items.add(new String[]{Shortcuts.DIAL, "Phone (default dialer)"});
        java.util.HashSet<String> seen = new java.util.HashSet<String>();
        for (android.content.pm.ResolveInfo ri : apps)
            if (seen.add(ri.activityInfo.packageName)) items.add(new String[]{ri.activityInfo.packageName, ri.loadLabel(getPackageManager()).toString()});
        java.util.Collections.sort(items.subList(1, items.size()), new java.util.Comparator<String[]>() {
            public int compare(String[] a, String[] b) { return a[1].compareToIgnoreCase(b[1]); }
        });
        final String curPkg = slot >= 0 && slot < cur.length ? cur[slot][0] : null;
        for (final String[] it : items) {
            boolean sel = it[0].equals(curPkg);
            TextView row = text((sel ? "◉  " : "○  ") + it[1], 16, sel ? primary : onSurface, false);
            row.setMinHeight(px(48)); row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(px(8), 0, px(8), 0);
            row.setBackground(ripple(new ColorDrawable(Color.TRANSPARENT), 8, onSurface));
            row.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
                String l = lab.getText().toString().trim();
                boolean keepOld = slot >= 0 && slot < cur.length && it[0].equals(cur[slot][0]) && l.equals(cur[slot][1]);
                String label = l.isEmpty() || (!keepOld && slot >= 0 && slot < cur.length && l.equals(cur[slot][1]) && !it[0].equals(cur[slot][0]))
                        ? (Shortcuts.DIAL.equals(it[0]) ? "PHONE" : Shortcuts.label(it[1])) : l;
                ArrayList<String[]> L = new ArrayList<String[]>(java.util.Arrays.asList(cur));
                String[] ent = {it[0], label};
                if (slot >= 0 && slot < L.size()) L.set(slot, ent); else if (L.size() < HudRenderer.Data.SCMAX) L.add(ent);
                Shortcuts.save(sp, L); fillShortcuts(list, acts); dlg.dismiss();
            } });
            lst.addView(row);
        }
        box.addView(sc, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(320)));
        LinearLayout actions = new LinearLayout(this); actions.setGravity(Gravity.END);
        if (slot >= 0 && slot < cur.length)
            actions.addView(button("Save label", 2, new View.OnClickListener() { public void onClick(View v) {
                String l = lab.getText().toString().trim();
                ArrayList<String[]> L = new ArrayList<String[]>(java.util.Arrays.asList(cur));
                L.set(slot, new String[]{cur[slot][0], l.isEmpty() ? cur[slot][1] : l});
                Shortcuts.save(sp, L); fillShortcuts(list, acts); dlg.dismiss();
            } }));
        actions.addView(button("Cancel", 2, new View.OnClickListener() { public void onClick(View v) { dlg.dismiss(); } }));
        box.addView(actions);
        dlg.setContentView(box);
        if (dlg.getWindow() != null) dlg.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        dlg.show();
    }

    /** glyph row: big letter, assigned action (tap to change), Teach / Clear */
    View glyphItem(final String g) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(px(56));
        TextView big = text(g, 26, primary, false);
        big.setTypeface(Typeface.MONOSPACE);
        big.setGravity(Gravity.CENTER);
        r.addView(big, new LinearLayout.LayoutParams(px(48), ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout tx = new LinearLayout(this); tx.setOrientation(LinearLayout.VERTICAL);
        final TextView v = text("", 16, onSurface, false);
        final TextView n = text("", 12, onSurfaceVar, false);
        tx.addView(v); tx.addView(n);
        r.addView(tx, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final Runnable paint = new Runnable() { public void run() {
            int a = Gestures.glyphAction(sp, g);
            String s = Gestures.ACTION_NAMES[a];
            if (a == Gestures.OPEN_APP) {
                String k = Gestures.glyphApp(sp, g);
                s = "Open " + (k == null ? "(no app chosen)" : appLabel(k.substring(0, k.indexOf('/') > 0 ? k.indexOf('/') : k.length()), k));
            }
            v.setText(s); v.setTextColor(a == Gestures.NONE ? onSurfaceVar : onSurface);
            int c = Glyph.taught(sp, g).size();
            n.setText(c == 0 ? "built-in shape" : "built-in + " + c + " of your samples");
        } };
        paint.run();
        tx.setBackground(ripple(new ColorDrawable(Color.TRANSPARENT), 8, onSurface));
        tx.setClickable(true);
        tx.setOnClickListener(new View.OnClickListener() { public void onClick(View x) {
            glass().list("Glyph " + g, Gestures.ACTION_NAMES, Gestures.glyphAction(sp, g), new Glass.Pick() {
                public void on(int w) {
                    if (w == Gestures.OPEN_APP) pickGestureApp("gm_" + g, paint);
                    else { sp.edit().putInt("gm_" + g, w).apply(); paint.run(); }
                }
            }).show();
        } });
        r.addView(button("Teach", 2, new View.OnClickListener() { public void onClick(View x) { teachGlyph(g, paint); } }));
        return r;
    }

    /** drawing pad: each stroke becomes a sample; shows what the recogniser currently reads */
    void teachGlyph(final String g, final Runnable done) {
        final android.app.Dialog dlg = new android.app.Dialog(this);
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(shape(surfHigh, 28, 0, 0));
        box.setPadding(px(24), px(24), px(24), px(16));
        box.addView(text("Teach " + g, 24, onSurface, false));
        final TextView st = text("Draw " + g + " in the box, the way you'd draw it on the home screen. Each stroke is saved.", 14, onSurfaceVar, false);
        st.setPadding(0, px(8), 0, px(12));
        box.addView(st);
        final View pad = new View(this) {
            final android.graphics.Path path = new android.graphics.Path();
            final Paint pa = new Paint(Paint.ANTI_ALIAS_FLAG);
            final ArrayList<float[]> pts = new ArrayList<float[]>();
            @Override protected void onDraw(Canvas c) {
                pa.setStyle(Paint.Style.STROKE); pa.setStrokeWidth(4 * dp); pa.setColor(primary); pa.setStrokeCap(Paint.Cap.ROUND); pa.setStrokeJoin(Paint.Join.ROUND);
                c.drawPath(path, pa);
            }
            @Override public boolean onTouchEvent(android.view.MotionEvent e) {
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN: path.reset(); pts.clear(); path.moveTo(e.getX(), e.getY()); pts.add(new float[]{e.getX(), e.getY()}); getParent().requestDisallowInterceptTouchEvent(true); break;
                    case android.view.MotionEvent.ACTION_MOVE: path.lineTo(e.getX(), e.getY()); pts.add(new float[]{e.getX(), e.getY()}); break;
                    case android.view.MotionEvent.ACTION_UP:
                        float[] v = Glyph.vector(pts);
                        if (v != null && Glyph.pathLen(pts) > 60 * dp) {
                            Object[] before = Glyph.recognise(sp, pts);
                            Glyph.teach(sp, g, v);
                            st.setText(String.format(Locale.US, "Saved (%d). Before this sample it read: %s (%.2f)", Glyph.taught(sp, g).size(),
                                    before[0] == null ? "nothing" : before[0], (Float) before[1]));
                        } else st.setText("Too short; draw it bigger.");
                        break;
                }
                invalidate();
                return true;
            }
        };
        pad.setBackground(shape(surface, 16, outlineVar, 1));
        box.addView(pad, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(280)));
        LinearLayout actions = new LinearLayout(this); actions.setGravity(Gravity.END);
        actions.addView(button("Clear samples", 2, new View.OnClickListener() { public void onClick(View v) { sp.edit().remove("gt_" + g).apply(); st.setText("Samples cleared; the built-in shape remains."); } }));
        actions.addView(button("Done", 2, new View.OnClickListener() { public void onClick(View v) { dlg.dismiss(); } }));
        box.addView(actions);
        dlg.setContentView(box);
        dlg.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() { public void onDismiss(android.content.DialogInterface d) { done.run(); } });
        if (dlg.getWindow() != null) dlg.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        dlg.show();
    }

    /** launcher gesture row: name, current action; tap to pick an action (and an app for "Open an app…") */
    View gestureItem(final int gi) {
        final String g = Gestures.KEYS[gi];
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setMinimumHeight(px(56));
        r.setPadding(0, px(8), 0, px(8));
        r.addView(text(Gestures.NAMES[gi], 16, onSurface, false));
        final TextView v = text("", 14, primary, false);
        r.addView(v);
        if (Gestures.HINTS[gi] != null) r.addView(text(Gestures.HINTS[gi], 12, onSurfaceVar, false));
        final Runnable paint = new Runnable() { public void run() {
            int a = Gestures.get(sp, g);
            String s = Gestures.ACTION_NAMES[a];
            if (a == Gestures.OPEN_APP) {
                String k = Gestures.app(sp, g);
                s = "Open " + (k == null ? "(no app chosen)" : appLabel(k.substring(0, k.indexOf('/') > 0 ? k.indexOf('/') : k.length()), k));
            }
            v.setText(s);
        } };
        paint.run();
        r.setBackground(ripple(new ColorDrawable(Color.TRANSPARENT), 8, onSurface));
        r.setClickable(true);
        r.setOnClickListener(new View.OnClickListener() { public void onClick(View x) {
            glass().list(Gestures.NAMES[gi], Gestures.ACTION_NAMES, Gestures.get(sp, g), new Glass.Pick() {
                public void on(int w) {
                    if (w == Gestures.OPEN_APP) pickGestureApp("lg_" + g, paint);
                    else { sp.edit().putInt("lg_" + g, w).apply(); paint.run(); }
                }
            }).show();
        } });
        return r;
    }

    void pickGestureApp(final String key, final Runnable done) {
        android.content.pm.LauncherApps la = (android.content.pm.LauncherApps) getSystemService(Context.LAUNCHER_APPS_SERVICE);
        android.os.UserManager um = (android.os.UserManager) getSystemService(Context.USER_SERVICE);
        android.os.UserHandle me = android.os.Process.myUserHandle();
        final ArrayList<String[]> items = new ArrayList<String[]>();
        for (android.content.pm.LauncherActivityInfo i : la.getActivityList(null, me))
            items.add(new String[]{i.getComponentName().flattenToShortString() + "#" + um.getSerialNumberForUser(me), String.valueOf(i.getLabel())});
        java.util.Collections.sort(items, new java.util.Comparator<String[]>() { public int compare(String[] a, String[] b) { return a[1].compareToIgnoreCase(b[1]); } });
        String[] names = new String[items.size()];
        for (int i = 0; i < names.length; i++) names[i] = items.get(i)[1];
        glass().list("Open which app?", names, -1, new Glass.Pick() {
            public void on(int w) {
                sp.edit().putInt(key, Gestures.OPEN_APP).putString(key + "_app", items.get(w)[0]).apply();
                done.run();
            }
        }).show();
    }

    /** single-select chip row stored as an int pref */
    View chipItem(String headline, String supporting, final String key, final int def, final int[] val, final String[] lab) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, px(10), 0, px(6));
        box.addView(text(headline, 16, onSurface, false));
        if (supporting != null) box.addView(text(supporting, 14, onSurfaceVar, false));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        final TextView[] ch = new TextView[val.length];
        final Runnable paint = new Runnable() { public void run() {
            int cur = sp.getInt(key, def);
            for (int i = 0; i < ch.length; i++) {
                boolean on = val[i] == cur;
                ch[i].setText(on ? "✓ " + lab[i] : lab[i]);
                ch[i].setTextColor(on ? onSecC : onSurfaceVar);
                ch[i].setBackground(ripple(shape(on ? secC : Color.TRANSPARENT, 8, outlineVar, on ? 0 : 1), 8, onSurface));
                ch[i].setContentDescription(headline + ": " + lab[i] + (on ? ", selected" : ""));
            }
        } };
        for (int i = 0; i < val.length; i++) {
            final int v = val[i];
            ch[i] = text("", 14, onSurfaceVar, true);
            ch[i].setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, px(32), 1f);
            lp.rightMargin = px(6); lp.topMargin = px(8); lp.bottomMargin = px(4);
            row.addView(ch[i], lp);
            ch[i].setOnClickListener(new View.OnClickListener() { public void onClick(View x) { sp.edit().putInt(key, v).apply(); paint.run(); } });
        }
        box.addView(row);
        paint.run();
        return box;
    }

    /** "Text size" list item with single-select chips; val 0 = Android's font size, -1 = same as the home screen */
    View textSizeItem(final String key, final int def, final int[] val, final String firstLabel) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, px(10), 0, px(6));
        box.addView(text("Text size", 16, onSurface, false));
        final TextView note = text("", 14, onSurfaceVar, false);
        box.addView(note);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        final TextView[] ch = new TextView[val.length];
        final Runnable paint = new Runnable() { public void run() {
            int cur = sp.getInt(key, def);
            for (int i = 0; i < ch.length; i++) {
                boolean on = val[i] == cur;
                String l = i == 0 ? firstLabel : val[i] + "%";
                ch[i].setText(on ? "✓ " + l : l);
                ch[i].setTextColor(on ? onSecC : onSurfaceVar);
                ch[i].setBackground(ripple(shape(on ? secC : Color.TRANSPARENT, 8, outlineVar, on ? 0 : 1), 8, onSurface));
                ch[i].setContentDescription("Text size " + l + (on ? ", selected" : ""));
            }
            float sys = getResources().getConfiguration().fontScale;
            float[] t = HudRenderer.textScales(sp.getInt("textScale", 0), sp.getInt("textScaleLock", -1), sp.getInt("textScaleDream", -1), sys);
            int eff = Math.round(100 * (key.equals("textScale") ? t[0] : key.equals("textScaleLock") ? t[1] : t[2]));
            note.setText(cur == 0 ? "Follows Android's font size: " + eff + "%" + (sys > 1.25f || sys < 0.85f ? " (HUD limited to 85–125%)" : "")
                    : cur < 0 ? "Same as the home screen: " + eff + "%" : "Fixed at " + eff + "%");
        } };
        for (int i = 0; i < val.length; i++) {
            final int v = val[i];
            ch[i] = text("", 14, onSurfaceVar, true);
            ch[i].setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, px(32), i == 0 ? 1.4f : 1f);
            lp.rightMargin = px(6); lp.topMargin = px(8); lp.bottomMargin = px(4);
            row.addView(ch[i], lp);
            ch[i].setOnClickListener(new View.OnClickListener() { public void onClick(View x) { sp.edit().putInt(key, v).apply(); paint.run(); } });
        }
        box.addView(row);
        paint.run();
        onResumeHooks.add(paint);                          // the home size can change on another tab
        TextView hint = text("Small text only (tiles, labels, readouts); the big numerals keep their size.", 12, onSurfaceVar, false);
        hint.setPadding(0, px(4), 0, 0);
        box.addView(hint);
        return box;
    }

    // ------------------------------------------------------------------ backup + diagnostics (v31)
    static final int RC_EXPORT = 9101, RC_IMPORT = 9102;
    TextView backupStatus;

    @Override protected void onActivityResult(int rc, int res, Intent data) {
        super.onActivityResult(rc, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        android.net.Uri u = data.getData();
        try {
            if (rc == RC_EXPORT) {
                java.io.OutputStream o = getContentResolver().openOutputStream(u);
                o.write(Backup.export(sp).getBytes("UTF-8")); o.close();
                if (backupStatus != null) backupStatus.setText("Saved.");
            } else if (rc == RC_IMPORT) {
                InputStream in = getContentResolver().openInputStream(u);
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] b = new byte[8192]; int n;
                while ((n = in.read(b)) > 0) bo.write(b, 0, n);
                in.close();
                int k = Backup.apply(sp, bo.toString("UTF-8"));
                Alerts.ensureJob(this);
                build();
                if (backupStatus != null) backupStatus.setText("Loaded " + k + " settings.");
            }
        } catch (Exception e) {
            if (backupStatus != null) backupStatus.setText("Failed: " + e.getMessage());
        }
    }

    String diagText() {
        long now = System.currentTimeMillis();
        StringBuilder b = new StringBuilder();
        String[][] src = {{"wx", "Weather    "}, {"sw", "NOAA SWPC  "}, {"tle", "CelesTrak  "}};
        for (String[] s0 : src) {
            long ok = sp.getLong("dg_" + s0[0] + "_ok", 0), tr = sp.getLong("dg_" + s0[0] + "_try", 0);
            String err = sp.getString("dg_" + s0[0] + "_err", null);
            b.append(s0[1]).append(ok == 0 ? "never ok" : "ok " + ago(now - ok) + " ago");
            if (err != null) b.append("\n           last try ").append(tr == 0 ? "?" : ago(now - tr) + " ago").append(": ").append(err);
            b.append('\n');
        }
        b.append("Location   ").append(sp.getString("dg_loc", "unknown")).append('\n');
        long pa = sp.getLong("dg_perfAt", 0);
        if (pa > 0) b.append(String.format(Locale.US, "Drawing    %.1f ms/frame, %.0f frames/h (%s ago)\n", sp.getFloat("dg_frameMs", 0), sp.getFloat("dg_framesH", 0), ago(now - pa)));
        else b.append("Drawing    no data yet (needs 5 min of HUD time)\n");
        boolean al = sp.getBoolean("alAurora", false) || sp.getBoolean("alStorm", false) || sp.getBoolean("alPass", false) || sp.getBoolean("alStarlink", false);
        b.append("Alerts     ").append(al ? "on" : "off");
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != 0) b.append(" (notifications not allowed)");
        b.append('\n');
        try { b.append("Version    ").append(getPackageManager().getPackageInfo(getPackageName(), 0).versionName).append(" · Android ").append(Build.VERSION.RELEASE); }
        catch (Exception ignored) { }
        return b.toString();
    }

    // ------------------------------------------------------------------ permissions (v24)
    static final String P_COARSE = "android.permission.ACCESS_COARSE_LOCATION", P_FINE = "android.permission.ACCESS_FINE_LOCATION";

    boolean has(String p) { return checkSelfPermission(p) == 0; }

    /** row: headline, what it powers, live status; Allow (runtime dialog, or system page after "don't ask again") / Revoke */
    View permRow(String headline, String what, final String[] req, final String check, final int rc) {
        final LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(px(88));
        r.setPadding(0, px(8), 0, px(8));
        LinearLayout tx = new LinearLayout(this); tx.setOrientation(LinearLayout.VERTICAL);
        tx.addView(text(headline, 16, onSurface, false));
        tx.addView(text(what, 14, onSurfaceVar, false));
        final TextView st = text("", 12, onSurfaceVar, true);
        st.setPadding(0, px(4), 0, 0);
        tx.addView(st);
        r.addView(tx, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final FrameLayout slot = new FrameLayout(this);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.leftMargin = px(16);
        r.addView(slot, sl);
        onResumeHooks.add(new Runnable() { public void run() {
            boolean on = has(check);
            boolean blocked = !on && sp.getBoolean("asked_" + check, false) && !shouldShowRequestPermissionRationale(check);
            boolean needsCoarse = !on && check.endsWith("BACKGROUND_LOCATION") && !has(P_COARSE);
            st.setText(on ? "✓ Allowed" : needsCoarse ? "Needs approximate location first" : blocked ? "Denied · Allow opens system settings" : "Not allowed");
            st.setTextColor(on ? primary : blocked ? error : onSurfaceVar);
            slot.removeAllViews();
            if (!on) {
                slot.addView(button("Allow", 1, new View.OnClickListener() { public void onClick(View v) {
                    if (check.endsWith("BACKGROUND_LOCATION") && !has(P_COARSE)) { requestPermissions(new String[]{P_COARSE}, 3); return; }
                    boolean blk = sp.getBoolean("asked_" + check, false) && !shouldShowRequestPermissionRationale(check);
                    sp.edit().putBoolean("asked_" + check, true).apply();
                    if (blk) appSettings(); else requestPermissions(req, rc);
                } }));
            } else {
                slot.addView(button(Build.VERSION.SDK_INT >= 33 ? "Revoke" : "Manage", 2, new View.OnClickListener() { public void onClick(View v) {
                    if (Build.VERSION.SDK_INT >= 33) confirmRevoke(revokeSet(check)); else appSettings();
                } }));
            }
        } });
        return r;
    }

    /** revoking a location level also revokes the levels that depend on it */
    String[] revokeSet(String p) {
        if (p.equals(P_COARSE)) return new String[]{P_COARSE, P_FINE, "android.permission.ACCESS_BACKGROUND_LOCATION"};
        return new String[]{p};
    }

    void confirmRevoke(final String[] perms) {
        glass().message("Revoke access?", "TN-HUD will close and restart to apply this; the wallpaper reloads by itself.",
                new String[]{"Cancel", "Revoke"}, new Glass.Pick() {
            public void on(int w) {
                if (w != 1) return;
                try {
                    java.util.List<String> l = new java.util.ArrayList<String>();
                    for (String p : perms) if (has(p)) l.add(p);
                    Context.class.getMethod("revokeSelfPermissionsOnKill", java.util.Collection.class).invoke(SetWallpaper.this, l);
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() { public void run() {
                        finishAffinity();
                        android.os.Process.killProcess(android.os.Process.myPid());
                    } }, 400);
                } catch (Throwable e) { appSettings(); }
            }
        }).show();
    }

    Glass glass() { return new Glass(this, dark, null); }

    void appSettings() {
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", getPackageName(), null)));
        } catch (Exception ignored) { }
    }

    String dataStatusText() {
        long now = System.currentTimeMillis();
        long sw = sp.getLong("swUpd", 0);
        java.io.File tf = new java.io.File(getFilesDir(), "tle.txt");
        String a = sw == 0 ? "Space weather (NOAA): not fetched yet; it updates while the HUD is visible and online."
                : "Space weather (NOAA): updated " + ago(now - sw) + " ago.";
        String b = !tf.exists() ? "Orbit data (CelesTrak): not fetched yet; it downloads once a day."
                : "Orbit data (CelesTrak): " + ago(now - tf.lastModified()) + " old.";
        boolean loc = sp.contains("city") || sp.getBoolean("liveloc", false);
        String c = loc ? "Location for sky events: " + (sp.getBoolean("liveloc", false) ? "live" : sp.getString("city", "")) + "."
                : "Location for sky events: none; set a city on the Home screen tab.";
        return a + "\n" + b + "\n" + c;
    }

    static String ago(long ms) {
        long m = Math.max(0, ms / 60000);
        if (m < 1) return "under a minute";
        if (m < 60) return m + " min";
        if (m < 2880) return (m / 60) + " h";
        return (m / 1440) + " days";
    }

    // ------------------------------------------------------------------ components
    TextView text(String t, float spSize, int color, boolean medium) {
        TextView v = new TextView(this);
        v.setText(t); v.setTextColor(color); v.setTextSize(TypedValue.COMPLEX_UNIT_SP, spSize);
        v.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        v.setLineSpacing(0, 1.15f);
        return v;
    }

    TextView body(String t) { TextView v = text(t, 14, onSurfaceVar, false); v.setPadding(0, px(6), 0, px(6)); return v; }

    Drawable shape(int fill, float radiusDp, int strokeCol, float strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill); g.setCornerRadius(radiusDp * dp);
        if (strokeDp > 0) g.setStroke(Math.max(1, px(strokeDp)), strokeCol);
        return g;
    }

    Drawable ripple(Drawable content, float radiusDp, int stateColor) {
        GradientDrawable mask = new GradientDrawable(); mask.setColor(Color.WHITE); mask.setCornerRadius(radiusDp * dp);
        return new RippleDrawable(ColorStateList.valueOf(withAlpha(stateColor, 0.12f)), content, mask);
    }

    /** M3 filled card with title (titleMedium) and optional supporting text */
    LinearLayout card(String title, String supporting) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(shape(surf, 12, 0, 0));
        c.setPadding(px(16), px(16), px(16), px(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = px(12);
        col.addView(c, lp);
        c.addView(text(title, 16, onSurface, true));
        if (supporting != null) { TextView s2 = text(supporting, 14, onSurfaceVar, false); s2.setPadding(0, px(4), 0, px(8)); c.addView(s2); }
        return c;
    }

    void divider(LinearLayout parent) {
        View v = new View(this); v.setBackgroundColor(outlineVar);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, px(1)));
        lp.topMargin = px(4); lp.bottomMargin = px(4);
        parent.addView(v, lp);
    }

    LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, px(8), 0, px(4));
        return r;
    }

    /** kind 0 filled, 1 tonal, 2 outlined; 40 dp tall, fully rounded, labelLarge */
    TextView button(String label, int kind, View.OnClickListener l) {
        TextView b = text(label, 14, kind == 0 ? onPrimary : kind == 1 ? onSecC : primary, true);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(px(40)); b.setMinimumHeight(px(40));
        b.setPadding(px(24), 0, px(24), 0);
        int fill = kind == 0 ? primary : kind == 1 ? secC : Color.TRANSPARENT;
        b.setBackground(ripple(shape(fill, 20, outline, kind == 2 ? 1 : 0), 20, kind == 0 ? onPrimary : onSurface));
        b.setClickable(true); b.setFocusable(true);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(40));
        lp.topMargin = px(4); lp.bottomMargin = px(4);      // keeps a 48 dp touch row
        b.setLayoutParams(lp);
        return b;
    }

    /** M3 list item (56/72 dp) with trailing switch; the whole row toggles */
    View switchItem(String headline, String supporting, final String key, boolean def, final Runnable after) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(px(supporting == null ? 56 : 72));
        r.setPadding(0, px(8), 0, px(8));
        LinearLayout tx = new LinearLayout(this); tx.setOrientation(LinearLayout.VERTICAL);
        tx.addView(text(headline, 16, onSurface, false));
        if (supporting != null) tx.addView(text(supporting, 14, onSurfaceVar, false));
        r.addView(tx, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final M3Switch sw = new M3Switch(this, sp.getBoolean(key, def));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(px(52), px(48)); sl.leftMargin = px(16);
        r.addView(sw, sl);
        r.setBackground(ripple(new ColorDrawable(Color.TRANSPARENT), 8, onSurface));
        r.setClickable(true);
        r.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean on = !sw.on; sw.set(on);
                sp.edit().putBoolean(key, on).apply();
                if (after != null) after.run();
                updateLocStatus();
            }
        });
        r.setContentDescription(headline);
        return r;
    }

    /** list item for a runtime permission: trailing "Allowed" or a tonal Grant button */
    View permItem(String headline, String supporting, final String perm, final int rc) {
        final LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(px(72));
        r.setPadding(0, px(8), 0, px(8));
        LinearLayout tx = new LinearLayout(this); tx.setOrientation(LinearLayout.VERTICAL);
        tx.addView(text(headline, 16, onSurface, false));
        tx.addView(text(supporting, 14, onSurfaceVar, false));
        r.addView(tx, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final FrameLayout slot = new FrameLayout(this);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.leftMargin = px(16);
        r.addView(slot, sl);
        onResumeHooks.add(new Runnable() {
            public void run() {
                slot.removeAllViews();
                if (checkSelfPermission(perm) == 0) {
                    TextView ok = text("✓ Allowed", 14, primary, true);
                    ok.setPadding(px(12), px(10), px(4), px(10));
                    slot.addView(ok);
                } else {
                    slot.addView(button("Grant", 1, new View.OnClickListener() {
                        public void onClick(View v) { requestPermissions(new String[]{perm}, rc); }
                    }));
                }
            }
        });
        return r;
    }

    /** variant requesting several permissions together (precise location must be requested with coarse): trailing "Allowed" or a tonal Grant button */
    View permItem(String headline, String supporting, final String perm, final int rc, final String[] req) {
        final LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(px(72));
        r.setPadding(0, px(8), 0, px(8));
        LinearLayout tx = new LinearLayout(this); tx.setOrientation(LinearLayout.VERTICAL);
        tx.addView(text(headline, 16, onSurface, false));
        tx.addView(text(supporting, 14, onSurfaceVar, false));
        r.addView(tx, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final FrameLayout slot = new FrameLayout(this);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.leftMargin = px(16);
        r.addView(slot, sl);
        onResumeHooks.add(new Runnable() {
            public void run() {
                slot.removeAllViews();
                if (checkSelfPermission(perm) == 0) {
                    TextView ok = text("✓ Allowed", 14, primary, true);
                    ok.setPadding(px(12), px(10), px(4), px(10));
                    slot.addView(ok);
                } else {
                    slot.addView(button("Grant", 1, new View.OnClickListener() {
                        public void onClick(View v) { requestPermissions(req, rc); }
                    }));
                }
            }
        });
        return r;
    }

    /** M3 filter chip: 32 dp, 8 dp corners; selected = secondary container + check */
    View chip(final String label, final String key, boolean def) {
        final TextView c = text("", 14, onSurfaceVar, true);
        c.setGravity(Gravity.CENTER);
        c.setPadding(px(10), 0, px(12), 0);
        final boolean[] on = {sp.getBoolean(key, def)};
        final Runnable paint = new Runnable() {
            public void run() {
                c.setText(on[0] ? "✓ " + label : label);
                c.setTextColor(on[0] ? onSecC : onSurfaceVar);
                c.setBackground(ripple(shape(on[0] ? secC : Color.TRANSPARENT, 8, outlineVar, on[0] ? 0 : 1), 8, onSurface));
                c.setContentDescription(label + (on[0] ? ", selected" : ", not selected"));
            }
        };
        paint.run();
        c.setClickable(true);
        c.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { on[0] = !on[0]; sp.edit().putBoolean(key, on[0]).apply(); paint.run(); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, px(32), 1f);
        lp.rightMargin = px(8); lp.topMargin = px(8); lp.bottomMargin = px(8);
        c.setLayoutParams(lp);
        return c;
    }

    /** M3 outlined text field: 56 dp, 4 dp corners, label resting on the outline */
    EditText outlinedField(LinearLayout parent, String label, String value) {
        FrameLayout f = new FrameLayout(this);
        f.setPadding(0, px(8), 0, 0);
        final EditText e = new EditText(this);
        e.setText(value); e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        e.setImeOptions(EditorInfo.IME_ACTION_DONE);
        e.setTextColor(onSurface); e.setHintTextColor(onSurfaceVar);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        e.setHint("e.g. Sandefjord");
        e.setPadding(px(16), 0, px(16), 0);
        final GradientDrawable bg = (GradientDrawable) shape(Color.TRANSPARENT, 4, outline, 1);
        e.setBackground(bg);
        e.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean has) { bg.setStroke(px(has ? 2 : 1), has ? primary : outline); }
        });
        f.addView(e, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(56)));
        TextView lab = text(label, 12, primary, false);
        lab.setBackgroundColor(surf);
        lab.setPadding(px(4), 0, px(4), 0);
        FrameLayout.LayoutParams ll = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ll.leftMargin = px(12); ll.topMargin = -px(8);
        f.addView(lab, ll);
        parent.addView(f);
        return e;
    }

    /** M3 switch: 52x32 track, 16 dp thumb off / 24 dp on, primary when on */
    class M3Switch extends View {
        boolean on;
        float t;
        final Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF rr = new RectF();

        M3Switch(Context c, boolean initial) { super(c); on = initial; t = initial ? 1 : 0; setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }

        void set(boolean v) {
            on = v;
            ValueAnimator a = ValueAnimator.ofFloat(t, v ? 1 : 0);
            a.setDuration(160);
            a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                public void onAnimationUpdate(ValueAnimator an) { t = (Float) an.getAnimatedValue(); invalidate(); }
            });
            a.start();
        }

        @Override protected void onDraw(Canvas c) {
            float w = 52 * dp, h = 32 * dp, x = (getWidth() - w) / 2, y = (getHeight() - h) / 2;
            rr.set(x, y, x + w, y + h);
            pt.setStyle(Paint.Style.FILL);
            pt.setColor(blend(surfHighest, primary, t));
            c.drawRoundRect(rr, h / 2, h / 2, pt);
            if (t < 1) {
                pt.setStyle(Paint.Style.STROKE); pt.setStrokeWidth(2 * dp); pt.setColor(withAlpha(outline, 1 - t));
                rr.inset(dp, dp); c.drawRoundRect(rr, h / 2 - dp, h / 2 - dp, pt);
            }
            float r = (8 + 4 * t) * dp, cx = x + h / 2 + t * (w - h), cy = y + h / 2;
            pt.setStyle(Paint.Style.FILL);
            pt.setColor(blend(outline, onPrimary, t));
            c.drawCircle(cx, cy, r, pt);
        }
    }

    /** list item: which app opens when the weather is tapped */
    View wxAppItem() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setMinimumHeight(px(72));
        r.setPadding(0, px(10), 0, px(10));
        r.addView(text("Weather app", 16, onSurface, false));
        final TextView sub = text(appLabel(sp.getString("wxApp", null), "Samsung Weather (default)"), 14, onSurfaceVar, false);
        r.addView(sub);
        r.setBackground(ripple(new ColorDrawable(Color.TRANSPARENT), 8, onSurface));
        r.setClickable(true);
        r.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { pickApp(sub); } });
        return r;
    }

    String appLabel(String pkg, String def) {
        if (pkg == null) return def;
        try { return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0)).toString(); }
        catch (Exception e) { return def; }
    }

    /** M3 dialog: 28 dp corners, surface container high, headline, scrolling list, text button */
    void pickApp(final TextView sub) {
        final android.app.Dialog dlg = new android.app.Dialog(this);
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(shape(surfHigh, 28, 0, 0));
        box.setPadding(px(24), px(24), px(24), px(16));
        TextView h = text("Open weather in", 24, onSurface, false); h.setPadding(0, 0, 0, px(16));
        box.addView(h);
        ScrollView sc = new ScrollView(this);
        LinearLayout list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL);
        sc.addView(list);
        Intent q = new Intent(Intent.ACTION_MAIN); q.addCategory(Intent.CATEGORY_LAUNCHER);
        java.util.List<android.content.pm.ResolveInfo> apps = getPackageManager().queryIntentActivities(q, 0);
        final ArrayList<String[]> items = new ArrayList<String[]>();
        items.add(new String[]{null, "Samsung Weather (default)"});
        for (android.content.pm.ResolveInfo ri : apps) items.add(new String[]{ri.activityInfo.packageName, ri.loadLabel(getPackageManager()).toString()});
        java.util.Collections.sort(items.subList(1, items.size()), new java.util.Comparator<String[]>() {
            public int compare(String[] a, String[] b) { return a[1].compareToIgnoreCase(b[1]); }
        });
        String cur = sp.getString("wxApp", null);
        for (final String[] it : items) {
            boolean sel = cur == null ? it[0] == null : cur.equals(it[0]);
            TextView row = text((sel ? "\u25C9  " : "\u25CB  ") + it[1], 16, sel ? primary : onSurface, false);
            row.setMinHeight(px(48)); row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(px(8), 0, px(8), 0);
            row.setBackground(ripple(new ColorDrawable(Color.TRANSPARENT), 8, onSurface));
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (it[0] == null) sp.edit().remove("wxApp").apply(); else sp.edit().putString("wxApp", it[0]).apply();
                    sub.setText(it[1]); dlg.dismiss();
                }
            });
            list.addView(row);
        }
        box.addView(sc, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(360)));
        LinearLayout actions = new LinearLayout(this); actions.setGravity(Gravity.END);
        actions.addView(button("Cancel", 2, new View.OnClickListener() { public void onClick(View v) { dlg.dismiss(); } }));
        box.addView(actions);
        dlg.setContentView(box);
        if (dlg.getWindow() != null) dlg.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        dlg.show();
    }

    // ------------------------------------------------------------------ actions
    void applyWallpaper() {
        Intent i = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
        i.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, new ComponentName(this, HudWallpaper.class));
        try { startActivity(i); } catch (Exception e) { startActivity(new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)); }
    }

    void updateLocStatus() {
        if (locStatus == null) return;
        boolean fg = checkSelfPermission("android.permission.ACCESS_COARSE_LOCATION") == 0;
        boolean bgp = Build.VERSION.SDK_INT < 29 || checkSelfPermission("android.permission.ACCESS_BACKGROUND_LOCATION") == 0;
        if (!sp.getBoolean("liveloc", false)) { locStatus.setText("Live location off · using the saved city"); locStatus.setTextColor(onSurfaceVar); }
        else if (!fg) { locStatus.setText("Location not allowed"); locStatus.setTextColor(error); }
        else if (!bgp) { locStatus.setText("Allowed only while in use · set “all the time” in Permissions"); locStatus.setTextColor(error); }
        else { locStatus.setText("Live location ready"); locStatus.setTextColor(primary); }
    }

    void geocode(final String q) {
        if (q.length() == 0) return;
        cityStatus.setText("Searching…"); cityStatus.setTextColor(onSurfaceVar);
        new Thread(new Runnable() {
            public void run() {
                String msg; int colr;
                try {
                    URL u = new URL("https://geocoding-api.open-meteo.com/v1/search?count=1&language=en&format=json&name="
                            + URLEncoder.encode(q, "UTF-8"));
                    JSONObject js = new JSONObject(get(u));
                    JSONArray r = js.optJSONArray("results");
                    if (r == null || r.length() == 0) { msg = "Not found: " + q; colr = error; }
                    else {
                        JSONObject o = r.getJSONObject(0);
                        String name = o.getString("name").toUpperCase(Locale.US);
                        double lat = o.getDouble("latitude"), lon = o.getDouble("longitude");
                        sp.edit().putString("cityQuery", q).putString("city", name)
                                .putString("lat", Double.toString(lat)).putString("lon", Double.toString(lon)).apply();
                        msg = String.format(Locale.US, "Saved: %s, %s (%.2f, %.2f)", name, o.optString("country_code", "?"), lat, lon);
                        colr = primary;
                    }
                } catch (Throwable e) { msg = "Lookup failed (" + e.getClass().getSimpleName() + ")"; colr = error; }
                final String m = msg; final int cc = colr;
                runOnUiThread(new Runnable() { public void run() { cityStatus.setText(m); cityStatus.setTextColor(cc); } });
            }
        }).start();
    }

    static String get(URL u) throws Exception {
        HttpURLConnection con = (HttpURLConnection) u.openConnection();
        con.setConnectTimeout(8000); con.setReadTimeout(8000);
        con.setRequestProperty("User-Agent", "TN-HUD-Live (Android live wallpaper)");
        try {
            InputStream in = con.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        } finally { con.disconnect(); }
    }
}
