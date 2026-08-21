package com.itsaky.androidide.quickbuild.runtime;

import android.content.res.AssetManager;
import android.content.res.Resources;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;

/**
 * Applies a resource payload on API 28/29, where ResourcesLoader does not exist.
 *
 * Persist the relinked apk, append it to the live AssetManager through the hidden addAssetPath, then flush the Resources caches so the deploy's activity recreate resolves against the new table. The new package shares the old package id and resource ids, and the last-added package wins the lookup.
 *
 * Degraded by design relative to the API 30+ loader path: an added path can never be removed, so each generation appends one more package until the process restarts, and a Resources with its own AssetManager only picks the table up when {@link ResourceStore#attachTo} reaches it. {@link #deleteStaleApks} sweeps the directory at startup instead, since nothing a previous process mounted survives its death.
 */
final class LegacyResourceSwap {

	/**
	 * Cache subdirectory the relinked apks live in.
	 *
	 * Must match {@code ResourceStore.LEGACY_TABLE_DIR}, which is what actually writes them; {@code LegacyResourceSwapCacheDirTest} pins the two together.
	 */
	static final String TABLE_DIR = "quickbuild-res";

	/** Prefix of a generation-stamped relinked apk, as written by {@link #writeResourceApk}. */
	private static final String APK_PREFIX = "gen-";

	/** Suffix of a generation-stamped relinked apk. */
	private static final String APK_SUFFIX = ".zip";

	/**
	 * Mounts the resource apk at {@code path} on the live AssetManager, via the hidden addAssetPath.
	 *
	 * Idempotent: the framework returns the existing cookie for an already-added path. Throws on any failure so the deploy path can roll the payload back, because a resource payload must never be silently dropped.
	 *
	 * @param assets
	 *            the process's live AssetManager, normally {@code Resources#getAssets()}
	 * @param path
	 *            absolute path of the apk written by {@link #writeResourceApk}; it must stay on disk for the life of the process, since a mounted path can never be removed
	 * @throws IOException
	 *             when the hidden method is missing, throws, or returns cookie 0, which is the framework's way of rejecting the path
	 */
	static void addAssetPath(AssetManager assets, String path) throws IOException {
		try {
			Method method = AssetManager.class.getDeclaredMethod("addAssetPath", String.class);
			method.setAccessible(true);
			Object cookie = method.invoke(assets, path);
			if (!(cookie instanceof Integer) || (Integer) cookie == 0) {
				throw new IOException("addAssetPath rejected " + path + " (cookie=" + cookie + ")");
			}
		} catch (IOException error) {
			throw error;
		} catch (Throwable error) {
			throw new IOException("addAssetPath failed for " + path, error);
		}
	}

	/**
	 * Deletes every relinked apk in {@code dir}, for a process that has mounted none of them yet.
	 *
	 * Safe only before the first swap of this process: a mounted path can never be unmounted, so deleting one this process is serving would leave the AssetManager pointing at nothing. Best-effort - a file it cannot delete costs cache space, never correctness.
	 *
	 * @param dir
	 *            the cache subdirectory named by {@link #TABLE_DIR}; a missing one is a no-op
	 * @return how many files were deleted, for the log line and the tests
	 */
	static int deleteStaleApks(File dir) {
		File[] entries = dir.listFiles();
		if (entries == null) {
			return 0;
		}
		int deleted = 0;
		for (File entry : entries) {
			String name = entry.getName();
			if (!entry.isFile() || !name.startsWith(APK_PREFIX) || !name.endsWith(APK_SUFFIX)) {
				continue;
			}
			if (entry.delete()) {
				deleted++;
			} else {
				RuntimeLog.w("could not delete stale resource apk " + entry);
			}
		}
		return deleted;
	}

	/**
	 * Drops the cached drawables, color state lists and typed values so lookups cannot serve values from the old table.
	 *
	 * updateConfiguration with the current config is the only public way to force that.
	 *
	 * @param resources
	 *            the Resources whose caches to drop; its configuration is re-applied unchanged, so this is a flush and not a configuration change
	 */
	@SuppressWarnings("deprecation")
	static void flushCaches(Resources resources) {
		resources.updateConfiguration(resources.getConfiguration(), resources.getDisplayMetrics());
	}

	/**
	 * Copies the relinked resource apk stream to a gen-numbered zip under {@code dir}.
	 *
	 * The stream from {@code Aapt2Link} is already a valid apk/zip, so this is a plain byte copy. Do not re-wrap it: a bare arsc in a synthetic single-entry zip leaves file-backed resources such as layouts and drawable XMLs with no zip entry to resolve against, and they crash on first access.
	 *
	 * @param apk
	 *            the relinked apk bytes; read to exhaustion but never closed, since the caller owns the stream
	 * @param dir
	 *            app-private directory to write into, created when missing
	 * @param generation
	 *            the payload generation, which names the file and so keeps every mounted path distinct
	 * @return the written file, whose path is what {@link #addAssetPath} mounts
	 * @throws IOException
	 *             when {@code dir} cannot be created, the stream exceeds the payload cap, or the write fails
	 */
	static File writeResourceApk(InputStream apk, File dir, long generation) throws IOException {
		byte[] bytes = Streams.readFully(apk);
		if (!dir.isDirectory() && !dir.mkdirs()) {
			throw new IOException("cannot create " + dir);
		}
		File zip = new File(dir, APK_PREFIX + generation + APK_SUFFIX);
		FileOutputStream out = new FileOutputStream(zip);
		try {
			out.write(bytes);
		} finally {
			out.close();
		}
		return zip;
	}

	private LegacyResourceSwap() {}
}
