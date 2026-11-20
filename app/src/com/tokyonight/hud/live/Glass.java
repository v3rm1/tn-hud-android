package com.tokyonight.hud.live;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Terminal-style dialogs in the Tokyo Night palette (night when dark, day when light), used for every pop-up and
 * long-press menu instead of the stock Material alert dialogs: square panel with a 1 dp accent border, a header
 * strip filled with the accent ("CMD://PALETTE · ESC ✕"), square buttons, the primary one filled.
 *
 * On Android 12+ with cross-window blur enabled the panel is translucent and blurs what is behind it (window
 * background blur, plus a light blur-behind of the whole screen). Elsewhere, or when blur is switched off
 * (battery saver, developer option), the panel falls back to a near-opaque fill so text stays readable.
 * The blur APIs are reached by reflection because the app compiles against an older platform jar.
 */
final class Glass {
    interface Pick { void on(int which); }

    // Tokyo Night (night) and Tokyo Night Day
    static final int N_BG = 0x05070D, N_FG = 0xFFC0CAF5, N_DIM = 0xFF737AA2, N_TITLE = 0xFFBB9AF7, N_ACCENT = 0xFFBB9AF7,
            N_RIM = 0xFFBB9AF7, N_PRESS = 0x24BB9AF7, N_RULE = 0xFF3B4261, N_ON = 0xFF000000, N_OK = 0xFF9ECE6A;
    static final int D_BG = 0xE1E2E7, D_FG = 0xFF3760BF, D_DIM = 0xFF6172B0, D_TITLE = 0xFF9854F1, D_ACCENT = 0xFF9854F1,
            D_RIM = 0xFF9854F1, D_PRESS = 0x249854F1, D_RULE = 0xFFA8AECB, D_ON = 0xFFFFFFFF, D_OK = 0xFF587539;

    final Context c;
    final boolean dark;
    final Typeface tf;
    final float dp;
    final boolean blur;
    final int fg, dim, title, accent, rim, press, rule, fill, onAccent, ok;

    Glass(Context c, boolean dark, Typeface tf) {
        this.c = c; this.dark = dark; this.tf = tf;
        dp = c.getResources().getDisplayMetrics().density;
        blur = blurAvailable(c);
        fg = dark ? N_FG : D_FG; dim = dark ? N_DIM : D_DIM; title = dark ? N_TITLE : D_TITLE;
        accent = dark ? N_ACCENT : D_ACCENT; rim = dark ? N_RIM : D_RIM; press = dark ? N_PRESS : D_PRESS;
        rule = dark ? N_RULE : D_RULE; onAccent = dark ? N_ON : D_ON; ok = dark ? N_OK : D_OK;
        int a = blur ? (dark ? 0xD9 : 0xE0) : (dark ? 0xFA : 0xFA);
        fill = (a << 24) | (dark ? N_BG : D_BG);
    }

    int px(float v) { return Math.round(v * dp); }

    static boolean blurAvailable(Context c) {
        if (Build.VERSION.SDK_INT < 31) return false;
        try {
            Object wm = c.getSystemService(Context.WINDOW_SERVICE);
            return (Boolean) WindowManager.class.getMethod("isCrossWindowBlurEnabled").invoke(wm);
        } catch (Throwable e) { return false; }
    }

    TextView text(CharSequence s, float sp, int col) {
        TextView t = new TextView(c);
        t.setText(s); t.setTextColor(col);
        if (tf != null) t.setTypeface(tf);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return t;
    }

