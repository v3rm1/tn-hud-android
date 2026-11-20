package com.tokyonight.hud.live;

import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Locale;
import java.util.Random;

/**
 * Pure android.graphics renderer. Design space is 1440 units wide; height follows aspect.
 * LOCKED: idle restricted-access HUD (animated). UNLOCKING: 1.4 s breach sequence.
 * STATIC: dashboard, drawn once per event.
 */
public final class HudRenderer {
    public static final int LOCKED = 0, UNLOCKING = 1, STATIC = 2;
    public static final long UNLOCK_MS = 1400;

    static final int BLACK = 0xFF000000, BLUE = 0xFF7AA2F7, CYAN = 0xFF7DCFFF, MAGENTA = 0xFFBB9AF7,
            RED = 0xFFF7768E, YELLOW = 0xFFE0AF68, GREEN = 0xFF9ECE6A, FG = 0xFFC0CAF5,
            COMMENT = 0xFF565F89, LINE = 0xFF292E42, DARK = 0xFF24283B, ORANGE = 0xFFFF9E64, NAUT = 0xFF3D59A1;
    static final float W = 1440f, M = 64f;
    static final String[] HEX = {"7A", "A2", "F7", "BB", "9A", "7D", "C0", "CA", "F5", "E0", "AF", "68"};
    static final int[][] PATH = {{0, 2}, {3, 2}, {3, 0}, {1, 0}, {1, 4}};

    public static final class Data {
        public int battery = 76;
        public boolean charging;
        public String alarm;
        public String model = "SM-S928B";
        public long free = 98L << 30, total = 256L << 30;
        public long wallMs = System.currentTimeMillis();
        // diagnostics (null / negative = unknown -> line omitted or shown as "--")
        public float tempC = Float.NaN;
        public int voltMv = -1;
        public String health;           // GOOD, OVERHEAT, ...
        public String plug;             // AC, USB, WIRELESS or null
        public long ramAvail = -1, ramTotal = -1;
        public int thermal = -1;        // PowerManager.THERMAL_STATUS_*
        public String net;              // WIFI, CELL, ETH, OFFLINE
        public boolean vpn, saver, dnd;
        public String ringer;           // RING, VIBRATE, SILENT
        public long uptimeMs = -1;
        public String os, patch, cpu;
        // live telemetry, 1 sample/s, newest at hHead-1
        public static final int N = 60;
        public final float[] hPwr = new float[N], hRx = new float[N], hTx = new float[N], hRam = new float[N], hTmp = new float[N];
        public int hLen, hHead;
        public float pwrW = Float.NaN, rxBps, txBps, ramPct, headroom = Float.NaN;
        public boolean pwrIn;
        public void push(float pw, float rx, float tx, float ram, float tmp) {
            hPwr[hHead] = pw; hRx[hHead] = rx; hTx[hHead] = tx; hRam[hHead] = ram; hTmp[hHead] = tmp;
            hHead = (hHead + 1) % N; if (hLen < N) hLen++;
        }
        public void clearHistory() { hLen = 0; hHead = 0; }
        // clock
        public boolean h24 = true;
        // weather
        public String wxCity;
        public float wxTemp = Float.NaN, wxHi = Float.NaN, wxLo = Float.NaN, wxWind = Float.NaN;
        public int wxCode = -1, wxRh = -1;
        public long wxUpd;
        public boolean wxErr;
        // bluetooth
        public String btState;
        public final String[] btName = new String[4];
        public final int[] btLvl = new int[4];
        public int btN;
        // network detail
        public int wifiRssi = Integer.MIN_VALUE, linkMbps = -1, cellLvl = -1;
        public String carrier;
        public long totRx = -1, totTx = -1, mobRx = -1, mobTx = -1;
        // motion, 4 Hz, 15 s window
        public static final int MN = 60;
        public final float[] ax = new float[MN], ay = new float[MN], az = new float[MN], gx = new float[MN], gy = new float[MN], gz = new float[MN];
        public int mLen, mHead;
        public boolean motionOn = true;
        public void pushMotion(float x, float y, float z, float a, float b, float cc) {
            ax[mHead] = x; ay[mHead] = y; az[mHead] = z; gx[mHead] = a; gy[mHead] = b; gz[mHead] = cc;
            mHead = (mHead + 1) % MN; if (mLen < MN) mLen++;
        }
        public void clearMotion() { mLen = 0; mHead = 0; }
        // now playing
        public boolean mActive, mPlaying, mAccess = true;
        public String mTitle, mArtist, mApp;
        public long mPos, mDur;
        public final float[] spec = new float[32];
        public boolean specOk;
        public String specState;
        // lock screen
        public long alarmAt, alertLeft, lastUnlock, fullInMs = -1;
        public boolean lkSpec = true;
        public String lkRain, lkSun, lkSunLabel, stepsState;
        // v20 lock: space weather (NOAA SWPC)
        public double kp = Double.NaN, kpNeed = Double.NaN, bz = Double.NaN, swSpeed = Double.NaN, xrFlux = Double.NaN, xrMax = Double.NaN;
        public long xrMaxAt, swUpd;
        public int scR = -1, scS = -1, scG = -1;
        public double[] kpT, kpV;                  // 1-min Kp series (oldest first)
        public int aur = -1; public String aurWhy;
        public double cloud = Double.NaN;          // max(low, mid) cloud cover now, %
        // v20 lock: sky events
        public long[] tw0, tw1;                    // twilight crossings: night before / after today's noon (Astro.twilight)
        public final Sat.Pass[] pass = new Sat.Pass[3];       // 0 ISS, 1 CSS (Tiangong), 2 Starlink train
        public final Sgp4[] passSat = new Sgp4[3];
        public String satState;                    // null when TLEs are loaded
        public double[] met;                       // Astro.meteors()
        public long phT; public int phK = -1; public double phDist = Double.NaN;
        public long apT; public boolean apPeri; public double apDist = Double.NaN;
        // v22 user layout: content-module order per view ([0] single, [1..3] tabs); lock tile order + visibility
        public final int[][] order = new int[4][];
        public int[] lkOrder;
        public final boolean[] lkOn = LK_ON_DEF.clone();
        public boolean lkKp = true, lkWind = true, lkTime = true, lkCharge = true, lkClock = true, lkNight = true;
        public long[] cloudT; public float[] cloudV;          // hourly max(low, mid) cloud cover, %
        // v20 screen saver: GNSS receiver state (deduplicated per constellation + svid)
        public static final int GMAX = 160;
        public int gN;
        public final int[] gSvid = new int[GMAX], gCons = new int[GMAX];
        public final float[] gCn0 = new float[GMAX], gEl = new float[GMAX], gAz = new float[GMAX];
        public final boolean[] gUsed = new boolean[GMAX], gL5 = new boolean[GMAX];
        public String gState;
        public float gAcc = Float.NaN;
        public double gLat = Double.NaN, gLon = Double.NaN, gAlt = Double.NaN;
        public long gFixAt, gTtff = -1, gStart;
        public int steps = -1, unlocks;
        public final int[] btLvl2 = {-1, -1, -1, -1};
        // sky / sensor lab
        public double obsLat = Double.NaN, obsLon = Double.NaN;
        public float heading = Float.NaN, fieldUT = Float.NaN, hPa = Float.NaN, hPaTrend = Float.NaN, altM = Float.NaN, lux = Float.NaN,
                pitch = Float.NaN, roll = Float.NaN;
        public boolean headTrue, altQnh, hasMag;
        // calendar
        public final String[] calT = new String[2];
        public final long[] calB = new long[2], calE = new long[2];
        public final boolean[] calAll = new boolean[2];
        public int calN;
        public String calState;
        // pages, taps, night dimming
        public int page, pages = 1, skyMode, motionWin = 15;
        public boolean splitPages = true, nightDim = true, clockUtc, dialMoon;
        public float nightDimAmt = 0.4f;
        public boolean noDock;
        public long tapHintUntil; public float tapHintY0;
        // shortcut row: up to 4 text blocks pinned near the bottom of the home screen
        public static final int SCMAX = 4;
        public int scN; public boolean scOn = true;
        public final String[] scLabel = new String[SCMAX];
        public final boolean[] scOk = new boolean[SCMAX];
        // v31: Kp forecast (3 h bins), hourly forecast strip, sky object info
        public double[] kpFcT, kpFcV;
        public long[] hrT; public float[] hrTemp, hrPop;
        public long wxHourlyUntil;
        public int skySel = -1; public long skySelUntil;
        public boolean launcherMode; public String widgetLabel;                                // favourites bar removed: modules use its space, readouts at the bottom                      // overlay opacity at full night dimming, 0.3 .. 0.7
        public float pageAnim = 1f, tapAnim = 1f;
        public boolean virtualPages, labShown;
        /** [0] single page, [1..3] tabs; columns = MOD_* */
        public final boolean[][] mods = {
            {true, true, true, true, true, false, false, false, false, false, false},
            {true, true, true, false, false, false, true, false, false, true, false},
            {true, true, false, true, true, false, false, false, false, false, false},
            {true, true, false, false, false, true, false, false, true, false, false}};
        /** visible (non-empty) tabs -> out[i] = tab index 0..2; returns count */
        public int visibleTabs(int[] out) {
            int n = 0;
            for (int t = 0; t < 3; t++) { boolean any = false; for (boolean b : mods[1 + t]) any |= b; if (any) out[n++] = t; }
            return n;
        }
        // calendar module: instances over the visible month grid, sorted by begin
        public int calView;                                   // 0 day, 1 week, 2 month
        public static final int EVMAX = 400;
        public int evN;
        public final String[] evT = new String[EVMAX];
        public final long[] evB = new long[EVMAX], evE = new long[EVMAX];
        public final boolean[] evAll = new boolean[EVMAX];
        public final int[] evCol = new int[EVMAX];
        public final long[] evId = new long[EVMAX];
    }

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint g = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF oval = new RectF();
    private final String[] grid = new String[25];
    private float s = 1f, H = 3120f;
    private int pw, ph;
    private float[] bx, bw, bh;
    private boolean[] ant;
    private float[] winX, winY;
    private int[] winC;

    public HudRenderer() {
        p.setTypeface(Typeface.MONOSPACE);
        Random r = new Random(1940);
        for (int i = 0; i < 25; i++) grid[i] = HEX[r.nextInt(HEX.length)];
    }

    public void setSize(int w, int h) {
        pw = w; ph = h; s = w / W; H = h / s;
        g.setStyle(Paint.Style.STROKE);
        g.setMaskFilter(new BlurMaskFilter(Math.max(1f, 12 * s), BlurMaskFilter.Blur.NORMAL));
    }

    private void buildSkyline() {
        Random r = new Random(49);
        ArrayList<float[]> b = new ArrayList<float[]>();
        float x = -20;
        while (x < W + 40) { float w = 70 + r.nextInt(130); b.add(new float[]{x, w, 120 + r.nextInt(300)}); x += w; }
        int n = b.size();
        bx = new float[n]; bw = new float[n]; bh = new float[n]; ant = new boolean[n];
        ArrayList<float[]> win = new ArrayList<float[]>();
        int[] pal = {CYAN, BLUE, MAGENTA, YELLOW};
        for (int i = 0; i < n; i++) {
            bx[i] = b.get(i)[0]; bw[i] = b.get(i)[1]; bh[i] = b.get(i)[2]; ant[i] = r.nextFloat() < 0.3f;
            for (float wy = 24; wy < bh[i] - 16; wy += 34)
                for (float wx = bx[i] + 16; wx < bx[i] + bw[i] - 16; wx += 28)
                    if (r.nextFloat() < 0.045f) win.add(new float[]{wx, wy - bh[i], pal[r.nextInt(4)]});
        }
        winX = new float[win.size()]; winY = new float[win.size()]; winC = new int[win.size()];
        for (int i = 0; i < win.size(); i++) { winX[i] = win.get(i)[0]; winY[i] = win.get(i)[1]; winC[i] = (int) win.get(i)[2]; }
    }

    // ---------------------------------------------------------------- entry
    public void draw(Canvas c, int mode, long clock, float u, Data d) {
        ts = mode == STATIC ? tsHome : tsLock;
        c.drawColor(BLACK);
        c.save();
        double mins = d.wallMs / 60000.0;  // burn-in drift, ±6 units, ~45 min period
        c.translate((float) (6 * Math.sin(mins / 7.0)) * s, (float) (6 * Math.cos(mins / 11.0)) * s);
        scene(c, mode, clock, u, d);
        float a = glitchAmt(mode, clock, u);
        if (mode == LOCKED && d.alertLeft > 0 && (clock / 120) % 3 == 0) a = Math.max(a, 0.6f);
        if (mode == STATIC && d.pageAnim < 0.3f) a = Math.max(a, 0.55f * (1 - d.pageAnim / 0.3f));
        if (mode == STATIC && d.tapAnim < 1f) a = Math.max(a, 0.3f * (1 - d.tapAnim));
        if (a > 0) glitch(c, mode, clock, u, d, a);
        c.restore();
        float dim = nightDim(d);
        if (dim > 0) c.drawColor(Math.round(dim * 255) << 24);       // translucent black over everything; black stays black
    }

    /** 0 in daylight, ramps to 0.4 between sunset and the end of civil twilight (-6 deg) */
    private float nightDim(Data d) {
        if (!d.nightDim || Double.isNaN(d.obsLat)) return 0f;
        double sa = Astro.pos(Astro.SUN, d.wallMs, d.obsLat, d.obsLon)[0];
        return sa >= 0 ? 0f : sa <= -6 ? d.nightDimAmt : (float) (-sa / 6 * d.nightDimAmt);
    }

    // ---- tap targets (design units), refreshed on every dashboard draw
    private boolean hSky, hDial, hMotion;
    private float hSkyX, hSkyY, hSkyR, hDialX, hDialY, hDialR, hMotY0, hMotY1;
    public static final int HIT_NONE = 0, HIT_SKY = 1, HIT_DIAL = 2, HIT_CLOCK = 3, HIT_MOTION = 4, HIT_CAL = 5, HIT_TAB0 = 10;
    private final float[] tabX0 = new float[3], tabX1 = new float[3];
    private int tabN;
    private float cdy;                                          // content shift below the tab bar

    /** px, py in surface pixels */
    // generic tap targets: {x0, y0, x1, y1, action} in design units + a long argument; last added wins
    private final java.util.ArrayList<float[]> taps = new java.util.ArrayList<float[]>();
    private final java.util.ArrayList<Long> tapArgs = new java.util.ArrayList<Long>();
    private float tdy;                                          // vertical offset of the block being drawn
    public long hitArg;
    public static final int A_SKYMODE = 20, A_CALVIEW = 21, A_UTC = 22, A_CLOCKAPP = 23, A_WX = 24, A_BATT = 25, A_STORAGE = 26,
            A_DEVCARE = 27, A_BT = 28, A_NET = 29, A_EVENT = 30, A_DAY = 31, A_NEWEVENT = 32, A_SEARCH = 33, A_VOICE = 34,
            A_MEMCLEAN = 35, A_TASKDONE = 36, A_TASKADD = 37, A_TASKSAPP = 38, A_NOTE_NEW = 39, A_KEEP_NEW = 40, A_NOTES_APP = 41,
            A_KEEP_APP = 42, A_MEDIA_PREV = 43, A_MEDIA_PLAY = 44, A_MEDIA_NEXT = 45, A_MUSIC_APP = 46, A_SHORTCUT = 47, A_SKYOBJ = 48;

    private void tap(float x0, float y0, float x1, float y1, int act, long arg) {
        taps.add(new float[]{x0, y0 + tdy, x1, y1 + tdy, act}); tapArgs.add(arg);
    }

    /** px, py in surface pixels; returns the action (HIT_NONE if nothing) and sets hitArg */
    public float hitY0, hitY1;                               // rect of the last hit, design units
    /** shortcuts that open system screens (Settings, Device care): gated by the tap setting */
    public static boolean systemAction(int a) { return a == A_BATT || a == A_STORAGE || a == A_DEVCARE || a == A_BT || a == A_NET || a == A_MEMCLEAN; }

    public int hit(float px, float py) {
        float x = px / s, y = py / s;
        for (int i = taps.size() - 1; i >= 0; i--) {
            float[] r = taps.get(i);
            if (x >= r[0] - 8 && x <= r[2] + 8 && y >= r[1] - 8 && y <= r[3] + 8) { hitArg = tapArgs.get(i); hitY0 = r[1]; hitY1 = r[3]; return (int) r[4]; }
        }
        return HIT_NONE;
    }

    /** small chamfered chip; returns its left edge (chips are laid out right to left) */
    private float chipR(Canvas c, String label, float rx, float base, boolean on, int act, long arg) {
        float w = tw(label, 20) + 28, x0 = rx - w, y0 = base - 26, y1 = base + 10;
        if (on) fill(BLUE); else stroke(LINE, 2);
        poly(c, true, x0 + 8, y0, rx, y0, rx, y1 - 8, rx - 8, y1, x0, y1, x0, y0 + 8);
        textC(c, label, x0 + w / 2, base, on ? BLACK : COMMENT, 20);
        tap(x0, y0, rx, y1, act, arg);
        return x0;
    }

    // ---- header font: Share Tech Mono (SIL OFL 1.1), clock in "scanline hologram" style
    private Typeface clockTf;
    public void setClockTypeface(Typeface t) { clockTf = t; }

    private float textF(Canvas c, String t, float x, float y, int col, float size) {
        p.setTypeface(clockTf != null ? clockTf : Typeface.MONOSPACE);
        float r = text(c, t, x, y, col, size);
        p.setTypeface(Typeface.MONOSPACE);
        return r;
    }

    private float textFR(Canvas c, String t, float rx, float y, int col, float size) {
        p.setTypeface(clockTf != null ? clockTf : Typeface.MONOSPACE);
        float x = textR(c, t, rx, y, col, size);
        p.setTypeface(Typeface.MONOSPACE);
        return x;
    }

    /** cyan glow, pale-to-cyan vertical gradient, horizontal scanline cuts; returns right edge */
    private float holo(Canvas c, String t, float x, float y, float size, int top, int bot, int glowCol) {
        Typeface tf = clockTf != null ? clockTf : Typeface.MONOSPACE;
        p.setTypeface(tf); p.setTextSize(size * s);
        float w = p.measureText(t) / s, y0 = y - size * 0.74f;
        g.setStyle(Paint.Style.FILL); g.setTypeface(tf); g.setTextSize(size * s); g.setColor(alpha(glowCol, 0.55f));
        c.drawText(t, x * s, y * s, g);
        g.setStyle(Paint.Style.STROKE);
        p.setStyle(Paint.Style.FILL); p.setPathEffect(null); p.setColor(0xFFFFFFFF);
        p.setShader(new LinearGradient(0, y0 * s, 0, y * s, top, bot, Shader.TileMode.CLAMP));
        c.drawText(t, x * s, y * s, p);
        p.setShader(null);
        float step = Math.max(4f, size / 24f);
        for (float yy = y0 - 6; yy < y + 6; yy += step) {
            fill(0xFF000000); rect(c, x - 6, yy, x + w + 6, yy + step * 0.22f);
            fill(0x8C000000); rect(c, x - 6, yy + step * 0.22f, x + w + 6, yy + step * 0.42f);
        }
        p.setTypeface(Typeface.MONOSPACE);
        return x + w;
    }

    // ---- star catalogue (ra, dec J2000 deg; V mag; B-V) + constellation polylines
    private float[] stRa, stDec, stMag, stBv;
    private float[][] stLines;

