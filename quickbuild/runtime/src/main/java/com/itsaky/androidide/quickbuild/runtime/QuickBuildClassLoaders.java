package com.itsaky.androidide.quickbuild.runtime;

/**
 * Supplies the classloader that generated proxy activities return from their
 * {@code getClassLoader()} override.
 *
 * Public for that reason (see ProxySourceGenerator in :gradle-plugin); everything else in this
 * AAR is package-private. The override is needed because {@link QuickBuildAppComponentFactory}
 * only chooses the loader that instantiates the Activity object - the framework still pins
 * {@code Context#getClassLoader()} to the base APK's loader at attach time. Anything resolving a
 * class by name through the Context, such as LayoutInflater on a custom view tag or an androidx
 * FragmentFactory on a {@code <fragment>} tag, would otherwise miss every payload-only class.
 */
public final class QuickBuildClassLoaders {

	/**
	 * Returns the loader a proxy activity should report: the payload loader whenever one is live.
	 *
	 * The payload loader's parent is the APK classloader (see {@link PayloadStore}), so it
	 * resolves everything {@code fallback} would plus the payload-only classes, never less. The
	 * fallback should be unreachable from a proxy activity, whose own bytecode is payload-only,
	 * and exists only so a misinjected app cannot crash.
	 *
	 * @param fallback the loader to report when no payload is live, normally the activity's
	 *     {@code super.getClassLoader()}; may be null, in which case null is returned
	 * @return the loader the caller must report from {@code getClassLoader()}
	 */
	public static ClassLoader forActivity(ClassLoader fallback) {
		return choose(PayloadStore.INSTANCE.classLoader(), fallback);
	}

	/**
	 * Prefers the payload loader over the fallback; extracted so the choice is testable without
	 * the PayloadStore singleton.
	 *
	 * @param payloadLoader the live payload loader, or null when no payload has been applied
	 * @param fallback the loader to fall back to; returned verbatim, null included
	 * @return {@code payloadLoader} when non-null, otherwise {@code fallback}
	 */
	static ClassLoader choose(ClassLoader payloadLoader, ClassLoader fallback) {
		return payloadLoader != null ? payloadLoader : fallback;
	}

	private QuickBuildClassLoaders() {}
}
