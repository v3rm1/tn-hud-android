package com.tokyonight.hud.live;

/**
 * Low-precision ephemeris, pure Java (no Android deps).
 * Sun: Astronomical Almanac low-precision formula (~0.01 deg).
 * Moon: AA low-precision series incl. horizontal parallax (~0.3 deg), topocentric altitude.
 * Planets: JPL Keplerian elements, valid 1800-2050 (~arcminutes for inner, <1 deg outer).
 * Angles in degrees, azimuth from north through east.
 */
public final class Astro {
    public static final int SUN = -2, MOON = -1, MERCURY = 0, VENUS = 1, MARS = 2, JUPITER = 3, SATURN = 4;
    public static final String[] PNAME = {"MERCURY", "VENUS", "MARS", "JUPITER", "SATURN"};
    static final double D2R = Math.PI / 180, R2D = 180 / Math.PI;

    // a, e, I, L, long.perihelion, long.node  (J2000) and rates per Julian century
    static final double[][] EL = {
        {0.38709927, 0.20563593, 7.00497902, 252.25032350, 77.45779628, 48.33076593},
        {0.72333566, 0.00677672, 3.39467605, 181.97909950, 131.60246718, 76.67984255},
        {1.52371034, 0.09339410, 1.84969142, -4.55343205, -23.94362959, 49.55953891},
        {5.20288700, 0.04838624, 1.30439695, 34.39644051, 14.72847983, 100.47390909},
        {9.53667594, 0.05386179, 2.48599187, 49.95424423, 92.59887831, 113.66242448}};
    static final double[][] RT = {
        {0.00000037, 0.00001906, -0.00594749, 149472.67411175, 0.16047689, -0.12534081},
        {0.00000390, -0.00004107, -0.00078890, 58517.81538729, 0.00268329, -0.27769418},
        {0.00001847, 0.00007882, -0.00813131, 19140.30268499, 0.44441088, -0.29257343},
        {-0.00011607, -0.00013253, -0.00183714, 3034.74612775, 0.21252668, 0.20469106},
        {-0.00125060, -0.00050991, 0.00193609, 1222.49362201, -0.41897216, -0.28867794}};
    static final double[] EM = {1.00000261, 0.01671123, -0.00001531, 100.46457166, 102.93768193, 0.0};
    static final double[] EMR = {0.00000562, -0.00004392, -0.01294668, 35999.37244981, 0.32327364, 0.0};

    public static double norm(double a) { a %= 360; return a < 0 ? a + 360 : a; }
    public static double jd(long ms) { return ms / 86400000.0 + 2440587.5; }
    static double obl(double jd) { return 23.439291 - 0.0130042 * (jd - 2451545.0) / 36525.0; }

    /** {ra, dec, eclipticLon} */
    public static double[] sun(double jd) {
        double d = jd - 2451545.0;
        double g = norm(357.529 + 0.98560028 * d) * D2R;
        double q = norm(280.459 + 0.98564736 * d);
        double L = norm(q + 1.915 * Math.sin(g) + 0.020 * Math.sin(2 * g));
        double[] eq = eclToEq(L, 0, jd);
        return new double[]{eq[0], eq[1], L};
    }

    /** {ra, dec, eclipticLon, eclipticLat, horizontalParallax} (geocentric) */
    public static double[] moon(double jd) {
        double T = (jd - 2451545.0) / 36525.0;
        double l = 218.32 + 481267.881 * T
                + 6.29 * sd(135.0 + 477198.87 * T) - 1.27 * sd(259.3 - 413335.36 * T)
                + 0.66 * sd(235.7 + 890534.22 * T) + 0.21 * sd(269.9 + 954397.74 * T)
                - 0.19 * sd(357.5 + 35999.05 * T) - 0.11 * sd(186.5 + 966404.03 * T);
        double b = 5.13 * sd(93.3 + 483202.02 * T) + 0.28 * sd(228.2 + 960400.89 * T)
                - 0.28 * sd(318.3 + 6003.15 * T) - 0.17 * sd(217.6 - 407332.21 * T);
        double hp = 0.9508 + 0.0518 * cd(135.0 + 477198.87 * T) + 0.0095 * cd(259.3 - 413335.36 * T)
                + 0.0078 * cd(235.7 + 890534.22 * T) + 0.0028 * cd(269.9 + 954397.74 * T);
        l = norm(l);
        double[] eq = eclToEq(l, b, jd);
        return new double[]{eq[0], eq[1], l, b, hp};
    }

