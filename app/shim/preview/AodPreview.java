package preview;
import android.graphics.Canvas;
import com.tokyonight.hud.live.HudRenderer;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.File;
public class AodPreview {
  public static void main(String[] a) throws Exception {
    HudRenderer r = new HudRenderer(); r.setSize(1440, 3120);
    r.setClockTypeface(android.graphics.Typeface.createFromFile("app/assets/ShareTechMono-Regular.ttf"));
    HudRenderer.Data d = new HudRenderer.Data();
    d.wallMs = 1793822400000L + 3 * 3600000L; d.h24 = true; d.charging = true; d.battery = 64;
    java.util.Random R = new java.util.Random(3);
    int[][] cons = {{1, 11}, {6, 9}, {3, 7}, {5, 12}, {4, 2}, {2, 2}};
    for (int[] cc : cons) for (int k = 0; k < cc[1]; k++) {
      int i = d.gN++;
      d.gCons[i] = cc[0]; d.gSvid[i] = cc[0] == 2 ? 120 + k * 3 : 1 + R.nextInt(36);
      d.gEl[i] = cc[0] == 2 ? 22 : 5 + R.nextFloat() * 80; d.gAz[i] = R.nextFloat() * 360;
      d.gCn0[i] = 18 + R.nextFloat() * 28 + d.gEl[i] / 12; d.gUsed[i] = d.gCn0[i] > 28 && cc[0] != 2;
      d.gL5[i] = (cc[0] == 1 || cc[0] == 6 || cc[0] == 5) && R.nextFloat() < 0.5f;
    }
    d.gFixAt = d.wallMs - 800; d.gAcc = 3.8f; d.gLat = 59.13; d.gLon = 10.22; d.gAlt = 41; d.gTtff = 4300; d.gStart = d.wallMs - 95000;
    d.kp = 4.33; d.bz = -6.2; d.xrFlux = 2.4e-6; d.scG = 1; d.swUpd = d.wallMs - 600000;
    BufferedImage im = new BufferedImage(1440, 3120, BufferedImage.TYPE_INT_RGB);
    java.awt.Graphics2D g = im.createGraphics(); r.drawAod(new Canvas(g, 1440, 3120), d); g.dispose();
    ImageIO.write(im, "png", new File(a[0]));
    d.gN = 0; d.gFixAt = 0; d.gState = "GRANT PRECISE LOCATION IN TN-HUD SETTINGS";
    im = new BufferedImage(1440, 3120, BufferedImage.TYPE_INT_RGB);
    g = im.createGraphics(); r.drawAod(new Canvas(g, 1440, 3120), d); g.dispose();
    ImageIO.write(im, "png", new File(a[0].replace(".png", "_noperm.png")));
  }
}
