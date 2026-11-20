package com.tokyonight.hud.live;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.net.URL;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Sky alerts as notifications: aurora GO, geomagnetic storms (G2+), visible ISS / Tiangong passes and Starlink trains.
 * A periodic job (AlertJob, every 15 min, network required) refreshes NOAA data and schedules pass alarms (AlertRx).
 * Notification APIs newer than the compile jar (channels, Builder(ctx, id)) are reached by reflection.
 */
public final class Alerts {
    static final String CH = "sky", PREFS = "hud";
    static final int JOB_ID = 4201;
    static final int N_AURORA = 1, N_STORM = 2, N_PASS = 10, N_TEST = 99;

    static SharedPreferences sp(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    static boolean anyOn(SharedPreferences sp) {
        return sp.getBoolean("alAurora", false) || sp.getBoolean("alStorm", false) || sp.getBoolean("alPass", false) || sp.getBoolean("alStarlink", false);
    }

    /** schedule or cancel the periodic job to match the switches */
    public static void ensureJob(Context c) {
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            if (!anyOn(sp(c))) { js.cancel(JOB_ID); cancelPasses(c); return; }
            for (JobInfo j : js.getAllPendingJobs()) if (j.getId() == JOB_ID) return;
            JobInfo.Builder b = new JobInfo.Builder(JOB_ID, new ComponentName(c, AlertJob.class))
                    .setPeriodic(15 * 60000L)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true);
            js.schedule(b.build());
        } catch (Throwable ignored) { }
    }

    static boolean quiet(SharedPreferences sp, long now) {
        if (!sp.getBoolean("alQuiet", true)) return false;
        int a = sp.getInt("alQuietFrom", 23), b = sp.getInt("alQuietTo", 7);
        Calendar c = Calendar.getInstance(); c.setTimeInMillis(now);
        int h = c.get(Calendar.HOUR_OF_DAY);
        return a <= b ? h >= a && h < b : h >= a || h < b;
    }

    static void channel(Context c) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            Class<?> ncl = Class.forName("android.app.NotificationChannel");
            Object ch = ncl.getConstructor(String.class, CharSequence.class, int.class).newInstance(CH, "Sky alerts", 3 /* IMPORTANCE_DEFAULT */);
            ncl.getMethod("setDescription", String.class).invoke(ch, "Aurora, geomagnetic storms and visible satellite passes");
            NotificationManager.class.getMethod("createNotificationChannel", ncl).invoke(nm, ch);
        } catch (Throwable ignored) { }
    }

    @SuppressWarnings("deprecation")
    public static void notify(Context c, int id, String title, String text) {
        try {
            if (c.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != 0 && android.os.Build.VERSION.SDK_INT >= 33) return;
            channel(c);
            Notification.Builder b;
            try { b = Notification.Builder.class.getConstructor(Context.class, String.class).newInstance(c, CH); }
            catch (Throwable t) { b = new Notification.Builder(c); }
            Intent open = new Intent(c, SetWallpaper.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(c, id, open, PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000 /* FLAG_IMMUTABLE */);
            b.setSmallIcon(c.getResources().getIdentifier("ic_stat", "drawable", c.getPackageName())).setContentTitle(title).setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setColor(0xFF7AA2F7).setAutoCancel(true).setContentIntent(pi).setWhen(System.currentTimeMillis()).setShowWhen(true);
            ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).notify(id, b.build());
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ evaluation (worker thread)

    /** called by AlertJob: refresh space weather if stale, aurora / storm checks, (re)schedule pass alarms */
    public static void evaluate(Context c) {
        SharedPreferences sp = sp(c);
        long now = System.currentTimeMillis();
        if (now - sp.getLong("swUpd", 0) > 20 * 60000L) refreshSpace(c, sp);
        double lat = sp.getFloat("obsLat", Float.NaN), lon = sp.getFloat("obsLon", Float.NaN);
        boolean hush = quiet(sp, now);
        // aurora
        if (sp.getBoolean("alAurora", false) && !Double.isNaN(lat) && now - sp.getLong("swUpd", 0) < 3 * 3600000L) {
            double kp = sp.getFloat("kp", Float.NaN), bz = sp.getFloat("bz", Float.NaN);
            double ml = SpaceWx.geomagLat(lat, lon), sa = Astro.pos(Astro.SUN, now, lat, lon)[0];
            double cloud = cloudNow(sp, now);
            String[] w = new String[1];
            int v = SpaceWx.aurora(kp, bz, sa, cloud, ml, w);
            if (v == 2 && now - sp.getLong("alLastAurora", 0) > 6 * 3600000L && !hush) {
                notify(c, N_AURORA, "Aurora: GO · look north",
                        String.format(Locale.US, "Kp %.1f (you need ~%.1f), Bz %+.1f nT%s. Dark sky now.", kp, SpaceWx.kpNeeded(ml), bz,
                                Double.isNaN(cloud) ? "" : String.format(Locale.US, ", cloud %.0f%%", cloud)));
                sp.edit().putLong("alLastAurora", now).apply();
            }
        }
        // storms
        int g = sp.getInt("scG", -1);
        if (sp.getBoolean("alStorm", false) && g >= 2 && now - sp.getLong("swUpd", 0) < 3 * 3600000L) {
            boolean newer = g > sp.getInt("alLastG", 0) || now - sp.getLong("alLastStorm", 0) > 12 * 3600000L;
            if (newer && !hush) {
                String[] nm = {"", "minor", "moderate", "strong", "severe", "extreme"};
                notify(c, N_STORM, "G" + g + " geomagnetic storm", "NOAA reports a " + nm[Math.min(5, g)] + " geomagnetic storm in progress. Aurora possible far south of usual; GNSS and HF radio may be degraded.");
                sp.edit().putLong("alLastStorm", now).putInt("alLastG", g).apply();
            }
        }
        if (sp.getInt("scG", -1) < 1) sp.edit().putInt("alLastG", 0).apply();
        // passes
        if ((sp.getBoolean("alPass", false) || sp.getBoolean("alStarlink", false)) && !Double.isNaN(lat)) schedulePasses(c, sp, lat, lon, now);
        else cancelPasses(c);
    }

    static double cloudNow(SharedPreferences sp, long now) {
        String t = sp.getString("cloudT", null), v = sp.getString("cloudV", null);
        if (t == null || v == null) return Double.NaN;
        try {
            String[] a = t.split(","), b = v.split(",");
            for (int i = 0; i < a.length && i < b.length; i++) {
                long s = Long.parseLong(a[i]) * 1000L;
                if (now >= s && now < s + 3600000L) return Double.parseDouble(b[i]);
            }
        } catch (Exception ignored) { }
        return Double.NaN;
    }

    static void refreshSpace(Context c, SharedPreferences sp) {
        try {
            SharedPreferences.Editor e = sp.edit();
            boolean any = false;
            try {
                double[][] k = SpaceWx.kp(SetWallpaper.get(new URL(SpaceWx.U_KP)));
                if (k[0].length > 0) { e.putFloat("kp", (float) k[1][k[1].length - 1]); any = true; }
            } catch (Throwable ignored) { }
            try {
                double bz = SpaceWx.latest(SetWallpaper.get(new URL(SpaceWx.U_MAG)), "Bz", "bz_gsm");
                if (!Double.isNaN(bz)) { e.putFloat("bz", (float) bz); any = true; }
            } catch (Throwable ignored) { }
            try {
                int[] s = SpaceWx.scales(SetWallpaper.get(new URL(SpaceWx.U_SCALES)));
                if (s[2] >= 0) { e.putInt("scR", s[0]).putInt("scS", s[1]).putInt("scG", s[2]); any = true; }
            } catch (Throwable ignored) { }
            if (any) e.putLong("swUpd", System.currentTimeMillis());
            e.apply();
        } catch (Throwable ignored) { }
    }

    static PendingIntent passPi(Context c, int k, Intent i) {
        return PendingIntent.getBroadcast(c, 500 + k, i, PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000);
    }

    static void cancelPasses(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        for (int k = 0; k < 3; k++) try { am.cancel(passPi(c, k, new Intent(c, AlertRx.class))); } catch (Throwable ignored) { }
    }

    static void schedulePasses(Context c, SharedPreferences sp, double lat, double lon, long now) {
        java.io.File f = new java.io.File(c.getFilesDir(), "tle.txt");
        if (!f.exists()) return;
        List<Sgp4> L;
        try {
            byte[] b = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int n = 0; while (n < b.length) { int r = in.read(b, n, b.length - n); if (r < 0) break; n += r; }
            in.close();
            L = Sat.parseTle(new String(b, 0, n, "UTF-8"));
        } catch (Exception e) { return; }
        Sat.Grid G = new Sat.Grid(now, 24 * 3600000L, lat, lon);
        Sat.Pass[] P = new Sat.Pass[3];
        String[] nm = {"ISS", "Tiangong", "Starlink train"};
        if (sp.getBoolean("alPass", false)) {
            for (Sgp4 s : L) {
                int k = s.norad == 25544 ? 0 : s.norad == 48274 ? 1 : -1;
                if (k >= 0 && P[k] == null) P[k] = Sat.nextVisible(s, G);
            }
        }
        if (sp.getBoolean("alStarlink", false)) {
            int[] cnt = new int[64];
            List<Sgp4> tr = Sat.trains(L, now, cnt);
            for (int i = 0; i < tr.size(); i++) {
                Sat.Pass p = Sat.nextVisible(tr.get(i), G);
                if (p != null && (P[2] == null || p.visStart < P[2].visStart)) { p.count = cnt[i]; P[2] = p; }
            }
        }
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        for (int k = 0; k < 3; k++) {
            Intent i = new Intent(c, AlertRx.class);
            Sat.Pass p = P[k];
            if (p == null || p.visStart - 10 * 60000L < now + 30000L) {
                if (p == null) am.cancel(passPi(c, k, i));
                continue;
            }
            i.putExtra("name", nm[k]).putExtra("start", p.visStart).putExtra("end", p.visEnd).putExtra("max", p.maxEl)
                    .putExtra("dir", Sat.compass(p.azVisStart) + " → " + Sat.compass(p.azVisEnd)).putExtra("count", p.count).putExtra("k", k);
            try { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, p.visStart - 10 * 60000L, passPi(c, k, i)); }
            catch (Throwable t) { am.set(AlarmManager.RTC_WAKEUP, p.visStart - 10 * 60000L, passPi(c, k, i)); }
        }
    }
}
