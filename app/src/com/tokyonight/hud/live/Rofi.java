package com.tokyonight.hud.live;

import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Locale;

/**
 * Rofi-style launcher pop-up: a frosted-glass prompt over the HUD with up to eight live results.
 *   empty            -> most-used apps
 *   text             -> apps by name
 *   > word           -> drawer commands (Commands)
 *   > call / sms name -> matching contacts' numbers; > wa / wav / tg name -> messenger call entries
 *   = expr / arithmetic -> calculator (result copied on Enter)
 *   $ command        -> runs in Termux, output shown in the pop-up
 * Plain text also finds app shortcuts and Android settings pages, after the apps.
 * Enter or a tap runs the highlighted (top) row; a command that needs an argument is typed into the prompt instead.
 * Opened by glyph '>' (apps) and glyph C (pre-filled "> call ").
 */
final class Rofi {
    static final int MAX = 8;
    static final Object ALLOW = new Object();                     // row: grant contacts access

    /** rows other than apps, commands and contacts */
    static final class Row {
        static final int CALC = 0, TERM = 1, SETTING = 2, SHORTCUT = 3, INFO = 4, ALLOW_TERMUX = 5;
        final int kind; final String label, sub, arg; final Object ref;
        Row(int kind, String label, String sub, String arg, Object ref) { this.kind = kind; this.label = label; this.sub = sub; this.arg = arg; this.ref = ref; }
    }

    /** Android settings pages reachable by name */
    static final String[][] SETTINGS = {
            {"Wi-Fi settings", android.provider.Settings.ACTION_WIFI_SETTINGS}, {"Bluetooth settings", android.provider.Settings.ACTION_BLUETOOTH_SETTINGS},
            {"Mobile network", android.provider.Settings.ACTION_DATA_ROAMING_SETTINGS}, {"Display", android.provider.Settings.ACTION_DISPLAY_SETTINGS},
            {"Sound", android.provider.Settings.ACTION_SOUND_SETTINGS}, {"Battery", Intent.ACTION_POWER_USAGE_SUMMARY},
            {"Apps", android.provider.Settings.ACTION_APPLICATION_SETTINGS}, {"Storage", android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS},
            {"Location", android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS}, {"Security", android.provider.Settings.ACTION_SECURITY_SETTINGS},
            {"Privacy", "android.settings.PRIVACY_SETTINGS"}, {"Notifications", "android.settings.NOTIFICATION_SETTINGS"},
            {"Accessibility", android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS}, {"Date & time", android.provider.Settings.ACTION_DATE_SETTINGS},
            {"Language & keyboard", android.provider.Settings.ACTION_LOCALE_SETTINGS}, {"Developer options", android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS},
            {"About phone", android.provider.Settings.ACTION_DEVICE_INFO_SETTINGS}, {"NFC", android.provider.Settings.ACTION_NFC_SETTINGS},
            {"Hotspot & tethering", "android.settings.TETHER_SETTINGS"}, {"VPN", "android.settings.VPN_SETTINGS"},
            {"Default apps", "android.settings.MANAGE_DEFAULT_APPS_SETTINGS"}, {"Do not disturb", "android.settings.ZEN_MODE_SETTINGS"},
            {"Wallpaper", Intent.ACTION_SET_WALLPAPER}, {"Accounts", android.provider.Settings.ACTION_SYNC_SETTINGS},
            {"Data usage", "android.settings.DATA_USAGE_SETTINGS"}, {"Night light", "android.settings.NIGHT_DISPLAY_SETTINGS"},
    };

    java.util.List<Object[]> shortcuts;                            // loaded on the first plain-text query
    ArrayList<String> termOut;                                     // last "$" output, shown until the line changes
    String termFor;

    final HudLauncher l;
    final Glass g;
    final Dialog d;
    final EditText in;
    final LinearLayout res;
    final ArrayList<Object> rows = new ArrayList<Object>();

