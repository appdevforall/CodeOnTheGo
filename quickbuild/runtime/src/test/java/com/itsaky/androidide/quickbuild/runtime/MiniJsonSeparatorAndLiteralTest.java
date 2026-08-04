package com.itsaky.androidide.quickbuild.runtime;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Covers the hand-rolled parser's separator and literal-token edges.
 *
 * A missing comma, a value that starts on a separator, and literals terminated by whitespace,
 * ']' or end-of-input. Every malformed input must throw IllegalArgumentException - the
 * bad-payload contract - rather than parse into something plausible.
 */
class MiniJsonSeparatorAndLiteralTest {

	@Test
	void aBracketTerminatedLiteralInsideAnArrayIsDropped() {
		Map<String, Object> obj = MiniJson.parseObject("{\"a\":[1]}");
		assertThat(obj).containsKey("a");
		assertThat((Iterable<?>) obj.get("a")).isEmpty();
	}

	@Test
	void aLiteralRunningToEndOfInputThrows() {
		// skipLiteral consumes "true" to the end; the object is then unterminated.
		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> MiniJson.parseObject("{\"a\":true"));
		assertThat(error).hasMessageThat().contains("unexpected end of input");
	}

	@Test
	void arrayElementsWithoutACommaThrow() {
		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> MiniJson.parseObject("{\"a\":[\"x\" \"y\"]}"));
		assertThat(error).hasMessageThat().contains("expected ',' or ']'");
	}

	@Test
	void aValueStartingOnASeparatorThrows() {
		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> MiniJson.parseObject("{\"a\":,}"));
		assertThat(error).hasMessageThat().contains("unexpected character");
	}

	@Test
	void aWhitespaceTerminatedLiteralIsDropped() {
		Map<String, Object> obj = MiniJson.parseObject("{\"a\":true }");
		assertThat(obj).isEmpty();
	}

	@Test
	void objectEntriesWithoutACommaThrow() {
		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> MiniJson.parseObject("{\"a\":\"b\" \"c\":\"d\"}"));
		assertThat(error).hasMessageThat().contains("expected ',' or '}'");
	}
}
