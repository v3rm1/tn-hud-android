package com.tokyonight.hud.live;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** state pushes from TN-HUD Link (protected by the LINK signature permission in the manifest) */
public class LinkRx extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        if (i.getIntExtra("v", 0) < 1) return;
        Link.update(i);
    }
}
