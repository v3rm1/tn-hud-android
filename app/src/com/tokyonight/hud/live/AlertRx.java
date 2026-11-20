package com.tokyonight.hud.live;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.util.Calendar;
import java.util.Locale;

/** pass alarm (10 min before the visible part starts; may run late in Doze, so it re-checks the window) */
public class AlertRx extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        SharedPreferences sp = Alerts.sp(c);
        int k = i.getIntExtra("k", 0);
        boolean on = k == 2 ? sp.getBoolean("alStarlink", false) : sp.getBoolean("alPass", false);
        long now = System.currentTimeMillis(), st = i.getLongExtra("start", 0), en = i.getLongExtra("end", 0);
        if (!on || now > en || Alerts.quiet(sp, now)) return;
        Calendar t = Calendar.getInstance(); t.setTimeInMillis(st);
        String when = now >= st ? "visible now" : "in " + Math.max(1, (st - now + 30000) / 60000) + " min";
        String name = i.getStringExtra("name");
        int cnt = i.getIntExtra("count", 0);
        String title = name + (k == 2 && cnt > 0 ? " (" + cnt + " satellites)" : "") + " " + when;
        String text = String.format(Locale.US, "From %02d:%02d, up to %.0f° high, %s, about %d min.", t.get(Calendar.HOUR_OF_DAY), t.get(Calendar.MINUTE),
                i.getDoubleExtra("max", 0), i.getStringExtra("dir"), Math.max(1, (en - st) / 60000));
        Alerts.notify(c, Alerts.N_PASS + k, title, text);
    }
}
