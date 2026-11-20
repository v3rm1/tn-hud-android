package android.graphics;
import java.awt.geom.Path2D;
public class Path {
  public final Path2D.Float p = new Path2D.Float();
  public void reset() { p.reset(); }
  public void moveTo(float x, float y) { p.moveTo(x, y); }
  public void lineTo(float x, float y) { p.lineTo(x, y); }
  public void close() { p.closePath(); }
}