    /** {ra, dec, distanceAU} geocentric */
    public static double[] planet(int p, double jd) {
        double T = (jd - 2451545.0) / 36525.0;
        double[] P = helio(EL[p], RT[p], T), E = helio(EM, EMR, T);
        double x = P[0] - E[0], y = P[1] - E[1], z = P[2] - E[2];
        double r = Math.sqrt(x * x + y * y + z * z);
        double lon = Math.atan2(y, x) * R2D + 1.3969713 * T;      // J2000 ecliptic -> of date (general precession)
        double lat = Math.asin(z / r) * R2D;
        double[] eq = eclToEq(norm(lon), lat, jd);
        return new double[]{eq[0], eq[1], r};
    }

    static double[] helio(double[] el, double[] rt, double T) {
        double a = el[0] + rt[0] * T, e = el[1] + rt[1] * T, I = (el[2] + rt[2] * T) * D2R;
        double L = el[3] + rt[3] * T, wb = el[4] + rt[4] * T, Om = el[5] + rt[5] * T;
        double M = norm(L - wb) * D2R, w = (wb - Om) * D2R, O = Om * D2R;
        double E = M;
        for (int i = 0; i < 8; i++) E -= (E - e * Math.sin(E) - M) / (1 - e * Math.cos(E));
        double xp = a * (Math.cos(E) - e), yp = a * Math.sqrt(1 - e * e) * Math.sin(E);
        double cw = Math.cos(w), sw = Math.sin(w), cO = Math.cos(O), sO = Math.sin(O), cI = Math.cos(I), sI = Math.sin(I);
        return new double[]{
            (cw * cO - sw * sO * cI) * xp + (-sw * cO - cw * sO * cI) * yp,
            (cw * sO + sw * cO * cI) * xp + (-sw * sO + cw * cO * cI) * yp,
            (sw * sI) * xp + (cw * sI) * yp};
    }

    static double[] eclToEq(double lonDeg, double latDeg, double jd) {
        double l = lonDeg * D2R, b = latDeg * D2R, e = obl(jd) * D2R;
        double x = Math.cos(b) * Math.cos(l), y = Math.cos(b) * Math.sin(l), z = Math.sin(b);
        double ye = y * Math.cos(e) - z * Math.sin(e), ze = y * Math.sin(e) + z * Math.cos(e);
        return new double[]{norm(Math.atan2(ye, x) * R2D), Math.asin(ze) * R2D};
    }

    public static double lst(double jd, double lon) { return norm(280.46061837 + 360.98564736629 * (jd - 2451545.0) + lon); }

    /** {alt, az} */
    public static double[] altAz(double ra, double dec, double lst, double lat) {
        double H = (lst - ra) * D2R, d = dec * D2R, f = lat * D2R;
        double alt = Math.asin(Math.sin(f) * Math.sin(d) + Math.cos(f) * Math.cos(d) * Math.cos(H));
        double az = Math.atan2(-Math.cos(d) * Math.sin(H), Math.sin(d) * Math.cos(f) - Math.cos(d) * Math.cos(H) * Math.sin(f));
        return new double[]{alt * R2D, norm(az * R2D)};
    }

    /** {alt, az} of a body; moon altitude is topocentric */
    public static double[] pos(int body, long ms, double lat, double lon) {
        double j = jd(ms), ls = lst(j, lon);
        if (body == SUN) { double[] s = sun(j); return altAz(s[0], s[1], ls, lat); }
        if (body == MOON) {
            double[] m = moon(j); double[] h = altAz(m[0], m[1], ls, lat);
            h[0] -= m[4] * Math.cos(h[0] * D2R);
            return h;
        }
        double[] p = planet(body, j); return altAz(p[0], p[1], ls, lat);
    }

