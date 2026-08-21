package com.itsaky.androidide.quickbuild.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the runtime's small JSON schemas: deploy metadata, build status and the persisted-payload metadata.
 *
 * Hand-rolled because this AAR carries zero dependencies and android.jar's org.json is a stub in JVM unit tests. It keeps only strings and arrays of strings, but consumes nested objects, numbers, booleans and nulls so a document with extra fields still parses. Malformed input throws {@link IllegalArgumentException}, which callers treat as a bad payload.
 *
 * Every rejection must be that exception and nothing else: the input crosses binder from CoGo, so a document deep enough to exhaust the stack would raise an Error no caller catches, and a literal skipped without checking its shape would leave a key silently missing from the result.
 */
final class MiniJson {

	/**
	 * Nesting the parser will descend, since each level costs a Java frame.
	 *
	 * Well above the two shallow schemas this reads, and far below any stack the runtime has.
	 */
	private static final int MAX_DEPTH = 64;

	/**
	 * Parses {@code json} as a top-level object.
	 *
	 * String values map to {@link String} and arrays keep only their string elements as {@code List<String>}; every other value is consumed and dropped.
	 *
	 * @param json
	 *            the whole document, which must be one object with nothing after it
	 * @return a mutable insertion-ordered map holding the kept values; keys whose value was dropped are absent entirely
	 * @throws IllegalArgumentException
	 *             when {@code json} is null, is not a well-formed object, or carries trailing content
	 */
	static Map<String, Object> parseObject(String json) {
		if (json == null) {
			throw new IllegalArgumentException("json is null");
		}
		MiniJson parser = new MiniJson(json);
		parser.skipWhitespace();
		parser.enter();
		Map<String, Object> result = parser.readObject();
		parser.depth--;
		parser.skipWhitespace();
		if (parser.pos != json.length()) {
			throw parser.fail("trailing content");
		}
		return result;
	}

	/** The document being read; a parser instance is single-use. */
	private final String src;

	/** Read cursor into {@link #src}, in chars. */
	private int pos;

	/** Object and array levels currently open, capped by {@link #MAX_DEPTH}. */
	private int depth;

	/**
	 * @param src
	 *            the document to read; never null, since {@link #parseObject} checks first
	 */
	private MiniJson(String src) {
		this.src = src;
	}

	/**
	 * Opens one nesting level, refusing to descend past {@link #MAX_DEPTH}.
	 *
	 * The recursion is one Java frame per level, so an unbounded descent raises StackOverflowError - an Error, not the IllegalArgumentException this class contracts to throw and callers catch.
	 *
	 * @throws IllegalArgumentException
	 *             when the document nests deeper than the cap
	 */
	private void enter() {
		if (++depth > MAX_DEPTH) {
			throw fail("nesting deeper than " + MAX_DEPTH + " levels");
		}
	}

	/**
	 * Consumes the next char, requiring it to be {@code expected}.
	 *
	 * @param expected
	 *            the char the grammar demands here
	 * @throws IllegalArgumentException
	 *             when the next char differs, with the cursor left on it so the message points at the right offset
	 */
	private void expect(char expected) {
		if (read() != expected) {
			pos--;
			throw fail("expected '" + expected + "'");
		}
	}

	/**
	 * Builds the parse failure, stamped with the current offset.
	 *
	 * @param message
	 *            what the grammar expected at this point
	 * @return the exception to throw; this method never throws it itself
	 */
	private IllegalArgumentException fail(String message) {
		return new IllegalArgumentException("malformed json at offset " + pos + ": " + message);
	}

	/**
	 * @param c
	 *            the char to test
	 * @return true for ASCII 0-9 only; Character.isDigit would also accept other scripts' digits, which JSON does not
	 */
	private boolean isDigit(char c) {
		return c >= '0' && c <= '9';
	}

	/**
	 * Whether {@code token} is a JSON number.
	 *
	 * Hand-rolled rather than delegating to Double.parseDouble, which also accepts hex, {@code NaN}, {@code Infinity}, a trailing {@code d}/{@code f} and surrounding whitespace - none of which is JSON.
	 *
	 * @param token
	 *            the candidate token, never empty
	 * @return true when it matches {@code -?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?}
	 */
	private boolean isNumber(String token) {
		int i = 0;
		int length = token.length();
		if (token.charAt(i) == '-') {
			i++;
		}
		if (i >= length) {
			return false;
		}
		if (token.charAt(i) == '0') {
			i++;
		} else {
			int digits = i;
			while (i < length && isDigit(token.charAt(i))) {
				i++;
			}
			if (i == digits) {
				return false;
			}
		}
		if (i < length && token.charAt(i) == '.') {
			i++;
			int digits = i;
			while (i < length && isDigit(token.charAt(i))) {
				i++;
			}
			if (i == digits) {
				return false;
			}
		}
		if (i < length && (token.charAt(i) == 'e' || token.charAt(i) == 'E')) {
			i++;
			if (i < length && (token.charAt(i) == '+' || token.charAt(i) == '-')) {
				i++;
			}
			int digits = i;
			while (i < length && isDigit(token.charAt(i))) {
				i++;
			}
			if (i == digits) {
				return false;
			}
		}
		return i == length;
	}