    /** square press highlight (radius kept for callers, ignored: the concept has no rounded corners) */
    StateListDrawable pressBg(float radius) {
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_pressed}, new ColorDrawable(press));
        sl.addState(new int[0], new ColorDrawable(0));
        return sl;
    }

    /** selected row: faint accent fill with a 2 dp accent bar on the left */
    android.graphics.drawable.Drawable selBg() {
        android.graphics.drawable.LayerDrawable ld = new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{
                new ColorDrawable(press), new ColorDrawable(accent)});
        ld.setLayerGravity(1, Gravity.START | Gravity.FILL_VERTICAL);
        ld.setLayerWidth(1, px(2));
        return ld;
    }

    /** square button; primary = accent fill */
    TextView button(String label, boolean primary) {
        TextView b = text(label.toUpperCase(java.util.Locale.ROOT), 12, primary ? onAccent : fg);
        b.setLetterSpacing(0.06f);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(px(40));
        b.setPadding(px(14), px(8), px(14), px(8));
        if (primary) b.setTypeface(tf, Typeface.BOLD);
        GradientDrawable n = new GradientDrawable();
        if (primary) n.setColor(accent); else { n.setColor(0); n.setStroke(Math.max(1, px(1)), rule); }
        StateListDrawable sl = new StateListDrawable();
        GradientDrawable p = new GradientDrawable(); p.setColor(primary ? accent & 0xB3FFFFFF : press); p.setStroke(Math.max(1, px(1)), accent);
        sl.addState(new int[]{android.R.attr.state_pressed}, p);
        sl.addState(new int[0], n);
        b.setBackground(sl);
        return b;
    }

    /** title (may be null), a scrolling body, and right-aligned text buttons; button i calls onBtn.on(i) and closes */
    Dialog sheet(String head, View body, final String[] buttons, final Pick onBtn) {
        final Dialog d = new Dialog(c);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setCanceledOnTouchOutside(true);

        final int cap = Math.round(c.getResources().getDisplayMetrics().heightPixels * 0.75f);
        LinearLayout box = new LinearLayout(c) {
            // never taller than cap: the weighted scroll view in the middle shrinks and scrolls instead
            @Override protected void onMeasure(int w, int h) {
                if (MeasureSpec.getMode(h) == MeasureSpec.UNSPECIFIED || MeasureSpec.getSize(h) > cap)
                    h = MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST);
                super.onMeasure(w, h);
            }
        };
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, px(head == null ? 8 : 0), 0, px(buttons == null || buttons.length == 0 ? 8 : 0));

        if (head != null) {                                       // header strip: accent fill, title left, ESC ✕ right
            LinearLayout strip = new LinearLayout(c);
            strip.setGravity(Gravity.CENTER_VERTICAL);
            strip.setBackgroundColor(accent);
            strip.setPadding(px(12), 0, px(4), 0);
            TextView h = text(head.toUpperCase(java.util.Locale.ROOT), 11, onAccent);
            h.setTypeface(tf, Typeface.BOLD);
            h.setLetterSpacing(0.1f);
            h.setSingleLine(true); h.setEllipsize(android.text.TextUtils.TruncateAt.END);
            strip.addView(h, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView x = text("ESC \u2715", 11, onAccent);
            x.setTypeface(tf, Typeface.BOLD);
            x.setGravity(Gravity.CENTER);
            x.setPadding(px(10), 0, px(10), 0);
            x.setContentDescription("Close");
            x.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { d.dismiss(); } });
            strip.addView(x, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(40)));
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(36));
            sl.bottomMargin = px(6);
            box.addView(strip, sl);
        }

        ScrollView sv = new ScrollView(c);
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        sv.addView(body);
        // weight + wrap: the list takes its natural height but shrinks (and scrolls) when the dialog hits the screen cap
        box.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        if (buttons != null && buttons.length > 0) {
            LinearLayout row = new LinearLayout(c);
            row.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            row.setPadding(px(12), px(8), px(12), px(12));
            for (int i = 0; i < buttons.length; i++) {
                final int w = i;
                TextView b = button(buttons[i], i == buttons.length - 1);
                b.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
                    d.dismiss();
                    if (onBtn != null) onBtn.on(w);
                } });
                LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                bl.leftMargin = px(8);
                row.addView(b, bl);
            }
            box.addView(row);
        }

        d.setContentView(box);
        Window win = d.getWindow();
        if (win != null) {
            GradientDrawable panel = new GradientDrawable();
            panel.setColor(fill); panel.setCornerRadius(0); panel.setStroke(Math.max(1, px(1)), rim);
            win.setBackgroundDrawable(panel);   // the platform takes the blur corner radius from this GradientDrawable
            DisplayMetrics m = c.getResources().getDisplayMetrics();
            WindowManager.LayoutParams lp = win.getAttributes();
            lp.width = Math.min(m.widthPixels - px(48), px(400));
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            lp.gravity = Gravity.CENTER;
            lp.dimAmount = blur ? 0.25f : 0.45f;
            win.setAttributes(lp);
            win.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            if (blur) {
                try {
                    Window.class.getMethod("setBackgroundBlurRadius", int.class).invoke(win, px(28));
                    win.addFlags(0x4 /* FLAG_BLUR_BEHIND */);
                    WindowManager.LayoutParams a = win.getAttributes();
                    WindowManager.LayoutParams.class.getMethod("setBlurBehindRadius", int.class).invoke(a, px(6));
                    win.setAttributes(a);
                } catch (Throwable ignored) { }
            }
        }
        return d;
    }

    /** a tappable list; checked (or -1) is marked in the accent colour; a pick closes the dialog */
    Dialog list(String head, String[] items, int checked, final Pick pick) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(px(8), px(2), px(8), px(2));
        final Dialog[] ref = new Dialog[1];
        for (int i = 0; i < items.length; i++) {
            final int w = i;
            boolean on = i == checked;
            TextView t = text((checked >= 0 ? (on ? "[\u25A0] " : "[ ] ") : "\u203A ") + items[i], 15, on ? accent : fg);
            t.setPadding(px(14), px(12), px(14), px(12));
            t.setMinHeight(px(44));
            t.setGravity(Gravity.CENTER_VERTICAL);
            t.setBackground(on ? selBg() : pressBg(0));
            t.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
                if (ref[0] != null) ref[0].dismiss();
                if (pick != null) pick.on(w);
            } });
            col.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        ref[0] = sheet(head, col, null, null);
        return ref[0];
    }

    /** a message with buttons */
    Dialog message(String head, String msg, String[] buttons, Pick onBtn) {
        TextView t = text(msg, 14, fg);
        t.setLineSpacing(0, 1.15f);
        t.setPadding(px(22), px(6), px(22), px(8));
        return sheet(head, t, buttons, onBtn);
    }

    /** an input field styled for the glass panel */
    android.widget.EditText field(String s) {
        android.widget.EditText e = new android.widget.EditText(c);
        e.setText(s); e.setTextColor(fg); e.setHintTextColor(dim);
        if (tf != null) e.setTypeface(tf);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        GradientDrawable g = new GradientDrawable();
        g.setColor(dark ? 0xFF000000 : 0x40FFFFFF); g.setCornerRadius(0); g.setStroke(Math.max(1, px(1)), accent);
        e.setBackground(g);
        e.setPadding(px(14), px(10), px(14), px(10));
        return e;
    }

    /** wraps a view with the panel's side margins (for sheet bodies) */
    View padded(View v) {
        LinearLayout f = new LinearLayout(c);
        f.setPadding(px(22), px(8), px(22), px(8));
        f.addView(v, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return f;
    }
}