    /** {illuminatedFraction 0..1, waxing 1/0, elongationFromSun 0..360 (moonLon - sunLon)} */
    public static double[] moonPhase(long ms) {
        double j = jd(ms); double[] s = sun(j), m = moon(j);
        double D = norm(m[2] - s[2]);
        double cpsi = Math.cos(m[3] * D2R) * Math.cos(D * D2R);
        return new double[]{(1 - cpsi) / 2, D < 180 ? 1 : 0, D};
    }

    /** first time in [t0,t1] the body crosses altitude h going up (rising) or down; 0 if none */
    public static long crossing(int body, long t0, long t1, double lat, double lon, double h, boolean rising, long step) {
        double prev = pos(body, t0, lat, lon)[0] - h;
        for (long t = t0 + step; t <= t1; t += step) {
            double cur = pos(body, t, lat, lon)[0] - h;
            if (rising ? (prev < 0 && cur >= 0) : (prev >= 0 && cur < 0)) {
                long a = t - step, b = t; double fa = prev, fb = cur;
                for (int i = 0; i < 4; i++) {                      // secant refinement
                    long m = a + (long) ((b - a) * (fa / (fa - fb)));
                    if (m <= a || m >= b) break;
                    double fm = pos(body, m, lat, lon)[0] - h;
                    if ((fm < 0) == (fa < 0)) { a = m; fa = fm; } else { b = m; fb = fm; }
                    if (b - a < 2000) break;
                }
                return a + (long) ((b - a) * (fa / (fa - fb)));
            }
            prev = cur;
        }
        return 0;
    }

    /** {timeMs, altitudeDeg} of the sun's culmination in [t0, t1] */
    public static double[] culmination(long t0, long t1, double lat, double lon) {
        long best = t0; double ba = -99;
        for (long t = t0; t <= t1; t += 600000L) { double a = pos(SUN, t, lat, lon)[0]; if (a > ba) { ba = a; best = t; } }
        for (long t = best - 600000L; t <= best + 600000L; t += 15000L) { double a = pos(SUN, t, lat, lon)[0]; if (a > ba) { ba = a; best = t; } }
        return new double[]{best, ba};
    }


    // ------------------------------------------------------------------ v20: lock-screen astronomy

    public static final double AU_KM = 149597870.7, C_KMS = 299792.458;

    /** geocentric lunar distance in km from horizontal parallax */
    public static double moonDistKm(long ms) { return 6378.14 / Math.sin(moon(jd(ms))[4] * D2R); }

    /** equation of time in minutes (apparent minus mean solar time) */
    public static double eotMin(long ms) {
        double j = jd(ms), T = (j - 2451545.0) / 36525.0;
        double L0 = norm(280.46646 + 36000.76983 * T + 0.0003032 * T * T);
        double d = L0 - 0.0057183 - sun(j)[0];
        d = ((d + 180) % 360 + 360) % 360 - 180;
        return 4 * d;
    }

    /** first time >= t0 the Moon-Sun elongation reaches target deg (0 new, 90 FQ, 180 full, 270 LQ); 0 if none in 40 d */
    public static long phaseEvent(long t0, double target) {
        long step = 6 * 3600000L;
        double prev = elong(t0, target);
        for (long t = t0 + step; t < t0 + 40L * 86400000L; t += step) {
            double cur = elong(t, target);
            if (prev < 0 && cur >= 0 && cur - prev < 90) {          // wrapped difference crosses zero going up
                long a = t - step, b = t;
                for (int i = 0; i < 30 && b - a > 20000; i++) {
                    long m = (a + b) / 2;
                    if (elong(m, target) < 0) a = m; else b = m;
                }
                return (a + b) / 2;
            }
            prev = cur;
        }
        return 0;
    }

