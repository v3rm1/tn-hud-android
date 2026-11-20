import com.tokyonight.hud.live.*;
public class AstroV20 {
  public static void main(String[] a) {
    long now = 1793822400000L; // 2026-11-04 20:00Z
    String[] nm={"NEW","FQ","FULL","LQ"};
    for (int k=0;k<4;k++) System.out.println("PH "+nm[k]+" "+Astro.phaseEvent(now, k*90));
    double[] p=Astro.apsis(now,true), q=Astro.apsis(now,false);
    System.out.printf("PERI %d %.0f%nAPO %d %.0f%n",(long)p[0],p[1],(long)q[0],q[1]);
    System.out.printf("DIST %.0f%n", Astro.moonDistKm(now));
    for (long t: new long[]{now, now+86400000L*60, now+86400000L*100, now+86400000L*200}) System.out.printf("EOT %d %.3f%n", t, Astro.eotMin(t));
    long ds = 1793750400000L; // 2026-11-04 00:00 UTC (~01:00 local)
    long[] tw=Astro.twilight(ds-3600000L, 59.91, 10.75);
    for (long t: tw) System.out.println("TW "+t);
    double[] m=Astro.meteors(now,59.91,10.75);
    System.out.printf("MET %s eff %.2f alt %.1f dp %.2f act %d%n", Astro.SHOWER_CODE((int)m[0]), m[1], m[2], m[3], (int)m[4]);
    long[] vt={1793750400000L-8L*86400000L, 1795414567000L, 1770595200000L, 1750982400000L};
    for (long t: vt) System.out.printf("VOY %d %.0f %.4f AU | %.0f %.4f AU%n", t, Astro.voyagerKm(0,t), Astro.voyagerKm(0,t)/Astro.AU_KM, Astro.voyagerKm(1,t), Astro.voyagerKm(1,t)/Astro.AU_KM);
  }
}
