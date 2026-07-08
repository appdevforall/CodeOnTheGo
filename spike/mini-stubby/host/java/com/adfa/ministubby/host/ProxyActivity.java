package com.adfa.ministubby.host;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.reflect.Method;

/**
 * Generic, manifest-pre-declared host for a payload's SECOND (third, …) screen.
 *
 * <p><b>The manifest wall it closes.</b> A hot-loaded payload cannot add its own
 * {@code <activity>} to the shell's fixed manifest, so
 * {@code startActivity(SecondActivity.class)} dies with
 * {@code ActivityNotFoundException}. Instead the payload asks the shell to open a
 * screen through THIS activity, which IS in the manifest, and which loads the
 * payload's screen class from the live {@link PayloadRuntime} and renders it in a
 * REAL window with a REAL back-stack.
 *
 * <p><b>Navigation contract.</b> The payload starts a second screen with an
 * explicit Intent to the shell package, naming the screen class in an extra:
 * <pre>
 *   host.startActivity(new Intent()
 *       .setClassName(host, "com.adfa.ministubby.host.ProxyActivity")
 *       .putExtra("payload_screen", "app.payload.DetailScreen")
 *       .putExtra(…, …));   // any payload-defined extras the screen reads
 * </pre>
 * A payload "screen" class follows the SAME reflection contract as {@code Main}:
 * {@code public static View render(Activity host)} and, optionally,
 * {@code public static View render(Activity host, Bundle args)}. It reads any
 * navigation extras from {@code host.getIntent()}.
 *
 * <p><b>Runtime wiring.</b> On create this activity attaches the same three
 * payload facilities MainActivity uses, via the shared {@link PayloadRuntime}
 * helper: (1) the payload ResourcesLoader (0x80 table) onto its own Resources,
 * (2) the payload's declared theme, and (3) a payload-classloader-aware
 * {@link PayloadViewFactory} on its window inflater — so custom views, theme
 * attrs, and {@code @…/0x80} resource refs all resolve exactly as on the home
 * screen.
 *
 * <p><b>Production refinement (not implemented).</b> A fully transparent version
 * — payload subclasses real {@code android.app.Activity} and calls plain
 * {@code startActivity(SecondActivity.class)} — needs an {@code Instrumentation}
 * hook that intercepts the Intent, substitutes a proxy, and forwards the full
 * lifecycle / {@code onActivityResult} / task semantics (VirtualAPK / Tencent
 * Shadow). This explicit-Intent proxy is the spike-weight subset: real window,
 * real back-stack, real transition, no Instrumentation surgery.
 */
public class ProxyActivity extends Activity {

    private static final String TAG = "MiniStubby";
    /** Intent extra: fully-qualified payload screen class name. */
    public static final String EXTRA_SCREEN = "payload_screen";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Same payload-aware inflater the home screen uses: install our OWN
        // factory instance (setFactory2 is once-per-inflater) that reads the live
        // classloader from PayloadRuntime.
        getLayoutInflater().setFactory2(new PayloadViewFactory());

        // Merge the live payload 0x80 resource table + adopt its theme BEFORE we
        // inflate/render, so theme enforcement and resource lookups succeed.
        PayloadRuntime.attachResources(this);
        PayloadRuntime.applyThemeTo(this);

        String screenClass = getIntent() != null
                ? getIntent().getStringExtra(EXTRA_SCREEN) : null;

        if (screenClass == null || screenClass.isEmpty()) {
            setContentView(errorView("ProxyActivity started with no '"
                    + EXTRA_SCREEN + "' extra"));
            return;
        }

        ClassLoader cl = PayloadRuntime.getClassLoader();
        if (cl == null) {
            setContentView(errorView("no payload loaded — nothing to render for "
                    + screenClass));
            return;
        }

        try {
            Class<?> entry = cl.loadClass(screenClass);
            View view = invokeRender(entry, savedInstanceState);
            setTitle(screenClass);
            setContentView(view);
            Log.i(TAG, "ProxyActivity rendered payload screen " + screenClass);
        } catch (Throwable t) {
            Log.e(TAG, "ProxyActivity failed to render " + screenClass, t);
            setContentView(errorView("failed to render " + screenClass + "\n" + t));
        }
    }

    /**
     * Prefer the extended {@code render(Activity, Bundle)} contract, fall back to
     * {@code render(Activity)} — identical to MainActivity's home-screen dispatch.
     */
    private View invokeRender(Class<?> entry, Bundle savedState) throws Exception {
        Method render2 = null;
        try {
            render2 = entry.getMethod("render", Activity.class, Bundle.class);
        } catch (NoSuchMethodException legacy) {
            // fine — screen only offers render(Activity)
        }
        if (render2 != null) {
            return (View) render2.invoke(null, this, savedState);
        }
        return (View) entry.getMethod("render", Activity.class).invoke(null, this);
    }

    private View errorView(String message) {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFFB00020);
        TextView tv = new TextView(this);
        tv.setText("ProxyActivity error:\n\n" + message);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tv.setPadding(48, 120, 48, 48);
        root.addView(tv, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        return root;
    }
}
