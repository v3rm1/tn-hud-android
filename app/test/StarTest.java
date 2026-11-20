import com.tokyonight.hud.live.Astro;
import java.io.*;
public class StarTest {
  public static void main(String[] a) throws Exception {
    DataInputStream in = new DataInputStream(new FileInputStream("app/assets/stars.bin"));
    int n = in.readInt(); float[][] st = new float[n][4];
    for (int i = 0; i < n; i++) for (int k = 0; k < 4; k++) st[i][k] = in.readFloat();
    long t = 1793727000000L; double lat = 59.13, lon = 10.22;
    double lst = Astro.lst(Astro.jd(t), lon);
    double[][] ref = {{37.95, 89.26}, {279.23, 38.78}, {310.36, 45.28}, {79.17, 46.00}, {297.70, 8.87}};
    String[] nm = {"Polaris", "Vega", "Deneb", "Capella", "Altair"};
    for (int r = 0; r < ref.length; r++) {
      int best = -1; double bd = 9;
      for (int i = 0; i < n; i++) { double dd = Math.hypot((st[i][0] - ref[r][0]) * Math.cos(Math.toRadians(ref[r][1])), st[i][1] - ref[r][1]); if (dd < bd) { bd = dd; best = i; } }
      double[] h = Astro.altAz(st[best][0], st[best][1], lst, lat);
      System.out.printf("%s %.2f %.2f mag%.2f%n", nm[r], h[0], h[1], st[best][2]);
    }
  }
}
