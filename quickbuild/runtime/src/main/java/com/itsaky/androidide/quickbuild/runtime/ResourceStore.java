package com.itsaky.androidide.quickbuild.runtime;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.loader.ResourcesLoader;
import android.content.res.loader.ResourcesProvider;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Owns the payload's resource and asset overrides.
 *
 * The payload fd is always the whole relinked resource apk from {@code Aapt2Link}, never a bare table: a bare table cannot back a file-typed resource such as a layout or a drawable XML.
 *
 * The swap mechanism follows {@link ResourceSwapStrategy}. On API 30+ one long-lived {@link ResourcesLoader} has its providers swapped per payload; one loader suffices because an attached loader propagates provider changes, so an activity attaches once and follows every later generation. On API 28/29 {@link LegacyResourceSwap} addAssetPath's the apk instead, and nothing there can serve assets, so CoGo's classifier routes asset-bearing edits to a full Gradle build.
 *
 * The asset overlay can add and replace but not hide: a deleted asset stays readable until the next proxy app build.
 */
final class ResourceStore {

	/** The process-wide store; the strategy is fixed from the device's SDK level at class init. */
	static final ResourceStore INSTANCE = new ResourceStore();

	/** Cache subdirectory holding the API 28/29 relinked apks, one per generation. */
	private static final String LEGACY_TABLE_DIR = "quickbuild-res";

	/** Cache subdirectory holding the cumulative extracted assets and their baseline marker. */
	private static final String ASSETS_ROOT_DIR = "quickbuild-assets";

	/**
	 * Hands a swap failure to the caller's listener without letting the listener's own failure escape.
	 *
	 * This runs on the main thread inside the swap's guard, so a throw here would replace a resource failure with an unrelated crash.
	 *
	 * @param onFailure
	 *            the caller's listener; null is a no-op
	 * @param error
	 *            the failure to report, already logged
	 */
	private static void reportSwapFailure(SwapFailure onFailure, Throwable error) {
		if (onFailure == null) {
			return;
		}
		try {
			onFailure.onSwapFailed(error);
		} catch (Throwable reportFailure) {
			RuntimeLog.e("resource swap failure listener threw", reportFailure);
		}
	}

	private final ResourceSwapStrategy strategy;

	/** The API 30+ loader, created on the first resource or assets payload and never replaced. */
	private volatile ResourcesLoader loader;

	/** The table provider inside {@link #loader}; the previous one is closed after each swap. */
	private volatile ResourcesProvider provider;

	/** The assets-only provider inside {@link #loader}; the previous one is closed after each swap. */
	private volatile ResourcesProvider assetsProvider;

	/** The directory provider backing {@link #assetsProvider}; closed alongside it. */
	private volatile DirectoryAssetsProvider assetsDirProvider;

	/** The newest API 28/29 apk, mounted onto each new Resources by {@link #attachTo}. */
	private volatile File legacyTableZip;

	/** Latches the unsupported-SDK warning so it is logged once, not once per deploy. */
	private boolean warnedNoResourceReload;

	/** Whether the application Resources has the loader; written under the monitor on the main thread. */
	private boolean attachedAppResources;

	/**
	 * @param strategy
	 *            the swap mechanism to use; injected so tests can drive each branch without an SDK level
	 */
	ResourceStore(ResourceSwapStrategy strategy) {
		this.strategy = strategy;
	}

	/** Builds {@link #INSTANCE}, picking the strategy from this device's SDK level. */
	private ResourceStore() {
		this(ResourceSwapStrategy.forSdk(Build.VERSION.SDK_INT));
	}

	/**
	 * Merges a changed-assets zip into the cumulative override dir under {@code cacheRoot} and serves it through the loader.
	 *
	 * The merge clears the dir first when it belongs to another baseline, so assets never outlive the baseline they were deployed onto.
	 *
	 * A failed merge is not undone: there is no asset rollback, so whatever it already wrote stays live until the next successful deploy onto the same baseline overwrites it.
	 *
	 * @param assetsFd
	 *            the changed-assets zip; always closed, success or failure
	 * @param baselineFingerprint
	 *            the running baseline's fingerprint, which keys the cumulative dir
	 * @param appContext
	 *            application context, for the cache dir the cumulative override lives under and the Resources the loader attaches to
	 * @param onFailure
	 *            told when the posted provider swap fails, since that lands after this method returns; null when the caller has nothing to do about it
	 * @throws IOException
	 *             on a read, extraction, path-traversal or provider failure; the previous override stays live
	 */
	void applyAssets(ParcelFileDescriptor assetsFd, String baselineFingerprint, Context appContext,
			SwapFailure onFailure) throws IOException {
		File assetsRoot = new File(appContext.getCacheDir(), ASSETS_ROOT_DIR);
		InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(assetsFd);
		try {
			int extracted = AssetExtractor.extractCumulative(in, assetsRoot, baselineFingerprint);
			if (strategy == ResourceSwapStrategy.RESOURCES_LOADER) {
				refreshAssetsProvider(AssetExtractor.currentDir(assetsRoot), appContext, onFailure);
			}
			RuntimeLog.i("merged " + extracted + " changed asset(s) into the override");
		} finally {
			try {
				in.close();
			} catch (IOException ignored) {
				// Nothing useful to do with a failed close.
			}
		}
	}