    public void setStars(byte[] b) {
        try {
            java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(b));
            int n = in.readInt();
            stRa = new float[n]; stDec = new float[n]; stMag = new float[n]; stBv = new float[n];
            for (int i = 0; i < n; i++) { stRa[i] = in.readFloat(); stDec[i] = in.readFloat(); stMag[i] = in.readFloat(); stBv[i] = in.readFloat(); }
            int m = in.readInt();
            stLines = new float[m][];
            for (int i = 0; i < m; i++) { int k = in.readInt(); stLines[i] = new float[k * 2]; for (int j = 0; j < k * 2; j++) stLines[i][j] = in.readFloat(); }
        } catch (Exception e) { stRa = null; stLines = null; }
    }

    private void scene(Canvas c, int mode, long clock, float u, Data d) {
        if (mode == LOCKED) lockScene(c, clock, d);
        else if (mode == UNLOCKING) unlockScene(c, clock, u, d);
        else {
            dashboard(c, d, 1f, 0);
            if (d.wallMs < d.tapHintUntil) {                  // double-tap prompt over the tapped row
                float y = Math.max(140, d.tapHintY0 - 10), w = tw("TAP AGAIN TO OPEN", 24) + 48;
                fill(BLACK); rect(c, 720 - w / 2, y - 40, 720 + w / 2, y + 8);
                stroke(CYAN, 2); rect(c, 720 - w / 2, y - 40, 720 + w / 2, y + 8);
                textC(c, "TAP AGAIN TO OPEN", 720, y - 8, CYAN, 24);
            }
        }
    }

    private float glitchAmt(int mode, long clock, float u) {
        if (mode == LOCKED) return (clock % 7000) < 140 ? 0.5f : 0f;
        if (mode == UNLOCKING) {
            if (u < 260) return 1f - u / 520f;
            if (u > 640 && u < 780) return 0.7f;
            if (u > 950 && u < 1050) return 0.4f;
        }
        return 0f;
    }

    private void glitch(Canvas c, int mode, long clock, float u, Data d, float amt) {
        Random r = new Random((clock / 50) * 7919L + (long) (u / 50));
        int n = 3 + r.nextInt(4);
        for (int i = 0; i < n; i++) {
            float y = r.nextFloat() * H, hh = 8 + r.nextInt(90), dx = (r.nextFloat() * 2 - 1) * 80 * amt;
            c.save();
            c.clipRect(-20 * s, y * s, pw + 20 * s, (y + hh) * s);
            c.drawColor(BLACK);
            c.translate(dx * s, 0);
            scene(c, mode, clock, u, d);
            c.restore();
            fill(alpha(i % 2 == 0 ? RED : CYAN, 0.6f * amt));
            rect(c, -20, y, W + 20, y + 3);
        }
    }

    // ---------------------------------------------------------------- scenes
    static final float LOCK_CY = 830, LOCK_R = 190, LOCK_PK = 0.6f;   // ring centre (design units), radius, padlock scale

    private void lockScene(Canvas c, long clock, Data d) {
        boolean alert = d.alertLeft > 0, blink = (clock / 180) % 2 == 0;
        boolean flare = !Double.isNaN(d.xrFlux) && d.xrFlux >= 1e-5 && d.wallMs - d.swUpd < 3600000L;
        int fc = alert && blink ? YELLOW : flare && (clock / 400) % 2 == 0 ? (d.xrFlux >= 1e-4 ? MAGENTA : ORANGE) : RED;
        frame(c, fc);
        rail(c, clock, RED, true);
        float cx = 720, cy = LOCK_CY, R = LOCK_R;
        if (d.lkClock) {                                   // same header as the home screen (clock + weather)
            clockBlock(c, d, 1f, 0); weatherBlock(c, d, 1f, 0);
            stroke(LINE, 2); ln(c, M + 40, 500, W - M - 40, 500);
        }
        ring(c, cx, cy, R, clock / 40f, 0x00000000, 1f);
        twilightRing(c, d, cx, cy, R - 40 + 16);
        dayRing(c, d, cx, cy, R - 40, alert ? RED : BLUE);
        moonRing(c, d, cx, cy, R + 24);
        int live = livePass(d);
        if (live >= 0) passPlot(c, d, live, cx, cy, R - 46);
        else padlock(c, cx, cy - 12, alert && blink ? RED : FG, 0f, LOCK_PK);
        if (d.lkKp) kpStrip(c, d, M + 40, cy - 220, M + 330, cy + 150);
        if (d.lkWind) windPanel(c, d, 1010, cy - 226);

        // status line: motion > storm > aurora > satellite overhead > idle
        String st; int sc;
        if (alert) { st = "// MOTION DETECTED"; sc = blink ? RED : YELLOW; }
        else if (d.scG >= 1) { st = "// G" + d.scG + " GEOMAGNETIC STORM IN PROGRESS"; sc = d.scG >= 3 ? MAGENTA : YELLOW; }
        else if (d.scR >= 3) { st = "// R" + d.scR + " RADIO BLACKOUT"; sc = ORANGE; }
        else if (d.aur == 2) { st = "// AURORA ALERT · LOOK NORTH"; sc = GREEN; }
        else if (live >= 0) { st = "// " + passName(d, live) + " OVERHEAD"; sc = CYAN; }
        else { st = "// ACCESS RESTRICTED"; sc = RED; }
        textC(c, st, cx, cy + R + 118, sc, 34);

        String l2; int c2;
        if (d.alarmAt > d.wallMs) {
            long m = (d.alarmAt - d.wallMs + 59999) / 60000;
            String cd = m >= 1440 ? String.format(Locale.US, "T-%dD %02dH", m / 1440, (m / 60) % 24)
                    : String.format(Locale.US, "T-%02d:%02d", m / 60, m % 60);
            l2 = cd + " → ALARM " + hm(d, d.alarmAt); c2 = YELLOW;
        } else { l2 = "AWAITING BIOMETRIC AUTH"; c2 = COMMENT; }
        float ex = textC(c, l2, cx, cy + R + 166, c2, 26);
        if ((clock / 530) % 2 == 0) { fill(RED); rect(c, ex + 10, cy + R + 144, ex + 24, cy + R + 168); }
        float ty = cy + R + 236;
        if (d.lkNight) { tonight(c, d, ty); ty += 194; }

        // tiles, flowed two per row
        lt.clear();
        for (int id : cleanOrder(d.lkOrder, LK_DEF)) {
            if (!d.lkOn[id]) continue;
            switch (id) {
                case 0: addT("WX" + (d.wxCity != null ? " · " + d.wxCity : ""), Float.isNaN(d.wxTemp) ? "--" : String.format(Locale.US, "%.0f° %s", d.wxTemp, wmo(d.wxCode)), FG); break;
                case 1: addT("PRECIP · NEXT 2H", d.lkRain != null ? d.lkRain : "--", d.lkRain != null && !d.lkRain.startsWith("DRY") ? CYAN : FG); break;
                case 2: auroraTile(d); break;
                case 3: darkTile(d); break;
                case 4: addT(d.lkSunLabel != null ? d.lkSunLabel : "DAYLIGHT", d.lkSun != null ? d.lkSun : "--", YELLOW); break;
                case 5: moonTile(d); break;
                case 6: passTile(d, 0); break;
                case 7: passTile(d, 1); break;
                case 8: meteorTile(d); break;
                case 9: if (d.pass[2] != null) passTile(d, 2); break;
                case 10: calTile(d); break;
                case 11:
                    if (d.btN > 0) {
                        int lo = d.btLvl2[0] >= 0 ? Math.min(d.btLvl[0], d.btLvl2[0]) : d.btLvl[0];
                        addT("LINKED" + (d.btN > 1 ? " · +" + (d.btN - 1) + " MORE" : ""), btLine(d, 0), lo < 0 ? FG : lo > 50 ? GREEN : lo > 20 ? YELLOW : RED);
                    } else addT("LINKED", d.btState != null ? d.btState : "NONE", COMMENT);
                    break;
                default:
                    String stp = d.steps >= 0 ? String.format(Locale.US, "%,d", d.steps).replace(',', ' ') : "--";
                    addT("STEPS · UNLOCKS" + (d.lastUnlock > 0 ? " · LAST " + ago(d.wallMs - d.lastUnlock) : ""), stp + " · " + d.unlocks, d.steps >= 0 ? GREEN : COMMENT);
            }
        }
        float xa = M + 80, xb = 780, y = ty + 10;
        for (int i = 0; i < lt.size(); i++) {
            Object[] t = lt.get(i);
            tile2(c, (String) t[0], (String) t[1], (Integer) t[2], i % 2 == 0 ? xa : xb, y);
            if (i % 2 == 1) y += 66 + 38 * Math.max(1f, ts);
        }
        if (lt.size() % 2 == 1) y += 66 + 38 * Math.max(1f, ts);
        if (d.charging && d.lkCharge) {
            String chg = (Float.isNaN(d.pwrW) ? "" : String.format(Locale.US, "+%.1f W · ", d.pwrW))
                    + String.format(Locale.US, "%d%%", d.battery)
                    + (d.fullInMs > 0 ? String.format(Locale.US, " · FULL IN %d:%02d", d.fullInMs / 3600000, (d.fullInMs / 60000) % 60) : "");
            tile2(c, "CHARGING" + (d.plug != null ? " · " + d.plug : ""), chg, CYAN, xa, y);
            int on = Math.round(d.battery / 5f);
            for (int i = 0; i < 20; i++) {
                float sx = xb + 40 + i * 24, sy = y + 14;
                if (i < on) fill(CYAN); else stroke(LINE, 2);
                poly(c, true, sx + 6, sy, sx + 22, sy, sx + 16, sy + 22, sx, sy + 22);
            }
            y += 104;
        }
        if (d.lkTime) timeStrip(c, d, y + 30);

        sweep(c, clock);
        readouts(c, "SYS//LOCKED", "UNIT " + d.model + " · ICE ", "ACTIVE", RED, (clock / 530) % 2 == 0, 1f, 0);
    }

    // ---- tonight: next 12 h of sun depth, Moon above the horizon, cloud, and visible passes
    private long tnAt; private double tnLat = Double.NaN;
    private final float[] tnSun = new float[73], tnMoon = new float[73];
    private float tnIllum;

    private void tonight(Canvas c, Data d, float y) {
        float lx = M + 40, x0 = M + 150, x1 = W - M - 40, w = x1 - x0;
        long t0 = d.wallMs - d.wallMs % 600000L, span = 12 * 3600000L;
        text(c, "TONIGHT · NEXT 12 H", lx, y, COMMENT, 22);
        if (Double.isNaN(d.obsLat)) { textR(c, "SET A LOCATION", x1, y, COMMENT, 22); return; }
        if (Math.abs(t0 - tnAt) >= 600000L || d.obsLat != tnLat) {
            tnAt = t0; tnLat = d.obsLat;
            for (int i = 0; i < 73; i++) {
                long t = t0 + i * 600000L;
                tnSun[i] = (float) Astro.pos(Astro.SUN, t, d.obsLat, d.obsLon)[0];
                tnMoon[i] = (float) Astro.pos(Astro.MOON, t, d.obsLat, d.obsLon)[0];
            }
            tnIllum = (float) Astro.moonPhase(t0 + span / 2)[0];
        }
        float sy = y + 22, mh = 14;
        text(c, "SUN", lx, sy + 18, COMMENT, 16);
        // sun depth band
        for (int i = 0; i < 72; i++) {
            float a = tnSun[i], xa = x0 + w * i / 72f, xb = x0 + w * (i + 1) / 72f;
            int col = a > -0.833f ? alpha(YELLOW, 0.45f) : a > -6 ? alpha(ORANGE, 0.75f) : a > -12 ? alpha(MAGENTA, 0.7f) : a > -18 ? NAUT : BLUE;
            fill(col); rect(c, xa, sy, xb + 0.5f, sy + 22);
        }
        // moon above the horizon, brightness ~ illumination
        float my = sy + 32;
        text(c, "MOON " + Math.round(tnIllum * 100) + "%", lx, my + 13, COMMENT, 16);
        stroke(LINE, 1); ln(c, x0, my + mh / 2, x1, my + mh / 2);
        for (int i = 0; i < 72; i++) if (tnMoon[i] > 0) { fill(alpha(FG, 0.25f + 0.7f * tnIllum)); rect(c, x0 + w * i / 72f, my, x0 + w * (i + 1) / 72f + 0.5f, my + mh); }
        // Kp forecast, 3 h bins; green where it reaches the Kp you need
        float ky = my + mh + 8;
        text(c, "KP FC", lx, ky + 13, COMMENT, 16);
        stroke(LINE, 1); ln(c, x0, ky + mh, x1, ky + mh);
        if (d.kpFcT != null) for (int i = 0; i < d.kpFcT.length; i++) {
            long a = (long) d.kpFcT[i], b = a + 3 * 3600000L;
            if (b <= t0 || a >= t0 + span) continue;
            float xa = x0 + w * Math.max(0, a - t0) / span, xb = x0 + w * Math.min(span, b - t0) / span;
            boolean ok = !Double.isNaN(d.kpNeed) && d.kpFcV[i] >= d.kpNeed;
            float hk = (float) Math.min(1, d.kpFcV[i] / 9) * mh;
            fill(ok ? GREEN : d.kpFcV[i] >= 5 ? YELLOW : alpha(COMMENT, 0.8f)); rect(c, xa + 1, ky + mh - Math.max(2, hk), xb - 1, ky + mh);
            if (xb - xa > 40) textC(c, String.format(Locale.US, "%.0f", d.kpFcV[i]), (xa + xb) / 2, ky - 2, ok ? GREEN : COMMENT, 14);
        }
        // cloud cover, hourly bars hanging below
        float cy0 = ky + mh + 8;
        if (d.cloudT != null) for (int i = 0; i < d.cloudT.length; i++) {
            long a = d.cloudT[i], b = a + 3600000L;
            if (b <= t0 || a >= t0 + span || Float.isNaN(d.cloudV[i])) continue;
            float xa = x0 + w * Math.max(0, a - t0) / span, xb = x0 + w * Math.min(span, b - t0) / span;
            fill(alpha(CYAN, 0.55f)); rect(c, xa + 1, cy0, xb - 1, cy0 + 26 * d.cloudV[i] / 100f);
        }
        // pass markers
        for (int k = 0; k < 3; k++) {
            Sat.Pass p0 = d.pass[k];
            if (p0 == null || p0.visStart < t0 || p0.visStart > t0 + span) continue;
            float px = x0 + w * (p0.visStart - t0) / span;
            stroke(CYAN, 3); ln(c, px, sy - 4, px, cy0 + 30);
            text(c, k == 0 ? "ISS" : k == 1 ? "CSS" : "STARLINK", px + 6, sy + 17, FG, 16);
        }
        // hour ticks
        Calendar cal = Calendar.getInstance(); cal.setTimeInMillis(t0);
        cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0);
        for (long t = cal.getTimeInMillis() + 3600000L; t < t0 + span; t += 3600000L) {
            float tx = x0 + w * (t - t0) / span;
            stroke(LINE, 1); ln(c, tx, sy + 22, tx, sy + 28);
            Calendar h = Calendar.getInstance(); h.setTimeInMillis(t);
            if (h.get(Calendar.HOUR_OF_DAY) % 2 == 0) textC(c, String.format(Locale.US, "%02d", h.get(Calendar.HOUR_OF_DAY)), tx, cy0 + 48, COMMENT, 16);
        }
        text(c, "CLOUD", lx, cy0 + 18, COMMENT, 16);
    }

    private final ArrayList<Object[]> lt = new ArrayList<Object[]>();
    private void addT(String label, String value, int col) { lt.add(new Object[]{label, value, col}); }

    private void tile2(Canvas c, String label, String value, int col, float x, float y) {
        text(c, fit(label, 22, 560), x, y, COMMENT, 22);
        text(c, fit(value, 28, 560), x, y + 16 + 22 * Math.max(1f, ts), col, 28);
    }

    private static String dhm(long ms) {
        long m = Math.max(0, ms / 60000);
        if (m >= 1440) return String.format(Locale.US, "%dD %dH", m / 1440, (m / 60) % 24);
        if (m >= 60) return String.format(Locale.US, "%dH %02dM", m / 60, m % 60);
        return m + "M";
    }

    private static String tminus(long ms) {
        long m = Math.max(0, (ms + 59999) / 60000);
        return m >= 1440 ? String.format(Locale.US, "T-%dD %02dH", m / 1440, (m / 60) % 24) : String.format(Locale.US, "T-%02d:%02d", m / 60, m % 60);
    }

    private static String dayMon(long ms) {
        Calendar c = Calendar.getInstance(); c.setTimeInMillis(ms);
        return String.format(Locale.US, "%ta %<td %<tb", c).toUpperCase(Locale.US);
    }

    // ---- A1 aurora
    private void auroraTile(Data d) {
        String lab = "AURORA" + (Double.isNaN(d.kp) ? "" : String.format(Locale.US, " · KP %.1f", d.kp))
                + (Double.isNaN(d.kpNeed) ? "" : String.format(Locale.US, " / NEED %.1f", d.kpNeed));
        String w = d.aurWhy != null ? d.aurWhy : "--";
        double[] fc = SpaceWx.kpForecastMax(d.kpFcT, d.kpFcV, d.wallMs, d.wallMs + 12 * 3600000L);
        if (!Double.isNaN(fc[0]) && d.aur >= 0) {
            Calendar a = Calendar.getInstance(); a.setTimeInMillis((long) fc[1]);
            Calendar b = Calendar.getInstance(); b.setTimeInMillis((long) fc[1] + 3 * 3600000L);
            w += String.format(Locale.US, " · FC %.1f %02d\u2013%02d", fc[0], a.get(Calendar.HOUR_OF_DAY), b.get(Calendar.HOUR_OF_DAY));
        }
        if (d.aur == 2) addT(lab, "GO · " + w, GREEN);
        else if (d.aur == 1) addT(lab, "MAYBE · " + w, YELLOW);
        else if (d.aur == 0) addT(lab, "NO · " + w, COMMENT);
        else addT("AURORA", Double.isNaN(d.obsLat) ? "SET A LOCATION" : "AWAITING NOAA DATA", COMMENT);
    }

    // ---- D8 darkness window
    private void darkTile(Data d) {
        long now = d.wallMs;
        String[] kinds = {"ASTRO DARK", "NAUTICAL DARK", "NAUT TWILIGHT"};
        boolean any = false;
        for (long[] t : new long[][]{d.tw0, d.tw1}) {
            if (t == null) continue;
            any = true;
            for (int k = 0; k < 3; k++) {
                long a = t[3 - k], b = t[4 + k];
                if (a <= 0 || b <= 0 || now >= b) continue;
                if (now >= a) { addT(kinds[k] + " \u00B7 NOW", "ENDS " + hm(d, b) + " \u00B7 " + dhm(b - now) + " LEFT", MAGENTA); return; }
                addT(kinds[k] + " \u00B7 " + dhm(b - a), hm(d, a) + " \u2192 " + hm(d, b) + " \u00B7 IN " + dhm(a - now), FG);
                return;
            }
        }
        addT("DARKNESS", any ? "WHITE NIGHT \u00B7 SUN STAYS ABOVE -6\u00B0" : Double.isNaN(d.obsLat) ? "SET A LOCATION" : "--", any ? YELLOW : COMMENT);
    }

    // ---- D10 lunar event
    private void moonTile(Data d) {
        if (d.phK < 0 || d.phT <= 0) { addT("MOON", "--", COMMENT); return; }
        String[] nm = {"NEW MOON", "FIRST QUARTER", "FULL MOON", "LAST QUARTER"};
        boolean sup = (d.phK == 0 || d.phK == 2) && d.phDist < 362000;
        String lab = nm[d.phK] + " · " + dayMon(d.phT) + " " + hm(d, d.phT) + (sup ? " · SUPERMOON" : "");
        String v = "IN " + dhm(d.phT - d.wallMs) + String.format(Locale.US, " · %.0fK KM", d.phDist / 1000);
        if (d.apT > 0) v += " · " + (d.apPeri ? "PERI " : "APO ") + dayMon(d.apT).substring(4);
        addT(lab, v, sup ? YELLOW : FG);
    }

    // ---- D9 meteors
    private void meteorTile(Data d) {
        if (d.met == null) { addT("METEORS", "NO MAJOR SHOWER ACTIVE", COMMENT); return; }
        int i = (int) d.met[0];
        String lab = "METEORS · " + Astro.SHOWER_NAME(i) + (d.met[4] > 1 ? " · +" + (int) (d.met[4] - 1) : "");
        double dp = d.met[3];
        String pk = Math.abs(dp) < 0.5 ? "PEAK NOW" : dp > 0 ? "PEAK IN " + Math.round(dp) + "D" : "PEAK +" + Math.round(-dp) + "D";
        double alt = d.met[2];
        String rad = Double.isNaN(alt) ? "" : alt > 0 ? String.format(Locale.US, " · RAD %.0f°", alt) : " · RAD BELOW HZN";
        String zhr = String.format(Locale.US, "ZHR %d", Astro.SHOWER_ZHR(i));
        addT(lab, zhr + rad + " · " + pk, Math.abs(dp) < 1.5 ? CYAN : FG);
    }

    // ---- B4/B5 satellite passes
    static final String[] PNAMES = {"ISS", "TIANGONG", "STARLINK"};
    private String passName(Data d, int k) { return PNAMES[k]; }

    private void passTile(Data d, int k) {
        Sat.Pass p0 = d.pass[k];
        String lab = k == 2 && p0 != null ? "STARLINK TRAIN · " + p0.label + " · " + p0.count + " SATS" : PNAMES[k] + " · NEXT VISIBLE PASS";
        if (p0 == null || p0.visEnd < d.wallMs) {
            addT(PNAMES[k] + (k == 2 ? " TRAIN" : "") + " · PASSES", d.satState != null ? d.satState : Double.isNaN(d.obsLat) ? "SET A LOCATION" : "NONE VISIBLE · 36H", COMMENT);
            return;
        }
        String dir = Sat.compass(p0.azVisStart) + "→" + Sat.compass(p0.azVisEnd);
        long dur = p0.visEnd - p0.visStart;
        if (d.wallMs >= p0.visStart) {
            double[] o = new double[4];
            if (d.passSat[k] != null && Sat.look(d.passSat[k], d.wallMs, d.obsLat, d.obsLon, o))
                addT(PNAMES[k] + " · VISIBLE NOW", String.format(Locale.US, "EL %.0f° · AZ %.0f° %s · %s", o[0], o[1], Sat.compass(o[1]),
                        o[3] > 0 ? "SUNLIT" : "IN SHADOW"), o[3] > 0 ? CYAN : COMMENT);
            else addT(PNAMES[k] + " · VISIBLE NOW", dir, CYAN);
            return;
        }
        long to = p0.visStart - d.wallMs;
        String v = tminus(to) + String.format(Locale.US, " · %.0f° · ", p0.maxEl) + dir + " · " + Math.max(1, Math.round(dur / 60000.0)) + "M";
        addT(lab.replace("NEXT VISIBLE PASS", hm(d, p0.visStart)), v, to < 600000L ? CYAN : p0.maxEl >= 40 ? FG : alpha(FG, 0.8f));
    }

    /** index of a pass whose visible window is now (-3 min .. +1 min), else -1 */
    private int livePass(Data d) {
        for (int k = 0; k < 3; k++) {
            Sat.Pass p0 = d.pass[k];
            if (p0 != null && d.wallMs >= p0.visStart - 180000L && d.wallMs <= p0.visEnd + 60000L && d.passSat[k] != null) return k;
        }
        return -1;
    }

    /** polar sky plot of the pass inside the ring: zenith at centre, north up, east right (map view) */
    private void passPlot(Canvas c, Data d, int k, float cx, float cy, float rp) {
        Sat.Pass p0 = d.pass[k];
        stroke(LINE, 2);
        c.drawCircle(cx * s, cy * s, rp * s, p);
        c.drawCircle(cx * s, cy * s, rp * 2 / 3 * s, p);
        c.drawCircle(cx * s, cy * s, rp / 3 * s, p);
        ln(c, cx - rp, cy, cx + rp, cy); ln(c, cx, cy - rp, cx, cy + rp);
        textC(c, "N", cx, cy - rp - 10, COMMENT, 22); textC(c, "S", cx, cy + rp + 28, COMMENT, 22);
        text(c, "E", cx + rp + 8, cy + 8, COMMENT, 22); textR(c, "W", cx - rp - 8, cy + 8, COMMENT, 22);
        for (int i = 1; i < p0.tAz.length; i++) {
            if (p0.tEl[i] < 0 || p0.tEl[i - 1] < 0) continue;
            float[] a = sp(cx, cy, rp, p0.tEl[i - 1], p0.tAz[i - 1]), b = sp(cx, cy, rp, p0.tEl[i], p0.tAz[i]);
            stroke(p0.tVis[i] ? CYAN : COMMENT, p0.tVis[i] ? 4 : 2); ln(c, a[0], a[1], b[0], b[1]);
        }
        double[] o = new double[4];
        if (Sat.look(d.passSat[k], d.wallMs, d.obsLat, d.obsLon, o) && o[0] > -2) {
            float[] q = sp(cx, cy, rp, (float) Math.max(0, o[0]), (float) o[1]);
            int col = o[3] > 0 ? CYAN : COMMENT;
            fill(alpha(col, 0.3f)); c.drawCircle(q[0] * s, q[1] * s, 20 * s, p);
            fill(col); c.drawCircle(q[0] * s, q[1] * s, 9 * s, p);
            text(c, String.format(Locale.US, "%s %.0f°", PNAMES[k], o[0]), q[0] + 16, q[1] - 12, FG, 22);
        } else textC(c, PNAMES[k] + " " + tminus(p0.visStart - d.wallMs), cx, cy + 8, CYAN, 26);
    }

    private static float[] sp(float cx, float cy, float rp, float el, float az) {
        float r = rp * (90 - el) / 90f; double t = Math.toRadians(az);
        return new float[]{cx + r * (float) Math.sin(t), cy - r * (float) Math.cos(t)};
    }

    // ---- A2 Kp seismograph (left of the ring): time runs downward, newest at the bottom
    private void kpStrip(Canvas c, Data d, float x0, float y0, float x1, float y1) {
        text(c, "KP · 6H", x0, y0 - 18, COMMENT, 20);
        stroke(LINE, 2); rect(c, x0, y0, x1, y1);
        float kx = (x1 - x0) / 9f;
        for (int k = 1; k < 9; k++) { stroke(alpha(LINE, k % 3 == 0 ? 1f : 0.5f), 1); ln(c, x0 + k * kx, y0, x0 + k * kx, y1); }
        stroke(alpha(YELLOW, 0.6f), 2); dashV(c, x0 + 5 * kx, y0, y1);                                  // G1 storm line
        if (!Double.isNaN(d.kpNeed) && d.kpNeed < 9) { stroke(alpha(GREEN, 0.8f), 2); dashV(c, x0 + (float) d.kpNeed * kx, y0, y1); }
        if (d.kpT != null && d.kpT.length > 1) {
            double t1 = d.kpT[d.kpT.length - 1], t0 = t1 - 6 * 3600000.0;
            path.reset(); boolean st = false;
            for (int i = 0; i < d.kpT.length; i++) {
                if (d.kpT[i] < t0) continue;
                float y = y0 + (float) ((d.kpT[i] - t0) / (t1 - t0)) * (y1 - y0);
                float x = x0 + (float) Math.min(9, Math.max(0, d.kpV[i])) * kx;
                if (!st) { path.moveTo(x * s, y * s); st = true; } else path.lineTo(x * s, y * s);
            }
            int col = d.kp >= 5 ? YELLOW : !Double.isNaN(d.kpNeed) && d.kp >= d.kpNeed ? GREEN : CYAN;
            g.setColor(alpha(col, 0.5f)); g.setStrokeWidth(6 * s); c.drawPath(path, g);
            stroke(col, 3); c.drawPath(path, p);
        }
        String kv = Double.isNaN(d.kp) ? "--" : String.format(Locale.US, "%.1f", d.kp);
        float kxEnd = text(c, kv, x0, y1 + 50, Double.isNaN(d.kp) ? COMMENT : d.kp >= 5 ? YELLOW : FG, 40);
        text(c, " KP", kxEnd, y1 + 50, COMMENT, 22);
        text(c, "0", x0, y1 + 82, COMMENT, 18); textC(c, "5", x0 + 5 * kx, y1 + 82, YELLOW, 18); textR(c, "9", x1, y1 + 82, COMMENT, 18);
    }

    private void dashV(Canvas c, float x, float y0, float y1) {
        for (float y = y0; y < y1; y += 18) ln(c, x, y, x, Math.min(y1, y + 9));
    }

    // ---- A2 solar wind / X-ray / NOAA scales (right of the ring)
    private void windPanel(Canvas c, Data d, float x, float y) {
        boolean stale = d.swUpd == 0 || d.wallMs - d.swUpd > 3 * 3600000L;
        text(c, "SOLAR WIND", x, y, COMMENT, 20);
        String v = Double.isNaN(d.swSpeed) || stale ? "--" : String.format(Locale.US, "%.0f", d.swSpeed);
        float e = text(c, v, x, y + 44, !stale && d.swSpeed >= 600 ? ORANGE : FG, 38);
        text(c, " KM/S", e, y + 44, COMMENT, 20);
        text(c, "IMF BZ", x, y + 92, COMMENT, 20);
        String bz = Double.isNaN(d.bz) || stale ? "--" : String.format(Locale.US, "%+.1f NT", d.bz);
        int bc = Double.isNaN(d.bz) ? COMMENT : d.bz <= -10 ? MAGENTA : d.bz <= -5 ? GREEN : FG;
        text(c, bz, x, y + 128, bc, 30);
        float bx0 = x, bx1 = x + 220, by = y + 146, mid = (bx0 + bx1) / 2;           // -20 .. +20 nT bar, south = left
        stroke(LINE, 2); ln(c, bx0, by, bx1, by); ln(c, mid, by - 8, mid, by + 8);
        if (!Double.isNaN(d.bz) && !stale) {
            float bxv = mid + (float) Math.max(-20, Math.min(20, d.bz)) / 20f * (bx1 - mid);
            stroke(bc, 6); ln(c, mid, by, bxv, by);
        }
        text(c, "S", bx0, by + 26, COMMENT, 16); textR(c, "N", bx1, by + 26, COMMENT, 16);
        text(c, "X-RAY · GOES", x, y + 208, COMMENT, 20);
        String xc = stale ? "--" : SpaceWx.flareClass(d.xrFlux);
        int xcol = Double.isNaN(d.xrFlux) || stale ? COMMENT : d.xrFlux >= 1e-4 ? MAGENTA : d.xrFlux >= 1e-5 ? ORANGE : d.xrFlux >= 1e-6 ? YELLOW : FG;
        text(c, xc, x, y + 248, xcol, 34);
        if (!stale && !Double.isNaN(d.xrMax)) text(c, "6H MAX " + SpaceWx.flareClass(d.xrMax) + (d.xrMaxAt > 0 ? " " + hm(d, d.xrMaxAt) : ""), x, y + 278, COMMENT, 18);
        text(c, "NOAA SCALES", x, y + 330, COMMENT, 20);
        int[] sv = {d.scR, d.scS, d.scG}; String[] sk = {"R", "S", "G"};
        float sx = x;
        for (int i = 0; i < 3; i++) {
            String t = sk[i] + (sv[i] < 0 || stale ? "-" : String.valueOf(sv[i]));
            sx = text(c, t + " ", sx, y + 366, sv[i] <= 0 || stale ? COMMENT : sv[i] >= 3 ? MAGENTA : YELLOW, 28);
        }
    }

    // ---- C6/C7 time scales
    private void timeStrip(Canvas c, Data d, float y) {
        long ms = d.wallMs;
        Calendar u = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")); u.setTimeInMillis(ms);
        String utc = String.format(Locale.US, "%02d:%02d:%02d", u.get(Calendar.HOUR_OF_DAY), u.get(Calendar.MINUTE), u.get(Calendar.SECOND));
        long tai = ms / 1000 + 37;
        long gps = ms / 1000 + 18 - 315964800L;
        double jd = Astro.jd(ms);
        double lon = Double.isNaN(d.obsLon) ? 0 : d.obsLon;
        double lst = Astro.lst(jd, lon) / 15.0;
        double eot = Astro.eotMin(ms);
        double lat = ((ms / 1000.0 + lon * 240 + eot * 60) % 86400 + 86400) % 86400;
        String[][] r1 = {{"UTC ", utc}, {"  TAI ", hmsOf(tai)}, {"  GPS ", String.format(Locale.US, "%d/%06d", gps / 604800, gps % 604800)},
                {"  UNIX ", String.format(Locale.US, "0x%08X", ms / 1000)}};
        String[][] r2 = {{"JD ", String.format(Locale.US, "%.5f", jd)}, {Double.isNaN(d.obsLon) ? "  GMST " : "  LST ", hmsOf((long) (lst * 3600))},
                {"  SUNDIAL ", Double.isNaN(d.obsLon) ? "--" : hmsOf((long) lat)}, {"  EOT ", String.format(Locale.US, "%+d:%02d", (int) eot, (int) Math.abs(Math.round((eot - (int) eot) * 60)))}};
        kvLine(c, r1, y); kvLine(c, r2, y + 40 * Math.max(1f, ts));
    }

    private static String hmsOf(long sec) {
        sec = ((sec % 86400) + 86400) % 86400;
        return String.format(Locale.US, "%02d:%02d:%02d", sec / 3600, (sec / 60) % 60, sec % 60);
    }

    private void kvLine(Canvas c, String[][] kv, float y) {
        float w = 0;
        for (String[] e : kv) w += tw(e[0], 22) + tw(e[1], 22);
        float x = 720 - w / 2;
        for (String[] e : kv) { x = text(c, e[0], x, y, COMMENT, 22); x = text(c, e[1], x, y, alpha(FG, 0.85f), 22); }
    }

    /** thin outer band: day / civil / nautical / astronomical / dark, same 24 h mapping as the day ring */
    private void twilightRing(Canvas c, Data d, float cx, float cy, float r) {
        if (d.tw0 == null && d.tw1 == null) return;
        Calendar n = Calendar.getInstance(); n.setTimeInMillis(d.wallMs);
        n.set(Calendar.HOUR_OF_DAY, 0); n.set(Calendar.MINUTE, 0); n.set(Calendar.SECOND, 0); n.set(Calendar.MILLISECOND, 0);
        long d0 = n.getTimeInMillis(), d1 = d0 + 86400000L;
        oval.set((cx - r) * s, (cy - r) * s, (cx + r) * s, (cy + r) * s);
        stroke(alpha(YELLOW, 0.35f), 4); c.drawCircle(cx * s, cy * s, r * s, p);       // day by default
        int[] col = {alpha(ORANGE, 0.7f), alpha(MAGENTA, 0.7f), alpha(NAUT, 0.95f), alpha(BLUE, 0.95f)};
        for (long[] t : new long[][]{d.tw0, d.tw1}) {
            if (t == null) continue;
            for (int i = 0; i < 4; i++) {
                long a = t[i], b = t[7 - i];
                if (a == 0 && b == 0) continue;                       // never reaches this depth
                if (a == 0) a = t[0] > 0 ? t[0] : b - 1;
                if (b == 0) b = t[7] > 0 ? t[7] : a + 1;
                a = Math.max(a, d0); b = Math.min(b, d1);
                if (b <= a) continue;
                float sa = 90 + (a - d0) / 86400000f * 360, sw = (b - a) / 86400000f * 360;
                stroke(col[i], i == 3 ? 8 : 6); c.drawArc(oval, sa, sw, false, p);
            }
        }
    }

    private void calTile(Data d) {
        if (d.calN == 0) { addT("CALENDAR", d.calState != null ? d.calState : "NO EVENTS NEXT 48H", d.calState != null && d.calState.startsWith("GRANT") ? YELLOW : COMMENT); return; }
        long b = d.calB[0], e = d.calE[0];
        String lab; int col = FG;
        if (d.calAll[0]) lab = "TODAY · ALL DAY";
        else if (b <= d.wallMs) { lab = "NOW · ENDS " + hm(d, e); col = CYAN; }
        else {
            long m = (b - d.wallMs) / 60000;
            lab = "NEXT · " + (m < 60 ? "IN " + m + "M" : m < 24 * 60 && sameDay(d.wallMs, b) ? hm(d, b) : "TOMORROW " + hm(d, b));
            if (m <= 15) col = CYAN;
        }
        addT(lab, d.calT[0] == null || d.calT[0].isEmpty() ? "(NO TITLE)" : d.calT[0].toUpperCase(Locale.US), col);
    }

    /** inner ring: fills from midnight (bottom, clockwise like the home dial), sunrise/sunset ticks, now dot */
    private void dayRing(Canvas c, Data d, float cx, float cy, float r, int col) {
        Calendar n = Calendar.getInstance(); n.setTimeInMillis(d.wallMs);
        float f = (n.get(Calendar.HOUR_OF_DAY) * 3600 + n.get(Calendar.MINUTE) * 60 + n.get(Calendar.SECOND)) / 86400f;
        oval.set((cx - r) * s, (cy - r) * s, (cx + r) * s, (cy + r) * s);
        stroke(LINE, 6); c.drawCircle(cx * s, cy * s, r * s, p);
        stroke(alpha(col, 0.85f), 6); c.drawArc(oval, 90, f * 360, false, p);
        if (!Double.isNaN(d.obsLat)) {
            astroCache(d);
            long[] ev = {sRise, sSet}; int[] ec = {YELLOW, ORANGE};
            for (int i = 0; i < 2; i++) {
                if (ev[i] <= 0) continue;
                Calendar e = Calendar.getInstance(); e.setTimeInMillis(ev[i]);
                double t = Math.toRadians((e.get(Calendar.HOUR_OF_DAY) + e.get(Calendar.MINUTE) / 60.0) * 15 + 180);
                stroke(ec[i], 4);
                ln(c, cx + (float) Math.sin(t) * (r - 14), cy - (float) Math.cos(t) * (r - 14), cx + (float) Math.sin(t) * (r + 14), cy - (float) Math.cos(t) * (r + 14));
            }
        }
        double t = Math.toRadians(f * 360 + 180);
        fill(FG); c.drawCircle((cx + (float) Math.sin(t) * r) * s, (cy - (float) Math.cos(t) * r) * s, 7 * s, p);
    }

    /** outer ring lit in proportion to lunar illumination: right side while waxing, left while waning */
    private void moonRing(Canvas c, Data d, float cx, float cy, float r) {
        double[] ph = Astro.moonPhase(d.wallMs);
        float k = (float) ph[0]; boolean wax = ph[1] > 0.5;
        oval.set((cx - r) * s, (cy - r) * s, (cx + r) * s, (cy + r) * s);
        stroke(LINE, 2); c.drawCircle(cx * s, cy * s, r * s, p);
        float centre = wax ? 0 : 180, sweep = k * 360;
        g.setColor(alpha(FG, 0.5f)); g.setStrokeWidth(8 * s); c.drawArc(oval, centre - sweep / 2, sweep, false, g);
        stroke(FG, 5); c.drawArc(oval, centre - sweep / 2, sweep, false, p);
        double t = Math.toRadians(wax ? 90 + 14 : 270 - 14);      // icon just below the 3 / 9 o'clock tick
        moonIcon(c, cx + (float) Math.sin(t) * (r + 34), cy - (float) Math.cos(t) * (r + 34), 11, k, wax);
    }

    private void calRow(Canvas c, Data d, float xa, float xb, float y) {
        if (d.calN == 0) { tile(c, "CALENDAR", d.calState != null ? d.calState : "NO EVENTS NEXT 48H", d.calState != null && d.calState.startsWith("GRANT") ? YELLOW : COMMENT, xa, y); return; }
        for (int i = 0; i < d.calN && i < 2; i++) {
            long b = d.calB[i], e = d.calE[i];
            String lab; int col = FG;
            if (d.calAll[i]) lab = i == 0 ? "TODAY \u00B7 ALL DAY" : "THEN \u00B7 ALL DAY";
            else if (b <= d.wallMs) { lab = "NOW \u00B7 ENDS " + hm(d, e); col = CYAN; }
            else {
                long m = (b - d.wallMs) / 60000;
                String when = m < 60 ? "IN " + m + "M" : m < 24 * 60 && sameDay(d.wallMs, b) ? hm(d, b) : "TOMORROW " + hm(d, b);
                lab = (i == 0 ? "NEXT \u00B7 " : "THEN \u00B7 ") + when;
                if (m <= 15) col = CYAN;
            }
            tile(c, lab, d.calT[i] == null || d.calT[i].isEmpty() ? "(NO TITLE)" : d.calT[i].toUpperCase(Locale.US), col, i == 0 ? xa : xb, y);
        }
    }

    private static boolean sameDay(long a, long b) {
        Calendar x = Calendar.getInstance(), y = Calendar.getInstance(); x.setTimeInMillis(a); y.setTimeInMillis(b);
        return x.get(Calendar.YEAR) == y.get(Calendar.YEAR) && x.get(Calendar.DAY_OF_YEAR) == y.get(Calendar.DAY_OF_YEAR);
    }

    private void tile(Canvas c, String label, String value, int col, float x, float y) {
        text(c, fit(label, 22, 560), x, y, COMMENT, 22);
        text(c, fit(value, 30, 560), x, y + 42, col, 30);
    }

    private static String ago(long ms) {
        long m = Math.max(0, ms / 60000);
        if (m < 60) return m + "M";
        if (m < 1440) return String.format(Locale.US, "%dH %02dM", m / 60, m % 60);
        return (m / 1440) + "D";
    }

    /** "BUDS3 38·46%" when a device reports two halves (L/R earbuds) */
    private String btLine(Data d, int i) {
        String nm = d.btName[i] == null ? "DEVICE" : d.btName[i].toUpperCase(Locale.US);
        String lv = d.btLvl[i] < 0 ? "--" : d.btLvl2[i] >= 0 ? d.btLvl[i] + "·" + d.btLvl2[i] + "%" : d.btLvl[i] + "%";
        return fit(nm, 30, 560 - tw(" " + lv, 30)) + " " + lv;
    }

    private void spectrumRing(Canvas c, float cx, float cy, float r0, float L, Data d) {
        int B = d.spec.length;
        for (int k = 0; k < 2 * B; k++) {
            int b = k < B ? k : 2 * B - 1 - k;
            float v = d.spec[b];
            double a = -Math.PI / 2 + (k + 0.5) * Math.PI * 2 / (2 * B);
            float ca = (float) Math.cos(a), sa = (float) Math.sin(a), len = 4 + v * L;
            stroke(alpha(bandCol(b / (float) (B - 1)), 0.9f), 8);
            ln(c, cx + ca * r0, cy + sa * r0, cx + ca * (r0 + len), cy + sa * (r0 + len));
        }
    }

    private void unlockScene(Canvas c, long clock, float u, Data d) {
        float cx = 720, cy = LOCK_CY, R = LOCK_R;
        frame(c, u < 700 ? RED : GREEN);

        float ringA = 1 - seg(u, 160, 380);
        if (ringA > 0) {
            ring(c, cx, cy, R * (1 - 0.25f * seg(u, 160, 380)), clock / 40f + u * 0.9f, RED, ringA);
            padlock(c, cx, cy - 12, alpha(FG, ringA), ease(seg(u, 0, 160)), LOCK_PK);
        }

        float hA = seg(u, 0, 80) * (1 - seg(u, 900, 1000));
        if (hA > 0) {
            boolean done = u >= 650;
            String head = done ? "ICE BREACH // COMPLETE" : "ICE BREACH // IN PROGRESS";
            textC(c, decode(head, seg(u, done ? 650 : 0, done ? 780 : 250), (long) (u / 40)), cx, cy - 470,
                    alpha(done ? GREEN : RED, hA), 34);
        }

        float mIn = seg(u, 150, 420), mOut = 1 - seg(u, 720, 900);
        if (mIn > 0 && mOut > 0) matrix(c, cx, cy, u, mIn, mOut);

        float gIn = seg(u, 620, 900), gOut = 1 - seg(u, 1050, 1250);
        if (gIn > 0 && gOut > 0) {
            String t = decode("ACCESS GRANTED", gIn, (long) (u / 30));
            float off = 22 * (1 - ease(seg(u, 620, 950))), size = 92;
            float x = cx - tw(t, size) / 2, y = cy + 30;
            text(c, t, x + off, y, alpha(RED, 0.7f * gOut), size);
            text(c, t, x - off, y, alpha(CYAN, 0.7f * gOut), size);
            text(c, t, x, y, alpha(GREEN, gOut), size);
            textC(c, decode("NEURAL HANDSHAKE OK · WELCOME BACK", seg(u, 760, 1000), (long) (u / 30) + 7),
                    cx, y + 80, alpha(COMMENT, gOut), 28);
        }

        float wipe = ease(seg(u, 950, UNLOCK_MS));
        if (wipe > 0) {
            float wy = -40 + wipe * (H + 80);
            c.save();
            c.clipRect(-20 * s, -20 * s, pw + 20 * s, wy * s);
            c.drawColor(BLACK);
            dashboard(c, d, ease(seg(u, 1000, UNLOCK_MS)), (long) (u / 45));
            c.restore();
            g.setColor(alpha(CYAN, 0.8f)); g.setStrokeWidth(10 * s);
            c.drawLine(-20 * s, wy * s, (W + 20) * s, wy * s, g);
            stroke(CYAN, 3); ln(c, -20, wy, W + 20, wy);
            stroke(alpha(RED, 0.7f), 2); ln(c, -20, wy + 8, W + 20, wy + 8);
        }
    }

    private void matrix(Canvas c, float cx, float cy, float u, float in, float out) {
        float cw = 128, chh = 96, gx = cx - 2.5f * cw, gy = cy - 2.5f * chh + 20;
        int step = (int) Math.min(5, Math.floor(seg(u, 380, 700) * 5.999f));

        text(c, "BUFFER", gx, gy - 136, alpha(YELLOW, out * in), 28);
        for (int i = 0; i < 5; i++) {
            float x0 = gx + 150 + i * 92;
            stroke(alpha(i < step ? CYAN : LINE, out * in), 2);
            rect(c, x0, gy - 176, x0 + 76, gy - 120);
            if (i < step) { int[] pc = PATH[i]; textC(c, grid[pc[0] * 5 + pc[1]], x0 + 38, gy - 136, alpha(CYAN, out), 30); }
        }
        if (step < 5) {
            fill(alpha(LINE, 0.9f * out * in));
            if (step % 2 == 0) { float ry = gy + PATH[step][0] * chh; rect(c, gx, ry, gx + 5 * cw, ry + chh); }
            else { float rx = gx + PATH[step][1] * cw; rect(c, rx, gy, rx + cw, gy + 5 * chh); }
        }
        for (int r = 0; r < 5; r++) for (int k = 0; k < 5; k++) {
            float a = seg(in, (r + k) / 10f, (r + k) / 10f + 0.2f) * out;
            if (a <= 0) continue;
            boolean sel = false;
            for (int i = 0; i < step; i++) if (PATH[i][0] == r && PATH[i][1] == k) sel = true;
            float x0 = gx + k * cw, y0 = gy + r * chh;
            if (sel) { fill(alpha(CYAN, a)); rect(c, x0 + 10, y0 + 10, x0 + cw - 10, y0 + chh - 10); }
            textC(c, grid[r * 5 + k], x0 + cw / 2, y0 + chh / 2 + 14, alpha(sel ? BLACK : FG, sel ? 1f : a), 40);
        }
        float py = gy + 5 * chh + 30;
        stroke(alpha(LINE, out), 2); rect(c, gx, py, gx + 5 * cw, py + 14);
        fill(alpha(CYAN, out)); rect(c, gx, py, gx + 5 * cw * seg(u, 380, 720), py + 14);
        text(c, "INJECTING KEY SEQUENCE", gx, py + 54, alpha(COMMENT, out), 26);
    }

    // ---------------------------------------------------------------- modules
    /** reorderable content modules (clock + weather form the fixed header) and their heights */
    public static final int[] DEF_ORDER = {6, 9, 10, 2, 3, 4, 5, 8};
    static float modH(int m) { switch (m) { case 6: return 150; case 9: return 180; case 2: return 720; case 3: return 570; case 4: return 670; case 5: return 640; case 10: return 440; default: return 150; } }
    /** order with unknown ids dropped and missing ones appended */
    public static int[] cleanOrder(int[] o, int[] def) {
        int[] out = new int[def.length]; int n = 0;
        if (o != null) for (int m : o) { boolean ok = false, dup = false; for (int x : def) ok |= x == m; for (int i = 0; i < n; i++) dup |= out[i] == m; if (ok && !dup) out[n++] = m; }
        for (int x : def) { boolean have = false; for (int i = 0; i < n; i++) have |= out[i] == x; if (!have) out[n++] = x; }
        return out;
    }
    public static final String[] LK_NAME = {"Weather", "Precipitation, next 2 h", "Aurora verdict", "Darkness window", "Sunrise / sunset", "Moon phase & distance",
            "ISS passes", "Tiangong passes", "Meteor showers", "Starlink trains (when visible)", "Next calendar event", "Linked devices", "Steps & unlocks"};
    public static final int[] LK_DEF = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};
    /** weather tile off by default: the lock header shows it */
    public static final boolean[] LK_ON_DEF = {false, true, true, true, true, true, true, true, true, true, true, true, true};

    public static final int MOD_CLOCK = 0, MOD_WX = 1, MOD_DEV = 2, MOD_SKY = 3, MOD_SEN = 4, MOD_CAL = 5, MOD_SEARCH = 6,
            MOD_TODO = 7, MOD_NOTES = 8, MOD_MUSIC = 9, MOD_WIDGET = 10, NMOD = 11;
    static final String[] MOD_TAB = {"MIN", "MIN", "DEV", "SKY", "SEN", "CAL", "SRCH", "TODO", "NOTE", "MEDIA", "WDGT"};
    private boolean hClock, hCal;
    private float hCalY0, hCalY1, tabY0, tabY1;

    private void dashboard(Canvas c, Data d, float dec, long seed) {
        wgShown = false;
        frame(c, BLUE);
        float x0 = M + 40, xr = W - M - 40;
        hSky = false; hDial = false; hMotion = false; hClock = false; hCal = false; tabN = 0; cdy = 0;
        taps.clear(); tapArgs.clear(); tdy = 0;
        d.labShown = false;
        int[] vt = new int[3];
        int nt = d.visibleTabs(vt);
        boolean tabbed = d.splitPages && nt >= 1;
        int pg = Math.max(0, Math.min(nt - 1, d.page));
        boolean[] on = tabbed ? d.mods[1 + vt[pg]] : d.mods[0];
        float pdec = Math.min(dec, d.pageAnim);

        float y = 110;
        if (on[MOD_CLOCK] || on[MOD_WX]) {
            if (on[MOD_CLOCK]) { tap(x0, 110, 840, 470, A_CLOCKAPP, 0); clockBlock(c, d, dec, seed); hClock = true; }
            if (on[MOD_WX]) { tap(xr - 430, 140, xr, 460, A_WX, 0); weatherBlock(c, d, dec, seed); }
            stroke(LINE, 2); ln(c, x0, 500, xr, 500);
            y = 500;
        }
        if (tabbed && nt >= 2) {                               // tab bar
            float bw = 150, gap = 16, x1 = xr, t0 = y + 14, t1 = y + 64;
            for (int i = nt - 1; i >= 0; i--) {
                boolean act = i == pg;
                float a0 = x1 - bw;
                if (act) fill(BLUE); else stroke(LINE, 2);
                poly(c, true, a0 + 12, t0, x1, t0, x1, t1 - 12, x1 - 12, t1, a0, t1, a0, t0 + 12);
                textC(c, tabLabel(d, vt[i]), a0 + bw / 2, t1 - 16, act ? BLACK : COMMENT, 24);
                tabX0[i] = a0; tabX1[i] = x1;
                tap(a0, t0, x1, t1, HIT_TAB0 + i, 0);
                x1 = a0 - gap;
            }
            tabN = nt; tabY0 = t0; tabY1 = t1;
            if (d.virtualPages) text(c, "TAP TO SWITCH VIEW", x0, t1 - 16, LINE, 20);
            y += 70;
        }
        // content modules, stacked top to bottom; whatever doesn't fit above the dock is counted, not drawn
        float limit = d.noDock ? H - M - 262 : H - 400;
        if (d.scOn && d.scN > 0) {                               // shortcut row sits between the modules and the dock / readouts
            float sy1 = limit - 10, sy0 = sy1 - 96;
            shortcutRow(c, d, sy0, sy1);
            limit = sy0 - 30;
        }
        int[] order = cleanOrder(d.order[tabbed ? 1 + vt[pg] : 0], DEF_ORDER);
        int hidden = 0;
        for (int k = 0; k < order.length; k++) {
            int m = order[k];
            if (!on[m]) continue;
            float hk = modH(m);
            if (y + hk - 30 > limit) { hidden++; continue; }
            if (m == MOD_SEARCH) searchBlock(c, y);
            else if (m == MOD_MUSIC) musicBlock(c, d, y);
            else if (m == MOD_NOTES) notesBlock(c, y);
            else if (m == MOD_WIDGET) widgetBlock(c, d, y, y + hk - 30);
            else if (m == MOD_DEV) { c.save(); c.translate(0, (y - 500) * s); tdy = y - 500; deviceBlock(c, d, pdec, seed + 10); tdy = 0; c.restore(); }
            else if (m == MOD_SKY) sky(c, d, pdec, seed + 60, y + 70);
            else if (m == MOD_SEN) { lab(c, d, pdec, seed + 70, y + 70); d.labShown = true; }
            else calendar(c, d, pdec, seed + 90, y);
            y += hk;
        }
        if (hidden > 0) text(c, "+" + hidden + " MODULE" + (hidden > 1 ? "S" : "") + " HIDDEN · NO ROOM · USE TABS OR TURN SOME OFF IN THE APP", x0, y + 10, COMMENT, 22);
        if (d.noDock) {
            boolean off = d.net == null || "OFFLINE".equals(d.net);
            readouts(c, off ? "SYS//OFFLINE" : "SYS//ONLINE", "UNIT " + d.model + " · LINK ", (off ? "OFFLINE" : d.net) + (d.vpn ? "+VPN" : ""), off ? RED : GREEN, false, dec, seed + 300);
            String hint = "\u2191 SWIPE UP · APPS";
            textR(c, hint, xr, H - M - 122, LINE, 22);
        }
    }

    // ---- hosted widget slot: the launcher places a real app widget over this frame (rect handed over in pixels)
    public int wgL, wgT, wgR, wgB; public boolean wgShown;

    private void widgetBlock(Canvas c, Data d, float y0, float y1) {
        float x0 = M + 40, xr = W - M - 40, b0 = y0 + 60;
        sectionTitle(c, "// WIDGET", d.widgetLabel, y0);
        stroke(LINE, 2); box(c, x0, b0, xr, y1);
        if (!d.launcherMode) textC(c, "NEEDS THE TN-HUD LAUNCHER", (x0 + xr) / 2, (b0 + y1) / 2 + 8, COMMENT, 22);
        else if (d.widgetLabel == null) textC(c, "LONG-PRESS HOME \u203A CHOOSE WIDGET", (x0 + xr) / 2, (b0 + y1) / 2 + 8, COMMENT, 22);
        wgL = Math.round((x0 + 6) * s); wgT = Math.round((b0 + 6) * s); wgR = Math.round((xr - 6) * s); wgB = Math.round((y1 - 6) * s);
        wgShown = true;
    }

    /** [01] WHATSAPP-style text blocks, equal width, single tap opens the app */
    private void shortcutRow(Canvas c, Data d, float y0, float y1) {
        float x0 = M + 40, xr = W - M - 40, gap = 16, n = d.scN, w = (xr - x0 - (n - 1) * gap) / n;
        for (int i = 0; i < d.scN; i++) {
            float a = x0 + i * (w + gap), b = a + w;
            boolean ok = d.scOk[i];
            fill(alpha(ok ? BLUE : LINE, ok ? 0.14f : 0.3f)); box(c, a, y0, b, y1);
            stroke(ok ? BLUE : LINE, 2); box(c, a, y0, b, y1);
            text(c, String.format(Locale.US, "%02d", i + 1), a + 18, y0 + 26, ok ? CYAN : COMMENT, 16);
            String l = d.scLabel[i] == null ? "--" : d.scLabel[i];
            textC(c, fit(l, 26, w - 36), (a + b) / 2, y1 - 26, ok ? FG : COMMENT, 26);
            fill(ok ? BLUE : LINE); rect(c, b - 34, y0 + 14, b - 18, y0 + 18);         // HUD tick
            tap(a, y0, b, y1, A_SHORTCUT, i);
        }
    }

    // ---------------------------------------------------------------- media, to-do, notes
    private void sectionTitle(Canvas c, String t, String sub, float y) {
        float x0 = M + 40, xr = W - M - 40;
        text(c, t, x0, y + 30, BLUE, 24);
        if (sub != null) text(c, " · " + sub, x0 + tw(t, 24), y + 30, COMMENT, 24);
        stroke(LINE, 2); ln(c, x0, y + 44, xr, y + 44);
    }

    private void box(Canvas c, float x0, float y0, float x1, float y1) {
        poly(c, true, x0 + 18, y0, x1, y0, x1, y1 - 18, x1 - 18, y1, x0, y1, x0, y0 + 18);
    }

    /** media: what's playing (when the player broadcasts it) + prev / play-pause / next */
    private void musicBlock(Canvas c, Data d, float y) {
        float x0 = M + 40, xr = W - M - 40, b0 = y + 60, b1 = y + 160;
        sectionTitle(c, "// MEDIA", d.mApp != null ? d.mApp : null, y);
        stroke(LINE, 2); box(c, x0, b0, xr, b1);
        float bx = xr - 3 * 100 - 10;
        String t = d.mTitle != null ? d.mTitle.toUpperCase(Locale.US) : d.mPlaying ? "PLAYING" : "NOTHING PLAYING";
        text(c, fit(t, 30, bx - x0 - 60), x0 + 28, b0 + 44, d.mTitle != null ? FG : d.mPlaying ? CYAN : COMMENT, 30);
        String a = d.mArtist != null ? d.mArtist.toUpperCase(Locale.US) : d.mPlaying && d.mTitle == null ? "PLAYER SENDS NO TRACK INFO" : "";
        text(c, fit(a, 22, bx - x0 - 60), x0 + 28, b0 + 78, COMMENT, 22);
        tap(x0, b0, bx - 10, b1, A_MUSIC_APP, 0);
        int[] acts = {A_MEDIA_PREV, A_MEDIA_PLAY, A_MEDIA_NEXT};
        for (int i = 0; i < 3; i++) {
            float cx = bx + 50 + i * 100, cy = (b0 + b1) / 2;
            if (i == 1) { fill(d.mPlaying ? CYAN : BLUE); c.drawCircle(cx * s, cy * s, 34 * s, p); }
            else { stroke(LINE, 2); c.drawCircle(cx * s, cy * s, 30 * s, p); }
            int gc = i == 1 ? BLACK : FG;
            fill(gc);
            if (i == 0) { poly(c, true, cx + 12, cy - 13, cx + 12, cy + 13, cx - 6, cy); rect(c, cx - 12, cy - 13, cx - 7, cy + 13); }
            else if (i == 2) { poly(c, true, cx - 12, cy - 13, cx - 12, cy + 13, cx + 6, cy); rect(c, cx + 7, cy - 13, cx + 12, cy + 13); }
            else if (d.mPlaying) { rect(c, cx - 11, cy - 14, cx - 3, cy + 14); rect(c, cx + 3, cy - 14, cx + 11, cy + 14); }
            else poly(c, true, cx - 8, cy - 15, cx - 8, cy + 15, cx + 15, cy);
            tap(cx - 48, b0, cx + 48, b1, acts[i], 0);
        }
    }

    /** notes: shortcut buttons (Samsung Notes and Keep don't expose note content to other apps) */
    private void notesBlock(Canvas c, float y) {
        float x0 = M + 40, xr = W - M - 40, b0 = y + 62, b1 = y + 122, gap = 16;
        sectionTitle(c, "// NOTES", null, y);
        String[] lb = {"+ SAMSUNG NOTE", "+ KEEP NOTE", "SAMSUNG NOTES", "KEEP"};
        int[] ac = {A_NOTE_NEW, A_KEEP_NEW, A_NOTES_APP, A_KEEP_APP};
        float w = (xr - x0 - 3 * gap) / 4;
        for (int i = 0; i < 4; i++) {
            float a = x0 + i * (w + gap);
            if (i < 2) fill(alpha(BLUE, 0.18f)); else fill(BLACK);
            box(c, a, b0, a + w, b1);
            stroke(i < 2 ? BLUE : LINE, 2); box(c, a, b0, a + w, b1);
            textC(c, fit(lb[i], 20, w - 20), a + w / 2, b0 + 38, i < 2 ? FG : COMMENT, 20);
            tap(a, b0, a + w, b1, ac[i], 0);
        }
    }

    /** search bar: opens Google search; mic opens voice search */
    private void searchBlock(Canvas c, float y) {
        float x0 = M + 40, xr = W - M - 40, t0 = y + 30, t1 = y + 110;
        stroke(LINE, 2);
        poly(c, true, x0 + 20, t0, xr, t0, xr, t1 - 20, xr - 20, t1, x0, t1, x0, t0 + 20);
        stroke(CYAN, 3); c.drawCircle((x0 + 50) * s, (t0 + 36) * s, 14 * s, p); ln(c, x0 + 60, t0 + 46, x0 + 72, t0 + 58);
        float ex = text(c, "// SEARCH", x0 + 96, t0 + 50, COMMENT, 28);
        fill(CYAN); rect(c, ex + 10, t0 + 26, ex + 26, t0 + 54);
        float mx = xr - 60, my = t0 + 40;
        stroke(MAGENTA, 3);
        rect(c, mx - 9, my - 22, mx + 9, my + 6);
        oval.set((mx - 18) * s, (my - 14) * s, (mx + 18) * s, (my + 16) * s); c.drawArc(oval, 0, 180, false, p);
        ln(c, mx, my + 16, mx, my + 24);
        tap(x0, t0, xr - 120, t1, A_SEARCH, 0);
        tap(xr - 120, t0, xr, t1, A_VOICE, 0);
    }

    private String tabLabel(Data d, int tab) {
        boolean[] m = d.mods[1 + tab];
        int first = -1, n = 0;
        for (int i = MOD_DEV; i < NMOD; i++) if (m[i] && i != MOD_SEARCH && i != MOD_TODO) { if (first < 0) first = i; n++; }
        return first < 0 ? "MIN" : MOD_TAB[first] + (n > 1 ? "+" : "");
    }

    private void clockBlock(Canvas c, Data d, float dec, long seed) {
        float x0 = M + 40, xr = W - M - 40;
        // ---- clock / date / alarm (left)
        Calendar cal = d.clockUtc ? Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")) : Calendar.getInstance();
        cal.setTimeInMillis(d.wallMs);
        int hh = cal.get(Calendar.HOUR_OF_DAY), mm = cal.get(Calendar.MINUTE), ss = cal.get(Calendar.SECOND);
        String hm = d.h24 ? String.format(Locale.US, "%02d:%02d", hh, mm)
                : String.format(Locale.US, "%d:%02d", (hh + 11) % 12 + 1, mm);
        float cx = holo(c, decode(hm, dec, seed), x0 - 8, 334, 236, FG, CYAN, CYAN);
        holo(c, String.format(Locale.US, ":%02d", ss), cx + 4, 334, 66, CYAN, BLUE, BLUE);
        if (!d.h24 && !d.clockUtc) textF(c, hh < 12 ? "AM" : "PM", cx + 12, 258, COMMENT, 38);
        if (d.clockUtc) textF(c, "UTC", cx + 12, 258, MAGENTA, 38);
        String date = (d.clockUtc ? "UTC " : "") + String.format(Locale.US, "%ta %<td %<tb", cal).toUpperCase(Locale.US)
                + String.format(Locale.US, " · WK %02d", cal.get(Calendar.WEEK_OF_YEAR));
        float de = textF(c, decode(date, dec, seed + 1), x0, 402, FG, 38);
        float uw = tw("UTC", 20) + 28;
        chipR(c, "UTC", de + 20 + uw, 398, d.clockUtc, A_UTC, 0);
        fill(YELLOW); poly(c, true, x0 + 8, 432, x0 + 18, 442, x0 + 8, 452, x0 - 2, 442);
        textF(c, decode(d.alarm != null ? d.alarm : "NO SCHEDULED OPS", dec, seed + 2), x0 + 30, 452, d.alarm != null ? YELLOW : COMMENT, 30);

    }

    private void weatherBlock(Canvas c, Data d, float dec, long seed) {
        float x0 = M + 40, xr = W - M - 40;
        if (d.wallMs < d.wxHourlyUntil && d.hrT != null) { hourlyStrip(c, d, xr - 430, xr); return; }
        // ---- weather (right)
        textR(c, "// WX · " + (d.wxCity != null ? d.wxCity : "NO LOCATION"), xr, 170, COMMENT, 24);
        if (!Float.isNaN(d.wxTemp)) {
            int tcol = d.wxTemp <= 0 ? CYAN : d.wxTemp >= 25 ? YELLOW : FG;
            textFR(c, decode(String.format(Locale.US, "%.0f°", d.wxTemp), dec, seed + 3), xr, 322, tcol, 168);
            textR(c, decode(wmo(d.wxCode), dec, seed + 4), xr, 372, CYAN, 30);
            String sub = (Float.isNaN(d.wxHi) ? "" : String.format(Locale.US, "H %.0f° L %.0f°", d.wxHi, d.wxLo))
                    + (Float.isNaN(d.wxWind) ? "" : String.format(Locale.US, " · %.0f M/S", d.wxWind))
                    + (d.wxRh < 0 ? "" : String.format(Locale.US, " · %d%%", d.wxRh));
            textR(c, decode(sub, dec, seed + 5), xr, 412, COMMENT, 24);
            Calendar u = Calendar.getInstance(); u.setTimeInMillis(d.wxUpd);
            textR(c, String.format(Locale.US, "UPD %02d:%02d%s", u.get(Calendar.HOUR_OF_DAY), u.get(Calendar.MINUTE), d.wxErr ? " · STALE" : ""),
                    xr, 448, d.wxErr ? RED : COMMENT, 20);
        } else {
            textFR(c, "--°", xr, 322, COMMENT, 168);
            textR(c, d.wxCity == null ? "SET CITY IN TN-HUD APP" : d.wxErr ? "WX LINK DOWN" : "ACQUIRING", xr, 372, d.wxErr ? RED : YELLOW, 26);
        }
    }

    // ---- tapped sky object: rise / highest / set over the next 24 h, current position
    private int siBody = -99; private long siAt; private long[] siEv = new long[3]; private double siMax;

    private void skyInfo(Canvas c, Data d, int body, float xa, float y0, float xb, float y1) {
        double la = d.obsLat, lo = d.obsLon;
        long now = d.wallMs;
        if (body != siBody || now - siAt > 300000L) {
            siBody = body; siAt = now;
            double h0 = body == Astro.MOON ? 0.125 : -0.5667;
            siEv[0] = Astro.crossing(body, now, now + 86400000L, la, lo, h0, true, 600000L);
            siEv[2] = Astro.crossing(body, now, now + 86400000L, la, lo, h0, false, 600000L);
            long bt = 0; double bm = -99;
            for (long t = now; t < now + 86400000L; t += 600000L) { double a = Astro.pos(body, t, la, lo)[0]; if (a > bm) { bm = a; bt = t; } }
            for (long t = bt - 600000L; t <= bt + 600000L; t += 60000L) { double a = Astro.pos(body, t, la, lo)[0]; if (a > bm) { bm = a; bt = t; } }
            siEv[1] = bt; siMax = bm;
        }
        fill(BLACK); rect(c, xa - 10, y0 + 30, xb - 20, y1 - 30);
        String name = body == Astro.MOON ? "MOON" : Astro.PNAME[body];
        double[] q = Astro.pos(body, now, la, lo);
        float y = y0 + 80, lw = 150, vmax = xb - 30 - xa - lw;
        text(c, "// " + name, xa, y0 + 70, CYAN, 26);
        textR(c, "TAP AGAIN TO CLOSE", xb - 30, y0 + 70, COMMENT, 18);
        y += 40;
        String[] card = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        y = row(c, "NOW", String.format(Locale.US, "ALT %+.0f° · AZ %.0f° %s", q[0], q[1], card[(int) Math.round(q[1] / 45) % 8]), q[0] > 0 ? CYAN : COMMENT, xa, y, lw, vmax, 1f, 0);
        y = row(c, "RISES", siEv[0] > 0 ? dayHm(d, siEv[0]) : q[0] > 0 ? "UP ALL DAY" : "DOES NOT RISE", FG, xa, y, lw, vmax, 1f, 0);
        y = row(c, "HIGHEST", siMax > -90 ? String.format(Locale.US, "%s · %.0f°", dayHm(d, siEv[1]), siMax) : "--", siMax > 0 ? YELLOW : COMMENT, xa, y, lw, vmax, 1f, 0);
        y = row(c, "SETS", siEv[2] > 0 ? dayHm(d, siEv[2]) : q[0] > 0 ? "DOES NOT SET" : "--", FG, xa, y, lw, vmax, 1f, 0);
        if (body == Astro.MOON) {
            double[] ph = Astro.moonPhase(now);
            row(c, "LIT", String.format(Locale.US, "%.0f%% · %.0fK KM", ph[0] * 100, Astro.moonDistKm(now) / 1000), FG, xa, y, lw, vmax, 1f, 0);
        } else {
            double[] sn = Astro.pos(Astro.SUN, now, la, lo);
            double a1 = Math.toRadians(q[0]), a2 = Math.toRadians(sn[0]), dz = Math.toRadians(q[1] - sn[1]);
            double el = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, Math.sin(a1) * Math.sin(a2) + Math.cos(a1) * Math.cos(a2) * Math.cos(dz)))));
            row(c, "FROM SUN", String.format(Locale.US, "%.0f°%s", el, el < 15 ? " · LOST IN THE GLARE" : ""), el < 15 ? COMMENT : FG, xa, y, lw, vmax, 1f, 0);
        }
    }

    private String dayHm(Data d, long t) {
        boolean tomorrow = dayStart(t) != dayStart(d.wallMs);
        return (tomorrow ? "TMRW " : "") + hm(d, t);
    }

    /** next 12 h: temperature line with values every 3 h, precipitation probability bars (tap the weather again to close) */
    private void hourlyStrip(Canvas c, Data d, float xa, float xb) {
        textR(c, "// WX · NEXT 12 H", xb, 170, COMMENT, 24);
        long t0 = d.wallMs - d.wallMs % 3600000L;
        int i0 = -1;
        for (int i = 0; i < d.hrT.length; i++) if (d.hrT[i] >= t0) { i0 = i; break; }
        if (i0 < 0) { textR(c, "NO HOURLY DATA", xb, 300, COMMENT, 24); return; }
        int n = Math.min(12, d.hrT.length - i0);
        if (n < 2) { textR(c, "NO HOURLY DATA", xb, 300, COMMENT, 24); return; }
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        for (int i = 0; i < n; i++) { float v = d.hrTemp[i0 + i]; if (!Float.isNaN(v)) { lo = Math.min(lo, v); hi = Math.max(hi, v); } }
        if (hi - lo < 4) { float m = (hi + lo) / 2; lo = m - 2; hi = m + 2; }
        float y0 = 220, y1 = 330, py0 = 350, py1 = 410, cw = (xb - xa) / n;
        for (int i = 0; i < n; i++) {
            float pop = d.hrPop == null ? Float.NaN : d.hrPop[i0 + i], x = xa + i * cw;
            if (!Float.isNaN(pop)) {
                fill(alpha(CYAN, 0.25f + 0.5f * pop / 100f)); rect(c, x + 4, py1 - (py1 - py0) * pop / 100f, x + cw - 4, py1);
            }
            stroke(LINE, 1); ln(c, x + 4, py1, x + cw - 4, py1);
        }
        path.reset(); boolean st = false;
        for (int i = 0; i < n; i++) {
            float v = d.hrTemp[i0 + i];
            if (Float.isNaN(v)) continue;
            float x = xa + (i + 0.5f) * cw, y = y1 - (v - lo) / (hi - lo) * (y1 - y0);
            if (!st) { path.moveTo(x * s, y * s); st = true; } else path.lineTo(x * s, y * s);
            if (i % 3 == 0) {
                fill(FG); c.drawCircle(x * s, y * s, 5 * s, p);
                textC(c, String.format(Locale.US, "%.0f°", v), x, y - 14, v <= 0 ? CYAN : FG, 24);
                Calendar h = Calendar.getInstance(); h.setTimeInMillis(d.hrT[i0 + i]);
                textC(c, String.format(Locale.US, "%02d", h.get(Calendar.HOUR_OF_DAY)), x, 448, COMMENT, 20);
            }
        }
        stroke(BLUE, 3); c.drawPath(path, p);
        text(c, "RAIN %", xa, py0 - 6, COMMENT, 16);
    }

    /** device block, drawn in its original coordinates (top = 500) */
    private void deviceBlock(Canvas c, Data d, float dec, long seed) {
        float x0 = M + 40, xr = W - M - 40;
        tap(x0, 540, x0 + 680, 610, A_BATT, 0);
        tap(x0, 640, x0 + 680, 700, A_DEVCARE, 0);
        tap(x0, 730, x0 + 680, 790, A_STORAGE, 0);
        tap(x0, 830, x0 + 680, 1220, A_DEVCARE, 0);
        tap(800, 540, xr, 800, A_BT, 0);
        tap(800, 830, xr, 1220, A_NET, 0);
        // ---- left: bars
        float y0 = 570;
        int lvl = Math.max(0, Math.min(100, d.battery));
        int bc = d.charging ? CYAN : lvl > 50 ? GREEN : lvl > 20 ? YELLOW : RED;
        text(c, decode("CELL", dec, seed + 6), x0, y0, COMMENT, 26);
        float ex = text(c, decode(String.format(Locale.US, "%03d%%", lvl), dec, seed + 7), x0 + 90, y0, FG, 26);
        if (d.charging) text(c, decode(" CHG", dec, seed + 8), ex, y0, CYAN, 26);
        int on = Math.round(lvl / 5f * dec);
        for (int i = 0; i < 20; i++) {
            float sx = x0 + i * 32, sy = y0 + 22;
            if (i < on) fill(bc); else stroke(LINE, 2);
            poly(c, true, sx + 8, sy, sx + 30, sy, sx + 22, sy + 28, sx, sy + 28);
        }
        float ry0 = y0 + 100;
        float ramUsed = d.ramTotal > 0 ? 1f - d.ramAvail / (float) d.ramTotal : 0f;
        text(c, decode("RAM", dec, seed + 9), x0, ry0, COMMENT, 26);
        text(c, decode(d.ramTotal > 0 ? String.format(Locale.US, "%.1f / %.1fG", (d.ramTotal - d.ramAvail) / 1073741824f, d.ramTotal / 1073741824f) : "--",
                dec, seed + 10), x0 + 90, ry0, FG, 26);
        bar(c, x0, ry0 + 20, ramUsed * dec, ramUsed > 0.85f ? RED : BLUE);
        chipR(c, "CLEAN", x0 + 640, ry0, false, A_MEMCLEAN, 0);
        float my = ry0 + 90;
        float used = d.total > 0 ? 1f - d.free / (float) d.total : 0f;
        text(c, decode("STOR", dec, seed + 11), x0, my, COMMENT, 26);
        text(c, decode(String.format(Locale.US, "%02d%% FREE · %dG", Math.round((1 - used) * 100), d.total >> 30), dec, seed + 12),
                x0 + 90, my, FG, 26);
        bar(c, x0, my + 20, used * dec, used > 0.9f ? RED : MAGENTA);

        // ---- left: diagnostics
        float dy = 860;
        header(c, "// DIAGNOSTICS", x0, dy, 640);
        dy += 56;
        String[] th = {"NOMINAL", "LIGHT", "MODERATE", "SEVERE", "CRITICAL", "EMERGENCY", "SHUTDOWN"};
        int thc = d.thermal <= 0 ? GREEN : d.thermal == 1 ? YELLOW : RED;
        String therm = (d.thermal >= 0 && d.thermal < th.length ? th[d.thermal] : "--")
                + (Float.isNaN(d.tempC) ? "" : String.format(Locale.US, " · %.1f°C", d.tempC));
        dy = diag(c, "THERM", therm, d.thermal < 0 ? FG : thc, x0, dy, 150, dec, seed + 20);
        String pwr = (d.voltMv > 0 ? String.format(Locale.US, "%.2fV", d.voltMv / 1000f) : "--")
                + (d.health != null ? " · " + d.health : "") + (d.plug != null ? " · " + d.plug : "")
                + (d.saver ? " · SAVER" : "");
        dy = diag(c, "PWR", pwr, d.saver || (d.health != null && !"GOOD".equals(d.health)) ? YELLOW : FG, x0, dy, 150, dec, seed + 21);
        String aud = (d.ringer != null ? d.ringer : "--") + (d.dnd ? " · DND" : "");
        dy = diag(c, "AUDIO", aud, d.dnd || "SILENT".equals(d.ringer) ? MAGENTA : FG, x0, dy, 150, dec, seed + 23);
        if (d.uptimeMs >= 0) {
            long m = d.uptimeMs / 60000;
            dy = diag(c, "UPTIME", String.format(Locale.US, "%dD %02dH %02dM", m / 1440, (m / 60) % 24, m % 60), FG, x0, dy, 150, dec, seed + 24);
        }
        if (d.os != null) dy = diag(c, "OS", d.os, FG, x0, dy, 150, dec, seed + 25);
        if (d.patch != null) dy = diag(c, "PATCH", d.patch, FG, x0, dy, 150, dec, seed + 26);
        if (d.cpu != null) dy = diag(c, "CPU", d.cpu, FG, x0, dy, 150, dec, seed + 27);

        // ---- right: linked devices
        float xR = 800, wR = xr - xR;
        header(c, "// LINKED DEVICES", xR, 570, wR);
        float by = 626;
        if (d.btState != null) text(c, decode(d.btState, dec, seed + 30), xR, by, d.btN == 0 && d.btState.startsWith("GRANT") ? YELLOW : COMMENT, 24);
        for (int i = 0; i < d.btN && i < 4; i++) {
            String nm = d.btName[i] == null ? "DEVICE" : d.btName[i].toUpperCase(Locale.US);
            nm = fit(nm, 24, 190);
            text(c, decode(nm, dec, seed + 31 + i), xR, by, FG, 24);
            int bl = d.btLvl2[i] >= 0 ? Math.min(d.btLvl[i], d.btLvl2[i]) : d.btLvl[i];
            int col = bl < 0 ? COMMENT : bl > 50 ? GREEN : bl > 20 ? YELLOW : RED;
            int segs = bl < 0 ? 0 : Math.round(bl / 10f * dec);
            for (int k = 0; k < 10; k++) { fill(k < segs ? col : LINE); rect(c, xr - 330 + k * 22, by - 18, xr - 330 + k * 22 + 16, by - 2); }
            textR(c, d.btLvl[i] < 0 ? "--" : d.btLvl2[i] >= 0 ? d.btLvl[i] + "\u00B7" + d.btLvl2[i] + "%" : d.btLvl[i] + "%", xr, by, col, 24);
            by += 44;
        }

        // ---- right: network
        float ny = 860;
        header(c, "// NETWORK", xR, ny, wR);
        ny += 56;
        String net = (d.net != null ? d.net : "--") + (d.vpn ? " · VPN" : "");
        ny = diag(c, "LINK", net, "OFFLINE".equals(d.net) ? RED : d.vpn ? GREEN : FG, xR, ny, 130, dec, seed + 40);
        if (d.wifiRssi != Integer.MIN_VALUE || d.linkMbps > 0) {
            String wf = (d.wifiRssi != Integer.MIN_VALUE ? d.wifiRssi + " DBM" : "--") + (d.linkMbps > 0 ? " · ~" + d.linkMbps + " MBPS" : "");
            int wc = d.wifiRssi == Integer.MIN_VALUE ? FG : d.wifiRssi > -60 ? GREEN : d.wifiRssi > -75 ? YELLOW : RED;
            ny = diag(c, "SIGNAL", wf, wc, xR, ny, 130, dec, seed + 41);
        }
        if (d.carrier != null || d.cellLvl >= 0) {
            String cl = (d.carrier != null && d.carrier.length() > 0 ? d.carrier.toUpperCase(Locale.US) : "CELL")
                    + (d.cellLvl >= 0 ? " · LVL " + d.cellLvl + "/4" : "");
            ny = diag(c, "CELL", cl, d.cellLvl < 0 ? FG : d.cellLvl >= 3 ? GREEN : d.cellLvl >= 2 ? YELLOW : RED, xR, ny, 130, dec, seed + 42);
        }
        if (d.totRx >= 0) ny = diag(c, "BOOT", "↓" + bytes(d.totRx) + " ↑" + bytes(d.totTx), FG, xR, ny, 130, dec, seed + 43);
        if (d.mobRx >= 0) ny = diag(c, "MOBILE", "↓" + bytes(d.mobRx) + " ↑" + bytes(d.mobTx), FG, xR, ny, 130, dec, seed + 44);

    }

    // ---------------------------------------------------------------- calendar module
    private static final String[] WD = {"MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"};

    private static long dayStart(long ms) {
        Calendar c = Calendar.getInstance(); c.setTimeInMillis(ms);
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static long addDays(long day0, int n) {
        Calendar c = Calendar.getInstance(); c.setTimeInMillis(day0); c.add(Calendar.DAY_OF_YEAR, n); return c.getTimeInMillis();
    }

    /** Monday 00:00 of the week containing ms */
    private static long weekStart(long ms) {
        Calendar c = Calendar.getInstance(); c.setTimeInMillis(dayStart(ms));
        int dow = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7;           // Mon = 0
        c.add(Calendar.DAY_OF_YEAR, -dow);
        return c.getTimeInMillis();
    }

    private static int evColor(int c) { return (c & 0x00FFFFFF) == 0 ? 0xFF7AA2F7 : (c | 0xFF000000); }

    private void calendar(Canvas c, Data d, float dec, long seed, float y) {
        float x0 = M + 40, xr = W - M - 40, py = y + 70, ph = 520;
        long now = d.wallMs, today = dayStart(now);
        Calendar cn = Calendar.getInstance(); cn.setTimeInMillis(now);
        String sub = d.calView == 0 ? String.format(Locale.US, "DAY · %ta %<td %<tb", cn).toUpperCase(Locale.US)
                : d.calView == 1 ? String.format(Locale.US, "WEEK %02d", cn.get(Calendar.WEEK_OF_YEAR))
                : String.format(Locale.US, "%tB %<tY", cn).toUpperCase(Locale.US);
        String title = "// CALENDAR · " + sub;
        text(c, title, x0, y + 30, BLUE, 24);
        float cxr = chipR(c, "+", xr, y + 30, false, A_NEWEVENT, 0) - 18;
        String[] cv = {"DAY", "WEEK", "MONTH"};
        for (int i = 2; i >= 0; i--) cxr = chipR(c, cv[i], cxr, y + 30, d.calView == i, A_CALVIEW, i) - 10;
        stroke(LINE, 2); ln(c, x0, y + 44, xr, y + 44);
        poly(c, true, x0 + 18, py, xr, py, xr, py + ph - 18, xr - 18, py + ph, x0, py + ph, x0, py + 18);

        if (d.calState != null && d.calState.startsWith("GRANT")) {
            textC(c, "GRANT CALENDAR ACCESS IN THE TN-HUD APP", (x0 + xr) / 2, py + ph / 2, YELLOW, 26);
            return;
        }
        if (d.calView == 0) calDay(c, d, dec, seed, x0, xr, py, ph, now, today);
        else if (d.calView == 1) calWeek(c, d, dec, x0, xr, py, ph, now, today);
        else calMonth(c, d, x0, xr, py, ph, now, today, cn);
    }

    private void calDay(Canvas c, Data d, float dec, long seed, float x0, float xr, float py, float ph, long now, long today) {
        long tomorrow = addDays(today, 1);
        float lx = x0 + 24, rx = xr - 24, y = py + 46;
        // all-day chips
        float cxp = lx; int allN = 0;
        for (int i = 0; i < d.evN; i++) {
            if (!d.evAll[i] || d.evE[i] <= today || d.evB[i] >= tomorrow) continue;
            allN++;
            if (cxp > rx - 200) continue;
            String t = fit((d.evT[i] == null ? "(NO TITLE)" : d.evT[i]).toUpperCase(Locale.US), 22, 300);
            float w = tw(t, 22) + 28;
            fill(alpha(evColor(d.evCol[i]), 0.28f)); poly(c, true, cxp + 8, y - 28, cxp + w, y - 28, cxp + w, y + 2, cxp, y + 2, cxp, y - 20);
            text(c, t, cxp + 14, y - 6, FG, 22);
            cxp += w + 12;
        }
        if (allN > 0) y += 50;
        // timed events today
        int rows = 0, maxRows = (int) ((py + ph - 30 - y) / 56), shown = 0, total = 0;
        boolean nowDrawn = false;
        for (int i = 0; i < d.evN; i++) {
            if (d.evAll[i] || d.evE[i] <= today || d.evB[i] >= tomorrow) continue;
            total++;
            if (rows >= maxRows) continue;
            boolean past = d.evE[i] <= now, live = d.evB[i] <= now && d.evE[i] > now;
            if (!nowDrawn && !past) {                         // NOW marker before the first event that isn't over
                if (rows < maxRows - 1 && !live) {
                    p.setStyle(Paint.Style.STROKE); p.setColor(CYAN); p.setStrokeWidth(2 * s);
                    p.setPathEffect(new DashPathEffect(new float[]{10 * s, 8 * s}, 0));
                    ln(c, lx + 150, y - 16, rx, y - 16); p.setPathEffect(null);
                    text(c, "NOW " + hm(d, now), lx, y - 8, CYAN, 22);
                    y += 40; rows++;
                }
                nowDrawn = true;
            }
            int col = evColor(d.evCol[i]);
            tap(lx, y - 38, rx, y + 18, A_EVENT, i);
            fill(past ? alpha(col, 0.35f) : col); rect(c, lx + 132, y - 34, lx + 138, y + 10);
            textF(c, hm(d, d.evB[i]), lx, y - 6, past ? COMMENT : FG, 30);
            text(c, "–" + hm(d, d.evE[i]), lx + 2, y + 18, COMMENT, 18);
            String st = live ? "NOW" : past ? "DONE" : rel(d.evB[i] - now);
            float sx = textR(c, st, rx, y - 6, live ? CYAN : past ? LINE : (d.evB[i] - now < 15 * 60000L ? CYAN : COMMENT), 22);
            String t = (d.evT[i] == null || d.evT[i].isEmpty() ? "(NO TITLE)" : d.evT[i]).toUpperCase(Locale.US);
            text(c, decode(fit(t, 28, sx - (lx + 160) - 20), dec, seed + i), lx + 160, y - 6, live ? CYAN : past ? COMMENT : FG, 28);
            if (live) {
                float fr = (now - d.evB[i]) / (float) Math.max(1, d.evE[i] - d.evB[i]);
                fill(LINE); rect(c, lx + 160, y + 10, rx, y + 14);
                fill(CYAN); rect(c, lx + 160, y + 10, lx + 160 + (rx - lx - 160) * fr, y + 14);
            }
            y += 56; rows++; shown++;
        }
        if (total > shown) text(c, "+" + (total - shown) + " MORE TODAY", lx + 160, py + ph - 20, COMMENT, 22);
        if (total == 0) {
            text(c, allN > 0 ? "NO TIMED EVENTS TODAY" : "NO EVENTS TODAY", lx, y + 10, COMMENT, 28);
            for (int i = 0; i < d.evN; i++) if (d.evB[i] >= tomorrow) {     // next upcoming
                Calendar b = Calendar.getInstance(); b.setTimeInMillis(d.evB[i]);
                String when = String.format(Locale.US, "%ta %<td %<tb", b).toUpperCase(Locale.US) + (d.evAll[i] ? "" : " " + hm(d, d.evB[i]));
                text(c, "NEXT · " + when, lx, y + 64, COMMENT, 22);
                text(c, fit((d.evT[i] == null ? "(NO TITLE)" : d.evT[i]).toUpperCase(Locale.US), 28, rx - lx), lx, y + 104, FG, 28);
                break;
            }
        }
    }

    private static String rel(long ms) {
        long m = Math.max(0, ms / 60000);
        return m < 60 ? "IN " + m + "M" : m < 24 * 60 ? String.format(Locale.US, "IN %dH %02dM", m / 60, m % 60) : "IN " + (m / 1440) + "D";
    }

    private void calWeek(Canvas c, Data d, float dec, float x0, float xr, float py, float ph, long now, long today) {
        long ws = weekStart(now);
        float gx0 = x0 + 74, gx1 = xr - 16, cw = (gx1 - gx0) / 7, hy = py + 40, ay0 = py + 56, ay1 = py + 84, gy0 = py + 96, gy1 = py + ph - 20;
        int h0 = 7, h1 = 23;
        float perH = (gy1 - gy0) / (h1 - h0);
        // hour grid
        p.setStyle(Paint.Style.STROKE); p.setColor(LINE); p.setStrokeWidth(1.5f * s);
        p.setPathEffect(new DashPathEffect(new float[]{4 * s, 8 * s}, 0));
        for (int h = h0; h <= h1; h += 4) { float yy = gy0 + (h - h0) * perH; ln(c, gx0, yy, gx1, yy); }
        p.setPathEffect(null);
        for (int h = h0 + 1; h < h1; h += 4) textR(c, String.format(Locale.US, "%02d", h + 3), gx0 - 10, gy0 + (h + 3 - h0) * perH + 8, COMMENT, 18);
        textR(c, "ALL", gx0 - 10, ay1 - 6, COMMENT, 16);
        for (int k = 0; k < 7; k++) {
            long d0 = addDays(ws, k), d1 = addDays(ws, k + 1);
            float cx0 = gx0 + k * cw, cx1 = cx0 + cw - 6;
            boolean isToday = d0 == today;
            Calendar dc = Calendar.getInstance(); dc.setTimeInMillis(d0);
            String hd = WD[k] + " " + dc.get(Calendar.DAY_OF_MONTH);
            tap(cx0, hy - 30, cx1, hy + 8, A_DAY, d0);
            if (isToday) { fill(BLUE); rect(c, cx0, hy - 26, cx1, hy + 6); }
            textC(c, hd, (cx0 + cx1) / 2, hy - 2, isToday ? BLACK : k >= 5 ? MAGENTA : FG, 20);
            stroke(LINE, 1.5f); ln(c, cx0 - 3, gy0, cx0 - 3, gy1);
            int allK = 0;
            for (int i = 0; i < d.evN; i++) {
                if (d.evE[i] <= d0 || d.evB[i] >= d1) continue;
                int col = evColor(d.evCol[i]);
                if (d.evAll[i]) {
                    if (allK < 2) { fill(alpha(col, 0.55f)); rect(c, cx0, ay0 + allK * 14, cx1, ay0 + allK * 14 + 10); }
                    allK++;
                    continue;
                }
                long b = Math.max(d.evB[i], d0), e = Math.min(d.evE[i], d1);
                float hb = (b - d0) / 3600000f, he = (e - d0) / 3600000f;
                if (he <= h0 || hb >= h1) {                   // outside the window: tick at the edge
                    fill(alpha(col, 0.8f)); float ty = hb >= h1 ? gy1 - 4 : gy0; rect(c, cx0 + 4, ty, cx1 - 4, ty + 4);
                    continue;
                }
                float yb = gy0 + (Math.max(hb, h0) - h0) * perH, ye = gy0 + (Math.min(he, h1) - h0) * perH;
                ye = Math.max(ye, yb + 10);
                boolean past = d.evE[i] <= now;
                tap(cx0, yb, cx1, Math.max(ye, yb + 24), A_EVENT, i);
                fill(alpha(col, past ? 0.18f : 0.34f)); rect(c, cx0, yb, cx1, ye - 2);
                fill(past ? alpha(col, 0.5f) : col); rect(c, cx0, yb, cx0 + 5, ye - 2);
                if (ye - yb >= 21) {
                    String t = (d.evT[i] == null ? "" : d.evT[i]).toUpperCase(Locale.US);
                    text(c, fit(t, 16, cw - 18), cx0 + 9, yb + 17, past ? COMMENT : FG, 16);
                }
            }
            if (isToday) {                                    // now line
                float hn = (now - d0) / 3600000f;
                if (hn >= h0 && hn <= h1) {
                    float yn = gy0 + (hn - h0) * perH;
                    stroke(CYAN, 3); ln(c, cx0, yn, cx1, yn);
                    fill(CYAN); c.drawCircle(cx0 * s, yn * s, 6 * s, p);
                }
            }
        }
    }

    private void calMonth(Canvas c, Data d, float x0, float xr, float py, float ph, long now, long today, Calendar cn) {
        Calendar m1 = Calendar.getInstance(); m1.setTimeInMillis(today); m1.set(Calendar.DAY_OF_MONTH, 1);
        int month = m1.get(Calendar.MONTH);
        long gs = weekStart(m1.getTimeInMillis());
        float gx0 = x0 + 74, gx1 = xr - 16, cw = (gx1 - gx0) / 7, hy = py + 40, gy0 = py + 56, gy1 = py + ph - 14, rh = (gy1 - gy0) / 6;
        for (int k = 0; k < 7; k++) textC(c, WD[k].substring(0, 2), gx0 + k * cw + cw / 2, hy, k >= 5 ? MAGENTA : COMMENT, 20);
        for (int r = 0; r < 6; r++) {
            long rowStart = addDays(gs, r * 7);
            Calendar wc = Calendar.getInstance(); wc.setTimeInMillis(rowStart);
            textR(c, String.format(Locale.US, "%02d", wc.get(Calendar.WEEK_OF_YEAR)), gx0 - 14, gy0 + r * rh + 30, LINE, 18);
            for (int k = 0; k < 7; k++) {
                long d0 = addDays(gs, r * 7 + k), d1 = addDays(d0, 1);
                Calendar dc = Calendar.getInstance(); dc.setTimeInMillis(d0);
                boolean inMonth = dc.get(Calendar.MONTH) == month, isToday = d0 == today, past = d0 < today;
                float cx0 = gx0 + k * cw, cy0 = gy0 + r * rh;
                tap(cx0, cy0, cx0 + cw, cy0 + rh, A_DAY, d0);
                stroke(LINE, 1.5f); rect(c, cx0 + 2, cy0 + 2, cx0 + cw - 4, cy0 + rh - 4);
                if (isToday) { fill(BLUE); poly(c, true, cx0 + 12, cy0 + 2, cx0 + cw - 4, cy0 + 2, cx0 + cw - 4, cy0 + rh - 4, cx0 + 2, cy0 + rh - 4, cx0 + 2, cy0 + 12); }
                int nc = isToday ? BLACK : !inMonth ? LINE : past ? COMMENT : k >= 5 ? MAGENTA : FG;
                text(c, String.valueOf(dc.get(Calendar.DAY_OF_MONTH)), cx0 + 12, cy0 + 30, nc, 24);
                int dots = 0;
                for (int i = 0; i < d.evN && dots < 5; i++) {
                    if (d.evE[i] <= d0 || d.evB[i] >= d1) continue;
                    if (dots < 4) { fill(isToday ? BLACK : alpha(evColor(d.evCol[i]), inMonth ? 1f : 0.4f)); c.drawCircle((cx0 + 18 + dots * 18) * s, (cy0 + rh - 18) * s, 5 * s, p); }
                    else text(c, "+", cx0 + 14 + 4 * 18, cy0 + rh - 11, isToday ? BLACK : COMMENT, 20);
                    dots++;
                }
            }
        }
    }

    private void header(Canvas c, String t, float x, float y, float w) {
        text(c, t, x, y, BLUE, 24);
        stroke(LINE, 2); ln(c, x, y + 14, x + w, y + 14);
    }

    private static String wmo(int code) {
        switch (code) {
            case 0: return "CLEAR";
            case 1: return "MAINLY CLEAR";
            case 2: return "PARTLY CLOUDY";
            case 3: return "OVERCAST";
            case 45: case 48: return "FOG";
            case 51: case 53: case 55: return "DRIZZLE";
            case 56: case 57: return "FREEZING DRIZZLE";
            case 61: return "LIGHT RAIN";
            case 63: return "RAIN";
            case 65: return "HEAVY RAIN";
            case 66: case 67: return "FREEZING RAIN";
            case 71: return "LIGHT SNOW";
            case 73: return "SNOW";
            case 75: return "HEAVY SNOW";
            case 77: return "SNOW GRAINS";
            case 80: case 81: case 82: return "SHOWERS";
            case 85: case 86: return "SNOW SHOWERS";
            case 95: return "THUNDERSTORM";
            case 96: case 99: return "THUNDER · HAIL";
            default: return "--";
        }
    }

    private static String bytes(long b) {
        if (b >= 1L << 30) return String.format(Locale.US, "%.1fG", b / 1073741824f);
        if (b >= 1L << 20) return String.format(Locale.US, "%.0fM", b / 1048576f);
        return String.format(Locale.US, "%.0fK", b / 1024f);
    }

    // ---------------------------------------------------------------- now playing
    private void music(Canvas c, Data d, float dec, long seed) {
        float x0 = M + 40, xr = W - M - 40, y0 = 2110, ph = 420;
        if (H - M - 250 < y0 + ph) return;                       // not enough room on short screens
        text(c, "// NOW PLAYING", x0, y0 - 40, BLUE, 24);
        if (d.mApp != null) text(c, " · " + d.mApp, x0 + tw("// NOW PLAYING", 24), y0 - 40, COMMENT, 24);
        stroke(LINE, 2); ln(c, x0, y0 - 26, xr, y0 - 26);
        poly(c, true, x0 + 18, y0, xr, y0, xr, y0 + ph - 18, xr - 18, y0 + ph, x0, y0 + ph, x0, y0 + 18);

        // circular spectrum: 32 bands mirrored -> 64 spokes
        float cx = x0 + 230, cy = y0 + ph / 2, r0 = 86, L = 100;
        p.setStyle(Paint.Style.STROKE); p.setColor(LINE); p.setStrokeWidth(3 * s);
        p.setPathEffect(new DashPathEffect(new float[]{40 * s, 14 * s}, mod(d.wallMs / 60f, 54f) * s));
        c.drawCircle(cx * s, cy * s, (r0 + L + 18) * s, p);
        p.setPathEffect(null);
        stroke(LINE, 2); c.drawCircle(cx * s, cy * s, (r0 - 12) * s, p);
        int B = d.spec.length;
        float level = 0;
        for (int k = 0; k < 2 * B; k++) {
            int b = k < B ? k : 2 * B - 1 - k;
            float v = d.specOk && d.mPlaying ? d.spec[b] * dec : 0f;
            level += v;
            double a = -Math.PI / 2 + (k + 0.5) * Math.PI * 2 / (2 * B);
            float ca = (float) Math.cos(a), sa = (float) Math.sin(a), len = 6 + v * L;
            stroke(d.specOk ? bandCol(b / (float) (B - 1)) : LINE, 7);
            ln(c, cx + ca * r0, cy + sa * r0, cx + ca * (r0 + len), cy + sa * (r0 + len));
        }
        level /= 2 * B;
        // centre: play / pause glyph, pulses with level
        fill(alpha(CYAN, 0.12f + 0.5f * level)); c.drawCircle(cx * s, cy * s, (r0 - 22) * s, p);
        fill(FG);
        if (d.mPlaying) poly(c, true, cx - 18, cy - 26, cx + 26, cy, cx - 18, cy + 26);
        else { rect(c, cx - 22, cy - 26, cx - 8, cy + 26); rect(c, cx + 8, cy - 26, cx + 22, cy + 26); }

        // track info
        float tx = x0 + 480, tmax = xr - 30 - tx;
        String title = d.mTitle != null ? d.mTitle : "UNKNOWN TRACK";
        text(c, decode(fit(title.toUpperCase(Locale.US), 40, tmax), dec, seed), tx, y0 + 140, FG, 40);
        if (d.mArtist != null) text(c, decode(fit(d.mArtist.toUpperCase(Locale.US), 28, tmax), dec, seed + 1), tx, y0 + 188, COMMENT, 28);
        float sx = text(c, d.mPlaying ? "PLAYING" : "PAUSED", tx, y0 + 240, d.mPlaying ? CYAN : YELLOW, 24);
        String hint = !d.mAccess ? "NO TITLE BROADCAST FROM PLAYER" : (!d.specOk && d.specState != null ? d.specState : null);
        if (hint != null) text(c, " · " + fit(hint, 20, tmax - (sx - tx) - 40), sx, y0 + 240, YELLOW, 20);

        // progress
        float py = y0 + 272;
        float frac = d.mDur > 0 ? Math.max(0, Math.min(1, d.mPos / (float) d.mDur)) : 0f;
        int segs = 40, on = Math.round(frac * segs * dec);
        float sw = tmax / segs;
        for (int i = 0; i < segs; i++) { fill(i < on ? CYAN : LINE); rect(c, tx + i * sw, py, tx + i * sw + sw - 5, py + 12); }
        if (d.mDur > 0) {
            text(c, mmss(d.mPos), tx, py + 44, COMMENT, 24);
            textR(c, mmss(d.mDur), tx + tmax, py + 44, COMMENT, 24);
        }

    }

    private static int bandCol(float t) {          // blue -> cyan -> magenta
        int a, b; float u;
        if (t < 0.5f) { a = BLUE; b = CYAN; u = t * 2; } else { a = CYAN; b = MAGENTA; u = (t - 0.5f) * 2; }
        int r = (int) (((a >> 16) & 255) * (1 - u) + ((b >> 16) & 255) * u);
        int g = (int) (((a >> 8) & 255) * (1 - u) + ((b >> 8) & 255) * u);
        int bl = (int) ((a & 255) * (1 - u) + (b & 255) * u);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private static String mmss(long ms) {
        long sec = Math.max(0, ms / 1000);
        return sec >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", sec / 3600, (sec / 60) % 60, sec % 60)
                : String.format(Locale.US, "%d:%02d", sec / 60, sec % 60);
    }

    private String fit(String t, float size, float maxw) {
        if (tw(t, size) <= maxw) return t;
        int n = t.length();
        while (n > 1 && tw(t.substring(0, n) + "…", size) > maxw) n--;
        return t.substring(0, n) + "…";
    }

    // ---------------------------------------------------------------- sky now
    private long aAt; private double aLat = Double.NaN, aLon = Double.NaN;
    private long sRise, sSet, sRise2, mRise, mSet, dayLen = -1, dayDelta, nxDay;
    private final float[] dSun = new float[144], dMoon = new float[144];
    private double noonAlt;
    private final float[] pAlt = new float[73], pAz = new float[73];

    private void astroCache(Data d) {
        if (Math.abs(d.wallMs - aAt) < 600000L && d.obsLat == aLat && d.obsLon == aLon) return;
        aAt = d.wallMs; aLat = d.obsLat; aLon = d.obsLon;
        double la = d.obsLat, lo = d.obsLon;
        Calendar c0 = Calendar.getInstance(); c0.setTimeInMillis(d.wallMs);
        c0.set(Calendar.HOUR_OF_DAY, 0); c0.set(Calendar.MINUTE, 0); c0.set(Calendar.SECOND, 0); c0.set(Calendar.MILLISECOND, 0);
        long day0 = c0.getTimeInMillis(), day1 = day0 + 86400000L, dayY = day0 - 86400000L, st = 600000L;
        sRise = Astro.crossing(Astro.SUN, day0, day1, la, lo, -0.833, true, st);
        sSet = Astro.crossing(Astro.SUN, day0, day1, la, lo, -0.833, false, st);
        sRise2 = Astro.crossing(Astro.SUN, day1, day1 + 86400000L, la, lo, -0.833, true, st);
        long yR = Astro.crossing(Astro.SUN, dayY, day0, la, lo, -0.833, true, st), yS = Astro.crossing(Astro.SUN, dayY, day0, la, lo, -0.833, false, st);
        dayLen = sRise > 0 && sSet > sRise ? sSet - sRise : -1;
        dayDelta = dayLen > 0 && yR > 0 && yS > yR ? dayLen - (yS - yR) : 0;
        noonAlt = Astro.culmination(day0, day1, la, lo)[1];
        mRise = Astro.crossing(Astro.MOON, d.wallMs, d.wallMs + 36 * 3600000L, la, lo, -0.833, true, st);
        mSet = Astro.crossing(Astro.MOON, d.wallMs, d.wallMs + 36 * 3600000L, la, lo, -0.833, false, st);
        for (int i = 0; i < 73; i++) { double[] p = Astro.pos(Astro.SUN, day0 + i * 1200000L, la, lo); pAlt[i] = (float) p[0]; pAz[i] = (float) p[1]; }
        for (int i = 0; i < 144; i++) {                                   // 10-min slices for the 24 h dial
            long t = day0 + i * 600000L + 300000L;
            dSun[i] = (float) Astro.pos(Astro.SUN, t, la, lo)[0];
            dMoon[i] = (float) Astro.pos(Astro.MOON, t, la, lo)[0];
        }
        nxDay = day0;
    }

    private void sky(Canvas c, Data d, float dec, long seed, float y0) {
        float x0 = M + 40, xr = W - M - 40, ph = 460;
        long now = d.wallMs;
        if (d.skyMode == 1) {                                   // tonight 22:00 (tomorrow if already past)
            Calendar t = Calendar.getInstance(); t.setTimeInMillis(d.wallMs);
            if (t.get(Calendar.HOUR_OF_DAY) >= 22) t.add(Calendar.DAY_OF_YEAR, 1);
            t.set(Calendar.HOUR_OF_DAY, 22); t.set(Calendar.MINUTE, 0); t.set(Calendar.SECOND, 0); t.set(Calendar.MILLISECOND, 0);
            now = t.getTimeInMillis();
        }
        String title = d.skyMode == 1 ? "// SKY \u00B7 22:00" : d.skyMode == 2 ? "// SKY \u00B7 PLANETS" : "// SKY \u00B7 NOW";
        text(c, title, x0, y0 - 40, d.skyMode == 0 ? BLUE : MAGENTA, 24);
        float cxr = xr;
        String[] sm = {"NOW", "22:00", "PLANETS"};
        for (int i = 2; i >= 0; i--) cxr = chipR(c, sm[i], cxr, y0 - 40, d.skyMode == i, A_SKYMODE, i) - 10;
        text(c, Double.isNaN(d.obsLat) ? " NO LOCATION" : String.format(Locale.US, " %.2f\u00B0%s %.2f\u00B0%s \u00B7 TAP MAP", Math.abs(d.obsLat), d.obsLat >= 0 ? "N" : "S",
                Math.abs(d.obsLon), d.obsLon >= 0 ? "E" : "W").replace(" \u00B7 TAP MAP", ""), x0 + tw(title, 24), y0 - 40, COMMENT, 24);
        stroke(LINE, 2); ln(c, x0, y0 - 26, xr, y0 - 26);
        poly(c, true, x0 + 18, y0, xr, y0, xr, y0 + ph - 18, xr - 18, y0 + ph, x0, y0 + ph, x0, y0 + 18);
        float cx = x0 + 230, cy = y0 + ph / 2, R = 196;
        // map frame: horizon, 30/60 deg rings, cardinal ticks
        stroke(LINE, 3); c.drawCircle(cx * s, cy * s, R * s, p);
        p.setPathEffect(new DashPathEffect(new float[]{6 * s, 10 * s}, 0));
        p.setStrokeWidth(2 * s); c.drawCircle(cx * s, cy * s, R * 60 / 90f * s, p); c.drawCircle(cx * s, cy * s, R * 30 / 90f * s, p);
        p.setPathEffect(null);
        stroke(COMMENT, 2); ln(c, cx - 8, cy, cx + 8, cy); ln(c, cx, cy - 8, cx, cy + 8);
        for (int a = 0; a < 360; a += 30) {
            double t = Math.toRadians(a);
            stroke(a % 90 == 0 ? COMMENT : LINE, 3);
            ln(c, cx + (float) Math.sin(t) * R, cy - (float) Math.cos(t) * R, cx + (float) Math.sin(t) * (R + (a % 90 == 0 ? 16 : 9)), cy - (float) Math.cos(t) * (R + (a % 90 == 0 ? 16 : 9)));
        }
        textC(c, "N", cx, cy - R - 24, YELLOW, 22);
        textR(c, "E", cx - R - 22, cy + 8, COMMENT, 22);
        textC(c, "S", cx, cy + R + 40, COMMENT, 22);
        text(c, "W", cx + R + 22, cy + 8, COMMENT, 22);
        float tx = x0 + 480, lw = 150, vmax = xr - 30 - tx - lw;
        if (Double.isNaN(d.obsLat)) {
            textC(c, "AWAITING POSITION", cx, cy + 8, YELLOW, 22);
            text(c, "SET A CITY OR ENABLE LIVE LOCATION IN THE APP", tx, y0 + 80, YELLOW, 22);
            return;
        }

        astroCache(d);
        double la = d.obsLat, lo = d.obsLon;
        // stars + constellation lines (dimmed by daylight / twilight)
        if (d.skyMode != 2 && stRa != null) {
            double lstD = Astro.lst(Astro.jd(now), lo);
            float sa = (float) Astro.pos(Astro.SUN, now, la, lo)[0];
            float vis = sa < -12 ? 1f : sa > 0 ? 0.22f : 0.22f + 0.78f * (-sa / 12f);
            stroke(alpha(0xFF3B4261, vis), 1.6f);
            for (float[] pl : stLines) {
                float px = 0, py = 0; boolean pv = false;
                for (int i = 0; i < pl.length; i += 2) {
                    double[] h = Astro.altAz(pl[i], pl[i + 1], lstD, la);
                    boolean v = h[0] > 0;
                    float[] q = proj(cx, cy, R, (float) Math.max(h[0], 0), (float) h[1]);
                    if (v && pv) ln(c, px, py, q[0], q[1]);
                    px = q[0]; py = q[1]; pv = v;
                }
            }
            for (int i = 0; i < stRa.length; i++) {
                double[] h = Astro.altAz(stRa[i], stDec[i], lstD, la);
                if (h[0] <= 0) continue;
                float[] q = proj(cx, cy, R, (float) h[0], (float) h[1]);
                float bv = stBv[i];
                int col = bv < 0 ? 0xFFB4C8FF : bv < 0.5f ? FG : bv < 1.1f ? 0xFFFFE8C0 : 0xFFFFB98A;
                fill(alpha(col, vis * (stMag[i] < 2 ? 1f : 0.7f)));
                c.drawCircle(q[0] * s, q[1] * s, Math.max(1.3f, 4.6f - stMag[i] * 0.85f) * s, p);
            }
        }
        // sun path today
        fill(alpha(YELLOW, 0.35f));
        for (int i = 0; i < 73; i++) if (pAlt[i] > 0) { float[] q = proj(cx, cy, R, pAlt[i], pAz[i]); c.drawCircle(q[0] * s, q[1] * s, 2.5f * s, p); }
        // planets
        StringBuilder up = new StringBuilder();
        int[] pc = {COMMENT, FG, RED, YELLOW, alpha(YELLOW, 0.7f)};
        String[] ab = {"ME", "VE", "MA", "JU", "SA"};
        String[] cardinal = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        for (int i = 0; i < 5; i++) {
            double[] q = Astro.pos(i, now, la, lo);
            if (q[0] <= 0) continue;
            float[] xy = proj(cx, cy, R, (float) q[0], (float) q[1]);
            fill(pc[i]); c.drawCircle(xy[0] * s, xy[1] * s, 6 * s, p);
            text(c, ab[i], xy[0] + 10, xy[1] - 8, COMMENT, 18);
            tap(xy[0] - 30, xy[1] - 30, xy[0] + 30, xy[1] + 30, A_SKYOBJ, i);
            if (d.skySel == i && d.wallMs < d.skySelUntil) { stroke(CYAN, 2); c.drawCircle(xy[0] * s, xy[1] * s, 16 * s, p); }
            if (up.length() > 0) up.append(" · ");
            up.append(Astro.PNAME[i]).append(String.format(Locale.US, " %.0f° ", q[0])).append(cardinal[(int) Math.round(q[1] / 45) % 8]);
        }
        // moon
        double[] mp = Astro.pos(Astro.MOON, now, la, lo), ph_ = Astro.moonPhase(now);
        if (mp[0] > 0) {
            float[] xy = proj(cx, cy, R, (float) mp[0], (float) mp[1]); moonIcon(c, xy[0], xy[1], 15, ph_[0], ph_[1] > 0.5);
            tap(xy[0] - 34, xy[1] - 34, xy[0] + 34, xy[1] + 34, A_SKYOBJ, Astro.MOON);
            if (d.skySel == Astro.MOON && d.wallMs < d.skySelUntil) { stroke(CYAN, 2); c.drawCircle(xy[0] * s, xy[1] * s, 24 * s, p); }
        }
        if (d.skySel >= 0 && d.wallMs < d.skySelUntil) { skyInfo(c, d, d.skySel, x0 + 480, y0, xr, y0 + ph); return; }
        // sun
        double[] sp = Astro.pos(Astro.SUN, now, la, lo);
        if (sp[0] > -0.833) {
            float[] xy = proj(cx, cy, R, (float) Math.max(0, sp[0]), (float) sp[1]);
            g.setColor(alpha(YELLOW, 0.7f)); g.setStrokeWidth(10 * s); c.drawCircle(xy[0] * s, xy[1] * s, 13 * s, g);
            fill(YELLOW); c.drawCircle(xy[0] * s, xy[1] * s, 12 * s, p);
        }

        // ---- readouts (time-of-day events live on the 24 h dial below; nothing repeated here)
        float y = y0 + 70;
        double k = ph_[0]; boolean wax = ph_[1] > 0.5;
        String pn = k < 0.03 ? "NEW MOON" : k > 0.97 ? "FULL MOON" : wax ? (k < 0.47 ? "WAXING CRESCENT" : k <= 0.53 ? "FIRST QUARTER" : "WAXING GIBBOUS")
                : (k < 0.47 ? "WANING CRESCENT" : k <= 0.53 ? "LAST QUARTER" : "WANING GIBBOUS");
        double D = ph_[2];
        double toFull = Astro.norm(180 - D) / 12.19, toNew = Astro.norm(360 - D) / 12.19;
        String nx = toFull < toNew ? String.format(Locale.US, "FULL %.1fD", toFull) : String.format(Locale.US, "NEW %.1fD", toNew);
        moonIcon(c, tx + lw - 26, y - 9, 11, k, wax);
        y = row(c, "MOON", String.format(Locale.US, "%s %.0f%% · %s", pn, k * 100, nx), FG, tx, y, lw, vmax, dec, seed);
        double[] mg = Astro.moon(Astro.jd(now));
        double km = 6378.14 / Math.sin(Math.toRadians(mg[4]));
        String apsis = km < 362000 ? " · NEAR PERIGEE" : km > 404000 ? " · NEAR APOGEE" : "";
        y = row(c, "MOON POS", String.format(Locale.US, "ALT %+.0f° · AZ %.0f° · %,.0f KM", mp[0], mp[1], km).replace(',', ' ') + apsis,
                mp[0] > 0 ? CYAN : COMMENT, tx, y, lw, vmax, dec, seed + 1);
        y = row(c, "SUN", String.format(Locale.US, "ALT %+.1f° · AZ %.0f° %s", sp[0], sp[1], cardinal[(int) Math.round(sp[1] / 45) % 8]),
                sp[0] > 0 ? YELLOW : sp[0] > -6 ? MAGENTA : FG, tx, y, lw, vmax, dec, seed + 2);
        String dl;
        if (dayLen > 0) {
            long dm = dayLen / 60000, ds = Math.abs(dayDelta) / 1000;
            dl = String.format(Locale.US, "%dH %02dM · %s%dM %02dS VS YESTERDAY", dm / 60, dm % 60, dayDelta < 0 ? "−" : "+", ds / 60, ds % 60);
        } else dl = noonAlt > 0 ? "POLAR DAY" : "POLAR NIGHT";
        y = row(c, "DAY", dl, dayDelta < 0 ? MAGENTA : GREEN, tx, y, lw, vmax, dec, seed + 3);
        double lam = Astro.sun(Astro.jd(now))[2];
        String[] evN = la >= 0 ? new String[]{"SPRING EQUINOX", "SUMMER SOLSTICE", "AUTUMN EQUINOX", "WINTER SOLSTICE"}
                : new String[]{"AUTUMN EQUINOX", "WINTER SOLSTICE", "SPRING EQUINOX", "SUMMER SOLSTICE"};
        int nxt = (int) Math.floor(lam / 90 + 1) % 4;
        double days = Astro.norm(nxt * 90 - lam) / 0.98565;
        y = row(c, "SEASON", String.format(Locale.US, "%s IN %.0fD", evN[nxt], Math.ceil(days)), FG, tx, y, lw, vmax, dec, seed + 4);
        y = row(c, "PLANETS", up.length() > 0 ? up.toString() : "NONE ABOVE HORIZON", up.length() > 0 ? CYAN : COMMENT, tx, y, lw, vmax, dec, seed + 5);
        double j = Astro.jd(now), lst = Astro.lst(j, lo) / 15.0;
        int lh = (int) lst, lm = (int) ((lst - lh) * 60), ls = (int) ((((lst - lh) * 60) - lm) * 60);
        row(c, "LST", String.format(Locale.US, "%02d:%02d:%02d · JD %.4f", lh, lm, ls, j), COMMENT, tx, y, lw, vmax, dec, seed + 6);
    }

    private float row(Canvas c, String k, String v, int col, float x, float y, float lw, float vmax, float dec, long seed) {
        text(c, decode(k, dec, seed), x, y, COMMENT, 22);
        text(c, decode(fit(v, 24, vmax), dec, seed + 50), x + lw, y, col, 24);
        return y + 48;
    }

    private String hm(Data d, long ms) {
        Calendar c = Calendar.getInstance(); c.setTimeInMillis(ms);
        int h = c.get(Calendar.HOUR_OF_DAY), m = c.get(Calendar.MINUTE);
        return d.h24 ? String.format(Locale.US, "%02d:%02d", h, m) : String.format(Locale.US, "%d:%02d%s", (h + 11) % 12 + 1, m, h < 12 ? "A" : "P");
    }

    /** azimuthal sky projection: zenith at centre, horizon at R, north up */
    private static float[] proj(float cx, float cy, float R, float alt, float az) {
        float r = R * (90 - alt) / 90f; double t = Math.toRadians(az);
        return new float[]{cx - r * (float) Math.sin(t), cy - r * (float) Math.cos(t)};   // star-chart view: east on the left
    }

    /** lit fraction k, waxing = lit on the right (northern hemisphere view) */
    private void moonIcon(Canvas c, float cx, float cy, float r, double k, boolean waxing) {
        fill(LINE); c.drawCircle(cx * s, cy * s, r * s, p);
        path.reset();
        float sg = waxing ? 1 : -1;
        for (int i = 0; i <= 24; i++) {
            double th = -Math.PI / 2 + Math.PI * i / 24;
            float x = cx + sg * r * (float) Math.cos(th), y = cy + r * (float) Math.sin(th);
            if (i == 0) path.moveTo(x * s, y * s); else path.lineTo(x * s, y * s);
        }
        for (int i = 24; i >= 0; i--) {
            double th = -Math.PI / 2 + Math.PI * i / 24;
            float x = cx + sg * (float) (1 - 2 * k) * r * (float) Math.cos(th), y = cy + r * (float) Math.sin(th);
            path.lineTo(x * s, y * s);
        }
        path.close();
        fill(FG); c.drawPath(path, p);
    }

    private int bandOf(float a) { return a > 6 ? YELLOW : a > -4 ? ORANGE : a > -6 ? BLUE : a > -12 ? NAUT : a > -18 ? LINE : 0; }

    /** 24 h dial: noon at top, midnight at bottom, morning on the left. Ring = sun altitude bands, inner arc = moon up. */
    private void dayDial(Canvas c, Data d, float cx, float cy, float R) {
        stroke(LINE, 2); c.drawCircle(cx * s, cy * s, R * s, p); c.drawCircle(cx * s, cy * s, (R - 26) * s, p);
        if (Double.isNaN(d.obsLat)) { textC(c, "NO LOCATION", cx, cy + 8, YELLOW, 20); stroke(LINE, 3); c.drawCircle(cx * s, cy * s, R * s, p); return; }
        astroCache(d);
        float rb = R - 13;                                     // band radius, 26 wide
        oval.set((cx - rb) * s, (cy - rb) * s, (cx + rb) * s, (cy + rb) * s);
        for (int i = 0; i < 144; ) {                           // merge equal-colour runs into single arcs (no seams)
            int col = bandOf(dSun[i]), j = i + 1;
            while (j < 144 && bandOf(dSun[j]) == col) j++;
            if (col != 0) { stroke(col, 26); c.drawArc(oval, 90 + i * 2.5f, (j - i) * 2.5f, false, p); }   // 0 deg = 3 o'clock; 00:00 at bottom
            i = j;
        }
        float rm = R - 36;                                     // moon-up arc
        oval.set((cx - rm) * s, (cy - rm) * s, (cx + rm) * s, (cy + rm) * s);
        for (int i = 0; i < 144; ) {
            int j = i; while (j < 144 && dMoon[j] > 0) j++;
            if (j > i) { stroke(CYAN, 4); c.drawArc(oval, 90 + i * 2.5f, (j - i) * 2.5f, false, p); i = j; } else i++;
        }
        // hour ticks + labels
        for (int hgt = 0; hgt < 24; hgt++) {
            double t = Math.toRadians(hgt * 15 + 180);
            float r1 = R + 2, r2 = R + (hgt % 6 == 0 ? 12 : 6);
            stroke(hgt % 6 == 0 ? COMMENT : LINE, 2);
            ln(c, cx + (float) Math.sin(t) * r1, cy - (float) Math.cos(t) * r1, cx + (float) Math.sin(t) * r2, cy - (float) Math.cos(t) * r2);
        }
        String[] hl = {"00", "06", "12", "18"};
        for (int i = 0; i < 4; i++) {
            double t = Math.toRadians(i * 90 + 180);
            textC(c, hl[i], cx + (float) Math.sin(t) * (R + 30), cy - (float) Math.cos(t) * (R + 30) + 6, COMMENT, 16);
        }
        // now hand
        Calendar n = Calendar.getInstance(); n.setTimeInMillis(d.wallMs);
        float hr = n.get(Calendar.HOUR_OF_DAY) + n.get(Calendar.MINUTE) / 60f;
        double t = Math.toRadians(hr * 15 + 180);
        float hx2 = cx + (float) Math.sin(t) * (R + 4), hy2 = cy - (float) Math.cos(t) * (R + 4);
        g.setColor(alpha(FG, 0.6f)); g.setStrokeWidth(6 * s); c.drawLine((cx + (float) Math.sin(t) * (R - 30)) * s, (cy - (float) Math.cos(t) * (R - 30)) * s, hx2 * s, hy2 * s, g);
        stroke(FG, 3); ln(c, cx + (float) Math.sin(t) * (R - 30), cy - (float) Math.cos(t) * (R - 30), hx2, hy2);
        fill(FG); c.drawCircle(hx2 * s, hy2 * s, 6 * s, p);
        // centre: countdown to the next sunrise / sunset
        long now = d.wallMs, ev; String lab;
        if (d.dialMoon) {
            if (mRise > now && (mSet <= now || mRise < mSet)) { ev = mRise; lab = "MOONRISE"; } else { ev = mSet > now ? mSet : 0; lab = "MOONSET"; }
        }
        else if (sRise > now) { ev = sRise; lab = "SUNRISE"; }
        else if (sSet > now) { ev = sSet; lab = "SUNSET"; }
        else { ev = sRise2; lab = "SUNRISE"; }
        if (ev > 0) {
            long m = (ev - now) / 60000;
            textC(c, lab, cx, cy - 14, COMMENT, 16);
            textC(c, m >= 60 ? String.format(Locale.US, "%dH %02dM", m / 60, m % 60) : m + "M", cx, cy + 12, d.dialMoon ? CYAN : lab.equals("SUNSET") ? ORANGE : YELLOW, 24);
            textC(c, hm(d, ev), cx, cy + 34, COMMENT, 16);
        } else textC(c, d.dialMoon ? "NO MOON EVENT" : noonAlt > 0 ? "POLAR DAY" : "POLAR NIGHT", cx, cy + 8, COMMENT, 18);
    }

    // ---------------------------------------------------------------- sensor lab
    private void lab(Canvas c, Data d, float dec, long seed, float y0) {
        float x0 = M + 40, xr = W - M - 40, ph = 360;
        text(c, "// 24H CYCLE \u00B7 SENSOR LAB", x0, y0 - 40, BLUE, 24);
        stroke(LINE, 2); ln(c, x0, y0 - 26, xr, y0 - 26);
        poly(c, true, x0 + 18, y0, xr, y0, xr, y0 + ph - 18, xr - 18, y0 + ph, x0, y0 + ph, x0, y0 + 18);
        float cx = x0 + 190, cy = y0 + ph / 2, R = 118;
        dayDial(c, d, cx, cy, R);
        tap(cx - R - 20, cy - R - 20, cx + R + 20, cy + R + 20, HIT_DIAL, 0);

        float tx = x0 + 400, lw = 160, vmax = xr - 30 - tx - lw, y = y0 + 78;
        boolean off = !d.motionOn;
        String fv = off ? "OFF" : Float.isNaN(d.fieldUT) ? "NO SENSOR" : String.format(Locale.US, "%.1f µT", d.fieldUT);
        int fc = Float.isNaN(d.fieldUT) ? FG : d.fieldUT > 80 || d.fieldUT < 20 ? RED : FG;
        float fx = text(c, decode(fv, dec, seed + 1), tx + lw, y, fc, 24);
        text(c, "MAG FIELD", tx, y, COMMENT, 22);
        if (!Float.isNaN(d.fieldUT) && !off) {
            int on = Math.round(Math.min(1f, d.fieldUT / 100f) * 20 * dec);
            for (int i = 0; i < 20; i++) { fill(i < on ? (d.fieldUT > 80 ? RED : MAGENTA) : LINE); rect(c, fx + 24 + i * 14, y - 16, fx + 24 + i * 14 + 9, y - 2); }
        }
        y += 54;
        String pv = off ? "OFF" : Float.isNaN(d.hPa) ? "NO SENSOR" : String.format(Locale.US, "%.1f HPA", d.hPa)
                + (Float.isNaN(d.hPaTrend) ? " · TREND --" : String.format(Locale.US, " %s %+.1f/3H", d.hPaTrend > 0.5f ? "↑" : d.hPaTrend < -0.5f ? "↓" : "→", d.hPaTrend));
        y = row(c, "PRESSURE", pv, !Float.isNaN(d.hPaTrend) && d.hPaTrend < -1.5f ? YELLOW : FG, tx, y, lw, vmax, dec, seed + 2) + 6;
        y = row(c, "ALTITUDE", off || Float.isNaN(d.altM) ? "--" : String.format(Locale.US, "~%.0f M%s", d.altM, d.altQnh ? "" : " (STD ATM)"), FG, tx, y, lw, vmax, dec, seed + 3) + 6;
        y = row(c, "LIGHT", off ? "OFF" : Float.isNaN(d.lux) ? "NO SENSOR" : String.format(Locale.US, "%.0f LX", d.lux), FG, tx, y, lw, vmax, dec, seed + 4) + 6;
        row(c, "TILT", off || Float.isNaN(d.pitch) ? "--" : String.format(Locale.US, "PITCH %+.0f° · ROLL %+.0f°", d.pitch, d.roll), FG, tx, y, lw, vmax, dec, seed + 5);

        // motion graphs
        float y2 = y0 + ph + 40, gap = 40, pw = (xr - x0 - gap) / 2, phh = 180, x1 = x0 + pw + gap;
        tap(x0, y2, xr, y2 + phh, HIT_MOTION, 0);
        String win = " \u00B7 " + d.motionWin + "S";
        int MN = Data.MN;
        if (d.motionOn) {
            float la = Math.abs((float) Math.sqrt(sq(d.ax, d.mHead) + sq(d.ay, d.mHead) + sq(d.az, d.mHead)) - 9.81f) / 9.81f;
            panel(c, x0, y2, pw, phh, "ACCEL XYZ" + win, d.mLen == 0 ? "--" : String.format(Locale.US, "%.2f G", la), FG);
            spark(c, x0, y2, pw, phh, d.az, d.mLen, d.mHead, MN, 12f, -12f, BLUE, dec);
            spark(c, x0, y2, pw, phh, d.ay, d.mLen, d.mHead, MN, 12f, -12f, GREEN, dec);
            spark(c, x0, y2, pw, phh, d.ax, d.mLen, d.mHead, MN, 12f, -12f, RED, dec);
            float om = (float) Math.sqrt(sq(d.gx, d.mHead) + sq(d.gy, d.mHead) + sq(d.gz, d.mHead));
            panel(c, x1, y2, pw, phh, "GYRO XYZ" + win, d.mLen == 0 ? "--" : String.format(Locale.US, "%.2f RAD/S", om), FG);
            spark(c, x1, y2, pw, phh, d.gz, d.mLen, d.mHead, MN, 4f, -4f, BLUE, dec);
            spark(c, x1, y2, pw, phh, d.gy, d.mLen, d.mHead, MN, 4f, -4f, GREEN, dec);
            spark(c, x1, y2, pw, phh, d.gx, d.mLen, d.mHead, MN, 4f, -4f, RED, dec);
        } else {
            panel(c, x0, y2, pw, phh, "ACCEL XYZ", "OFF", COMMENT);
            panel(c, x1, y2, pw, phh, "GYRO XYZ", "OFF", COMMENT);
        }
    }

    // ---------------------------------------------------------------- telemetry
    private void telemetry(Canvas c, Data d, float dec, long seed) {
        float x0 = M + 40, gap = 40, pw = (W - 2 * M - 80 - gap) / 2, phh = 200, x1 = x0 + pw + gap;
        float y0 = 1330;
        text(c, "// LIVE TELEMETRY", x0, y0 - 40, BLUE, 24);
        text(c, " 60S @1HZ · MOTION 15S @4HZ", x0 + tw("// LIVE TELEMETRY", 24), y0 - 40, COMMENT, 24);
        stroke(LINE, 2); ln(c, x0, y0 - 26, x0 + 2 * pw + gap, y0 - 26);
        int N = Data.N;

        String pv = Float.isNaN(d.pwrW) ? "--" : String.format(Locale.US, "%s%.2f W", d.pwrIn ? "IN " : "", d.pwrW);
        int pc = d.pwrIn ? CYAN : YELLOW;
        panel(c, x0, y0, pw, phh, "POWER", decode(pv, dec, seed), pc);
        spark(c, x0, y0, pw, phh, d.hPwr, d.hLen, d.hHead, N, Math.max(2f, maxOf(d.hPwr, d.hLen, d.hHead, N) * 1.2f), 0f, pc, dec);

        panel(c, x1, y0, pw, phh, "NET", decode("↓" + rate(d.rxBps) + " ↑" + rate(d.txBps), dec, seed + 1), CYAN);
        float nmax = Math.max(64 * 1024f, Math.max(maxOf(d.hRx, d.hLen, d.hHead, N), maxOf(d.hTx, d.hLen, d.hHead, N)) * 1.2f);
        spark(c, x1, y0, pw, phh, d.hTx, d.hLen, d.hHead, N, nmax, 0f, MAGENTA, dec);
        spark(c, x1, y0, pw, phh, d.hRx, d.hLen, d.hHead, N, nmax, 0f, CYAN, dec);

        float y1 = y0 + phh + 50;
        int rc = d.ramPct > 85 ? RED : BLUE;
        panel(c, x0, y1, pw, phh, "RAM", decode(String.format(Locale.US, "%.0f%% USED", d.ramPct), dec, seed + 2), rc);
        spark(c, x0, y1, pw, phh, d.hRam, d.hLen, d.hHead, N, 100f, 0f, rc, dec);

        String tv = (Float.isNaN(d.tempC) ? "--" : String.format(Locale.US, "%.1f°C", d.tempC))
                + (Float.isNaN(d.headroom) ? "" : String.format(Locale.US, " · HR %.0f%%", Math.max(0, 1 - d.headroom) * 100));
        int tc = d.thermal >= 2 || (!Float.isNaN(d.tempC) && d.tempC >= 42) ? RED : d.thermal == 1 || (!Float.isNaN(d.tempC) && d.tempC >= 38) ? YELLOW : GREEN;
        panel(c, x1, y1, pw, phh, "THERM", decode(tv, dec, seed + 3), tc);
        spark(c, x1, y1, pw, phh, d.hTmp, d.hLen, d.hHead, N, 50f, 20f, tc, dec);

        float y2 = y1 + phh + 50;
        int MN = Data.MN;
        if (d.motionOn) {
            float la = Math.abs((float) Math.sqrt(sq(d.ax, d.mHead) + sq(d.ay, d.mHead) + sq(d.az, d.mHead)) - 9.81f) / 9.81f;
            panel(c, x0, y2, pw, phh, "ACCEL XYZ", d.mLen == 0 ? "--" : String.format(Locale.US, "%.2f G", la), FG);
            spark(c, x0, y2, pw, phh, d.az, d.mLen, d.mHead, MN, 12f, -12f, BLUE, dec);
            spark(c, x0, y2, pw, phh, d.ay, d.mLen, d.mHead, MN, 12f, -12f, GREEN, dec);
            spark(c, x0, y2, pw, phh, d.ax, d.mLen, d.mHead, MN, 12f, -12f, RED, dec);
            float om = (float) Math.sqrt(sq(d.gx, d.mHead) + sq(d.gy, d.mHead) + sq(d.gz, d.mHead));
            panel(c, x1, y2, pw, phh, "GYRO XYZ", d.mLen == 0 ? "--" : String.format(Locale.US, "%.2f RAD/S", om), FG);
            spark(c, x1, y2, pw, phh, d.gz, d.mLen, d.mHead, MN, 4f, -4f, BLUE, dec);
            spark(c, x1, y2, pw, phh, d.gy, d.mLen, d.mHead, MN, 4f, -4f, GREEN, dec);
            spark(c, x1, y2, pw, phh, d.gx, d.mLen, d.mHead, MN, 4f, -4f, RED, dec);
        } else {
            panel(c, x0, y2, pw, phh, "ACCEL XYZ", "OFF", COMMENT);
            panel(c, x1, y2, pw, phh, "GYRO XYZ", "OFF", COMMENT);
        }
    }

    private static float sq(float[] a, int head) { float v = a[(head - 1 + a.length) % a.length]; return v * v; }

    private static String rate(float bps) {
        if (bps >= 1048576) return String.format(Locale.US, "%.1fM", bps / 1048576f);
        if (bps >= 1024) return String.format(Locale.US, "%.0fK", bps / 1024f);
        return String.format(Locale.US, "%.0fB", bps);
    }

    private static float maxOf(float[] a, int len, int head, int cap) {
        float m = 0;
        for (int i = 0; i < len; i++) { float v = a[(head - 1 - i + cap) % cap]; if (v > m) m = v; }
        return m;
    }

    private void panel(Canvas c, float x, float y, float w, float h, String title, String value, int col) {
        stroke(LINE, 2);
        poly(c, true, x + 18, y, x + w, y, x + w, y + h - 18, x + w - 18, y + h, x, y + h, x, y + 18);
        text(c, title, x + 20, y + 36, COMMENT, 22);
        textR(c, value, x + w - 20, y + 36, col, 26);
        p.setStyle(Paint.Style.STROKE); p.setColor(LINE); p.setStrokeWidth(1.5f * s);
        p.setPathEffect(new DashPathEffect(new float[]{4 * s, 10 * s}, 0));
        float gy0 = y + 56, gy1 = y + h - 18;
        ln(c, x + 20, gy0 + (gy1 - gy0) / 3, x + w - 20, gy0 + (gy1 - gy0) / 3);
        ln(c, x + 20, gy0 + 2 * (gy1 - gy0) / 3, x + w - 20, gy0 + 2 * (gy1 - gy0) / 3);
        p.setPathEffect(null);
    }

    /** newest sample at the right edge; vmin..vmax mapped to graph height */
    private void spark(Canvas c, float x, float y, float w, float h, float[] a, int len, int head, int cap,
                       float vmax, float vmin, int col, float dec) {
        int n = Math.round(len * dec);
        if (n < 2) return;
        float gx0 = x + 20, gx1 = x + w - 20, gy0 = y + 56, gy1 = y + h - 18;
        path.reset();
        for (int i = 0; i < n; i++) {
            float v = a[(head - n + i + cap) % cap];
            float t = (v - vmin) / (vmax - vmin); t = t < 0 ? 0 : t > 1 ? 1 : t;
            float px = gx1 - (n - 1 - i) * (gx1 - gx0) / (cap - 1), py = gy1 - t * (gy1 - gy0);
            if (i == 0) path.moveTo(px * s, py * s); else path.lineTo(px * s, py * s);
        }
        g.setColor(alpha(col, 0.5f)); g.setStrokeWidth(6 * s); c.drawPath(path, g);
        stroke(col, 3); c.drawPath(path, p);
        float lv = a[(head - 1 + cap) % cap], lt = (lv - vmin) / (vmax - vmin); lt = lt < 0 ? 0 : lt > 1 ? 1 : lt;
        fill(col); c.drawCircle(gx1 * s, (gy1 - lt * (gy1 - gy0)) * s, 6 * s, p);
    }

    // ---------------------------------------------------------------- parts
    private void frame(Canvas c, int accent) {
        stroke(accent, 5); poly(c, false, M, M + 260, M, M + 70, M + 70, M, M + 260, M);
        stroke(COMMENT, 4);
        poly(c, false, W - M - 180, M, W - M, M, W - M, M + 180);
        poly(c, false, M, H - M - 180, M, H - M, M + 180, H - M);
        stroke(MAGENTA, 5); poly(c, false, W - M, H - M - 260, W - M, H - M - 70, W - M - 70, H - M, W - M - 260, H - M);
        fill(accent); poly(c, true, M + 300, M - 2, M + 520, M - 2, M + 502, M + 22, M + 300, M + 22);
    }

    private void rail(Canvas c, long clock, int col, boolean animate) {
        float r0 = H * 0.29f;
        stroke(LINE, 3);
        for (int i = 0; i < 24; i++) ln(c, W - M - 8, r0 + i * 40, W - M - 8 - (i % 4 == 0 ? 30 : 14), r0 + i * 40);
        float by = animate ? r0 + (float) ((Math.sin(clock / 2600.0) * 0.5 + 0.5) * 16 * 40) : r0 + 6 * 40;
        stroke(col, 6); ln(c, W - M - 8, by, W - M - 8, by + 8 * 40);
    }

    private void ring(Canvas c, float cx, float cy, float R, float rot, int inner, float a) {
        p.setStyle(Paint.Style.STROKE);
        p.setColor(alpha(LINE, a)); p.setStrokeWidth(4 * s);
        p.setPathEffect(new DashPathEffect(new float[]{120 * s, 30 * s}, mod(rot, 150f) * s));
        c.drawCircle(cx * s, cy * s, R * s, p);
        p.setColor(alpha(inner, 0.7f * a)); p.setStrokeWidth(3 * s);
        p.setPathEffect(new DashPathEffect(new float[]{6 * s, 22 * s}, mod(-rot * 1.6f, 28f) * s));
        c.drawCircle(cx * s, cy * s, (R - 40) * s, p);
        p.setPathEffect(null);
        stroke(alpha(MAGENTA, a), 5);
        ln(c, cx - R - 60, cy, cx - R + 20, cy); ln(c, cx + R - 20, cy, cx + R + 60, cy);
        ln(c, cx, cy - R - 60, cx, cy - R + 20); ln(c, cx, cy + R - 20, cx, cy + R + 60);
    }

    private void padlock(Canvas c, float cx, float cy, int col, float open, float k) {
        stroke(col, 7 * Math.max(0.7f, k));
        poly(c, true, cx - 70 * k, cy - 10 * k, cx + 70 * k, cy - 10 * k, cx + 70 * k, cy + 100 * k, cx - 70 * k, cy + 100 * k);
        float lift = open * 40 * k;
        oval.set((cx - 45 * k) * s, (cy - 95 * k - lift) * s, (cx + 45 * k) * s, (cy - 5 * k - lift) * s);
        c.drawArc(oval, 180, 180, false, p);
        ln(c, cx - 45 * k, cy - 50 * k - lift, cx - 45 * k, cy - 10 * k - lift);
        ln(c, cx + 45 * k, cy - 50 * k - lift, cx + 45 * k, cy - 10 * k);
        fill(col);
        c.drawCircle(cx * s, (cy + 35 * k) * s, 10 * k * s, p);
        poly(c, true, cx - 5 * k, cy + 40 * k, cx + 5 * k, cy + 40 * k, cx + 8 * k, cy + 70 * k, cx - 8 * k, cy + 70 * k);
    }

    private void sweep(Canvas c, long clock) {
        float ph = (clock % 8000) / 8000f, y = M + ph * (H - 2 * M);
        g.setColor(alpha(CYAN, 0.35f * (1 - ph))); g.setStrokeWidth(6 * s);
        c.drawLine(M * s, y * s, (W - M) * s, y * s, g);
        stroke(alpha(CYAN, 0.6f * (1 - ph)), 2); ln(c, M, y, W - M, y);
    }

    private void readouts(Canvas c, String l1, String l2, String tag, int tagCol, boolean cursor, float dec, long seed) {
        float y = H - M - 210;
        text(c, decode(l1, dec, seed), M + 24, y, COMMENT, 30);
        float x = text(c, decode(l2, dec, seed + 1), M + 24, y + 44, COMMENT, 30);
        text(c, decode(tag, dec, seed + 2), x, y + 44, tagCol, 30);
        float x3 = text(c, decode("TN//NIGHT_GRID ", dec, seed + 3), M + 24, y + 88, COMMENT, 30);
        if (cursor) { fill(RED); rect(c, x3, y + 66, x3 + 16, y + 92); }
    }


    // ================================================================ v20 screen saver (DreamService), 1 Hz
    static final String[] CNAME = {"UNK", "GPS", "SBAS", "GLONASS", "QZSS", "BEIDOU", "GALILEO", "NAVIC"};
    static final String[] CTAG = {"U", "G", "S", "R", "J", "C", "E", "I"};
    static final int[] CCOL = {COMMENT, BLUE, COMMENT, RED, MAGENTA, YELLOW, CYAN, ORANGE};
    private long ldAt; private long ldCross;

    public void drawAod(Canvas c, Data d) {
        c.drawColor(BLACK);
        c.save();
        long minute = d.wallMs / 60000;                      // burn-in: jump to a new offset every minute, ±28 units
        Random rr = new Random(minute * 7919);
        c.translate((rr.nextFloat() * 56 - 28) * s, (rr.nextFloat() * 56 - 28 + Math.max(0, (H - 2670) / 2)) * s);   // content ~230..2440, centred
        float A = 0.8f;
        // clock
        Calendar n = Calendar.getInstance(); n.setTimeInMillis(d.wallMs);
        String hm = d.h24 ? String.format(Locale.US, "%02d:%02d", n.get(Calendar.HOUR_OF_DAY), n.get(Calendar.MINUTE))
                : String.format(Locale.US, "%d:%02d", (n.get(Calendar.HOUR_OF_DAY) + 11) % 12 + 1, n.get(Calendar.MINUTE));
        p.setTypeface(clockTf != null ? clockTf : Typeface.MONOSPACE);
        float cw = tw(hm, 210);
        text(c, hm, 720 - cw / 2, 330, alpha(BLUE, A), 210);
        p.setTypeface(Typeface.MONOSPACE);
        Calendar wk = Calendar.getInstance(); wk.setFirstDayOfWeek(Calendar.MONDAY); wk.setMinimalDaysInFirstWeek(4); wk.setTimeInMillis(d.wallMs);
        textC(c, String.format(Locale.US, "%ta %<td %<tb · WK %d", n, wk.get(Calendar.WEEK_OF_YEAR)).toUpperCase(Locale.US)
                + (d.charging ? String.format(Locale.US, " · CHG %d%%", d.battery) : ""), 720, 400, alpha(COMMENT, 1f), 30);
        text(c, "// GNSS RECEIVER", M + 40, 500, alpha(BLUE, A), 26);
        textR(c, "SYS//DREAM", W - M - 40, 500, alpha(COMMENT, 1f), 22);
        stroke(alpha(LINE, 1f), 2); ln(c, M + 40, 516, W - M - 40, 516);

        // sky plot
        float cx = 720, cy = 940, rp = 360;
        stroke(LINE, 2);
        c.drawCircle(cx * s, cy * s, rp * s, p); c.drawCircle(cx * s, cy * s, rp * 2 / 3 * s, p); c.drawCircle(cx * s, cy * s, rp / 3 * s, p);
        ln(c, cx - rp, cy, cx + rp, cy); ln(c, cx, cy - rp, cx, cy + rp);
        textC(c, "N", cx, cy - rp - 14, COMMENT, 24); textC(c, "S", cx, cy + rp + 34, COMMENT, 24);
        text(c, "E", cx + rp + 12, cy + 8, COMMENT, 24); textR(c, "W", cx - rp - 12, cy + 8, COMMENT, 24);
        text(c, "30°", cx + 6, cy - rp * 2 / 3 - 6, COMMENT, 16); text(c, "60°", cx + 6, cy - rp / 3 - 6, COMMENT, 16);
        int[] inView = new int[8], used = new int[8]; float[] cnSum = new float[8]; int l5 = 0, nUsed = 0;
        for (int i = 0; i < d.gN; i++) {
            int k = d.gCons[i] < 0 || d.gCons[i] > 7 ? 0 : d.gCons[i];
            inView[k]++; cnSum[k] += d.gCn0[i];
            if (d.gUsed[i]) { used[k]++; nUsed++; }
            if (d.gL5[i]) l5++;
            if (d.gEl[i] <= 0 && d.gAz[i] == 0) continue;
            float[] q = sp(cx, cy, rp, Math.max(0, d.gEl[i]), d.gAz[i]);
            float r = 6 + Math.max(0, Math.min(1, (d.gCn0[i] - 15) / 30f)) * 12;
            int col = alpha(CCOL[k], A);
            if (d.gUsed[i]) { fill(col); c.drawCircle(q[0] * s, q[1] * s, r * s, p); }
            else { stroke(col, 2); c.drawCircle(q[0] * s, q[1] * s, r * s, p); }
            if (d.gL5[i]) { stroke(alpha(col, 0.6f), 2); c.drawCircle(q[0] * s, q[1] * s, (r + 7) * s, p); }
            text(c, CTAG[k] + d.gSvid[i], q[0] + r + 4, q[1] + 6, alpha(COMMENT, 1f), 16);
        }
        if (d.gN == 0) textC(c, d.gState != null ? d.gState : "SEARCHING FOR SATELLITES", cx, cy + 10, alpha(YELLOW, A), 26);

        // constellation table
        float y = 1390;
        text(c, "SYSTEM", M + 80, y, COMMENT, 22); textR(c, "VIEW", 800, y, COMMENT, 22); textR(c, "USED", 980, y, COMMENT, 22); textR(c, "C/N0", 1200, y, COMMENT, 22);
        y += 46;
        int[] order = {1, 6, 3, 5, 4, 7, 2};
        for (int k : order) {
            if (inView[k] == 0) continue;
            fill(alpha(CCOL[k], A)); rect(c, M + 80, y - 18, M + 96, y - 2);
            text(c, CNAME[k], M + 112, y, alpha(FG, A), 26);
            textR(c, String.valueOf(inView[k]), 800, y, alpha(FG, A), 26);
            textR(c, String.valueOf(used[k]), 980, y, alpha(used[k] > 0 ? GREEN : COMMENT, A), 26);
            textR(c, String.format(Locale.US, "%.0f", cnSum[k] / inView[k]), 1200, y, alpha(FG, A), 26);
            y += 42;
        }
        y = Math.max(y, 1700) + 10;

        // C/N0 bars, strongest first
        Integer[] idx = new Integer[d.gN];
        for (int i = 0; i < d.gN; i++) idx[i] = i;
        final Data fd = d;
        java.util.Arrays.sort(idx, new java.util.Comparator<Integer>() { public int compare(Integer a, Integer b) { return Float.compare(fd.gCn0[b], fd.gCn0[a]); } });
        int nb = Math.min(48, d.gN);
        float bx0 = M + 80, bx1 = W - M - 80, bw = (bx1 - bx0) / 48f, by = y + 190;
        text(c, "C/N0 DB-HZ", bx0, y, COMMENT, 20);
        for (int lv : new int[]{20, 30, 40, 50}) { stroke(alpha(LINE, 1f), 1); float yy = by - lv / 55f * 170; ln(c, bx0, yy, bx1, yy); text(c, String.valueOf(lv), bx1 + 6, yy + 6, COMMENT, 14); }
        for (int j = 0; j < nb; j++) {
            int i = idx[j], k = d.gCons[i] < 0 || d.gCons[i] > 7 ? 0 : d.gCons[i];
            float h = Math.max(2, Math.min(55, d.gCn0[i])) / 55f * 170;
            if (d.gUsed[i]) fill(alpha(CCOL[k], A)); else stroke(alpha(CCOL[k], A), 2);
            rect(c, bx0 + j * bw + 2, by - h, bx0 + (j + 1) * bw - 3, by);
        }
        y = by + 60;

        // fix
        String fx;
        if (d.gFixAt > 0 && d.wallMs - d.gFixAt < 15000) {
            fx = String.format(Locale.US, "FIX 3D · ±%.0f M · %.2f, %.2f%s · %d/%d SATS", d.gAcc, d.gLat, d.gLon,
                    Double.isNaN(d.gAlt) ? "" : String.format(Locale.US, " · %.0f M", d.gAlt), nUsed, d.gN);
        } else fx = "NO FIX · " + d.gN + " IN VIEW" + (d.gStart > 0 ? " · " + mmss(d.wallMs - d.gStart) : "");
        textC(c, fx, 720, y, alpha(d.gFixAt > 0 && d.wallMs - d.gFixAt < 15000 ? GREEN : YELLOW, A), 26);
        textC(c, (d.gTtff >= 0 ? String.format(Locale.US, "TTFF %.1f S · ", d.gTtff / 1000.0) : "") + "DUAL-BAND SIGNALS " + l5, 720, y + 40, COMMENT, 22);

        // Voyagers
        y += 130;
        text(c, "// DEEP SPACE · VOYAGER", M + 40, y, alpha(MAGENTA, A), 26);
        stroke(alpha(LINE, 1f), 2); ln(c, M + 40, y + 16, W - M - 40, y + 16);
        y += 70;
        double LD = Astro.C_KMS * 86400;
        for (int v = 0; v < 2; v++) {
            double km = Astro.voyagerKm(v, d.wallMs);
            long lt = (long) (km / Astro.C_KMS);
            text(c, v == 0 ? "VOYAGER 1" : "VOYAGER 2", M + 80, y, alpha(FG, A), 30);
            textR(c, String.format(Locale.US, "%,.0f KM", km).replace(',', ' '), W - M - 80, y, alpha(CYAN, A), 30);
            String sub = String.format(Locale.US, "%.3f AU · LIGHT %02d:%02d:%02d", km / Astro.AU_KM, lt / 3600, (lt / 60) % 60, lt % 60);
            if (v == 0) {
                if (km < LD) {
                    if (d.wallMs - ldAt > 60000L || ldCross == 0) { ldAt = d.wallMs; ldCross = crossLd(d.wallMs, LD); }
                    if (ldCross > 0) sub += " · 1 LIGHT-DAY IN " + dhm(ldCross - d.wallMs);
                } else sub += " · BEYOND 1 LIGHT-DAY";
            }
            text(c, sub, M + 80, y + 40, COMMENT, 22);
            y += 110;
        }
        // space weather snapshot (cached by the wallpaper)
        if (!Double.isNaN(d.kp) && d.swUpd > 0 && d.wallMs - d.swUpd < 6 * 3600000L)
            textC(c, String.format(Locale.US, "KP %.1f · BZ %+.1f NT · %s · G%d", d.kp, d.bz, SpaceWx.flareClass(d.xrFlux), Math.max(0, d.scG)), 720, y + 20,
                    d.scG >= 1 ? alpha(YELLOW, A) : COMMENT, 24);
        c.restore();
    }

    private static long crossLd(long now, double LD) {
        long a = now, b = now + 400L * 86400000L;
        if (Astro.voyagerKm(0, b) < LD) return 0;
        for (int i = 0; i < 40 && b - a > 1000; i++) { long m = (a + b) / 2; if (Astro.voyagerKm(0, m) < LD) a = m; else b = m; }
        return b;
    }

    // ---------------------------------------------------------------- helpers
    private void bar(Canvas c, float x0, float y, float frac, int col) {
        int on = Math.round(Math.max(0, Math.min(1, frac)) * 40);
        for (int i = 0; i < 40; i++) { fill(i < on ? col : LINE); rect(c, x0 + i * 16, y, x0 + i * 16 + 11, y + 10); }
    }
    private float diag(Canvas c, String k, String v, int col, float x, float y, float lw, float dec, long seed) {
        text(c, decode(k, dec, seed), x, y, COMMENT, 24);
        text(c, decode(v, dec, seed + 100), x + lw, y, col, 24);
        return y + 40;
    }
    private static float mod(float a, float m) { float r = a % m; return r < 0 ? r + m : r; }
    private static float seg(float u, float a, float b) { float v = (u - a) / (b - a); return v < 0 ? 0 : v > 1 ? 1 : v; }
    private static float ease(float v) { return v * v * (3 - 2 * v); }
    private static int alpha(int col, float a) {
        a = a < 0 ? 0 : a > 1 ? 1 : a;
        return (col & 0x00FFFFFF) | (Math.round(((col >>> 24) & 0xFF) * a) << 24);
    }
    private static String decode(String t, float prog, long seed) {
        if (prog >= 1f) return t;
        char[] o = t.toCharArray();
        int k = (int) (prog * o.length);
        Random r = new Random(seed * 31 + o.length);
        String pool = "0123456789ABCDEF#%&/<>";
        for (int i = k; i < o.length; i++) if (o[i] != ' ') o[i] = pool.charAt(r.nextInt(pool.length()));
        return new String(o);
    }
    private void stroke(int col, float w) { p.setStyle(Paint.Style.STROKE); p.setColor(col); p.setStrokeWidth(w * s); p.setPathEffect(null); }
    private void fill(int col) { p.setStyle(Paint.Style.FILL); p.setColor(col); p.setPathEffect(null); }
    private void ln(Canvas c, float x0, float y0, float x1, float y1) { c.drawLine(x0 * s, y0 * s, x1 * s, y1 * s, p); }
    private void rect(Canvas c, float l, float t, float r, float b) { c.drawRect(l * s, t * s, r * s, b * s, p); }
    private void poly(Canvas c, boolean close, float... xy) {
        path.reset(); path.moveTo(xy[0] * s, xy[1] * s);
        for (int i = 2; i < xy.length; i += 2) path.lineTo(xy[i] * s, xy[i + 1] * s);
        if (close) path.close();
        c.drawPath(path, p);
    }
    // ---- user text size: scales body text (< 40 units); display numerals (clock, temperature, Kp) keep their size
    private float ts = 1f;
    private float tsHome = 1f, tsLock = 1f;
    public void setTextScale(float k) { ts = tsHome = tsLock = clampTs(k); }
    /** separate sizes; draw() picks home for the dashboard, lock for the lock screen and the unlock sequence */
    public void setTextScales(float home, float lock) { tsHome = clampTs(home); tsLock = clampTs(lock); }
    static float clampTs(float k) { return Math.max(0.85f, Math.min(1.25f, k)); }
    public static float textScaleFor(int pref, float systemScale) { return clampTs(pref == 0 ? systemScale : pref / 100f); }
    /** prefs: textScale (0 = system, else %); textScaleLock / textScaleDream (-1 = same as home, else %) */
    public static float[] textScales(int home, int lock, int dream, float systemScale) {
        float h = textScaleFor(home, systemScale);
        return new float[]{h, lock < 0 ? h : clampTs(lock / 100f), dream < 0 ? h : clampTs(dream / 100f)};
    }
    private float z(float size) { return size < 40 ? size * ts : size; }
    private float tw(String t, float size) { p.setTextSize(z(size) * s); return p.measureText(t) / s; }
    private float text(Canvas c, String t, float x, float y, int col, float size) {
        p.setStyle(Paint.Style.FILL); p.setPathEffect(null); p.setColor(col); p.setTextSize(z(size) * s);
        c.drawText(t, x * s, y * s, p);
        return x + p.measureText(t) / s;
    }
    /** centred; returns right edge */
    private float textC(Canvas c, String t, float cx, float y, int col, float size) { return text(c, t, cx - tw(t, size) / 2, y, col, size); }
    /** right-aligned; returns left edge */
    private float textR(Canvas c, String t, float rx, float y, int col, float size) { float x = rx - tw(t, size); text(c, t, x, y, col, size); return x; }
}
