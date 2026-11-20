package com.tokyonight.hud.live;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Satellite look angles and visible-pass prediction.
 * TEME -> ECEF by GMST rotation (polar motion ignored, < 20 m); WGS-84 observer at sea level.
 * Visible = satellite sunlit (cylindrical shadow) AND sun below -6 deg at the observer AND elevation >= 10 deg.
 */
public final class Sat {
    static final double D2R = Math.PI / 180, R2D = 180 / Math.PI, RE = 6378.137;
    public static final double MIN_EL = 10, SUN_DARK = -6;
    static final long STEP = 30000L;

    public static final class Pass {
        public String label;
        public int count = 1;                     // satellites in a train
        public long rise, set, visStart, visEnd, peak;
        public double maxEl, azRise, azSet, azVisStart, azVisEnd;
        public float[] tAz, tEl; public boolean[] tVis;   // track at 10 s
        public long t0;
    }

    /** {el, az, rangeKm, sunlit 0/1} at ms */
    public static boolean look(Sgp4 s, long ms, double lat, double lon, double[] out) {
        double jd = Astro.jd(ms);
        double[] r = new double[3];
        if (!s.posJd(jd, r)) return false;
        double g = Astro.lst(jd, 0) * D2R;
        topo(r, g, lat, lon, out);
        double[] sn = Astro.sun(jd);
        out[3] = sunlit(r, sn[0], sn[1]) ? 1 : 0;
        return true;
    }

    static void topo(double[] r, double g, double lat, double lon, double[] out) {
        double cg = Math.cos(g), sg = Math.sin(g);
        double x = cg * r[0] + sg * r[1], y = -sg * r[0] + cg * r[1], z = r[2];
        double f = 1 / 298.257223563, e2 = f * (2 - f), p = lat * D2R, l = lon * D2R;
        double sp = Math.sin(p), cp = Math.cos(p), sl = Math.sin(l), cl = Math.cos(l);
        double N = RE / Math.sqrt(1 - e2 * sp * sp);
        double ox = N * cp * cl, oy = N * cp * sl, oz = N * (1 - e2) * sp;
        double rx = x - ox, ry = y - oy, rz = z - oz;
        double S = sp * cl * rx + sp * sl * ry - cp * rz, E = -sl * rx + cl * ry, Z = cp * cl * rx + cp * sl * ry + sp * rz;
        double rho = Math.sqrt(rx * rx + ry * ry + rz * rz);
        out[0] = Math.asin(Z / rho) * R2D;
        out[1] = Astro.norm(Math.atan2(E, -S) * R2D);
        out[2] = rho;
    }

    static boolean sunlit(double[] r, double raDeg, double decDeg) {
        double ra = raDeg * D2R, de = decDeg * D2R;
        double sx = Math.cos(de) * Math.cos(ra), sy = Math.cos(de) * Math.sin(ra), sz = Math.sin(de);
        double proj = r[0] * sx + r[1] * sy + r[2] * sz;
        if (proj > 0) return true;
        double rr = r[0] * r[0] + r[1] * r[1] + r[2] * r[2];
        return Math.sqrt(Math.max(0, rr - proj * proj)) > RE;
    }

    /** shared time grid: GMST, sun direction and sun altitude at each step (computed once for all satellites) */
    public static final class Grid {
        final long t0; final int n; final double lat, lon;
        final double[] g, sra, sdec, salt;
        public Grid(long t0, long spanMs, double lat, double lon) {
            this.t0 = t0; this.lat = lat; this.lon = lon;
            n = (int) (spanMs / STEP) + 1;
            g = new double[n]; sra = new double[n]; sdec = new double[n]; salt = new double[n];
            for (int i = 0; i < n; i++) {
                double jd = Astro.jd(t0 + i * STEP);
                double[] sn = Astro.sun(jd);
                g[i] = Astro.lst(jd, 0) * D2R; sra[i] = sn[0]; sdec[i] = sn[1];
                salt[i] = Astro.altAz(sn[0], sn[1], Astro.lst(jd, lon), lat)[0];
            }
        }
    }

    /** next pass with a visible segment reaching MIN_EL, or null */
    public static Pass nextVisible(Sgp4 s, Grid G) {
        double[] r = new double[3], o = new double[4];
        double prevEl = -90; int start = -1;
        for (int i = 0; i < G.n; i++) {
            double jd = Astro.jd(G.t0 + i * STEP);
            double el = -90;
            if (s.posJd(jd, r)) { topo(r, G.g[i], G.lat, G.lon, o); el = o[0]; }
            if (el >= 0 && prevEl < 0) start = i;
            if (start >= 0 && (el < 0 || i == G.n - 1)) {
                Pass p = evaluate(s, G, Math.max(0, start - 1), i);
                if (p != null) return p;
                start = -1;
            }
            prevEl = el;
        }
        return null;
    }

