package app.payload;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Probes the manifest walls: a 2nd Activity + a runtime permission the shell
 *  never declared. Each result is rendered so we can read it on-device. */
public final class Main {
    private Main() {}
    public static View render(Activity host) {
        LinearLayout col = new LinearLayout(host);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Color.WHITE);
        col.setPadding(40, 120, 40, 40);

        // (1) start a payload-declared Activity NOT in the shell manifest
        String act;
        try {
            host.startActivity(new Intent(host, SecondActivity.class));
            act = "startActivity(SecondActivity): NO EXCEPTION (unexpected)";
        } catch (Throwable e) {
            act = "startActivity(SecondActivity) -> " + e.getClass().getSimpleName()
                    + ": " + trimMsg(e.getMessage());
        }

        // (2) request a runtime permission the shell manifest doesn't declare
        String perm;
        try {
            int before = host.checkSelfPermission(android.Manifest.permission.CAMERA);
            host.requestPermissions(new String[]{android.Manifest.permission.CAMERA}, 7);
            perm = "requestPermissions(CAMERA): checkSelfPermission=" + before
                    + " (0=granted,-1=denied); no prompt if undeclared";
        } catch (Throwable e) {
            perm = "requestPermissions -> " + e.getClass().getSimpleName();
        }

        col.addView(label(host, "MANIFEST-WALL PROBES\n"));
        col.addView(label(host, "1) " + act + "\n"));
        col.addView(label(host, "2) " + perm));
        return col;
    }
    private static TextView label(Activity host, String s) {
        TextView t = new TextView(host);
        t.setText(s); t.setTextColor(Color.parseColor("#FF102A43"));
        t.setTextSize(15); t.setPadding(0, 12, 0, 12);
        return t;
    }
    private static String trimMsg(String m) {
        if (m == null) return "";
        return m.length() > 90 ? m.substring(0, 90) + "…" : m;
    }
}
