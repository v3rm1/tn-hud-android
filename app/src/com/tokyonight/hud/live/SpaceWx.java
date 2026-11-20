package com.tokyonight.hud.live;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NOAA SWPC feeds -> numbers. Tolerant of the 2026 format changes (SCN 26-21): array-of-arrays vs array-of-objects,
 * quoted vs bare numbers, optional outer brackets, bare NaN tokens. Pure Java, no Android deps (unit-testable).
 */
public final class SpaceWx {
    public static final String BASE = "https://services.swpc.noaa.gov/";
    public static final String U_KP = BASE + "json/planetary_k_index_1m.json";
    public static final String U_SCALES = BASE + "products/noaa-scales.json";
    public static final String U_MAG = BASE + "products/summary/solar-wind-mag-field.json";
    public static final String U_SPEED = BASE + "products/summary/solar-wind-speed.json";
    public static final String U_XRAY = BASE + "json/goes/primary/xrays-6-hour.json";
    public static final String U_KPFC = BASE + "products/noaa-planetary-k-index-forecast.json";
    public static final String U_TLE_STATIONS = "https://celestrak.org/NORAD/elements/gp.php?GROUP=stations&FORMAT=tle";
    public static final String U_TLE_RECENT = "https://celestrak.org/NORAD/elements/gp.php?GROUP=last-30-days&FORMAT=tle";

    // ------------------------------------------------------------------ minimal JSON
    static final class P {
        final String s; int i;
        P(String s) { this.s = s; }
        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        Object val() {
            ws();
            if (i >= s.length()) throw new IllegalArgumentException("eof");
            char c = s.charAt(i);
            if (c == '{') {
                i++; Map<String, Object> m = new LinkedHashMap<String, Object>();
                ws(); if (s.charAt(i) == '}') { i++; return m; }
                while (true) {
                    ws(); String k = (String) val(); ws();
                    if (s.charAt(i++) != ':') throw new IllegalArgumentException(":");
                    m.put(k, val()); ws();
                    char d = s.charAt(i++);
                    if (d == '}') return m;
                    if (d != ',') throw new IllegalArgumentException(",");
                }
            }
            if (c == '[') {
                i++; List<Object> l = new ArrayList<Object>();
                ws(); if (s.charAt(i) == ']') { i++; return l; }
                while (true) {
                    l.add(val()); ws();
                    char d = s.charAt(i++);
                    if (d == ']') return l;
                    if (d != ',') throw new IllegalArgumentException(",");
                }
            }
            if (c == '"') {
                StringBuilder b = new StringBuilder(); i++;
                while (true) {
                    char d = s.charAt(i++);
                    if (d == '"') return b.toString();
                    if (d == '\\') {
                        char e = s.charAt(i++);
                        if (e == 'u') { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                        else b.append(e == 'n' ? '\n' : e == 't' ? '\t' : e == 'r' ? '\r' : e == 'b' ? '\b' : e == 'f' ? '\f' : e);
                    } else b.append(d);
                }
            }
            int j = i;
            while (i < s.length() && ",]}".indexOf(s.charAt(i)) < 0 && !Character.isWhitespace(s.charAt(i))) i++;
            String t = s.substring(j, i);
            if (t.equals("true")) return Boolean.TRUE;
            if (t.equals("false")) return Boolean.FALSE;
            if (t.equals("null")) return null;
            try { return Double.parseDouble(t); } catch (NumberFormatException e) { return Double.NaN; }   // NaN, Infinity
        }
    }

    public static Object parse(String s) { return s == null ? null : new P(s.trim()).val(); }

    /** number from Double or numeric String; NaN otherwise */
    public static double num(Object o) {
        if (o instanceof Double) return (Double) o;
        if (o instanceof String) { try { return Double.parseDouble(((String) o).trim()); } catch (Exception e) { return Double.NaN; } }
        return Double.NaN;
    }

    /** case-insensitive key lookup over several aliases */
    static Object get(Map<?, ?> m, String... keys) {
        for (String k : keys) for (Map.Entry<?, ?> e : m.entrySet()) if (k.equalsIgnoreCase(String.valueOf(e.getKey()))) return e.getValue();
        return null;
    }

