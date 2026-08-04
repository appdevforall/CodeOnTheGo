package com.itsaky.androidide.quickbuild.runtime;

/**
 * Holds the classloader routing decision every {@link QuickBuildAppComponentFactory} override makes.
 *
 * Extracted from the factory so it is JVM-unit-testable without the Android framework.
 */
final class LoaderRouter {

	/**
	 * Picks the loader that should instantiate {@code className}: the payload loader when it can serve the class, else the default.
	 *
	 * The payload loader's parent chain covers the APK, so framework and androidx classes resolve the same either way. A null payload loader means no payload is live and always yields the default. Only ClassNotFoundException is caught - a LinkageError must propagate, because the factory's own catch then re-instantiates through the default loader, which is a stronger fallback than merely picking it.
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
