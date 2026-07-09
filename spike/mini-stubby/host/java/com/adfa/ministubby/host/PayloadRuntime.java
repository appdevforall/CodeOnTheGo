package com.adfa.ministubby.host;

import android.app.Activity;
import android.content.res.loader.ResourcesLoader;
import android.util.Log;

/**
 * Process-wide singleton holding the CURRENT payload runtime — the pieces every
 * Activity that renders payload UI needs, kept in one place so both the launcher
 * {@link MainActivity} and the manifest-declared {@link ProxyActivity} read the
 * SAME state.
 *
 * <p>Why a static singleton (acceptable for this spike): the shell hot-reloads a
 * payload by swapping a {@link dalvik.system.DexClassLoader} + a
 * {@link ResourcesLoader} + the payload's declared theme. {@code MainActivity}
 * owns that reload machinery and is the only writer; it calls
 * {@link #setClassLoader}, {@link #setResourcesLoader}, {@link #setThemeRes} on
 * every successful reload (and rolls them back on a failed one). Any
 * {@code ProxyActivity} launched afterwards is a separate Activity instance in
 * the SAME process, so it just reads these fields to attach the live payload
 * runtime to its own window. There is exactly one payload generation live at a
 * time, so a singleton is the natural shape.
 *
 * <p>Production refinement: a fully transparent "payload uses real
 * {@code android.app.Activity} subclasses + plain {@code startActivity}" design
 * would replace this + {@code ProxyActivity} with an {@code Instrumentation}
 * hook that rewrites Intents and forwards lifecycle (the VirtualAPK / Tencent
 * Shadow pattern). That is out of scope for the spike; here the payload names
 * the target screen class explicitly (see {@link ProxyActivity}).
 */
final class PayloadRuntime {

    private static final String TAG = "MiniStubby";

    private PayloadRuntime() {}

    /** Current payload DexClassLoader. Null until the first payload loads. */
    private static volatile ClassLoader sClassLoader;
    /** Current payload ResourcesLoader (the 0x80 table). Null until first load. */
    private static volatile ResourcesLoader sResourcesLoader;
    /** Payload's declared application theme (a 0x80 style id), or 0 for none. */
    private static volatile int sThemeRes;
    /**
     * Bumped every time the classloader is swapped, so a {@link PayloadViewFactory}
     * shared across inflater clones can invalidate its per-generation constructor
     * cache without holding a back-reference to each reload.
     */
    private static volatile int sGeneration;

    static ClassLoader getClassLoader() {
        return sClassLoader;
    }

    /** Retarget the current payload classloader and invalidate cached constructors. */
    static synchronized void setClassLoader(ClassLoader cl) {
        sClassLoader = cl;
        sGeneration++;
    }

    static int getGeneration() {
        return sGeneration;
    }

    static ResourcesLoader getResourcesLoader() {
        return sResourcesLoader;
    }

    static void setResourcesLoader(ResourcesLoader loader) {
        sResourcesLoader = loader;
    }

    static void setThemeRes(int themeRes) {
        sThemeRes = themeRes;
    }

    static int getThemeRes() {
        return sThemeRes;
    }

    /**
     * Adopt the current payload's declared theme onto {@code activity}'s theme —
     * the same treatment {@code MainActivity} applies to itself, so a screen shown
     * in {@link ProxyActivity} passes Material3's {@code ThemeEnforcement} check
     * and resolves {@code ?attr/…} against the payload palette.
     *
     * <p>Rebuilds from a clean base each call ({@code getTheme().applyStyle}
     * accumulates), then layers the payload theme. A payload that declares no
     * theme ({@code themeRes == 0}) is left on the caller's manifest theme.
     */
    static void applyThemeTo(Activity activity) {
        int themeRes = sThemeRes;
        if (themeRes == 0) {
            Log.i(TAG, "payload declares no theme; keeping shell theme");
            return;
        }
        try {
            activity.getTheme().setTo(activity.getResources().newTheme());   // clean slate
            activity.getTheme().applyStyle(
                    android.R.style.Theme_DeviceDefault_DayNight, true);     // shell base
            activity.getTheme().applyStyle(themeRes, true);                  // payload theme
            Log.i(TAG, "applied payload theme 0x" + Integer.toHexString(themeRes));
        } catch (Throwable t) {
            Log.w(TAG, "applyThemeTo failed (continuing with caller theme)", t);
        }
    }

    /**
     * Attach the live payload resource table to {@code activity}'s own
     * {@link android.content.res.Resources}. A {@link ResourcesLoader} may back
     * more than one Resources object, so sharing MainActivity's loader with a
     * ProxyActivity is safe — the payload's 0x80 ids resolve identically in both.
     */
    static void attachResources(Activity activity) {
        ResourcesLoader loader = sResourcesLoader;
        if (loader == null) return;
        try {
            activity.getResources().addLoaders(loader);
        } catch (Throwable t) {
            // addLoaders throws if this exact loader is already attached — benign.
            Log.w(TAG, "attachResources: " + t);
        }
    }
}
