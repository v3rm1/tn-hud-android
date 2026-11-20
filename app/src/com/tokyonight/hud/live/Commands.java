package com.tokyonight.hud.live;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Drawer command line: a query starting with '>' lists these instead of apps (in the spirit of T-UI).
 * "> torch" toggles the torch, "> timer 5" sets a five-minute timer, "> web tokyo night" searches, and so on.
 * Names match by prefix, so "> to" is enough for torch; Enter runs the top match with the rest of the line as its argument.
 * Rofi is the same command line as a floating pop-up (glyphs '>' and C).
 * HudLauncher.runCommand does the work; this class only holds the table and the parsing.
 */
final class Commands {
    static final class Cmd {
        final String name, args, help;
        Cmd(String name, String args, String help) { this.name = name; this.args = args; this.help = help; }
        boolean needsArgs() { return args != null && !args.startsWith("["); }
    }

    static final Cmd[] ALL = {
            new Cmd("torch", null, "toggle the torch"),
            new Cmd("wifi", null, "Wi-Fi panel"),
            new Cmd("net", null, "internet panel"),
            new Cmd("bt", null, "Bluetooth settings"),
            new Cmd("vol", null, "volume panel"),
            new Cmd("notif", null, "HUD notifications"),
            new Cmd("qs", null, "quick settings"),
            new Cmd("camera", null, "open the camera"),
            new Cmd("tab", null, "next HUD tab"),
            new Cmd("call", "<name|number>", "dial a contact or number"),
            new Cmd("sms", "<name|number>", "text a contact or number"),
            new Cmd("wa", "<name>", "WhatsApp voice call"),
            new Cmd("wav", "<name>", "WhatsApp video call"),
            new Cmd("tg", "<name>", "Telegram call"),
            new Cmd("web", "<query>", "search the web"),
            new Cmd("maps", "<place>", "find a place"),
            new Cmd("timer", "<minutes>", "start a timer"),
            new Cmd("alarm", "<hh:mm>", "set an alarm"),
            new Cmd("info", "<app>", "app info"),
            new Cmd("hide", "<app>", "hide an app from the list"),
            new Cmd("unhide", "<app>", "show a hidden app again"),
            new Cmd("settings", null, "HUD settings"),
            new Cmd("android", null, "Android settings"),
            new Cmd("help", null, "list every command"),
    };

    /** commands whose argument is a contact: call / sms use phone numbers, the rest a messenger's contact entry */
    static boolean contactCmd(String n) { return n.equals("call") || n.equals("sms") || mime(n) != null; }

    /**
     * Messenger call entries that WhatsApp and Telegram add to the system contacts (when their contact sync is on).
     * Opening such a Data row with ACTION_VIEW starts the call in that app. Unofficial: these mimetypes are what the
     * apps write today, not a published API, so they may change.
     */
    static String mime(String n) {
        if (n.equals("wa")) return "vnd.android.cursor.item/vnd.com.whatsapp.voip.call";
        if (n.equals("wav")) return "vnd.android.cursor.item/vnd.com.whatsapp.video.call";
        if (n.equals("tg")) return "vnd.android.cursor.item/vnd.org.telegram.messenger.android.call";
        return null;
    }

    static String pkg(String n) { return n.startsWith("wa") ? "com.whatsapp" : n.equals("tg") ? "org.telegram.messenger" : null; }

    static boolean isCommandLine(String raw) { return raw.startsWith(">"); }

    /** the text after '>' split into the command word (lower case) and the rest */
    static String[] split(String raw) {
        String s = raw.substring(1).trim();
        int sp = s.indexOf(' ');
        String w = (sp < 0 ? s : s.substring(0, sp)).toLowerCase(Locale.ROOT);
        String rest = sp < 0 ? "" : s.substring(sp + 1).trim();
        return new String[]{w, rest};
    }

    /** exact name first, then prefix matches in table order; an empty word lists everything */
    static ArrayList<Cmd> match(String word) {
        ArrayList<Cmd> out = new ArrayList<Cmd>();
        for (Cmd c : ALL) if (c.name.equals(word)) out.add(c);
        for (Cmd c : ALL) if (!c.name.equals(word) && c.name.startsWith(word)) out.add(c);
        return out;
    }

    /** "7:30", "07:30", "730" or "7" (hours) to {hour, minute}, or null */
    static int[] parseTime(String s) {
        s = s.trim().replace('.', ':');
        try {
            int h, m;
            if (s.contains(":")) { String[] p = s.split(":"); h = Integer.parseInt(p[0].trim()); m = Integer.parseInt(p[1].trim()); }
            else if (s.length() >= 3) { int v = Integer.parseInt(s); h = v / 100; m = v % 100; }
            else { h = Integer.parseInt(s); m = 0; }
            return h >= 0 && h < 24 && m >= 0 && m < 60 ? new int[]{h, m} : null;
        } catch (Exception e) { return null; }
    }
}
