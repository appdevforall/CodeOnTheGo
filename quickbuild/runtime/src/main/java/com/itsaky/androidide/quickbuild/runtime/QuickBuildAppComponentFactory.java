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
 * After a payload swap a recreated activity comes from the new loader, and since user classes exist only in the payload dex the parent-first chain cannot serve a stale copy. Receivers are re-instantiated per delivery so routing alone keeps them current; services, providers and the Application swap via a process restart CoGo drives.
 *
 * Declared as {@code android:appComponentFactory} in the runtime manifest, which the framework instantiates on API 28+. Androidx-free on purpose - the AAR is injected into arbitrary user apps and must not drag a dependency in. Every override falls back to the framework default on failure; when the fallback fails too, the PAYLOAD failure propagates - see {@link #rethrowPayloadFailure}.
 */
public class QuickBuildAppComponentFactory extends AppComponentFactory {

	/**
	 * Throws the failure that best explains a component we could not instantiate from either loader: the PAYLOAD one.
	 *
	 * The fallback exists for framework classes that really do live in the APK, so when it fails too the class was a user class and the default loader was never going to find it - its {@code ClassNotFoundException} is a consequence, not the cause, and reporting it would leave the real failure buried in logcat.
	 *
	 * @param payloadError
	 *            what the payload loader threw; rethrown as-is when its type allows, so its stack survives
	 * @param fallbackError
	 *            what the default loader then threw; attached as suppressed so it is not lost either
	 * @return never returns - declared so callers can write {@code throw rethrowPayloadFailure(...)} and the compiler sees the path end
	 * @throws InstantiationException
	 *             when that is what the payload loader threw
	 * @throws IllegalAccessException
	 *             when that is what the payload loader threw
	 * @throws ClassNotFoundException
	 *             when that is what the payload loader threw
	 */
	static RuntimeException rethrowPayloadFailure(Throwable payloadError, Throwable fallbackError)
			throws InstantiationException, IllegalAccessException, ClassNotFoundException {
		if (fallbackError != payloadError) {
			payloadError.addSuppressed(fallbackError);
		}
		if (payloadError instanceof InstantiationException) {
			throw (InstantiationException) payloadError;
		}
		if (payloadError instanceof IllegalAccessException) {
			throw (IllegalAccessException) payloadError;
		}
		if (payloadError instanceof ClassNotFoundException) {
			throw (ClassNotFoundException) payloadError;
		}
		if (payloadError instanceof Error) {
			throw (Error) payloadError;
		}
		if (payloadError instanceof RuntimeException) {
			throw (RuntimeException) payloadError;
		}
		// A checked throwable none of these signatures allow. Wrapping keeps it as the cause,
		// which is the whole point of this method.
		return new RuntimeException(payloadError);
	}

	/**
	 * Picks the loader for {@code className}; the decision itself lives in {@link LoaderRouter}, where it is unit-tested.
	 *
	 * @param defaultLoader
	 *            the loader the framework passed this factory
	 * @param className
	 *            binary name of the component about to be instantiated
	 * @return the payload loader when it can serve the class, else {@code defaultLoader}
	 */
	private static ClassLoader pickLoader(ClassLoader defaultLoader, String className) {
		return LoaderRouter.pick(defaultLoader, PayloadStore.INSTANCE.classLoader(), className);
	}

	/**
	 * Instantiates an activity from the payload loader, so a recreate after a deploy runs new code.
	 *
	 * @param cl
	 *            the framework's default loader, also the fallback if payload instantiation fails
	 * @param className
	 *            binary name of the activity, as the manifest declares it
	 * @param intent
	 *            the launch intent, passed to the framework untouched
	 * @return the activity instance the framework will attach
	 * @throws InstantiationException
	 *             if instantiation failed on both loaders
	 * @throws IllegalAccessException
	 *             if the constructor was not accessible on either loader
	 * @throws ClassNotFoundException
	 *             if neither loader can resolve {@code className}
	 */
	@Override
	public Activity instantiateActivity(ClassLoader cl, String className, Intent intent)
			throws InstantiationException, IllegalAccessException, ClassNotFoundException {
		PayloadStore.INSTANCE.ensureBaseline(cl);
		try {
			return super.instantiateActivity(pickLoader(cl, className), className, intent);
		} catch (Throwable payloadError) {
			RuntimeLog.e("payload activity instantiation failed for " + className
					+ "; using default loader", payloadError);
			try {
				return super.instantiateActivity(cl, className, intent);
			} catch (Throwable fallbackError) {
				throw rethrowPayloadFailure(payloadError, fallbackError);
			}
		}
	}

	/**
	 * Routes the Application through the payload loader and installs the runtime, the earliest per-process hook.
	 *
	 * @param cl
	 *            the framework's default loader, also the fallback if payload instantiation fails
	 * @param className
	 *            binary name of the app's Application class
	 * @return the Application instance, with {@link QuickBuildRuntime} already installed on it
	 * @throws InstantiationException
	 *             if instantiation failed on both loaders
	 * @throws IllegalAccessException
	 *             if the constructor was not accessible on either loader
	 * @throws ClassNotFoundException
	 *             if neither loader can resolve {@code className}
	 */
	@Override
	public Application instantiateApplication(ClassLoader cl, String className)
			throws InstantiationException, IllegalAccessException, ClassNotFoundException {
		PayloadStore.INSTANCE.ensureBaseline(cl);
		Application application;
		try {
			application = super.instantiateApplication(pickLoader(cl, className), className);
		} catch (Throwable payloadError) {
			RuntimeLog.e("payload application instantiation failed; using default loader", payloadError);
			try {
				application = super.instantiateApplication(cl, className);
			} catch (Throwable fallbackError) {
				throw rethrowPayloadFailure(payloadError, fallbackError);
			}
		}
		// The runtime defers Context work to the first activity: the Application has
		// no base context yet.
		QuickBuildRuntime.install(application);
		return application;
	}

	/**
	 * Instantiates a content provider from the payload loader.
	 *
	 * Providers cannot hot-swap: one already created keeps its class until CoGo restarts the process, so this only keeps a provider created after a deploy on current code.
	 *
	 * @param cl
	 *            the framework's default loader, also the fallback if payload instantiation fails
	 * @param className
	 *            binary name of the provider
	 * @return the provider instance the framework will attach
	 * @throws InstantiationException
	 *             if instantiation failed on both loaders
	 * @throws IllegalAccessException
	 *             if the constructor was not accessible on either loader
	 * @throws ClassNotFoundException
	 *             if neither loader can resolve {@code className}
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
		} catch (Throwable payloadError) {
			RuntimeLog.e("payload provider instantiation failed for " + className
					+ "; using default loader", payloadError);
			try {
				return super.instantiateProvider(cl, className);
			} catch (Throwable fallbackError) {
				throw rethrowPayloadFailure(payloadError, fallbackError);
			}
		}
	}

	/**
	 * Instantiates a broadcast receiver from the payload loader, which is all a receiver needs to stay on current code.
	 *
	 * @param cl
	 *            the framework's default loader, also the fallback if payload instantiation fails
	 * @param className
	 *            binary name of the receiver
	 * @param intent
	 *            the broadcast being delivered, passed to the framework untouched
	 * @return the receiver instance for this one delivery
	 * @throws InstantiationException
	 *             if instantiation failed on both loaders
	 * @throws IllegalAccessException
	 *             if the constructor was not accessible on either loader
	 * @throws ClassNotFoundException
	 *             if neither loader can resolve {@code className}
	 */
	@Override
	public BroadcastReceiver instantiateReceiver(ClassLoader cl, String className, Intent intent)
			throws InstantiationException, IllegalAccessException, ClassNotFoundException {
		// Manifest receivers are created fresh per delivery, so routing through the
		// current loader alone keeps them on current code - no restart needed.
		PayloadStore.INSTANCE.ensureBaseline(cl);
		try {
			return super.instantiateReceiver(pickLoader(cl, className), className, intent);
		} catch (Throwable payloadError) {
			RuntimeLog.e("payload receiver instantiation failed for " + className
					+ "; using default loader", payloadError);
			try {
				return super.instantiateReceiver(cl, className, intent);
			} catch (Throwable fallbackError) {
				throw rethrowPayloadFailure(payloadError, fallbackError);
			}
		}
	}

	/**
	 * Instantiates a service from the payload loader.
	 *
	 * A service already running keeps its class, which is why a deploy touching service code restarts the process instead of hot-swapping.
	 *
	 * @param cl
	 *            the framework's default loader, also the fallback if payload instantiation fails
	 * @param className
	 *            binary name of the service
	 * @param intent
	 *            the intent that started the service, passed to the framework untouched
	 * @return the service instance the framework will attach
	 * @throws InstantiationException
	 *             if instantiation failed on both loaders
	 * @throws IllegalAccessException
	 *             if the constructor was not accessible on either loader
	 * @throws ClassNotFoundException
	 *             if neither loader can resolve {@code className}
	 */
	@Override
	public Service instantiateService(ClassLoader cl, String className, Intent intent)
			throws InstantiationException, IllegalAccessException, ClassNotFoundException {
		PayloadStore.INSTANCE.ensureBaseline(cl);
		try {
			return super.instantiateService(pickLoader(cl, className), className, intent);
		} catch (Throwable payloadError) {
			RuntimeLog.e("payload service instantiation failed for " + className
					+ "; using default loader", payloadError);
			try {
				return super.instantiateService(cl, className, intent);
			} catch (Throwable fallbackError) {
				throw rethrowPayloadFailure(payloadError, fallbackError);
			}
		}
	}
}
