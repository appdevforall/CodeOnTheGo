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
	 * Tells the caller's listener the swap is live, without letting the listener's own failure escape.
	 *
	 * Runs on the main thread inside the swap's guard, like {@link #reportSwapFailure}, so a throw here would turn a landed swap into an unrelated crash.
	 *
	 * @param onOutcome
	 *            the caller's listener; null is a no-op
	 */
	private static void reportSwapCommitted(SwapOutcome onOutcome) {
		if (onOutcome == null) {
			return;
		}
		try {
			onOutcome.onSwapCommitted();
		} catch (Throwable reportFailure) {
			RuntimeLog.e("resource swap commit listener threw", reportFailure);
		}
	}

	/**
	 * Hands a swap failure to the caller's listener without letting the listener's own failure escape.
	 *
	 * This runs on the main thread inside the swap's guard, so a throw here would replace a resource failure with an unrelated crash.
	 *
	 * @param onOutcome
	 *            the caller's listener; null is a no-op
	 * @param error
	 *            the failure to report, already logged
	 */
	private static void reportSwapFailure(SwapOutcome onOutcome, Throwable error) {
		if (onOutcome == null) {
			return;
		}
		try {
			onOutcome.onSwapFailed(error);
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
	 * Newest generation whose provider swap has committed, or -1 before the first.
	 *
	 * Two deploys arrive on two binder threads, so the swaps they post are not ordered by generation. Without this an overtaken deploy's swap can land last and put its older table back under the newer generation's label. Written and read under the monitor, inside the swap itself.
	 */
	private long swappedGeneration = -1;

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
	 * @param generation
	 *            the payload generation, which orders this swap against the others; an overtaken one is dropped rather than installed
	 * @param baselineFingerprint
	 *            the running baseline's fingerprint, which keys the cumulative dir
	 * @param appContext
	 *            application context, for the cache dir the cumulative override lives under and the Resources the loader attaches to
	 * @param onOutcome
	 *            told when the posted provider swap fails, since that lands after this method returns; null when the caller has nothing to do about it
	 * @throws IOException
	 *             on a read, extraction, path-traversal or provider failure; the previous override stays live
	 */
	void applyAssets(ParcelFileDescriptor assetsFd, long generation, String baselineFingerprint,
			Context appContext, SwapOutcome onOutcome) throws IOException {
		File assetsRoot = new File(appContext.getCacheDir(), ASSETS_ROOT_DIR);
		InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(assetsFd);
		try {
			int extracted = AssetExtractor.extractCumulative(in, assetsRoot, baselineFingerprint);
			if (strategy == ResourceSwapStrategy.RESOURCES_LOADER) {
				refreshAssetsProvider(
						AssetExtractor.currentDir(assetsRoot), generation, appContext, onOutcome);
			} else {
				// The merge on disk is the whole swap below API 30; there is no provider to
				// queue, so the outcome is settled here rather than by a callback that never
				// comes.
				reportSwapCommitted(onOutcome);
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
	 *            the payload generation: the API 28/29 path names its file after it, and the API 30+ path orders this swap against the others
	 * @param appContext
	 *            application context, for the Resources the loader attaches to and, on API 28/29, the cache dir and the Resources to mount onto
	 * @param onOutcome
	 *            told when the posted provider swap fails, since that lands after this method returns; null when the caller has nothing to do about it
	 * @throws IOException
	 *             when the swap fails; an unsupported SDK is not a failure, it warns once and drops the payload
	 */
	void applyTable(ParcelFileDescriptor tableFd, long generation, Context appContext,
			SwapOutcome onOutcome) throws IOException {
		switch (strategy) {
		case RESOURCES_LOADER:
			applyTableWithLoader(tableFd, generation, appContext, onOutcome);
			return;
		case LEGACY_ASSET_PATH:
			applyTableLegacy(tableFd, generation, appContext, onOutcome);
			return;
		default:
			Streams.closeQuietly(tableFd);
			synchronized (this) {
				if (!warnedNoResourceReload) {
					warnedNoResourceReload = true;
					RuntimeLog.w("resource payloads need API 28+; ignoring");
				}
			}
			// Nothing was queued, so nothing reports later; a deploy must not be left
			// waiting on a swap this SDK level never makes.
			reportSwapCommitted(onOutcome);
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
	 * API 28/29 swap: write the apk to disk, then addAssetPath it into the application AssetManager and flush caches on the main thread.
	 *
	 * The mount is posted through {@link #swapProvidersOnMain} for the reason its own KDoc gives for the loader path: addAssetPath re-tables the live AssetManager and flushCaches drops the drawable and typed-value caches, and either can race an inflation already in progress - a lookup straddling the swap mixes old and new values. Running it on the arriving binder thread left that race open on exactly the devices this path exists for, since CoGo's classifier routes resource edits with no SDK gate.
	 *
	 * The mount is also ordered against the other swaps by {@code swappedGeneration}, like both siblings: two deploys arrive on two binder threads, so an overtaken one could otherwise addAssetPath last and leave the screen resolving an older table than {@code PayloadStore.generation()} reports.
	 *
	 * The write stays off the main thread; only the mount is posted. A throw rolls back the dex payload only, not this path's own on-disk apk or a mount that already committed.
	 *
	 * @param tableFd
	 *            the relinked resource apk; always closed, success or failure
	 * @param generation
	 *            the payload generation, which names the file on disk and orders this swap against the others; an overtaken one is dropped rather than mounted
	 * @param appContext
	 *            application context, for the cache dir and the Resources to mount onto
	 * @param onOutcome
	 *            told when the posted mount fails or is refused, since that lands after this method returns; null when the caller has nothing to do about it
	 * @throws IOException
	 *             when the write fails; the previous table stays live. A mount failure arrives through {@code onOutcome} instead.
	 */
	private void applyTableLegacy(ParcelFileDescriptor tableFd, final long generation,
			final Context appContext, SwapOutcome onOutcome) throws IOException {
		InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(tableFd);
		final File zip;
		try {
			File dir = new File(appContext.getCacheDir(), LEGACY_TABLE_DIR);
			zip = LegacyResourceSwap.writeResourceApk(in, dir, generation);
		} finally {
			try {
				in.close();
			} catch (IOException ignored) {
				// Nothing useful to do with a failed close.
			}
		}
		// The return is not tested: a refused post is already reported through onOutcome,
		// and unlike the loader paths there is no provider left in this method's hands to
		// close - the apk on disk is swept by deleteStaleApks on the next process start.
		swapProvidersOnMain(new Runnable() {

			@Override
			public void run() {
				synchronized (ResourceStore.this) {
					if (generation < swappedGeneration) {
						// Overtaken, same as both loader swaps. Mounting now would make the last
						// addAssetPath win the lookup with the older table, under the newer
						// generation's label, and hand that apk to every later activity.
						RuntimeLog.w("dropping overtaken legacy table swap for gen " + generation
								+ "; gen " + swappedGeneration + " already committed");
						return;
					}
					Resources appResources = appContext.getResources();
					try {
						LegacyResourceSwap.addAssetPath(appResources.getAssets(),
								zip.getAbsolutePath());
					} catch (IOException error) {
						// A Runnable cannot carry a checked exception out, and swallowing it would
						// ack a table that never mounted. swapProvidersOnMain catches Throwable and
						// routes it to onOutcome, so wrap rather than drop.
						throw new IllegalStateException(
								"legacy table swap failed for gen " + generation, error);
					}
					// Recorded only after the mount took, as in both loader swaps.
					legacyTableZip = zip;
					swappedGeneration = generation;
					LegacyResourceSwap.flushCaches(appResources);
				}
			}
		}, onOutcome);
	}

	/**
	 * API 30+ swap: replace the table provider inside the process-wide loader, creating the loader on first use.
	 *
	 * TargetApi because lint cannot see the SDK guard: the strategy is RESOURCES_LOADER only when SDK >= 30.
	 *
	 * @param tableFd
	 *            the relinked resource apk; loadFromApk dups it, so this method closes ours either way
	 * @param generation
	 *            the payload generation, which orders this swap against the others; an overtaken one is dropped rather than installed
	 * @param appContext
	 *            application context, whose Resources the loader is attached to on first use
	 * @param onOutcome
	 *            told when the posted swap fails or is refused; null when the caller has nothing to do about it
	 * @throws IOException
	 *             when the apk cannot be loaded as a provider; the previous provider stays live and attached
	 */
	@TargetApi(30)
	private void applyTableWithLoader(ParcelFileDescriptor tableFd, final long generation,
			final Context appContext, SwapOutcome onOutcome) throws IOException {
		try {
			final ResourcesProvider next = ResourcesProvider.loadFromApk(tableFd, null);
			boolean willRun = swapProvidersOnMain(new Runnable() {

				@Override
				public void run() {
					synchronized (ResourceStore.this) {
						if (generation < swappedGeneration) {
							// Overtaken. Two deploys arrive on two binder threads, so the posts are not
							// ordered by generation, and installing this one would put the older table
							// back under the newer generation's label.
							RuntimeLog.w("dropping overtaken table swap for gen " + generation + "; gen "
									+ swappedGeneration + " already committed");
							Streams.closeQuietly(next);
							return;
						}
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
						// Recorded only after the install took: a rejected swap leaves the previous set
						// live, so it must not block the next deploy from installing over it.
						swappedGeneration = generation;
						attachAppResources(appContext);
						Streams.closeQuietly(previous);
					}
				}
			}, onOutcome);
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
		// Latched only on success: attachLoaderTo swallows its failures, so setting the
		// flag first would record an attach that never happened and no later deploy
		// would retry it. Callers hold the monitor, so the retry is bounded by deploys.
		attachedAppResources = attachLoaderTo(appContext.getResources());
	}

	/**
	 * Adds the process-wide loader to one Resources object. TargetApi: reached only on SDK >= 30.
	 *
	 * @param resources
	 *            the Resources to attach to; attaching again, or an unusual implementation, is logged and ignored
	 * @return whether the loader was attached; false when there is no loader yet or addLoaders threw (logged, not rethrown), so the caller can retry on a later deploy instead of recording a failed attach as done
	 */
	@TargetApi(30)
	private boolean attachLoaderTo(Resources resources) {
		ResourcesLoader target = loader;
		if (target == null) {
			return false;
		}
		try {
			resources.addLoaders(target);
			return true;
		} catch (Throwable error) {
			// Already attached, or an unusual Resources implementation. Not worth
			// crashing over.
			RuntimeLog.d("attachTo skipped", error);
			return false;
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
	 * @param generation
	 *            the payload generation, which orders this swap against the others; an overtaken one is dropped rather than installed
	 * @param appContext
	 *            application context, whose Resources the loader is attached to on first use
	 * @param onOutcome
	 *            told when the posted swap fails or is refused; null when the caller has nothing to do about it
	 * @throws IOException
	 *             when the provider cannot be created; the previous one stays live and attached
	 */
	@TargetApi(30)
	private void refreshAssetsProvider(File dir, final long generation, final Context appContext,
			SwapOutcome onOutcome) throws IOException {
		final DirectoryAssetsProvider nextDir = new DirectoryAssetsProvider(dir);
		final ResourcesProvider next = ResourcesProvider.empty(nextDir);
		boolean willRun = swapProvidersOnMain(new Runnable() {

			@Override
			public void run() {
				synchronized (ResourceStore.this) {
					if (generation < swappedGeneration) {
						// Overtaken, same as the table swap: a newer generation's providers are already
						// installed and this pair would replace them with the older override dir.
						RuntimeLog.w("dropping overtaken assets swap for gen " + generation + "; gen "
								+ swappedGeneration + " already committed");
						Streams.closeQuietly(next);
						Streams.closeQuietly(nextDir);
						return;
					}
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
					// Recorded only after the install took, as in the table swap.
					swappedGeneration = generation;
					attachAppResources(appContext);
					Streams.closeQuietly(previous);
					Streams.closeQuietly(previousDir);
				}
			}
		}, onOutcome);
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
	 * A swap failure is never thrown from here: on the posted path no caller is left to catch it, and the previous provider set stays live either way. The result is still deliberately NOT returned synchronously to the deploy chain - that would make a deploy arriving on a binder thread block on a main-thread round trip in the hot reload path, which is the very thing posting the swap exists to avoid. It travels back through {@code onOutcome} instead, so the deploy that queued the swap can fail rather than ack a swap that did not land. Each swap un-commits its own fields on failure, so what stays live is a consistent previous generation.
	 *
	 * @param swap
	 *            the field swap + setProviders + close of the replaced provider, taking the store's monitor itself
	 * @param onOutcome
	 *            told when the swap threw or was never accepted; may be null
	 * @return true when the swap has run or is queued to run, false when the main looper refused it and the caller still owns the providers it created
	 */
	private boolean swapProvidersOnMain(final Runnable swap, final SwapOutcome onOutcome) {
		Runnable guarded = new Runnable() {

			@Override
			public void run() {
				try {
					swap.run();
				} catch (Throwable error) {
					RuntimeLog.e("resource provider swap failed; previous set stays live", error);
					reportSwapFailure(onOutcome, error);
					return;
				}
				// A swap dropped as overtaken reports committed too: it returned normally, and
				// the generation that overtook it owns the screen and its own ack, so calling
				// this one failed would report a crash for a deploy nothing is wrong with.
				reportSwapCommitted(onOutcome);
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
		reportSwapFailure(onOutcome, error);
		return false;
	}

	/**
	 * Told how a posted provider swap ended, so the deploy that queued it can ack or fail on the swap itself rather than on having queued it.
	 *
	 * The swap runs after the method that queued it has returned, so this is the only way either answer reaches the deploy chain.
	 *
	 * Exactly one of the two fires per {@code applyTable} or {@code applyAssets} call that returns normally - including a call that queues nothing because this SDK level has no swap to make, since a deploy waiting on it would otherwise wait forever. A call that throws reports neither: the caller has the exception instead.
	 */
	interface SwapOutcome {

		/** The swap is live, or there was none to make. A backgrounded deploy's ack hangs off this. */
		void onSwapCommitted();

		/**
		 * @param error
		 *            the swap failure, already logged by the store
		 */
		void onSwapFailed(Throwable error);
	}
}
