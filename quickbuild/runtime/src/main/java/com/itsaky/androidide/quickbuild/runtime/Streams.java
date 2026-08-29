package com.itsaky.androidide.quickbuild.runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

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
	 * Binder does not size-limit a ParcelFileDescriptor, and payloads are read fully into memory. A legitimate payload is one app's dex, resources and assets, tens of MB even for a whole cold deploy, so anything near this always means something is wrong.
	 *
	 * The number has to be one a device heap can actually hold, or the guard cannot prevent the OOM it exists for: the buffer doubles as it grows and {@link ByteArrayOutputStream#toByteArray} copies it, so a payload at the cap needs roughly twice the cap live at the copy. At the former 256 MB that was half a gigabyte on a phone whose whole heap is a few hundred MB - the app died before the cap ever fired. 64 MB keeps the peak inside a low-end heap and still leaves generous headroom over a real cold deploy.
	 */
	static final int MAX_PAYLOAD_BYTES = 64 * 1024 * 1024;

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
	 * Copies {@code in} to {@code out} to exhaustion, capped at {@code maxBytes}. Closes neither; the caller owns both.
	 *
	 * The point of copying rather than reading into an array is that nothing payload-sized is ever live in the heap: a chunk at a time crosses, so a large resource apk costs the buffer rather than twice its own size.
	 *
	 * @param in
	 *            the stream to drain; read but never closed
	 * @param out
	 *            where the bytes go; written but never closed or flushed
	 * @param maxBytes
	 *            inclusive ceiling on the total copied; exactly {@code maxBytes} is fine
	 * @return the number of bytes copied
	 * @throws IOException
	 *             on a read or write failure, or at the first chunk that would carry the total past {@code maxBytes}, so an oversize stream is refused part-written rather than absorbed
	 */
	static long copy(InputStream in, OutputStream out, int maxBytes) throws IOException {
		byte[] buffer = new byte[BUFFER_SIZE];
		long total = 0;
		int read;
		while ((read = in.read(buffer)) != -1) {
			if (total + read > maxBytes) {
				throw new IOException("stream exceeds the " + maxBytes + "-byte payload limit; rejecting rather than writing it out");
			}
			out.write(buffer, 0, read);
			total += read;
		}
		return total;
	}

	/**
	 * Reads {@code in} to exhaustion, capped at {@link #MAX_PAYLOAD_BYTES}. Does not close the stream; the caller owns it.
	 *
	 * @param in
	 *            the payload stream, normally a fd handed over binder; read but never closed
	 * @return the whole stream as a fresh array, empty when the stream was already at its end
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
	 * @return the whole stream as a fresh array, empty when the stream was already at its end
	 * @throws IOException
	 *             on a read failure, or at the first chunk that would carry the total past {@code maxBytes}, so it never buffers without bound
	 */
	static byte[] readFully(InputStream in, int maxBytes) throws IOException {
		return readFully(in, maxBytes, 0);
	}

	/**
	 * Reads {@code in} to exhaustion, sizing the buffer up front when the caller knows how big the stream is. Does not close the stream; the caller owns it.
	 *
	 * The hint only avoids the doubling-and-copying a growing buffer does; it is invisible in the bytes returned, and a wrong hint costs at most the growth it would have cost anyway. Callers holding a file or a descriptor have an exact size for free and pass it straight through - a length of 0, the negative a pipe reports, or one past {@code maxBytes} is taken as "unknown" here rather than checked at every call site.
	 *
	 * @param in
	 *            the stream to drain; read but never closed
	 * @param maxBytes
	 *            inclusive ceiling on the total read; exactly {@code maxBytes} is fine
	 * @param sizeHint
	 *            expected byte count, straight from {@code File.length()} or {@code ParcelFileDescriptor.getStatSize()}; anything outside {@code 1..maxBytes} means unknown and starts at the read-buffer size
	 * @return the whole stream as a fresh array, empty when the stream was already at its end
	 * @throws IOException
	 *             on a read failure, or at the first chunk that would carry the total past {@code maxBytes}, so it never buffers without bound
	 */
	static byte[] readFully(InputStream in, int maxBytes, long sizeHint) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream(
				sizeHint > 0 && sizeHint <= maxBytes ? (int) sizeHint : BUFFER_SIZE);
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
