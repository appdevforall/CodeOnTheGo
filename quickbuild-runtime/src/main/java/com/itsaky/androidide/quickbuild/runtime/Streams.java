package com.itsaky.androidide.quickbuild.runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Stream helper for reading payload fds fully into memory. Dex goes straight into an InMemoryDexClassLoader and nothing ever lands in shared storage; the only disk the payload path touches is the app-PRIVATE {@link PayloadPersistence} store (component-proxying design section 3, revising plan D1) plus the extracted-assets cache. Plain Java, JVM-unit-testable.
 */
final class Streams {

	private static final int BUFFER_SIZE = 16 * 1024;

	/** Closes {@code closeable} if non-null, swallowing any close failure. */
	static void closeQuietly(AutoCloseable closeable) {
		if (closeable != null) {
			try {
				closeable.close();
			} catch (Exception ignored) {
				// Nothing useful to do with a failed close.
			}
		}
	}

	/** Reads {@code in} to exhaustion. Does not close the stream; the caller owns it. */
	static byte[] readFully(InputStream in) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buffer = new byte[BUFFER_SIZE];
		int read;
		while ((read = in.read(buffer)) != -1) {
			out.write(buffer, 0, read);
		}
		return out.toByteArray();
	}

	private Streams() {}
}
