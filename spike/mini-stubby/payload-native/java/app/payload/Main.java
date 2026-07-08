package app.payload;

import android.app.Activity;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Native-lib payload: loads libpayloadjni.so and calls into it. */
public final class Main {
    private Main() {}
    public static View render(Activity host) {
        LinearLayout col = new LinearLayout(host);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setBackgroundColor(Color.WHITE);
        col.setPadding(48, 48, 48, 48);
        TextView t = new TextView(host);
        String result;
        try {
            result = Native.greet() + "\n\nNative.add(20, 22) = " + Native.add(20, 22);
        } catch (Throwable e) {
            result = "NATIVE LOAD FAILED:\n" + e;
        }
        t.setText(result);
        t.setTextColor(Color.parseColor("#FF1F5A4C"));
        t.setTextSize(18);
        t.setGravity(Gravity.CENTER);
        col.addView(t);
        return col;
    }
}
