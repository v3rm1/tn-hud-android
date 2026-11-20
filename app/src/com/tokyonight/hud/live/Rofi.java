package com.tokyonight.hud.live;

import android.app.Dialog;
import android.content.DialogInterface;
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
 * Enter or a tap runs the highlighted (top) row; a command that needs an argument is typed into the prompt instead.
 * Opened by glyph '>' (apps) and glyph C (pre-filled "> call ").
 */
final class Rofi {
    static final int MAX = 8;
    static final Object ALLOW = new Object();                     // row: grant contacts access

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
        TextView ps = g.text("❯", 18, g.accent);
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

        d = g.sheet(null, box, null, null);
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
        if (Commands.isCommandLine(raw)) {
            String[] w = Commands.split(raw);
            boolean argTyped = raw().indexOf(' ', raw().indexOf('>') + 2) > 0;   // "> call " counts as typing the argument
            if (Commands.contactCmd(w[0]) && argTyped) contacts(w[0], w[1]);
            else rows.addAll(Commands.match(w[0]));
        } else apps(HudLauncher.norm(raw));
        render();
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
        rows.addAll(m.subList(0, Math.min(MAX, m.size())));
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
            t.setBackground(g.pressBg(g.px(10)));
            if (i == 0) { android.graphics.drawable.GradientDrawable hl = new android.graphics.drawable.GradientDrawable(); hl.setColor(g.press); hl.setCornerRadius(g.px(10)); t.setBackground(hl); }
            t.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { pick(o); } });
            res.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        res.addView(spacer());
    }

    View spacer() { View v = new View(l); v.setMinimumHeight(g.px(6)); return v; }

    CharSequence label(Object o, boolean top) {
        if (o == ALLOW) return "› allow contacts access";
        if (o instanceof HudLauncher.App) return ((HudLauncher.App) o).label;
        if (o instanceof Commands.Cmd) return l.commandLabel((Commands.Cmd) o, top);
        String[] c = (String[]) o;
        String head = c[0], tail = c[2].isEmpty() ? "" : "   " + c[2];
        android.text.SpannableString ss = new android.text.SpannableString(head + tail);
        ss.setSpan(new android.text.style.ForegroundColorSpan(g.dim), head.length(), ss.length(), 0);
        ss.setSpan(new android.text.style.RelativeSizeSpan(0.8f), head.length(), ss.length(), 0);
        return ss;
    }

    void pick(Object o) {
        if (o == ALLOW) { l.requestPermissions(new String[]{HudLauncher.P_CONTACTS}, HudLauncher.RC_CONTACTS); return; }
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
