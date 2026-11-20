package android.service.quicksettings;

/** compile-time stub (API 24+); the real framework class is used at runtime. Never dexed. */
public final class Tile {
    public static final int STATE_UNAVAILABLE = 0, STATE_INACTIVE = 1, STATE_ACTIVE = 2;
    public void setState(int s) { throw new RuntimeException(); }
    public int getState() { throw new RuntimeException(); }
    public void setLabel(CharSequence l) { throw new RuntimeException(); }
    public void setContentDescription(CharSequence d) { throw new RuntimeException(); }
    public void updateTile() { throw new RuntimeException(); }
}
