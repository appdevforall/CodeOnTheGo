package com.itsaky.androidide.quickbuild.runtime;

import android.app.Activity;
import android.app.AppComponentFactory;
import android.app.Application;
import android.content.Intent;

/**
 * Routes component instantiation through the CURRENT payload generation's classloader - the mechanism that makes hot reload real: after a payload swap, a recreated activity is instantiated from the NEW loader, and because user classes exist only in the payload dex (never in the APK), the parent-first chain cannot serve a stale copy.
 *
 * Declared as {@code android:appComponentFactory} in the runtime's manifest (merged into the generated test app; framework instantiates it on API 28+). Deliberately androidx-free - this AAR is injected into arbitrary user apps and must not drag a dependency in.
 *
 * Everything falls back to the framework default path on any failure: an app this factory is injected into must at worst behave like a normal app, never crash because of us.
 */
public class QuickBuildAppComponentFactory extends AppComponentFactory {

	/**
	 * The payload loader when it can serve {@code className} (its parent chain covers the APK, so framework/androidx classes resolve identically), else the default.
	 */
	private static ClassLoader pickLoader(ClassLoader defaultLoader, String className) {
		ClassLoader payloadLoader = PayloadStore.INSTANCE.classLoader();
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

	@Override
	public Activity instantiateActivity(ClassLoader cl, String className, Intent intent)
			throws InstantiationException, IllegalAccessException, ClassNotFoundException {
		PayloadStore.INSTANCE.ensureBaseline(cl);
		try {
			return super.instantiateActivity(pickLoader(cl, className), className, intent);
		} catch (Throwable error) {
			RuntimeLog.e("payload activity instantiation failed for " + className
					+ "; using default loader", error);
			return super.instantiateActivity(cl, className, intent);
		}
	}

	@Override
	public Application instantiateApplication(ClassLoader cl, String className)
			throws InstantiationException, IllegalAccessException, ClassNotFoundException {
		PayloadStore.INSTANCE.ensureBaseline(cl);
		Application application;
		try {
			application = super.instantiateApplication(pickLoader(cl, className), className);
		} catch (Throwable error) {
			RuntimeLog.e("payload application instantiation failed; using default loader", error);
			application = super.instantiateApplication(cl, className);
		}
		// Earliest per-process hook; the runtime defers Context work to the first
		// activity because the Application has no base context yet.
		QuickBuildRuntime.install(application);
		return application;
	}
}
