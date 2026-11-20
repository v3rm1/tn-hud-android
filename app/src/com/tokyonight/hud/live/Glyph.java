package com.tokyonight.hud.live;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Unistroke glyph recogniser (Protractor-style, orientation sensitive: a W is not an M).
 * A stroke is resampled to N points, centred, scaled uniformly (aspect kept, so lines and letters both work) and
 * flattened to a unit vector; similarity is the cosine between vectors. Built-in letter templates can be
 * supplemented with the user's own samples (taught in the settings app).
 */
final class Glyph {
    static final int N = 32;
    static final String[] NAMES = {"W", "M", "V", "N", "Z", "L", "C", "O", "S", "G", "P", "✓", "Λ"};
    static final float ACCEPT = 0.86f, MARGIN = 0.015f;

    /** built-in templates in a unit box, y down, drawn the usual way */
    static float[][] builtin(String g) {
        switch (g) {
            case "W": return poly(0, 0, .25f, 1, .5f, .35f, .75f, 1, 1, 0);
            case "M": return poly(0, 1, .25f, 0, .5f, .65f, .75f, 0, 1, 1);
            case "V": return poly(0, 0, .5f, 1, 1, 0);
            case "N": return poly(0, 1, 0, 0, 1, 1, 1, 0);
            case "Z": return poly(0, 0, 1, 0, 0, 1, 1, 1);
            case "L": return poly(0, 0, 0, 1, .7f, 1);
            case "C": return arc(.5f, .5f, .5f, 60, 300);
            case "O": return arc(.5f, .5f, .5f, 90, 450);
            case "S": return poly(.9f, .1f, .5f, 0, .1f, .15f, .1f, .4f, .5f, .5f, .9f, .6f, .9f, .85f, .5f, 1, .1f, .9f);
            case "G": return poly(.9f, .15f, .5f, 0, .1f, .25f, .05f, .6f, .3f, .95f, .7f, 1, .95f, .75f, .95f, .55f, .55f, .55f);
            case "P": return poly(0, 1, 0, 0, .6f, 0, .85f, .2f, .6f, .45f, 0, .45f);
            case "✓": return poly(0, .6f, .35f, 1, 1, 0);
            case "Λ": return poly(0, 1, .5f, 0, 1, 1);
        }
        return null;
    }

    static float[][] poly(float... xy) {
        float[][] p = new float[xy.length / 2][];
        for (int i = 0; i < p.length; i++) p[i] = new float[]{xy[2 * i], xy[2 * i + 1]};
        return p;
    }

    /** arc, angles in degrees counter-clockwise on screen (y down) */
    static float[][] arc(float cx, float cy, float r, float a0, float a1) {
        int n = 40; float[][] p = new float[n][];
        for (int i = 0; i < n; i++) {
            double t = Math.toRadians(a0 + (a1 - a0) * i / (n - 1));
            p[i] = new float[]{cx + r * (float) Math.cos(t), cy - r * (float) Math.sin(t)};
        }
        return p;
    }

    static float pathLen(List<float[]> p) {
        float L = 0;
        for (int i = 1; i < p.size(); i++) L += Math.hypot(p.get(i)[0] - p.get(i - 1)[0], p.get(i)[1] - p.get(i - 1)[1]);
        return L;
    }

    /** resample + normalise to a unit vector of 2N floats; null if the stroke is degenerate */
    static float[] vector(List<float[]> pts) {
        if (pts.size() < 2) return null;
        float L = pathLen(pts);
        if (L <= 0) return null;
        float step = L / (N - 1), acc = 0;
        List<float[]> src = new ArrayList<float[]>(pts), out = new ArrayList<float[]>();
        out.add(src.get(0));
        for (int i = 1; i < src.size(); i++) {
            float[] a = src.get(i - 1), b = src.get(i);
            float d = (float) Math.hypot(b[0] - a[0], b[1] - a[1]);
            if (acc + d >= step && d > 0) {
                float t = (step - acc) / d;
                float[] q = {a[0] + t * (b[0] - a[0]), a[1] + t * (b[1] - a[1])};
                out.add(q); src.add(i, q); acc = 0;
            } else acc += d;
        }
        while (out.size() < N) out.add(src.get(src.size() - 1));
        float cx = 0, cy = 0;
        for (int i = 0; i < N; i++) { cx += out.get(i)[0]; cy += out.get(i)[1]; }
        cx /= N; cy /= N;
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0; i < N; i++) { float[] q = out.get(i); minX = Math.min(minX, q[0]); maxX = Math.max(maxX, q[0]); minY = Math.min(minY, q[1]); maxY = Math.max(maxY, q[1]); }
        float sc = Math.max(maxX - minX, maxY - minY);
        if (sc <= 0) return null;
        float[] v = new float[2 * N]; double norm = 0;
        for (int i = 0; i < N; i++) { v[2 * i] = (out.get(i)[0] - cx) / sc; v[2 * i + 1] = (out.get(i)[1] - cy) / sc; norm += v[2 * i] * v[2 * i] + v[2 * i + 1] * v[2 * i + 1]; }
        float n = (float) Math.sqrt(norm);
        for (int i = 0; i < v.length; i++) v[i] /= n;
        return v;
    }

    static float[] vector(float[][] pts) {
        List<float[]> l = new ArrayList<float[]>();
        for (float[] p : pts) l.add(p);
        return vector(l);
    }

    static float cos(float[] a, float[] b) {
        float s = 0;
        for (int i = 0; i < a.length; i++) s += a[i] * b[i];
        return s;
    }

    /** taught samples: pref "gt_<glyph>" = ';'-separated vectors of comma floats */
    static List<float[]> taught(SharedPreferences sp, String g) {
        List<float[]> out = new ArrayList<float[]>();
        String raw = sp.getString("gt_" + g, null);
        if (raw == null || raw.isEmpty()) return out;
        for (String smp : raw.split(";")) {
            String[] f = smp.split(",");
            if (f.length != 2 * N) continue;
            float[] v = new float[2 * N];
            try { for (int i = 0; i < v.length; i++) v[i] = Float.parseFloat(f[i]); out.add(v); } catch (Exception ignored) { }
        }
        return out;
    }

    static void teach(SharedPreferences sp, String g, float[] v) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < v.length; i++) { if (i > 0) b.append(','); b.append(String.format(Locale.US, "%.4f", v[i])); }
        String old = sp.getString("gt_" + g, "");
        String[] keep = old.isEmpty() ? new String[0] : old.split(";");
        StringBuilder all = new StringBuilder();
        for (int i = Math.max(0, keep.length - 4); i < keep.length; i++) all.append(keep[i]).append(';');   // keep the last 5
        all.append(b);
        sp.edit().putString("gt_" + g, all.toString()).apply();
    }

    /** best glyph over all templates: {name or null if below threshold / ambiguous, score} */
    static Object[] recognise(SharedPreferences sp, List<float[]> stroke) {
        float[] v = vector(stroke);
        if (v == null) return null;
        String best = null; float b1 = -2, b2 = -2;
        for (String g : NAMES) {
            float s = -2;
            float[][] t = builtin(g);
            if (t != null) s = Math.max(s, cos(v, vector(t)));
            if (sp != null) for (float[] u : taught(sp, g)) s = Math.max(s, cos(v, u));
            if (s > b1) { b2 = b1; b1 = s; best = g; } else if (s > b2) b2 = s;
        }
        if (best == null || b1 < ACCEPT || b1 - b2 < MARGIN) return new Object[]{null, b1};
        return new Object[]{best, b1};
    }
}
