package com.itsaky.androidide.editor.language.treesitter

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.itsaky.androidide.editor.schemes.LanguageSpecProvider
import com.itsaky.androidide.treesitter.TSParser
import com.itsaky.androidide.treesitter.TSQuery
import com.itsaky.androidide.treesitter.TSQueryError
import com.itsaky.androidide.treesitter.TreeSitter
import com.itsaky.androidide.treesitter.javascript.TSLanguageJavascript
import io.github.rosemoe.sora.lang.styling.MappedSpans
import io.github.rosemoe.sora.lang.styling.SpanFactory
import io.github.rosemoe.sora.lang.styling.Styles
import io.github.rosemoe.sora.lang.styling.TextStyle
import io.github.rosemoe.sora.text.Content
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JavaScriptLanguageTest {
	@Before
	fun loadNative() {
		TreeSitter.loadLibrary()
	}

	@Test
	fun grammarHasAnAbiTheRuntimeLoads() {
		val version = TSLanguageJavascript.getInstance().languageVersion
		assertThat(version).isAtLeast(TreeSitter.getMinimumCompatibleLanguageVersion())
		assertThat(version).isAtMost(TreeSitter.getLanguageVersion())
	}

	@Test
	fun parsesModernJavaScriptWithoutErrors() {
		assertThat(hasErrors(MODERN_JS)).isFalse()
	}

	@Test
	fun flagsBrokenJavaScript() {
		assertThat(hasErrors("function f( {\n\treturn 1 +;\n}\n")).isTrue()
	}

	@Test
	fun editorQueriesCompile() {
		val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
		for (name in listOf("highlights", "blocks", "brackets", "indents")) {
			val scm = assets.open("editor/treesitter/${JavaScriptLanguage.TS_TYPE}/$name.scm").reader().use { it.readText() }
			TSQuery.create(TSLanguageJavascript.getInstance(), scm).use { query ->
				assertWithMessage(name).that(query.errorType).isEqualTo(TSQueryError.None)
				assertWithMessage(name).that(query.patternCount).isGreaterThan(0)
			}
		}
	}

	// The path the app takes when a .js file opens. It checks more than the compile above: it
	// joins locals to highlights, rejects characters the query loader cannot read, and validates
	// the blocks, brackets and indents queries.
	@Test
	fun editorLoadsTheLanguageSpec() {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		LanguageSpecProvider
			.getLanguageSpec(context, JavaScriptLanguage.TS_TYPE, TSLanguageJavascript.getInstance())
			.use { spec ->
				assertThat(spec.spec.tsQuery.patternCount).isGreaterThan(0)
				assertThat(spec.indentsQuery).isNotNull()
			}
	}

	// The editor gives Enter to the first newline handler that accepts the cursor's position, and
	// that handler puts the closing bracket on a line of its own. Without one, [ ] included, the
	// closing bracket stays at the start of the new line and is dedented.
	@Test
	fun enterBetweenBracketsIsHandled() {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		LanguageSpecProvider
			.getLanguageSpec(context, JavaScriptLanguage.TS_TYPE, TSLanguageJavascript.getInstance())
			.use { spec ->
				val handlers = JavaScriptLanguage(spec).newlineHandlers
				for (pair in listOf("{}", "()", "[]")) {
					val content = Content("const a = $pair")
					val betweenPair = content.indexer.getCharPosition(0, content.getColumnCount(0) - 1)
					val styles = plainStyles()
					assertWithMessage(pair)
						.that(handlers.any { it.matchesRequirement(content, betweenPair, styles) })
						.isTrue()
				}
			}
	}

	// A handler accepts only a position whose style span allows completion, as code outside
	// strings, template text and regexes has.
	private fun plainStyles(): Styles {
		val spans = MappedSpans.Builder()
		spans.add(0, SpanFactory.obtain(0, TextStyle.makeStyle(0)))
		spans.determine(0)
		return Styles(spans.build())
	}

	private fun hasErrors(source: String): Boolean =
		TSParser.create().use { parser ->
			parser.language = TSLanguageJavascript.getInstance()
			parser.parseString(source).use { tree -> tree.rootNode.hasErrors() }
		}

	private companion object {
		val MODERN_JS =
			"""
			import { readFile } from "node:fs/promises";
			export default class Counter extends EventTarget {
				#count = 0;
				static #instances = 0;
				get value() { return this.#count; }
				increment(by = 1) { this.#count += by; return this; }
			}
			const slug = (s) => s.toLowerCase().replace(/[^a-z0-9]+/g, "-");
			const big = 2n ** 70n, name = globalThis.x?.y ?? "none";
			async function* lines(path) {
				for (const line of (await readFile(path, "utf8")).split("\n")) yield line;
			}
			const message = `hello ${'$'}{name}`;
			const App = ({ items }) => <ul>{items.map((it) => <li key={it.id}>{it.name}</li>)}</ul>;
			""".trimIndent()
	}
}
