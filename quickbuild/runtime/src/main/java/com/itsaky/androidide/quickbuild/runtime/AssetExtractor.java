package com.itsaky.androidide.quickbuild.runtime;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Extracts changed-assets zip payloads into one cumulative app-private override directory.
 *
 * Each payload carries only the assets that changed since the previous build, so extraction merges into one directory rather than per-payload dirs. It outlives the process on purpose: after a relaunch only the newest zip is re-applied from persistence, and the merged dir is what still holds the older ones. A fingerprint marker keys it to the baseline and clears it on mismatch, so assets never outlive the baseline they were deployed onto.
 *
 * Entry names arrive over binder and are checked for path traversal before any byte is written. Plain Java, no Android imports, so it stays JVM-unit-testable.
 */
final class AssetExtractor {

	/**
	 * Directory under the assets root that the merged extraction accumulates into. Its layout is a DirectoryAssetsProvider root: assets sit under an {@code assets/} subdirectory, because that provider treats its directory as the root of an APK.
	 */
	static final String CURRENT_DIR = "current";

	/** APK-layout subdirectory of {@link #CURRENT_DIR} the asset entries land in. */
	static final String ASSETS_SUBDIR = "assets";

	/** Marker file beside {@link #CURRENT_DIR} naming the baseline the merged assets belong to. */
	static final String BASELINE_MARKER = "baseline.fp";

	/**
	 * Marker file beside {@link #CURRENT_DIR} that exists only while a merge is in flight.
	 *
	 * Finding it at the start of the next merge means the previous one died part-way, so the merged dir holds two generations. The baseline marker still matches and later payloads carry only newly-changed files, so nothing else would ever heal it - a wrongly-written file would stay wrong until a forced rebuild.
	 */
	static final String MERGE_PENDING_MARKER = "merge.pending";

	private static final int BUFFER_SIZE = 16 * 1024;

	/**
	 * The merged override directory under {@code assetsRoot} - the DirectoryAssetsProvider root.
	 *
	 * @param assetsRoot
	 *            the per-app assets cache root the cumulative state lives under
	 * @return the directory {@link #extractCumulative} merges into; may not exist yet
	 */
	static File currentDir(File assetsRoot) {
		return new File(assetsRoot, CURRENT_DIR);
	}

	/**
	 * Extracts every file entry of {@code zipStream} under {@code destDir}, overwriting existing files. Does not close the stream; the caller owns it.
	 *
	 * @param zipStream
	 *            the changed-assets zip as it arrived over binder; read but never closed
	 * @param destDir
	 *            the app-private override directory, created when missing
	 * @return the number of files extracted, directory entries excluded
	 * @throws IOException
	 *             on I/O failure, when an entry would escape {@code destDir}, or when the entries together exceed {@link Streams#MAX_PAYLOAD_BYTES}; extraction stops and the directory can hold a partial set
	 */
	static int extract(InputStream zipStream, File destDir) throws IOException {
		if (!destDir.isDirectory() && !destDir.mkdirs()) {
			throw new IOException("cannot create asset dir " + destDir);
		}
		String destPrefix = destDir.getCanonicalPath() + File.separator;
		ZipInputStream zip = new ZipInputStream(zipStream);
		int count = 0;
		// Every other payload path is capped at MAX_PAYLOAD_BYTES; the per-entry writes
		// here were not, so a zip's entries could together exceed it. The cap is
		// cumulative because no single entry has to be large to get there.
		long written = 0;
		ZipEntry entry;
		while ((entry = zip.getNextEntry()) != null) {
			try {
				if (entry.isDirectory()) {
					continue;
				}
				File target = new File(destDir, entry.getName());
				if (!target.getCanonicalPath().startsWith(destPrefix)) {
					throw new IOException("zip entry escapes destination: " + entry.getName());
				}
				written += writeFile(zip, target, Streams.MAX_PAYLOAD_BYTES - written);
				count++;
			} finally {
				zip.closeEntry();
			}
		}
		return count;
	}

