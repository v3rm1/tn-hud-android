package preview;
import android.graphics.Canvas;
import com.tokyonight.hud.live.HudRenderer;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.File;
public class Preview {
  public static void main(String[] a) throws Exception {
    int w = Integer.parseInt(a[1]), h = Integer.parseInt(a[2]);
    String out = a[0];
    HudRenderer r = new HudRenderer(); r.setSize(w, h);
    r.setClockTypeface(android.graphics.Typeface.createFromFile("app/assets/ShareTechMono-Regular.ttf"));
    r.setStars(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("app/assets/stars.bin")));
    HudRenderer.Data d = new HudRenderer.Data();
    d.alarm = "ALARM TUE 07:30"; d.battery = 78;
    d.wallMs = 1793727000000L; // fixed date for reproducibility
    d.tempC = 31.4f; d.voltMv = 4128; d.health = "GOOD"; d.plug = null;
    d.ramAvail = 4_600_000_000L; d.ramTotal = 11_800_000_000L; d.thermal = 0;
    d.net = "WIFI"; d.vpn = true; d.ringer = "VIBRATE"; d.dnd = false; d.saver = false;
    d.uptimeMs = 3L * 86400000 + 4 * 3600000 + 12 * 60000;
    d.os = "ANDROID 16 \u00B7 ONE UI 8.5"; d.patch = "2026-10-01"; d.cpu = "8 CORES \u00B7 SM8750";
    d.wxCity = "OSLO"; d.wxTemp = 4f; d.wxCode = 61; d.wxHi = 6f; d.wxLo = 1f; d.wxWind = 5f; d.wxRh = 87; d.wxUpd = d.wallMs - 8 * 60000;
    d.btState = null; d.btN = 2; d.btName[0] = "Galaxy Buds3 Pro"; d.btLvl[0] = 72; d.btName[1] = "MX Keys"; d.btLvl[1] = 18;
    d.wifiRssi = -58; d.linkMbps = 240; d.carrier = "Telenor"; d.cellLvl = 3;
    d.totRx = 2_480_000_000L; d.totTx = 325_000_000L; d.mobRx = 126_000_000L; d.mobTx = 12_400_000L;
    java.util.Random R = new java.util.Random(5);
    for (int i = 0; i < 60; i++) {
      float pw = 1.2f + 0.6f * (float) Math.sin(i / 6.0) + R.nextFloat() * 0.5f + (i > 40 && i < 46 ? 2.2f : 0);
      float rx = (i > 20 && i < 34 ? 900_000f + R.nextFloat() * 600_000f : R.nextFloat() * 40_000f);
      float tx = R.nextFloat() * 25_000f + (i > 22 && i < 30 ? 120_000f : 0);
      d.push(pw, rx, tx, 58 + 4 * (float) Math.sin(i / 9.0) + R.nextFloat(), 31f + i * 0.02f);
      float sh = (i > 30 && i < 38) ? 1 : 0;
      d.pushMotion(0.3f + sh * (R.nextFloat() * 6 - 3), 4.8f + sh * (R.nextFloat() * 5 - 2.5f), 8.5f + sh * (R.nextFloat() * 4 - 2) + R.nextFloat() * 0.2f,
                   sh * (R.nextFloat() * 3 - 1.5f) + R.nextFloat() * 0.05f, sh * (R.nextFloat() * 2 - 1), R.nextFloat() * 0.05f + sh * (R.nextFloat() - 0.5f));
    }
    d.pwrW = 1.84f; d.rxBps = 18_000f; d.txBps = 6_000f; d.ramPct = 61f; d.headroom = 0.38f;
    d.mActive = true; d.mPlaying = true; d.mTitle = "Midnight Transit (Extended Mix)"; d.mArtist = "Neon Harbor"; d.mApp = "SPOTIFY";
    d.mPos = 83_000; d.mDur = 245_000; d.specOk = true;
    d.btN = 1; d.btName[0] = "Varun's Buds3 Pro"; d.btLvl[0] = 38; d.btLvl2[0] = 46;
    d.alarmAt = d.wallMs + 3 * 3600000L + 31 * 60000L; d.lkRain = "RAIN IN 40 MIN"; d.lkSunLabel = "SUNSET 15:58"; d.lkSun = "1H 12M OF DAYLIGHT";
    d.steps = 6412; d.unlocks = 37; d.lastUnlock = d.wallMs - 12 * 60000L; d.charging = true; d.plug = "USB"; d.fullInMs = 42 * 60000L;
    d.wxCity = "SANDEFJORD \u00B7 LIVE";
    d.calN = 2; d.calT[0] = "Dentist"; d.calB[0] = d.wallMs + 34 * 60000L; d.calE[0] = d.calB[0] + 3600000L;
    d.calT[1] = "Dinner with Sara"; d.calB[1] = d.wallMs + 150 * 60000L; d.calE[1] = d.calB[1] + 7200000L;
    d.obsLat = 59.13; d.obsLon = 10.22;
    {   // sample calendar: this month, colours like Samsung/Google calendars
      java.util.Calendar t0 = java.util.Calendar.getInstance(); t0.setTimeInMillis(d.wallMs);
      t0.set(java.util.Calendar.HOUR_OF_DAY, 0); t0.set(java.util.Calendar.MINUTE, 0); t0.set(java.util.Calendar.SECOND, 0); t0.set(java.util.Calendar.MILLISECOND, 0);
      long day = t0.getTimeInMillis(), H = 3600000L;
      Object[][] ev = {
        {"Recycling pickup", 0, 0.0, 24.0, true, 0xFF9ECE6A}, {"Standup", 0, 8.5, 9.0, false, 0xFF7AA2F7},
        {"Design review", 0, 13.0, 14.5, false, 0xFFBB9AF7}, {"Gym", 0, 17.75, 19.0, false, 0xFFFF9E64},
        {"Call with Sara", 0, 20.0, 20.75, false, 0xFF7DCFFF}, {"Dentist", 1, 10.0, 11.0, false, 0xFFF7768E},
        {"Sprint planning", 2, 9.0, 11.0, false, 0xFF7AA2F7}, {"Padel", 2, 18.0, 19.5, false, 0xFFFF9E64},
        {"Flight OSL-BER", 3, 7.0, 9.0, false, 0xFFE0AF68}, {"Conference", 3, 0.0, 48.0, true, 0xFFBB9AF7},
        {"Standup", -1, 8.5, 9.0, false, 0xFF7AA2F7}, {"1:1", -2, 15.0, 15.5, false, 0xFF7AA2F7},
        {"Dinner", 5, 19.0, 21.0, false, 0xFF7DCFFF}, {"Hike", 9, 9.0, 14.0, false, 0xFF9ECE6A},
        {"Release", 12, 0.0, 24.0, true, 0xFFF7768E}, {"Concert", 16, 20.0, 23.0, false, 0xFFBB9AF7},
        {"Review", 19, 10.0, 11.0, false, 0xFF7AA2F7}, {"Trip", 22, 0.0, 72.0, true, 0xFFE0AF68}, {"Late gig", 1, 22.0, 24.5, false, 0xFFBB9AF7}};
      for (Object[] e : ev) {
        int i = d.evN++;
        d.evT[i] = (String) e[0]; d.evB[i] = day + (Integer) e[1] * 24 * H + (long) ((Double) e[2] * H);
        d.evE[i] = day + (Integer) e[1] * 24 * H + (long) ((Double) e[3] * H); d.evAll[i] = (Boolean) e[4]; d.evCol[i] = (Integer) e[5]; d.evId[i] = 1000 + i;
      }
    } d.heading = 214f; d.headTrue = true; d.fieldUT = 48.6f; d.hasMag = true;
    d.hPa = 1012.4f; d.hPaTrend = -1.2f; d.altM = 38f; d.altQnh = true; d.lux = 120f; d.pitch = 12f; d.roll = -3f;
    for (int b = 0; b < 32; b++) d.spec[b] = (float) Math.max(0.05, Math.min(1, 0.9 * Math.exp(-b / 14.0) + 0.25 * Math.sin(b * 0.9) * Math.sin(b * 0.9) + R.nextFloat() * 0.15));
    v20(d);
    int n = 0;
    // lock idle: 1.2 s @ 20 fps, include one idle glitch frame
    for (long t = 6900; t < 8100; t += 50) n = shot(r, d, out, n, w, h, HudRenderer.LOCKED, t, 0);
    for (float u = 0; u <= HudRenderer.UNLOCK_MS; u += 33) n = shot(r, d, out, n, w, h, HudRenderer.UNLOCKING, 8100 + (long) u, u);
    for (int i = 0; i < 20; i++) n = shot(r, d, out, n, w, h, HudRenderer.STATIC, 0, 0);
    // stills
    still(r, d, out + "/still_lock.png", w, h, HudRenderer.LOCKED, 3000, 0);
    still(r, d, out + "/still_matrix.png", w, h, HudRenderer.UNLOCKING, 9000, 560);
    still(r, d, out + "/still_granted.png", w, h, HudRenderer.UNLOCKING, 9000, 900);
    still(r, d, out + "/still_wipe.png", w, h, HudRenderer.UNLOCKING, 9000, 1200);
    still(r, d, out + "/still_home.png", w, h, HudRenderer.STATIC, 0, 0);
    d.nightDim = false; still(r, d, out + "/hdr_day.png", w, h, HudRenderer.STATIC, 0, 0); d.nightDim = true;
    {   // sample tasks + media for tab previews
      d.mActive = true; d.mPlaying = true; d.mTitle = "Midnight Transit"; d.mArtist = "Neon Harbor"; d.mApp = "SAMSUNG MUSIC";
    }
    d.nightDim = false; d.splitPages = true; d.virtualPages = true; d.page = 0; still(r, d, out + "/tab1_v18.png", w, h, HudRenderer.STATIC, 0, 0);
    d.page = 2; d.calView = 0; still(r, d, out + "/tab3_v18.png", w, h, HudRenderer.STATIC, 0, 0);
    d.nightDim = false; d.splitPages = false; d.mods[0][6] = true; d.mods[0][4] = false; still(r, d, out + "/single_search.png", w, h, HudRenderer.STATIC, 0, 0); d.mods[0][6] = false; d.mods[0][4] = true;
    d.nightDim = false; d.splitPages = true; d.virtualPages = true; d.page = 2;
    d.calView = 0; still(r, d, out + "/cal_day.png", w, h, HudRenderer.STATIC, 0, 0);
    d.calView = 1; still(r, d, out + "/cal_week.png", w, h, HudRenderer.STATIC, 0, 0);
    d.calView = 2; still(r, d, out + "/cal_month.png", w, h, HudRenderer.STATIC, 0, 0);
    d.calView = 0; d.splitPages = false; d.mods[0][5] = true; still(r, d, out + "/single_all.png", w, h, HudRenderer.STATIC, 0, 0);
    d.mods[0][2] = false; d.mods[0][4] = false; still(r, d, out + "/single_custom.png", w, h, HudRenderer.STATIC, 0, 0);
    d.mods[0][2] = true; d.mods[0][4] = true; d.mods[0][5] = false; d.nightDim = true;
    d.pages = 3; d.virtualPages = true; d.page = 0; still(r, d, out + "/pg_sys.png", w, h, HudRenderer.STATIC, 0, 0);
    d.page = 1; still(r, d, out + "/pg_sky.png", w, h, HudRenderer.STATIC, 0, 0);
    d.page = 2; still(r, d, out + "/pg_min.png", w, h, HudRenderer.STATIC, 0, 0);
    d.page = 1; d.pageAnim = 0.12f; still(r, d, out + "/pg_trans.png", w, h, HudRenderer.STATIC, 0, 0); d.pageAnim = 1f;
    d.skyMode = 1; d.clockUtc = true; d.dialMoon = true; d.motionWin = 60; still(r, d, out + "/pg_sky_alt.png", w, h, HudRenderer.STATIC, 0, 0);
    d.skyMode = 0; d.clockUtc = false; d.dialMoon = false; d.motionWin = 15; d.pages = 1; d.page = 0;
    still(r, d, out + "/lock_v20.png", w, h, HudRenderer.LOCKED, 3000, 0);
    { long keep = d.wallMs; com.tokyonight.hud.live.Sat.Pass ps = d.pass[0];
      if (ps != null) { d.wallMs = (ps.visStart + ps.visEnd) / 2; still(r, d, out + "/lock_pass.png", w, h, HudRenderer.LOCKED, 3000, 0); }
      d.wallMs = keep; int g0 = d.scG; double xf = d.xrFlux; d.scG = 3; d.xrFlux = 3.1e-5; d.kp = 7.33; d.aur = 2; d.aurWhy = "LOOK NORTH";
      still(r, d, out + "/lock_storm.png", w, h, HudRenderer.LOCKED, 3000, 0); d.scG = g0; d.xrFlux = xf; }
    { boolean sp0 = d.splitPages; d.splitPages = false; d.nightDim = false; d.noDock = true; d.net = "WIFI";
      still(r, d, out + "/nodock_home.png", w, h, HudRenderer.STATIC, 0, 0); d.noDock = false; d.splitPages = sp0; }
    { boolean sp0 = d.splitPages; d.splitPages = false; d.nightDim = false; d.noDock = true; d.net = "WIFI";
      d.skySel = 3; d.skySelUntil = d.wallMs + 60000; d.wxHourlyUntil = d.wallMs + 60000;
      still(r, d, out + "/v31_home.png", w, h, HudRenderer.STATIC, 0, 0);
      d.skySel = -1; d.wxHourlyUntil = 0; d.noDock = false; d.splitPages = sp0; }
    still(r, d, out + "/v31_lock.png", w, h, HudRenderer.LOCKED, 3000, 0);
    r.setTextScale(1.25f); still(r, d, out + "/ts125_lock.png", w, h, HudRenderer.LOCKED, 3000, 0);
    d.nightDim = false; d.splitPages = false; still(r, d, out + "/ts125_home.png", w, h, HudRenderer.STATIC, 0, 0); r.setTextScale(1f); d.splitPages = true;
    d.alertLeft = 2000; still(r, d, out + "/still_lock_alert.png", w, h, HudRenderer.LOCKED, 3060, 0); d.alertLeft = 0;
    d.charging = true; d.battery = 14; d.alarm = null; d.plug = "USB"; d.pwrIn = true; d.pwrW = 9.4f; d.btN = 0; d.btState = "GRANT NEARBY DEVICES IN APP"; d.wxTemp = Float.NaN; d.wxErr = true; d.motionOn = false; d.h24 = false; d.mPlaying = false; d.mAccess = false; d.mTitle = null; d.mArtist = null; d.mApp = null; d.specOk = false; d.specState = "GRANT AUDIO ACCESS IN APP"; d.mDur = 0; d.thermal = 2; d.tempC = 41.8f; d.net = "OFFLINE"; d.vpn = false; d.dnd = true; d.ringer = "SILENT"; d.saver = true; d.ramAvail = 900_000_000L;
    still(r, d, out + "/still_home_alt.png", w, h, HudRenderer.STATIC, 0, 0);
    System.out.println("frames " + n);
  }
  static void v20(HudRenderer.Data d) {
    long now = d.wallMs;
    java.util.Random R = new java.util.Random(7);
    int N = 360; d.kpT = new double[N]; d.kpV = new double[N]; double k = 2.0;
    for (int i = 0; i < N; i++) { d.kpT[i] = now - (N - 1 - i) * 60000L; k += (R.nextDouble() - 0.45) * 0.12; if (i > 250) k += 0.012; k = Math.max(0.3, Math.min(8.7, k)); d.kpV[i] = Math.round(k * 3) / 3.0; }
    d.kp = d.kpV[N - 1]; d.bz = -6.2; d.swSpeed = 512; d.xrFlux = 2.4e-6; d.xrMax = 1.2e-5; d.xrMaxAt = now - 2 * 3600000L; d.swUpd = now - 300000L;
    d.scR = 1; d.scS = 0; d.scG = 0;
    d.kpNeed = com.tokyonight.hud.live.SpaceWx.kpNeeded(com.tokyonight.hud.live.SpaceWx.geomagLat(d.obsLat, d.obsLon));
    d.cloud = 22;
    d.cloudT = new long[24]; d.cloudV = new float[24];
    for (int i = 0; i < 24; i++) { d.cloudT[i] = now - now % 3600000L + i * 3600000L; d.cloudV[i] = (float) Math.max(0, Math.min(100, 20 + 60 * Math.sin(i / 3.5) + R.nextInt(15))); }
    String[] w = new String[1];
    double sa = com.tokyonight.hud.live.Astro.pos(com.tokyonight.hud.live.Astro.SUN, now, d.obsLat, d.obsLon)[0];
    d.aur = com.tokyonight.hud.live.SpaceWx.aurora(d.kp, d.bz, sa, d.cloud, com.tokyonight.hud.live.SpaceWx.geomagLat(d.obsLat, d.obsLon), w); d.aurWhy = w[0];
    java.util.Calendar c = java.util.Calendar.getInstance(); c.setTimeInMillis(now);
    c.set(java.util.Calendar.HOUR_OF_DAY, 0); c.set(java.util.Calendar.MINUTE, 0); c.set(java.util.Calendar.SECOND, 0); c.set(java.util.Calendar.MILLISECOND, 0);
    long d0 = c.getTimeInMillis();
    d.tw0 = com.tokyonight.hud.live.Astro.twilight(d0 - 86400000L, d.obsLat, d.obsLon);
    d.tw1 = com.tokyonight.hud.live.Astro.twilight(d0, d.obsLat, d.obsLon);
    long best = Long.MAX_VALUE;
    for (int q = 0; q < 4; q++) { long t = com.tokyonight.hud.live.Astro.phaseEvent(now, q * 90); if (t > 0 && t < best) { best = t; d.phK = q; } }
    d.phT = best; d.phDist = com.tokyonight.hud.live.Astro.moonDistKm(best);
    double[] pe = com.tokyonight.hud.live.Astro.apsis(now, true), ap = com.tokyonight.hud.live.Astro.apsis(now, false);
    if (pe[0] < ap[0]) { d.apT = (long) pe[0]; d.apPeri = true; d.apDist = pe[1]; } else { d.apT = (long) ap[0]; d.apPeri = false; d.apDist = ap[1]; }
    d.met = com.tokyonight.hud.live.Astro.meteors(now, d.obsLat, d.obsLon);
    String l1 = "1 25544U 98067A   26307.50000000  .00016717  00000-0  10270-3 0  9005";
    String l2 = "2 25544  51.6416 247.4627 0006703 130.5360 325.0288 15.72125391563537";
    String c1 = "1 48274U 21035A   26307.50000000  .00020000  00000-0  23000-3 0  9991";
    String c2 = "2 48274  41.4700  95.1000 0005000 300.0000  60.0000 15.60000000300000";
    String s1 = "1 99001U 26123A   26307.50000000  .00050000  00000-0  50000-3 0  9991";
    String s2 = "2 99001  43.0000 200.0000 0002000  90.0000 270.0000 15.95000000    10";
    com.tokyonight.hud.live.Sat.Grid G = new com.tokyonight.hud.live.Sat.Grid(now, 36 * 3600000L, d.obsLat, d.obsLon);
    String[][] T = {{"ISS", l1, l2}, {"CSS", c1, c2}, {"STARLINK-99001", s1, s2}};
    for (int i = 0; i < 3; i++) {
      d.passSat[i] = com.tokyonight.hud.live.Sgp4.parse(T[i][0], T[i][1], T[i][2]);
      d.pass[i] = d.passSat[i] == null ? null : com.tokyonight.hud.live.Sat.nextVisible(d.passSat[i], G);
      System.out.println("pass " + i + " " + (d.pass[i] == null ? "none" : new java.util.Date(d.pass[i].visStart) + " max " + d.pass[i].maxEl));
    }
    if (d.pass[2] != null) { d.pass[2].label = "26-123"; d.pass[2].count = 28; }
    // v31 sample data
    String fc = "[[\"time_tag\",\"kp\",\"observed\",\"noaa_scale\"]";
    long b0 = now - now % (3 * 3600000L) - 6 * 3600000L;
    double[] kv = {2.33, 3.0, 3.67, 4.33, 5.0, 4.67, 3.33, 2.67, 2.0, 2.33};
    for (int i = 0; i < kv.length; i++) {
      java.util.Calendar u = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")); u.setTimeInMillis(b0 + i * 3 * 3600000L);
      fc += String.format(java.util.Locale.US, ",[\"%tF %<tT\",\"%.2f\",\"%s\",null]", u, kv[i], i < 2 ? "observed" : i < 3 ? "estimated" : "predicted");
    }
    fc += "]";
    double[][] f3 = com.tokyonight.hud.live.SpaceWx.kpForecast(fc);
    d.kpFcT = f3[0]; d.kpFcV = f3[1];
    System.out.println("kpfc n=" + f3[0].length + " predicted=" + (int) f3[2][5]);
    d.hrT = new long[48]; d.hrTemp = new float[48]; d.hrPop = new float[48];
    for (int i = 0; i < 48; i++) { d.hrT[i] = now - now % 3600000L + (i - 2) * 3600000L; d.hrTemp[i] = (float) (3 + 3 * Math.sin(i / 4.0)); d.hrPop[i] = (float) Math.max(0, 70 * Math.sin(i / 3.0)); }
    d.scN = 4; String[] sl = {"WHATSAPP", "MESSENGER", "PHONE", "GMAIL"};
    for (int i = 0; i < 4; i++) { d.scLabel[i] = sl[i]; d.scOk[i] = i != 1; }
  }
  static int shot(HudRenderer r, HudRenderer.Data d, String out, int n, int w, int h, int m, long t, float u) throws Exception {
    still(r, d, String.format("%s/f%04d.png", out, n), w, h, m, t, u); return n + 1;
  }
  static void still(HudRenderer r, HudRenderer.Data d, String f, int w, int h, int m, long t, float u) throws Exception {
    BufferedImage im = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
    java.awt.Graphics2D g = im.createGraphics();
    r.draw(new Canvas(g, w, h), m, t, u, d); g.dispose();
    ImageIO.write(im, "png", new File(f));
  }
}
