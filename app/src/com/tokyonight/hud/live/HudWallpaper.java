package com.tokyonight.hud.live;

import android.app.ActivityManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.content.ComponentName;
import android.media.MediaMetadata;
import android.media.audiofx.Visualizer;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import java.util.List;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.telephony.TelephonyManager;
import android.text.format.DateFormat;
import java.lang.reflect.Method;
import java.net.URL;
import org.json.JSONObject;
import android.app.AlarmManager;
import android.app.NotificationManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TrafficStats;
import android.os.PowerManager;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.os.SystemClock;
import android.service.wallpaper.WallpaperService;
import android.view.Surface;
import android.view.SurfaceHolder;
import java.util.Calendar;
import java.util.Locale;

/**
 * State machine:
 *   keyguard showing           -> LOCKED     (20 fps, only while visible)
 *   keyguard dismissed         -> UNLOCKING  (60 fps, 1.4 s)
 *   afterwards / home screen   -> STATIC     (single frame; redrawn on visibility, minute tick, battery change)
 * Preview (system picker) loops lock -> unlock -> dashboard as a demo.
 */
public class HudWallpaper extends WallpaperService {
    @Override public Engine onCreateEngine() { return new HudEngine(); }

    class HudEngine extends Engine {
        final Handler h = new Handler(Looper.getMainLooper());
        final HudRenderer r = new HudRenderer();
        final HudRenderer.Data d = new HudRenderer.Data();
        final long t0 = SystemClock.uptimeMillis();
        KeyguardManager km;
        int mode = HudRenderer.STATIC;
        long unlockAt;
        boolean visible, ready, hw = true, registered;
        long lastSample, lastRx, lastTx, lastMotion, lastWxTry;
        boolean sensorsOn, wxBusy;
        String wxFor;
        SensorManager sm;
        final float[] lastA = new float[3], lastG = new float[3];
        final SensorEventListener sel = new SensorEventListener() {
            public void onSensorChanged(SensorEvent e) {
                float[] t = e.sensor.getType() == Sensor.TYPE_GYROSCOPE ? lastG : lastA;
                t[0] = e.values[0]; t[1] = e.values[1]; t[2] = e.values[2];
            }
            public void onAccuracyChanged(Sensor sn, int a) { }
        };

        // ---- sensor lab: magnetometer, barometer, light (home screen only)
        final float[] lastM = new float[3];
        float lastP = Float.NaN, lastL = Float.NaN, hx, hy, wxMsl = Float.NaN, decl;
        boolean labOn, declOk;
        double declLat = Double.NaN, declLon = Double.NaN;
        long lastPSave;
        final SensorEventListener labL = new SensorEventListener() {
            public void onSensorChanged(SensorEvent e) {
                int t = e.sensor.getType();
                if (t == Sensor.TYPE_MAGNETIC_FIELD) { lastM[0] = e.values[0]; lastM[1] = e.values[1]; lastM[2] = e.values[2]; }
                else if (t == Sensor.TYPE_PRESSURE) lastP = e.values[0];
                else if (t == Sensor.TYPE_LIGHT) lastL = e.values[0];
            }
            public void onAccuracyChanged(Sensor sn, int a) { }
        };

        void setLab(boolean want) {
            if (sm == null) sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
            if (sm == null) return;
            if (want && !labOn) {
                Sensor m = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD), pr = sm.getDefaultSensor(Sensor.TYPE_PRESSURE), li = sm.getDefaultSensor(Sensor.TYPE_LIGHT);
                d.hasMag = m != null;
                if (m != null) sm.registerListener(labL, m, SensorManager.SENSOR_DELAY_UI);
                if (pr != null) sm.registerListener(labL, pr, SensorManager.SENSOR_DELAY_NORMAL);
                if (li != null) sm.registerListener(labL, li, SensorManager.SENSOR_DELAY_NORMAL);
                java.util.Arrays.fill(lastM, 0f); lastP = Float.NaN; lastL = Float.NaN; hx = 0; hy = 0;
                labOn = true;
            } else if (!want && labOn) {
                sm.unregisterListener(labL); labOn = false;
            }
        }

        /** heading (true if declination known), field, tilt, pressure/altitude, light */
        void labCompute() {
            if (!labOn) { d.heading = Float.NaN; d.fieldUT = Float.NaN; d.hPa = Float.NaN; d.lux = Float.NaN; d.pitch = Float.NaN; return; }
            boolean haveA = lastA[0] != 0 || lastA[1] != 0 || lastA[2] != 0, haveM = lastM[0] != 0 || lastM[1] != 0 || lastM[2] != 0;
            d.fieldUT = haveM ? (float) Math.sqrt(lastM[0] * lastM[0] + lastM[1] * lastM[1] + lastM[2] * lastM[2]) : (d.hasMag ? Float.NaN : Float.NaN);
            float[] Rm = new float[9], I = new float[9], o = new float[3];
            if (haveA && haveM && SensorManager.getRotationMatrix(Rm, I, lastA, lastM)) {
                SensorManager.getOrientation(Rm, o);
                d.pitch = (float) Math.toDegrees(o[1]); d.roll = (float) Math.toDegrees(o[2]);
                float az;
                if (Math.abs(d.pitch) > 45 || Math.abs(d.roll) > 45) {          // held upright: direction the back camera faces
                    float[] R2 = new float[9], o2 = new float[3];
                    SensorManager.remapCoordinateSystem(Rm, SensorManager.AXIS_X, SensorManager.AXIS_Z, R2);
                    SensorManager.getOrientation(R2, o2); az = (float) Math.toDegrees(o2[0]);
                } else az = (float) Math.toDegrees(o[0]);                        // lying flat: direction the top edge points
                if (!Double.isNaN(d.obsLat) && (d.obsLat != declLat || d.obsLon != declLon)) {
                    try {
                        decl = new android.hardware.GeomagneticField((float) d.obsLat, (float) d.obsLon, 0f, System.currentTimeMillis()).getDeclination();
                        declOk = true; declLat = d.obsLat; declLon = d.obsLon;
                    } catch (Throwable ignored) { }
                }
                if (declOk) az += decl;
                d.headTrue = declOk;
                double t = Math.toRadians(az);                                   // circular low-pass
                hx = hx * 0.75f + 0.25f * (float) Math.cos(t); hy = hy * 0.75f + 0.25f * (float) Math.sin(t);
                d.heading = (float) ((Math.toDegrees(Math.atan2(hy, hx)) + 360) % 360);
            } else { d.heading = Float.NaN; }
            d.hPa = lastP;
            if (!Float.isNaN(lastP)) {
                boolean qnh = !Float.isNaN(wxMsl) && wxMsl > 900;
                d.altM = SensorManager.getAltitude(qnh ? wxMsl : SensorManager.PRESSURE_STANDARD_ATMOSPHERE, lastP);
                d.altQnh = qnh;
            } else d.altM = Float.NaN;
            d.lux = lastL;
        }

        /** pressure history in prefs (one point / 10 min, 4 h kept) -> 3 h tendency */
        void pressureTrend() {
            if (Float.isNaN(lastP)) { d.hPaTrend = Float.NaN; return; }
            long now = System.currentTimeMillis();
            SharedPreferences sp = getSharedPreferences("hud", Context.MODE_PRIVATE);
            String h = sp.getString("pHist", "");
            StringBuilder keep = new StringBuilder();
            long bestDt = Long.MAX_VALUE; float best = Float.NaN;
            for (String e : h.split(";")) {
                int i = e.indexOf(',');
                if (i <= 0) continue;
                try {
                    long t = Long.parseLong(e.substring(0, i)); float v = Float.parseFloat(e.substring(i + 1));
                    if (now - t > 4 * 3600000L) continue;
                    keep.append(t).append(',').append(v).append(';');
                    long dt = Math.abs((now - t) - 3 * 3600000L);
                    if (dt < 45 * 60000L && dt < bestDt) { bestDt = dt; best = v; }
                } catch (Exception ignored) { }
            }
            d.hPaTrend = Float.isNaN(best) ? Float.NaN : lastP - best;
            if (now - lastPSave > 10 * 60000L) {
                lastPSave = now;
                keep.append(now).append(',').append(String.format(Locale.US, "%.2f", lastP)).append(';');
                sp.edit().putString("pHist", keep.toString()).apply();
            }
        }

