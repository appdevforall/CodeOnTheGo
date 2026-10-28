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
 * LexerImpl matches on a window of the remaining template and grows it while the regex engine reports reading the window's end (ADFA-3305). Stock Pebble 4.1.1 matched on the whole remaining template, so these tests check that a window of any size gives stock's tokens, values, line numbers and errors. The oracle is stock's own tokens for a template per token kind, and the lex with an unbounded window, one match over the whole remainder as stock did, for every template in core.cgt and the hand-made ones. Two more bound the work the window exists to cut.
 */
public class LexerWindowTest {

	private static final int WHOLE = Integer.MAX_VALUE;

	private static final int[] WINDOWS = {1, 2, 3, 5, 7, 16, 64, LexerImpl.MATCH_WINDOW};

	// Built from char values: written into the source, the formatter reads them as line breaks.
	private static final String LINE_SEPARATOR = String.valueOf((char) 0x2028);

	private static final String NEXT_LINE = String.valueOf((char) 0x0085);

	private static final String PARAGRAPH_SEPARATOR = String.valueOf((char) 0x2029);

	private static final String LONG = repeat("lorem ipsum dolor ", 40);

	// U+1D465, MATHEMATICAL ITALIC SMALL X: a letter outside the BMP, so two chars in UTF-16.
	private static final String SUPPLEMENTARY_LETTER = new String(Character.toChars(0x1D465));

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
		templates.put("supplementary letters at window edges", supplementaryLettersAtWindowEdges());
		return templates;
	}

	private static String lex(String template, int window) {
		LexerImpl lexer = newLexer();
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

	// Lexes template at the production window, for the lexer's counters.
	private static LexerImpl lexedAtTheProductionWindow(String template) {
		LexerImpl lexer = newLexer();
		lexer.tokenize(new StringReader(template), "template");
		return lexer;
	}

	// About 800 KB, the size of the template stock Pebble took 93-114 s over on a Pixel 6 Pro, of tokens
	// thousands of characters long: text, an identifier, two strings, a comment and verbatim text.
	private static String longTokens() {
		String text = repeat("lorem ipsum dolor sit amet ", 80);
		String block = text + "{{ " + repeat("identifier_", 100) + " }}{{ \"" + text + "\" }}{{ '" + text + "' }}{# " + text
				+ " #}{% verbatim %}" + text + "{% endverbatim %}";
		return repeat(block, 800_000 / block.length() + 1);
	}

	private static LexerImpl newLexer() {
		PebbleEngine engine = new PebbleEngine.Builder().build();
		ExtensionRegistry extensions = engine.getExtensionRegistry();
		return new LexerImpl(engine.getSyntax(), extensions.getUnaryOperators().values(), extensions.getBinaryOperators().values());
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

	// A template per token kind, through every one of LexerImpl's 18 match sites, and as stock Pebble 4.1.1
	// (io.pebbletemplates:pebble:4.1.1, which has no window) lexed it. These pin the whole-remainder lex the
	// other tests compare windows with, so a call site broken alike at every window still fails.
	// About 100 KB of rows of short tokens, like an HTML page's.
	private static String shortTokens() {
		String row = "<li class=\"row\">{{ item.name | upper }}</li>{% if item.count > 1 %}<b>{{ item.count + 1 }}</b>{% endif %}\n";
		return repeat(row, 100_000 / row.length() + 1);
	}

	private static Map<String, String> stockTokens() {
		Map<String, String> tokens = new LinkedHashMap<>();
		tokens.put("", "EOF null @1\n");
		tokens.put("plain text only", "TEXT plain text only @1\n"
				+ "EOF null @1\n");
		tokens.put("a {{ b }} c", "TEXT a  @1\n"
				+ "PRINT_START null @1\n"
				+ "NAME b @1\n"
				+ "PRINT_END }} @1\n"
				+ "TEXT  c @1\n"
				+ "EOF null @1\n");
		tokens.put("{% if x > 1 %}y{% elseif x < 0 %}z{% else %}w{% endif %}", "EXECUTE_START null @1\n"
				+ "NAME if @1\n"
				+ "NAME x @1\n"
				+ "OPERATOR > @1\n"
				+ "NUMBER 1 @1\n"
				+ "EXECUTE_END %} @1\n"
				+ "TEXT y @1\n"
				+ "EXECUTE_START null @1\n"
				+ "NAME elseif @1\n"
				+ "NAME x @1\n"
				+ "OPERATOR < @1\n"
				+ "NUMBER 0 @1\n"
				+ "EXECUTE_END %} @1\n"
				+ "TEXT z @1\n"
				+ "EXECUTE_START null @1\n"
				+ "NAME else @1\n"
				+ "EXECUTE_END %} @1\n"
				+ "TEXT w @1\n"
				+ "EXECUTE_START null @1\n"
				+ "NAME endif @1\n"
				+ "EXECUTE_END %} @1\n"
				+ "EOF null @1\n");
		tokens.put("{{ a is not null and b or c }}{{ organization }}{{ notable }}{{ isx }}{{ x in y }}{{ -a + +b }}", "PRINT_START null @1\n"
				+ "NAME a @1\n"
				+ "OPERATOR is not @1\n"
				+ "NAME null @1\n"
				+ "OPERATOR and @1\n"
				+ "NAME b @1\n"
				+ "OPERATOR or @1\n"
				+ "NAME c @1\n"
				+ "PRINT_END }} @1\n"
				+ "PRINT_START null @1\n"
				+ "NAME organization @1\n"
				+ "PRINT_END }} @1\n"
				+ "PRINT_START null @1\n"
				+ "NAME notable @1\n"
				+ "PRINT_END }} @1\n"
				+ "PRINT_START null @1\n"
				+ "NAME isx @1\n"
				+ "PRINT_END }} @1\n"
				+ "PRINT_START null @1\n"
				+ "NAME x @1\n"
				+ "NAME in @1\n"
				+ "NAME y @1\n"
				+ "PRINT_END }} @1\n"
				+ "PRINT_START null @1\n"
				+ "OPERATOR - @1\n"
				+ "NAME a @1\n"
				+ "OPERATOR + @1\n"
				+ "OPERATOR + @1\n"
				+ "NAME b @1\n"
				+ "PRINT_END }} @1\n"
				+ "EOF null @1\n");
		tokens.put("{{ a == b != c <= d >= e < f > g }}{{ a ~ b }}{{ a contains b }}{{ 1..3 }}{{ a ? b : c }}{{ a ?: b }}", "PRINT_START null @1\n"
				+ "NAME a @1\n"
				+ "OPERATOR == @1\n"
				+ "NAME b @1\n"
				+ "OPERATOR != @1\n"
				+ "NAME c @1\n"
				+ "OPERATOR <= @1\n"
				+ "NAME d @1\n"
				+ "OPERATOR >= @1\n"
				+ "NAME e @1\n"
				+ "OPERATOR < @1\n"
				+ "NAME f @1\n"
				+ "OPERATOR > @1\n"
				+ "NAME g @1\n"
				+ "PRINT_END }} @1\n"
				+ "PRINT_START null @1\n"
				+ "NAME a @1\n"
				+ "OPERATOR ~ @1\n"
				+ "NAME b @1\n"
				+ "PRINT_END }} @1\n"
				+ "PRINT_START null @1\n"
				+ "NAME a @1\n"
				+ "OPERATOR contains @1\n"
				+ "NAME b @1\n"
				+ "PRINT_END }} @1\n"
				+ "PRINT_START null @1\n"
				+ "NUMBER 1 @1\n"
				+ "OPERATOR .. @1\n"
				+ "NUMBER 3 @1\n"
				+ "PRINT_END }} @1\n"
				+ "PRINT_START null @1\n"
				+ "NAME a @1\n"
				+ "PUNCTUATION ? @1\n"
				+ "NAME b @1\n"
				+ "PUNCTUATION : @1\n"
				+ "NAME c @1\n"
				+ "PRINT_END }} @1\n"
				+ "PRINT_START null @1\n"
				+ "NAME a @1\n"
				+ "PUNCTUATION ? @1\n"
				+ "PUNCTUATION : @1\n"
				+ "NAME b @1\n"
				+ "PRINT_END }} @1\n"
				+ "EOF null @1\n");
		tokens.put("{{ 123L + 4.5 * 6 / 7 % 8 }}{{ 1234567890123456789L }}{{ 0.5 }}", "PRINT_START null @1\n"
				+ "LONG 123 @1\n"
				+ "OPERATOR + @1\n"
				+ "NUMBER 4.5 @1\n"
				+ "OPERATOR * @1\n"
				+ "NUMBER 6 @1\n"
				+ "OPERATOR / @1\n"
				+ "NUMBER 7 @1\n"
				+ "OPERATOR % @1\n"
				+ "NUMBER 8 @1\n"
				+ "PRINT_END }} @1\n"
				+ "PRINT_START null @1\n"
				+ "LONG 1234567890123456789 @1\n"
				+ "PRINT_END }} @1\n"
				+ "PRINT_START null @1\n"
				+ "NUMBER 0.5 @1\n"
				+ "PRINT_END }} @1\n"
				+ "EOF null @1\n");
		tokens.put("{{ 'single' ~ \"double\" ~ 'it\\'s' ~ \"say \\\"hi\\\"\" }}", "PRINT_START null @1\n"
				+ "STRING single @1\n"
				+ "OPERATOR ~ @1\n"
				+ "STRING double @1\n"
				+ "OPERATOR ~ @1\n"
				+ "STRING it's @1\n"
				+ "OPERATOR ~ @1\n"
				+ "STRING say \"hi\" @1\n"
				+ "PRINT_END }} @1\n"
				+ "EOF null @1\n");
		tokens.put("{{ \"x #{ y + 1 } z #{ \"#{ w }\" } end\" }}{{ \"#{a}#{b}\" }}", "PRINT_START null @1\n"
				+ "STRING x  @1\n"
				+ "STRING_INTERPOLATION_START null @1\n"
				+ "NAME y @1\n"
				+ "OPERATOR + @1\n"
				+ "NUMBER 1 @1\n"
				+ "STRING_INTERPOLATION_END null @1\n"
				+ "STRING  z  @1\n"
				+ "STRING_INTERPOLATION_START null @1\n"
				+ "STRING_INTERPOLATION_START null @1\n"
				+ "NAME w @1\n"
				+ "STRING_INTERPOLATION_END null @1\n"
				+ "STRING_INTERPOLATION_END null @1\n"
				+ "STRING  end @1\n"
				+ "PRINT_END }} @1\n"
				+ "PRINT_START null @1\n"
				+ "STRING_INTERPOLATION_START null @1\n"
				+ "NAME a @1\n"
				+ "STRING_INTERPOLATION_END null @1\n"
				+ "STRING_INTERPOLATION_START null @1\n"
				+ "NAME b @1\n"
				+ "STRING_INTERPOLATION_END null @1\n"
				+ "PRINT_END }} @1\n"
				+ "EOF null @1\n");
		tokens.put("{{ f([1, 2], {'k': (3)}) | upper }}{{ a.b[c] }}{{ a|default('x') }}", "PRINT_START null @1\n"
				+ "NAME f @1\n"
				+ "PUNCTUATION ( @1\n"
				+ "PUNCTUATION [ @1\n"
				+ "NUMBER 1 @1\n"
				+ "PUNCTUATION , @1\n"
				+ "NUMBER 2 @1\n"
				+ "PUNCTUATION ] @1\n"
				+ "PUNCTUATION , @1\n"
				+ "PUNCTUATION { @1\n"
				+ "STRING k @1\n"
				+ "PUNCTUATION : @1\n"
				+ "PUNCTUATION ( @1\n"
				+ "NUMBER 3 @1\n"
				+ "PUNCTUATION ) @1\n"
				+ "PUNCTUATION } @1\n"
				+ "PUNCTUATION ) @1\n"
				+ "OPERATOR | @1\n"
				+ "NAME upper @1\n"
				+ "PRINT_END }} @1\n"
				+ "PRINT_START null @1\n"
				+ "NAME a @1\n"
				+ "PUNCTUATION . @1\n"
				+ "NAME b @1\n"
				+ "PUNCTUATION [ @1\n"
				+ "NAME c @1\n"
				+ "PUNCTUATION ] @1\n"
				+ "PRINT_END }} @1\n"
				+ "PRINT_START null @1\n"
				+ "NAME a @1\n"
				+ "OPERATOR | @1\n"
				+ "NAME default @1\n"
				+ "PUNCTUATION ( @1\n"
				+ "STRING x @1\n"
				+ "PUNCTUATION ) @1\n"
				+ "PRINT_END }} @1\n"
				+ "EOF null @1\n");
		tokens.put("a {# comment #} b {#- trimmed -#} c {# multi\nline #} d", "TEXT a  @1\n"
				+ "TEXT  b @1\n"
				+ "TEXT c  @1\n"
				+ "TEXT  d @2\n"
				+ "EOF null @2\n");
		tokens.put("a {% verbatim %}{{ x }}{% if %}{% endverbatim %} b {%- verbatim -%} c {%- endverbatim -%} d", "TEXT a  @1\n"
				+ "TEXT {{ x }}{% if %} @1\n"
				+ "TEXT  b @1\n"
				+ "TEXT c @1\n"
				+ "TEXT d @1\n"
				+ "EOF null @1\n");
		tokens.put("  a  {{- x -}}  b  {%- if y -%}  c  {%- endif -%}  d  ", "TEXT   a @1\n"
				+ "PRINT_START null @1\n"
				+ "NAME x @1\n"
				+ "PRINT_END }} @1\n"
				+ "TEXT b @1\n"
				+ "EXECUTE_START null @1\n"
				+ "NAME if @1\n"
				+ "NAME y @1\n"
				+ "EXECUTE_END %} @1\n"
				+ "TEXT c @1\n"
				+ "EXECUTE_START null @1\n"
				+ "NAME endif @1\n"
				+ "EXECUTE_END %} @1\n"
				+ "TEXT d   @1\n"
				+ "EOF null @1\n");
		tokens.put("a\nb\r\n{{ x\n+ y }}\r" + LINE_SEPARATOR + "c" + NEXT_LINE + "{# d\r\n #}" + PARAGRAPH_SEPARATOR + "e{{ 'q\nr' }}", "TEXT a\n"
				+ "b\r\n"
				+ " @3\n"
				+ "PRINT_START null @3\n"
				+ "NAME x @3\n"
				+ "OPERATOR + @4\n"
				+ "NAME y @4\n"
				+ "PRINT_END }} @4\n"
				+ "TEXT " + LINE_SEPARATOR + "c" + NEXT_LINE + " @7\n"
				+ "TEXT e @9\n"
				+ "PRINT_START null @9\n"
				+ "STRING q\n"
				+ "r @10\n"
				+ "PRINT_END }} @10\n"
				+ "EOF null @10\n");
		tokens.put("{{ a" + SUPPLEMENTARY_LETTER + "b }}", "PRINT_START null @1\n"
				+ "NAME a" + SUPPLEMENTARY_LETTER + "b @1\n"
				+ "PRINT_END }} @1\n"
				+ "EOF null @1\n");
		tokens.put("{% for i in range(0, 10) %}{{ i }}{% endfor %}{% set x = [1, 2] %}{% macro m(a, b='c') %}{% endmacro %}", "EXECUTE_START null @1\n"
				+ "NAME for @1\n"
				+ "NAME i @1\n"
				+ "NAME in @1\n"
				+ "NAME range @1\n"
				+ "PUNCTUATION ( @1\n"
				+ "NUMBER 0 @1\n"
				+ "PUNCTUATION , @1\n"
				+ "NUMBER 10 @1\n"
				+ "PUNCTUATION ) @1\n"
				+ "EXECUTE_END %} @1\n"
				+ "PRINT_START null @1\n"
				+ "NAME i @1\n"
				+ "PRINT_END }} @1\n"
				+ "EXECUTE_START null @1\n"
				+ "NAME endfor @1\n"
				+ "EXECUTE_END %} @1\n"
				+ "EXECUTE_START null @1\n"
				+ "NAME set @1\n"
				+ "NAME x @1\n"
				+ "PUNCTUATION = @1\n"
				+ "PUNCTUATION [ @1\n"
				+ "NUMBER 1 @1\n"
				+ "PUNCTUATION , @1\n"
				+ "NUMBER 2 @1\n"
				+ "PUNCTUATION ] @1\n"
				+ "EXECUTE_END %} @1\n"
				+ "EXECUTE_START null @1\n"
				+ "NAME macro @1\n"
				+ "NAME m @1\n"
				+ "PUNCTUATION ( @1\n"
				+ "NAME a @1\n"
				+ "PUNCTUATION , @1\n"
				+ "NAME b @1\n"
				+ "PUNCTUATION = @1\n"
				+ "STRING c @1\n"
				+ "PUNCTUATION ) @1\n"
				+ "EXECUTE_END %} @1\n"
				+ "EXECUTE_START null @1\n"
				+ "NAME endmacro @1\n"
				+ "EXECUTE_END %} @1\n"
				+ "EOF null @1\n");
		tokens.put("text {# unclosed comment", "error ParserException: Unclosed comment. (template:1)");
		tokens.put("{{ \"unclosed string }}", "error ParserException: Unclosed \"\"\" (template:1)");
		tokens.put("{% verbatim %}never closed", "error ParserException: Unclosed verbatim tag. (template:1)");
		tokens.put("{{ a ` }}", "error ParserException: Unexpected character [`] (template:1)");
		tokens.put("{{ (a + b }}", "error ParserException: Unclosed \")\" (template:1)");
		tokens.put("{{ a ]", "error ParserException: Unexpected \"]\" (template:1)");
		tokens.put("{{ a", "PRINT_START null @1\n"
				+ "NAME a @1\n"
				+ "EOF null @1\n");
		tokens.put("{{ \"#{ a \" }}", "error ParserException: Unclosed \"\"\" (template:1)");
		return tokens;
	}

	// For each window, identifiers whose supplementary letter straddles its first three edges.
	private static String supplementaryLettersAtWindowEdges() {
		StringBuilder template = new StringBuilder();
		for (int window : WINDOWS) {
			for (int edge = window; edge <= 4 * window; edge *= 2) {
				template.append("{{ ").append(repeat("a", edge - 1)).append(SUPPLEMENTARY_LETTER).append("b }}");
			}
		}
		return template.toString();
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
	public void aShortTokenIsMatchedInOneWindow() {
		// A window too small for a typical token makes most matches read its end and run again on a longer one: another
		// Matcher, and on Android two more copies. A 1-character window needs about 2 per match here.
		LexerImpl lexer = lexedAtTheProductionWindow(shortTokens());
		assertTrue(lexer.windows + " windows for " + lexer.matches + " matches", lexer.windows <= 1.1 * lexer.matches);
	}

	@Test
	public void aWindowNeverEndsBetweenTheHalvesOfASurrogatePair() {
		// Both regex engines read a lone high surrogate at the window's end as a whole code point without
		// reporting the end, so a split pair would end the identifier just before its supplementary letter.
		String small = lex("{{ a" + SUPPLEMENTARY_LETTER + "b }}", 2);
		assertTrue(small, small.contains("NAME a" + SUPPLEMENTARY_LETTER + "b @1\n"));
		String identifier = repeat("a", LexerImpl.MATCH_WINDOW - 1) + SUPPLEMENTARY_LETTER + "b";
		String production = lex("{{ " + identifier + " }}", LexerImpl.MATCH_WINDOW);
		assertTrue(production, production.contains("NAME " + identifier + " @1\n"));
	}

	@Test
	public void eachTokenKindLexesAsStockPebble411DidAtEveryWindow() {
		for (Map.Entry<String, String> stock : stockTokens().entrySet()) {
			assertEquals(stock.getKey() + ", whole remainder", stock.getValue(), lex(stock.getKey(), WHOLE));
			for (int window : WINDOWS) {
				assertEquals(stock.getKey() + ", window " + window, stock.getValue(), lex(stock.getKey(), window));
			}
		}
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

	@Test
	public void theEngineIsHandedAFewCharactersPerTemplateCharacter() {
		// Stock Pebble handed the regex engine the whole rest of the template at every match: on this template over a
		// thousand characters per template character, each copied twice on Android. Doubling the window hands it about
		// 4; growing it one character at a time, about as many as stock.
		String template = longTokens();
		LexerImpl lexer = lexedAtTheProductionWindow(template);
		assertTrue(
				lexer.windowChars + " characters for a template of " + template.length(),
				lexer.windowChars <= 8L * template.length());
	}
}
