package com.itsaky.androidide.quickbuild.runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Reads payload fds fully into memory, with a size cap.
 *
 * Dex bytes go straight into an InMemoryDexClassLoader; nothing lands in shared storage. The only disk the payload path touches is the app-private {@link PayloadPersistence} store and the extracted-assets cache.
 */
final class Streams {

	private static final int BUFFER_SIZE = 16 * 1024;

	/**
	 * Ceiling for {@link #readFully(InputStream)}, guarding against an OOM from a runaway payload.
	 *
	 * Binder does not size-limit a ParcelFileDescriptor, and payloads are read fully into memory. A legitimate payload is one app's dex, resources and assets, tens of MB even for a whole cold deploy, so hitting 256 MB always means something is wrong.
	 */
	static final int MAX_PAYLOAD_BYTES = 256 * 1024 * 1024;

	/**
	 * Closes {@code closeable} if non-null, swallowing any close failure.
	 *
	 * @param closeable
	 *            the stream or fd to close; null is a no-op, so callers need not pre-check
	 */
	static void closeQuietly(AutoCloseable closeable) {
		if (closeable != null) {
			try {
				closeable.close();
			} catch (Exception ignored) {
				// Nothing useful to do with a failed close.
			}
		}
	}

	/**
	 * Reads {@code in} to exhaustion, capped at {@link #MAX_PAYLOAD_BYTES}. Does not close the stream; the caller owns it.
	 *
	 * @param in
	 *            the payload stream, normally a fd handed over binder; read but never closed
	 * @return the whole stream as a fresh array, empty when the stream was
	 * @throws IOException
	 *             on a read failure, or when the stream exceeds {@link #MAX_PAYLOAD_BYTES}
	 */
	static byte[] readFully(InputStream in) throws IOException {
		return readFully(in, MAX_PAYLOAD_BYTES);
	}

	/**
	 * Reads {@code in} to exhaustion. Does not close the stream; the caller owns it.
	 *
	 * @param in
	 *            the stream to drain; read but never closed
	 * @param maxBytes
	 *            inclusive ceiling on the total read; exactly {@code maxBytes} is fine
	 * @return the whole stream as a fresh array, empty when the stream was
	 * @throws IOException
	 *             on a read failure, or if the stream carries more than {@code maxBytes} bytes. The read stops at the first over-cap chunk, so it never buffers without bound.
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
