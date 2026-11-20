package com.tokyonight.hud.live;

/**
 * SGP4 propagator, near-earth branch only (period < 225 min: ISS, CSS, Starlink), WGS-72, opsmode 'i'.
 * Port of Vallado et al. (2006) "Revisiting Spacetrack Report #3". Positions in TEME, km.
 */
public final class Sgp4 {
    static final double MU = 398600.8, RE = 6378.135, J2 = 0.001082616, J3 = -0.00000253881, J4 = -0.00000165597;
    static final double XKE = 60.0 / Math.sqrt(RE * RE * RE / MU), J3OJ2 = J3 / J2, X2O3 = 2.0 / 3.0, TWOPI = 2 * Math.PI;

    public final String name, intl;      // intl: COSPAR id, e.g. "26123A"
    public final int norad;
    public final double epochJd;
    final double ecco, inclo, nodeo, argpo, mo, bstar, no;
    boolean isimp, ok = true;
    double con41, cc1, cc4, cc5, d2, d3, d4, delmo, eta, argpdot, omgcof, sinmao, t2cof, t3cof, t4cof, t5cof,
            x1mth2, x7thm1, mdot, nodedot, xlcof, xmcof, nodecf, aycof;

    /** null for deep-space objects or malformed lines */
    public static Sgp4 parse(String name, String l1, String l2) {
        try {
            if (l1.length() < 69 || l2.length() < 69 || l1.charAt(0) != '1' || l2.charAt(0) != '2') return null;
            Sgp4 s = new Sgp4(name, l1, l2);
            if (TWOPI / s.no >= 225.0 || !s.ok) return null;
            return s;
        } catch (RuntimeException e) { return null; }
    }

    static double expField(String f) {          // " 12345-3" -> 0.12345e-3
        f = f.trim();
        if (f.isEmpty()) return 0;
        int sign = 1;
        if (f.charAt(0) == '-') { sign = -1; f = f.substring(1); } else if (f.charAt(0) == '+') f = f.substring(1);
        int k = Math.max(f.lastIndexOf('-'), f.lastIndexOf('+'));
        if (k <= 0) return sign * Double.parseDouble("0." + f);
        return sign * Double.parseDouble("0." + f.substring(0, k)) * Math.pow(10, Integer.parseInt(f.substring(k).replace("+", "")));
    }

