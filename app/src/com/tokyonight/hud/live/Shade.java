package com.tokyonight.hud.live;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The HUD notification panel as a self-contained view, so the launcher (inside its own window) and ShadeService
 * (an accessibility overlay over any app) show the same thing: quick toggles, a brightness slider, the playing
 * media, and notifications grouped by app, with swipe-to-dismiss and inline reply (through TN-HUD Link).
 */
final class Shade {
    interface Host {
        /** the panel asks to be closed (swipe up at the end, a notification opened, ...) */
        void closeShade();
    }

    static final int FG = HudLauncher.FG, COMMENT = HudLauncher.COMMENT, BLUE = HudLauncher.BLUE, CYAN = HudLauncher.CYAN, TEXT2 = HudLauncher.TEXT2,
            ACCENT = HudLauncher.ACCENT, GREEN = HudLauncher.GREEN, YELLOW = HudLauncher.YELLOW, RED = HudLauncher.RED, LINE = HudLauncher.LINE,
            INK = HudLauncher.INK;

    final Context c;
    final Host host;
    final SharedPreferences sp;
    final Typeface mono;
    final float dp;
    final boolean blur;
    final LinearLayout view, rowA, rowB, bright, notes;
    final TextView count, sysInfo, batInfo;
    final HashSet<String> expanded = new HashSet<String>();
    String replyKey;                                            // notification whose reply box is open
    String replyDraft = "";                                      // survives the rebuild on every Link update

    Shade(Context ctx, Host h, Typeface tf, boolean overlay) {
        c = ctx; host = h; mono = tf;
        sp = c.getSharedPreferences("hud", Context.MODE_PRIVATE);
        dp = c.getResources().getDisplayMetrics().density;
        blur = Glass.blurAvailable(c);

        view = new LinearLayout(c);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setBackgroundColor(blur ? 0xD9000000 : 0xF7000000);   // AMOLED black; translucent when the screen behind is blurred
        int top = px(12);
        if (overlay) top += statusBarHeight(); else view.setFitsSystemWindows(true);
        view.setPadding(px(20), top, px(20), 0);
        view.setClickable(true);                                   // swallow taps on empty space

        // ---- expanded status header: big clock, date / uptime (left), battery (right)
        LinearLayout head = new LinearLayout(c);
        head.setGravity(Gravity.TOP);
        LinearLayout left = new LinearLayout(c);
        left.setOrientation(LinearLayout.VERTICAL);
        android.widget.TextClock clock = new android.widget.TextClock(c);
        clock.setFormat24Hour("HH:mm"); clock.setFormat12Hour("h:mm");
        clock.setTypeface(mono); clock.setTextColor(FG);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 44);
        clock.setIncludeFontPadding(false);
        left.addView(clock);
        sysInfo = tv("", 11, COMMENT);
        sysInfo.setPadding(0, px(4), 0, 0);
        left.addView(sysInfo);
        head.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        batInfo = tv("", 11, FG);
        batInfo.setGravity(Gravity.END);
        batInfo.setLineSpacing(px(2), 1f);
        head.addView(batInfo);
        view.addView(head);

