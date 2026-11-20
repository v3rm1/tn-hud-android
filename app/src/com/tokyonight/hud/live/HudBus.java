package com.tokyonight.hud.live;

import android.os.Handler;
import android.os.Looper;

/**
 * In-process link between the wallpaper engine and the launcher activity (both run in the app's one process):
 * the engine publishes where the widget slot is on screen, the launcher moves the hosted widget there.
 */
final class HudBus {
    interface WidgetListener { void onWidgetRect(int[] rect); }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static int[] rect;
    private static WidgetListener listener;

    static synchronized void widget(int[] r) {
        if (r == null ? rect == null : rect != null && java.util.Arrays.equals(r, rect)) return;
        rect = r;
        final int[] copy = r;
        final WidgetListener l = listener;
        if (l != null) MAIN.post(new Runnable() { public void run() { l.onWidgetRect(copy); } });
    }

    static synchronized void listen(WidgetListener l) {
        listener = l;
        final int[] copy = rect;
        if (l != null) MAIN.post(new Runnable() { public void run() { if (listener != null) listener.onWidgetRect(copy); } });
    }
}
