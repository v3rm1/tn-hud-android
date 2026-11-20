package android.location;
/** compile-time stub (API 24+); the real framework class is used at runtime. Never dexed. */
public final class GnssStatus {
    public static final int CONSTELLATION_UNKNOWN = 0, CONSTELLATION_GPS = 1, CONSTELLATION_SBAS = 2, CONSTELLATION_GLONASS = 3,
            CONSTELLATION_QZSS = 4, CONSTELLATION_BEIDOU = 5, CONSTELLATION_GALILEO = 6, CONSTELLATION_IRNSS = 7;
    public int getSatelliteCount() { throw new RuntimeException(); }
    public int getConstellationType(int i) { throw new RuntimeException(); }
    public int getSvid(int i) { throw new RuntimeException(); }
    public float getCn0DbHz(int i) { throw new RuntimeException(); }
    public float getElevationDegrees(int i) { throw new RuntimeException(); }
    public float getAzimuthDegrees(int i) { throw new RuntimeException(); }
    public boolean usedInFix(int i) { throw new RuntimeException(); }
    public boolean hasCarrierFrequencyHz(int i) { throw new RuntimeException(); }
    public float getCarrierFrequencyHz(int i) { throw new RuntimeException(); }
    public static abstract class Callback {
        public void onStarted() { }
        public void onStopped() { }
        public void onFirstFix(int ttffMillis) { }
        public void onSatelliteStatusChanged(GnssStatus status) { }
    }
}
