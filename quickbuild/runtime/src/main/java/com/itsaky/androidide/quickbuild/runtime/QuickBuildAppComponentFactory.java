package com.itsaky.androidide.quickbuild.runtime;

import android.app.Activity;
import android.app.AppComponentFactory;
import android.app.Application;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.Intent;

/**
 * Instantiates every app component through the current payload generation's classloader, which is
 * what makes hot reload work.
 *
 * After a payload swap a recreated activity comes from the new loader, and since user classes
 * exist only in the payload dex, the parent-first chain cannot serve a stale copy. Services,
 * receivers and providers route the same way; receivers are re-instantiated per delivery so
 * routing alone keeps them current, while services, providers and the Application swap via a
 * process restart that CoGo drives.
 *
 * Declared as {@code android:appComponentFactory} in the runtime manifest, which merges into the
 * generated proxy app; the framework instantiates it on API 28+, this module's floor. Androidx-free
 * on purpose - the AAR is injected into arbitrary user apps and must not drag a dependency in.
 * Every override falls back to the framework default on failure, so an app we are injected into
 * behaves at worst like a normal app.
 */
public class QuickBuildAppComponentFactory extends AppComponentFactory {

	/**
	 * Picks the loader for {@code className}; the decision itself lives in {@link LoaderRouter},
	 * where it is unit-tested.
	 *
	 * @param defaultLoader the loader the framework passed this factory
	 * @param className binary name of the component about to be instantiated
	 * @return the payload loader when it can serve the class, else {@code defaultLoader}
	 */
	private static ClassLoader pickLoader(ClassLoader defaultLoader, String className) {
		return LoaderRouter.pick(defaultLoader, PayloadStore.INSTANCE.classLoader(), className);
	}

	/**
	 * Instantiates an activity from the payload loader, so a recreate after a deploy runs new code.
	 *
	 * @param cl the framework's default loader, also the fallback if payload instantiation fails
	 * @param className binary name of the activity, already translated through {@link ComponentMap}
	 *     by whoever built the intent
	 * @param intent the launch intent, passed to the framework untouched
	 * @return the activity instance the framework will attach
	 * @throws InstantiationException if the fallback instantiation fails
	 * @throws IllegalAccessException if the fallback constructor is not accessible
	 * @throws ClassNotFoundException if neither loader can resolve {@code className}
	 */
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

	/**
	 * Routes the Application through the payload loader and installs the runtime, the earliest
	 * per-process hook.
	 *
	 * @param cl the framework's default loader, also the fallback if payload instantiation fails
	 * @param className binary name of the app's Application class
	 * @return the Application instance, with {@link QuickBuildRuntime} already installed on it
	 * @throws InstantiationException if the fallback instantiation fails
	 * @throws IllegalAccessException if the fallback constructor is not accessible
	 * @throws ClassNotFoundException if neither loader can resolve {@code className}
	 */
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

	/**
	 * Instantiates a content provider from the payload loader.
	 *
	 * Providers cannot hot-swap: one already created keeps its class until CoGo restarts the
	 * process, so this only keeps a provider created after a deploy on current code.
	 *
	 * @param cl the framework's default loader, also the fallback if payload instantiation fails
	 * @param className binary name of the provider
	 * @return the provider instance the framework will attach
	 * @throws InstantiationException if the fallback instantiation fails
	 * @throws IllegalAccessException if the fallback constructor is not accessible
	 * @throws ClassNotFoundException if neither loader can resolve {@code className}
	 */
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

	/**
	 * Instantiates a broadcast receiver from the payload loader, which is all a receiver needs to
	 * stay on current code.
	 *
	 * @param cl the framework's default loader, also the fallback if payload instantiation fails
	 * @param className binary name of the receiver
	 * @param intent the broadcast being delivered, passed to the framework untouched
	 * @return the receiver instance for this one delivery
	 * @throws InstantiationException if the fallback instantiation fails
	 * @throws IllegalAccessException if the fallback constructor is not accessible
	 * @throws ClassNotFoundException if neither loader can resolve {@code className}
	 */
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

	/**
	 * Instantiates a service from the payload loader.
	 *
	 * A service already running keeps its class, which is why {@link ServiceTracker} makes a
	 * deploy touching service code restart the process instead of hot-swapping.
	 *
	 * @param cl the framework's default loader, also the fallback if payload instantiation fails
	 * @param className binary name of the service
	 * @param intent the intent that started the service, passed to the framework untouched
	 * @return the service instance the framework will attach
	 * @throws InstantiationException if the fallback instantiation fails
	 * @throws IllegalAccessException if the fallback constructor is not accessible
	 * @throws ClassNotFoundException if neither loader can resolve {@code className}
	 */
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
