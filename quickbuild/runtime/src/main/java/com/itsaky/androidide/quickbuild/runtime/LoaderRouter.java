package com.itsaky.androidide.quickbuild.runtime;

/**
 * Holds the classloader routing decision every {@link QuickBuildAppComponentFactory} override
 * makes.
 *
 * Extracted from the factory so it is JVM-unit-testable without the Android framework.
 */
final class LoaderRouter {

	/**
	 * Picks the loader that should instantiate {@code className}: the payload loader when it can
	 * serve the class, else the default.
	 *
	 * The payload loader's parent chain covers the APK, so framework and androidx classes resolve
	 * the same either way. Only ClassNotFoundException is caught - a LinkageError must propagate
	 * so the factory's own catch re-instantiates through the default loader, a stronger fallback.
	 *
	 * @param defaultLoader the loader the framework handed the factory; returned whenever the
	 *     payload cannot serve the class
	 * @param payloadLoader the live payload loader, or null when no payload is live, which always
	 *     yields {@code defaultLoader}
	 * @param className binary name of the component the framework is about to instantiate
	 * @return the loader to instantiate {@code className} with, never null unless
	 *     {@code defaultLoader} was
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
