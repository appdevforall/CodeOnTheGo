package com.itsaky.androidide.quickbuild.runtime;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Covers the parser's behaviour on hostile and near-miss input.
 *
 * The documents come over binder from CoGo, so every rejection has to be the IllegalArgumentException the class contracts to throw: an Error escapes the callers' catch clauses, and a value dropped without being checked is indistinguishable from a key the host never sent.
 */
class MiniJsonHardeningTest {

	/**
	 * Builds a document nesting {@code levels} objects inside the top-level one.
	 *
	 * @param levels
	 *            how many nested objects to wrap around the innermost value
	 * @return the document text
	 */
	private static String nested(int levels) {
		StringBuilder json = new StringBuilder("{\"a\":");
		for (int i = 0; i < levels; i++) {
			json.append("{\"a\":");
		}
		json.append("\"deep\"");
		for (int i = 0; i < levels; i++) {
			json.append('}');
		}
		return json.append('}').toString();
	}

	@Test
	void aDuplicateKeyKeepsTheLastValue() {
		// The documented contract, and the one a map insertion helper can silently
		// invert: with putIfAbsent this reads "first" and every other test stays green.
		Map<String, Object> obj = MiniJson.parseObject("{\"a\":\"first\",\"a\":\"second\"}");

		assertThat(obj.get("a")).isEqualTo("second");
	}

	@Test
	void anUncheckedLiteralIsRejectedRatherThanDroppedSilently() {
		// Every one of these parses cleanly without the shape check, leaving the key
		// absent - which a caller reads as "the host did not send it".
		assertRejects("{\"b\":qqq}");
		assertRejects("{\"b\":tru}");
		assertRejects("{\"b\":TRUE}");
		assertRejects("{\"b\":nul}");
		assertRejects("{\"b\":01}");
		assertRejects("{\"b\":1.}");
		assertRejects("{\"b\":.5}");
		assertRejects("{\"b\":+1}");
		assertRejects("{\"b\":-}");
		assertRejects("{\"b\":1e}");
		assertRejects("{\"b\":1e+}");
		assertRejects("{\"b\":1e5x}");
		assertRejects("{\"b\":0x1f}");
		assertRejects("{\"b\":NaN}");
		assertRejects("{\"b\":Infinity}");
		assertRejects("{\"b\":1d}");
		assertRejects("{\"b\":\u0661}");
	}

	@Test
	void aSignedUnicodeEscapeIsRejected() {
		// Integer.parseInt(hex, 16) accepts a sign, so these decode to 0x41 and -0x41
		// unless the four chars are checked as digits first.
		assertThrows(IllegalArgumentException.class, () -> MiniJson.parseObject("{\"a\":\"\\u+041\"}"));
		assertThrows(IllegalArgumentException.class, () -> MiniJson.parseObject("{\"a\":\"\\u-041\"}"));
		assertThrows(IllegalArgumentException.class, () -> MiniJson.parseObject("{\"a\":\"\\u 041\"}"));
	}

	@Test
	void deepNestingInsideArraysIsCappedToo() {
		StringBuilder json = new StringBuilder("{\"a\":");
		for (int i = 0; i < 20000; i++) {
			json.append('[');
		}
		assertThrows(IllegalArgumentException.class, () -> MiniJson.parseObject(json.toString()));
	}

	@Test
	void deepNestingIsRejectedAsBadInputNotAsAnError() {
		// Without a depth cap this raises StackOverflowError, which is an Error and so
		// escapes assertThrows(IllegalArgumentException) and every caller's catch.
		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> MiniJson.parseObject(nested(20000)));

		assertThat(error).hasMessageThat().contains("nesting deeper than");
	}

	@Test
	void manySiblingsAreNotMistakenForDepth() {
		// The cap counts open levels, not levels ever opened; a flat document with far
		// more than the cap's worth of nested-but-closed values must still parse.
		StringBuilder json = new StringBuilder("{\"keep\":\"v\"");
		for (int i = 0; i < 500; i++) {
			json.append(",\"n").append(i).append("\":{\"x\":[\"y\"]}");
		}
		json.append('}');

		assertThat(MiniJson.parseObject(json.toString()).get("keep")).isEqualTo("v");
	}

	@Test
	void nestingUpToTheCapStillParses() {
		// 63 nested objects plus the top-level one is exactly the cap.
		assertThat(MiniJson.parseObject(nested(63))).isEmpty();
	}

	@Test
	void unicodeEscapesStillDecodeAcrossTheHexRange() {
		Map<String, Object> obj = MiniJson.parseObject(
				"{\"a\":\"\\u0041\\u00ff\\u00FF\\uabcd\\uABCD\\u0061\"}");

		assertThat(obj.get("a")).isEqualTo("A\u00ff\u00FF\uabcd\uABCD\u0061");
	}

	@Test
	void wellFormedLiteralsAreStillConsumedAndDropped() {
		// The shape check must not start rejecting the numbers a real document carries.
		Map<String, Object> obj = MiniJson.parseObject("{\"a\":0,\"b\":-0,\"c\":12,\"d\":-1.5,"
				+ "\"e\":1e3,\"f\":1E+3,\"g\":-2.5e-4,\"h\":true,\"i\":false,\"j\":null,"
				+ "\"keep\":\"v\"}");

		assertThat(obj.keySet()).containsExactly("keep");
	}

	/**
	 * Asserts {@code json} is refused as malformed rather than parsed with a key dropped.
	 *
	 * @param json
	 *            the document to reject
	 */
	private void assertRejects(String json) {
		assertThrows(IllegalArgumentException.class, () -> MiniJson.parseObject(json),
				"expected " + json + " to be rejected");
	}
}
