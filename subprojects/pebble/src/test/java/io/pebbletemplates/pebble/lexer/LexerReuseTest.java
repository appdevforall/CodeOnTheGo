package io.pebbletemplates.pebble.lexer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.pebbletemplates.pebble.PebbleEngine;
import io.pebbletemplates.pebble.extension.ExtensionRegistry;
import io.pebbletemplates.pebble.node.expression.OrExpression;
import io.pebbletemplates.pebble.operator.Associativity;
import io.pebbletemplates.pebble.operator.BinaryOperator;
import io.pebbletemplates.pebble.operator.BinaryOperatorImpl;
import io.pebbletemplates.pebble.operator.BinaryOperatorType;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/**
 * LexerImpl reuses its regex machinery (ADFA-3305): one Matcher per pattern, reset onto each window; one copy of each window for every pattern tried there; and one operator regex per set of operators, shared by every lexer. LexerWindowTest pins the tokens. These pin the reuse, which is what makes lexing cheap on Android, and that it never crosses templates, operator sets or threads.
 */
public class LexerReuseTest {

	private static final PebbleEngine ENGINE = new PebbleEngine.Builder().build();

	// The engine's operators plus "xor", a word operator the core extension does not define.
	private static List<BinaryOperator> withXor() {
		List<BinaryOperator> operators = new ArrayList<>(ENGINE.getExtensionRegistry().getBinaryOperators().values());
		operators.add(new BinaryOperatorImpl("xor", 10, OrExpression::new, BinaryOperatorType.NORMAL, Associativity.LEFT));
		return operators;
	}

	private static LexerImpl newLexer() {
		ExtensionRegistry extensions = ENGINE.getExtensionRegistry();
		return new LexerImpl(ENGINE.getSyntax(), extensions.getUnaryOperators().values(), extensions.getBinaryOperators().values());
	}

	private static LexerImpl newLexerWithXor() {
		return new LexerImpl(ENGINE.getSyntax(), ENGINE.getExtensionRegistry().getUnaryOperators().values(), withXor());
	}

	// The token dump LexerWindowTest compares: type, value and line of each token, or the error.
	private static String lex(LexerImpl lexer, String template) {
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

	// About 100 KB of rows of short tokens, like an HTML page's.
	private static String shortTokens() {
		String row = "<li class=\"row\">{{ item.name | upper }}</li>{% if item.count > 1 %}<b>{{ item.count + 1 }}</b>{% endif %}\n";
		return String.join("", Collections.nCopies(100_000 / row.length() + 1, row));
	}

	@Test
	public void eachPatternGetsOneMatcherPerLexer() {
		// LexerImpl matches about 15 patterns. A new Matcher per match made one for each of the 87,792 matches here.
		LexerImpl lexer = newLexer();
		lexer.tokenize(new StringReader(shortTokens()), "template");
		assertTrue(lexer.matches + " matches", lexer.matches > 10_000);
		assertTrue(lexer.matchersCreated + " matchers for " + lexer.matches + " matches", lexer.matchersCreated >= 1 && lexer.matchersCreated <= 20);
	}

	@Test
	public void thePatternsTriedAtOnePositionShareOneWindow() {
		// Most positions are tried against several patterns, such as a closing delimiter, then an operator, then a name:
		// 0.39 windows cut per window matched on here. A copy per match cut one for each.
		LexerImpl lexer = newLexer();
		lexer.tokenize(new StringReader(shortTokens()), "template");
		assertTrue(lexer.windowsCut + " windows cut for " + lexer.windows + " matched on", lexer.windowsCut >= 1 && lexer.windowsCut <= 0.5 * lexer.windows);
	}

	@Test
	public void aLexerUsedAgainCutsItsFirstWindowFromTheNewTemplate() {
		// The first template ends on a 7-character window, "abcdefg", and the second starts on one of the same length,
		// at the same remaining length. A window kept from the first template would hide the second's print tag.
		LexerImpl lexer = newLexer();
		lex(lexer, "{{ a }}abcdefg");
		String again = lex(lexer, "{{ b }}");
		assertEquals(lex(newLexer(), "{{ b }}"), again);
		assertTrue(again, again.contains("PRINT_START"));
	}

	@Test
	public void lexersWithTheSameOperatorsShareOneOperatorRegex() {
		LexerImpl first = newLexer();
		LexerImpl second = newLexer();
		lex(first, "{{ a }}");
		lex(second, "{{ b }}");
		assertSame(first.regexOperators, second.regexOperators);
	}

	@Test
	public void lexersWithOtherOperatorsGetTheirOwnOperatorRegex() {
		// In either order: whichever set of operators reaches a shared regex first, the other must still get its own.
		for (int round = 0; round < 2; round++) {
			LexerImpl plain = newLexer();
			LexerImpl xor = newLexerWithXor();
			String plainTokens, xorTokens;
			if (round == 0) {
				plainTokens = lex(plain, "{{ a xor b }}");
				xorTokens = lex(xor, "{{ a xor b }}");
			} else {
				xorTokens = lex(xor, "{{ a xor b }}");
				plainTokens = lex(plain, "{{ a xor b }}");
			}
			assertTrue(plainTokens, plainTokens.contains("NAME xor @1"));
			assertTrue(xorTokens, xorTokens.contains("OPERATOR xor @1"));
			assertNotSame(plain.regexOperators, xor.regexOperators);
		}
	}

	@Test
	public void lexersOnManyThreadsLexAsOneDoes() throws Exception {
		// Pebble compiles on whichever thread asks for a template, so lexers run concurrently and share the operator
		// regexes. Each thread lexes with both sets of operators, a new lexer per template as PebbleEngine makes one.
		List<String> templates = new ArrayList<>();
		templates.add(shortTokens());
		templates.add("{{ a xor b }}{% if c is not null and d %}{{ \"#{ e }\" ~ 'f' }}{% endif %}{# g #}");
		templates.add("{% verbatim %}{{ x }}{% endverbatim %}{{ organization }}{{ 1.5 + 2L }}");
		List<String> plainExpected = new ArrayList<>();
		List<String> xorExpected = new ArrayList<>();
		for (String template : templates) {
			plainExpected.add(lex(newLexer(), template));
			xorExpected.add(lex(newLexerWithXor(), template));
		}
		int threads = 8;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<String>> results = new ArrayList<>();
		for (int t = 0; t < threads; t++) {
			results.add(pool.submit(() -> {
				start.await();
				for (int i = 0; i < 20; i++) {
					for (int k = 0; k < templates.size(); k++) {
						if (!plainExpected.get(k).equals(lex(newLexer(), templates.get(k)))) {
							return "template " + k + " lexed differently with the core operators";
						}
						if (!xorExpected.get(k).equals(lex(newLexerWithXor(), templates.get(k)))) {
							return "template " + k + " lexed differently with xor";
						}
					}
				}
				return "same";
			}));
		}
		start.countDown();
		for (Future<String> result : results) {
			assertEquals("same", result.get(5, TimeUnit.MINUTES));
		}
		pool.shutdown();
	}
}
