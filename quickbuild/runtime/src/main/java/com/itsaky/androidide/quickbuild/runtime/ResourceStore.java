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
 * Owns the payload's resource and asset overrides.
 *
 * The payload fd is always the whole relinked resource apk from {@code Aapt2Link}, never a bare table: a bare table cannot back a file-typed resource such as a layout or a drawable XML.
 *
 * The swap mechanism follows {@link ResourceSwapStrategy}. On API 30+ one long-lived {@link ResourcesLoader} has its provider swapped per payload, loaded with {@link ResourcesProvider#loadFromApk} because {@code loadFromTable} does not serve file-based resources; one loader suffices because an attached loader propagates provider changes, so an activity attaches once and follows every later generation. On API 28/29 the {@link LegacyResourceSwap} shim writes the apk to disk and addAssetPath's it. Below 28 resource payloads are ignored, which is unreachable in practice since the CoGo host needs API 28+ on the same device.
 *
 * Assets have no in-memory API, so the changed-assets zip is extracted to a cache dir and exposed through {@link #overrideAsset}. That is a lookup path for code that asks the runtime; code reading through AssetManager directly still sees the baked-in APK assets until the next proxy app build.
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
	 * Extracts a changed-assets zip into a gen-numbered dir under {@code cacheRoot} and makes it the current override.
	 *
	 * Closes the fd, and throws on failure so the deploy path can roll the payload back.
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
	 * Swaps in a new resource table, using whichever strategy this API level supports.
	 *
	 * Closes the fd, and throws on failure so the deploy path can roll the whole payload back.
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
	 * Looks up the extracted override for an asset path such as "data/levels.json".
	 *
	 * @return null when no deploy changed that asset. See the class doc for what this path does and does not cover.
	 */
	File overrideAsset(String path) {
		File dir = assetOverrideDir;
		if (dir == null || path == null) {
			return null;
		}
		File candidate = new File(dir, path);
		// Same containment rule as AssetExtractor: a "../" lookup must not resolve
		// outside the override dir.
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
	 * API 28/29 swap: write the apk to disk, addAssetPath it into the application AssetManager, flush caches.
	 *
	 * The deploy's activity recreate then re-resolves from the new table. Any failure throws, so the deploy rolls back and CoGo hears about it rather than the app sitting on stale resources.
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
	 * API 30+ swap: replace the provider inside the process-wide loader, creating the loader on first use.
	 *
	 * TargetApi because lint cannot see the SDK guard: the strategy is RESOURCES_LOADER only when SDK >= 30.
	 */
	@TargetApi(30)
	private void applyTableWithLoader(ParcelFileDescriptor tableFd) throws IOException {
		try {
			ResourcesProvider next = ResourcesProvider.loadFromApk(tableFd, null);
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
			// loadFromApk dups the fd internally; ours must be closed either way.
			Streams.closeQuietly(tableFd);
		}
	}

	/** Adds the process-wide loader to one Resources object. TargetApi: reached only on SDK >= 30. */
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
}
