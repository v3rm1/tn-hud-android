package android.graphics;
public class Typeface {
  public static final Typeface MONOSPACE = new Typeface(null);
  final java.awt.Font awt;
  Typeface(java.awt.Font f) { awt = f; }
  public static Typeface createFromFile(String path) {
    try { return new Typeface(java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, new java.io.File(path))); }
    catch (Exception e) { return MONOSPACE; }
  }
}
