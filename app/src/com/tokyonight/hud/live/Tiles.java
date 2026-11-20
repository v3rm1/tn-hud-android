package com.tokyonight.hud.live;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * Quick settings tiles (add them from the edit page of Android's quick settings):
 *   HUD panel  - the TN-HUD notification / toggle panel (in place over the app when the pull-down service is on,
 *                otherwise on the home screen)
 *   HUD launcher - the rofi-style launcher pop-up on the home screen
 */
public final class Tiles {
    private Tiles() { }

    public static final class Panel extends TileService {
        @Override public void onStartListening() { label(this, "HUD panel"); }
        @Override public void onClick() {
            ShadeService s = ShadeService.instance;
            if (s != null) { collapse(this); s.open(); }
            else home(this, "panel");
        }
    }

    public static final class Popup extends TileService {
        @Override public void onStartListening() { label(this, "HUD launcher"); }
        @Override public void onClick() { home(this, "popup"); }
    }

    static void label(TileService t, String l) {
        try { Tile q = t.getQsTile(); if (q == null) return; q.setLabel(l); q.setState(Tile.STATE_INACTIVE); q.updateTile(); } catch (Throwable ignored) { }
    }

    /** opens the launcher (home) with "tnhud.open" and collapses the system shade */
    static void home(TileService t, String what) {
        Intent i = new Intent(t, HudLauncher.class).putExtra("tnhud.open", what).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            if (Build.VERSION.SDK_INT >= 34)
                t.startActivityAndCollapse(PendingIntent.getActivity(t, what.hashCode(), i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            else t.startActivityAndCollapse(i);
        } catch (Throwable ignored) { }
    }

    /** shade collapse without starting anything: StatusBarManager.collapsePanels (hidden API; ignored when refused) */
    static void collapse(TileService t) {
        try {
            Object sbm = t.getSystemService("statusbar");
            Class.forName("android.app.StatusBarManager").getMethod("collapsePanels").invoke(sbm);
        } catch (Throwable ignored) { }
    }
}
