import com.tokyonight.hud.live.*;
public class SwTest { public static void main(String[] a){
  String kp1="[{\"time_tag\":\"2026-11-04T20:40:00\",\"kp_index\":3,\"estimated_kp\":3.33,\"kp\":\"3P\"},{\"time_tag\":\"2026-11-04T20:41:00\",\"kp_index\":4,\"estimated_kp\":4.0,\"kp\":\"4Z\"}]";
  double[][] k=SpaceWx.kp(kp1); System.out.println("kp n="+k[0].length+" last="+k[1][k[1].length-1]);
  String kpOld="[[\"time_tag\",\"Kp\",\"a_running\",\"station_count\"],[\"2026-11-04 18:00:00.000\",\"2.67\",\"12\",\"8\"],[\"2026-11-04 21:00:00.000\",\"3.33\",\"18\",\"8\"]]";
  k=SpaceWx.kp(kpOld); System.out.println("kpOld n="+k[0].length+" last="+k[1][1]);
  String sc="{\"-1\":{\"G\":{\"Scale\":\"1\"}},\"0\":{\"DateStamp\":\"2026-11-04\",\"R\":{\"Scale\":\"0\",\"Text\":\"none\"},\"S\":{\"Scale\":0},\"G\":{\"Scale\":\"3\",\"Text\":\"strong\"}}}";
  int[] s=SpaceWx.scales(sc); System.out.println("scales R"+s[0]+" S"+s[1]+" G"+s[2]);
  System.out.println("bz "+SpaceWx.latest("[{\"Bt\":7,\"Bz\":-6.2,\"TimeStamp\":\"2026-11-04 20:42:00.000\"}]","Bz"));
  System.out.println("bzOld "+SpaceWx.latest("{\"Bt\":\"7\",\"Bz\":\"-1\",\"TimeStamp\":\"2026-11-04 20:42:00.000\"}","Bz"));
  System.out.println("v "+SpaceWx.latest("[{\"WindSpeed\":NaN,\"TimeStamp\":\"x\"},{\"WindSpeed\":512,\"TimeStamp\":\"2026-11-04 20:42:00\"}]","WindSpeed","proton_speed","speed"));
  String xr="[{\"time_tag\":\"2026-11-04T20:00:00Z\",\"satellite\":19,\"flux\":2.1e-6,\"energy\":\"0.1-0.8nm\"},{\"time_tag\":\"2026-11-04T20:01:00Z\",\"satellite\":19,\"flux\":3.4e-5,\"energy\":\"0.1-0.8nm\"},{\"time_tag\":\"2026-11-04T20:01:00Z\",\"satellite\":19,\"flux\":1e-7,\"energy\":\"0.05-0.4nm\"},{\"time_tag\":\"2026-11-04T20:02:00Z\",\"flux\":1.2e-5,\"energy\":\"0.1-0.8nm\"}]";
  double[] x=SpaceWx.xray(xr); System.out.println("xray "+SpaceWx.flareClass(x[0])+" max "+SpaceWx.flareClass(x[1]));
  for (double f: new double[]{3e-9,1.5e-8,9.99e-7,1e-6,4.5e-4,2.3e-3}) System.out.print(SpaceWx.flareClass(f)+" ");
  System.out.println();
  double ml=SpaceWx.geomagLat(59.91,10.75); System.out.printf("oslo mlat %.2f need %.2f%n", ml, SpaceWx.kpNeeded(ml));
  System.out.printf("tromso mlat %.2f need %.2f%n", SpaceWx.geomagLat(69.65,18.96), SpaceWx.kpNeeded(SpaceWx.geomagLat(69.65,18.96)));
  String[] r=new String[1];
  System.out.println(SpaceWx.aurora(4.3,-7,-20,20,ml,r)+" "+r[0]);
  System.out.println(SpaceWx.aurora(4.3,-7,-20,60,ml,r)+" "+r[0]);
  System.out.println(SpaceWx.aurora(2.0,1,-20,10,ml,r)+" "+r[0]);
  System.out.println(SpaceWx.aurora(6.0,-12,10,10,ml,r)+" "+r[0]);
}}
