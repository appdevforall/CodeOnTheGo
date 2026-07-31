package com.itsaky.androidide.quickbuild.runtime;

/**
 * The classloader routing decision shared by every {@link QuickBuildAppComponentFactory} override, extracted from the factory so it is JVM-unit-testable (the factory itself needs the Android framework).
 *
 * Catches ONLY ClassNotFoundException: any other failure from the probe (LinkageError etc.) must propagate, because the factory's per-override catch responds by re-instantiating through the default loader - a stronger fallback than merely picking it.
 */
final class LoaderRouter {

	/**
	 * The payload loader when it can serve {@code className} (its parent chain covers the APK, so framework/androidx classes resolve identically), else the default. A null payload loader (no payload live, runtime inert) always yields the default.
	 */
	static ClassLoader pick(ClassLoader defaultLoader, ClassLoader payloadLoader, String className) {
		if (payloadLoader == null) {
			return defaultLoader;
		}
		try {
			payloadLoader.loadClass(className);
			return payloadLoader;
		} catch (ClassNotFoundException notInPayloadChain) {
			return defaultLoader;
		}
	}

	private LoaderRouter() {}
}