    Rofi(HudLauncher launcher, String initial) {
        l = launcher;
        g = l.glass();
        LinearLayout box = new LinearLayout(l);
        box.setOrientation(LinearLayout.VERTICAL);

        LinearLayout prompt = new LinearLayout(l);
        prompt.setGravity(Gravity.CENTER_VERTICAL);
        prompt.setPadding(g.px(18), g.px(4), g.px(18), g.px(6));
        TextView ps = g.text("❯", 18, g.ok);
        ps.setPadding(0, 0, g.px(10), 0);
        prompt.addView(ps);
        in = new EditText(l);
        in.setTypeface(l.mono); in.setTextColor(g.fg); in.setHintTextColor(g.dim);
        in.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        in.setHint("run · > for commands");
        in.setSingleLine(true);
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_FILTER);
        in.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        in.setBackground(null);
        in.setPadding(0, g.px(8), 0, g.px(8));
        prompt.addView(in, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(prompt);
        View rule = new View(l); rule.setBackgroundColor(g.rule);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, g.px(0.75f)));
        rl.setMargins(g.px(18), 0, g.px(18), g.px(4));
        box.addView(rule, rl);
        res = new LinearLayout(l);
        res.setOrientation(LinearLayout.VERTICAL);
        res.setPadding(g.px(8), 0, g.px(8), 0);
        box.addView(res);

        d = g.sheet("cmd://palette", box, null, null);
        if (d.getWindow() != null) {
            WindowManager.LayoutParams lp = d.getWindow().getAttributes();
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;   // high on the screen, clear of the keyboard
            lp.y = Math.round(l.getResources().getDisplayMetrics().heightPixels * 0.12f);
            d.getWindow().setAttributes(lp);
            d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        }
        d.setOnDismissListener(new DialogInterface.OnDismissListener() {
            public void onDismiss(DialogInterface x) { if (l.rofi == Rofi.this) l.rofi = null; }
        });

        in.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable s) { refresh(); }
        });
        in.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int id, KeyEvent ev) {
                if (ev != null && ev.getAction() != KeyEvent.ACTION_DOWN) return true;
                if (!rows.isEmpty()) pick(rows.get(0));
                return true;
            }
        });
        in.setText(initial);
        in.setSelection(in.length());
    }

    void show() {
        l.rofi = this;
        d.show();
        in.requestFocus();
        refresh();
    }

    String raw() { return in.getText().toString(); }

    void refresh() {
        rows.clear();
        String raw = raw().trim();
        if (termOut != null && !raw.equals(termFor)) termOut = null;
        if (raw.startsWith("$")) { term(raw.substring(1).trim()); render(); return; }
        if (raw.startsWith("=")) { calc(raw.substring(1), true); render(); return; }
        if (Commands.isCommandLine(raw)) {
            String[] w = Commands.split(raw);
            boolean argTyped = raw().indexOf(' ', raw().indexOf('>') + 2) > 0;   // "> call " counts as typing the argument
            if (Commands.contactCmd(w[0]) && argTyped) contacts(w[0], w[1]);
            else rows.addAll(Commands.match(w[0]));
        } else {
            if (Calc.looksLike(raw)) calc(raw, false);
            String q = HudLauncher.norm(raw);
            apps(q);
            if (!q.isEmpty()) more(q);
        }
        render();
    }

    void calc(String expr, boolean explicit) {
        try {
            String v = Calc.format(Calc.eval(expr));
            rows.add(new Row(Row.CALC, "= " + v, "enter copies", v, null));
        } catch (Exception e) {
            if (explicit) rows.add(new Row(Row.INFO, expr.trim().isEmpty() ? "= expression, e.g. = 2*pi*4" : "= ?", e.getMessage(), null, null));
        }
    }

    void term(String cmd) {
        if (!Termux.installed(l)) { rows.add(new Row(Row.INFO, "Termux isn't installed", "F-Droid or GitHub build", null, null)); return; }
        if (!Termux.allowed(l)) { rows.add(new Row(Row.ALLOW_TERMUX, "\u203A allow running Termux commands", null, null, null)); return; }
        if (cmd.isEmpty()) { rows.add(new Row(Row.INFO, "$ command runs in Termux", "output shows here", null, null)); return; }
        rows.add(new Row(Row.TERM, "$ " + cmd, "enter runs in Termux", cmd, null));
    }

    /** app shortcuts and settings pages after the apps, up to MAX rows in all */
    void more(String q) {
        if (shortcuts == null) shortcuts = l.allShortcuts();
        final ArrayList<Object[]> sc = new ArrayList<Object[]>();
        for (Object[] s : shortcuts) {
            int a = HudLauncher.score((String) s[0], q), b = HudLauncher.score(s[2] + " " + s[0], q);
            int best = Math.max(a, b);
            if (best >= 2) sc.add(new Object[]{best, s});
        }
        Collections.sort(sc, new Comparator<Object[]>() { public int compare(Object[] x, Object[] y) { return (Integer) y[0] - (Integer) x[0]; } });
        for (int k = 0; k < sc.size() && rows.size() < MAX - 1; k++) {
            Object[] s = (Object[]) sc.get(k)[1];
            rows.add(new Row(Row.SHORTCUT, (String) s[0], (String) s[2], null, s[1]));
        }
        for (String[] st : SETTINGS) {
            if (rows.size() >= MAX) break;
            if (HudLauncher.score(st[0], q) >= 2) rows.add(new Row(Row.SETTING, st[0], "settings", st[1], null));
        }
    }

    void apps(String q) {
        final long now = System.currentTimeMillis();
        ArrayList<HudLauncher.App> m = new ArrayList<HudLauncher.App>();
        final java.util.HashMap<HudLauncher.App, Integer> sc = new java.util.HashMap<HudLauncher.App, Integer>();
        for (HudLauncher.App a : l.apps) {
            if (l.hidden(a)) continue;
            int s = q.isEmpty() ? (a.now(now) > 0.05f ? 1 : 0) : HudLauncher.score(a.label, q);
            if (s > 0) { m.add(a); sc.put(a, s); }
        }
        Collections.sort(m, new Comparator<HudLauncher.App>() {
            public int compare(HudLauncher.App x, HudLauncher.App y) {
                int c = sc.get(y) - sc.get(x);
                return c != 0 ? c : Float.compare(y.now(now), x.now(now));
            }
        });
        rows.addAll(m.subList(0, Math.min(q.isEmpty() ? MAX : 5, m.size())));
    }

    void contacts(String cmd, String name) {
        if ((cmd.equals("call") || cmd.equals("sms")) && HudLauncher.isNumber(name)) { rows.add(new String[]{name, name, cmd.equals("call") ? "dial" : "text", cmd}); return; }
        if (name.isEmpty()) return;
        if (!l.hasContacts()) { rows.add(ALLOW); return; }
        for (String[] h : l.contactHits(cmd, name)) {
            if (rows.size() >= MAX) break;
            rows.add(new String[]{h[0], h[1], h[2], cmd});
        }
    }

    void render() {
        res.removeAllViews();
        if (termOut != null) {
            for (String line : termOut) {
                TextView t = g.text(line, 12, line.startsWith("!") ? 0xFFF7768E : g.fg);
                t.setPadding(g.px(14), 0, g.px(14), 0);
                t.setTextIsSelectable(true);
                res.addView(t);
            }
            res.addView(spacer());
            return;
        }
        if (rows.isEmpty()) {
            String raw = raw().trim();
            TextView t = g.text(raw.isEmpty() ? "no recent apps yet" : Commands.isCommandLine(raw) ? "no match · > help lists commands" : "no match · enter searches the web", 13, g.dim);
            t.setPadding(g.px(14), g.px(12), g.px(14), g.px(14));
            res.addView(t);
            if (!raw.isEmpty() && !Commands.isCommandLine(raw)) rows.add(Commands.match("web").get(0));
            return;
        }
        for (int i = 0; i < rows.size(); i++) {
            final Object o = rows.get(i);
            TextView t = g.text(label(o, i == 0), 16, i == 0 ? g.accent : g.fg);
            t.setSingleLine(true); t.setEllipsize(TextUtils.TruncateAt.END);
            t.setPadding(g.px(14), g.px(11), g.px(14), g.px(11));
            t.setMinHeight(g.px(44)); t.setGravity(Gravity.CENTER_VERTICAL);
            t.setBackground(i == 0 ? g.selBg() : g.pressBg(0));
            t.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { pick(o); } });
            res.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        res.addView(spacer());
    }

    View spacer() { View v = new View(l); v.setMinimumHeight(g.px(6)); return v; }

    CharSequence label(Object o, boolean top) {
        if (o == ALLOW) return "› allow contacts access";
        if (o instanceof Row) {
            Row r = (Row) o;
            String head = (r.kind == Row.SETTING ? "\u2699 " : r.kind == Row.SHORTCUT ? "\u203A " : "") + r.label, tail = r.sub == null ? "" : "   " + r.sub;
            android.text.SpannableString ss = new android.text.SpannableString(head + tail);
            ss.setSpan(new android.text.style.ForegroundColorSpan(g.dim), head.length(), ss.length(), 0);
            ss.setSpan(new android.text.style.RelativeSizeSpan(0.8f), head.length(), ss.length(), 0);
            return ss;
        }
        if (o instanceof HudLauncher.App) return ((HudLauncher.App) o).label;
        if (o instanceof Commands.Cmd) return l.commandLabel((Commands.Cmd) o, top);
        String[] c = (String[]) o;
        String head = c[0], tail = c[2].isEmpty() ? "" : "   " + c[2];
        android.text.SpannableString ss = new android.text.SpannableString(head + tail);
        ss.setSpan(new android.text.style.ForegroundColorSpan(g.dim), head.length(), ss.length(), 0);
        ss.setSpan(new android.text.style.RelativeSizeSpan(0.8f), head.length(), ss.length(), 0);
        return ss;
    }

    void pickRow(final Row r) {
        switch (r.kind) {
            case Row.CALC:
                try {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager) l.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("result", r.arg));
                    l.toast("Copied " + r.arg);
                } catch (Throwable ignored) { }
                d.dismiss();
                return;
            case Row.TERM: {
                final String line = raw().trim();
                termFor = line;
                termOut = new ArrayList<String>();
                termOut.add("running\u2026");
                render();
                try {
                    Termux.run(l, r.arg, new Termux.Result() {
                        public void on(String out, String err, int exit, String msg) {
                            termFor = line;
                            termOut = new ArrayList<String>();
                            if (msg != null) termOut.add("! " + msg);
                            for (String s : (out + (err.isEmpty() ? "" : "\n" + err)).split("\n")) if (termOut.size() < 200) termOut.add(s);
                            while (!termOut.isEmpty() && termOut.get(termOut.size() - 1).isEmpty()) termOut.remove(termOut.size() - 1);
                            termOut.add("[exit " + exit + "]");
                            if (d.isShowing()) render();
                        }
                    });
                } catch (Throwable t) {
                    termOut = new ArrayList<String>();
                    termOut.add("! Termux refused the command.");
                    termOut.add("! " + Termux.SETUP);
                    render();
                }
                return;
            }
            case Row.ALLOW_TERMUX: l.requestPermissions(new String[]{Termux.PERM}, HudLauncher.RC_TERMUX); return;
            case Row.SETTING:
                d.dismiss();
                try { l.startActivity(new Intent(r.arg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
                catch (Exception e) { try { l.startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) { } }
                return;
            case Row.SHORTCUT: d.dismiss(); l.startShortcut(r.ref); return;
            default:
        }
    }

    void pick(Object o) {
        if (o == ALLOW) { l.requestPermissions(new String[]{HudLauncher.P_CONTACTS}, HudLauncher.RC_CONTACTS); return; }
        if (o instanceof Row) { pickRow((Row) o); return; }
        if (o instanceof HudLauncher.App) { d.dismiss(); l.launch((HudLauncher.App) o); return; }
        if (o instanceof Commands.Cmd) {
            Commands.Cmd c = (Commands.Cmd) o;
            String raw = raw().trim();
            String arg = Commands.isCommandLine(raw) ? Commands.split(raw)[1] : raw;   // web fallback: the whole line is the query
            if (c.name.equals("help")) { in.setText(">"); in.setSelection(1); return; }
            if (c.needsArgs() && (arg.isEmpty() || Commands.contactCmd(c.name))) {
                String t = "> " + c.name + " " + arg;
                in.setText(t); in.setSelection(t.length());
                return;
            }
            d.dismiss();
            l.runCommand(c, arg);
            return;
        }
        String[] c = (String[]) o;
        d.dismiss();
        l.contactAction(c[3], c[1]);
    }
}
