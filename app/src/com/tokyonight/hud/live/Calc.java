package com.tokyonight.hud.live;

import java.util.Locale;

/**
 * Small expression evaluator for the launcher pop-up ("= 2*pi*4", or any line that looks like arithmetic).
 * + - * / % ^ (right-associative), unary minus, parentheses, implicit multiplication ("2pi", "3(4+1)"),
 * constants pi, e, functions sqrt cbrt abs ln log (base 10) exp sin cos tan asin acos atan (radians) and
 * sind cosd tand (degrees). Throws IllegalArgumentException on anything else.
 */
final class Calc {
    final String s;
    int i;

    private Calc(String s) { this.s = s.replace(" ", "").replace('×', '*').replace('÷', '/').replace(',', '.').toLowerCase(Locale.ROOT); }

    static double eval(String expr) {
        Calc c = new Calc(expr);
        if (c.s.isEmpty()) throw new IllegalArgumentException("empty");
        double v = c.sum();
        if (c.i != c.s.length()) throw new IllegalArgumentException("unexpected '" + c.s.charAt(c.i) + "'");
        return v;
    }

    /** looks like arithmetic: digits plus at least one operator, nothing but expression characters */
    static boolean looksLike(String t) {
        String x = t.trim();
        if (x.length() < 3 || !x.matches(".*\\d.*") || !x.matches(".*[-+*/^%×÷()].*")) return false;
        if (x.matches("[+\\d\\s()\\-#*.]+") && !x.matches(".*\\d\\s*[-+*/^%]\\s*[\\d(].*")) return false;   // phone-number-ish
        return x.matches("[\\d\\s.,+\\-*/^%()×÷a-zA-Z]+") && x.replaceAll("[^a-zA-Z]", "").matches("(pi|e|sqrt|cbrt|abs|ln|log|exp|sin|cos|tan|asin|acos|atan|sind|cosd|tand)*");
    }

    static String format(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return String.valueOf(v);
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return String.valueOf((long) v);
        String r = String.format(Locale.US, "%.10g", v);
        if (r.contains("e")) return r;
        r = r.replaceAll("0+$", "");
        return r.endsWith(".") ? r.substring(0, r.length() - 1) : r;
    }

    boolean eat(char ch) { if (i < s.length() && s.charAt(i) == ch) { i++; return true; } return false; }

    double sum() {
        double v = product();
        while (true) {
            if (eat('+')) v += product();
            else if (eat('-')) v -= product();
            else return v;
        }
    }

    double product() {
        double v = unary();
        while (true) {
            if (eat('*')) v *= unary();
            else if (eat('/')) v /= unary();
            else if (eat('%')) v %= unary();
            else if (i < s.length() && (s.charAt(i) == '(' || Character.isLetter(s.charAt(i)) || Character.isDigit(s.charAt(i)))) v *= power();   // implicit
            else return v;
        }
    }

    double unary() {
        if (eat('-')) return -unary();
        if (eat('+')) return unary();
        return power();
    }

    double power() {
        double b = atom();
        if (eat('^')) return Math.pow(b, unary());
        return b;
    }

    double atom() {
        if (eat('(')) { double v = sum(); if (!eat(')')) throw new IllegalArgumentException("missing )"); return v; }
        int st = i;
        if (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) {
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
            if (i < s.length() && s.charAt(i) == 'e' && i + 1 < s.length() && (Character.isDigit(s.charAt(i + 1)) || s.charAt(i + 1) == '-')) {
                i++; if (s.charAt(i) == '-') i++;
                while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            }
            return Double.parseDouble(s.substring(st, i));
        }
        while (i < s.length() && Character.isLetter(s.charAt(i))) i++;
        String w = s.substring(st, i);
        if (w.isEmpty()) throw new IllegalArgumentException(i < s.length() ? "unexpected '" + s.charAt(i) + "'" : "incomplete");
        if (w.equals("pi")) return Math.PI;
        if (w.equals("e")) return Math.E;
        double a = unary();
        switch (w) {
            case "sqrt": return Math.sqrt(a);
            case "cbrt": return Math.cbrt(a);
            case "abs": return Math.abs(a);
            case "ln": return Math.log(a);
            case "log": return Math.log10(a);
            case "exp": return Math.exp(a);
            case "sin": return Math.sin(a);
            case "cos": return Math.cos(a);
            case "tan": return Math.tan(a);
            case "asin": return Math.asin(a);
            case "acos": return Math.acos(a);
            case "atan": return Math.atan(a);
            case "sind": return Math.sin(Math.toRadians(a));
            case "cosd": return Math.cos(Math.toRadians(a));
            case "tand": return Math.tan(Math.toRadians(a));
        }
        throw new IllegalArgumentException("unknown '" + w + "'");
    }
}
