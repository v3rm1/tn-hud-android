package android.graphics;
import java.awt.*;
import java.awt.geom.*;
import java.util.ArrayDeque;
public class Canvas {
  final Graphics2D g; final int w, h;
  final ArrayDeque<Object[]> st = new ArrayDeque<>();
  public Canvas(Graphics2D g, int w, int h) {
    this.g = g; this.w = w; this.h = h;
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
  }
  public int save() { st.push(new Object[]{g.getTransform(), g.getClip()}); return st.size(); }
  public void restore() { Object[] o = st.pop(); g.setTransform((AffineTransform) o[0]); g.setClip((Shape) o[1]); }
  public void translate(float dx, float dy) { g.translate(dx, dy); }
  public boolean clipRect(float l, float t, float r, float b) { g.clip(new Rectangle2D.Float(l, t, r - l, b - t)); return true; }
  public void drawColor(int c) {
    AffineTransform at = g.getTransform(); Shape clip = g.getClip();
    g.setColor(new Color(c, true)); g.setComposite((c >>> 24) == 255 ? AlphaComposite.Src : AlphaComposite.SrcOver);
    try { Shape dev = clip == null ? null : at.createTransformedShape(clip);
      g.setTransform(new AffineTransform()); g.setClip(dev); g.fillRect(0, 0, w, h); }
    finally { g.setTransform(at); g.setClip(clip); g.setComposite(AlphaComposite.SrcOver); }
  }
  void paint(Shape s, Paint p) {
    Color col = new Color(p.color, true);
    if (p.style == Paint.Style.FILL) { g.setColor(col); g.fill(s); return; }
    float[] dash = null; float ph = 0;
    if (p.pe instanceof DashPathEffect) { dash = ((DashPathEffect) p.pe).iv; ph = ((DashPathEffect) p.pe).phase; }
    if (p.mf instanceof BlurMaskFilter) {          // cheap glow approximation
      float r = ((BlurMaskFilter) p.mf).radius;
      for (int i = 3; i >= 1; i--) {
        int a = (int) (((p.color >>> 24) & 0xFF) * 0.18f);
        g.setColor(new Color((p.color & 0xFFFFFF) | (a << 24), true));
        g.setStroke(new BasicStroke(Math.max(1f, p.sw + r * i * 0.8f), BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, dash, ph));
        g.draw(s);
      }
      return;
    }
    g.setColor(col);
    g.setStroke(new BasicStroke(Math.max(0.5f, p.sw), BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, dash, ph));
    g.draw(s);
  }
  public void drawPath(Path path, Paint p) { paint(path.p, p); }
  public void drawLine(float a, float b, float c, float d, Paint p) { paint(new Line2D.Float(a, b, c, d), p); }
  public void drawRect(float l, float t, float r, float b, Paint p) { paint(new Rectangle2D.Float(l, t, r - l, b - t), p); }
  public void drawCircle(float cx, float cy, float r, Paint p) { paint(new Ellipse2D.Float(cx - r, cy - r, 2 * r, 2 * r), p); }
  public void drawArc(RectF o, float start, float sweep, boolean center, Paint p) {
    paint(new Arc2D.Float(o.left, o.top, o.right - o.left, o.bottom - o.top, -start, -sweep, center ? Arc2D.PIE : Arc2D.OPEN), p);
  }
  public void drawText(String s, float x, float y, Paint p) {
    g.setFont(p.font());
    if (p.mf instanceof BlurMaskFilter) {                      // approximate glow: halo of faint copies
      float r = ((BlurMaskFilter) p.mf).radius; int a = (int) (((p.color >>> 24) & 0xFF) * 0.10f);
      g.setColor(new Color((p.color & 0xFFFFFF) | (a << 24), true));
      for (int i = 0; i < 12; i++) { double t = i * Math.PI / 6; for (float k = 0.35f; k <= 1f; k += 0.32f) g.drawString(s, x + (float) Math.cos(t) * r * k, y + (float) Math.sin(t) * r * k); }
      return;
    }
    if (p.sh instanceof LinearGradient) { LinearGradient lg = (LinearGradient) p.sh; g.setPaint(new GradientPaint(lg.x0, lg.y0, new Color(lg.c0, true), lg.x1, lg.y1, new Color(lg.c1, true))); }
    else g.setColor(new Color(p.color, true));
    g.drawString(s, x, y);
  }
}
