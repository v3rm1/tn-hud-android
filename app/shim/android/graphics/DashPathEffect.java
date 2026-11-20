package android.graphics;
public class DashPathEffect extends PathEffect {
  public final float[] iv; public final float phase;
  public DashPathEffect(float[] iv, float phase) { this.iv = iv.clone(); this.phase = phase; }
}