	/**
	 * Swaps in a new resource table, using whichever strategy this API level supports.
	 *
	 * A swap that already took is not undone: the deploy path's rollback covers the dex payload only, so a table applied before a later step threw stays mounted until the next successful deploy.
	 *
	 * @param tableFd
	 *            the relinked resource apk; always closed, success or failure
	 * @param generation
	 *            the payload generation, used only by the API 28/29 path to name its file
	 * @param appContext
	 *            application context, for the Resources the loader attaches to and, on API 28/29, the cache dir and the Resources to mount onto
	 * @param onFailure
	 *            told when the posted provider swap fails, since that lands after this method returns; null when the caller has nothing to do about it
	 * @throws IOException
	 *             when the swap fails; an unsupported SDK is not a failure, it warns once and drops the payload
	 */
	void applyTable(ParcelFileDescriptor tableFd, long generation, Context appContext,
			SwapFailure onFailure) throws IOException {
		switch (strategy) {
		case RESOURCES_LOADER:
			applyTableWithLoader(tableFd, appContext, onFailure);
			return;
		case LEGACY_ASSET_PATH:
			applyTableLegacy(tableFd, generation, appContext);
			return;
		default:
			Streams.closeQuietly(tableFd);
			synchronized (this) {
				if (!warnedNoResourceReload) {
					warnedNoResourceReload = true;
					RuntimeLog.w("resource payloads need API 28+; ignoring");
				}
			}
		}
	}

	/**
	 * Attaches the current resource override to a newly created {@code resources}.
	 *
	 * Uses the loader on API 30+ and the current table zip on 28/29; both are idempotent, and it is a no-op until the first resource payload arrives. A failed attach is logged, never fatal.
	 *
	 * @param resources
	 *            the newly created activity or context Resources, attached to before it inflates anything or it resolves against the old table; null is ignored
	 */
	void attachTo(Resources resources) {
		if (resources == null) {
			return;
		}
		if (strategy == ResourceSwapStrategy.RESOURCES_LOADER) {
			attachLoaderTo(resources);
		} else if (strategy == ResourceSwapStrategy.LEGACY_ASSET_PATH) {
			File zip = legacyTableZip;
			if (zip == null) {
				return;
			}
			try {
				LegacyResourceSwap.addAssetPath(resources.getAssets(), zip.getAbsolutePath());
				LegacyResourceSwap.flushCaches(resources);
			} catch (Throwable error) {
				RuntimeLog.d("legacy attachTo skipped", error);
			}
		}
	}

	/**
	 * API 28/29 swap: write the apk to disk, addAssetPath it into the application AssetManager, flush caches.
	 *
	 * The deploy's activity recreate then re-resolves from the new table. A throw rolls back the dex payload only, not this path's own on-disk apk or an addAssetPath that already succeeded.
	 *
	 * @param tableFd
	 *            the relinked resource apk; always closed, success or failure
	 * @param generation
	 *            the payload generation, which names the file on disk
	 * @param appContext
	 *            application context, for the cache dir and the Resources to mount onto
	 * @throws IOException
	 *             when the write or the mount fails; the previous table stays live
	 */
	private void applyTableLegacy(ParcelFileDescriptor tableFd, long generation,
			Context appContext) throws IOException {
		InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(tableFd);
		try {
			File dir = new File(appContext.getCacheDir(), LEGACY_TABLE_DIR);
			File zip = LegacyResourceSwap.writeResourceApk(in, dir, generation);
			Resources appResources = appContext.getResources();
			LegacyResourceSwap.addAssetPath(appResources.getAssets(), zip.getAbsolutePath());
			legacyTableZip = zip;
			LegacyResourceSwap.flushCaches(appResources);
		} finally {
			try {
				in.close();
			} catch (IOException ignored) {
				// Nothing useful to do with a failed close.
			}
		}
	}

