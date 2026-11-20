package com.tokyonight.hud.link;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** commands from TN-HUD Live (signature permission): media, dismiss, clear, dnd, refresh */
public class LinkCmdRx extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        LinkListener l = LinkListener.instance;
        String cmd = i.getStringExtra("cmd");
        if (l == null) {                                   // no access (yet): say so
            Intent s = new Intent(LinkListener.STATE).setComponent(new android.content.ComponentName(LinkListener.HUD, LinkListener.HUD + ".LinkRx"))
                    .putExtra("v", 1).putExtra("at", System.currentTimeMillis()).putExtra("access", false);
            c.sendBroadcast(s, LinkListener.PERM);
            return;
        }
        if ("media".equals(cmd)) l.media(i.getStringExtra("key"));
        else if ("dismiss".equals(cmd)) l.dismiss(i.getStringExtra("key"));
        else if ("clear".equals(cmd)) l.dismiss(null);
        else if ("dnd".equals(cmd)) l.dnd(i.getIntExtra("mode", 1));
        else l.schedule();
    }
}
