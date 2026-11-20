package com.tokyonight.hud.live;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Parcelable;

import java.util.HashMap;
import java.util.Map;

/**
 * Last state pushed by the optional companion app (TN-HUD Link, which holds notification access):
 * badges per package, recent notifications, the active media session, Do Not Disturb.
 * Kept in memory (same process as the wallpaper and the launcher); listeners are told on the main thread.
 */
final class Link {
    static final String PKG = "com.tokyonight.hud.link", CMD = "com.tokyonight.hud.LINK_CMD", PERM = "com.tokyonight.hud.permission.LINK";

    interface Listener { void onLink(); }

    static volatile long at;                  // wall time of the last push, 0 = never
    static volatile boolean access;
    static final Map<String, Integer> badges = new HashMap<String, Integer>();
    static String[] nKey = new String[0], nPkg = new String[0], nApp = new String[0], nTitle = new String[0], nText = new String[0];
    static long[] nWhen = new long[0];
    static boolean[] nClear = new boolean[0];
    static PendingIntent[] nIntent = new PendingIntent[0];
    static String mPkg, mApp, mTitle, mArtist, mAlbum;
    static int mState;                       // PlaybackState constants: 3 playing, 2 paused
    static long mPos, mPosAt, mDur; static float mSpeed = 1f;
    static int dnd;                          // NotificationListenerService interruption filter (1 = all)

    private static final java.util.List<Listener> listeners = new java.util.ArrayList<Listener>();

    static synchronized void listen(Listener l, boolean add) { if (add) { if (!listeners.contains(l)) listeners.add(l); } else listeners.remove(l); }

    static boolean installed(Context c) {
        try { c.getPackageManager().getPackageInfo(PKG, 0); return true; } catch (Exception e) { return false; }
    }

    static boolean live() { return access && at > 0 && System.currentTimeMillis() - at < 6 * 3600000L; }

    static int badge(String pkg) {
        if (pkg == null || !live()) return 0;
        synchronized (badges) { Integer n = badges.get(pkg); return n == null ? 0 : n; }
    }

    static boolean mediaActive() { return live() && mTitle != null && (mState == 3 || mState == 2 || mState == 6); }

    /** called by LinkRx on the main thread */
    static void update(Intent i) {
        at = i.getLongExtra("at", System.currentTimeMillis());
        access = i.getBooleanExtra("access", false);
        if (access) {
            String[] bp = i.getStringArrayExtra("bPkg"); int[] bn = i.getIntArrayExtra("bN");
            synchronized (badges) {
                badges.clear();
                if (bp != null && bn != null) for (int k = 0; k < bp.length && k < bn.length; k++) badges.put(bp[k], bn[k]);
            }
            nKey = orEmpty(i.getStringArrayExtra("nKey")); nPkg = orEmpty(i.getStringArrayExtra("nPkg")); nApp = orEmpty(i.getStringArrayExtra("nApp"));
            nTitle = orEmpty(i.getStringArrayExtra("nTitle")); nText = orEmpty(i.getStringArrayExtra("nText"));
            long[] w = i.getLongArrayExtra("nWhen"); nWhen = w == null ? new long[0] : w;
            boolean[] cl = i.getBooleanArrayExtra("nClear"); nClear = cl == null ? new boolean[0] : cl;
            Parcelable[] pi = i.getParcelableArrayExtra("nIntent");
            nIntent = new PendingIntent[pi == null ? 0 : pi.length];
            for (int k = 0; k < nIntent.length; k++) nIntent[k] = pi[k] instanceof PendingIntent ? (PendingIntent) pi[k] : null;
            mPkg = i.getStringExtra("mPkg"); mApp = i.getStringExtra("mApp"); mTitle = i.getStringExtra("mTitle");
            mArtist = i.getStringExtra("mArtist"); mAlbum = i.getStringExtra("mAlbum");
            mState = i.getIntExtra("mState", 0); mPos = i.getLongExtra("mPos", 0); mPosAt = i.getLongExtra("mPosAt", 0);
            mDur = i.getLongExtra("mDur", 0); mSpeed = i.getFloatExtra("mSpeed", 1f);
            dnd = i.getIntExtra("dnd", 1);
        } else {
            synchronized (badges) { badges.clear(); }
            nKey = new String[0]; nPkg = nKey; nApp = nKey; nTitle = nKey; nText = nKey; nWhen = new long[0]; nClear = new boolean[0]; nIntent = new PendingIntent[0];
            mTitle = null; mPkg = null;
        }
        Listener[] ls;
        synchronized (Link.class) { ls = listeners.toArray(new Listener[0]); }
        for (Listener l : ls) try { l.onLink(); } catch (Throwable ignored) { }
    }

    static String[] orEmpty(String[] a) { return a == null ? new String[0] : a; }

    static void send(Context c, String cmd, String key, int mode) {
        try {
            Intent i = new Intent(CMD).setComponent(new ComponentName(PKG, PKG + ".LinkCmdRx")).putExtra("cmd", cmd);
            if (key != null) i.putExtra("key", key);
            i.putExtra("mode", mode);
            c.sendBroadcast(i, PERM);
        } catch (Throwable ignored) { }
    }
}