	/**
	 * API 30+ swap: replace the table provider inside the process-wide loader, creating the loader on first use.
	 *
	 * TargetApi because lint cannot see the SDK guard: the strategy is RESOURCES_LOADER only when SDK >= 30.
	 *
	 * @param tableFd
	 *            the relinked resource apk; loadFromApk dups it, so this method closes ours either way
	 * @param appContext
	 *            application context, whose Resources the loader is attached to on first use
	 * @param onFailure
	 *            told when the posted swap fails or is refused; null when the caller has nothing to do about it
	 * @throws IOException
	 *             when the apk cannot be loaded as a provider; the previous provider stays live and attached
	 */
	@TargetApi(30)
	private void applyTableWithLoader(ParcelFileDescriptor tableFd, final Context appContext,
			SwapFailure onFailure) throws IOException {
		try {
			final ResourcesProvider next = ResourcesProvider.loadFromApk(tableFd, null);
			boolean willRun = swapProvidersOnMain(new Runnable() {

				@Override
				public void run() {
					synchronized (ResourceStore.this) {
						ResourcesProvider previous = provider;
						provider = next;
						try {
							installProviders();
						} catch (RuntimeException | Error error) {
							// Un-commit. The loader still holds the previous set, so the field has to as
							// well - leaving the rejected provider there makes the next deploy offer it
							// again, and dropping `previous` on the floor leaks a provider that is still
							// installed. Closing `next` is safe: it was never installed.
							provider = previous;
							Streams.closeQuietly(next);
							throw error;
						}
						attachAppResources(appContext);
						Streams.closeQuietly(previous);
					}
				}
			}, onFailure);
			if (!willRun) {
				// The swap will never run, so nothing else will ever close this provider.
				Streams.closeQuietly(next);
			}
		} finally {
			// loadFromApk dups the fd internally; ours must be closed either way.
			Streams.closeQuietly(tableFd);
		}
	}

	/**
	 * Adds the loader to the application Resources, once.
	 *
	 * Activity Resources get the loader through {@link #attachTo}, but nothing ever creates the application's, so without this a Service, a ContentProvider or a notification builder keeps resolving the baseline table while the activity on screen resolves the new one - the two disagree about the same resource id. The API 28/29 path already mounts onto the application Resources explicitly; this makes the API 30+ path symmetric.
	 *
	 * Once is enough: the loader is long-lived and every later provider swap propagates to each Resources already attached to it. Callers hold the monitor and have just installed the providers, so the loader exists.
	 *
	 * @param appContext
	 *            application context; null is ignored, which only costs the symmetry this restores
	 */
	@TargetApi(30)
	private void attachAppResources(Context appContext) {
		if (attachedAppResources || appContext == null) {
			return;
		}
		attachedAppResources = true;
		attachLoaderTo(appContext.getResources());
	}

	/**
	 * Adds the process-wide loader to one Resources object. TargetApi: reached only on SDK >= 30.
	 *
	 * @param resources
	 *            the Resources to attach to; attaching again, or an unusual implementation, is logged and ignored
	 */
	@TargetApi(30)
	private void attachLoaderTo(Resources resources) {
		ResourcesLoader target = loader;
		if (target == null) {
			return;
		}
		try {
			resources.addLoaders(target);
		} catch (Throwable error) {
			// Already attached, or an unusual Resources implementation. Not worth
			// crashing over.
			RuntimeLog.d("attachTo skipped", error);
		}
	}

	/**
	 * Installs the current provider set into the loader, creating the loader on first use. Callers hold the monitor and close any provider they replaced.
	 */
	@TargetApi(30)
	private void installProviders() {
		ResourcesLoader target = loader;
		if (target == null) {
			target = new ResourcesLoader();
			loader = target;
		}
		List<ResourcesProvider> providers = new ArrayList<ResourcesProvider>(2);
		if (provider != null) {
			providers.add(provider);
		}
		if (assetsProvider != null) {
			providers.add(assetsProvider);
		}
		target.setProviders(providers);
	}

