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
 * Multi-screen demo payload — DETAIL screen (the "second Activity").
 *
 * <p>Rendered by the shell's {@code ProxyActivity}, which loaded this class from
 * the live payload classloader. It follows the same reflection contract as
 * {@code Main}: {@code public static View render(Activity host)}. Navigation
 * extras come from {@code host.getIntent()} — the same explicit Intent the HOME
 * screen built.
 *
 * <p>Visually distinct (orange) from HOME (green) so it is obvious on-device that
 * this is a separate, real screen. Offers an explicit Back button
 * ({@code host.finish()}); system Back also pops it off the real back-stack.
 */
public final class DetailScreen {

    private DetailScreen() {}

    public static View render(final Activity host) {
        Intent in = host.getIntent();
        String item = in != null ? in.getStringExtra("item") : null;
        String from = in != null ? in.getStringExtra("from") : null;

        LinearLayout col = new LinearLayout(host);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setBackgroundColor(0xFFE65100); // deep orange — "you are on DETAIL"
        col.setPadding(48, 140, 48, 48);

        TextView badge = new TextView(host);
        badge.setText("DETAIL");
        badge.setTextColor(Color.WHITE);
        badge.setTextSize(40);
        badge.setGravity(Gravity.CENTER);
        col.addView(badge);

        TextView blurb = new TextView(host);
        blurb.setText("\nScreen 2 — a REAL second Activity hosted by the shell's"
                + " ProxyActivity.\n\n"
                + "Passed item: " + (item != null ? item : "(none)") + "\n"
                + "Navigated from: " + (from != null ? from : "(unknown)") + "\n");
        blurb.setTextColor(0xFFFFE0B2);
        blurb.setTextSize(16);
        blurb.setGravity(Gravity.CENTER);
        col.addView(blurb);

        Button back = new Button(host);
        back.setText("←  Back");
        back.setAllCaps(false);
        back.setTextSize(18);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                host.finish(); // pops this real Activity off the back-stack
            }
        });
        col.addView(back, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        return col;
    }
}
