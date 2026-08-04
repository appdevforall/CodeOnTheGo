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
 * Three steps in order: persist the relinked apk ({@link #writeResourceApk}), append it to the
 * live AssetManager through the hidden addAssetPath ({@link #addAssetPath}), then flush the
 * Resources caches ({@link #flushCaches}) so the activity recreate every deploy performs resolves
 * against the new table. The new package shares the old package id and resource ids; the
 * last-added package wins the lookup.
 *
 * Degraded relative to the API 30+ loader path, by design: added paths cannot be removed, so each
 * generation appends one more package until the process restarts, and a Resources object with its
 * own AssetManager only picks the table up when {@link ResourceStore#attachTo} reaches it.
 */
final class LegacyResourceSwap {

	/**
	 * Mounts the resource apk at {@code path} on the live AssetManager, via the hidden
	 * addAssetPath.
	 *
	 * Idempotent: the framework returns the existing cookie for an already-added path. Throws on
	 * any failure so the deploy path can roll the payload back, because a resource payload must
	 * never be silently dropped.
	 *
	 * @param assets the process's live AssetManager, normally {@code Resources#getAssets()}
	 * @param path absolute path of the apk written by {@link #writeResourceApk}; it must stay on
	 *     disk for the life of the process, since a mounted path can never be removed
	 * @throws IOException when the hidden method is missing, throws, or returns cookie 0, which
	 *     is the framework's way of rejecting the path
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
	 * Drops the cached drawables, color state lists and typed values so lookups cannot serve
	 * values from the old table.
	 *
	 * updateConfiguration with the current config is the only public way to force that.
	 *
	 * @param resources the Resources whose caches to drop; its configuration is re-applied
	 *     unchanged, so this is a flush and not a configuration change
	 */
	@SuppressWarnings("deprecation")
	static void flushCaches(Resources resources) {
		resources.updateConfiguration(resources.getConfiguration(), resources.getDisplayMetrics());
	}

	/**
	 * Copies the relinked resource apk stream to a gen-numbered zip under {@code dir}.
	 *
	 * The stream from {@code Aapt2Link} is already a valid apk/zip, so this is a plain byte copy.
	 * Do not re-wrap it: a bare arsc in a synthetic single-entry zip leaves file-backed resources
	 * such as layouts and drawable XMLs with no zip entry to resolve against, and they crash on
	 * first access.
	 *
	 * @param apk the relinked apk bytes; read to exhaustion but never closed, since the caller
	 *     owns the stream
	 * @param dir app-private directory to write into, created when missing
	 * @param generation the payload generation, which names the file and so keeps every mounted
	 *     path distinct
	 * @return the written file, whose path is what {@link #addAssetPath} mounts
	 * @throws IOException when {@code dir} cannot be created, the stream exceeds the payload cap,
	 *     or the write fails
	 */
	static File writeResourceApk(InputStream apk, File dir, long generation) throws IOException {
		byte[] bytes = Streams.readFully(apk);
		if (!dir.isDirectory() && !dir.mkdirs()) {
			throw new IOException("cannot create " + dir);
		}
		File zip = new File(dir, "gen-" + generation + ".zip");
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