    private Sgp4(String nm, String l1, String l2) {
        name = nm == null ? "" : nm.trim();
        norad = Integer.parseInt(l1.substring(2, 7).trim());
        intl = l1.substring(9, 17).trim();
        int yy = Integer.parseInt(l1.substring(18, 20).trim());
        double doy = Double.parseDouble(l1.substring(20, 32).trim());
        int year = yy < 57 ? 2000 + yy : 1900 + yy;
        epochJd = jdJan0(year) + doy;
        bstar = expField(l1.substring(53, 61));
        double D2R = Math.PI / 180;
        inclo = Double.parseDouble(l2.substring(8, 16).trim()) * D2R;
        nodeo = Double.parseDouble(l2.substring(17, 25).trim()) * D2R;
        ecco = Double.parseDouble("0." + l2.substring(26, 33).trim());
        argpo = Double.parseDouble(l2.substring(34, 42).trim()) * D2R;
        mo = Double.parseDouble(l2.substring(43, 51).trim()) * D2R;
        double noKozai = Double.parseDouble(l2.substring(52, 63).trim()) * TWOPI / 1440.0;
        // ---- initl
        double eccsq = ecco * ecco, omeosq = 1 - eccsq, rteosq = Math.sqrt(omeosq), cosio = Math.cos(inclo), cosio2 = cosio * cosio;
        double ak = Math.pow(XKE / noKozai, X2O3);
        double d1 = 0.75 * J2 * (3 * cosio2 - 1) / (rteosq * omeosq);
        double del = d1 / (ak * ak);
        double adel = ak * (1 - del * del - del * (1.0 / 3.0 + 134 * del * del / 81.0));
        del = d1 / (adel * adel);
        no = noKozai / (1 + del);
        double ao = Math.pow(XKE / no, X2O3), sinio = Math.sin(inclo), po = ao * omeosq;
        double con42 = 1 - 5 * cosio2;
        con41 = -con42 - cosio2 - cosio2;
        double posq = po * po, rp = ao * (1 - ecco);
        if (omeosq < 0 || no <= 0) { ok = false; return; }
        // ---- sgp4init
        double ss = 78.0 / RE + 1, qzms2t = Math.pow((120.0 - 78.0) / RE, 4);
        isimp = rp < 220.0 / RE + 1;
        double sfour = ss, qzms24 = qzms2t, perige = (rp - 1) * RE;
        if (perige < 156) {
            sfour = perige - 78;
            if (perige < 98) sfour = 20;
            qzms24 = Math.pow((120 - sfour) / RE, 4);
            sfour = sfour / RE + 1;
        }
        double pinvsq = 1 / posq, tsi = 1 / (ao - sfour);
        eta = ao * ecco * tsi;
        double etasq = eta * eta, eeta = ecco * eta, psisq = Math.abs(1 - etasq);
        double coef = qzms24 * Math.pow(tsi, 4), coef1 = coef / Math.pow(psisq, 3.5);
        double cc2 = coef1 * no * (ao * (1 + 1.5 * etasq + eeta * (4 + etasq))
                + 0.375 * J2 * tsi / psisq * con41 * (8 + 3 * etasq * (8 + etasq)));
        cc1 = bstar * cc2;
        double cc3 = ecco > 1.0e-4 ? -2 * coef * tsi * J3OJ2 * no * sinio / ecco : 0;
        x1mth2 = 1 - cosio2;
        cc4 = 2 * no * coef1 * ao * omeosq * (eta * (2 + 0.5 * etasq) + ecco * (0.5 + 2 * etasq)
                - J2 * tsi / (ao * psisq) * (-3 * con41 * (1 - 2 * eeta + etasq * (1.5 - 0.5 * eeta))
                + 0.75 * x1mth2 * (2 * etasq - eeta * (1 + etasq)) * Math.cos(2 * argpo)));
        cc5 = 2 * coef1 * ao * omeosq * (1 + 2.75 * (etasq + eeta) + eeta * etasq);
        double cosio4 = cosio2 * cosio2, temp1 = 1.5 * J2 * pinvsq * no, temp2 = 0.5 * temp1 * J2 * pinvsq,
                temp3 = -0.46875 * J4 * pinvsq * pinvsq * no;
        mdot = no + 0.5 * temp1 * rteosq * con41 + 0.0625 * temp2 * rteosq * (13 - 78 * cosio2 + 137 * cosio4);
        argpdot = -0.5 * temp1 * con42 + 0.0625 * temp2 * (7 - 114 * cosio2 + 395 * cosio4) + temp3 * (3 - 36 * cosio2 + 49 * cosio4);
        double xhdot1 = -temp1 * cosio;
        nodedot = xhdot1 + (0.5 * temp2 * (4 - 19 * cosio2) + 2 * temp3 * (3 - 7 * cosio2)) * cosio;
        omgcof = bstar * cc3 * Math.cos(argpo);
        xmcof = ecco > 1.0e-4 ? -X2O3 * coef * bstar / eeta : 0;
        nodecf = 3.5 * omeosq * xhdot1 * cc1;
        t2cof = 1.5 * cc1;
        xlcof = Math.abs(cosio + 1) > 1.5e-12 ? -0.25 * J3OJ2 * sinio * (3 + 5 * cosio) / (1 + cosio)
                : -0.25 * J3OJ2 * sinio * (3 + 5 * cosio) / 1.5e-12;
        aycof = -0.5 * J3OJ2 * sinio;
        delmo = Math.pow(1 + eta * Math.cos(mo), 3);
        sinmao = Math.sin(mo);
        x7thm1 = 7 * cosio2 - 1;
        if (!isimp) {
            double cc1sq = cc1 * cc1;
            d2 = 4 * ao * tsi * cc1sq;
            double temp = d2 * tsi * cc1 / 3.0;
            d3 = (17 * ao + sfour) * temp;
            d4 = 0.5 * temp * ao * tsi * (221 * ao + 31 * sfour) * cc1;
            t3cof = d2 + 2 * cc1sq;
            t4cof = 0.25 * (3 * d3 + cc1 * (12 * d2 + 10 * cc1sq));
            t5cof = 0.2 * (3 * d4 + 12 * cc1 * d3 + 6 * d2 * d2 + 15 * cc1sq * (2 * d2 + cc1sq));
        }
    }

    static double jdJan0(int year) {               // JD of Jan 0.0 UTC (= Dec 31 00:00)
        int y = year - 1, a = y / 100, b = 2 - a + a / 4;
        return Math.floor(365.25 * (y + 4716)) + 428 + 1 + b - 1524.5 - 1;   // Meeus 7.1 for Jan 1, minus one day
    }

