package com.tokyonight.hud.live;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Installed icon packs in the common ADW / Nova format: an appfilter.xml (res/xml or assets) mapping
 * "ComponentInfo{package/activity}" to drawable names in the pack's resources. TN-HUD-Icons is one of them.
 * Only mapped icons are replaced; everything else keeps its own icon.
 */
final class IconPacks {
    static final String[] ACTIONS = {"org.adw.launcher.THEMES", "com.novalauncher.THEME", "com.gau.go.launcherex.theme"};

    /** {label, package} of every installed pack, sorted by label */
    static List<String[]> list(Context c) {
        PackageManager pm = c.getPackageManager();
        Map<String, String> m = new HashMap<String, String>();
        for (String a : ACTIONS) {
            try {
                for (ResolveInfo r : pm.queryIntentActivities(new Intent(a), 0)) {
                    String p = r.activityInfo.packageName;
                    if (!m.containsKey(p)) m.put(p, String.valueOf(r.loadLabel(pm)));
                }
            } catch (Throwable ignored) { }
        }
        List<String[]> out = new ArrayList<String[]>();
        for (Map.Entry<String, String> e : m.entrySet()) out.add(new String[]{e.getValue(), e.getKey()});
        java.util.Collections.sort(out, new java.util.Comparator<String[]>() { public int compare(String[] a, String[] b) { return a[0].compareToIgnoreCase(b[0]); } });
        return out;
    }

    final String pkg;
    final Resources res;
    final Map<String, String> map = new HashMap<String, String>();

    private IconPacks(String pkg, Resources res) { this.pkg = pkg; this.res = res; }

    /** null when the pack is missing or has no appfilter */
    static IconPacks load(Context c, String pkg) {
        if (pkg == null || pkg.isEmpty()) return null;
        try {
            Resources r = c.getPackageManager().getResourcesForApplication(pkg);
            IconPacks p = new IconPacks(pkg, r);
            XmlPullParser x = null;
            InputStream in = null;
            int id = r.getIdentifier("appfilter", "xml", pkg);
            if (id != 0) x = r.getXml(id);
            else {
                in = r.getAssets().open("appfilter.xml");
                x = XmlPullParserFactory.newInstance().newPullParser();
                x.setInput(in, "utf-8");
            }
            try {
                for (int ev = x.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = x.next()) {
                    if (ev != XmlPullParser.START_TAG || !"item".equals(x.getName())) continue;
                    String comp = x.getAttributeValue(null, "component"), dr = x.getAttributeValue(null, "drawable");
                    if (comp == null || dr == null || !comp.startsWith("ComponentInfo{") || !comp.endsWith("}")) continue;
                    p.map.put(comp.substring(14, comp.length() - 1), dr);
                }
            } finally { if (in != null) try { in.close(); } catch (Exception ignored) { } }
            return p.map.isEmpty() ? null : p;
        } catch (Throwable t) { return null; }
    }

    /** the pack's icon for an activity, or null */
    Drawable icon(ComponentName cn, int dpi) {
        String n = map.get(cn.getPackageName() + "/" + cn.getClassName());
        if (n == null) n = map.get(cn.flattenToShortString());          // some packs use the short ".Activity" form
        if (n == null) return null;
        int id = res.getIdentifier(n, "drawable", pkg);
        if (id == 0) return null;
        try { return res.getDrawableForDensity(id, dpi, null); } catch (Throwable t) { return null; }
    }
}
