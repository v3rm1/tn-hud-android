package preview;
import android.graphics.Canvas;
import com.tokyonight.hud.live.HudRenderer;
import java.awt.image.BufferedImage;
public class BlankPreview { public static void main(String[] a) throws Exception {
  HudRenderer r = new HudRenderer(); r.setSize(400, 867);
  HudRenderer.Data d = new HudRenderer.Data(); d.virtualPages = true;
  d.order[1] = new int[]{3, 2, 9, 6}; d.lkOrder = new int[]{12, 0, 5}; d.lkOn[3] = false; d.lkKp = false;
  for (int m : new int[]{0, 2}) { BufferedImage im = new BufferedImage(400, 867, BufferedImage.TYPE_INT_RGB);
    java.awt.Graphics2D g = im.createGraphics(); r.draw(new Canvas(g, 400, 867), m, 4000, 0, d); g.dispose();
    javax.imageio.ImageIO.write(im, "png", new java.io.File(a[0] + m + ".png")); }
  d.splitPages = true; d.page = 0; BufferedImage im = new BufferedImage(400, 867, BufferedImage.TYPE_INT_RGB);
  java.awt.Graphics2D g = im.createGraphics(); r.draw(new Canvas(g, 400, 867), 2, 4000, 0, d); g.dispose();
  javax.imageio.ImageIO.write(im, "png", new java.io.File(a[0] + "tab.png"));
}}
