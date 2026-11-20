package com.tokyonight.hud.live;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;

/**
 * "$ command" in the launcher pop-up: runs the line in Termux through its RUN_COMMAND service and shows the output.
 * Termux side, once: install Termux (F-Droid / GitHub build) and set allow-external-apps = true in
 * ~/.termux/termux.properties. This side: the com.termux.permission.RUN_COMMAND runtime permission.
 * The result comes back through a mutable PendingIntent to TermuxRx, which hands it to the waiting callback.
 */
final class Termux {
    static final String PKG = "com.termux", PERM = "com.termux.permission.RUN_COMMAND";
    static final String SETUP = "In Termux: echo 'allow-external-apps = true' >> ~/.termux/termux.properties";

    interface Result { void on(String stdout, String stderr, int exit, String err); }

    static Result waiting;
    static int seq;

    static boolean installed(Context c) { try { c.getPackageManager().getPackageInfo(PKG, 0); return true; } catch (Exception e) { return false; } }
    static boolean allowed(Context c) { return c.checkSelfPermission(PERM) == PackageManager.PERMISSION_GRANTED; }

    /** throws when Termux refuses the start (usually allow-external-apps not set) */
    static void run(Context c, String cmd, Result r) {
        waiting = r;
        Intent back = new Intent(c, TermuxRx.class).putExtra("seq", ++seq);
        int mutable = android.os.Build.VERSION.SDK_INT >= 31 ? 1 << 25 : 0;    // PendingIntent.FLAG_MUTABLE: Termux fills in the result
        PendingIntent pi = PendingIntent.getBroadcast(c, 7100, back, PendingIntent.FLAG_UPDATE_CURRENT | mutable);
        Intent i = new Intent("com.termux.RUN_COMMAND").setComponent(new ComponentName(PKG, "com.termux.app.RunCommandService"));
        i.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash");
        i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{"-lc", cmd});
        i.putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home");
        i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
        i.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pi);
        try { Context.class.getMethod("startForegroundService", Intent.class).invoke(c, i); }   // API 26+, absent from the API 23 jar
        catch (java.lang.reflect.InvocationTargetException e) { throw new RuntimeException(e.getCause()); }
        catch (ReflectiveOperationException e) { c.startService(i); }
    }

    /** called by TermuxRx */
    static void deliver(Intent i) {
        Result r = waiting;
        if (r == null || i.getIntExtra("seq", -1) != seq) return;
        waiting = null;
        Bundle b = i.getBundleExtra("result");
        if (b == null) { r.on("", "", -1, "no result from Termux"); return; }
        String err = b.getString("errmsg");
        r.on(b.getString("stdout", ""), b.getString("stderr", ""), b.getInt("exitCode", -1), err == null || err.isEmpty() ? null : err);
    }
}