	/**
	 * API 30+: rebuild the assets half of the loader over the merged override dir.
	 *
	 * A fresh provider pair per deploy, rather than one long-lived one, so the loader's setProviders notifies every attached Resources that the underlying assets changed; the recreate then reads the new content. The table provider is untouched - the two halves change independently, since a deploy carries only what changed.
	 *
	 * @param dir
	 *            the merged override dir laid out as an APK root (assets under {@code assets/})
	 * @param appContext
	 *            application context, whose Resources the loader is attached to on first use
	 * @param onFailure
	 *            told when the posted swap fails or is refused; null when the caller has nothing to do about it
	 * @throws IOException
	 *             when the provider cannot be created; the previous one stays live and attached
	 */
	@TargetApi(30)
	private void refreshAssetsProvider(File dir, final Context appContext, SwapFailure onFailure)
			throws IOException {
		final DirectoryAssetsProvider nextDir = new DirectoryAssetsProvider(dir);
		final ResourcesProvider next = ResourcesProvider.empty(nextDir);
		boolean willRun = swapProvidersOnMain(new Runnable() {

			@Override
			public void run() {
				synchronized (ResourceStore.this) {
					ResourcesProvider previous = assetsProvider;
					DirectoryAssetsProvider previousDir = assetsDirProvider;
					assetsProvider = next;
					assetsDirProvider = nextDir;
					try {
						installProviders();
					} catch (RuntimeException | Error error) {
						// Same un-commit as applyTableWithLoader: restore the fields the loader still
						// reflects, close the pair that never got installed, and let the failure out.
						assetsProvider = previous;
						assetsDirProvider = previousDir;
						Streams.closeQuietly(next);
						Streams.closeQuietly(nextDir);
						throw error;
					}
					attachAppResources(appContext);
					Streams.closeQuietly(previous);
					Streams.closeQuietly(previousDir);
				}
			}
		}, onFailure);
		if (!willRun) {
			// The swap will never run, so nothing else will ever close this pair.
			Streams.closeQuietly(next);
			Streams.closeQuietly(nextDir);
		}
	}

	/**
	 * Runs a provider swap on the main thread, inline when already there.
	 *
	 * The swap must not run on the binder thread the deploy arrives on: setProviders rebuilds every attached Resources in place and the swap then closes the replaced provider's ApkAssets, either of which can race an inflation already in progress on the main thread - a lookup straddling the swap mixes old and new values, or touches a just-closed provider. Serializing with the main thread removes both races, and Looper FIFO keeps a posted swap ahead of the recreate the deploy posts right after it.
	 *
	 * Inline on the main thread, not posted, because the boot restore path runs during the first activity's creation and its swap must land before anything inflates.
	 *
	 * A swap failure is never thrown from here: on the posted path no caller is left to catch it, and the previous provider set stays live either way. The result is still deliberately NOT returned synchronously to the deploy chain - that would make a deploy arriving on a binder thread block on a main-thread round trip in the hot reload path, which is the very thing posting the swap exists to avoid. It travels back through {@code onFailure} instead, so the deploy that queued the swap can fail rather than ack a swap that did not land. Each swap un-commits its own fields on failure, so what stays live is a consistent previous generation.
	 *
	 * @param swap
	 *            the field swap + setProviders + close of the replaced provider, taking the store's monitor itself
	 * @param onFailure
	 *            told when the swap threw or was never accepted; may be null
	 * @return true when the swap has run or is queued to run, false when the main looper refused it and the caller still owns the providers it created
	 */
	private boolean swapProvidersOnMain(final Runnable swap, final SwapFailure onFailure) {
		Runnable guarded = new Runnable() {

			@Override
			public void run() {
				try {
					swap.run();
				} catch (Throwable error) {
					RuntimeLog.e("resource provider swap failed; previous set stays live", error);
					reportSwapFailure(onFailure, error);
				}
			}
		};
		Looper main = Looper.getMainLooper();
		if (Looper.myLooper() == main) {
			guarded.run();
			return true;
		}
		if (new Handler(main).post(guarded)) {
			return true;
		}
		// The main looper is quitting, so the runnable will never run. Without this the
		// deploy would ack a swap that never happened and the app would render the old
		// table under the new generation's label.
		IllegalStateException error = new IllegalStateException(
				"main looper refused the resource provider swap");
		RuntimeLog.e("resource provider swap was not queued; previous set stays live", error);
		reportSwapFailure(onFailure, error);
		return false;
	}

	/**
	 * Told when a posted provider swap did not land, so the deploy that queued it can fail rather than ack.
	 *
	 * The swap runs after the method that queued it has returned, so this is the only way the failure reaches the deploy chain.
	 */
	interface SwapFailure {

		/**
		 * @param error
		 *            the swap failure, already logged by the store
		 */
		void onSwapFailed(Throwable error);
	}
}
