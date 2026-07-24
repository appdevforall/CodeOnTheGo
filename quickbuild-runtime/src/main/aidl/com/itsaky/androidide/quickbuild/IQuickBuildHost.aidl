package com.itsaky.androidide.quickbuild;

import com.itsaky.androidide.quickbuild.IQuickBuildTarget;

/**
 * CoGo side of the deploy channel (bound service, LogSender bind pattern). The test app
 * binds on launch and registers its callback. CoGo verifies Binder.getCallingUid()
 * against the test app's installed uid on every call.
 */
interface IQuickBuildHost {

	/**
	 * Register the test app. CoGo replies (possibly immediately) with an
	 * {@link IQuickBuildTarget#onPayload} carrying the current generation when the
	 * app's running generation is stale.
	 */
	void connect(IQuickBuildTarget target, String packageName, long runningGeneration);

	/** The payload for {@code generation} was loaded and rendered in {@code reloadMillis}. */
	oneway void reportReloaded(long generation, long reloadMillis);

	/** The payload for {@code generation} crashed in render/lifecycle. */
	oneway void reportCrash(long generation, String stackSummary);

	void disconnect(String packageName);
}