	/**
	 * Merges a changed-assets zip into the cumulative override dir, clearing it first when it was built against another baseline.
	 *
	 * The clear-then-mark order is the safe crash window: a death between the two leaves a mismatched marker, so the next call clears an already-empty dir instead of serving another baseline's assets.
	 *
	 * A merge that dies part-way is recovered at the START of the next call, not on the failure path: {@link #MERGE_PENDING_MARKER} is written before the first byte and cleared only after the last, and finding it still there clears the dir. A cleared dir is safe - the provider falls through to the APK's baked-in assets - whereas a half-merged one serves a file from the wrong generation.
	 *
	 * @param zipStream
	 *            the changed-assets zip as it arrived over binder; read but never closed
	 * @param assetsRoot
	 *            the per-app assets cache root holding the merged dir and its marker
	 * @param baselineFingerprint
	 *            the running baseline's fingerprint; a marker mismatch clears the merged dir
	 * @return the number of files extracted from this zip, directory entries excluded
	 * @throws IOException
	 *             on I/O failure, a path-traversal entry, or a stale dir that cannot be cleared - serving it anyway would violate the never-stale invariant
	 */
	static int extractCumulative(InputStream zipStream, File assetsRoot,
			String baselineFingerprint) throws IOException {
		if (baselineFingerprint == null) {
			throw new IOException("no baseline fingerprint; cannot key the asset override dir");
		}
		File providerRoot = currentDir(assetsRoot);
		File marker = new File(assetsRoot, BASELINE_MARKER);
		File pending = new File(assetsRoot, MERGE_PENDING_MARKER);
		if (!baselineFingerprint.equals(readMarker(marker)) || pending.isFile()) {
			deleteRecursively(providerRoot);
			writeMarker(marker, baselineFingerprint);
		}
		writeMarker(pending, baselineFingerprint);
		int count = extract(zipStream, new File(providerRoot, ASSETS_SUBDIR));
		if (!pending.delete()) {
			// The merge itself is complete and correct, but a marker we cannot clear makes the
			// next call clear a dir that did not need it. Say so rather than leave it silent.
			throw new IOException("cannot clear merge marker " + pending);
		}
		return count;
	}

	/**
	 * Deletes {@code file} and everything under it; a no-op when it does not exist.
	 *
	 * @param file
	 *            the file or directory to remove
	 * @throws IOException
	 *             when anything cannot be deleted; the caller must not proceed, since leftover files would be served as live assets
	 */
	private static void deleteRecursively(File file) throws IOException {
		File[] children = file.listFiles();
		if (children != null) {
			for (File child : children) {
				deleteRecursively(child);
			}
		}
		if (file.exists() && !file.delete()) {
			throw new IOException("cannot delete stale asset override " + file);
		}
	}

	/**
	 * Reads the baseline marker.
	 *
	 * @param marker
	 *            the marker file; may be absent
	 * @return its contents, or null when absent or unreadable - both count as a mismatch, which errs toward clearing rather than serving assets of unknown provenance
	 */
	private static String readMarker(File marker) {
		if (!marker.isFile()) {
			return null;
		}
		InputStream in = null;
		try {
			in = new FileInputStream(marker);
			return new String(Streams.readFully(in), StandardCharsets.UTF_8);
		} catch (IOException error) {
			return null;
		} finally {
			Streams.closeQuietly(in);
		}
	}

	/**
	 * Writes to a temp file and renames, so a failure mid-copy never leaves a half-written asset.
	 *
	 * @param in
	 *            the current zip entry's bytes; read to the end of the entry, never closed
	 * @param target
	 *            the final path, already checked to sit inside the destination directory
	 * @throws IOException
	 *             when a parent directory cannot be created, the copy fails, or the rename into place fails twice
	 */
	private static long writeFile(InputStream in, File target, long remaining) throws IOException {
		File parent = target.getParentFile();
		if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
			throw new IOException("cannot create dir " + parent);
		}
		File temp = new File(parent, target.getName() + ".qb-tmp");
		FileOutputStream out = new FileOutputStream(temp);
		long written = 0;
		try {
			byte[] buffer = new byte[BUFFER_SIZE];
			int read;
			while ((read = in.read(buffer)) != -1) {
				written += read;
				if (written > remaining) {
					throw new IOException("asset payload exceeds " + Streams.MAX_PAYLOAD_BYTES
							+ " bytes at " + target.getName());
				}
				out.write(buffer, 0, read);
			}
		} finally {
			out.close();
		}
		if (!temp.renameTo(target)) {
			// Rename over an existing file can fail on some filesystems; retry once
			// after an explicit delete, then give up loudly.
			target.delete();
			if (!temp.renameTo(target)) {
				temp.delete();
				throw new IOException("cannot move extracted asset into place: " + target);
			}
		}
		return written;
	}

	/**
	 * Writes the baseline marker. A plain write, not temp-then-rename: a torn marker reads as a mismatch, which clears and rewrites - the safe direction.
	 *
	 * @param marker
	 *            the marker file; its parent is created when missing
	 * @param fingerprint
	 *            the baseline fingerprint to record
	 * @throws IOException
	 *             when the parent cannot be created or the write fails
	 */
	private static void writeMarker(File marker, String fingerprint) throws IOException {
		File parent = marker.getParentFile();
		if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
			throw new IOException("cannot create dir " + parent);
		}
		FileOutputStream out = new FileOutputStream(marker);
		try {
			out.write(fingerprint.getBytes(StandardCharsets.UTF_8));
		} finally {
			out.close();
		}
	}

	private AssetExtractor() {}
}