    static double elong(long ms, double target) {
        double j = jd(ms);
        double d = norm(moon(j)[2] - sun(j)[2]) - target;
        return ((d + 180) % 360 + 360) % 360 - 180;
    }

    /** {timeMs, distKm} of the next lunar distance extremum: perigee if min, else apogee */
    public static double[] apsis(long t0, boolean min) {
        long step = 3600000L;
        double a = moonDistKm(t0), b = moonDistKm(t0 + step);
        for (long t = t0 + 2 * step; t < t0 + 32L * 86400000L; t += step) {
            double c = moonDistKm(t);
            boolean ext = min ? (b < a && b <= c) : (b > a && b >= c);
            if (ext) {
                double den = a - 2 * b + c;                                   // parabolic vertex
                double off = den == 0 ? 0 : 0.5 * (a - c) / den;
                long tm = t - step + (long) (off * step);
                return new double[]{tm, b - 0.25 * (a - c) * off};
            }
            a = b; b = c;
        }
        return new double[]{0, Double.NaN};
    }

    /**
     * sun altitude crossings for the local night that follows (or contains) ms:
     * {set-0.833, set-6, set-12, set-18, rise-18, rise-12, rise-6, rise-0.833}; 0 where the sun never gets there
     */
    public static long[] twilight(long dayStartMs, double lat, double lon) {
        double[] h = {-0.833, -6, -12, -18};
        long[] r = new long[8];
        long noon = (long) culmination(dayStartMs, dayStartMs + 86400000L, lat, lon)[0];
        for (int i = 0; i < 4; i++) {
            r[i] = crossing(SUN, noon, noon + 86400000L, lat, lon, h[i], false, 600000L);
            r[7 - i] = crossing(SUN, noon, noon + 86400000L, lat, lon, h[i], true, 600000L);
        }
        return r;
    }

    // meteor showers (IMO working list, approximate): code, name, start MMDD, peak MMDD, end MMDD, RA, Dec, ZHR, width days
    static final Object[][] SHOWERS = {
        {"QUA", "QUADRANTIDS", 1228, 103, 112, 230.0, 49.0, 80, 0.6},
        {"LYR", "LYRIDS", 414, 422, 430, 271.0, 34.0, 18, 1.0},
        {"ETA", "ETA AQUARIIDS", 419, 506, 528, 338.0, -1.0, 50, 5.0},
        {"CAP", "ALPHA CAPRICORNIDS", 703, 730, 815, 307.0, -10.0, 5, 6.0},
        {"SDA", "S DELTA AQUARIIDS", 712, 731, 823, 340.0, -16.0, 25, 5.0},
        {"PER", "PERSEIDS", 717, 812, 824, 48.0, 58.0, 100, 2.5},
        {"DRA", "DRACONIDS", 1006, 1008, 1010, 262.0, 54.0, 10, 0.5},
        {"STA", "S TAURIDS", 910, 1010, 1120, 32.0, 9.0, 5, 15.0},
        {"ORI", "ORIONIDS", 1002, 1021, 1107, 95.0, 16.0, 20, 4.0},
        {"NTA", "N TAURIDS", 1020, 1112, 1210, 58.0, 22.0, 5, 12.0},
        {"LEO", "LEONIDS", 1106, 1117, 1130, 152.0, 22.0, 15, 1.5},
        {"GEM", "GEMINIDS", 1204, 1214, 1220, 112.0, 33.0, 150, 1.5},
        {"URS", "URSIDS", 1217, 1222, 1226, 217.0, 76.0, 10, 0.8}};

