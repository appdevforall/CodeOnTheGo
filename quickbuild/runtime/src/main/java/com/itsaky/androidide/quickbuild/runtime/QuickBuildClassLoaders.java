package com.itsaky.androidide.quickbuild.runtime;

/**
 * PUBLIC because the generated proxy activities call it from their {@code getClassLoader()} override (see ProxySourceGenerator in :gradle-plugin) - everything else in this AAR stays package-private.
 *
 * Why an Activity-level override is needed: {@link QuickBuildAppComponentFactory} picks the payload classloader to INSTANTIATE the Activity object, but that choice never changes what {@code Context#getClassLoader()} returns afterwards - framework code fixes that to the LoadedApk's (base APK's) classloader at attach time, independent of which loader actually defined the Activity's class. Every by-NAME class resolution that goes through {@code context.getClassLoader()} - LayoutInflater resolving a custom view tag, androidx FragmentFactory resolving a {@code <fragment>}/Navigation-Component destination - therefore still misses every payload-only class (i.e. every user class) unless the Activity itself overrides {@code getClassLoader()}. Proxy activities are the one class the generator fully controls, so the override lives there.
 */
public final class QuickBuildClassLoaders {

	/**
	 * The classloader a proxy activity's {@code getClassLoader()} override should return. The payload loader's parent IS the APK classloader (see {@link PayloadStore}), so it resolves every class {@code fallback} would plus every payload-only class - never a narrower view. Falls back to {@code fallback} only when no payload is live yet, which should not happen for a proxy activity (its own bytecode is payload-only, so reaching this call already proves a payload loaded) - defense-in-depth matching the never-crash-an-app-we-were-wrongly-injected-into philosophy elsewhere in this AAR.
	 */
	public static ClassLoader forActivity(ClassLoader fallback) {
		return choose(PayloadStore.INSTANCE.classLoader(), fallback);
	}

	/** Extracted so the decision is JVM-testable without touching the PayloadStore singleton. */
	static ClassLoader choose(ClassLoader payloadLoader, ClassLoader fallback) {
		return payloadLoader != null ? payloadLoader : fallback;
	}

	private QuickBuildClassLoaders() {}
}