    /** TEME position (km) at minutes since epoch; false on decay / numerical failure */
    public boolean pos(double t, double[] out) {
        double xmdf = mo + mdot * t, argpdf = argpo + argpdot * t, nodedf = nodeo + nodedot * t;
        double argpm = argpdf, mm = xmdf, t2 = t * t, nodem = nodedf + nodecf * t2;
        double tempa = 1 - cc1 * t, tempe = bstar * cc4 * t, templ = t2cof * t2;
        if (!isimp) {
            double delomg = omgcof * t, delmtemp = 1 + eta * Math.cos(xmdf);
            double delm = xmcof * (delmtemp * delmtemp * delmtemp - delmo), temp = delomg + delm;
            mm = xmdf + temp; argpm = argpdf - temp;
            double t3 = t2 * t, t4 = t3 * t;
            tempa = tempa - d2 * t2 - d3 * t3 - d4 * t4;
            tempe = tempe + bstar * cc5 * (Math.sin(mm) - sinmao);
            templ = templ + t3cof * t3 + t4 * (t4cof + t * t5cof);
        }
        double nm = no, em = ecco, inclm = inclo;
        if (nm <= 0) return false;
        double am = Math.pow(XKE / nm, X2O3) * tempa * tempa;
        nm = XKE / Math.pow(am, 1.5);
        em = em - tempe;
        if (em >= 1 || em < -0.001 || am < 0.95) return false;
        if (em < 1.0e-6) em = 1.0e-6;
        mm = mm + no * templ;
        double xlm = mm + argpm + nodem;
        nodem = nodem % TWOPI; argpm = argpm % TWOPI; xlm = xlm % TWOPI;
        mm = (xlm - argpm - nodem) % TWOPI;
        double sinip = Math.sin(inclm), cosip = Math.cos(inclm);
        double axnl = em * Math.cos(argpm), temp = 1 / (am * (1 - em * em));
        double aynl = em * Math.sin(argpm) + temp * aycof;
        double xl = mm + argpm + nodem + temp * xlcof * axnl;
        double u = (xl - nodem) % TWOPI, eo1 = u, tem5 = 9999.9, sineo1 = 0, coseo1 = 0;
        for (int k = 0; Math.abs(tem5) >= 1.0e-12 && k < 10; k++) {
            sineo1 = Math.sin(eo1); coseo1 = Math.cos(eo1);
            tem5 = 1 - coseo1 * axnl - sineo1 * aynl;
            tem5 = (u - aynl * coseo1 + axnl * sineo1 - eo1) / tem5;
            if (Math.abs(tem5) >= 0.95) tem5 = tem5 > 0 ? 0.95 : -0.95;
            eo1 += tem5;
        }
        double ecose = axnl * coseo1 + aynl * sineo1, esine = axnl * sineo1 - aynl * coseo1;
        double el2 = axnl * axnl + aynl * aynl, pl = am * (1 - el2);
        if (pl < 0) return false;
        double rl = am * (1 - ecose), betal = Math.sqrt(1 - el2);
        temp = esine / (1 + betal);
        double sinu = am / rl * (sineo1 - aynl - axnl * temp), cosu = am / rl * (coseo1 - axnl + aynl * temp);
        double su = Math.atan2(sinu, cosu), sin2u = (cosu + cosu) * sinu, cos2u = 1 - 2 * sinu * sinu;
        temp = 1 / pl;
        double temp1 = 0.5 * J2 * temp, temp2 = temp1 * temp;
        double mrt = rl * (1 - 1.5 * temp2 * betal * con41) + 0.5 * temp1 * x1mth2 * cos2u;
        su = su - 0.25 * temp2 * x7thm1 * sin2u;
        double xnode = nodem + 1.5 * temp2 * cosip * sin2u, xinc = inclm + 1.5 * temp2 * cosip * sinip * cos2u;
        double sinsu = Math.sin(su), cossu = Math.cos(su), snod = Math.sin(xnode), cnod = Math.cos(xnode),
                sini = Math.sin(xinc), cosi = Math.cos(xinc);
        double xmx = -snod * cosi, xmy = cnod * cosi;
        out[0] = mrt * (xmx * sinsu + cnod * cossu) * RE;
        out[1] = mrt * (xmy * sinsu + snod * cossu) * RE;
        out[2] = mrt * (sini * sinsu) * RE;
        return mrt >= 1;
    }

    public boolean posJd(double jd, double[] out) { return pos((jd - epochJd) * 1440.0, out); }
}
