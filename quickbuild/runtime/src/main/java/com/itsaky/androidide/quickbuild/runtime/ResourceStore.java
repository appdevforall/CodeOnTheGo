package com.itsaky.androidide.quickbuild.runtime;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.loader.ResourcesLoader;
import android.content.res.loader.ResourcesProvider;
import android.os.Build;
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
	 * @param cacheRoot
	 *            the app's cache directory, the parent of the cumulative dir
	 * @throws IOException
	 *             on a read, extraction, path-traversal or provider failure; the previous override stays live
	 */
	void applyAssets(ParcelFileDescriptor assetsFd, String baselineFingerprint, File cacheRoot)
			throws IOException {
		File assetsRoot = new File(cacheRoot, ASSETS_ROOT_DIR);
		InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(assetsFd);
		try {
			int extracted = AssetExtractor.extractCumulative(in, assetsRoot, baselineFingerprint);
			if (strategy == ResourceSwapStrategy.RESOURCES_LOADER) {
				refreshAssetsProvider(AssetExtractor.currentDir(assetsRoot));
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
	 *            application context, used only by the API 28/29 path for its cache dir and Resources
	 * @throws IOException
	 *             when the swap fails; an unsupported SDK is not a failure, it warns once and drops the payload
	 */
	void applyTable(ParcelFileDescriptor tableFd, long generation, Context appContext)
			throws IOException {
		switch (strategy) {
		case RESOURCES_LOADER:
			applyTableWithLoader(tableFd);
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
				RuntimeLog.d("legacy attachTo skipped: " + error);
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
	 * @throws IOException
	 *             when the apk cannot be loaded as a provider; the previous provider stays live and attached
	 */
	@TargetApi(30)
	private void applyTableWithLoader(ParcelFileDescriptor tableFd) throws IOException {
		try {
			ResourcesProvider next = ResourcesProvider.loadFromApk(tableFd, null);
			synchronized (this) {
				ResourcesProvider previous = provider;
				provider = next;
				installProviders();
				Streams.closeQuietly(previous);
			}
		} finally {
			// loadFromApk dups the fd internally; ours must be closed either way.
			Streams.closeQuietly(tableFd);
		}
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
			RuntimeLog.d("attachTo skipped: " + error);
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
	 * @throws IOException
	 *             when the provider cannot be created; the previous one stays live and attached
	 */
	@TargetApi(30)
	private void refreshAssetsProvider(File dir) throws IOException {
		DirectoryAssetsProvider nextDir = new DirectoryAssetsProvider(dir);
		ResourcesProvider next = ResourcesProvider.empty(nextDir);
		synchronized (this) {
			ResourcesProvider previous = assetsProvider;
			DirectoryAssetsProvider previousDir = assetsDirProvider;
			assetsProvider = next;
			assetsDirProvider = nextDir;
			installProviders();
			Streams.closeQuietly(previous);
			Streams.closeQuietly(previousDir);
		}
	}
}
