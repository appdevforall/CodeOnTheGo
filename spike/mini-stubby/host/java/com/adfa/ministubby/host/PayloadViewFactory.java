package com.adfa.ministubby.host;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;

import java.lang.reflect.Constructor;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LayoutInflater.Factory2 that resolves custom-View class names in payload layout
 * XML against the CURRENT payload DexClassLoader (read from {@link PayloadRuntime}).
 *
 * <p>Shared by both {@link MainActivity} and {@link ProxyActivity}: each Activity
 * instance installs its OWN {@code PayloadViewFactory} on its OWN window inflater
 * ({@code setFactory2} is once-per-inflater), but all instances read the single
 * live classloader from {@link PayloadRuntime}, so every screen inflates against
 * the same generation with no per-Activity wiring.
 *
 * <p>Design constraints it satisfies (unchanged from the phase-3 nested version,
 * now sourcing the loader from {@link PayloadRuntime} instead of a private field):
 * <ul>
 *   <li>The payload classloader changes every hot reload → this object holds no
 *       loader itself; it reads {@link PayloadRuntime#getClassLoader()} each call
 *       and clears its per-generation constructor cache when
 *       {@link PayloadRuntime#getGeneration()} advances. Inflater clones (dialogs)
 *       copy the factory REFERENCE, so they stay current too.</li>
 *   <li>Framework widgets keep inflating through the default path → return null
 *       for anything this factory doesn't own: unqualified names ("TextView"),
 *       classes the payload loader can't find, non-Views, and — crucially —
 *       classes whose DEFINING loader is not the payload loader (parent-first
 *       delegation means the payload loader "finds" android.widget.* too;
 *       deferring those keeps the shell's behavior byte-identical).</li>
 * </ul>
 */
final class PayloadViewFactory implements LayoutInflater.Factory2 {

    private final Map<String, Constructor<? extends View>> ctorCache =
            new ConcurrentHashMap<>();
    private volatile int cachedGen = -1;

    @Override
    public View onCreateView(View parent, String name, Context context, AttributeSet attrs) {
        // Unqualified names are framework widgets ("TextView") — the default path
        // prepends android.widget./android.view. etc. Not ours.
        if (name.indexOf('.') < 0) return null;
        ClassLoader cl = PayloadRuntime.getClassLoader();
        if (cl == null) return null;

        // Drop stale constructors when the payload generation advances — an old-gen
        // Constructor must never outlive the classloader that defined its class.
        int gen = PayloadRuntime.getGeneration();
        if (gen != cachedGen) {
            ctorCache.clear();
            cachedGen = gen;
        }

        Constructor<? extends View> ctor = ctorCache.get(name);
        if (ctor == null) {
            Class<?> clazz;
            try {
                clazz = cl.loadClass(name);
            } catch (ClassNotFoundException notPayload) {
                return null; // let the default path try (and produce its own error)
            }
            // Parent-first delegation resolves framework/host classes too; only
            // intercept classes the payload dex actually DEFINES.
            if (clazz.getClassLoader() != cl || !View.class.isAssignableFrom(clazz)) {
                return null;
            }
            try {
                ctor = clazz.asSubclass(View.class)
                        .getConstructor(Context.class, AttributeSet.class);
            } catch (NoSuchMethodException e) {
                throw new RuntimeException("payload view " + name
                        + " lacks the (Context, AttributeSet) constructor required"
                        + " for XML inflation", e);
            }
            ctorCache.put(name, ctor);
        }
        try {
            return ctor.newInstance(context, attrs);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("payload view " + name + " failed to instantiate", e);
        }
    }

    @Override
    public View onCreateView(String name, Context context, AttributeSet attrs) {
        return onCreateView(null, name, context, attrs);
    }
}