    /** rows as maps, whether the feed is array-of-objects or array-of-arrays with a header row */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> rows(Object root) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        if (root instanceof Map) { out.add((Map<String, Object>) root); return out; }
        if (!(root instanceof List)) return out;
        List<Object> l = (List<Object>) root;
        if (!l.isEmpty() && l.get(0) instanceof List) {
            List<Object> hdr = (List<Object>) l.get(0);
            for (int r = 1; r < l.size(); r++) {
                List<Object> row = (List<Object>) l.get(r);
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                for (int c = 0; c < hdr.size() && c < row.size(); c++) m.put(String.valueOf(hdr.get(c)), row.get(c));
                out.add(m);
            }
        } else for (Object o : l) if (o instanceof Map) out.add((Map<String, Object>) o);
        return out;
    }

    /** "2026-11-04T20:43:00" / "2026-11-04 20:43:00.000" (UTC) -> ms; 0 on failure */
    public static long time(Object o) {
        if (!(o instanceof String)) return 0;
        String s = ((String) o).trim();
        try {
            java.util.Calendar c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
            c.clear();
            c.set(Integer.parseInt(s.substring(0, 4)), Integer.parseInt(s.substring(5, 7)) - 1, Integer.parseInt(s.substring(8, 10)),
                    Integer.parseInt(s.substring(11, 13)), Integer.parseInt(s.substring(14, 16)), s.length() >= 19 ? Integer.parseInt(s.substring(17, 19)) : 0);
            return c.getTimeInMillis();
        } catch (Exception e) { return 0; }
    }

    // ------------------------------------------------------------------ feeds

    /** Kp series from planetary_k_index_1m: {times[], kp[]} (estimated_kp preferred), oldest first */
    public static double[][] kp(String body) {
        List<Map<String, Object>> R = rows(parse(body));
        List<double[]> v = new ArrayList<double[]>();
        for (Map<String, Object> m : R) {
            long t = time(get(m, "time_tag"));
            double k = num(get(m, "estimated_kp"));
            if (Double.isNaN(k)) k = num(get(m, "kp_index", "Kp"));
            if (t > 0 && !Double.isNaN(k)) v.add(new double[]{t, k});
        }
        java.util.Collections.sort(v, new java.util.Comparator<double[]>() {
            public int compare(double[] a, double[] b) { return Double.compare(a[0], b[0]); }
        });
        double[][] o = new double[2][v.size()];
        for (int i = 0; i < v.size(); i++) { o[0][i] = v.get(i)[0]; o[1][i] = v.get(i)[1]; }
        return o;
    }

    /**
     * 3-day Kp forecast (3-hour bins): {start times[], kp[], predicted 0/1[]}, oldest first.
     * Rows carry "observed" = observed / estimated / predicted (array-of-arrays or objects).
     */
    public static double[][] kpForecast(String body) {
        List<double[]> v = new ArrayList<double[]>();
        for (Map<String, Object> m : rows(parse(body))) {
            long t = time(get(m, "time_tag"));
            double k = num(get(m, "kp", "Kp"));
            Object ob = get(m, "observed");
            if (t <= 0 || Double.isNaN(k)) continue;
            v.add(new double[]{t, k, ob != null && String.valueOf(ob).toLowerCase(java.util.Locale.ROOT).startsWith("pred") ? 1 : 0});
        }
        java.util.Collections.sort(v, new java.util.Comparator<double[]>() {
            public int compare(double[] a, double[] b) { return Double.compare(a[0], b[0]); }
        });
        double[][] o = new double[3][v.size()];
        for (int i = 0; i < v.size(); i++) { o[0][i] = v.get(i)[0]; o[1][i] = v.get(i)[1]; o[2][i] = v.get(i)[2]; }
        return o;
    }

    /** max forecast Kp over bins overlapping [t0, t1]: {kp, binStart}; NaN if none */
    public static double[] kpForecastMax(double[] t, double[] k, long t0, long t1) {
        double best = Double.NaN, at = 0;
        if (t == null) return new double[]{best, at};
        for (int i = 0; i < t.length; i++) {
            if (t[i] + 3 * 3600000L <= t0 || t[i] >= t1) continue;
            if (Double.isNaN(best) || k[i] > best) { best = k[i]; at = t[i]; }
        }
        return new double[]{best, at};
    }

    /** current NOAA scales {R, S, G} (key "0"); -1 where missing */
    @SuppressWarnings("unchecked")
    public static int[] scales(String body) {
        int[] o = {-1, -1, -1};
        Object root = parse(body);
        if (!(root instanceof Map)) return o;
        Object cur = ((Map<String, Object>) root).get("0");
        if (!(cur instanceof Map)) return o;
        String[] k = {"R", "S", "G"};
        for (int i = 0; i < 3; i++) {
            Object x = ((Map<String, Object>) cur).get(k[i]);
            if (x instanceof Map) {
                double v = num(get((Map<?, ?>) x, "Scale"));
                if (!Double.isNaN(v)) o[i] = (int) v;
            }
        }
        return o;
    }

