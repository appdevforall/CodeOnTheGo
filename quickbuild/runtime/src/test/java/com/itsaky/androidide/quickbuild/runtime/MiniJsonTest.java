package com.itsaky.androidide.quickbuild.runtime;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MiniJsonTest {

	@Test
	void decodesEscapes() {
		Map<String, Object> obj = MiniJson.parseObject(
				"{\"e\": \"q\\\"b\\\\s\\/f\\nn\\tt\\rr\\bb\\ff\\u0041u\"}");
		assertThat(obj.get("e")).isEqualTo("q\"b\\s/f\nn\tt\rr\bb\ffAu");
	}

	@Test
	void dropsNestedObjectsButKeepsFollowingFields() {
		Map<String, Object> obj = MiniJson.parseObject(
				"{\"nested\": {\"deep\": {\"x\": [1, \"s\"]}}, \"after\": \"v\"}");
		assertThat(obj.keySet()).containsExactly("after");
	}

	@Test
	void dropsNumbersBooleansAndNulls() {
		Map<String, Object> obj = MiniJson.parseObject(
				"{\"n\": 42, \"f\": -1.5e3, \"t\": true, \"z\": null, \"keep\": \"v\"}");
		assertThat(obj.keySet()).containsExactly("keep");
		assertThat(obj.get("keep")).isEqualTo("v");
	}

	@Test
	void keepsOnlyStringElementsInsideArrays() {
		Map<String, Object> obj = MiniJson.parseObject(
				"{\"a\": [\"keep\", 1, true, null, {\"o\": 1}, [\"inner\"], \"also\"]}");
		assertThat(obj.get("a")).isEqualTo(Arrays.asList("keep", "also"));
	}

	@Test
	void parsesEmptyObjectAndEmptyArray() {
		assertThat(MiniJson.parseObject("{}")).isEmpty();
		assertThat(MiniJson.parseObject("{\"a\": []}").get("a"))
				.isEqualTo(Arrays.asList());
	}

	@Test
	void parsesStringsAndStringArrays() {
		Map<String, Object> obj = MiniJson.parseObject(
				"{\"a\": \"hello\", \"b\": [\"x\", \"y\"]}");
		assertThat(obj.get("a")).isEqualTo("hello");
		assertThat(obj.get("b")).isEqualTo(Arrays.asList("x", "y"));
	}

	@Test
	void throwsOnMalformedInput() {
		assertThrows(IllegalArgumentException.class, () -> MiniJson.parseObject(null));
		assertThrows(IllegalArgumentException.class, () -> MiniJson.parseObject(""));
		assertThrows(IllegalArgumentException.class, () -> MiniJson.parseObject("[]"));
		assertThrows(IllegalArgumentException.class, () -> MiniJson.parseObject("{\"a\""));
		assertThrows(IllegalArgumentException.class, () -> MiniJson.parseObject("{\"a\" \"b\"}"));
		assertThrows(IllegalArgumentException.class, () -> MiniJson.parseObject("{\"a\": \"unterminated}"));
		assertThrows(IllegalArgumentException.class, () -> MiniJson.parseObject("{\"a\": \"v\"} trailing"));
		assertThrows(IllegalArgumentException.class, () -> MiniJson.parseObject("{\"a\": \"bad\\q\"}"));
		assertThrows(IllegalArgumentException.class, () -> MiniJson.parseObject("{\"a\": \"\\u00ZZ\"}"));
		assertThrows(IllegalArgumentException.class, () -> MiniJson.parseObject("{\"a\": \"\\u0\"}"));
	}

	@Test
	void toleratesWhitespaceEverywhere() {
		Map<String, Object> obj = MiniJson.parseObject(
				" \n\t{ \"a\" :\n\"v\" ,\r\n \"b\" : [ \"x\" , \"y\" ] } \n");
		assertThat(obj.get("a")).isEqualTo("v");
		assertThat(obj.get("b")).isEqualTo(Arrays.asList("x", "y"));
	}
}
