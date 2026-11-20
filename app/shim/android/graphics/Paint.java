package android.graphics;
import java.awt.Font;
import java.awt.image.BufferedImage;
public class Paint {
  public static final int ANTI_ALIAS_FLAG = 1;
  public enum Style { FILL, STROKE, FILL_AND_STROKE }
  int color = 0xFF000000; Style style = Style.FILL; float sw = 0, ts = 12;
  PathEffect pe; MaskFilter mf; Shader sh; Typeface tf = Typeface.MONOSPACE;
  static final java.awt.Graphics2D MG = new BufferedImage(1,1,BufferedImage.TYPE_INT_ARGB).createGraphics();
  public Paint(int flags) {}
  public void setColor(int c) { color = c; }
  public void setAlpha(int a) { color = (color & 0xFFFFFF) | ((a & 0xFF) << 24); }
  public void setStyle(Style s) { style = s; }
  public void setStrokeWidth(float w) { sw = w; }
  public void setTextSize(float t) { ts = t; }
  public Typeface setTypeface(Typeface t) { tf = t == null ? Typeface.MONOSPACE : t; return t; }
  public Shader setShader(Shader x) { sh = x; return x; }
  public PathEffect setPathEffect(PathEffect e) { pe = e; return e; }
  public MaskFilter setMaskFilter(MaskFilter m) { mf = m; return m; }
  Font font() { return (tf != null && tf.awt != null ? tf.awt : new Font("DejaVu Sans Mono", Font.PLAIN, 1)).deriveFont(ts); }
  public float measureText(String s) { return (float) MG.getFontMetrics(font()).getStringBounds(s, MG).getWidth(); }
}
