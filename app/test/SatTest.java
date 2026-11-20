import com.tokyonight.hud.live.*;
public class SatTest {
  public static void main(String[] a) {
    String n="ISS (ZARYA)";
    String l1="1 25544U 98067A   24001.50000000  .00016717  00000-0  10270-3 0  9005";
    String l2="2 25544  51.6416 247.4627 0006703 130.5360 325.0288 15.72125391563537";
    Sgp4 s=Sgp4.parse(n,l1,l2);
    double[] r=new double[3];
    for (double t: new double[]{0, 60, 720, 1440, 4320}) { s.pos(t, r); System.out.printf("P %.1f %.6f %.6f %.6f%n", t, r[0], r[1], r[2]); }
    System.out.printf("EPOCH %.8f%n", s.epochJd);
    double[] o=new double[4];
    long base = 1704110400000L; // 2024-01-01T12:00Z
    for (int k=0;k<6;k++){ long ms=base+k*3600_000L*7+k*600_000L; Sat.look(s, ms, 59.91, 10.75, o); System.out.printf("L %d %.4f %.4f %.3f %d%n", ms, o[0], o[1], o[2], (int)o[3]); }
    Sat.Grid G=new Sat.Grid(base, 48*3600_000L, 59.91, 10.75);
    Sat.Pass p=Sat.nextVisible(s,G);
    System.out.println("PASS "+p.rise+" "+p.set+" vis "+p.visStart+" "+p.visEnd+" max "+p.maxEl+" "+p.azVisStart+" "+p.azVisEnd);
  }
}
