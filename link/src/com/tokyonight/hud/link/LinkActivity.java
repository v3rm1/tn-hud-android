package com.tokyonight.hud.link;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** status screen: is notification access granted, is TN-HUD Live installed; one button each */
public class LinkActivity extends Activity {
    TextView status;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(0xFF000000);
        int p = (int) (24 * dp);
        col.setPadding(p, (int) (64 * dp), p, p);
        TextView h = new TextView(this);
        h.setText("// TN-HUD LINK"); h.setTextColor(0xFF7AA2F7); h.setTextSize(24); h.setTypeface(Typeface.MONOSPACE);
        col.addView(h);
        TextView t = new TextView(this);
        t.setText("Gives TN-HUD Live notification badges, track info from any player (Samsung Music included), "
                + "media controls, a HUD notification panel and Do Not Disturb. It only talks to TN-HUD Live "
                + "(same signing key) and has no internet permission.");
        t.setTextColor(0xFFC0CAF5); t.setTextSize(15); t.setPadding(0, (int) (12 * dp), 0, (int) (12 * dp));
        col.addView(t);
        status = new TextView(this);
        status.setTextColor(0xFF7DCFFF); status.setTextSize(15); status.setTypeface(Typeface.MONOSPACE);
        col.addView(status);
        Button a = new Button(this);
        a.setText("Notification access");
        a.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            try { startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); } catch (Exception ignored) { }
        } });
        col.addView(a);
        Button o = new Button(this);
        o.setText("Open TN-HUD settings");
        o.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            try { startActivity(new Intent().setComponent(new ComponentName(LinkListener.HUD, LinkListener.HUD + ".SetWallpaper")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
            catch (Exception ignored) { }
        } });
        col.addView(o);
        TextView n = new TextView(this);
        n.setText("Greyed out under Notification access? Android blocks this for apps installed from a browser or file manager: "
                + "App info › ⋮ › Allow restricted settings, or install with adb / Shizuku.");
        n.setTextColor(0xFF565F89); n.setTextSize(13); n.setPadding(0, (int) (16 * dp), 0, 0); n.setGravity(Gravity.START);
        col.addView(n);
        setContentView(col);
    }

    @Override protected void onResume() {
        super.onResume();
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        boolean on = !TextUtils.isEmpty(flat) && flat.contains(new ComponentName(this, LinkListener.class).flattenToString());
        boolean hud;
        try { getPackageManager().getPackageInfo(LinkListener.HUD, 0); hud = true; } catch (Exception e) { hud = false; }
        status.setText((on ? "✓ notification access" : "✗ notification access: not granted") + "\n"
                + (hud ? "✓ TN-HUD Live installed" : "✗ TN-HUD Live not installed") + "\n");
        if (on && LinkListener.instance != null) LinkListener.instance.schedule();
    }
}
