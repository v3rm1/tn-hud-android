package com.tokyonight.hud.live;

import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.Iterator;
import java.util.Map;

/**
 * Settings export / import as typed JSON. Leaves out location (city, coordinates, live-location state),
 * cached data (space weather, cloud, forecasts), diagnostics, counters and one-off flags.
 */
final class Backup {
    static final String[] SKIP_EXACT = {"city", "cityQuery", "lat", "lon", "liveloc", "obsLat", "obsLon", "swUpd", "kp", "bz", "swV", "xr", "xrMax",
            "xrMaxAt", "scR", "scS", "scG", "kpSeries", "kpFc", "kpFcAt", "cloudT", "cloudV", "cmdTap", "vPage", "uiTab", "lastUnlock", "unlCount", "unlDay", "wgId", "wgLabel", "wgLabelPending"};
    static final String[] SKIP_PREFIX = {"dg_", "asked_", "alLast", "steps", "lc_", "lf_", "lt_"};

    static boolean skip(String k) {
        for (String s : SKIP_EXACT) if (s.equals(k)) return true;
        for (String p : SKIP_PREFIX) if (k.startsWith(p)) return true;
        return false;
    }

    static String export(SharedPreferences sp) {
        try {
            JSONObject root = new JSONObject(), p = new JSONObject();
            for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
                if (skip(e.getKey()) || e.getValue() == null) continue;
                Object v = e.getValue();
                String t = v instanceof Boolean ? "b" : v instanceof Integer ? "i" : v instanceof Long ? "l" : v instanceof Float ? "f" : v instanceof String ? "s" : null;
                if (t == null) continue;
                JSONObject o = new JSONObject();
                o.put("t", t);
                o.put("v", v instanceof Float ? (double) (Float) v : v);
                p.put(e.getKey(), o);
            }
            root.put("app", "tn-hud-live");
            root.put("format", 1);
            root.put("prefs", p);
            return root.toString(2);
        } catch (Exception e) { return "{}"; }
    }

    /** returns how many settings were applied; throws on a file that isn't a TN-HUD backup */
    static int apply(SharedPreferences sp, String json) throws Exception {
        JSONObject root = new JSONObject(json);
        if (!"tn-hud-live".equals(root.optString("app"))) throw new Exception("not a TN-HUD settings file");
        JSONObject p = root.getJSONObject("prefs");
        SharedPreferences.Editor ed = sp.edit();
        int n = 0;
        for (Iterator<String> it = p.keys(); it.hasNext(); ) {
            String k = it.next();
            if (skip(k)) continue;
            JSONObject o = p.optJSONObject(k);
            if (o == null) continue;
            String t = o.optString("t");
            if ("b".equals(t)) ed.putBoolean(k, o.getBoolean("v"));
            else if ("i".equals(t)) ed.putInt(k, o.getInt("v"));
            else if ("l".equals(t)) ed.putLong(k, o.getLong("v"));
            else if ("f".equals(t)) ed.putFloat(k, (float) o.getDouble("v"));
            else if ("s".equals(t)) ed.putString(k, o.getString("v"));
            else continue;
            n++;
        }
        ed.apply();
        return n;
    }
}
