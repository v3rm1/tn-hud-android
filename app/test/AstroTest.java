import com.tokyonight.hud.live.Astro;
public class AstroTest {
  public static void main(String[] a) {
    double lat = 59.13, lon = 10.22;
    long[] ts = {1793026800000L, 1762160000000L, 1735689600000L, 1751328000000L, 1800000000000L};
    for (long t : ts) {
      StringBuilder sb = new StringBuilder("T " + t);
      int[] bodies = {Astro.SUN, Astro.MOON, 0, 1, 2, 3, 4};
      for (int b : bodies) { double[] p = Astro.pos(b, t, lat, lon); sb.append(String.format(" %d:%.3f,%.3f", b, p[0], p[1])); }
      double[] ph = Astro.moonPhase(t); sb.append(String.format(" PH:%.4f,%d", ph[0], (int) ph[1]));
      long day0 = t - (t % 86400000L) - 3600000L;  // local midnight approx (UTC+1)
      long d1 = day0 + 86400000L;
      sb.append(" SR:" + Astro.crossing(Astro.SUN, day0, d1, lat, lon, -0.833, true, 600000L));
      sb.append(" SS:" + Astro.crossing(Astro.SUN, day0, d1, lat, lon, -0.833, false, 600000L));
      sb.append(" MR:" + Astro.crossing(Astro.MOON, day0, d1 + 86400000L, lat, lon, -0.833, true, 600000L));
      sb.append(" MS:" + Astro.crossing(Astro.MOON, day0, d1 + 86400000L, lat, lon, -0.833, false, 600000L));
      System.out.println(sb);
    }
  }
}
