package io.pebbletemplates.pebble.lexer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.pebbletemplates.pebble.PebbleEngine;
import io.pebbletemplates.pebble.extension.ExtensionRegistry;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.Test;

/**
 * LexerImpl matches on a window of the remaining template and grows it while the regex engine reports reaching its end (ADFA-3305). These tests pin that a window of any size lexes exactly as the whole remaining template does, which is what the lexer did before: same tokens, values and line numbers, and the same errors.
 */
public class LexerWindowTest {

	private static final int WHOLE = Integer.MAX_VALUE;

	private static final int[] WINDOWS = {1, 2, 3, 5, 7, 16, 64, LexerImpl.MATCH_WINDOW};

	// Built from char values: written into the source, the formatter reads them as line breaks.
	private static final String LINE_SEPARATOR = String.valueOf((char) 0x2028);

	private static final String NEXT_LINE = String.valueOf((char) 0x0085);

	private static final String PARAGRAPH_SEPARATOR = String.valueOf((char) 0x2029);

	private static final String LONG = repeat("lorem ipsum dolor ", 40);

	private static void assertSameAtEveryWindow(String name, String template) {
		String whole = lex(template, WHOLE);
		for (int window : WINDOWS) {
			assertEquals(name + ", window " + window, whole, lex(template, window));
		}
	}

	// A print tag at every offset from the start, so that some delimiter straddles each window boundary.
	private static String delimitersAtEveryOffset() {
		StringBuilder template = new StringBuilder();
		for (int i = 0; i < 300; i++) {
			template.append(repeat("x", i % 17)).append("{{ v").append(i).append(" }}");
		}
		return template.toString();
	}

	private static Map<String, String> handMadeTemplates() {
		Map<String, String> templates = new LinkedHashMap<>();
		templates.put("plain text, no tags", LONG);
		templates.put("text and tags", LONG + "{{ a }}" + LONG + "{% if b %}" + LONG + "{% endif %}" + LONG);
		templates.put("every delimiter offset", delimitersAtEveryOffset());
		templates.put("long double-quoted string", "{{ \"" + LONG + "\" }}");
		templates.put("long single-quoted string", "{{ '" + LONG + "' }}");
		templates.put("escaped quotes", "{{ \"a \\\" " + LONG + "\" ~ 'b \\' " + LONG + "' }}");
		templates.put("interpolation", "{{ \"" + LONG + " #{ x + 1 } " + LONG + " #{ \"#{ y }\" }\" }}");
		templates.put("long comment", "a {# " + LONG + " #} b {#- " + LONG + " -#} c");
		templates.put(
				"verbatim",
				"a {% verbatim %}" + LONG + "{{ not lexed }}" + LONG + "{% endverbatim %} b {%- verbatim -%} "
						+ LONG + " {%- endverbatim -%} c");
		templates.put("operators and lookahead", "{{ a is not null and b or c }}{{ organization }}{{ notable }}{{ x in y }}");
		templates.put("numbers", "{{ 123L + 4.5 + 6 }}{{ 1234567890123456789L }}");
		templates.put("whitespace control", "  a  {{- x -}}  b  {%- if y -%}  c  {%- endif -%}  d  ");
		templates.put("line breaks", "a\r\nb\n{{ x\r\n+" + LINE_SEPARATOR + "y }}" + PARAGRAPH_SEPARATOR + "c" + NEXT_LINE + "{# d\r\n #}\re");
		templates.put("nested brackets", "{{ f([1, 2, {'k': (3 + 4)}], g(h(i))) }}");
		templates.put("tags only", "{% for i in range(0, 10) %}{{ i }}{% endfor %}");
		templates.put("ends inside text", "{{ a }}" + LONG.substring(0, 37));
		return templates;
	}

	private static String lex(String template, int window) {
		PebbleEngine engine = new PebbleEngine.Builder().build();
		ExtensionRegistry extensions = engine.getExtensionRegistry();
		LexerImpl lexer = new LexerImpl(
				engine.getSyntax(),
				extensions.getUnaryOperators().values(),
				extensions.getBinaryOperators().values());
		lexer.matchWindow = window;
		StringBuilder out = new StringBuilder();
		try {
			for (Token token : lexer.tokenize(new StringReader(template), "template").getTokens()) {
				out.append(token.getType()).append(' ').append(token.getValue()).append(" @").append(token.getLineNumber()).append('\n');
			}
		} catch (RuntimeException e) {
			out.append("error ").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage());
		}
		return out.toString();
	}

	private static File projectRoot() {
		File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
		while (dir != null && !new File(dir, ".androidide_root").exists()) {
			dir = dir.getParentFile();
		}
		if (dir == null) {
			throw new IllegalStateException("no .androidide_root above " + System.getProperty("user.dir"));
		}
		return dir;
	}

	private static Map<String, String> projectTemplates() throws IOException {
		Map<String, String> templates = new LinkedHashMap<>();
		try (ZipFile zip = new ZipFile(new File(projectRoot(), "assets/core.cgt"))) {
			Enumeration<? extends ZipEntry> entries = zip.entries();
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				if (entry.getName().endsWith(".peb")) {
					templates.put(entry.getName(), new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8));
				}
			}
		}
		return templates;
	}

	private static String repeat(String text, int times) {
		List<String> parts = new ArrayList<>();
		for (int i = 0; i < times; i++) {
			parts.add(text);
		}
		return String.join("", parts);
	}

	@Test
	public void aMatchThatReachesTheWindowsEndIsRetriedOnALongerOne() {
		// "or" is an operator only when no identifier character follows; with a 2-character window the engine reaches
		// the end before it can tell, so the lexer has to look further to find the identifier "organization".
		String tokens = lex("{{ organization }}", 2);
		assertTrue(tokens, tokens.contains("NAME organization"));
		assertFalse(tokens, tokens.contains("OPERATOR or"));
	}

	@Test
	public void errorsAreTheSameAtEveryWindow() {
		Map<String, String> broken = new LinkedHashMap<>();
		broken.put("unclosed comment", "text {# " + LONG);
		broken.put("unclosed string", "{{ \"" + LONG + " }}");
		broken.put("unclosed verbatim", "{% verbatim %}" + LONG);
		broken.put("unexpected character", "{{ a " + LONG.replace(' ', '_') + " ` }}");
		broken.put("unclosed bracket", "{{ (a + b }}");
		for (Map.Entry<String, String> template : broken.entrySet()) {
			String whole = lex(template.getValue(), WHOLE);
			assertTrue(template.getKey() + " should fail to lex: " + whole, whole.startsWith("error"));
			assertSameAtEveryWindow(template.getKey(), template.getValue());
		}
	}

	@Test
	public void handMadeTemplatesLexTheSameAtEveryWindow() {
		for (Map.Entry<String, String> template : handMadeTemplates().entrySet()) {
			assertSameAtEveryWindow(template.getKey(), template.getValue());
		}
	}

	@Test
	public void theAppsProjectTemplatesLexTheSameAtEveryWindow() throws IOException {
		Map<String, String> templates = projectTemplates();
		assertTrue("expected the .peb templates of assets/core.cgt, found " + templates.size(), templates.size() > 20);
		for (Map.Entry<String, String> template : templates.entrySet()) {
			assertSameAtEveryWindow(template.getKey(), template.getValue());
		}
	}
}