        void setSensors(boolean want) {
            if (sm == null) sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
            if (sm == null) return;
            if (want && !sensorsOn) {
                Sensor a = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), g = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
                if (a != null) sm.registerListener(sel, a, SensorManager.SENSOR_DELAY_UI);
                if (g != null) sm.registerListener(sel, g, SensorManager.SENSOR_DELAY_UI);
                sensorsOn = a != null || g != null;
                java.util.Arrays.fill(lastA, 0f); java.util.Arrays.fill(lastG, 0f); stillSince = 0;
                d.clearMotion();
            } else if (!want && sensorsOn) {
                sm.unregisterListener(sel); sensorsOn = false;
            }
        }

        // ---- now playing
        MediaSessionManager msm;
        long mPosBase, mPosAt, vizFailAt;
        float mSpeed = 1f;
        Visualizer viz;
        byte[] fft;

        // Track info from players' public "now playing" broadcasts (no notification access needed).
        String bTitle, bArtist, bApp;
        long bDur, bPos, bPosAt, bSeen;
        boolean bPlaying, mediaRxOn;

        final BroadcastReceiver mediaRx = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent i) {
                String a = i.getAction();
                if (a == null) return;
                long now = SystemClock.elapsedRealtime();
                boolean spotify = a.startsWith("com.spotify.music.");
                if (a.endsWith("metadatachanged") || a.endsWith("metachanged")) {
                    String t = i.getStringExtra("track");
                    if (t == null) t = i.getStringExtra("title");
                    if (t != null) {
                        bTitle = t; bArtist = i.getStringExtra("artist");
                        Object len = i.getExtras() == null ? null : i.getExtras().get(spotify ? "length" : "duration");
                        bDur = len instanceof Number ? ((Number) len).longValue() : 0;
                        bApp = spotify ? "SPOTIFY" : a.startsWith("com.sec.") || a.startsWith("com.samsung.") ? "SAMSUNG MUSIC" : null;
                        bPos = 0; bPosAt = now;
                    }
                }
                if (i.hasExtra("playing")) bPlaying = i.getBooleanExtra("playing", bPlaying);
                Object pos = i.getExtras() == null ? null : i.getExtras().get(spotify ? "playbackPosition" : "position");
                if (pos instanceof Number) { bPos = ((Number) pos).longValue(); bPosAt = now; }
                bSeen = now;
                if (visible && mode == HudRenderer.STATIC) { updateMedia(); frame(); }
            }
        };

        void setMediaRx(boolean on) {
            if (on && !mediaRxOn) {
                IntentFilter f = new IntentFilter();
                for (String p : new String[]{"com.spotify.music", "com.android.music", "com.sec.android.app.music", "com.samsung.sec.android.MusicPlayer"}) {
                    f.addAction(p + ".metadatachanged"); f.addAction(p + ".metachanged");
                    f.addAction(p + ".playbackstatechanged"); f.addAction(p + ".playstatechanged");
                }
                try {   // app broadcasts on Android 14 need RECEIVER_EXPORTED (0x2); method is API 26+
                    Context.class.getMethod("registerReceiver", BroadcastReceiver.class, IntentFilter.class, int.class)
                            .invoke(HudWallpaper.this, mediaRx, f, 2);
                    mediaRxOn = true;
                } catch (Throwable e) {
                    try { registerReceiver(mediaRx, f); mediaRxOn = true; } catch (Throwable ignored) { }
                }
            } else if (!on && mediaRxOn) {
                try { unregisterReceiver(mediaRx); } catch (Throwable ignored) { }
                mediaRxOn = false;
            }
        }

        void updateMedia() {
            if (Link.mediaActive()) {                          // companion: real metadata from any player's media session
                d.mAccess = true; d.mActive = true; d.mPlaying = Link.mState == 3;
                d.mTitle = Link.mTitle; d.mArtist = Link.mArtist; d.mApp = Link.mApp == null ? null : Link.mApp.toUpperCase(Locale.US);
                d.mDur = Link.mDur; mPosBase = Link.mPos; mPosAt = Link.mPosAt; mSpeed = Link.mState == 3 ? Link.mSpeed : 0f;
                return;
            }
            boolean musicActive = false;
            try { musicActive = ((AudioManager) getSystemService(Context.AUDIO_SERVICE)).isMusicActive(); } catch (Throwable ignored) { }
            long now = SystemClock.elapsedRealtime();
            boolean recent = bTitle != null && now - bSeen < 15 * 60000L;
            d.mAccess = bTitle != null;                       // false -> renderer shows the broadcast hint
            if (musicActive || (recent && bPlaying)) {
                d.mActive = true; d.mPlaying = musicActive;
            } else if (recent) {
                d.mActive = true; d.mPlaying = false;         // paused, keep panel for a while
            } else {
                d.mActive = false; d.mPlaying = false;
            }
            if (recent) {
                d.mTitle = bTitle; d.mArtist = bArtist; d.mApp = bApp; d.mDur = bDur;
                mPosBase = bPos; mPosAt = bPosAt; mSpeed = 1f;
            } else {
                d.mTitle = null; d.mArtist = null; d.mApp = null; d.mDur = 0; mPosBase = 0; mPosAt = 0;
            }
        }

        // ---- lock screen data
        boolean lockMotion = true, stepOn;
        long alertUntil, stillSince;
        long[] pT, sunR, sunS;
        float[] pV;
        Sensor stepS;

        final SensorEventListener stepL = new SensorEventListener() {
            public void onSensorChanged(SensorEvent e) { onSteps((long) e.values[0]); }
            public void onAccuracyChanged(Sensor sn, int a) { }
        };

        int dayKey(long ms) {
            Calendar c = Calendar.getInstance(); c.setTimeInMillis(ms);
            return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR);
        }

        void countUnlock() {
            SharedPreferences sp = getSharedPreferences("hud", Context.MODE_PRIVATE);
            long now = System.currentTimeMillis();
            if (now - sp.getLong("lastUnlock", 0) < 2000) return;          // another engine instance already counted it
            int day = dayKey(now);
            int n = sp.getInt("unlDay", 0) == day ? sp.getInt("unlCount", 0) : 0;
            sp.edit().putInt("unlDay", day).putInt("unlCount", n + 1).putLong("lastUnlock", now).apply();
            d.unlocks = n + 1; d.lastUnlock = now;
        }

        void ensureSteps() {
            if (Build.VERSION.SDK_INT >= 29 && checkSelfPermission("android.permission.ACTIVITY_RECOGNITION") != PackageManager.PERMISSION_GRANTED) {
                d.steps = -1; d.stepsState = "GRANT IN APP"; return;
            }
            if (!stepOn) {
                if (sm == null) sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
                stepS = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
                if (stepS == null) { d.steps = -1; d.stepsState = "NO STEP SENSOR"; return; }
                // hardware counter, batched up to 60 s: no wakeups of its own
                stepOn = sm.registerListener(stepL, stepS, SensorManager.SENSOR_DELAY_NORMAL, 60000000);
                if (!stepOn) { d.steps = -1; d.stepsState = "STEP SENSOR BUSY"; return; }
            }
            SharedPreferences sp = getSharedPreferences("hud", Context.MODE_PRIVATE);
            if (sp.getInt("stepDay", 0) != dayKey(System.currentTimeMillis())) d.steps = 0;
            else if (d.steps < 0) d.steps = (int) Math.max(0, sp.getLong("stepOff", 0) + sp.getLong("stepLast", 0) - sp.getLong("stepBase", 0));
        }

        void onSteps(long v) {
            SharedPreferences sp = getSharedPreferences("hud", Context.MODE_PRIVATE);
            int day = dayKey(System.currentTimeMillis());
            long base = sp.getLong("stepBase", -1), last = sp.getLong("stepLast", -1), off = sp.getLong("stepOff", 0);
            int sday = sp.getInt("stepDay", 0);
            if (sday != day) { base = (last >= 0 && last <= v) ? last : v; off = 0; sday = day; }   // new day
            else if (last >= 0 && v < last) { off += last - base; base = 0; }                        // counter reset by reboot
            else if (base < 0) base = v;
            d.steps = (int) Math.max(0, off + v - base);
            sp.edit().putInt("stepDay", sday).putLong("stepBase", base).putLong("stepLast", v).putLong("stepOff", off).apply();
        }

        /** lock screen: motion alert (only after the phone has been still for 3 s), spectrum ring. */
        long lockLive(long now) {
            setSensors(visible && lockMotion);
            if (sensorsOn && (lastA[0] != 0 || lastA[1] != 0 || lastA[2] != 0)) {
                float a = (float) Math.sqrt(lastA[0] * lastA[0] + lastA[1] * lastA[1] + lastA[2] * lastA[2]);
                float dev = Math.abs(a - 9.81f);
                float w = (float) Math.sqrt(lastG[0] * lastG[0] + lastG[1] * lastG[1] + lastG[2] * lastG[2]);
                if (dev < 0.35f && w < 0.15f) { if (stillSince == 0) stillSince = now; }
                else if (dev > 1.2f || w > 0.8f) {
                    if (stillSince != 0 && now - stillSince > 3000) alertUntil = now + 2500;
                    stillSince = 0;
                }
            }
            d.alertLeft = Math.max(0, alertUntil - now);
            return 50;
        }

        long iso(String s, int offSec) {
            Calendar u = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
            u.clear();
            u.set(Integer.parseInt(s.substring(0, 4)), Integer.parseInt(s.substring(5, 7)) - 1, Integer.parseInt(s.substring(8, 10)),
                    Integer.parseInt(s.substring(11, 13)), Integer.parseInt(s.substring(14, 16)));
            return u.getTimeInMillis() - offSec * 1000L;
        }

        void parseExtras(JSONObject fj) {
            int off = fj.optInt("utc_offset_seconds", 0);
            try {
                JSONObject dl = fj.getJSONObject("daily");
                org.json.JSONArray r = dl.getJSONArray("sunrise"), st = dl.getJSONArray("sunset");
                long[] a = new long[r.length()], b = new long[st.length()];
                for (int i = 0; i < a.length; i++) a[i] = r.isNull(i) ? 0 : iso(r.getString(i), off);
                for (int i = 0; i < b.length; i++) b[i] = st.isNull(i) ? 0 : iso(st.getString(i), off);
                sunR = a; sunS = b;
            } catch (Exception e) { sunR = null; sunS = null; }
            try {
                JSONObject m = fj.getJSONObject("minutely_15");
                org.json.JSONArray t = m.getJSONArray("time"), pr = m.getJSONArray("precipitation");
                long[] tt = new long[t.length()]; float[] vv = new float[t.length()];
                for (int i = 0; i < tt.length; i++) { tt[i] = iso(t.getString(i), off); vv[i] = (float) pr.optDouble(i, 0); }
                pT = tt; pV = vv;
            } catch (Exception e) { pT = null; pV = null; }
            try {
                JSONObject hr = fj.getJSONObject("hourly");
                org.json.JSONArray t = hr.getJSONArray("time"), lo = hr.getJSONArray("cloud_cover_low"), mi = hr.getJSONArray("cloud_cover_mid");
                long[] tt = new long[t.length()]; float[] vv = new float[t.length()];
                for (int i = 0; i < tt.length; i++) { tt[i] = iso(t.getString(i), off); vv[i] = (float) Math.max(lo.optDouble(i, Double.NaN), mi.optDouble(i, Double.NaN)); }
                cT = tt; cV = vv; d.cloudT = tt; d.cloudV = vv;
                StringBuilder a = new StringBuilder(), b = new StringBuilder();
                for (int i = 0; i < tt.length; i++) { if (i > 0) { a.append(','); b.append(','); } a.append(tt[i] / 1000); b.append(Float.isNaN(vv[i]) ? "NaN" : String.valueOf(Math.round(vv[i]))); }
                getSharedPreferences("hud", Context.MODE_PRIVATE).edit().putString("cloudT", a.toString()).putString("cloudV", b.toString()).apply();
            } catch (Exception e) { cT = null; cV = null; }
            try {
                JSONObject hr = fj.getJSONObject("hourly");
                org.json.JSONArray t = hr.getJSONArray("time"), te = hr.getJSONArray("temperature_2m");
                org.json.JSONArray pp = hr.optJSONArray("precipitation_probability");
                long[] tt = new long[t.length()]; float[] tv = new float[t.length()], pv = new float[t.length()];
                for (int i = 0; i < tt.length; i++) {
                    tt[i] = iso(t.getString(i), off);
                    tv[i] = te.isNull(i) ? Float.NaN : (float) te.optDouble(i, Double.NaN);
                    pv[i] = pp == null || pp.isNull(i) ? Float.NaN : (float) pp.optDouble(i, Double.NaN);
                }
                d.hrT = tt; d.hrTemp = tv; d.hrPop = pv;
            } catch (Exception e) { d.hrT = null; }
        }

        long[] cT; float[] cV;

        // ================================================================ v20: space weather, satellites, sky events
        long spAt, spTry, xrAt, tleAt, tleTry, astroAt;
        boolean spBusy, tleBusy, astroBusy;
        double astroLat = Double.NaN, astroLon = Double.NaN;
        java.util.List<Sgp4> sats;
        long satsAt;

        boolean online() {
            try {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                Network n = cm.getActiveNetwork();
                NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
                return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            } catch (Exception e) { return false; }
        }

        /** 1 Hz from sample(): cheap schedule checks; network and heavy maths on worker threads */
        void v20Tick() {
            long now = System.currentTimeMillis();
            if (now - dgAt > 300000L) flushDiag(now);
            if (mode == HudRenderer.STATIC && visible && now - avAt > 20000L) { avAt = now; autoView(); }
            if (sats == null) loadTle();
            if (visible && !spBusy && now - spAt > 15 * 60000L && now - spTry > 5 * 60000L && online()) fetchSpace(now - xrAt > 30 * 60000L);
            if (visible && !tleBusy && now - tleAt > 24 * 3600000L && now - tleTry > 3600000L && online()) fetchTle();
            boolean moved = !Double.isNaN(d.obsLat) && (Double.isNaN(astroLat) || Math.abs(d.obsLat - astroLat) > 0.05 || Math.abs(d.obsLon - astroLon) > 0.05);
            if (!astroBusy && (now - astroAt > 10 * 60000L || moved || satsAt > astroAt)) astroJob();
            // aurora verdict, recomputed every second from cached inputs
            d.cloud = Double.NaN;
            if (cT != null) for (int i = 0; i < cT.length; i++) if (now >= cT[i] && now < cT[i] + 3600000L) { d.cloud = cV[i]; break; }
            if (d.swUpd == 0 || now - d.swUpd > 3 * 3600000L) { d.aur = -1; d.aurWhy = null; }
            else if (!Double.isNaN(d.obsLat)) {
                double ml = SpaceWx.geomagLat(d.obsLat, d.obsLon);
                d.kpNeed = SpaceWx.kpNeeded(ml);
                double sa = Astro.pos(Astro.SUN, now, d.obsLat, d.obsLon)[0];
                String[] w = new String[1];
                d.aur = SpaceWx.aurora(d.kp, d.bz, sa, d.cloud, ml, w); d.aurWhy = w[0];
            }
        }

        void fetchSpace(final boolean xray) {
            spBusy = true; spTry = System.currentTimeMillis();
            new Thread(new Runnable() { public void run() {
                String kp = null, sc = null, mag = null, spd = null, xr = null;
                try { kp = SetWallpaper.get(new URL(SpaceWx.U_KP)); } catch (Throwable ignored) { }
                try { sc = SetWallpaper.get(new URL(SpaceWx.U_SCALES)); } catch (Throwable ignored) { }
                try { mag = SetWallpaper.get(new URL(SpaceWx.U_MAG)); } catch (Throwable ignored) { }
                try { spd = SetWallpaper.get(new URL(SpaceWx.U_SPEED)); } catch (Throwable ignored) { }
                if (xray) try { xr = SetWallpaper.get(new URL(SpaceWx.U_XRAY)); } catch (Throwable ignored) { }
                String fc = null;
                if (System.currentTimeMillis() - fcAt > 3 * 3600000L) try { fc = SetWallpaper.get(new URL(SpaceWx.U_KPFC)); } catch (Throwable ignored) { }
                double[][] fcp = null;
                try { if (fc != null) fcp = SpaceWx.kpForecast(fc); } catch (Throwable ignored) { }
                StringBuilder miss = new StringBuilder();
                if (kp == null) miss.append("KP "); if (sc == null) miss.append("SCALES "); if (mag == null) miss.append("MAG ");
                if (spd == null) miss.append("WIND "); if (xray && xr == null) miss.append("XRAY ");
                if (System.currentTimeMillis() - fcAt > 3 * 3600000L && fcp == null) miss.append("FORECAST ");
                diag("sw", miss.length() == 0 ? null : "failed: " + miss.toString().trim());
                final double[][] ffc = fcp;
                double[][] k = null; int[] scl = null; double bz = Double.NaN, v = Double.NaN; double[] x = null;
                try { if (kp != null) k = SpaceWx.kp(kp); } catch (Throwable ignored) { }
                try { if (sc != null) scl = SpaceWx.scales(sc); } catch (Throwable ignored) { }
                try { if (mag != null) bz = SpaceWx.latest(mag, "Bz", "bz_gsm"); } catch (Throwable ignored) { }
                try { if (spd != null) v = SpaceWx.latest(spd, "WindSpeed", "proton_speed", "speed"); } catch (Throwable ignored) { }
                try { if (xr != null) x = SpaceWx.xray(xr); } catch (Throwable ignored) { }
                final double[][] fk = k; final int[] fs = scl; final double fbz = bz, fv = v; final double[] fx = x;
                h.post(new Runnable() { public void run() {
                    spBusy = false;
                    long now = System.currentTimeMillis();
                    boolean any = false;
                    if (fk != null && fk[0].length > 0) { d.kpT = fk[0]; d.kpV = fk[1]; d.kp = fk[1][fk[1].length - 1]; any = true; }
                    if (fs != null && fs[2] >= 0) { d.scR = fs[0]; d.scS = fs[1]; d.scG = fs[2]; any = true; }
                    if (!Double.isNaN(fbz)) { d.bz = fbz; any = true; }
                    if (!Double.isNaN(fv)) { d.swSpeed = fv; any = true; }
                    if (fx != null && !Double.isNaN(fx[0])) { d.xrFlux = fx[0]; d.xrMax = fx[1]; d.xrMaxAt = (long) fx[2]; xrAt = now; }
                    if (ffc != null && ffc[0].length > 0) {
                        d.kpFcT = ffc[0]; d.kpFcV = ffc[1]; fcAt = now;
                        StringBuilder a = new StringBuilder();
                        for (int i = 0; i < ffc[0].length; i++) { if (i > 0) a.append(','); a.append((long) (ffc[0][i] / 1000)).append(':').append(Math.round(ffc[1][i] * 100)); }
                        getSharedPreferences("hud", Context.MODE_PRIVATE).edit().putString("kpFc", a.toString()).putLong("kpFcAt", now).apply();
                    }
                    if (any) { spAt = now; d.swUpd = now; saveSpace(); }
                    if (visible) frame();
                }});
            }}).start();
        }

        void saveSpace() {
            SharedPreferences.Editor e = getSharedPreferences("hud", Context.MODE_PRIVATE).edit();
            e.putLong("swUpd", d.swUpd).putFloat("kp", (float) d.kp).putFloat("bz", (float) d.bz).putFloat("swV", (float) d.swSpeed)
             .putFloat("xr", (float) d.xrFlux).putFloat("xrMax", (float) d.xrMax).putLong("xrMaxAt", d.xrMaxAt)
             .putInt("scR", d.scR).putInt("scS", d.scS).putInt("scG", d.scG);
            if (d.kpT != null) {
                StringBuilder b = new StringBuilder();
                int i0 = Math.max(0, d.kpT.length - 400);
                for (int i = i0; i < d.kpT.length; i++) b.append((long) (d.kpT[i] / 1000)).append(':').append(Math.round(d.kpV[i] * 100)).append(',');
                e.putString("kpSeries", b.toString());
            }
            e.apply();
        }

        long fcAt;

        void loadSpace() {
            SharedPreferences sp = getSharedPreferences("hud", Context.MODE_PRIVATE);
            String fcs = sp.getString("kpFc", null);
            if (fcs != null && fcs.length() > 0) {
                String[] p = fcs.split(",");
                double[] t = new double[p.length], v = new double[p.length]; int n = 0;
                for (String q : p) { int c = q.indexOf(':'); if (c <= 0) continue;
                    try { t[n] = Long.parseLong(q.substring(0, c)) * 1000.0; v[n] = Integer.parseInt(q.substring(c + 1)) / 100.0; n++; } catch (Exception ignored) { } }
                d.kpFcT = java.util.Arrays.copyOf(t, n); d.kpFcV = java.util.Arrays.copyOf(v, n);
                fcAt = sp.getLong("kpFcAt", 0);
            }
            d.swUpd = sp.getLong("swUpd", 0); spAt = d.swUpd; xrAt = d.swUpd;
            if (d.swUpd == 0) return;
            d.kp = sp.getFloat("kp", Float.NaN); d.bz = sp.getFloat("bz", Float.NaN); d.swSpeed = sp.getFloat("swV", Float.NaN);
            d.xrFlux = sp.getFloat("xr", Float.NaN); d.xrMax = sp.getFloat("xrMax", Float.NaN); d.xrMaxAt = sp.getLong("xrMaxAt", 0);
            d.scR = sp.getInt("scR", -1); d.scS = sp.getInt("scS", -1); d.scG = sp.getInt("scG", -1);
            String ks = sp.getString("kpSeries", null);
            if (ks != null && ks.length() > 0) {
                String[] p = ks.split(",");
                double[] t = new double[p.length], v = new double[p.length]; int n = 0;
                for (String q : p) {
                    int c = q.indexOf(':');
                    if (c <= 0) continue;
                    try { t[n] = Long.parseLong(q.substring(0, c)) * 1000.0; v[n] = Integer.parseInt(q.substring(c + 1)) / 100.0; n++; } catch (Exception ignored) { }
                }
                d.kpT = java.util.Arrays.copyOf(t, n); d.kpV = java.util.Arrays.copyOf(v, n);
            }
        }

        java.io.File tleFile() { return new java.io.File(getFilesDir(), "tle.txt"); }

        void loadTle() {
            sats = new java.util.ArrayList<Sgp4>();
            try {
                java.io.File fl = tleFile();
                if (!fl.exists()) { d.satState = "AWAITING CELESTRAK"; return; }
                byte[] b = new byte[(int) fl.length()];
                java.io.FileInputStream in = new java.io.FileInputStream(fl);
                int n = 0; while (n < b.length) { int k = in.read(b, n, b.length - n); if (k < 0) break; n += k; }
                in.close();
                sats = Sat.parseTle(new String(b, 0, n, "UTF-8"));
                tleAt = fl.lastModified(); satsAt = System.currentTimeMillis();
                d.satState = sats.isEmpty() ? "NO ORBIT DATA" : null;
            } catch (Exception e) { d.satState = "NO ORBIT DATA"; }
        }

        /** CelesTrak: space stations + last 30 days of launches, once a day (their guidance: hourly at most) */
        void fetchTle() {
            tleBusy = true; tleTry = System.currentTimeMillis();
            new Thread(new Runnable() { public void run() {
                String a = null, b = null;
                try { a = SetWallpaper.get(new URL(SpaceWx.U_TLE_STATIONS)); } catch (Throwable ignored) { }
                try { b = SetWallpaper.get(new URL(SpaceWx.U_TLE_RECENT)); } catch (Throwable ignored) { }
                String all = (a != null && a.contains("\n1 ") ? a : "") + "\n" + (b != null && b.contains("\n1 ") ? b : "");
                final java.util.List<Sgp4> L = Sat.parseTle(all);
                boolean ok = !L.isEmpty();
                diag("tle", ok ? (a == null || b == null ? "partial: " + (a == null ? "stations " : "") + (b == null ? "recent launches" : "") : null) : "failed");
                if (ok) {
                    try {
                        java.io.FileOutputStream o = new java.io.FileOutputStream(tleFile());
                        o.write(all.getBytes("UTF-8")); o.close();
                    } catch (Exception ignored) { }
                }
                final boolean fok = ok;
                h.post(new Runnable() { public void run() {
                    tleBusy = false;
                    if (fok) { sats = L; tleAt = System.currentTimeMillis(); satsAt = tleAt; d.satState = null; }
                }});
            }}).start();
        }

        /** twilight, lunar events, meteors, satellite passes; ~100 ms of maths on a worker thread */
        void astroJob() {
            if (Double.isNaN(d.obsLat)) return;
            astroBusy = true; astroAt = System.currentTimeMillis();
            final double lat = d.obsLat, lon = d.obsLon;
            astroLat = lat; astroLon = lon;
            final java.util.List<Sgp4> L = sats;
            new Thread(new Runnable() { public void run() {
                final long now = System.currentTimeMillis();
                Calendar c = Calendar.getInstance(); c.setTimeInMillis(now);
                c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
                long d0 = c.getTimeInMillis();
                final long[] tw0 = Astro.twilight(d0 - 86400000L, lat, lon), tw1 = Astro.twilight(d0, lat, lon);
                long best = Long.MAX_VALUE; int bk = -1;
                for (int q = 0; q < 4; q++) { long t = Astro.phaseEvent(now, q * 90); if (t > 0 && t < best) { best = t; bk = q; } }
                final long phT = best == Long.MAX_VALUE ? 0 : best; final int phK = bk;
                final double phDist = phT > 0 ? Astro.moonDistKm(phT) : Double.NaN;
                double[] pe = Astro.apsis(now, true), ap = Astro.apsis(now, false);
                final boolean peri = pe[0] > 0 && (ap[0] <= 0 || pe[0] < ap[0]);
                final double[] aps = peri ? pe : ap;
                final double[] met = Astro.meteors(now, lat, lon);
                final Sat.Pass[] P = new Sat.Pass[3]; final Sgp4[] PS = new Sgp4[3];
                if (L != null && !L.isEmpty()) {
                    try {
                        Sat.Grid G = new Sat.Grid(now - 15 * 60000L, 36 * 3600000L, lat, lon);
                        for (Sgp4 s : L) {
                            int k = s.norad == 25544 ? 0 : s.norad == 48274 ? 1 : -1;
                            if (k < 0 || PS[k] != null) continue;
                            PS[k] = s; P[k] = Sat.nextVisible(s, G);
                        }
                        int[] cnt = new int[64];
                        java.util.List<Sgp4> tr = Sat.trains(L, now, cnt);
                        for (int i = 0; i < tr.size(); i++) {
                            Sat.Pass p = Sat.nextVisible(tr.get(i), G);
                            if (p != null && (P[2] == null || p.visStart < P[2].visStart)) {
                                String id = tr.get(i).intl;
                                p.label = id.length() >= 5 ? id.substring(0, 2) + "-" + id.substring(2, 5) : id;
                                p.count = i < cnt.length ? cnt[i] : 0;
                                P[2] = p; PS[2] = tr.get(i);
                            }
                        }
                    } catch (Throwable ignored) { }
                }
                h.post(new Runnable() { public void run() {
                    astroBusy = false;
                    d.tw0 = tw0; d.tw1 = tw1; d.phT = phT; d.phK = phK; d.phDist = phDist;
                    d.apT = (long) aps[0]; d.apPeri = peri; d.apDist = aps[1];
                    d.met = met;
                    for (int i = 0; i < 3; i++) { d.pass[i] = P[i]; d.passSat[i] = PS[i]; }
                    if (visible) frame();
                }});
            }}).start();
        }

        String hhmm(long ms) {
            Calendar c = Calendar.getInstance(); c.setTimeInMillis(ms);
            int h = c.get(Calendar.HOUR_OF_DAY), m = c.get(Calendar.MINUTE);
            return d.h24 ? String.format(Locale.US, "%02d:%02d", h, m) : String.format(Locale.US, "%d:%02d %s", (h + 11) % 12 + 1, m, h < 12 ? "AM" : "PM");
        }

        String dur(long ms) {
            long m = Math.max(0, ms / 60000);
            return m >= 60 ? String.format(Locale.US, "%dH %02dM", m / 60, m % 60) : m + "M";
        }

        void computeLock() {
            long now = System.currentTimeMillis();
            if (pT != null && pV != null) {
                boolean snow = !Float.isNaN(d.wxTemp) && d.wxTemp <= 0;
                String r = "DRY NEXT 2H";
                for (int i = 0; i < pT.length; i++) {
                    if (pT[i] + 15 * 60000L < now) continue;
                    if (pT[i] > now + 2 * 3600000L) break;
                    if (pV[i] >= 0.1f) {
                        long m = (pT[i] - now) / 60000;
                        r = m <= 0 ? (snow ? "SNOW NOW" : "RAIN NOW") : (snow ? "SNOW IN " : "RAIN IN ") + m + " MIN";
                        break;
                    }
                }
                d.lkRain = r;
            } else d.lkRain = null;
            d.lkSun = null; d.lkSunLabel = null;
            if (sunR != null && sunS != null && sunR.length > 0 && sunS.length > 0) {
                if (sunR[0] > 0 && now < sunR[0]) { d.lkSunLabel = "SUNRISE " + hhmm(sunR[0]); d.lkSun = "IN " + dur(sunR[0] - now); }
                else if (sunS[0] > 0 && now < sunS[0]) { d.lkSunLabel = "SUNSET " + hhmm(sunS[0]); d.lkSun = dur(sunS[0] - now) + " OF DAYLIGHT"; }
                else if (sunR.length > 1 && sunR[1] > now) { d.lkSunLabel = "SUNRISE " + hhmm(sunR[1]); d.lkSun = "IN " + dur(sunR[1] - now); }
            }
        }

        String appName(String pkg) {
            if (pkg == null) return null;
            switch (pkg) {
                case "com.spotify.music": return "SPOTIFY";
                case "com.google.android.apps.youtube.music": return "YT MUSIC";
                case "com.google.android.youtube": return "YOUTUBE";
                case "com.sec.android.app.music": return "SAMSUNG MUSIC";
                case "deezer.android.app": return "DEEZER";
                case "com.aspiro.tidal": return "TIDAL";
                case "com.apple.android.music": return "APPLE MUSIC";
                case "com.soundcloud.android": return "SOUNDCLOUD";
                case "au.com.shiftyjelly.pocketcasts": return "POCKET CASTS";
                case "no.nrk.mobil.radio": return "NRK RADIO";
                case "com.audible.application": return "AUDIBLE";
                case "grit.storytel.app": return "STORYTEL";
            }
            int i = pkg.lastIndexOf('.');
            return (i >= 0 ? pkg.substring(i + 1) : pkg).toUpperCase(Locale.US);
        }

        void setViz(boolean want) {
            if (want && viz == null) {
                if (checkSelfPermission("android.permission.RECORD_AUDIO") != PackageManager.PERMISSION_GRANTED) {
                    d.specOk = false; d.specState = "GRANT AUDIO ACCESS IN APP"; return;
                }
                long now = SystemClock.elapsedRealtime();
                if (vizFailAt != 0 && now - vizFailAt < 30000) return;          // back off after a failure
                try {
                    Visualizer v = new Visualizer(0);                             // global output mix
                    int[] rg = Visualizer.getCaptureSizeRange();
                    int cs = Math.min(Math.max(512, rg[0]), rg[1]);
                    v.setCaptureSize(cs);
                    v.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED);
                    v.setEnabled(true);
                    viz = v; fft = new byte[cs]; d.specState = null;
                } catch (Throwable e) {
                    viz = null; vizFailAt = now; d.specOk = false; d.specState = "SPECTRUM BLOCKED BY SYSTEM";
                }
            } else if (!want && viz != null) {
                try { viz.setEnabled(false); viz.release(); } catch (Throwable ignored) { }
                viz = null; d.specOk = false;
                java.util.Arrays.fill(d.spec, 0f);
            }
        }

        void pollViz() {
            if (viz == null) return;
            try { if (viz.getFft(fft) != Visualizer.SUCCESS) return; } catch (Throwable e) { return; }
            int n = fft.length / 2, B = d.spec.length;
            for (int b = 0; b < B; b++) {
                int k0 = (int) Math.max(1, Math.pow(n - 1, b / (double) B));
                int k1 = (int) Math.max(k0 + 1, Math.pow(n - 1, (b + 1) / (double) B));
                float m = 0;
                for (int k = k0; k < k1 && k < n; k++) {
                    float re = fft[2 * k], im = fft[2 * k + 1];
                    float mg = (float) Math.sqrt(re * re + im * im);
                    if (mg > m) m = mg;
                }
                float v = (float) (Math.log1p(m * (1 + b * 0.12f)) / Math.log1p(128));
                v = v < 0 ? 0 : v > 1 ? 1 : v;
                d.spec[b] = Math.max(v, d.spec[b] * 0.82f);                    // fast attack, slow decay
            }
            d.specOk = true;
        }

        // ---- location for weather: passive-first, cached, rare low-power one-shot
        static final long FIX_MAX_AGE = 60 * 60000L, ACTIVE_MIN_GAP = 60 * 60000L, MOVE_REFRESH_GAP = 10 * 60000L;
        static final float MOVE_M = 5000f;
        LocationManager lm;
        boolean passiveOn, geoBusy, liveLoc;
        Location fix;
        long lastActiveReq, lastGeo;
        double wxLat = Double.NaN, wxLon = Double.NaN, geoLat = Double.NaN, geoLon = Double.NaN;
        String geoName;

        final LocationListener passive = new LocationListener() {
            public void onLocationChanged(Location l) { onFix(l); }
            public void onStatusChanged(String p, int s, Bundle b) { }
            public void onProviderEnabled(String p) { }
            public void onProviderDisabled(String p) { }
        };
        final LocationListener oneShot = new LocationListener() {
            public void onLocationChanged(Location l) { onFix(l); if (visible && mode == HudRenderer.STATIC) { refresh(); frame(); } }
            public void onStatusChanged(String p, int s, Bundle b) { }
            public void onProviderEnabled(String p) { }
            public void onProviderDisabled(String p) { }
        };

        boolean locPerm() {
            return checkSelfPermission("android.permission.ACCESS_COARSE_LOCATION") == PackageManager.PERMISSION_GRANTED;
        }

        void setPassive(boolean want) {
            if (lm == null) lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return;
            if (want && !passiveOn) {
                try {   // piggybacks on fixes other apps already requested: no radio/GPS cost of its own
                    lm.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 15 * 60000L, 1000f, passive, Looper.getMainLooper());
                    passiveOn = true;
                } catch (Throwable ignored) { }
            } else if (!want && passiveOn) {
                try { lm.removeUpdates(passive); } catch (Throwable ignored) { }
                passiveOn = false;
            }
        }

        void onFix(Location l) {
            if (l == null) return;
            if (fix == null || l.getTime() > fix.getTime()) fix = l;
            long now = System.currentTimeMillis();
            if (!Double.isNaN(wxLat)) {
                float[] r = new float[1];
                Location.distanceBetween(wxLat, wxLon, l.getLatitude(), l.getLongitude(), r);
                if (r[0] > MOVE_M && now - lastWxTry > MOVE_REFRESH_GAP) d.wxUpd = 0;   // moved: weather due now
            }
        }

        /** newest known fix; schedules a single low-power network fix only if everything is stale. */
        Location resolveLocation() {
            long now = System.currentTimeMillis();
            String[] provs = {LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, "fused"};
            for (String pv : provs) {
                try { Location l = lm.getLastKnownLocation(pv); if (l != null && (fix == null || l.getTime() > fix.getTime())) fix = l; }
                catch (Throwable ignored) { }
            }
            if (fix != null && now - fix.getTime() < FIX_MAX_AGE) return fix;
            if (visible && now - lastActiveReq > ACTIVE_MIN_GAP) {
                lastActiveReq = now;
                try {
                    if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                        lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, oneShot, Looper.getMainLooper());
                } catch (Throwable ignored) { }
            }
            return fix != null && now - fix.getTime() < 24 * FIX_MAX_AGE ? fix : null;   // stale but usable
        }

        void reverseGeocode(final double lat, final double lon) {
            if (geoBusy || !Geocoder.isPresent()) return;
            long now = System.currentTimeMillis();
            if (geoName != null && now - lastGeo < MOVE_REFRESH_GAP) return;
            geoBusy = true; lastGeo = now;
            new Thread(new Runnable() { public void run() {
                String nm = null;
                try {
                    List<Address> a = new Geocoder(HudWallpaper.this, Locale.US).getFromLocation(lat, lon, 1);
                    if (a != null && !a.isEmpty()) {
                        Address ad = a.get(0);
                        nm = ad.getLocality() != null ? ad.getLocality() : ad.getSubAdminArea() != null ? ad.getSubAdminArea() : ad.getAdminArea();
                    }
                } catch (Throwable ignored) { }
                final String fn = nm;
                h.post(new Runnable() { public void run() {
                    geoBusy = false;
                    if (fn != null) { geoName = fn.toUpperCase(Locale.US); geoLat = lat; geoLon = lon; }
                    if (visible && mode == HudRenderer.STATIC) frame();
                }});
            }}).start();
        }

        void prefs() {
            SharedPreferences sp = getSharedPreferences("hud", Context.MODE_PRIVATE);
            d.motionOn = sp.getBoolean("motion", true);
            d.lkSpec = sp.getBoolean("lockSpec", true);
            lockMotion = sp.getBoolean("lockMotion", true);
            cmdTap = sp.getBoolean("cmdTap", false);
            d.nightDim = sp.getBoolean("nightDim", true);
            d.nightDimAmt = Math.max(30, Math.min(70, sp.getInt("nightDimPct", 40))) / 100f;
            d.noDock = launcherMode || sp.getBoolean("noDock", false);
            d.widgetLabel = sp.getInt("wgId", -1) >= 0 ? sp.getString("wgLabel", "WIDGET") : null;
            loadShortcuts(sp);
            d.splitPages = sp.getBoolean("splitPages", true);
            for (int col = 0; col < 4; col++)
                for (int m = 0; m < HudRenderer.NMOD; m++) d.mods[col][m] = sp.getBoolean("mod_" + col + "_" + m, DEF_MODS[col][m]);
            d.calView = Math.max(0, Math.min(2, sp.getInt("calView", 0)));
            for (int v = 0; v < 4; v++) d.order[v] = HudRenderer.cleanOrder(ints(sp.getString("order_" + v, null)), HudRenderer.DEF_ORDER);
            d.lkOrder = HudRenderer.cleanOrder(ints(sp.getString("lkOrder", null)), HudRenderer.LK_DEF);
            for (int i = 0; i < d.lkOn.length; i++) d.lkOn[i] = sp.getBoolean("lkOn_" + i, HudRenderer.LK_ON_DEF[i]);
            d.lkClock = sp.getBoolean("lkClock", true); d.lkNight = sp.getBoolean("lkNight", true);
            float[] tsc = HudRenderer.textScales(sp.getInt("textScale", 0), sp.getInt("textScaleLock", -1), sp.getInt("textScaleDream", -1),
                    getResources().getConfiguration().fontScale);
            r.setTextScales(tsc[0], tsc[1]);
            d.lkKp = sp.getBoolean("lkKp", true); d.lkWind = sp.getBoolean("lkWind", true);
            d.lkTime = sp.getBoolean("lkTime", true); d.lkCharge = sp.getBoolean("lkCharge", true);
            vPage = Math.max(0, Math.min(2, sp.getInt("vPage", 0)));
            updatePaging();
            d.skyMode = sp.getInt("skyMode", 0);
            d.clockUtc = sp.getBoolean("clockUtc", false);
            d.dialMoon = sp.getBoolean("dialMoon", false);
            int mw = sp.getInt("motionWin", 15);
            if (mw != d.motionWin) { d.motionWin = mw; d.clearMotion(); }
            int today = dayKey(System.currentTimeMillis());
            d.unlocks = sp.getInt("unlDay", 0) == today ? sp.getInt("unlCount", 0) : 0;
            d.lastUnlock = sp.getLong("lastUnlock", 0);
            liveLoc = sp.getBoolean("liveloc", false) && locPerm();
            setPassive(liveLoc);
            double lat = Double.NaN, lon = Double.NaN;
            String key = null;
            if (liveLoc) {
                Location l = resolveLocation();
                if (l != null) {
                    lat = Math.round(l.getLatitude() * 100) / 100.0;       // ~1 km grid: privacy + stable cache key
                    lon = Math.round(l.getLongitude() * 100) / 100.0;
                    float[] r = new float[1];
                    if (!Double.isNaN(geoLat)) Location.distanceBetween(geoLat, geoLon, lat, lon, r);
                    if (geoName == null || r[0] > MOVE_M) reverseGeocode(lat, lon);
                    d.wxCity = (geoName != null ? geoName : String.format(Locale.US, "%.2f, %.2f", lat, lon)) + " · LIVE";
                    key = "live";
                }
            }
            if (key == null) {                                            // manual city fallback
                String city = sp.getString("city", null);
                if (city != null) {
                    try { lat = Double.parseDouble(sp.getString("lat", "")); lon = Double.parseDouble(sp.getString("lon", "")); key = city; }
                    catch (Exception ignored) { }
                }
                d.wxCity = key != null ? city : liveLoc ? "LOCATING" : null;
            }
            d.obsLat = key == null ? Double.NaN : lat; d.obsLon = key == null ? Double.NaN : lon;
            {
                String ls = key == null ? (liveLoc ? "LIVE · NO FIX YET" : "NONE") : "live".equals(key) ? "LIVE · " + d.wxCity.replace(" · LIVE", "") : "CITY · " + key;
                if (!Double.isNaN(d.obsLat) && (sp.getFloat("obsLat", Float.NaN) != (float) d.obsLat || sp.getFloat("obsLon", Float.NaN) != (float) d.obsLon))
                    sp.edit().putFloat("obsLat", (float) d.obsLat).putFloat("obsLon", (float) d.obsLon).apply();
                if (!ls.equals(sp.getString("dg_loc", null))) sp.edit().putString("dg_loc", ls).apply();
            }
            if (key == null) return;
            if (!key.equals(wxFor)) { d.wxTemp = Float.NaN; d.wxErr = false; d.wxUpd = 0; lastWxTry = 0; }
            long now = System.currentTimeMillis();
            boolean due = now - d.wxUpd > 30 * 60000L && now - lastWxTry > (d.wxErr ? 5 : 1) * 60000L;
            if (due && !wxBusy) {
                wxLat = lat; wxLon = lon;
                fetchWeather(lat, lon, key);
            }
        }

        String[] scPkg = new String[HudRenderer.Data.SCMAX];

        // ---------------------------------------------------------------- v31 helpers
        long frameNs, frames, dgAt, avAt; boolean avOverride; int avLast = -1;
        Paint redP;

        String errText(Throwable t) {
            String m = t.getMessage();
            return t.getClass().getSimpleName() + (m == null ? "" : ": " + (m.length() > 80 ? m.substring(0, 80) : m));
        }

        /** per-source status for the Diagnostics card: time of the attempt and null (ok) or an error */
        void diag(String src, String err) {
            SharedPreferences.Editor e = getSharedPreferences("hud", Context.MODE_PRIVATE).edit();
            long now = System.currentTimeMillis();
            e.putLong("dg_" + src + "_try", now);
            if (err == null) { e.putLong("dg_" + src + "_ok", now); e.remove("dg_" + src + "_err"); } else e.putString("dg_" + src + "_err", err);
            e.apply();
        }

        void flushDiag(long now) {
            if (dgAt > 0 && frames > 0) {
                float mins = Math.max(1, (now - dgAt) / 60000f);
                getSharedPreferences("hud", Context.MODE_PRIVATE).edit()
                        .putFloat("dg_frameMs", frameNs / 1e6f / frames).putFloat("dg_framesH", frames / mins * 60f).putLong("dg_perfAt", now).apply();
            }
            dgAt = now; frameNs = 0; frames = 0;
        }

        /** red night mode: 0 off, 1 at night (sun < -6 deg), 2 aurora GO in the dark, 3 always */
        boolean redNow() {
            int m = getSharedPreferences("hud", Context.MODE_PRIVATE).getInt("redMode", 0);
            if (m == 3) return true;
            if (m == 0 || Double.isNaN(d.obsLat)) return false;
            double sa = Astro.pos(Astro.SUN, System.currentTimeMillis(), d.obsLat, d.obsLon)[0];
            return m == 1 ? sa < -6 : d.aur == 2 && sa < -12;
        }

        Paint redPaint() {
            if (redP == null) {
                redP = new Paint();
                redP.setColorFilter(new android.graphics.ColorMatrixColorFilter(new android.graphics.ColorMatrix(new float[]{
                        0.42f, 0.82f, 0.16f, 0, 0,   0, 0.04f, 0, 0, 0,   0, 0, 0.02f, 0, 0,   0, 0, 0, 1, 0})));
            }
            return redP;
        }

        /** light tick through the vibrator (EFFECT_TICK / EFFECT_CLICK on Android 10+, 12 ms pulse before) */
        void haptic(boolean click) {
            if (!getSharedPreferences("hud", Context.MODE_PRIVATE).getBoolean("haptic", true)) return;
            try {
                android.os.Vibrator v = (android.os.Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                if (v == null || !v.hasVibrator()) return;
                if (Build.VERSION.SDK_INT >= 29) {
                    Class<?> ve = Class.forName("android.os.VibrationEffect");
                    Object eff = ve.getMethod("createPredefined", int.class).invoke(null, click ? 0 : 2);
                    android.os.Vibrator.class.getMethod("vibrate", ve).invoke(v, eff);
                } else v.vibrate(12);
            } catch (Throwable ignored) { }
        }

        /** switch the virtual tab by context; a manual tab tap wins until the screen goes off */
        void autoView() {
            if (avOverride || !d.splitPages || !d.virtualPages) return;
            SharedPreferences sp = getSharedPreferences("hud", Context.MODE_PRIVATE);
            int want = -1;
            if (want < 0 && sp.getBoolean("avHead", false) && headphones()) want = HudRenderer.MOD_MUSIC;
            if (want < 0 && sp.getBoolean("avCharge", false) && d.charging) want = HudRenderer.MOD_DEV;
            if (want < 0 && sp.getBoolean("avNight", false) && !Double.isNaN(d.obsLat)
                    && Astro.pos(Astro.SUN, System.currentTimeMillis(), d.obsLat, d.obsLon)[0] < -6) want = HudRenderer.MOD_SKY;
            if (want < 0 && sp.getBoolean("avMorning", false)) {
                int hr = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
                if (hr >= 5 && hr < 10) want = HudRenderer.MOD_CAL;
            }
            if (want < 0 || want == avLast) return;
            int[] vt = new int[3]; int nt = d.visibleTabs(vt);
            for (int i = 0; i < nt; i++) {
                if (d.mods[1 + vt[i]][want]) {
                    avLast = want;
                    if (i != vPage) {
                        vPage = i; sp.edit().putInt("vPage", vPage).apply();
                        pageAt = SystemClock.uptimeMillis(); updatePaging(); frame();
                    }
                    return;
                }
            }
        }

        boolean headphones() {
            try {
                AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                for (android.media.AudioDeviceInfo i : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                    int t = i.getType();
                    if (t == 8 /* A2DP */ || t == 3 /* WIRED_HEADSET */ || t == 4 /* WIRED_HEADPHONES */ || t == 22 /* USB_HEADSET */
                            || t == 26 /* BLE_HEADSET */ || t == 27 /* BLE_SPEAKER */) return true;
                }
            } catch (Throwable ignored) { }
            return false;
        }

        final Link.Listener linkL = new Link.Listener() { public void onLink() {
            updateMedia(); scBadges();
            if (visible) frame();
        } };

        void scBadges() {
            for (int i = 0; i < HudRenderer.Data.SCMAX; i++) {
                String p = scPkg[i];
                if (Shortcuts.DIAL.equals(p)) {
                    try { p = ((android.telecom.TelecomManager) getSystemService(Context.TELECOM_SERVICE)).getDefaultDialerPackage(); } catch (Throwable t) { p = null; }
                }
                d.scBadge[i] = Link.badge(p);
            }
        }

        void loadShortcuts(SharedPreferences sp) {
            String[][] e = Shortcuts.load(sp);
            d.scOn = sp.getBoolean("scOn", true);
            d.scN = e.length;
            for (int i = 0; i < HudRenderer.Data.SCMAX; i++) {
                scPkg[i] = i < e.length ? e[i][0] : null;
                d.scLabel[i] = i < e.length ? e[i][1] : null;
                d.scOk[i] = i < e.length && (Shortcuts.DIAL.equals(e[i][0]) || app(e[i][0]) != null);
            }
            scBadges();
        }

        int[] ints(String csv) {
            if (csv == null || csv.isEmpty()) return null;
            String[] p = csv.split(",");
            int[] o = new int[p.length]; int n = 0;
            for (String q : p) try { o[n++] = Integer.parseInt(q.trim()); } catch (Exception ignored) { }
            return java.util.Arrays.copyOf(o, n);
        }

        void fetchWeather(final double lat, final double lon, final String city) {
            wxBusy = true; lastWxTry = System.currentTimeMillis();
            new Thread(new Runnable() { public void run() {
                JSONObject js = null;
                String err = null;
                try {
                    js = new JSONObject(SetWallpaper.get(new URL(String.format(Locale.US,
                        "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                        + "&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m,pressure_msl"
                        + "&daily=temperature_2m_max,temperature_2m_min,sunrise,sunset&minutely_15=precipitation"
                        + "&hourly=cloud_cover_low,cloud_cover_mid,temperature_2m,precipitation_probability"
                        + "&wind_speed_unit=ms&timezone=auto&forecast_days=2", lat, lon))));
                } catch (Throwable t) { err = errText(t); }
                diag("wx", err);
                final JSONObject fj = js;
                h.post(new Runnable() { public void run() {
                    wxBusy = false;
                    try {
                        if (fj == null) throw new Exception();
                        JSONObject cur = fj.getJSONObject("current");
                        d.wxTemp = (float) cur.getDouble("temperature_2m");
                        d.wxRh = cur.optInt("relative_humidity_2m", -1);
                        d.wxCode = cur.optInt("weather_code", -1);
                        d.wxWind = (float) cur.optDouble("wind_speed_10m", Double.NaN);
                        wxMsl = (float) cur.optDouble("pressure_msl", Double.NaN);
                        JSONObject dl = fj.optJSONObject("daily");
                        if (dl != null) {
                            d.wxHi = (float) dl.getJSONArray("temperature_2m_max").getDouble(0);
                            d.wxLo = (float) dl.getJSONArray("temperature_2m_min").getDouble(0);
                        }
                        d.wxUpd = System.currentTimeMillis(); d.wxErr = false; wxFor = city;
                        parseExtras(fj); computeLock();
                    } catch (Exception e) { d.wxErr = true; wxFor = city; }
                    if (visible && mode == HudRenderer.STATIC) frame();
                }});
            }}).start();
        }

        /** next two calendar events within 48 h (visible calendars, not declined); timed events preferred */
        void calScan() {
            d.calN = 0;
            if (checkSelfPermission("android.permission.READ_CALENDAR") != PackageManager.PERMISSION_GRANTED) { d.calState = "GRANT IN APP"; return; }
            d.calState = null;
            long now = System.currentTimeMillis();
            android.database.Cursor cur = null;
            try {
                android.net.Uri.Builder b = android.provider.CalendarContract.Instances.CONTENT_URI.buildUpon();
                android.content.ContentUris.appendId(b, now - 24 * 3600000L);
                android.content.ContentUris.appendId(b, now + 48 * 3600000L);
                String[] proj = {"title", "begin", "end", "allDay"};
                cur = getContentResolver().query(b.build(), proj, "visible=1 AND selfAttendeeStatus!=2", null, "begin ASC");
                java.util.ArrayList<Object[]> timed = new java.util.ArrayList<Object[]>(), allDay = new java.util.ArrayList<Object[]>();
                while (cur != null && cur.moveToNext()) {
                    long bg = cur.getLong(1), en = cur.getLong(2);
                    boolean ad = cur.getInt(3) != 0;
                    if (ad) {                                   // all-day instances are stored in UTC midnights
                        java.util.TimeZone tz = java.util.TimeZone.getDefault();
                        bg -= tz.getOffset(bg); en -= tz.getOffset(en);
                    }
                    if (en <= now) continue;
                    Object[] o = {cur.getString(0), bg, en, ad};
                    if (ad) allDay.add(o); else timed.add(o);
                }
                timed.addAll(allDay);
                for (int i = 0; i < timed.size() && d.calN < 2; i++) {
                    Object[] o = timed.get(i);
                    d.calT[d.calN] = (String) o[0]; d.calB[d.calN] = (Long) o[1]; d.calE[d.calN] = (Long) o[2]; d.calAll[d.calN] = (Boolean) o[3];
                    d.calN++;
                }
            } catch (Throwable e) { d.calState = "CALENDAR UNAVAILABLE"; }
            finally { if (cur != null) cur.close(); }
            calRange();
        }

        /** all instances in the 6-week grid around this month, with calendar colours, sorted by start */
        void calRange() {
            d.evN = 0;
            Calendar m1 = Calendar.getInstance();
            m1.set(Calendar.DAY_OF_MONTH, 1); m1.set(Calendar.HOUR_OF_DAY, 0); m1.set(Calendar.MINUTE, 0); m1.set(Calendar.SECOND, 0); m1.set(Calendar.MILLISECOND, 0);
            m1.add(Calendar.DAY_OF_YEAR, -((m1.get(Calendar.DAY_OF_WEEK) + 5) % 7));
            long gs = m1.getTimeInMillis();
            m1.add(Calendar.DAY_OF_YEAR, 42);
            long ge = m1.getTimeInMillis();
            android.database.Cursor cur = null;
            try {
                android.net.Uri.Builder b = android.provider.CalendarContract.Instances.CONTENT_URI.buildUpon();
                android.content.ContentUris.appendId(b, gs - 86400000L);
                android.content.ContentUris.appendId(b, ge + 86400000L);
                cur = getContentResolver().query(b.build(), new String[]{"title", "begin", "end", "allDay", "displayColor", "event_id"},
                        "visible=1 AND selfAttendeeStatus!=2", null, "begin ASC");
                java.util.TimeZone tz = java.util.TimeZone.getDefault();
                while (cur != null && cur.moveToNext() && d.evN < HudRenderer.Data.EVMAX) {
                    long bg = cur.getLong(1), en = cur.getLong(2);
                    boolean ad = cur.getInt(3) != 0;
                    if (ad) { bg -= tz.getOffset(bg); en -= tz.getOffset(en); }       // all-day instances are UTC midnights
                    if (en <= gs || bg >= ge) continue;
                    int i = d.evN++;
                    d.evT[i] = cur.getString(0); d.evB[i] = bg; d.evE[i] = en; d.evAll[i] = ad;
                    d.evCol[i] = cur.isNull(4) ? 0 : cur.getInt(4);
                    d.evId[i] = cur.getLong(5);
                }
            } catch (Throwable ignored) { d.evN = 0; }
            finally { if (cur != null) cur.close(); }
        }

        void btScan() {
            d.btN = 0;
            java.util.Arrays.fill(d.btLvl2, -1);
            try {
                if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission("android.permission.BLUETOOTH_CONNECT") != PackageManager.PERMISSION_GRANTED) {
                    d.btState = "GRANT NEARBY DEVICES IN APP"; return;
                }
                BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
                if (ba == null) { d.btState = "NO BLUETOOTH"; return; }
                if (!ba.isEnabled()) { d.btState = "BLUETOOTH OFF"; return; }
                Method gl = null, ic = null;
                try { gl = BluetoothDevice.class.getMethod("getBatteryLevel"); } catch (Throwable ignored) { }
                try { ic = BluetoothDevice.class.getMethod("isConnected"); } catch (Throwable ignored) { }
                for (BluetoothDevice dev : ba.getBondedDevices()) {
                    if (d.btN >= 4) break;
                    boolean conn = false; int lvl = -1;
                    if (ic != null) try { conn = (Boolean) ic.invoke(dev); } catch (Throwable ignored) { }
                    if (gl != null) try { lvl = (Integer) gl.invoke(dev); } catch (Throwable ignored) { }
                    if (!conn && lvl < 0) continue;
                    String nm = dev.getName();
                    int same = -1;
                    for (int j = 0; j < d.btN; j++) if (nm != null && nm.equals(d.btName[j])) same = j;
                    if (same >= 0) { if (d.btLvl2[same] < 0) d.btLvl2[same] = lvl; continue; }   // L/R earbuds
                    d.btName[d.btN] = nm; d.btLvl[d.btN] = lvl; d.btN++;
                }
                d.btState = d.btN == 0 ? "NO DEVICES CONNECTED" : null;
            } catch (Throwable e) { d.btN = 0; d.btState = "BT UNAVAILABLE"; }
        }

        final Runnable tick = new Runnable() { public void run() { frame(); } };

        final BroadcastReceiver rx = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent i) {
                if (isPreview()) { if (Intent.ACTION_BATTERY_CHANGED.equals(i.getAction())) battery(i); return; }
                String a = i.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                    mode = HudRenderer.LOCKED; h.removeCallbacks(tick); avOverride = false; avLast = -1;
                } else if (Intent.ACTION_USER_PRESENT.equals(a)) {
                    countUnlock();
                    if (mode == HudRenderer.LOCKED) startUnlock();
                } else if (Intent.ACTION_BATTERY_CHANGED.equals(a)) {
                    if (battery(i) && mode == HudRenderer.STATIC && visible) frame();
                } else if (Intent.ACTION_TIME_TICK.equals(a) || PowerManager.ACTION_POWER_SAVE_MODE_CHANGED.equals(a)
                        || AudioManager.RINGER_MODE_CHANGED_ACTION.equals(a) || ConnectivityManager.CONNECTIVITY_ACTION.equals(a)
                        || NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED.equals(a)) {
                    if (mode == HudRenderer.STATIC && visible) { refresh(); frame(); }
                }
            }
        };

        @Override public void onCreate(SurfaceHolder sh) {
            setTouchEventsEnabled(true);
            if (!isPreview()) Alerts.ensureJob(HudWallpaper.this);
            Link.listen(linkL, true);
            if (Link.installed(HudWallpaper.this)) Link.send(HudWallpaper.this, "refresh", null, 0);
            try {
                java.io.InputStream in = getAssets().open("stars.bin");
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                in.close();
                r.setStars(bo.toByteArray());
            } catch (Exception ignored) { }
            try { r.setClockTypeface(android.graphics.Typeface.createFromAsset(getAssets(), "ShareTechMono-Regular.ttf")); }
            catch (Exception ignored) { }
            super.onCreate(sh);
            km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_SCREEN_OFF);
            f.addAction(Intent.ACTION_USER_PRESENT);
            f.addAction(Intent.ACTION_BATTERY_CHANGED);
            f.addAction(Intent.ACTION_TIME_TICK);
            f.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
            f.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION);
            f.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
            f.addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED);
            Intent sticky = registerReceiver(rx, f);   // system broadcasts only -> no export flag needed
            loadSpace();
            registered = true;
            setMediaRx(true);
            if (sticky != null) battery(sticky);
            refresh();
            if (!isPreview() && km != null && km.isKeyguardLocked()) mode = HudRenderer.LOCKED;
        }

        @Override public void onDestroy() {
            Link.listen(linkL, false);
            h.removeCallbacks(tick);
            setSensors(false);
            setViz(false);
            setPassive(false);
            setMediaRx(false);
            setLab(false);
            if (stepOn && sm != null) { try { sm.unregisterListener(stepL); } catch (Throwable ignored) { } stepOn = false; }
            if (registered) { try { unregisterReceiver(rx); } catch (Exception ignored) { } registered = false; }
            super.onDestroy();
        }

        boolean battery(Intent i) {
            int lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), sc = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            if (lvl < 0 || sc <= 0) return false;
            int st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            int pct = Math.round(100f * lvl / sc);
            boolean chg = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL;
            boolean changed = pct != d.battery || chg != d.charging;
            int t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
            d.tempC = t == Integer.MIN_VALUE ? Float.NaN : t / 10f;
            d.voltMv = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
            switch (i.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
                case BatteryManager.BATTERY_HEALTH_GOOD: d.health = "GOOD"; break;
                case BatteryManager.BATTERY_HEALTH_OVERHEAT: d.health = "OVERHEAT"; break;
                case BatteryManager.BATTERY_HEALTH_DEAD: d.health = "DEAD"; break;
                case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE: d.health = "OVERVOLT"; break;
                case BatteryManager.BATTERY_HEALTH_COLD: d.health = "COLD"; break;
                default: d.health = null;
            }
            int pl = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            d.plug = pl == BatteryManager.BATTERY_PLUGGED_AC ? "AC" : pl == BatteryManager.BATTERY_PLUGGED_USB ? "USB"
                    : pl == BatteryManager.BATTERY_PLUGGED_WIRELESS ? "WIRELESS" : null;
            d.battery = pct; d.charging = chg;
            return changed;
        }

        void refresh() {
            d.wallMs = System.currentTimeMillis();
            d.uptimeMs = SystemClock.elapsedRealtime();
            try {
                ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo(); am.getMemoryInfo(mi);
                d.ramAvail = mi.availMem; d.ramTotal = mi.totalMem;
            } catch (Exception ignored) { }
            try {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                d.saver = pm.isPowerSaveMode();
                d.thermal = Build.VERSION.SDK_INT >= 29 ? (Integer) PowerManager.class.getMethod("getCurrentThermalStatus").invoke(pm) : -1;
            } catch (Throwable ignored) { d.thermal = -1; }
            try {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                Network n = cm.getActiveNetwork();
                NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
                if (nc == null) { d.net = "OFFLINE"; d.vpn = false; d.wifiRssi = Integer.MIN_VALUE; d.linkMbps = -1; }
                else {
                    d.vpn = nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
                    d.linkMbps = nc.getLinkDownstreamBandwidthKbps() / 1000;
                    d.wifiRssi = Integer.MIN_VALUE;
                    if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && Build.VERSION.SDK_INT >= 29) {
                        try { int r = (Integer) NetworkCapabilities.class.getMethod("getSignalStrength").invoke(nc);
                              if (r != Integer.MIN_VALUE && r < 0) d.wifiRssi = r; } catch (Throwable ignored) { }
                    }
                    d.net = nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "WIFI"
                            : nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "CELL"
                            : nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? "ETH" : d.vpn ? "VPN" : "LINK";
                }
            } catch (Exception ignored) { d.net = null; }
            try {
                AudioManager au = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                int rm = au.getRingerMode();
                d.ringer = rm == AudioManager.RINGER_MODE_SILENT ? "SILENT" : rm == AudioManager.RINGER_MODE_VIBRATE ? "VIBRATE" : "RING";
            } catch (Exception ignored) { d.ringer = null; }
            try {
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                int fl = nm.getCurrentInterruptionFilter();
                d.dnd = fl != NotificationManager.INTERRUPTION_FILTER_ALL && fl != NotificationManager.INTERRUPTION_FILTER_UNKNOWN;
            } catch (Exception ignored) { d.dnd = false; }
            d.h24 = DateFormat.is24HourFormat(HudWallpaper.this);
            try {
                TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
                if (tm != null && tm.getSimState() == TelephonyManager.SIM_STATE_READY) {
                    d.carrier = tm.getNetworkOperatorName();
                    d.cellLvl = -1;
                    if (Build.VERSION.SDK_INT >= 28) {
                        Object ss = TelephonyManager.class.getMethod("getSignalStrength").invoke(tm);
                        if (ss != null) d.cellLvl = (Integer) ss.getClass().getMethod("getLevel").invoke(ss);
                    }
                } else { d.carrier = null; d.cellLvl = -1; }
            } catch (Throwable ignored) { }
            long trx = TrafficStats.getTotalRxBytes(), ttx = TrafficStats.getTotalTxBytes();
            d.totRx = trx == TrafficStats.UNSUPPORTED ? -1 : trx; d.totTx = ttx;
            long mrx = TrafficStats.getMobileRxBytes(), mtx = TrafficStats.getMobileTxBytes();
            d.mobRx = mrx == TrafficStats.UNSUPPORTED ? -1 : mrx; d.mobTx = mtx;
            btScan();
            ensureSteps();
            calScan();
            prefs();
            if (d.os == null) {
                String os = "ANDROID " + Build.VERSION.RELEASE;
                String oneui = oneUi();
                d.os = oneui != null ? os + " \u00B7 ONE UI " + oneui : os + " \u00B7 SDK " + Build.VERSION.SDK_INT;
                d.patch = Build.VERSION.SECURITY_PATCH;
                String soc = null;
                try { if (Build.VERSION.SDK_INT >= 31) soc = (String) Build.class.getField("SOC_MODEL").get(null); } catch (Throwable ignored) { }
                if (soc == null || soc.length() == 0 || "unknown".equalsIgnoreCase(soc)) soc = Build.HARDWARE;
                d.cpu = Runtime.getRuntime().availableProcessors() + " CORES \u00B7 " + (soc == null ? "?" : soc.toUpperCase(Locale.US));
            }
            d.model = Build.MODEL == null ? "UNKNOWN" : Build.MODEL.toUpperCase(Locale.US);
            try {
                StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
                d.free = fs.getAvailableBytes(); d.total = fs.getTotalBytes();
            } catch (Exception ignored) { }
            try {
                AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
                AlarmManager.AlarmClockInfo ai = am == null ? null : am.getNextAlarmClock();
                if (ai == null) { d.alarm = null; d.alarmAt = 0; }
                else {
                    d.alarmAt = ai.getTriggerTime();
                    Calendar c = Calendar.getInstance(); c.setTimeInMillis(ai.getTriggerTime());
                    d.alarm = String.format(Locale.US, "ALARM %ta %<tH:%<tM", c).toUpperCase(Locale.US);
                }
            } catch (Exception ignored) { }
        }

        /** Samsung exposes Build.VERSION.SEM_PLATFORM_INT (e.g. 170000 -> One UI 8.0). */
        String oneUi() {
            try {
                int v = Build.VERSION.class.getField("SEM_PLATFORM_INT").getInt(null);
                int x = v - 90000;
                if (x <= 0) return null;
                int major = x / 10000, minor = (x % 10000) / 100;
                return major + "." + minor;
            } catch (Throwable e) { return null; }
        }

        /** 1 Hz telemetry sample; cheap binder/file reads only. */
        void sample() {
            long now = SystemClock.elapsedRealtime();
            if (lastSample != 0 && now - lastSample < 900) return;
            float dt = lastSample == 0 ? 0f : (now - lastSample) / 1000f;
            if (dt > 5f) { d.clearHistory(); dt = 0f; }
            long rx = TrafficStats.getTotalRxBytes(), tx = TrafficStats.getTotalTxBytes();
            if (dt > 0 && rx != TrafficStats.UNSUPPORTED && rx >= lastRx && tx >= lastTx) {
                d.rxBps = (rx - lastRx) / dt; d.txBps = (tx - lastTx) / dt;
            } else { d.rxBps = 0; d.txBps = 0; }
            lastRx = rx; lastTx = tx; lastSample = now;
            try {
                BatteryManager bm = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);
                int cur = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);   // µA on spec, mA on some devices
                if (cur != Integer.MIN_VALUE && cur != 0 && d.voltMv > 0) {
                    long ua = Math.abs((long) cur);
                    if (ua < 20000) ua *= 1000;
                    d.pwrW = ua / 1e6f * d.voltMv / 1000f; d.pwrIn = d.charging;
                } else d.pwrW = Float.NaN;
            } catch (Throwable e) { d.pwrW = Float.NaN; }
            try {
                ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo(); am.getMemoryInfo(mi);
                d.ramAvail = mi.availMem; d.ramTotal = mi.totalMem;
                d.ramPct = mi.totalMem > 0 ? 100f * (1f - mi.availMem / (float) mi.totalMem) : 0f;
            } catch (Exception ignored) { }
            if (Build.VERSION.SDK_INT >= 30) {
                try {
                    PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                    float hr = (Float) PowerManager.class.getMethod("getThermalHeadroom", int.class).invoke(pm, 0);
                    if (!Float.isNaN(hr)) d.headroom = hr;      // NaN when polled too often -> keep last
                } catch (Throwable ignored) { }
            }
            d.push(Float.isNaN(d.pwrW) ? 0f : d.pwrW, d.rxBps, d.txBps, d.ramPct, Float.isNaN(d.tempC) ? 20f : d.tempC);
            updateMedia();
            computeLock();
            v20Tick();
            if (labOn) pressureTrend();
            d.fullInMs = -1;
            if (d.charging && Build.VERSION.SDK_INT >= 28) {
                try {
                    BatteryManager bm2 = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);
                    long t = (Long) BatteryManager.class.getMethod("computeChargeTimeRemaining").invoke(bm2);
                    d.fullInMs = t > 0 ? t : -1;
                } catch (Throwable ignored) { }
            }
        }

        void startUnlock() {
            mode = HudRenderer.UNLOCKING;
            unlockAt = SystemClock.uptimeMillis();
            refresh();
            sample();
            if (visible) frame();
        }

        @Override public void onVisibilityChanged(boolean v) {
            visible = v;
            if (!v && !isPreview()) HudBus.widget(null);
            if (v && zoom < 0.02f) drawerOpen = false;            // back on home after an app (launched from the drawer or not)
            if (!v) {
                h.removeCallbacks(tick);
                setSensors(false);
                setLab(false);
                setViz(false);
                if (mode == HudRenderer.UNLOCKING) mode = HudRenderer.STATIC;   // unlocked straight into an app
                return;
            }
            refresh();
            stillSince = 0; alertUntil = 0;
            if (!isPreview()) {
                boolean locked = km != null && km.isKeyguardLocked();
                if (locked) { if (mode != HudRenderer.UNLOCKING) mode = HudRenderer.LOCKED; }
                else if (mode == HudRenderer.LOCKED) { startUnlock(); return; }
            }
            frame();
        }

        @Override public Bundle onCommand(String action, int x, int y, int z, Bundle extras, boolean resultRequested) {
            // Earliest unlock signal where the platform sends it; USER_PRESENT and polling are the fallbacks.
            if (!isPreview() && "android.wallpaper.keyguardgoingaway".equals(action) && mode == HudRenderer.LOCKED) startUnlock();
            // TN-HUD launcher: it owns the touches and forwards them; while it is in front, raw touches are ignored
            if ("tnhud.launcher".equals(action)) {
                launcherMode = z == 1; launcherAt = System.currentTimeMillis(); d.launcherMode = launcherMode;
                SharedPreferences lsp = getSharedPreferences("hud", Context.MODE_PRIVATE);
                if (launcherMode) { drawerOpen = false; d.noDock = true; } else d.noDock = lsp.getBoolean("noDock", false);
                d.widgetLabel = lsp.getInt("wgId", -1) >= 0 ? lsp.getString("wgLabel", "WIDGET") : null;
                if (visible && mode == HudRenderer.STATIC) frame();
            }
            if ("tnhud.drawer".equals(action)) { launcherDrawer = z == 1; }
            if ("tnhud.tap".equals(action) && mode == HudRenderer.STATIC && !launcherDrawer) { launcherAt = System.currentTimeMillis(); onTap(x, y); }
            if ("tnhud.page".equals(action) && mode == HudRenderer.STATIC && d.virtualPages) {
                int[] vt = new int[3]; int nt = d.visibleTabs(vt);
                if (nt > 1) {
                    vPage = ((vPage + z) % nt + nt) % nt; avOverride = true;
                    getSharedPreferences("hud", Context.MODE_PRIVATE).edit().putInt("vPage", vPage).apply();
                    pageAt = SystemClock.uptimeMillis(); updatePaging(); haptic(false); frame();
                }
            }
            if ("android.wallpaper.tap".equals(action)) {
                if (!cmdTap) { cmdTap = true; getSharedPreferences("hud", Context.MODE_PRIVATE).edit().putBoolean("cmdTap", true).apply(); }
                if (mode == HudRenderer.STATIC) onTap(x, y);
            }
            return super.onCommand(action, x, y, z, extras, resultRequested);
        }

        long pageAt = -100000, tapAt = -100000, lastTapUp, pendAt, pendArg;
        int lastTapX, lastTapY, pendAct = -1;
        boolean cmdTap, launcherMode, launcherDrawer; long launcherAt;
        final boolean[][] DEF_MODS = {
            {true, true, true, true, true, false, false, false, false, false, false}, {true, true, true, false, false, false, true, false, false, true, false},
            {true, true, false, true, true, false, false, false, false, false, false}, {true, true, false, false, false, true, false, false, true, false, false}};
        int offPages = 1, offPage, vPage;
        float tdx, tdy; long tdt;

        /** paging source: launcher offsets if it reports more than one page, else tappable tabs */
        void updatePaging() {
            int nt = d.visibleTabs(new int[3]);
            d.pages = Math.max(1, nt);
            if (offPages > 1) { d.page = Math.min(offPage, d.pages - 1); d.virtualPages = false; }
            else { d.page = Math.min(vPage, d.pages - 1); d.virtualPages = true; }
        }

        /** raw touches reach the wallpaper on most launchers; used for the tab bar only (icons may sit over other panels) */
        /**
         * Android hands the wallpaper every touch on a window that shows it, and One UI's app drawer does,
         * so a tap on an app in the drawer would also hit the HUD underneath. There is no "drawer open" API,
         * so follow the gestures: swipe up on home opens the drawer; swipe down, back (edge swipe), the home
         * gesture (from the bottom edge), leaving and returning to home, or the launcher zooming the
         * wallpaper back to 0 close it.
         */
        boolean drawerOpen; float zoom;
        float drawerScroll;                                      // estimated scroll of the vertical app list, px (0 = top)
        float vy; long lastMoveT; float lastMoveY;

        public void onZoomChanged(float z) {                     // no @Override: not in the android-23 compile jar           // API 30+: launchers zoom the wallpaper for drawer / recents
            zoom = z;
            if (z < 0.02f && drawerByZoom) { drawerOpen = false; drawerByZoom = false; }
            else if (z >= 0.02f) { drawerOpen = true; drawerByZoom = true; }
        }
        boolean drawerByZoom;

        @Override public void onTouchEvent(android.view.MotionEvent e) {
            if (mode != HudRenderer.STATIC || launcherMode) return;
            int a = e.getActionMasked();
            if (a == android.view.MotionEvent.ACTION_DOWN) { tdx = e.getX(); tdy = e.getY(); tdt = e.getEventTime(); vy = 0; lastMoveT = tdt; lastMoveY = tdy; }
            else if (a == android.view.MotionEvent.ACTION_MOVE) {
                long t = e.getEventTime();
                if (t > lastMoveT) { vy = 0.6f * vy + 0.4f * (e.getY() - lastMoveY) * 1000f / (t - lastMoveT); lastMoveT = t; lastMoveY = e.getY(); }
            }
            else if (a == android.view.MotionEvent.ACTION_UP) {
                float dn = getResources().getDisplayMetrics().density, slop = 12 * dn;
                float dx = e.getX() - tdx, dy = e.getY() - tdy;
                int sw = getResources().getDisplayMetrics().widthPixels, sh = getResources().getDisplayMetrics().heightPixels;
                boolean vertical = Math.abs(dy) > 60 * dn && Math.abs(dy) > 1.5f * Math.abs(dx);
                if (tdy > sh - 48 * dn && dy < -40 * dn) { drawerOpen = false; return; }              // home gesture
                if ((tdx < 24 * dn || tdx > sw - 24 * dn) && Math.abs(dx) > 40 * dn) { drawerOpen = false; return; }   // back gesture
                // vertical app drawer: the first swipe up opens it; later swipes scroll the list. Swipe down only closes it
                // when the list is (estimated to be) at the top: drag distance plus a fling allowance (~0.35 s of release speed)
                if (vertical) {
                    float travel = Math.abs(dy) + (Math.signum(vy) == Math.signum(dy) ? Math.abs(vy) * 0.35f : 0);
                    if (dy < 0) {
                        if (!drawerOpen) { drawerOpen = true; drawerScroll = 0; }
                        else drawerScroll += travel;
                    } else if (drawerOpen && !drawerByZoom) {
                        if (drawerScroll <= 8 * dn) drawerOpen = false;
                        else drawerScroll = Math.max(0, drawerScroll - travel);
                    }
                    return;
                }
                if (drawerOpen || zoom >= 0.02f) return;                                                // taps belong to the drawer
                if (!getSharedPreferences("hud", Context.MODE_PRIVATE).getBoolean("hudTaps", true)) return;
                if (e.getEventTime() - tdt < 350 && Math.hypot(dx, dy) < slop) {
                    int hitId = r.hit(e.getX(), e.getY());
                    boolean tab = hitId >= HudRenderer.HIT_TAB0 && hitId < HudRenderer.HIT_TAB0 + 3;
                    // launcher tap signal seen -> raw touches only for tabs (icons may cover other targets);
                    // never seen (One UI) -> raw touches are the only way in, so use them for everything
                    if (tab || (!cmdTap && hitId != HudRenderer.HIT_NONE)) onTap((int) e.getX(), (int) e.getY());
                }
            }
        }

        /** taps on empty home-screen areas (launcher sends WallpaperManager.COMMAND_TAP) */
        void onTap(int x, int y) {
            if ((drawerOpen && !launcherMode) || zoom >= 0.02f || !getSharedPreferences("hud", Context.MODE_PRIVATE).getBoolean("hudTaps", true)) return;
            long nowT = SystemClock.uptimeMillis();
            // the same physical tap can arrive twice (raw touch + launcher command): same place, within 150 ms
            if (nowT - lastTapUp < 150 && Math.abs(x - lastTapX) < 40 && Math.abs(y - lastTapY) < 40) return;
            int hitId = r.hit(x, y);
            if (hitId == HudRenderer.HIT_NONE) return;
            lastTapUp = nowT; lastTapX = x; lastTapY = y;
            SharedPreferences sp0 = getSharedPreferences("hud", Context.MODE_PRIVATE);
            if (HudRenderer.systemAction(hitId)) {               // 0 = off, 1 = single tap, 2 = double tap
                int mode = sp0.getInt("sysTaps", 2);
                if (mode == 0) return;
                if (mode == 2 && !(pendAct == hitId && pendArg == r.hitArg && nowT - pendAt < 600)) {
                    pendAct = hitId; pendArg = r.hitArg; pendAt = nowT; haptic(false);
                    d.tapHintUntil = System.currentTimeMillis() + 600; d.tapHintY0 = r.hitY0;
                    frame();
                    h.postDelayed(new Runnable() { public void run() { if (visible) frame(); } }, 650);
                    return;
                }
                pendAct = -1; d.tapHintUntil = 0;
            }
            if (hitId == HudRenderer.A_WX && !sp0.getBoolean("wxTap", false)) {      // weather app off: tap flips to the 12 h strip
                boolean show = d.wallMs >= d.wxHourlyUntil;
                d.wxHourlyUntil = show ? System.currentTimeMillis() + 20000L : 0;
                haptic(false); frame();
                if (show) h.postDelayed(new Runnable() { public void run() { if (visible) frame(); } }, 20100);
                return;
            }
            if (hitId == HudRenderer.A_SKYOBJ) {
                int b = (int) r.hitArg;
                boolean same = d.skySel == b && System.currentTimeMillis() < d.skySelUntil;
                d.skySel = same ? -1 : b; d.skySelUntil = same ? 0 : System.currentTimeMillis() + 15000L;
                haptic(false); frame();
                if (!same) h.postDelayed(new Runnable() { public void run() { if (visible) frame(); } }, 15100);
                return;
            }
            haptic(hitId != HudRenderer.A_SKYMODE && hitId != HudRenderer.A_UTC && hitId != HudRenderer.HIT_DIAL
                    && hitId != HudRenderer.A_CALVIEW && hitId != HudRenderer.HIT_MOTION && !(hitId >= HudRenderer.HIT_TAB0 && hitId < HudRenderer.HIT_TAB0 + 3));
            SharedPreferences sp = getSharedPreferences("hud", Context.MODE_PRIVATE);
            SharedPreferences.Editor e = sp.edit();
            if (hitId >= HudRenderer.HIT_TAB0 && hitId < HudRenderer.HIT_TAB0 + 3) {
                int pg = hitId - HudRenderer.HIT_TAB0;
                avOverride = true;
                if (d.virtualPages && pg != vPage) { vPage = pg; e.putInt("vPage", vPage); pageAt = nowT; updatePaging(); }
            }
            else if (hitId == HudRenderer.A_SKYMODE) { d.skyMode = (int) r.hitArg; e.putInt("skyMode", d.skyMode); }
            else if (hitId == HudRenderer.A_UTC) { d.clockUtc = !d.clockUtc; e.putBoolean("clockUtc", d.clockUtc); }
            else if (hitId == HudRenderer.HIT_DIAL) { d.dialMoon = !d.dialMoon; e.putBoolean("dialMoon", d.dialMoon); }
            else if (hitId == HudRenderer.A_CALVIEW) { d.calView = (int) r.hitArg; e.putInt("calView", d.calView); }
            else if (hitId == HudRenderer.HIT_MOTION) { d.motionWin = d.motionWin == 15 ? 60 : 15; d.clearMotion(); e.putInt("motionWin", d.motionWin); }
            else launchFor(hitId, r.hitArg, sp);
            e.apply();
            tapAt = SystemClock.uptimeMillis();
            frame();
        }

        // ---- widget-style shortcuts
        boolean launch(Intent i) {
            if (i == null) return false;
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            try { startActivity(i); return true; } catch (Throwable t) { return false; }
        }

        Intent app(String pkg) {
            try { return pkg == null ? null : getPackageManager().getLaunchIntentForPackage(pkg); } catch (Throwable t) { return null; }
        }

        void launchFor(int act, long arg, SharedPreferences sp) {
            switch (act) {
                case HudRenderer.A_CLOCKAPP:
                    if (!launch(new Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS))) launch(app("com.sec.android.app.clockpackage"));
                    break;
                case HudRenderer.A_WX: {
                    String pk = sp.getString("wxApp", null);
                    if (launch(app(pk))) break;
                    launch(app("com.sec.android.daemonapp"));                                  // Samsung Weather; no web fallback
                    break;
                }
                case HudRenderer.A_BATT: launch(new Intent(Intent.ACTION_POWER_USAGE_SUMMARY)); break;
                case HudRenderer.A_STORAGE: launch(new Intent(android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS)); break;
                case HudRenderer.A_DEVCARE:
                    if (!launch(app("com.samsung.android.lool"))) launch(new Intent(android.provider.Settings.ACTION_DEVICE_INFO_SETTINGS));
                    break;
                case HudRenderer.A_BT: launch(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)); break;
                case HudRenderer.A_NET:
                    if (Build.VERSION.SDK_INT < 29 || !launch(new Intent("android.settings.panel.action.INTERNET_CONNECTIVITY")))
                        launch(new Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS));
                    break;
                case HudRenderer.A_EVENT: {
                    int i = (int) arg;
                    if (i < 0 || i >= d.evN) break;
                    Intent v = new Intent(Intent.ACTION_VIEW, android.content.ContentUris.withAppendedId(android.provider.CalendarContract.Events.CONTENT_URI, d.evId[i]));
                    v.putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, d.evB[i]);
                    v.putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, d.evE[i]);
                    launch(v);
                    break;
                }
                case HudRenderer.A_DAY:
                    launch(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("content://com.android.calendar/time/" + arg)));
                    break;
                case HudRenderer.A_NEWEVENT: {
                    long t = (System.currentTimeMillis() / 3600000L + 1) * 3600000L;      // next full hour
                    Intent n = new Intent(Intent.ACTION_INSERT).setData(android.provider.CalendarContract.Events.CONTENT_URI);
                    n.putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, t);
                    n.putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, t + 3600000L);
                    launch(n);
                    break;
                }
                case HudRenderer.A_SEARCH:
                    if (!launch(new Intent("android.search.action.GLOBAL_SEARCH"))) launch(app("com.google.android.googlequicksearchbox"));
                    break;
                case HudRenderer.A_VOICE:
                    if (!launch(new Intent("android.speech.action.WEB_SEARCH"))) launch(new Intent("android.intent.action.VOICE_COMMAND"));
                    break;
                case HudRenderer.A_SHORTCUT: {
                    int i = (int) arg;
                    if (i < 0 || i >= scPkg.length || scPkg[i] == null) break;
                    if (Shortcuts.DIAL.equals(scPkg[i])) { if (!launch(new Intent(Intent.ACTION_DIAL))) launch(app("com.samsung.android.dialer")); }
                    else launch(app(scPkg[i]));
                    break;
                }
                case HudRenderer.A_MEMCLEAN:                     // only Device care can actually clear memory (Android 14+)
                    if (!launch(new Intent("com.samsung.android.sm.ACTION_RAM")) && !launch(app("com.samsung.android.lool")))
                        launch(new Intent(android.provider.Settings.ACTION_DEVICE_INFO_SETTINGS));
                    break;
                case HudRenderer.A_MEDIA_PREV:
                    if (Link.mediaActive()) Link.send(HudWallpaper.this, "media", "prev", 0); else mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS);
                    break;
                case HudRenderer.A_MEDIA_PLAY:
                    if (Link.mediaActive()) Link.send(HudWallpaper.this, "media", "toggle", 0); else mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
                    d.mPlaying = !d.mPlaying; break;
                case HudRenderer.A_MEDIA_NEXT:
                    if (Link.mediaActive()) Link.send(HudWallpaper.this, "media", "next", 0); else mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_NEXT);
                    break;
                case HudRenderer.A_MUSIC_APP:
                    if (Link.mPkg != null && Link.live() && launch(app(Link.mPkg))) break;
                    if (!("SPOTIFY".equals(d.mApp) && launch(app("com.spotify.music")))) launch(app("com.sec.android.app.music"));
                    break;
                case HudRenderer.A_NOTE_NEW: shareTo("com.samsung.android.app.notes"); break;
                case HudRenderer.A_KEEP_NEW: shareTo("com.google.android.keep"); break;
                case HudRenderer.A_NOTES_APP: launch(app("com.samsung.android.app.notes")); break;
                case HudRenderer.A_KEEP_APP: launch(app("com.google.android.keep")); break;
            }
        }

        /** media keys go to whichever session is active; no permission needed */
        void mediaKey(int code) {
            try {
                AudioManager au = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                au.dispatchMediaKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code));
                au.dispatchMediaKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code));
            } catch (Throwable ignored) { }
        }

        /** "new note": an empty text share opens the editor in Samsung Notes / Keep */
        void shareTo(String pkg) {
            Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").setPackage(pkg).putExtra(Intent.EXTRA_TEXT, "");
            if (!launch(i)) launch(app(pkg));
        }

        /** One UI / Launcher3 report the home page through wallpaper offsets; no step = single page or unsupported */
        @Override public void onOffsetsChanged(float xo, float yo, float xs, float ys, int xp, int yp) {
            int pages = xs > 0 && xs < 1 ? Math.round(1f / xs) + 1 : 1;
            int pg = pages > 1 ? Math.round(xo / xs) : 0;
            if (pages != offPages || pg != offPage) {
                boolean changed = pages > 1 && offPages > 1 && pg != offPage;
                offPages = pages; offPage = pg;
                updatePaging();
                if (changed) pageAt = SystemClock.uptimeMillis();
                if (visible && mode == HudRenderer.STATIC) frame();
            }
        }

        @Override public void onSurfaceChanged(SurfaceHolder sh, int format, int w, int hgt) {
            super.onSurfaceChanged(sh, format, w, hgt);
            r.setSize(w, hgt); ready = true; frame();
        }

        @Override public void onSurfaceRedrawNeeded(SurfaceHolder sh) { frame(); }

        @Override public void onSurfaceDestroyed(SurfaceHolder sh) {
            ready = false; h.removeCallbacks(tick);
            super.onSurfaceDestroyed(sh);
        }

        void frame() {
            h.removeCallbacks(tick);
            if (!ready) return;
            long now = SystemClock.uptimeMillis();
            d.wallMs = System.currentTimeMillis();
            int m; float u = 0; long delay = -1;
            if (isPreview()) {
                long k = (now - t0) % 6500;
                if (k < 2400) { m = HudRenderer.LOCKED; delay = 50; }
                else if (k < 2400 + HudRenderer.UNLOCK_MS) { m = HudRenderer.UNLOCKING; u = k - 2400; delay = 16; }
                else { m = HudRenderer.STATIC; sample(); delay = Math.min(live(now), 6500 - k); }
            } else {
                if (mode == HudRenderer.LOCKED && visible && km != null && !km.isKeyguardLocked()) { mode = HudRenderer.UNLOCKING; unlockAt = now; }
                if (mode == HudRenderer.UNLOCKING && now - unlockAt >= HudRenderer.UNLOCK_MS) mode = HudRenderer.STATIC;
                m = mode; u = now - unlockAt;
                if (m == HudRenderer.STATIC || m == HudRenderer.LOCKED) sample();
                delay = m == HudRenderer.LOCKED ? lockLive(now) : m == HudRenderer.UNLOCKING ? 16 : live(now);
            }
            d.pageAnim = Math.min(1f, (now - pageAt) / 450f);
            d.tapAnim = Math.min(1f, (now - tapAt) / 250f);
            if (m == HudRenderer.STATIC && (d.pageAnim < 1f || d.tapAnim < 1f)) delay = 16;        // 60 fps only during transitions
            if ((m != HudRenderer.STATIC && m != HudRenderer.LOCKED) || !visible) { setSensors(false); setViz(false); }
            if (m != HudRenderer.STATIC || !visible) setLab(false);
            render(m, now - t0, u);
            if (visible && delay >= 0) h.postDelayed(tick, delay);
        }

        /** home-screen live mode: motion at 4 Hz if enabled, otherwise 1 Hz (clock seconds + telemetry). */
        long live(long now) {
            setSensors(visible && d.motionOn && d.labShown);
            setLab(visible && d.motionOn && d.labShown);
            labCompute();
            if (sensorsOn && now - lastMotion >= (d.motionWin == 60 ? 950 : 200)) {
                d.pushMotion(lastA[0], lastA[1], lastA[2], lastG[0], lastG[1], lastG[2]);
                lastMotion = now;
            }
            boolean playing = d.mActive && d.mPlaying;
            d.mPos = playing && mPosAt > 0 ? mPosBase + (long) ((SystemClock.elapsedRealtime() - mPosAt) * mSpeed) : mPosBase;
            return sensorsOn ? 250 : 1000;
        }

        void render(int m, long clock, float u) {
            SurfaceHolder sh = getSurfaceHolder();
            Surface sf = sh.getSurface();
            Canvas c = null;
            boolean usedHw = false;
            if (hw) {
                try { c = sf.lockHardwareCanvas(); usedHw = c != null; }
                catch (Throwable e) { hw = false; c = null; }
            }
            if (c == null) { try { c = sh.lockCanvas(); } catch (Throwable e) { c = null; } }
            if (c == null) return;
            long ft = System.nanoTime();
            boolean red = redNow();
            try {
                if (red) c.saveLayer(null, redPaint());
                r.draw(c, m, clock, u, d);
                if (red) c.restore();
                boolean show = m == HudRenderer.STATIC && r.wgShown && launcherMode && !isPreview();
                HudBus.widget(show ? new int[]{r.wgL, r.wgT, r.wgR, r.wgB} : null);
            }
            finally {
                frameNs += System.nanoTime() - ft; frames++;
                try { if (usedHw) sf.unlockCanvasAndPost(c); else sh.unlockCanvasAndPost(c); }
                catch (Throwable ignored) { }
            }
        }
    }
}
