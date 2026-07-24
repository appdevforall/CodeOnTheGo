package com.itsaky.androidide.quickbuild.runtime;

import android.app.Activity;
import android.app.AppComponentFactory;
import android.app.Application;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.Intent;

/**
 * Routes component instantiation through the CURRENT payload generation's classloader - the mechanism that makes hot reload real: after a payload swap, a recreated activity is instantiated from the NEW loader, and because user classes exist only in the payload dex (never in the APK), the parent-first chain cannot serve a stale copy.
 *
 * Services, receivers and providers route the same way (all hooks are API 28+, our floor). Receivers are re-instantiated per delivery so routing alone keeps them current; services, providers and the Application swap via process restart driven by CoGo (component-proxying design, section 4) - the factory's job is only to make every instantiation, including the post-restart one, use the current loader.
 *
 * Declared as {@code android:appComponentFactory} in the runtime's manifest (merged into the generated test app; framework instantiates it on API 28+). Deliberately androidx-free - this AAR is injected into arbitrary user apps and must not drag a dependency in.
 *
 * Everything falls back to the framework default path on any failure: an app this factory is injected into must at worst behave like a normal app, never crash because of us.
 */
public class QuickBuildAppComponentFactory extends AppComponentFactory {

	/** The current payload loader when it can serve {@code className}, else the default; decision in {@link LoaderRouter} (JVM-unit-tested there). */
	private static ClassLoader pickLoader(ClassLoader defaultLoader, String className) {
		return LoaderRouter.pick(defaultLoader, PayloadStore.INSTANCE.classLoader(), className);
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

	@Override
	public ContentProvider instantiateProvider(ClassLoader cl, String className)
			throws InstantiationException, IllegalAccessException, ClassNotFoundException {
		// Providers instantiate after instantiateApplication but BEFORE
		// Application.onCreate, so the baseline already exists on the normal path;
		// this ensureBaseline is defense-in-depth for exotic entry orders. Nothing
		// here may touch QuickBuildRuntime or any Context - too early.
		PayloadStore.INSTANCE.ensureBaseline(cl);
		try {
			return super.instantiateProvider(pickLoader(cl, className), className);
		} catch (Throwable error) {
			RuntimeLog.e("payload provider instantiation failed for " + className
					+ "; using default loader", error);
			return super.instantiateProvider(cl, className);
		}
	}

	@Override
	public BroadcastReceiver instantiateReceiver(ClassLoader cl, String className, Intent intent)
			throws InstantiationException, IllegalAccessException, ClassNotFoundException {
		// Manifest receivers are created fresh per delivery, so routing through the
		// current loader alone keeps them on current code - no restart needed.
		PayloadStore.INSTANCE.ensureBaseline(cl);
		try {
			return super.instantiateReceiver(pickLoader(cl, className), className, intent);
		} catch (Throwable error) {
			RuntimeLog.e("payload receiver instantiation failed for " + className
					+ "; using default loader", error);
			return super.instantiateReceiver(cl, className, intent);
		}
	}

	@Override
	public Service instantiateService(ClassLoader cl, String className, Intent intent)
			throws InstantiationException, IllegalAccessException, ClassNotFoundException {
		PayloadStore.INSTANCE.ensureBaseline(cl);
		try {
			return super.instantiateService(pickLoader(cl, className), className, intent);
		} catch (Throwable error) {
			RuntimeLog.e("payload service instantiation failed for " + className
					+ "; using default loader", error);
			return super.instantiateService(cl, className, intent);
		}
	}
}
