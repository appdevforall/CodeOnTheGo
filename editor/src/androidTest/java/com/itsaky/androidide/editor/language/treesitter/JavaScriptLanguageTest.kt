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
