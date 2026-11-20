package android.service.quicksettings;

/** compile-time stub (API 24+); the real framework class is used at runtime. Never dexed. */
public class TileService extends android.app.Service {
    public static final String ACTION_QS_TILE = "android.service.quicksettings.action.QS_TILE";
    public android.os.IBinder onBind(android.content.Intent i) { throw new RuntimeException(); }
    public void onTileAdded() { }
    public void onTileRemoved() { }
    public void onStartListening() { }
    public void onStopListening() { }
    public void onClick() { }
    public final Tile getQsTile() { throw new RuntimeException(); }
    public final boolean isLocked() { throw new RuntimeException(); }
    public final void unlockAndRun(Runnable r) { throw new RuntimeException(); }
    /** API 24-33; throws on API 34+ for apps targeting 34 */
    public final void startActivityAndCollapse(android.content.Intent i) { throw new RuntimeException(); }
    /** API 34+ */
    public final void startActivityAndCollapse(android.app.PendingIntent p) { throw new RuntimeException(); }
}
