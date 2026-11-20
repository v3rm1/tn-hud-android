package com.tokyonight.hud.live;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.dreams.DreamService;
import android.text.format.DateFormat;
import android.view.View;

/**
 * Charging screen saver (Settings > Display > Screen saver on Samsung): GNSS receiver monitor + Voyager distances.
 * GPS runs only while the screen saver is showing (the phone is on a charger). 1 Hz redraw, dim palette,
 * whole-frame offset jumps every minute to spread burn-in.
 */
public class HudDream extends DreamService {
    final Handler h = new Handler(Looper.getMainLooper());
    final HudRenderer r = new HudRenderer();
    final HudRenderer.Data d = new HudRenderer.Data();
    LocationManager lm;
    Object gnssCb;
    boolean gpsOn;
    View view;

    final Runnable tick = new Runnable() { public void run() { if (view != null) view.invalidate(); h.postDelayed(this, 1000 - System.currentTimeMillis() % 1000 + 5); } };

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(false);
        try { r.setClockTypeface(android.graphics.Typeface.createFromAsset(getAssets(), "ShareTechMono-Regular.ttf")); } catch (Exception ignored) { }
        view = new View(this) {
            @Override protected void onSizeChanged(int w, int hh, int ow, int oh) { r.setSize(w, hh); }
            @Override protected void onDraw(Canvas c) {
                d.wallMs = System.currentTimeMillis();
                boolean red = redNow();
                if (red) c.saveLayer(null, redPaint());
                r.drawAod(c, d);
                if (red) c.restore();
            }
        };
        view.setBackgroundColor(0xFF000000);
        setContentView(view);
    }

    @Override public void onDreamingStarted() {
        super.onDreamingStarted();
        d.h24 = DateFormat.is24HourFormat(this);
        SharedPreferences tp = getSharedPreferences("hud", Context.MODE_PRIVATE);
        r.setTextScale(HudRenderer.textScales(tp.getInt("textScale", 0), tp.getInt("textScaleLock", -1), tp.getInt("textScaleDream", -1),
                getResources().getConfiguration().fontScale)[2]);
        Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b != null) {
            int lvl = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), sc = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int st = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            if (lvl >= 0 && sc > 0) d.battery = Math.round(100f * lvl / sc);
            d.charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL;
        }
        SharedPreferences sp = getSharedPreferences("hud", Context.MODE_PRIVATE);
        d.swUpd = sp.getLong("swUpd", 0);
        d.kp = sp.getFloat("kp", Float.NaN); d.bz = sp.getFloat("bz", Float.NaN); d.xrFlux = sp.getFloat("xr", Float.NaN); d.scG = sp.getInt("scG", -1);
        startGps();
        h.post(tick);
    }

    @Override public void onDreamingStopped() {
        h.removeCallbacks(tick);
        stopGps();
        super.onDreamingStopped();
    }

    @Override public void onDetachedFromWindow() {
        h.removeCallbacks(tick);
        stopGps();
        super.onDetachedFromWindow();
    }

    final LocationListener fixL = new LocationListener() {
        public void onLocationChanged(Location l) {
            d.gLat = l.getLatitude(); d.gLon = l.getLongitude(); d.gAcc = l.hasAccuracy() ? l.getAccuracy() : Float.NaN;
            d.gAlt = l.hasAltitude() ? l.getAltitude() : Double.NaN; d.gFixAt = System.currentTimeMillis();
        }
        public void onStatusChanged(String p, int s, Bundle b) { }
        public void onProviderEnabled(String p) { }
        public void onProviderDisabled(String p) { d.gState = "LOCATION IS OFF"; }
    };

    android.graphics.Paint redP;
    boolean redNow() {
        SharedPreferences sp = getSharedPreferences("hud", Context.MODE_PRIVATE);
        int m = sp.getInt("redMode", 0);
        if (m == 3) return true;
        double la = sp.getFloat("obsLat", Float.NaN), lo = sp.getFloat("obsLon", Float.NaN);
        if (m == 0 || Double.isNaN(la)) return false;
        double sa = Astro.pos(Astro.SUN, System.currentTimeMillis(), la, lo)[0];
        if (m == 1) return sa < -6;
        if (sa >= -12 || System.currentTimeMillis() - d.swUpd > 3 * 3600000L) return false;
        String[] w = new String[1];
        return SpaceWx.aurora(d.kp, d.bz, sa, Alerts.cloudNow(sp, System.currentTimeMillis()), SpaceWx.geomagLat(la, lo), w) == 2;
    }
    android.graphics.Paint redPaint() {
        if (redP == null) {
            redP = new android.graphics.Paint();
            redP.setColorFilter(new android.graphics.ColorMatrixColorFilter(new android.graphics.ColorMatrix(new float[]{
                    0.42f, 0.82f, 0.16f, 0, 0,   0, 0.04f, 0, 0, 0,   0, 0, 0.02f, 0, 0,   0, 0, 0, 1, 0})));
        }
        return redP;
    }

    void startGps() {
        if (gpsOn) return;
        if (checkSelfPermission("android.permission.ACCESS_FINE_LOCATION") != 0) {
            d.gState = "GRANT PRECISE LOCATION IN TN-HUD SETTINGS"; return;
        }
        lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) { d.gState = "NO GNSS"; return; }
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) d.gState = "LOCATION IS OFF";
        try {
            d.gStart = System.currentTimeMillis(); d.gTtff = -1;
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, fixL, Looper.getMainLooper());
            gnssCb = new GnssStatus.Callback() {
                @Override public void onFirstFix(int ms) { d.gTtff = ms; }
                @Override public void onSatelliteStatusChanged(GnssStatus st) { onSats(st); }
            };
            LocationManager.class.getMethod("registerGnssStatusCallback", GnssStatus.Callback.class, Handler.class).invoke(lm, gnssCb, h);
            gpsOn = true;
        } catch (Throwable e) {
            d.gState = e instanceof SecurityException || e.getCause() instanceof SecurityException
                    ? "ALLOW LOCATION ALL THE TIME FOR THE SCREEN SAVER" : "GNSS UNAVAILABLE";
        }
    }

    void stopGps() {
        if (!gpsOn || lm == null) return;
        try { lm.removeUpdates(fixL); } catch (Throwable ignored) { }
        try { LocationManager.class.getMethod("unregisterGnssStatusCallback", GnssStatus.Callback.class).invoke(lm, gnssCb); } catch (Throwable ignored) { }
        gpsOn = false;
    }

    /** merge dual-frequency duplicates (same constellation + svid): keep the strongest, flag L5 / E5a / B2a */
    void onSats(GnssStatus st) {
        int n = st.getSatelliteCount(), m = 0;
        for (int i = 0; i < n && m < HudRenderer.Data.GMAX; i++) {
            int cons = st.getConstellationType(i), sv = st.getSvid(i);
            float cn = st.getCn0DbHz(i);
            boolean l5 = false;
            try { l5 = st.hasCarrierFrequencyHz(i) && st.getCarrierFrequencyHz(i) < 1.3e9f; } catch (Throwable ignored) { }
            int j = 0;
            while (j < m && !(d.gCons[j] == cons && d.gSvid[j] == sv)) j++;
            if (j == m) {
                d.gCons[m] = cons; d.gSvid[m] = sv; d.gCn0[m] = cn; d.gEl[m] = st.getElevationDegrees(i); d.gAz[m] = st.getAzimuthDegrees(i);
                d.gUsed[m] = st.usedInFix(i); d.gL5[m] = l5; m++;
            } else {
                if (cn > d.gCn0[j]) d.gCn0[j] = cn;
                d.gUsed[j] |= st.usedInFix(i); d.gL5[j] |= l5;
            }
        }
        d.gN = m;
        if (m > 0) d.gState = null;
    }
}