    /** refine one above-horizon interval at 10 s; null if no visible segment reaches MIN_EL */
    static Pass evaluate(Sgp4 s, Grid G, int i0, int i1) {
        long a = G.t0 + i0 * STEP, b = G.t0 + i1 * STEP;
        int n = (int) ((b - a) / 10000L) + 1;
        float[] az = new float[n], el = new float[n]; boolean[] vis = new boolean[n];
        double[] r = new double[3], o = new double[4];
        double best = -90, bestVis = -90; int ib = -1, ivs = -1, ive = -1, ir = -1, is = -1;
        for (int k = 0; k < n; k++) {
            long t = a + k * 10000L;
            double jd = Astro.jd(t);
            if (!s.posJd(jd, r)) return null;
            double g = Astro.lst(jd, 0) * D2R;
            topo(r, g, G.lat, G.lon, o);
            az[k] = (float) o[1]; el[k] = (float) o[0];
            int gi = (int) Math.min(G.n - 1, Math.max(0, Math.round((t - G.t0) / (double) STEP)));
            boolean lit = sunlit(r, G.sra[gi], G.sdec[gi]);
            vis[k] = o[0] > 0 && lit && G.salt[gi] < SUN_DARK;
            if (o[0] >= 0) { if (ir < 0) ir = k; is = k; }
            if (o[0] > best) { best = o[0]; ib = k; }
            if (vis[k]) { if (ivs < 0) ivs = k; ive = k; if (o[0] > bestVis) bestVis = o[0]; }
        }
        if (ivs < 0 || bestVis < MIN_EL || ir < 0) return null;
        Pass p = new Pass();
        p.t0 = a; p.tAz = az; p.tEl = el; p.tVis = vis;
        p.rise = a + ir * 10000L; p.set = a + is * 10000L; p.peak = a + ib * 10000L; p.maxEl = bestVis;
        p.azRise = az[ir]; p.azSet = az[is];
        p.visStart = a + ivs * 10000L; p.visEnd = a + ive * 10000L; p.azVisStart = az[ivs]; p.azVisEnd = az[ive];
        return p;
    }

    public static String compass(double az) {
        String[] c = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        return c[(int) Math.round(Astro.norm(az) / 45.0) % 8];
    }

    // ------------------------------------------------------------------ TLE sets

    public static List<Sgp4> parseTle(String txt) {
        List<Sgp4> out = new ArrayList<Sgp4>();
        if (txt == null) return out;
        String[] L = txt.replace("\r", "").split("\n");
        for (int i = 0; i + 1 < L.length; i++) {
            if (L[i].startsWith("1 ") && L[i + 1].startsWith("2 ")) {
                String nm = i > 0 && !L[i - 1].startsWith("1 ") && !L[i - 1].startsWith("2 ") ? L[i - 1] : "";
                Sgp4 s = Sgp4.parse(nm, L[i], L[i + 1]);
                if (s != null) out.add(s);
                i++;
            }
        }
        return out;
    }

    /**
     * Starlink launches whose satellites are still bunched (spread < 25 deg along the sky from the
     * centroid, mean altitude < 450 km): the "train" phase. Returns one representative per launch.
     */
    public static List<Sgp4> trains(List<Sgp4> all, long now, int[] counts) {
        Map<String, List<Sgp4>> by = new HashMap<String, List<Sgp4>>();
        for (Sgp4 s : all) {
            if (!s.name.toUpperCase().contains("STARLINK") || s.intl.length() < 5) continue;
            String k = s.intl.substring(0, 5);
            List<Sgp4> l = by.get(k);
            if (l == null) { l = new ArrayList<Sgp4>(); by.put(k, l); }
            l.add(s);
        }
        List<Sgp4> out = new ArrayList<Sgp4>();
        double jd = Astro.jd(now);
        int ci = 0;
        for (List<Sgp4> l : by.values()) {
            if (l.size() < 8) continue;
            double[] c = new double[3], r = new double[3];
            List<double[]> P = new ArrayList<double[]>();
            List<Sgp4> okS = new ArrayList<Sgp4>();
            double alt = 0;
            for (Sgp4 s : l) {
                if (!s.posJd(jd, r)) continue;
                okS.add(s);
                double m = Math.sqrt(r[0] * r[0] + r[1] * r[1] + r[2] * r[2]);
                alt += m - RE;
                double[] u = {r[0] / m, r[1] / m, r[2] / m};
                P.add(u); c[0] += u[0]; c[1] += u[1]; c[2] += u[2];
            }
            if (P.size() < 8) continue;
            alt /= P.size();
            double cm = Math.sqrt(c[0] * c[0] + c[1] * c[1] + c[2] * c[2]);
            double spread = 0; int bi = 0; double bd = 9;
            for (int i = 0; i < P.size(); i++) {
                double[] u = P.get(i);
                double ang = Math.acos(Math.max(-1, Math.min(1, (u[0] * c[0] + u[1] * c[1] + u[2] * c[2]) / cm))) * R2D;
                spread = Math.max(spread, ang);
                if (ang < bd) { bd = ang; bi = i; }
            }
            if (spread < 25 && alt < 450) {
                out.add(okS.get(bi));
                if (counts != null && ci < counts.length) counts[ci++] = P.size();
            }
        }
        return out;
    }
}
