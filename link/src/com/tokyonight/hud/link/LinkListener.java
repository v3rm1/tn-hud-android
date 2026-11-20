package com.tokyonight.hud.link;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads notifications and media sessions and pushes a compact state to TN-HUD Live (explicit broadcast,
 * signature permission). Debounced: bursts of notifications produce one update.
 */
public class LinkListener extends NotificationListenerService {
    static final String HUD = "com.tokyonight.hud.live", STATE = "com.tokyonight.hud.LINK_STATE", PERM = "com.tokyonight.hud.permission.LINK";
    static final int MAX_NOTES = 40;
    static LinkListener instance;
    /** reply actions of the notifications last pushed, by key */
    final Map<String, Notification.Action> replies = new HashMap<String, Notification.Action>();

    final Handler h = new Handler(Looper.getMainLooper());
    MediaSessionManager msm;
    MediaController media;
    final Runnable push = new Runnable() { public void run() { pushNow(); } };

    final MediaController.Callback mcb = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState s) { schedule(); }
        @Override public void onMetadataChanged(MediaMetadata m) { schedule(); }
        @Override public void onSessionDestroyed() { pickSession(null); schedule(); }
    };

    final MediaSessionManager.OnActiveSessionsChangedListener sessions = new MediaSessionManager.OnActiveSessionsChangedListener() {
        public void onActiveSessionsChanged(List<MediaController> l) { pickSession(l); schedule(); }
    };

    @Override public void onListenerConnected() {
        instance = this;
        try {
            msm = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
            ComponentName me = new ComponentName(this, LinkListener.class);
            msm.addOnActiveSessionsChangedListener(sessions, me, h);
            pickSession(msm.getActiveSessions(me));
        } catch (Throwable ignored) { }
        schedule();
    }

    public void onListenerDisconnected() {                  // API 24+: no @Override against the API 23 jar
        instance = null;
        try { if (msm != null) msm.removeOnActiveSessionsChangedListener(sessions); } catch (Throwable ignored) { }
        if (media != null) try { media.unregisterCallback(mcb); } catch (Throwable ignored) { }
        media = null;
        Intent i = base(); i.putExtra("access", false);
        send(i);
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) { schedule(); }
    @Override public void onNotificationRemoved(StatusBarNotification sbn) { schedule(); }
    @Override public void onInterruptionFilterChanged(int f) { schedule(); }

    /** prefer the playing session, else the first one */
    void pickSession(List<MediaController> l) {
        MediaController pick = null;
        if (l != null) {
            for (MediaController c : l) {
                PlaybackState s = c.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING) { pick = c; break; }
            }
            if (pick == null && !l.isEmpty()) pick = l.get(0);
        }
        if (media != null && (pick == null || !media.getSessionToken().equals(pick.getSessionToken()))) {
            try { media.unregisterCallback(mcb); } catch (Throwable ignored) { }
        }
        if (pick != null && (media == null || !media.getSessionToken().equals(pick.getSessionToken()))) pick.registerCallback(mcb, h);
        media = pick;
    }

    void schedule() { h.removeCallbacks(push); h.postDelayed(push, 350); }

    Intent base() {
        Intent i = new Intent(STATE).setComponent(new ComponentName(HUD, HUD + ".LinkRx"));
        i.putExtra("v", 1).putExtra("at", System.currentTimeMillis()).putExtra("access", true);
        return i;
    }

    void send(Intent i) { try { sendBroadcast(i, PERM); } catch (Throwable ignored) { } }

    String label(String pkg, Map<String, String> cache) {
        String l = cache.get(pkg);
        if (l != null) return l;
        try { PackageManager pm = getPackageManager(); l = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0))); }
        catch (Exception e) { l = pkg; }
        cache.put(pkg, l);
        return l;
    }

    void pushNow() {
        Intent i = base();
        StatusBarNotification[] all;
        try { all = getActiveNotifications(); } catch (Throwable t) { all = null; }
        Map<String, Integer> badge = new HashMap<String, Integer>();
        Map<String, String> labels = new HashMap<String, String>();
        ArrayList<String> key = new ArrayList<String>(), pkg = new ArrayList<String>(), app = new ArrayList<String>(), title = new ArrayList<String>(), text = new ArrayList<String>();
        ArrayList<Long> when = new ArrayList<Long>();
        ArrayList<Boolean> clear = new ArrayList<Boolean>(), reply = new ArrayList<Boolean>();
        replies.clear();
        ArrayList<PendingIntent> intent = new ArrayList<PendingIntent>();
        if (all != null) {
            java.util.Arrays.sort(all, new java.util.Comparator<StatusBarNotification>() {
                public int compare(StatusBarNotification a, StatusBarNotification b) { return Long.compare(b.getPostTime(), a.getPostTime()); }
            });
            for (StatusBarNotification s : all) {
                Notification n = s.getNotification();
                boolean ongoing = (n.flags & Notification.FLAG_ONGOING_EVENT) != 0 || (n.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0;
                boolean summary = (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0;
                Bundle x = n.extras;
                CharSequence t = x == null ? null : x.getCharSequence(Notification.EXTRA_TITLE);
                CharSequence b = x == null ? null : x.getCharSequence(Notification.EXTRA_BIG_TEXT);
                if (b == null && x != null) b = x.getCharSequence(Notification.EXTRA_TEXT);
                if (!ongoing && !summary) {
                    Integer c = badge.get(s.getPackageName());
                    badge.put(s.getPackageName(), (c == null ? 0 : c) + Math.max(1, n.number));
                }
                if (summary || key.size() >= MAX_NOTES || (t == null && b == null)) continue;
                key.add(s.getKey()); pkg.add(s.getPackageName()); app.add(label(s.getPackageName(), labels));
                title.add(t == null ? "" : clip(t.toString(), 120)); text.add(b == null ? "" : clip(b.toString(), 300));
                when.add(n.when > 0 ? n.when : s.getPostTime()); clear.add(s.isClearable()); intent.add(n.contentIntent);
                Notification.Action ra = replyAction(n);
                reply.add(ra != null);
                if (ra != null) replies.put(s.getKey(), ra);
            }
        }
        String[] bp = badge.keySet().toArray(new String[0]);
        int[] bn = new int[bp.length];
        for (int k = 0; k < bp.length; k++) bn[k] = badge.get(bp[k]);
        i.putExtra("bPkg", bp).putExtra("bN", bn);
        i.putExtra("nKey", key.toArray(new String[0])).putExtra("nPkg", pkg.toArray(new String[0])).putExtra("nApp", app.toArray(new String[0]))
                .putExtra("nTitle", title.toArray(new String[0])).putExtra("nText", text.toArray(new String[0]));
        long[] w = new long[when.size()]; boolean[] cl = new boolean[clear.size()], rp = new boolean[reply.size()];
        for (int k = 0; k < w.length; k++) { w[k] = when.get(k); cl[k] = clear.get(k); rp[k] = reply.get(k); }
        i.putExtra("nWhen", w).putExtra("nClear", cl).putExtra("nReply", rp).putExtra("nIntent", intent.toArray(new Parcelable[0]));
        // media
        MediaController m = media;
        if (m != null) {
            MediaMetadata md = m.getMetadata();
            PlaybackState ps = m.getPlaybackState();
            i.putExtra("mPkg", m.getPackageName()).putExtra("mApp", label(m.getPackageName(), labels));
            if (md != null) {
                i.putExtra("mTitle", md.getString(MediaMetadata.METADATA_KEY_TITLE)).putExtra("mArtist", firstNonNull(md.getString(MediaMetadata.METADATA_KEY_ARTIST), md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)))
                        .putExtra("mAlbum", md.getString(MediaMetadata.METADATA_KEY_ALBUM)).putExtra("mDur", md.getLong(MediaMetadata.METADATA_KEY_DURATION));
            }
            if (ps != null) i.putExtra("mState", ps.getState()).putExtra("mPos", ps.getPosition()).putExtra("mPosAt", ps.getLastPositionUpdateTime()).putExtra("mSpeed", ps.getPlaybackSpeed());
        }
        try { i.putExtra("dnd", getCurrentInterruptionFilter()); } catch (Throwable ignored) { }
        send(i);
    }

    static String firstNonNull(String a, String b) { return a != null ? a : b; }
    static String clip(String s, int n) { return s.length() > n ? s.substring(0, n) + "…" : s; }

    // ---- commands from the HUD
    void media(String what) {
        MediaController m = media;
        if (m == null) return;
        MediaController.TransportControls t = m.getTransportControls();
        PlaybackState s = m.getPlaybackState();
        if ("toggle".equals(what)) { if (s != null && s.getState() == PlaybackState.STATE_PLAYING) t.pause(); else t.play(); }
        else if ("next".equals(what)) t.skipToNext();
        else if ("prev".equals(what)) t.skipToPrevious();
        schedule();
    }

    /** the first action carrying a free-text RemoteInput (messaging apps' "Reply") */
    static Notification.Action replyAction(Notification n) {
        if (n.actions == null) return null;
        for (Notification.Action a : n.actions) {
            android.app.RemoteInput[] ri = a.getRemoteInputs();
            if (ri == null || a.actionIntent == null) continue;
            for (android.app.RemoteInput r : ri) if (r.getAllowFreeFormInput()) return a;
        }
        return null;
    }

    void reply(String key, String text) {
        Notification.Action a = key == null ? null : replies.get(key);
        if (a == null || text == null) return;
        try {
            Intent fill = new Intent();
            Bundle res = new Bundle();
            for (android.app.RemoteInput r : a.getRemoteInputs()) res.putCharSequence(r.getResultKey(), text);
            android.app.RemoteInput.addResultsToIntent(a.getRemoteInputs(), fill, res);
            a.actionIntent.send(this, 0, fill);
        } catch (Throwable ignored) { }
        schedule();
    }

    void dismiss(String key) { try { if (key == null) cancelAllNotifications(); else cancelNotification(key); } catch (Throwable ignored) { } schedule(); }

    void dnd(int mode) { try { requestInterruptionFilter(mode); } catch (Throwable ignored) { } schedule(); }
}
