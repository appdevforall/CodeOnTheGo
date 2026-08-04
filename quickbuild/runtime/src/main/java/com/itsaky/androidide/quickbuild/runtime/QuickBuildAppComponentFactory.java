package com.itsaky.androidide.quickbuild.runtime;

import android.app.Activity;
import android.app.AppComponentFactory;
import android.app.Application;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.Intent;

/**
 * Instantiates every app component through the current payload generation's classloader, which is what makes hot reload work.
 *
 * After a payload swap a recreated activity comes from the new loader, and since user classes exist only in the payload dex, the parent-first chain cannot serve a stale copy. Services, receivers and providers route the same way; receivers are re-instantiated per delivery so routing alone keeps them current, while services, providers and the Application swap via a process restart that CoGo drives.
 *
 * Declared as {@code android:appComponentFactory} in the runtime manifest, which merges into the generated proxy app; the framework instantiates it on API 28+, this module's floor. Androidx-free on purpose - the AAR is injected into arbitrary user apps and must not drag a dependency in. Every override falls back to the framework default on failure, so an app we are injected into behaves at worst like a normal app.
 */
public class QuickBuildAppComponentFactory extends AppComponentFactory {

	/** Picks the loader for {@code className}; the decision itself lives in {@link LoaderRouter}, where it is unit-tested. */
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

	/** Routes the Application through the payload loader and installs the runtime, the earliest per-process hook. */
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
		// The runtime defers Context work to the first activity: the Application has
		// no base context yet.
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
