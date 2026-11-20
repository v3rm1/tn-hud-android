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

    static final int FG = HudLauncher.FG, COMMENT = HudLauncher.COMMENT, BLUE = HudLauncher.BLUE, CYAN = HudLauncher.CYAN, TEXT2 = 0xFFA9B1D6;

    final Context c;
    final Host host;
    final SharedPreferences sp;
    final Typeface mono;
    final float dp;
    final boolean blur;
    final LinearLayout view, rowA, rowB, bright, notes;
    final TextView count;
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
        view.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, blur
                ? new int[]{0xB81A1B26, 0x9E1A1B26, 0xC416161E}          // translucent: the blurred screen shows through
                : new int[]{0xF21A1B26, 0xEB1A1B26, 0xF516161E}));
        int top = px(12);
        if (overlay) top += statusBarHeight(); else view.setFitsSystemWindows(true);
        view.setPadding(px(20), top, px(20), 0);
        view.setClickable(true);                                   // swallow taps on empty space

        LinearLayout head = new LinearLayout(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(tv("// NOTIFICATIONS", 14, BLUE));
        count = tv("", 14, COMMENT);
        head.addView(count, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView clear = tv("CLEAR ALL", 14, BLUE); clear.setPadding(px(10), px(10), px(10), px(10));
        clear.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { Link.send(c, "clear", null, 0); } });
        head.addView(clear);
        TextView sys = tv("SYSTEM ▾", 14, COMMENT); sys.setPadding(px(10), px(10), 0, px(10));
        sys.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { host.closeShade(); statusBar(c, false); } });
        head.addView(sys);
        view.addView(head);

        rowA = toggleRow(); rowB = toggleRow();
        view.addView(rowA, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(46)));
        view.addView(rowB, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(46)));
        bright = new LinearLayout(c);
        bright.setGravity(Gravity.CENTER_VERTICAL);
        bright.setPadding(0, px(6), 0, px(2));
        view.addView(bright);

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

    /** glass card: translucent storm fill, faint light rim, brighter while pressed */
    Drawable glassCard(float radius) {
        GradientDrawable n = new GradientDrawable(); n.setColor(0x6624283B); n.setCornerRadius(radius); n.setStroke(px(1), 0x29C0CAF5);
        GradientDrawable p = new GradientDrawable(); p.setColor(0x4D7AA2F7); p.setCornerRadius(radius); p.setStroke(px(1), 0x667AA2F7);
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_pressed}, p);
        sl.addState(new int[0], n);
        return sl;
    }

    static final int OFF = 0, ON = 1, LOCKED = 2;

    TextView chip(String label, int state, View.OnClickListener l) {
        TextView t = tv(state == LOCKED ? label + "·+" : label, 12, state == ON ? 0xFF1A1B26 : state == LOCKED ? COMMENT : FG);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        if (state == ON) { GradientDrawable g = new GradientDrawable(); g.setColor(0xE67DCFFF); g.setCornerRadius(px(10)); t.setBackground(g); }
        else t.setBackground(glassCard(px(10)));
        t.setOnClickListener(l);
        return t;
    }

    void add(LinearLayout row, TextView t) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, px(38), 1f);
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
        add(rowB, chip("WI-FI", OFF, panelIntent("android.settings.panel.action.WIFI")));
        add(rowB, chip("NET", OFF, panelIntent("android.settings.panel.action.INTERNET_CONNECTIVITY")));
        add(rowB, chip("BT", OFF, panelIntent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)));
        add(rowB, chip("VOL", OFF, panelIntent("android.settings.panel.action.VOLUME")));
        final boolean ab = Toggles.autoBright(c);
        add(rowB, chip("AUTO☀", !w ? LOCKED : ab ? ON : OFF, new View.OnClickListener() { public void onClick(View v) {
            if (!w) { explainLocked(false); return; }
            Toggles.setAutoBright(c, !ab); refillSoon();
        } }));
        bright.removeAllViews();
        if (w) {
            bright.addView(tv("☀", 14, COMMENT));
            SeekBar sb = new SeekBar(c);
            sb.setMax(255);
            sb.setProgress(Toggles.brightness(c));
            sb.setProgressTintList(android.content.res.ColorStateList.valueOf(CYAN));
            sb.setThumbTintList(android.content.res.ColorStateList.valueOf(CYAN));
            sb.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x663B4261));
            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onProgressChanged(SeekBar b, int v, boolean user) { if (user) Toggles.setBrightness(c, v); }
                public void onStartTrackingTouch(SeekBar b) { }
                public void onStopTrackingTouch(SeekBar b) { refillSoon(); }
            });
            bright.addView(sb, new LinearLayout.LayoutParams(0, px(36), 1f));
        }

        // ---- notifications
        notes.removeAllViews();
        if (!Link.installed(c) || !Link.live()) {
            count.setText("");
            TextView m = tv(!Link.installed(c) ? "INSTALL TN-HUD LINK TO SEE NOTIFICATIONS HERE.\n\nIT HOLDS NOTIFICATION ACCESS SO THIS APP DOESN'T HAVE TO. SEE HUD SETTINGS › PERMISSIONS."
                    : "TN-HUD LINK IS INSTALLED BUT HAS NO NOTIFICATION ACCESS (OR HASN'T REPORTED YET).", 15, COMMENT);
            m.setPadding(px(4), px(24), px(4), px(12));
            notes.addView(m);
            TextView open = tv("› OPEN HUD SETTINGS", 15, BLUE);
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
        count.setText("  · " + n);
        if (n == 0) { TextView m = tv("ALL CLEAR", 16, COMMENT); m.setPadding(px(4), px(24), 0, 0); notes.addView(m); }

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
            for (int k = 0; k < (open ? idx.size() : 1); k++) notes.addView(noteRow(idx.get(k), now), lp(idx.size() > 1 ? 4 : 8));
        }
        TextView hint = tv("TAP OPENS · SWIPE SIDEWAYS DISMISSES · SWIPE UP AT THE END CLOSES", 11, COMMENT);
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
        card.setPadding(px(14), px(12), px(14), px(8));
        card.setBackground(glassCard(px(14)));
        card.addView(tv(((Link.mApp == null ? "MEDIA" : Link.mApp) + (Link.mState == 3 ? "  ·  PLAYING" : "  ·  PAUSED")).toUpperCase(Locale.ROOT), 12, COMMENT));
        TextView t = tv(Link.mTitle == null ? "" : Link.mTitle, 16, FG);
        t.setSingleLine(true); t.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(t);
        if (Link.mArtist != null) { TextView a = tv(Link.mArtist, 14, TEXT2); a.setSingleLine(true); a.setEllipsize(TextUtils.TruncateAt.END); card.addView(a); }
        LinearLayout ctl = new LinearLayout(c);
        ctl.setGravity(Gravity.CENTER);
        String[] k = {"prev", "toggle", "next"};
        String[] g = {"⏮", Link.mState == 3 ? "⏸" : "▶", "⏭"};
        for (int i = 0; i < 3; i++) {
            final String cmd = k[i];
            TextView b = tv(g[i], 22, i == 1 ? CYAN : FG);
            b.setGravity(Gravity.CENTER);
            b.setPadding(px(18), px(6), px(18), px(6));
            b.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { Link.send(c, "media", cmd, 0); refillSoon(); } });
            ctl.addView(b);
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
        TextView t = tv((open ? "▾ " : "▸ ") + app.toUpperCase(Locale.ROOT) + "  · " + idx.size(), 12, BLUE);
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
        row.setPadding(px(14), px(12), px(14), px(12));
        row.setBackground(glassCard(px(14)));
        long ago = Math.max(0, now - (i < Link.nWhen.length ? Link.nWhen[i] : now)) / 60000;
        String when = ago < 1 ? "NOW" : ago < 60 ? ago + "M" : ago < 1440 ? (ago / 60) + "H" : (ago / 1440) + "D";
        LinearLayout meta = new LinearLayout(c);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        meta.addView(tv(Link.nApp[i].toUpperCase(Locale.ROOT) + "  ·  " + when, 12, COMMENT), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final boolean canReply = i < Link.nReply.length && Link.nReply[i];
        if (canReply) {
            TextView rp = tv("REPLY", 12, BLUE);
            rp.setPadding(px(10), px(2), 0, px(2));
            rp.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
                replyKey = Link.nKey[i].equals(replyKey) ? null : Link.nKey[i];
                replyDraft = "";
                fill();
            } });
            meta.addView(rp);
        }
        row.addView(meta);
        if (Link.nTitle[i].length() > 0) row.addView(tv(Link.nTitle[i], 16, FG));
        if (Link.nText[i].length() > 0) { TextView tx = tv(Link.nText[i], 14, TEXT2); tx.setMaxLines(3); tx.setEllipsize(TextUtils.TruncateAt.END); row.addView(tx); }
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
        GradientDrawable g = new GradientDrawable(); g.setColor(0x33000000); g.setCornerRadius(px(10)); g.setStroke(px(1), 0x807AA2F7);
        e.setBackground(g);
        e.setPadding(px(12), px(8), px(12), px(8));
        r.addView(e, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView send = tv("SEND", 13, CYAN);
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
