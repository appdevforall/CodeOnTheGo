package app.payload;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Multi-screen demo payload — HOME screen.
 *
 * <p>Proves the proxy-activity mechanism closes the "multiple Activities"
 * manifest wall: a hot-loaded payload cannot register {@code SecondActivity} in
 * the shell's fixed manifest, so it opens a real second screen by starting the
 * shell's pre-declared {@code ProxyActivity} with an explicit Intent that names
 * the payload screen class in the {@code payload_screen} extra. The proxy loads
 * that class from the live payload classloader and renders it in a REAL window
 * with a REAL back-stack (system Back returns here; the Detail screen also offers
 * an explicit Back button).
 *
 * <p>Framework widgets only (no androidx), so it hand-compiles via
 * {@code tools/build_simple_payload.sh payload-multiscreen}.
 */
public final class Main {

    /** Must match the shell's ProxyActivity + its EXTRA_SCREEN constant. */
    private static final String PROXY = "com.adfa.ministubby.host.ProxyActivity";
    private static final String EXTRA_SCREEN = "payload_screen";

    private Main() {}

    public static View render(final Activity host) {
        LinearLayout col = new LinearLayout(host);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setBackgroundColor(0xFF1B5E20); // deep green — "you are on HOME"
        col.setPadding(48, 140, 48, 48);

        TextView badge = new TextView(host);
        badge.setText("HOME");
        badge.setTextColor(Color.WHITE);
        badge.setTextSize(40);
        badge.setGravity(Gravity.CENTER);
        col.addView(badge);

        TextView blurb = new TextView(host);
        blurb.setText("\nScreen 1 of the multi-screen demo.\n"
                + "The button below opens a SECOND real Activity"
                + " (proxied through the shell) with a back-stack.\n");
        blurb.setTextColor(0xFFC8E6C9);
        blurb.setTextSize(16);
        blurb.setGravity(Gravity.CENTER);
        col.addView(blurb);

        Button open = new Button(host);
        open.setText("Open Detail  →");
        open.setAllCaps(false);
        open.setTextSize(18);
        open.setOnClickListener(new View.OnClickListener() {
            private int clicks = 0;
            @Override public void onClick(View v) {
                clicks++;
                // THE navigation call this whole mechanism exists to enable:
                // start the shell's generic ProxyActivity, naming the payload
                // screen class + any extras the screen should read.
                host.startActivity(new Intent()
                        .setClassName(host, PROXY)
                        .putExtra(EXTRA_SCREEN, "app.payload.DetailScreen")
                        .putExtra("item", "Widget #" + (41 + clicks))
                        .putExtra("from", "HOME"));
            }
        });
        col.addView(open, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        return col;
    }
}