	/**
	 * The char at the cursor, without consuming it.
	 *
	 * @return the current char
	 * @throws IllegalArgumentException
	 *             at end of input, so no caller has to bounds-check
	 */
	private char peek() {
		if (pos >= src.length()) {
			throw fail("unexpected end of input");
		}
		return src.charAt(pos);
	}

	/**
	 * The char at the cursor, consuming it.
	 *
	 * @return the char just consumed
	 * @throws IllegalArgumentException
	 *             at end of input
	 */
	private char read() {
		char c = peek();
		pos++;
		return c;
	}

	/**
	 * Reads an array, keeping only its string elements.
	 *
	 * No schema reads the elements, but an array must still survive as a non-null value: {@code PayloadPersistence.namedFile} tells a corrupt store from an absent kind by the value's type, and a dropped array would read as "this kind was never persisted".
	 *
	 * @return the string elements in document order, empty when the array held none
	 * @throws IllegalArgumentException
	 *             on a malformed array or at end of input
	 */
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

	/**
	 * Reads an object, keeping only the entries whose value survived {@link #readValue}.
	 *
	 * @return the kept entries in document order; a duplicate key keeps the last value
	 * @throws IllegalArgumentException
	 *             on a malformed object or at end of input
	 */
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

	/**
	 * Reads a quoted string, decoding the standard JSON escapes.
	 *
	 * @return the decoded string, without its quotes
	 * @throws IllegalArgumentException
	 *             on an unknown escape, an unterminated string, or end of input
	 */
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

	/**
	 * Decodes the four hex digits of a {@code \\u} escape, the cursor being just past the u.
	 *
	 * @return the decoded char; a surrogate is returned as-is, so a pair decodes across two calls
	 * @throws IllegalArgumentException
	 *             when fewer than four chars remain or they are not four hex digits
	 */
	private char readUnicodeEscape() {
		if (pos + 4 > src.length()) {
			throw fail("truncated unicode escape");
		}
		String hex = src.substring(pos, pos + 4);
		int decoded = 0;
		for (int i = 0; i < 4; i++) {
			// Integer.parseInt(hex, 16) accepts a leading sign, so an escape whose four
			// chars start with + or - would decode instead of being rejected. Digits only.
			int digit = Character.digit(hex.charAt(i), 16);
			if (digit < 0) {
				throw fail("bad unicode escape '\\u" + hex + "'");
			}
			decoded = (decoded << 4) | digit;
		}
		pos += 4;
		return (char) decoded;
	}

	/**
	 * Reads any value, keeping the two types this parser supports.
	 *
	 * @return a String, a List of strings, or null for value types we drop; null therefore means "consumed and dropped", never "the JSON literal null"
	 * @throws IllegalArgumentException
	 *             on malformed input or at end of input
	 */
	private Object readValue() {
		char c = peek();
		if (c == '"') {
			return readString();
		}
		if (c == '[') {
			enter();
			List<String> array = readArray();
			depth--;
			return array;
		}
		if (c == '{') {
			enter();
			readObject();
			depth--;
			return null;
		}
		skipLiteral();
		return null;
	}

	/**
	 * Consumes a number / true / false / null token, dropping its value but checking its shape.
	 *
	 * Stops at the first structural char or whitespace. The shape check is what keeps a dropped value distinguishable from a rejected document: without it {@code {"b":qqq}} parses cleanly with {@code b} simply absent, which a caller reads as "the host did not send b".
	 *
	 * @throws IllegalArgumentException
	 *             when the cursor sits on a structural char, i.e. there is no token here at all, or when the token is not one of the four JSON literal forms
	 */
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
		String token = src.substring(start, pos);
		if (!"true".equals(token) && !"false".equals(token) && !"null".equals(token)
				&& !isNumber(token)) {
			pos = start;
			throw fail("not a json literal: '" + token + "'");
		}
	}

	/** Advances the cursor past any whitespace; safe at end of input, where it does nothing. */
	private void skipWhitespace() {
		while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
			pos++;
		}
	}
}
