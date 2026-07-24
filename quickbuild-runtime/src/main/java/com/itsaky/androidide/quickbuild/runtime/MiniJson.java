package com.itsaky.androidide.quickbuild.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON object reader for the runtime's two tiny schemas (deploy metadata, component map). Hand-rolled because this AAR must carry ZERO dependencies and android.jar's org.json is a stub in JVM unit tests. It reads exactly what the schemas need - a top-level object whose interesting values are strings or arrays of strings - while still consuming (and dropping) nested objects, numbers, booleans and nulls so a well-formed document with extra fields parses cleanly.
 *
 * Malformed input throws {@link IllegalArgumentException}; callers treat that as a bad payload, never as a crash.
 */
final class MiniJson {

	/**
	 * Parses {@code json} as an object. Values that are strings map to {@link String}, arrays keep only their string elements as {@code List<String>}; everything else is consumed and dropped.
	 */
	static Map<String, Object> parseObject(String json) {
		if (json == null) {
			throw new IllegalArgumentException("json is null");
		}
		MiniJson parser = new MiniJson(json);
		parser.skipWhitespace();
		Map<String, Object> result = parser.readObject();
		parser.skipWhitespace();
		if (parser.pos != json.length()) {
			throw parser.fail("trailing content");
		}
		return result;
	}

	private final String src;

	private int pos;

	private MiniJson(String src) {
		this.src = src;
	}

	private void expect(char expected) {
		if (read() != expected) {
			pos--;
			throw fail("expected '" + expected + "'");
		}
	}

	private IllegalArgumentException fail(String message) {
		return new IllegalArgumentException("malformed json at offset " + pos + ": " + message);
	}

	private char peek() {
		if (pos >= src.length()) {
			throw fail("unexpected end of input");
		}
		return src.charAt(pos);
	}

	private char read() {
		char c = peek();
		pos++;
		return c;
	}

	private List<String> readArray() {
		expect('[');
		List<String> out = new ArrayList<String>();
		skipWhitespace();
		if (peek() == ']') {
			pos++;
			return out;
		}
		while (true) {
			skipWhitespace();
			Object value = readValue();
			if (value instanceof String) {
				out.add((String) value);
			}
			skipWhitespace();
			char c = read();
			if (c == ']') {
				return out;
			}
			if (c != ',') {
				throw fail("expected ',' or ']'");
			}
		}
	}

	private Map<String, Object> readObject() {
		expect('{');
		Map<String, Object> out = new LinkedHashMap<String, Object>();
		skipWhitespace();
		if (peek() == '}') {
			pos++;
			return out;
		}
		while (true) {
			skipWhitespace();
			String key = readString();
			skipWhitespace();
			expect(':');
			skipWhitespace();
			Object value = readValue();
			if (value != null) {
				out.put(key, value);
			}
			skipWhitespace();
			char c = read();
			if (c == '}') {
				return out;
			}
			if (c != ',') {
				throw fail("expected ',' or '}'");
			}
		}
	}

	private String readString() {
		expect('"');
		StringBuilder sb = new StringBuilder();
		while (true) {
			char c = read();
			if (c == '"') {
				return sb.toString();
			}
			if (c != '\\') {
				sb.append(c);
				continue;
			}
			char escape = read();
			switch (escape) {
			case '"':
				sb.append('"');
				break;
			case '\\':
				sb.append('\\');
				break;
			case '/':
				sb.append('/');
				break;
			case 'b':
				sb.append('\b');
				break;
			case 'f':
				sb.append('\f');
				break;
			case 'n':
				sb.append('\n');
				break;
			case 'r':
				sb.append('\r');
				break;
			case 't':
				sb.append('\t');
				break;
			case 'u':
				sb.append(readUnicodeEscape());
				break;
			default:
				throw fail("bad escape '\\" + escape + "'");
			}
		}
	}

	private char readUnicodeEscape() {
		if (pos + 4 > src.length()) {
			throw fail("truncated unicode escape");
		}
		String hex = src.substring(pos, pos + 4);
		try {
			char decoded = (char) Integer.parseInt(hex, 16);
			pos += 4;
			return decoded;
		} catch (NumberFormatException e) {
			throw fail("bad unicode escape '\\u" + hex + "'");
		}
	}

	/** Returns a String, a List of strings, or null for value types we drop. */
	private Object readValue() {
		char c = peek();
		if (c == '"') {
			return readString();
		}
		if (c == '[') {
			return readArray();
		}
		if (c == '{') {
			readObject();
			return null;
		}
		skipLiteral();
		return null;
	}

	/** Consumes a number / true / false / null token without interpreting it. */
	private void skipLiteral() {
		int start = pos;
		while (pos < src.length()) {
			char c = src.charAt(pos);
			if (c == ',' || c == '}' || c == ']' || Character.isWhitespace(c)) {
				break;
			}
			pos++;
		}
		if (pos == start) {
			throw fail("unexpected character '" + src.charAt(pos) + "'");
		}
	}

	private void skipWhitespace() {
		while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
			pos++;
		}
	}
}
