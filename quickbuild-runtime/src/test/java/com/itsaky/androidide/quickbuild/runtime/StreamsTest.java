package com.itsaky.androidide.quickbuild.runtime;

import static com.google.common.truth.Truth.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Random;
import org.junit.jupiter.api.Test;

class StreamsTest {

	@Test
	void emptyStreamYieldsEmptyArray() throws IOException {
		assertThat(Streams.readFully(new ByteArrayInputStream(new byte[0]))).isEmpty();
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
}
