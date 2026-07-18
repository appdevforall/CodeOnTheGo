package com.itsaky.androidide.quickbuild.runtime;

import android.content.res.AssetManager;
import android.content.res.Resources;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The API 28/29 degraded resource path (plan B5). ResourcesLoader does not exist below API 30, so a resource payload is applied by:
 *
 * 1. wrapping the relinked resources.arsc into an apk-shaped zip ({@link #writeTableZip}; the framework can only mount resources from an apk/zip path, and the arsc entry must be STORED because the native ResTable mmaps it); 2. appending that zip to the live AssetManager via the hidden addAssetPath(String) ({@link #addAssetPath}; greylisted-but-callable on 28/29 - never used on 30+, where the ResourcesLoader path applies instead); 3. flushing the Resources caches ({@link #flushCaches}) so the activity recreate that every deploy already performs resolves values from the new table (same package id, same resource ids - the last-added package wins the lookup).
 *
 * Degraded relative to the loader path, by design: added paths cannot be removed, so each generation appends one more package (bounded by session length, reset by process restart), and a Resources object whose AssetManager is not shared with the application's only picks the table up when {@link ResourceStore#attachTo} reaches it.
 *
 * The zip-wrapping is plain java.util.zip and JVM-unit-tested; the reflective calls can only be exercised on a real 28/29 device.
 */
final class LegacyResourceSwap {

	static final String TABLE_ENTRY_NAME = "resources.arsc";

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
	 * Writes {@code table} (a raw resources.arsc stream) into {@code
	 *
	<dir>
	 * /gen-<generation>.zip} as a single STORED {@value #TABLE_ENTRY_NAME} entry. The caller owns (and closes) the stream.
	 */
	static File writeTableZip(InputStream table, File dir, long generation) throws IOException {
		byte[] bytes = Streams.readFully(table);
		if (!dir.isDirectory() && !dir.mkdirs()) {
			throw new IOException("cannot create " + dir);
		}
		File zip = new File(dir, "gen-" + generation + ".zip");
		CRC32 crc = new CRC32();
		crc.update(bytes);
		ZipEntry entry = new ZipEntry(TABLE_ENTRY_NAME);
		entry.setMethod(ZipEntry.STORED);
		entry.setSize(bytes.length);
		entry.setCompressedSize(bytes.length);
		entry.setCrc(crc.getValue());
		ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip));
		try {
			out.putNextEntry(entry);
			out.write(bytes);
			out.closeEntry();
		} finally {
			out.close();
		}
		return zip;
	}

	private LegacyResourceSwap() {}
}
