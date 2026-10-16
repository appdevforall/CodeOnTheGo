package com.itsaky.androidide.quickbuild.runtime;

import static com.google.common.truth.Truth.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * The baseline-generation stamp parser: a stamped baseline boots at its stamp, and every malformed or missing stamp must fall back to 0 - the pre-stamp constant - or an APK from an older plugin would change behavior.
 */
class BaselineGenerationTest {

	@Test
	void malformedStampIsGenerationZero() {
		assertThat(BaselineGeneration.parse("")).isEqualTo(0L);
		assertThat(BaselineGeneration.parse("garbage")).isEqualTo(0L);
		assertThat(BaselineGeneration.parse("1.5")).isEqualTo(0L);
		// Overflows a long.
		assertThat(BaselineGeneration.parse("99999999999999999999")).isEqualTo(0L);
	}

	@Test
	void missingStampIsGenerationZero() {
		assertThat(BaselineGeneration.parse(null)).isEqualTo(0L);
		assertThat(BaselineGeneration.read(null)).isEqualTo(0L);
	}

	@Test
	void negativeStampIsGenerationZero() {
		// The host's counter only hands out positive numbers; a negative stamp is
		// corruption, and adopting it would accept payloads at or below generation 0.
		assertThat(BaselineGeneration.parse("-3")).isEqualTo(0L);
	}

	@Test
	void parsesADecimalStamp() {
		assertThat(BaselineGeneration.parse("7")).isEqualTo(7L);
		assertThat(BaselineGeneration.parse("42")).isEqualTo(42L);
		assertThat(BaselineGeneration.parse(String.valueOf(Long.MAX_VALUE))).isEqualTo(Long.MAX_VALUE);
	}

	@Test
	void readsTheStampFromAStream() {
		InputStream in = new ByteArrayInputStream("9\n".getBytes(StandardCharsets.UTF_8));
		assertThat(BaselineGeneration.read(in)).isEqualTo(9L);
	}

	@Test
	void toleratesSurroundingWhitespace() {
		// The asset is written by a Gradle task; a trailing newline from a future edit
		// must not silently reset every baseline to 0.
		assertThat(BaselineGeneration.parse(" 12\n")).isEqualTo(12L);
	}

	@Test
	void unreadableStreamIsGenerationZero() {
		InputStream failing = new InputStream() {
			@Override
			public int read() throws IOException {
				throw new IOException("boom");
			}
		};
		assertThat(BaselineGeneration.read(failing)).isEqualTo(0L);
	}
}
