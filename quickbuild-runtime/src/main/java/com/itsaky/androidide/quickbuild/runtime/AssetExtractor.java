package com.itsaky.androidide.quickbuild.runtime;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Extracts a changed-assets zip payload into an app-private directory. This is the one place a payload touches disk - assets have no in-memory loading API, so v1's asset reload is best-effort via an extracted override directory (see {@link ResourceStore#overrideAsset}).
 *
 * Entry names come from an fd handed over binder, so they are validated against path traversal (zip-slip) before any byte is written; files are written temp+rename so a mid-extraction failure never leaves a half-written asset behind. Plain Java (no Android imports), JVM-unit-testable.
 */
final class AssetExtractor {

	private static final int BUFFER_SIZE = 16 * 1024;

	/**
	 * Extracts every file entry of {@code zipStream} under {@code destDir}, overwriting existing files. Does not close the stream; the caller owns it.
	 *
	 * @return the number of files extracted.
	 * @throws IOException
	 *             on I/O failure or when an entry would escape {@code destDir}.
	 */
	static int extract(InputStream zipStream, File destDir) throws IOException {
		if (!destDir.isDirectory() && !destDir.mkdirs()) {
			throw new IOException("cannot create asset dir " + destDir);
		}
		String destPrefix = destDir.getCanonicalPath() + File.separator;
		ZipInputStream zip = new ZipInputStream(zipStream);
		int count = 0;
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
				writeFile(zip, target);
				count++;
			} finally {
				zip.closeEntry();
			}
		}
		return count;
	}

	private static void writeFile(InputStream in, File target) throws IOException {
		File parent = target.getParentFile();
		if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
			throw new IOException("cannot create dir " + parent);
		}
		File temp = new File(parent, target.getName() + ".qb-tmp");
		FileOutputStream out = new FileOutputStream(temp);
		try {
			byte[] buffer = new byte[BUFFER_SIZE];
			int read;
			while ((read = in.read(buffer)) != -1) {
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
	}

	private AssetExtractor() {}
}
