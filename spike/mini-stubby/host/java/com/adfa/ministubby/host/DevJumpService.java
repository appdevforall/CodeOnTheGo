package com.adfa.ministubby.host;

import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Dev-speed quick-jump: a small draggable floating pill shown as a system overlay
 * (TYPE_APPLICATION_OVERLAY), so it's visible on top of BOTH the running app (this
 * shell) AND the CoGo editor. One tap jumps either direction:
 *   ▶ App  → bring this shell (the running user app) to the front
 *   ✎ CoGo → bring the CoGo IDE to the front
 * No CoGo modification needed. Overlay permission is a special grant
 * (Settings.canDrawOverlays); on the dev device grant it once with:
 *   adb shell appops set com.adfa.ministubby.host SYSTEM_ALERT_WINDOW allow
 * The service self-stops (no pill) if the permission isn't held.
 */
public class DevJumpService extends Service {

    static final String COGO_PKG = "com.itsaky.androidide";

    private WindowManager wm;
    private View overlay;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return; }
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        overlay = buildBar();

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = 0;
        lp.y = dp(140);
        installDragHandle(lp);
        wm.addView(overlay, lp);
    }

    private LinearLayout buildBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xCC10202E);
        int p = dp(3);
        bar.setPadding(p, p, p, p);
        bar.addView(handle());
        bar.addView(chip("▶ App", 0xFF2E7D6B, v -> launch(getPackageName())));
        bar.addView(chip("✎ CoGo", 0xFF3D5A80, v -> launch(COGO_PKG)));
        return bar;
    }

    /** Left grip — drag target (the chips are tap targets). */
    private TextView handle() {
        TextView h = new TextView(this);
        h.setText("⠿");
        h.setTextColor(0xFF8AA0B4);
        h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        h.setPadding(dp(8), dp(6), dp(8), dp(6));
        return h;
    }

    private TextView chip(String label, int bg, View.OnClickListener onClick) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setAllCaps(false);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setBackgroundColor(bg);
        t.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(3), 0, dp(3), 0);
        t.setLayoutParams(lp);
        t.setOnClickListener(onClick);
        return t;
    }

    private void launch(String pkg) {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i != null) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); }
        } catch (Throwable ignored) { /* target not installed */ }
    }

    /** Drag the whole pill by its grip; chip taps are untouched (own listeners). */
    private void installDragHandle(WindowManager.LayoutParams lp) {
        View grip = ((LinearLayout) overlay).getChildAt(0);
        grip.setOnTouchListener(new View.OnTouchListener() {
            int ix, iy; float tx, ty;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        ix = lp.x; iy = lp.y; tx = e.getRawX(); ty = e.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        lp.x = ix + (int) (e.getRawX() - tx);
                        lp.y = iy + (int) (e.getRawY() - ty);
                        wm.updateViewLayout(overlay, lp);
                        return true;
                }
                return false;
            }
        });
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    @Override
    public void onDestroy() {
        if (overlay != null && wm != null) {
            try { wm.removeView(overlay); } catch (Throwable ignored) {}
        }
        super.onDestroy();
    }
}
