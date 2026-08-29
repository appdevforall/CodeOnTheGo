package com.itsaky.androidide.quickbuild.runtime;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;
import org.junit.jupiter.api.Test;

class StreamsTest {

	@Test
	void aCopyRefusesAtTheCapRatherThanWritingPastIt() {
		// The cap has to hold on the streaming path too, or moving resources off the heap
		// would have quietly removed the only guard against a runaway payload.
		byte[] data = new byte[64 * 1024 + 1];
		ByteArrayOutputStream out = new ByteArrayOutputStream();

		IOException thrown = assertThrows(IOException.class,
				() -> Streams.copy(new ByteArrayInputStream(data), out, 64 * 1024));
		assertThat(thrown).hasMessageThat().contains(String.valueOf(64 * 1024));
		assertThat(out.size()).isAtMost(64 * 1024);
	}

	@Test
	void aCopyReproducesTheStreamExactlyAcrossBufferBoundaries() throws IOException {
		// Sized past the internal buffer on purpose: a copy that mishandled a partial
		// final chunk would still pass on anything that fits in one read.
		byte[] data = new byte[40 * 1024 + 7];
		new Random(11).nextBytes(data);
		ByteArrayOutputStream out = new ByteArrayOutputStream();

		long copied = Streams.copy(new ByteArrayInputStream(data), out, 64 * 1024);

		assertThat(copied).isEqualTo(data.length);
		assertThat(out.toByteArray()).isEqualTo(data);
	}

	@Test
	void aSizeHintDoesNotRaiseTheCap() {
		// The hint arrives from the same descriptor the stream does, so an oversized one
		// must never be read as permission to buffer that much.
		byte[] data = new byte[64 * 1024 + 1];

		IOException thrown = assertThrows(IOException.class,
				() -> Streams.readFully(new ByteArrayInputStream(data), 64 * 1024, data.length));
		assertThat(thrown).hasMessageThat().contains(String.valueOf(64 * 1024));
	}

	@Test
	void aSizeHintNeverChangesTheBytesReturned() throws IOException {
		// The hint only picks the starting buffer size. Each value here is one a real
		// caller passes - an exact File.length(), the -1 a pipe reports, a stale length
		// from a file that changed under the read, and one past the cap - and all four
		// must return the same array, or presizing has become a correctness surface.
		byte[] data = new byte[40 * 1024];
		new Random(23).nextBytes(data);

		assertThat(Streams.readFully(new ByteArrayInputStream(data), 64 * 1024, data.length))
				.isEqualTo(data);
		assertThat(Streams.readFully(new ByteArrayInputStream(data), 64 * 1024, -1L)).isEqualTo(data);
		assertThat(Streams.readFully(new ByteArrayInputStream(data), 64 * 1024, 8L)).isEqualTo(data);
		assertThat(Streams.readFully(new ByteArrayInputStream(data), 64 * 1024, Long.MAX_VALUE))
				.isEqualTo(data);
	}

	@Test
	void defaultOverloadAppliesThePayloadCap() {
		// The 1-arg overload every production call site uses must carry the cap itself - a
		// capped 2-arg variant nobody calls would leave all six call sites unbounded.
		IOException thrown = assertThrows(IOException.class,
				() -> Streams.readFully(new OversizedStream(Streams.MAX_PAYLOAD_BYTES + 1L)));
		assertThat(thrown).hasMessageThat().contains(String.valueOf(Streams.MAX_PAYLOAD_BYTES));
	}

	@Test
	void emptyStreamYieldsEmptyArray() throws IOException {
		assertThat(Streams.readFully(new ByteArrayInputStream(new byte[0]))).isEmpty();
	}

	@Test
	void exactCapSizedStreamReadsFully() throws IOException {
		// The cap is inclusive: exactly maxBytes is a legal payload, one byte more is not.
		byte[] data = new byte[64 * 1024];
		new Random(11).nextBytes(data);
		assertThat(Streams.readFully(new ByteArrayInputStream(data), 64 * 1024)).isEqualTo(data);
	}

	@Test
	void overCapStreamThrowsNamingTheLimit() {
		// Payload fds are Binder-unbounded and read fully on a binder thread; without the cap
		// an oversized payload is an OOM, not an IOException the deploy path can reject.
		byte[] data = new byte[64 * 1024 + 1];
		IOException thrown = assertThrows(IOException.class,
				() -> Streams.readFully(new ByteArrayInputStream(data), 64 * 1024));
		assertThat(thrown).hasMessageThat().contains(String.valueOf(64 * 1024));
	}

	@Test
	void readsContentLargerThanInternalBuffer() throws IOException {
		// 100 KB > the 16 KB read buffer, so the loop must run several times.
		byte[] data = new byte[100 * 1024];
		new Random(42).nextBytes(data);
		assertThat(Streams.readFully(new ByteArrayInputStream(data))).isEqualTo(data);
	}

	@Test
	void readsSmallStreamFully() throws IOException {
		byte[] data = "payload-bytes".getBytes("UTF-8");
		assertThat(Streams.readFully(new ByteArrayInputStream(data))).isEqualTo(data);
	}

	@Test
	void readsUnderCapContentIntact() throws IOException {
		// A cap spanning several internal buffers: content under it must arrive byte-identical.
		byte[] data = new byte[40 * 1024];
		new Random(7).nextBytes(data);
		assertThat(Streams.readFully(new ByteArrayInputStream(data), 64 * 1024)).isEqualTo(data);
	}

	/**
	 * Claims {@code size} zero bytes without ever allocating them.
	 *
	 * Makes the default cap testable in-heap: the capped reader must throw before it buffers anywhere near that much.
	 */
	private static final class OversizedStream extends java.io.InputStream {

		private long remaining;

		OversizedStream(long size) {
			this.remaining = size;
		}

		@Override
		public int read() {
			if (remaining <= 0) {
				return -1;
			}
			remaining--;
			return 0;
		}

		@Override
		public int read(byte[] buffer, int offset, int length) {
			if (remaining <= 0) {
				return -1;
			}
			int count = (int) Math.min(length, remaining);
			java.util.Arrays.fill(buffer, offset, offset + count, (byte) 0);
			remaining -= count;
			return count;
		}
	}
}
