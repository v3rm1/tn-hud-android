package com.tokyonight.hud.live;

import android.content.SharedPreferences;

/** launcher gesture -> action mapping, shared by the launcher and the settings app */
final class Gestures {
    static final int NONE = 0, DRAWER = 1, NOTIFICATIONS = 2, QUICK_SETTINGS = 3, TORCH = 4, CAMERA = 5, NEXT_TAB = 6, HUD_SETTINGS = 7, OPEN_APP = 8,
            HUD_NOTIFICATIONS = 9;
    static final String[] ACTION_NAMES = {"Nothing", "App drawer", "Notifications", "Quick settings", "Torch on / off", "Camera", "Next tab",
            "HUD settings", "Open an app…", "HUD notification panel"};

    static final String SWIPE_UP = "up", DOWN_LEFT = "downL", DOWN_RIGHT = "downR", TWO_DOWN = "two", DOUBLE_TAP = "dbl", HOME_PRESS = "home";
    static final String[] KEYS = {SWIPE_UP, DOWN_LEFT, DOWN_RIGHT, TWO_DOWN, DOUBLE_TAP, HOME_PRESS};
    static final String[] NAMES = {"Swipe up", "Swipe down, left side", "Swipe down, right side", "Two-finger swipe down", "Double tap",
            "Home button while home"};
    static final String[] HINTS = {null, null, "The right 40 % of the screen.", null,
            "Taps still reach the HUD; the second tap of a double tap doesn't.", "When the drawer is closed; with it open, home closes it."};

    static int def(SharedPreferences sp, String g) {
        int legacy = sp.getInt("lnSwipeDown", 0);            // v32 split / notifications / quick settings
        if (SWIPE_UP.equals(g)) return DRAWER;
        if (DOWN_LEFT.equals(g)) return legacy == 2 ? QUICK_SETTINGS : NOTIFICATIONS;
        if (DOWN_RIGHT.equals(g)) return legacy == 1 ? NOTIFICATIONS : QUICK_SETTINGS;
        if (TWO_DOWN.equals(g)) return QUICK_SETTINGS;
        return NONE;
    }

    static int get(SharedPreferences sp, String g) { return sp.getInt("lg_" + g, def(sp, g)); }
    static String app(SharedPreferences sp, String g) { return sp.getString("lg_" + g + "_app", null); }

    // glyphs share the action list; stored as gm_<glyph> / gm_<glyph>_app
    static int glyphAction(SharedPreferences sp, String glyph) { return sp.getInt("gm_" + glyph, NONE); }
    static String glyphApp(SharedPreferences sp, String glyph) { return sp.getString("gm_" + glyph + "_app", null); }
}