    /** latest value of the first matching key across rows (summary feeds: one object, maybe in brackets) */
    public static double latest(String body, String... keys) {
        List<Map<String, Object>> R = rows(parse(body));
        long bt = -1; double bv = Double.NaN;
        for (Map<String, Object> m : R) {
            double v = num(get(m, keys));
            if (Double.isNaN(v)) continue;
            long t = time(get(m, "TimeStamp", "time_tag"));
            if (t >= bt) { bt = t; bv = v; }
        }
        return bv;
    }

    /** GOES long channel (0.1-0.8 nm): {latestFlux, maxFlux6h, timeOfMax} */
    public static double[] xray(String body) {
        double last = Double.NaN, mx = Double.NaN, tmx = 0; long lt = -1;
        for (Map<String, Object> m : rows(parse(body))) {
            Object en = get(m, "energy");
            if (en == null || !String.valueOf(en).startsWith("0.1-0.8")) continue;
            double f = num(get(m, "flux"));
            long t = time(get(m, "time_tag"));
            if (Double.isNaN(f) || f <= 0) continue;
            if (t >= lt) { lt = t; last = f; }
            if (Double.isNaN(mx) || f > mx) { mx = f; tmx = t; }
        }
        return new double[]{last, mx, tmx};
    }

    /** flux W/m^2 -> "M2.1" */
    public static String flareClass(double f) {
        if (Double.isNaN(f) || f <= 0) return "--";
        String L = "ABCMX";
        int e = (int) Math.floor(Math.log10(f)) + 8;      // 1e-8 -> A
        if (e < 0) e = 0;
        if (e > 4) e = 4;
        double m = f / Math.pow(10, e - 8);
        if (m >= 9.95 && e < 4) { e++; m /= 10; }
        return String.format(java.util.Locale.US, "%c%.1f", L.charAt(e), m);
    }

    // ------------------------------------------------------------------ aurora

    /** centred-dipole geomagnetic latitude; pole IGRF-14 epoch 2025 (80.8 N, 72.6 W) */
    public static double geomagLat(double lat, double lon) {
        double pl = Math.toRadians(80.8), plo = Math.toRadians(-72.6), f = Math.toRadians(lat), l = Math.toRadians(lon);
        double s = Math.sin(f) * Math.sin(pl) + Math.cos(f) * Math.cos(pl) * Math.cos(l - plo);
        return Math.toDegrees(Math.asin(s));
    }

    /**
     * Kp needed to see aurora on the poleward horizon. SWPC G-scale: G1 (Kp5) ~60 deg, G2 ~55, G3 ~50, G4 ~45, G5 ~40
     * geomagnetic latitude -> phi(Kp) = 85 - 5 Kp; horizon visibility ~ 1 Kp unit earlier than "overhead".
     */
    public static double kpNeeded(double mlat) { return Math.max(0, (85 - Math.abs(mlat)) / 5.0 - 1.0); }

    /**
     * verdict 2 = GO, 1 = MAYBE, 0 = NO; reason in r[0].
     * Kp_eff = Kp + 0.5 if Bz <= -5 nT, +1 if Bz <= -10 (sustained southward IMF couples energy into the magnetosphere).
     */
    public static int aurora(double kp, double bz, double sunAlt, double cloud, double mlat, String[] r) {
        if (Double.isNaN(kp)) { r[0] = "NO SPACE-WX DATA"; return 0; }
        double need = kpNeeded(mlat);
        double eff = kp + (!Double.isNaN(bz) && bz <= -10 ? 1 : !Double.isNaN(bz) && bz <= -5 ? 0.5 : 0);
        if (sunAlt > -12) { r[0] = sunAlt > -0.833 ? "DAYLIGHT" : "TWILIGHT"; return eff >= need ? 1 : 0; }
        if (!Double.isNaN(cloud) && cloud >= 75) { r[0] = String.format(java.util.Locale.US, "OVERCAST %.0f%%", cloud); return 0; }
        if (eff >= need) {
            r[0] = !Double.isNaN(cloud) && cloud >= 40 ? String.format(java.util.Locale.US, "CLOUD %.0f%%", cloud) : "LOOK NORTH";
            return !Double.isNaN(cloud) && cloud >= 40 ? 1 : 2;
        }
        if (eff >= need - 1) { r[0] = String.format(java.util.Locale.US, "NEED KP %.1f", need); return 1; }
        r[0] = String.format(java.util.Locale.US, "NEED KP %.1f", need);
        return 0;
    }
}
