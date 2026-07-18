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
import java.util.Collections;

/**
 * Owner of the payload's resource + asset overrides.
 *
 * Resources are applied per {@link ResourceSwapStrategy} (plan B5):
 *
 * API 30+ ({@code RESOURCES_LOADER}): one long-lived {@link ResourcesLoader} whose provider is swapped per payload. One loader (not one per generation) because a loader already attached to a Resources object propagates provider changes - every activity attaches the loader once at creation and then follows every future generation for free.
 *
 * API 28/29 ({@code LEGACY_ASSET_PATH}): the degraded {@link LegacyResourceSwap} shim - the table is wrapped into a zip, addAssetPath'd into the application AssetManager, and the per-deploy activity recreate re-reads values from it. New Resources objects get the current table zip attached via {@link #attachTo} (idempotent).
 *
 * Below 28 ({@code UNSUPPORTED}): resource payloads are ignored (logged once); unreachable in practice since the CoGo host needs API 28+ on the same device.
 *
 * Assets: no in-memory API exists, so the changed-assets zip is extracted to an app-private cache dir and exposed via {@link #overrideAsset}. v1 limitation, documented: this is a LOOKUP path for code that asks the runtime, not a transparent AssetManager override - code reading assets directly through AssetManager still sees the baked-in APK assets until the next setup build.
 */
final class ResourceStore {

	static final ResourceStore INSTANCE = new ResourceStore();

	private static final String LEGACY_TABLE_DIR = "quickbuild-res";

	private final ResourceSwapStrategy strategy;

	private volatile ResourcesLoader loader;
	private volatile ResourcesProvider provider;
	private volatile File legacyTableZip;
	private volatile File assetOverrideDir;
	private boolean warnedNoResourceReload;

	ResourceStore(ResourceSwapStrategy strategy) {
		this.strategy = strategy;
	}

	private ResourceStore() {
		this(ResourceSwapStrategy.forSdk(Build.VERSION.SDK_INT));
	}

	/**
	 * Extracts a changed-assets zip payload under {@code <cacheRoot>/quickbuild-assets/gen-<generation>}. Closes the fd; throws on failure so the deploy path can roll the payload back.
	 */
	void applyAssets(ParcelFileDescriptor assetsFd, long generation, File cacheRoot) throws IOException {
		File destDir = new File(new File(cacheRoot, "quickbuild-assets"), "gen-" + generation);
		InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(assetsFd);
		try {
			int extracted = AssetExtractor.extract(in, destDir);
			assetOverrideDir = destDir;
			RuntimeLog.i("extracted " + extracted + " changed asset(s) for gen " + generation);
		} finally {
			try {
				in.close();
			} catch (IOException ignored) {
				// Nothing useful to do with a failed close.
			}
		}
	}

	/**
	 * Swaps in a new resource table (an fd to a resources.arsc). Closes the fd; throws on failure so the deploy path can roll the whole payload back.
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
	 * Attaches the current resource override to {@code resources}: the loader on API 30+, the current table zip on 28/29 (both idempotent per Resources/AssetManager). No-op until the first resource payload arrives. Best-effort by contract - a failed attach is logged, never fatal.
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
	 * The extracted override for asset {@code path} (e.g. "data/levels.json"), or null when no deploy changed it. See the class doc for the v1 best-effort limitation.
	 */
	File overrideAsset(String path) {
		File dir = assetOverrideDir;
		if (dir == null || path == null) {
			return null;
		}
		File candidate = new File(dir, path);
		// Same containment rule as AssetExtractor: a "../" lookup must not resolve
		// outside the override dir, even though the caller is the app's own asset name.
		try {
			if (!candidate.getCanonicalPath().startsWith(dir.getCanonicalPath() + File.separator)) {
				return null;
			}
		} catch (IOException error) {
			return null;
		}
		return candidate.isFile() ? candidate : null;
	}

	/**
	 * API 28/29 path: zip-wrap the table, addAssetPath it into the application AssetManager, flush caches. The deploy's activity recreate then re-resolves from the new table. Any failure throws, so the deploy rolls back and the host is told - a resource change never silently leaves the app on stale resources.
	 */
	private void applyTableLegacy(ParcelFileDescriptor tableFd, long generation,
			Context appContext) throws IOException {
		InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(tableFd);
		try {
			File dir = new File(appContext.getCacheDir(), LEGACY_TABLE_DIR);
			File zip = LegacyResourceSwap.writeTableZip(in, dir, generation);
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
	 * API 30+ path: swap the provider inside the process-wide loader. TargetApi because lint cannot see the SDK guard (strategy is RESOURCES_LOADER only when SDK >= 30).
	 */
	@TargetApi(30)
	private void applyTableWithLoader(ParcelFileDescriptor tableFd) throws IOException {
		try {
			ResourcesProvider next = ResourcesProvider.loadFromTable(tableFd, null);
			synchronized (this) {
				ResourcesLoader target = loader;
				if (target == null) {
					target = new ResourcesLoader();
					loader = target;
				}
				target.setProviders(Collections.singletonList(next));
				ResourcesProvider previous = provider;
				provider = next;
				Streams.closeQuietly(previous);
			}
		} finally {
			// loadFromTable dups the fd internally; ours must be closed either way.
			Streams.closeQuietly(tableFd);
		}
	}

	/** TargetApi: reached only on the RESOURCES_LOADER strategy, i.e. SDK >= 30. */
	@TargetApi(30)
	private void attachLoaderTo(Resources resources) {
		ResourcesLoader target = loader;
		if (target == null) {
			return;
		}
		try {
			resources.addLoaders(target);
		} catch (Throwable error) {
			// Already-attached (or an exotic Resources impl) - never worth crashing over.
			RuntimeLog.d("attachTo skipped: " + error);
		}
	}
}
