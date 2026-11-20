package com.tokyonight.hud.live;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;

/**
 * Quick toggles that an ordinary app can really switch, given the right grant:
 *   WRITE_SETTINGS (user switch in Android settings): auto-rotate, adaptive brightness, brightness level
 *   WRITE_SECURE_SETTINGS (one-time adb grant): battery saver, location
 * Wi-Fi, Bluetooth, mobile data and airplane mode can't be switched by normal apps on current Android; the panel
 * opens the system panels for those instead. Every setter reads the state back, so a refused write shows as such.
 */
final class Toggles {
    static final String SECURE = "android.permission.WRITE_SECURE_SETTINGS";
    static final String ADB_GRANT = "adb shell pm grant com.tokyonight.hud.live android.permission.WRITE_SECURE_SETTINGS";

    static boolean canWrite(Context c) { try { return Settings.System.canWrite(c); } catch (Throwable t) { return false; } }
    static boolean canSecure(Context c) { return c.checkSelfPermission(SECURE) == PackageManager.PERMISSION_GRANTED; }

    // ---- battery saver (Settings.Global "low_power", the same switch `settings put global low_power 1` flips)
    static boolean saver(Context c) {
        try { return ((android.os.PowerManager) c.getSystemService(Context.POWER_SERVICE)).isPowerSaveMode(); } catch (Throwable t) { return false; }
    }
    static boolean setSaver(Context c, boolean on) {
        try { Settings.Global.putInt(c.getContentResolver(), "low_power", on ? 1 : 0); } catch (Throwable ignored) { }
        return saver(c) == on;
    }

    // ---- location
    static boolean location(Context c) {
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                Object lm = c.getSystemService(Context.LOCATION_SERVICE);
                return (Boolean) lm.getClass().getMethod("isLocationEnabled").invoke(lm);
            }
            return Settings.Secure.getInt(c.getContentResolver(), Settings.Secure.LOCATION_MODE, 0) != 0;
        } catch (Throwable t) { return false; }
    }
    static boolean setLocation(Context c, boolean on) {
        try {                                                 // system API, allowed with WRITE_SECURE_SETTINGS
            Object lm = c.getSystemService(Context.LOCATION_SERVICE);
            lm.getClass().getMethod("setLocationEnabledForUser", boolean.class, android.os.UserHandle.class).invoke(lm, on, android.os.Process.myUserHandle());
        } catch (Throwable t) {
            try { Settings.Secure.putInt(c.getContentResolver(), Settings.Secure.LOCATION_MODE, on ? 3 : 0); } catch (Throwable ignored) { }
        }
        return location(c) == on;
    }

    // ---- WRITE_SETTINGS group
    static boolean autoRotate(Context c) { return sys(c, Settings.System.ACCELEROMETER_ROTATION, 0) == 1; }
    static boolean setAutoRotate(Context c, boolean on) { return put(c, Settings.System.ACCELEROMETER_ROTATION, on ? 1 : 0); }
    static boolean autoBright(Context c) { return sys(c, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC; }
    static boolean setAutoBright(Context c, boolean on) {
        return put(c, Settings.System.SCREEN_BRIGHTNESS_MODE, on ? Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC : Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
    }
    static int brightness(Context c) { return sys(c, Settings.System.SCREEN_BRIGHTNESS, 128); }
    /** 0..255; turns adaptive brightness off, as the system slider does */
    static void setBrightness(Context c, int v) {
        if (autoBright(c)) setAutoBright(c, false);
        put(c, Settings.System.SCREEN_BRIGHTNESS, Math.max(1, Math.min(255, v)));
    }

    static int sys(Context c, String k, int d) { try { return Settings.System.getInt(c.getContentResolver(), k, d); } catch (Throwable t) { return d; } }
    static boolean put(Context c, String k, int v) {
        try { ContentResolver r = c.getContentResolver(); Settings.System.putInt(r, k, v); return Settings.System.getInt(r, k, -1) == v; }
        catch (Throwable t) { return false; }
    }

    // ---- torch (CameraManager, no permission); state tracked with a TorchCallback shared by every caller
    static String torchId;
    static boolean torchOn;
    static void torch(Context c) {
        try {
            android.hardware.camera2.CameraManager cm = (android.hardware.camera2.CameraManager) c.getSystemService(Context.CAMERA_SERVICE);
            if (torchId == null) {
                for (String id : cm.getCameraIdList()) {
                    Boolean f = cm.getCameraCharacteristics(id).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE);
                    if (f != null && f) { torchId = id; break; }
                }
                if (torchId == null) return;
                cm.registerTorchCallback(new android.hardware.camera2.CameraManager.TorchCallback() {
                    @Override public void onTorchModeChanged(String id, boolean on) { if (id.equals(torchId)) torchOn = on; }
                }, new android.os.Handler(android.os.Looper.getMainLooper()));
            }
            cm.setTorchMode(torchId, !torchOn);
        } catch (Throwable ignored) { }
    }
}
