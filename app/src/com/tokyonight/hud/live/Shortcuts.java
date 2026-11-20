package com.tokyonight.hud.live;

import android.content.SharedPreferences;

/** shortcut row storage: pref "shortcuts" = lines of "package\tLABEL"; "@dial" = the default phone app */
final class Shortcuts {
    static final String DIAL = "@dial", KEY = "shortcuts";
    static final String DEF = "com.whatsapp\tWHATSAPP\ncom.facebook.orca\tMESSENGER\n@dial\tPHONE\ncom.google.android.gm\tGMAIL";

    static String[][] load(SharedPreferences sp) {
        String raw = sp.getString(KEY, DEF);
        if (raw == null || raw.trim().isEmpty()) return new String[0][];
        String[] lines = raw.split("\n");
        int n = Math.min(lines.length, HudRenderer.Data.SCMAX);
        java.util.ArrayList<String[]> out = new java.util.ArrayList<String[]>();
        for (int i = 0; i < lines.length && out.size() < n; i++) {
            int t = lines[i].indexOf('\t');
            if (t <= 0) continue;
            out.add(new String[]{lines[i].substring(0, t), lines[i].substring(t + 1)});
        }
        return out.toArray(new String[0][]);
    }

    static void save(SharedPreferences sp, java.util.List<String[]> e) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < e.size() && i < HudRenderer.Data.SCMAX; i++) {
            if (b.length() > 0) b.append('\n');
            b.append(e.get(i)[0]).append('\t').append(e.get(i)[1].replace('\t', ' ').replace('\n', ' '));
        }
        sp.edit().putString(KEY, b.toString()).apply();
    }

    /** short uppercase label from an app name */
    static String label(String name) {
        String l = name == null ? "APP" : name.trim().toUpperCase(java.util.Locale.ROOT);
        return l.length() > 12 ? l.substring(0, 12) : l;
    }
}
