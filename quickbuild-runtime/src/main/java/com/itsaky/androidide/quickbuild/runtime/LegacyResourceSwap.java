package com.itsaky.androidide.quickbuild.runtime;

import android.content.res.AssetManager;
import android.content.res.Resources;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;

/**
 * The API 28/29 degraded resource path (plan B5). ResourcesLoader does not exist below API 30, so a resource payload is applied by:
 *
 * 1. persisting the relinked resource apk to a file ({@link #writeResourceApk}; the framework can only mount resources from an apk/zip path - the received bytes are ALREADY a valid apk/zip, aapt2 link's own output with resources.arsc stored uncompressed as the framework requires for mmap, so this is a plain byte copy, no re-wrapping); 2. appending that file to the live AssetManager via the hidden addAssetPath(String) ({@link #addAssetPath}; greylisted-but-callable on 28/29 - never used on 30+, where the ResourcesLoader path applies instead); 3. flushing the Resources caches ({@link #flushCaches}) so the activity recreate that every deploy already performs resolves values from the new table (same package id, same resource ids - the last-added package wins the lookup).
 *
 * Degraded relative to the loader path, by design: added paths cannot be removed, so each generation appends one more package (bounded by session length, reset by process restart), and a Resources object whose AssetManager is not shared with the application's only picks the table up when {@link ResourceStore#attachTo} reaches it.
 *
 * Before ADFA-4128 Bug 5's fix this method wrapped a BARE arsc byte array into a
 * synthetic single-entry zip - which meant file-backed resources (layouts, drawable
 * XMLs) had no zip entry to resolve against and crashed on first access. The payload is
 * now the full relinked apk from {@code Aapt2Link} (already a proper apk/zip), so the
 * write is a straight byte copy.
 *
 * Plain java.io and JVM-unit-tested; the reflective calls can only be exercised on a real 28/29 device.
 */
final class LegacyResourceSwap {

	/**
	 * Appends {@code path} to {@code assets} via the hidden AssetManager.addAssetPath. Idempotent (the framework returns the existing cookie for an already-added path). Throws on any failure so the deploy path can roll the payload back - a resource payload must never be silently dropped (never-stale invariant).
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
	 * Drops the ResourcesImpl caches (drawables, color state lists, cached typed values) so lookups after an addAssetPath cannot serve values resolved from the old table. updateConfiguration with the CURRENT config is the one public way to force that.
	 */
	@SuppressWarnings("deprecation")
	static void flushCaches(Resources resources) {
		resources.updateConfiguration(resources.getConfiguration(), resources.getDisplayMetrics());
	}

	/**
	 * Writes {@code apk} (the relinked resource apk stream from {@code Aapt2Link} -
	 * resources.arsc plus every compiled resource file) into {@code
	 *
	<dir>
	 * /gen-<generation>.zip}. The stream is already a valid apk/zip, so this is a plain
	 * byte copy - no re-wrapping (see class doc). The caller owns (and closes) the stream.
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
