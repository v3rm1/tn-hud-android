package android.graphics;
public class BlurMaskFilter extends MaskFilter {
  public enum Blur { NORMAL, SOLID, OUTER, INNER }
  public final float radius;
  public BlurMaskFilter(float r, Blur b) { radius = r; }
}
