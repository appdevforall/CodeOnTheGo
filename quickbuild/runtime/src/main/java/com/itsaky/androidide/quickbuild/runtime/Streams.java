package com.itsaky.androidide.quickbuild.runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Stream helper for reading payload fds fully into memory. Dex goes straight into an InMemoryDexClassLoader and nothing ever lands in shared storage; the only disk the payload path touches is the app-PRIVATE {@link PayloadPersistence} store (component-proxying design section 3) plus the extracted-assets cache. Plain Java, JVM-unit-testable.
 */
final class Streams {

	private static final int BUFFER_SIZE = 16 * 1024;

	/**
	 * Ceiling for {@link #readFully(InputStream)}. Payload fds arrive as ParcelFileDescriptors, which Binder does not size-limit, and are read fully into memory on a binder thread - without a cap a runaway or hostile payload is an OOM. Every legitimate payload is the dex + resources + assets of a SINGLE app under edit (whole cold-deploy payloads measure in the tens of MB); 256 MB is far above any of those while still well under device RAM, so hitting it always means a corrupt or runaway payload, never a real deploy.
	 */
	static final int MAX_PAYLOAD_BYTES = 256 * 1024 * 1024;

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

	/** Reads {@code in} to exhaustion, capped at {@link #MAX_PAYLOAD_BYTES}. Does not close the stream; the caller owns it. */
	static byte[] readFully(InputStream in) throws IOException {
		return readFully(in, MAX_PAYLOAD_BYTES);
	}

	/**
	 * Reads {@code in} to exhaustion. Does not close the stream; the caller owns it.
	 *
	 * @throws IOException
	 *             if the stream carries more than {@code maxBytes} bytes; exactly {@code maxBytes} is fine. The read stops at the first over-cap chunk, so no unbounded buffering happens either way.
	 */
	static byte[] readFully(InputStream in, int maxBytes) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buffer = new byte[BUFFER_SIZE];
		int read;
		while ((read = in.read(buffer)) != -1) {
			if (out.size() + read > maxBytes) {
				throw new IOException("stream exceeds the " + maxBytes + "-byte payload limit; rejecting rather than buffering it in memory");
			}
			out.write(buffer, 0, read);
		}
		return out.toByteArray();
	}

	private Streams() {}
}