        rowA = toggleRow(); rowB = toggleRow();
        LinearLayout.LayoutParams ra = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(52));
        ra.topMargin = px(10);
        view.addView(rowA, ra);
        view.addView(rowB, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(52)));
        bright = new LinearLayout(c);
        bright.setGravity(Gravity.CENTER_VERTICAL);
        bright.setPadding(0, px(6), 0, px(2));
        view.addView(bright);

        // ---- "$ tail -f /var/log/notif" ............ N entries
        LinearLayout logHead = new LinearLayout(c);
        logHead.setGravity(Gravity.CENTER_VERTICAL);
        logHead.setPadding(0, px(8), 0, px(4));
        logHead.addView(tv("$ tail -f /var/log/notif", 11, COMMENT), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        count = tv("", 11, COMMENT);
        logHead.addView(count);
        view.addView(logHead);

        ScrollView sv = new ScrollView(c);
        sv.setVerticalScrollBarEnabled(false);
        notes = new LinearLayout(c);
        notes.setOrientation(LinearLayout.VERTICAL);
        sv.addView(notes);
        sv.setOnTouchListener(new View.OnTouchListener() {         // swipe up from the end of the list closes the panel
            float y0;
            public boolean onTouch(View v, MotionEvent e) {
                ScrollView s = (ScrollView) v;
                boolean atEnd = s.getChildCount() == 0 || s.getScrollY() + s.getHeight() >= s.getChildAt(0).getHeight() - 2;
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) y0 = e.getRawY();
                else if (e.getActionMasked() == MotionEvent.ACTION_UP && atEnd && y0 - e.getRawY() > 140 * dp) { host.closeShade(); return true; }
                return false;
            }
        });
        view.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---- bottom bar: CLEAR ALL / SYSTEM / settings
        LinearLayout bar = new LinearLayout(c);
        bar.setPadding(0, px(8), 0, px(16));
        TextView clear = button("CLEAR ALL \u232B", RED, RED);
        clear.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { Link.send(c, "clear", null, 0); } });
        TextView sys = button("SYSTEM \u25BE", TEXT2, LINE);
        sys.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { host.closeShade(); statusBar(c, false); } });
        TextView gear = button("\u2699", TEXT2, LINE);
        gear.setContentDescription("HUD settings");
        gear.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            host.closeShade();
            try { c.startActivity(new Intent(c, SetWallpaper.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) { }
        } });
        LinearLayout.LayoutParams b1 = new LinearLayout.LayoutParams(0, px(44), 1f); b1.rightMargin = px(8);
        LinearLayout.LayoutParams b2 = new LinearLayout.LayoutParams(0, px(44), 1f); b2.rightMargin = px(8);
        bar.addView(clear, b1); bar.addView(sys, b2); bar.addView(gear, new LinearLayout.LayoutParams(px(44), px(44)));
        view.addView(bar);
    }

    /** concept's square button: 1 dp border, text centred, accent tint while pressed */
    TextView button(String label, int col, int border) {
        TextView t = tv(label, 12, col);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setBackground(box(border, 0));
        return t;
    }

    Drawable box(int border, int fill) {
        GradientDrawable n = new GradientDrawable(); n.setColor(fill); n.setStroke(px(1), border);
        GradientDrawable p = new GradientDrawable(); p.setColor(0x33BB9AF7); p.setStroke(px(1), ACCENT);
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_pressed}, p);
        sl.addState(new int[0], n);
        return sl;
    }

    /** "FRI 20 NOV · up 3d 04:11" and the battery block */
    void status() {
        long up = android.os.SystemClock.elapsedRealtime() / 60000;
        sysInfo.setText(String.format(Locale.US, "%ta %<td %<tb", System.currentTimeMillis()).toUpperCase(Locale.US)
                + String.format(Locale.US, " \u00B7 up %dd %02d:%02d", up / 1440, (up / 60) % 24, up % 60));
        Intent b = c.registerReceiver(null, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b == null) { batInfo.setText(""); return; }
        int lvl = b.getIntExtra("level", -1), sc = b.getIntExtra("scale", 100), plug = b.getIntExtra("plugged", 0);
        int pct = lvl < 0 ? -1 : Math.round(lvl * 100f / Math.max(1, sc));
        float t = b.getIntExtra("temperature", Integer.MIN_VALUE) / 10f;
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        HudLauncher.span(sb, "BAT ", COMMENT); HudLauncher.span(sb, pct < 0 ? "--" : pct + "%", FG);
        HudLauncher.span(sb, plug != 0 ? " CHG" : "", plug != 0 ? GREEN : FG);
        if (t > -100) { HudLauncher.span(sb, "\nT ", COMMENT); HudLauncher.span(sb, String.format(Locale.US, "%.1f\u00B0C", t), t >= 40 ? RED : FG); }
        try {
            android.os.BatteryManager bm = (android.os.BatteryManager) c.getSystemService(Context.BATTERY_SERVICE);
            int ua = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
            if (ua != Integer.MIN_VALUE && ua != 0) {
                int ma = Math.abs(ua) > 20000 ? ua / 1000 : ua;   // some devices report mA, most µA
                HudLauncher.span(sb, "\nI ", COMMENT); HudLauncher.span(sb, String.format(Locale.US, "%+dmA", ma), ma > 0 ? GREEN : YELLOW);
            }
        } catch (Throwable ignored) { }
        batInfo.setText(sb);
    }

    int px(float v) { return Math.round(v * dp); }

    int statusBarHeight() {
        int id = c.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? c.getResources().getDimensionPixelSize(id) : px(24);
    }

    TextView tv(String s, float sp_, int col) {
        TextView t = new TextView(c);
        t.setText(s); t.setTextColor(col); t.setTypeface(mono);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp_);
        return t;
    }

    LinearLayout toggleRow() { LinearLayout r = new LinearLayout(c); r.setOrientation(LinearLayout.HORIZONTAL); return r; }

    void toast(String s) { android.widget.Toast.makeText(c, s, android.widget.Toast.LENGTH_SHORT).show(); }

    /** log entry: dashed rule along the bottom, accent tint while pressed */
    Drawable logLine() {
        GradientDrawable d = new GradientDrawable(); d.setColor(0); d.setStroke(px(1), LINE, px(4), px(3));
        android.graphics.drawable.InsetDrawable bottom = new android.graphics.drawable.InsetDrawable(d, -px(2), -px(2), -px(2), 0);
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_pressed}, new android.graphics.drawable.LayerDrawable(new Drawable[]{
                new android.graphics.drawable.ColorDrawable(0x22BB9AF7), bottom}));
        sl.addState(new int[0], bottom);
        return sl;
    }

    static final int OFF = 0, ON = 1, LOCKED = 2;

    /** toggle tile: "[■]" + label in the accent while on, "[ ]" muted while off, "[+]" when a grant is missing */
    TextView chip(String label, int state, View.OnClickListener l) {
        String mark = state == ON ? "[\u25A0]" : state == LOCKED ? "[+]" : "[ ]";
        TextView t = tv(mark + "\n" + label, 11, state == ON ? ACCENT : state == LOCKED ? COMMENT : TEXT2);
        t.setGravity(Gravity.CENTER);
        t.setMaxLines(2);
        if (state == ON) t.setTypeface(mono, Typeface.BOLD);
        t.setBackground(box(state == ON ? ACCENT : LINE, state == ON ? 0x1ABB9AF7 : 0));
        t.setContentDescription(label + (state == ON ? " on" : state == LOCKED ? " needs permission" : " off"));
        t.setOnClickListener(l);
        return t;
    }

    /** settings panel launcher (not a toggle): "›" + label */
    TextView panelChip(String label, View.OnClickListener l) {
        TextView t = tv("\u203A\n" + label, 11, TEXT2);
        t.setGravity(Gravity.CENTER);
        t.setMaxLines(2);
        t.setBackground(box(LINE, 0));
        t.setOnClickListener(l);
        return t;
    }

    void add(LinearLayout row, TextView t) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, px(46), 1f);
        lp.rightMargin = px(6); lp.topMargin = px(6);
        row.addView(t, lp);
    }

    final Runnable refill = new Runnable() { public void run() { fill(); } };
    void refillSoon() { view.removeCallbacks(refill); view.postDelayed(refill, 300); }

    View.OnClickListener panelIntent(final String action) {
        return new View.OnClickListener() { public void onClick(View v) {
            try { c.startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); host.closeShade(); } catch (Exception e) { statusBar(c, true); }
        } };
    }

    /** WRITE_SETTINGS is a user switch in Android settings; WRITE_SECURE_SETTINGS needs a one-time adb grant */
    void explainLocked(boolean secure) {
        if (!secure) {
            try { c.startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:" + c.getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); host.closeShade(); }
            catch (Exception ignored) { }
            return;
        }
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("adb", Toggles.ADB_GRANT));
        } catch (Throwable ignored) { }
        toast("Needs a one-time adb grant (copied):\n" + Toggles.ADB_GRANT);
    }

    void fill() {
        status();
        // ---- toggles
        rowA.removeAllViews(); rowB.removeAllViews();
        final boolean w = Toggles.canWrite(c), s = Toggles.canSecure(c);
        add(rowA, chip("TORCH", Toggles.torchOn ? ON : OFF, new View.OnClickListener() { public void onClick(View v) { Toggles.torch(c); refillSoon(); } }));
        final boolean dndOn = Link.live() && Link.dnd > 1;
        add(rowA, chip("DND", dndOn ? ON : OFF, new View.OnClickListener() { public void onClick(View v) {
            if (Link.live()) { Link.send(c, "dnd", null, dndOn ? 1 : 2); refillSoon(); }
            else toast("DND needs TN-HUD Link with notification access");
        } }));
        final boolean saver = Toggles.saver(c);
        add(rowA, chip("SAVER", !s ? LOCKED : saver ? ON : OFF, new View.OnClickListener() { public void onClick(View v) {
            if (!s) { explainLocked(true); return; }
            if (!Toggles.setSaver(c, !saver)) toast("Battery saver didn't switch");
            refillSoon();
        } }));
        final boolean loc = Toggles.location(c);
        add(rowA, chip("LOC", !s ? LOCKED : loc ? ON : OFF, new View.OnClickListener() { public void onClick(View v) {
            if (!s) { explainLocked(true); return; }
            if (!Toggles.setLocation(c, !loc)) toast("Location didn't switch");
            refillSoon();
        } }));
        final boolean rot = Toggles.autoRotate(c);
        add(rowA, chip("ROTATE", !w ? LOCKED : rot ? ON : OFF, new View.OnClickListener() { public void onClick(View v) {
            if (!w) { explainLocked(false); return; }
            Toggles.setAutoRotate(c, !rot); refillSoon();
        } }));
        add(rowB, panelChip("WI-FI", panelIntent("android.settings.panel.action.WIFI")));
        add(rowB, panelChip("NET", panelIntent("android.settings.panel.action.INTERNET_CONNECTIVITY")));
        add(rowB, panelChip("BT", panelIntent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)));
        add(rowB, panelChip("VOL", panelIntent("android.settings.panel.action.VOLUME")));
        final boolean ab = Toggles.autoBright(c);
        add(rowB, chip("AUTO BRT", !w ? LOCKED : ab ? ON : OFF, new View.OnClickListener() { public void onClick(View v) {
            if (!w) { explainLocked(false); return; }
            Toggles.setAutoBright(c, !ab); refillSoon();
        } }));
        bright.removeAllViews();
        if (w) {
            TextView bl = tv("BRT", 12, COMMENT);
            bright.addView(bl, new LinearLayout.LayoutParams(px(36), ViewGroup.LayoutParams.WRAP_CONTENT));
            final TextView pct = tv("", 12, FG);
            pct.setGravity(Gravity.END);
            SeekBar sb = new SeekBar(c);
            sb.setMax(255);
            sb.setProgress(Toggles.brightness(c));
            pct.setText(Math.round(sb.getProgress() * 100f / 255) + "%");
            sb.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
            sb.setThumbTintList(android.content.res.ColorStateList.valueOf(ACCENT));
            sb.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF3B4261));
            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onProgressChanged(SeekBar b, int v, boolean user) { pct.setText(Math.round(v * 100f / 255) + "%"); if (user) Toggles.setBrightness(c, v); }
                public void onStartTrackingTouch(SeekBar b) { }
                public void onStopTrackingTouch(SeekBar b) { refillSoon(); }
            });
            bright.addView(sb, new LinearLayout.LayoutParams(0, px(40), 1f));
            bright.addView(pct, new LinearLayout.LayoutParams(px(44), ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        // ---- notifications
        notes.removeAllViews();
        if (!Link.installed(c) || !Link.live()) {
            count.setText("");
            TextView m = tv(!Link.installed(c) ? "INSTALL TN-HUD LINK TO SEE NOTIFICATIONS HERE.\n\nIT HOLDS NOTIFICATION ACCESS SO THIS APP DOESN'T HAVE TO. SEE HUD SETTINGS › PERMISSIONS."
                    : "TN-HUD LINK IS INSTALLED BUT HAS NO NOTIFICATION ACCESS (OR HASN'T REPORTED YET).", 15, COMMENT);
            m.setPadding(px(4), px(24), px(4), px(12));
            notes.addView(m);
            TextView open = tv("› OPEN HUD SETTINGS", 15, ACCENT);
            open.setPadding(px(4), px(8), px(4), px(8));
            open.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
                host.closeShade();
                try { c.startActivity(new Intent(c, SetWallpaper.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) { }
            } });
            notes.addView(open);
            return;
        }
        if (Link.mediaActive()) notes.addView(mediaCard(), lp(8));
        int n = Link.nKey.length;
        count.setText(n + (n == 1 ? " entry" : " entries"));
        if (n == 0) { TextView m = tv("-- no entries --", 13, COMMENT); m.setPadding(px(4), px(16), 0, 0); notes.addView(m); }

        LinkedHashMap<String, ArrayList<Integer>> groups = new LinkedHashMap<String, ArrayList<Integer>>();   // newest app first
        for (int i = 0; i < n; i++) {
            String p = i < Link.nPkg.length ? Link.nPkg[i] : "";
            ArrayList<Integer> l = groups.get(p);
            if (l == null) { l = new ArrayList<Integer>(); groups.put(p, l); }
            l.add(i);
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<String, ArrayList<Integer>> g : groups.entrySet()) {
            ArrayList<Integer> idx = g.getValue();
            if (idx.size() > 1) notes.addView(groupHead(g.getKey(), idx), lp(12));
            boolean open = idx.size() == 1 || expanded.contains(g.getKey());
            for (int k = 0; k < (open ? idx.size() : 1); k++) notes.addView(noteRow(idx.get(k), now), lp(0));
        }
        TextView hint = tv("tap opens \u00B7 swipe sideways dismisses \u00B7 swipe up at the end closes", 10, COMMENT);
        hint.setPadding(px(4), px(14), 0, px(24));
        notes.addView(hint);
    }

    LinearLayout.LayoutParams lp(int topDp) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        l.topMargin = px(topDp);
        return l;
    }

    View mediaCard() {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(px(12), px(10), px(12), px(10));
        card.setBackground(box(LINE, 0));
        card.addView(tv("/dev/audio \u00B7 " + (Link.mApp == null ? "media" : Link.mApp) + (Link.mState == 3 ? "" : " \u00B7 paused"), 10, ACCENT));
        TextView t = tv(Link.mTitle == null ? "" : Link.mTitle, 15, FG);
        t.setTypeface(mono, Typeface.BOLD);
        t.setSingleLine(true); t.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(t);
        if (Link.mArtist != null) { TextView a = tv(Link.mArtist, 12, COMMENT); a.setSingleLine(true); a.setEllipsize(TextUtils.TruncateAt.END); card.addView(a); }
        LinearLayout ctl = new LinearLayout(c);
        ctl.setGravity(Gravity.END);
        ctl.setPadding(0, px(8), 0, 0);
        String[] k = {"prev", "toggle", "next"};
        String[] g = {"|\u25C0", Link.mState == 3 ? "\u275A\u275A" : "\u25B6", "\u25B6|"};
        String[] cd = {"Previous", Link.mState == 3 ? "Pause" : "Play", "Next"};
        for (int i = 0; i < 3; i++) {
            final String cmd = k[i];
            TextView b = tv(g[i], 14, i == 1 ? 0xFF000000 : FG);
            b.setGravity(Gravity.CENTER);
            b.setContentDescription(cd[i]);
            if (i == 1) { GradientDrawable f = new GradientDrawable(); f.setColor(ACCENT); b.setBackground(f); }
            else b.setBackground(box(LINE, 0));
            b.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { Link.send(c, "media", cmd, 0); refillSoon(); } });
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(px(44), px(44)); bl.leftMargin = px(4);
            ctl.addView(b, bl);
        }
        card.addView(ctl);
        if (Link.mPkg != null) card.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            try { Intent i = c.getPackageManager().getLaunchIntentForPackage(Link.mPkg); if (i != null) { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); host.closeShade(); } }
            catch (Exception ignored) { }
        } });
        return card;
    }

    View groupHead(final String pkg, final ArrayList<Integer> idx) {
        LinearLayout r = new LinearLayout(c);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(px(4), px(2), 0, px(2));
        final boolean open = expanded.contains(pkg);
        int i0 = idx.get(0);
        String app = i0 < Link.nApp.length ? Link.nApp[i0] : pkg;
        TextView t = tv((open ? "▾ " : "▸ ") + app.toLowerCase(Locale.ROOT) + "  · " + idx.size(), 12, ACCENT);
        r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView x = tv("CLEAR", 12, COMMENT); x.setPadding(px(10), px(6), px(4), px(6));
        x.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            for (int i : idx) if (i < Link.nClear.length && Link.nClear[i]) Link.send(c, "dismiss", Link.nKey[i], 0);
        } });
        r.addView(x);
        r.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            if (open) expanded.remove(pkg); else expanded.add(pkg);
            fill();
        } });
        return r;
    }

    View noteRow(final int i, long now) {
        final LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(px(4), px(10), px(4), px(10));
        row.setBackground(logLine());
        long at = i < Link.nWhen.length ? Link.nWhen[i] : now;
        String when = now - at > 86400000L ? String.format(Locale.US, "%td%<tb", at).toUpperCase(Locale.US) : String.format(Locale.US, "%tH:%<tM", at);
        final boolean canReply = i < Link.nReply.length && Link.nReply[i];
        String pkg = i < Link.nPkg.length ? Link.nPkg[i] : "";
        String lvl = pkg.startsWith("com.tokyonight") ? "SKY" : canReply ? "MSG" : "INFO";
        int lc = lvl.equals("SKY") ? GREEN : lvl.equals("MSG") ? CYAN : BLUE;
        LinearLayout meta = new LinearLayout(c);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        android.text.SpannableStringBuilder mb = new android.text.SpannableStringBuilder();
        HudLauncher.span(mb, when + "  ", COMMENT);
        int l0 = mb.length(); HudLauncher.span(mb, String.format(Locale.US, "%-4s", lvl), lc);
        mb.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), l0, mb.length(), 0);
        HudLauncher.span(mb, "  " + Link.nApp[i].toLowerCase(Locale.ROOT), TEXT2);
        TextView mt = tv("", 11, COMMENT); mt.setText(mb);
        meta.addView(mt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (canReply) {
            TextView rp = tv("REPLY", 11, ACCENT);
            rp.setPadding(px(10), px(2), 0, px(2));
            rp.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
                replyKey = Link.nKey[i].equals(replyKey) ? null : Link.nKey[i];
                replyDraft = "";
                fill();
            } });
            meta.addView(rp);
        }
        row.addView(meta);
        if (Link.nTitle[i].length() > 0) { TextView tt = tv(Link.nTitle[i], 14, FG); tt.setPadding(0, px(4), 0, 0); row.addView(tt); }
        if (Link.nText[i].length() > 0) { TextView tx = tv(Link.nText[i], 13, TEXT2); tx.setMaxLines(3); tx.setEllipsize(TextUtils.TruncateAt.END); row.addView(tx); }
        if (canReply && Link.nKey[i].equals(replyKey)) row.addView(replyBox(Link.nKey[i]));
        row.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            PendingIntent pi = i < Link.nIntent.length ? Link.nIntent[i] : null;
            try {
                if (pi != null) sendPending(c, pi);
                else c.startActivity(c.getPackageManager().getLaunchIntentForPackage(Link.nPkg[i]).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                if (i < Link.nClear.length && Link.nClear[i]) Link.send(c, "dismiss", Link.nKey[i], 0);
                host.closeShade();
            } catch (Throwable ignored) { }
        } });
        final boolean clearable = i < Link.nClear.length && Link.nClear[i];
        row.setOnTouchListener(new View.OnTouchListener() {         // sideways swipe dismisses; a still tap opens
            float x0, y0; boolean drag;
            final int slop = ViewConfiguration.get(c).getScaledTouchSlop();
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: x0 = e.getRawX(); y0 = e.getRawY(); drag = false; return false;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = e.getRawX() - x0, dy = e.getRawY() - y0;
                        if (!drag && clearable && Math.abs(dx) > slop && Math.abs(dx) > 1.5f * Math.abs(dy)) {
                            drag = true; v.getParent().requestDisallowInterceptTouchEvent(true); v.setPressed(false);
                        }
                        if (drag) { v.setTranslationX(dx); v.setAlpha(1 - Math.min(1f, Math.abs(dx) / v.getWidth())); return true; }
                        return false;
                    }
                    case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL: {
                        if (!drag) return false;
                        float dx = e.getRawX() - x0;
                        if (e.getActionMasked() == MotionEvent.ACTION_UP && Math.abs(dx) > v.getWidth() * 0.35f) {
                            v.animate().translationX(Math.signum(dx) * v.getWidth()).alpha(0).setDuration(140).start();
                            Link.send(c, "dismiss", Link.nKey[i], 0);
                        } else v.animate().translationX(0).alpha(1).setDuration(140).start();
                        drag = false;
                        return true;
                    }
                }
                return false;
            }
        });
        return row;
    }

    View replyBox(final String key) {
        LinearLayout r = new LinearLayout(c);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, px(8), 0, 0);
        final EditText e = new EditText(c);
        e.setTypeface(mono); e.setTextColor(FG); e.setHintTextColor(COMMENT);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        e.setHint("reply");
        e.setText(replyDraft);
        e.setSelection(e.length());
        e.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int n) { }
            public void onTextChanged(CharSequence s, int a, int b, int n) { }
            public void afterTextChanged(android.text.Editable s) { replyDraft = s.toString(); }
        });
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        e.setMaxLines(4);
        e.setImeOptions(EditorInfo.IME_ACTION_SEND);
        GradientDrawable g = new GradientDrawable(); g.setColor(INK); g.setStroke(px(1), ACCENT);
        e.setBackground(g);
        e.setPadding(px(12), px(8), px(12), px(8));
        r.addView(e, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView send = tv("SEND", 13, ACCENT);
        send.setPadding(px(14), px(10), px(4), px(10));
        final Runnable go = new Runnable() { public void run() {
            String t = e.getText().toString().trim();
            if (t.isEmpty()) return;
            Link.reply(c, key, t);
            replyKey = null; replyDraft = "";
            toast("Sent");
            hideKeyboard(e);
            fill();
        } };
        send.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { go.run(); } });
        e.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int id, android.view.KeyEvent ev) { go.run(); return true; }
        });
        r.addView(send);
        e.post(new Runnable() { public void run() {
            e.requestFocus();
            android.view.inputmethod.InputMethodManager im = (android.view.inputmethod.InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (im != null) im.showSoftInput(e, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        } });
        return r;
    }

    void hideKeyboard(View v) {
        android.view.inputmethod.InputMethodManager im = (android.view.inputmethod.InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    /** a notification's content intent; on Android 14+ the sender has to opt in to lending its background-start rights */
    static void sendPending(Context c, PendingIntent pi) throws Exception {
        android.os.Bundle opt = null;
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            try {
                android.app.ActivityOptions o = android.app.ActivityOptions.makeBasic();
                o.getClass().getMethod("setPendingIntentBackgroundActivityStartMode", int.class).invoke(o, 1);   // MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                opt = o.toBundle();
            } catch (Throwable ignored) { }
        }
        if (opt == null) pi.send();
        else pi.send(c, 0, null, null, null, null, opt);
    }

    static void statusBar(Context c, boolean qs) {
        try {
            Object sbm = c.getSystemService("statusbar");
            Class.forName("android.app.StatusBarManager").getMethod(qs ? "expandSettingsPanel" : "expandNotificationsPanel").invoke(sbm);
        } catch (Throwable t) { /* hidden API refused: the normal swipe from the status bar still works */ }
    }
}
