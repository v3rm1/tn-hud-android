import com.tokyonight.hud.live.*;
public class FcTest { public static void main(String[] a){
  String o="[{\"time_tag\":\"2026-11-15T12:00:00\",\"kp\":3.33,\"observed\":\"observed\",\"noaa_scale\":null},{\"time_tag\":\"2026-11-15T15:00:00\",\"kp\":4.67,\"observed\":\"predicted\",\"noaa_scale\":\"G1\"},{\"time_tag\":\"2026-11-15T18:00:00\",\"kp\":NaN,\"observed\":\"predicted\"}]";
  double[][] f=SpaceWx.kpForecast(o); System.out.println("n="+f[0].length+" k1="+f[1][1]+" pred="+f[2][1]);
  long t0=SpaceWx.time("2026-11-15T13:00:00"); double[] m=SpaceWx.kpForecastMax(f[0],f[1],t0,t0+12*3600000L); System.out.println("max="+m[0]+" at="+new java.util.Date((long)m[1]));
  System.out.println(SpaceWx.kpForecastMax(null,null,0,1)[0]);
}}
