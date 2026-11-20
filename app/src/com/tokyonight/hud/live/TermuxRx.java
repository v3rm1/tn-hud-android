package com.tokyonight.hud.live;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** result of a "$ command" run in Termux (see Termux); delivered on the main thread */
public class TermuxRx extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) { Termux.deliver(i); }
}