    /**
     * most productive active shower: {index, effZHR, radiantAlt, daysToPeak (neg = past), activeCount}; null if none.
     * effZHR = ZHR * exp(-|dPeak| / width); hourly rate seen ~ effZHR * sin(alt) for a dark sky.
     */
    public static double[] meteors(long ms, double lat, double lon) {
        java.util.Calendar c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        c.setTimeInMillis(ms);
        int y = c.get(java.util.Calendar.YEAR);
        double best = -1; double[] out = null; int act = 0;
        for (int i = 0; i < SHOWERS.length; i++) {
            Object[] S = SHOWERS[i];
            for (int dy = -1; dy <= 1; dy++) {
                long st = md(y + dy - ((Integer) S[2] > (Integer) S[3] ? 1 : 0), (Integer) S[2]);
                long pk = md(y + dy, (Integer) S[3]);
                long en = md(y + dy + ((Integer) S[4] < (Integer) S[3] ? 1 : 0), (Integer) S[4]) + 86400000L;
                if (ms < st || ms >= en) continue;
                act++;
                double dp = (pk + 43200000L - ms) / 86400000.0;
                double eff = ((Integer) S[7]) * Math.exp(-Math.abs(dp) / (Double) S[8]);
                if (eff > best) {
                    best = eff;
                    double j = jd(ms);
                    double alt = Double.isNaN(lat) ? Double.NaN : altAz((Double) S[5], (Double) S[6], lst(j, lon), lat)[0];
                    out = new double[]{i, eff, alt, dp, 0};
                }
            }
        }
        if (out != null) out[4] = act;
        return out;
    }

    public static String SHOWER_CODE(int i) { return (String) SHOWERS[i][0]; }
    public static String SHOWER_NAME(int i) { return (String) SHOWERS[i][1]; }
    public static int SHOWER_ZHR(int i) { return (Integer) SHOWERS[i][7]; }

    static long md(int y, int mmdd) {
        java.util.Calendar c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        c.clear(); c.set(y, mmdd / 100 - 1, mmdd % 100);
        return c.getTimeInMillis();
    }

    /**
     * Voyager distances from Earth (km): linear heliocentric radial model, direction fixed (J2000 RA/Dec),
     * Earth from the low-precision solar position. idx 0 = Voyager 1, 1 = Voyager 2.
     * Calibrated: V1 = 1 light-day (25 902 068 371 km) at 2026-11-18 06:16:07 UTC (NASA/JPL);
     * V2 = 143.09 AU at 2026-02-09 (JPL via EarthSky).
     */
    public static double voyagerKm(int idx, long ms) {
        double ra = idx == 0 ? 258.95 : 303.7, dec = idx == 0 ? 12.08 : -59.47;     // deg
        double vk = idx == 0 ? VOY_V[0] : VOY_V[1];
        double ux = cd(dec) * cd(ra), uy = cd(dec) * sd(ra), uz = sd(dec);
        double r = VOY_R0[idx] + vk * (ms - VOY_T0[idx]) / 1000.0;
        double[] e = earthHelioKm(ms);
        double dx = r * ux - e[0], dy = r * uy - e[1], dz = r * uz - e[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
    static final double[] VOY_V = {16.99, 15.30};
    static final double[] VOY_R0 = {0, 0};
    static final long[] VOY_T0 = {1794982567000L, 1770595200000L};
    static final double[] VOY_D0 = {25902068371.0, 143.09 * AU_KM};
    static {                                                       // solve r0 so the model hits the calibration distance
        for (int k = 0; k < 2; k++) {
            double r = VOY_D0[k];
            for (int i = 0; i < 20; i++) { VOY_R0[k] = r; r += VOY_D0[k] - voyagerKm(k, VOY_T0[k]); }
            VOY_R0[k] = r;
        }
    }

    /** Earth heliocentric equatorial position, km */
    static double[] earthHelioKm(long ms) {
        double j = jd(ms), d = j - 2451545.0;
        double g = norm(357.529 + 0.98560028 * d) * D2R;
        double R = (1.00014 - 0.01671 * Math.cos(g) - 0.00014 * Math.cos(2 * g)) * AU_KM;
        double[] s = sun(j);
        return new double[]{-R * cd(s[1]) * cd(s[0]), -R * cd(s[1]) * sd(s[0]), -R * sd(s[1])};
    }

    static double sd(double deg) { return Math.sin(deg * D2R); }
    static double cd(double deg) { return Math.cos(deg * D2R); }
}
