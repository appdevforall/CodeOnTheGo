package com.itsaky.androidide.lsp.kotlin.navigation

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import com.itsaky.androidide.lsp.kotlin.fixtures.TestSourceModuleSpec
import com.itsaky.androidide.models.Location
import com.itsaky.androidide.progress.ICancelChecker
import org.junit.Test

class GoToDefinitionTest : KtLspTest() {
	override val moduleSpecs =
		listOf(
			TestSourceModuleSpec("lib"),
			TestSourceModuleSpec("app", dependsOn = listOf("lib")),
		)

	/** Locations for the caret at `text.indexOf(marker) + delta` in a new "app" module file. */
	private fun locationsAt(
		name: String,
		text: String,
		marker: String,
		delta: Int = 0,
	): List<Location> {
		val file = createSourceFile("app", name, text)
		val offset =
			text.indexOf(marker).also { check(it >= 0) { "marker '$marker' not in source" } } + delta
		return analyze(file) {
			val element = referenceAtCaret(file, offset) ?: return@analyze emptyList()
			definitionLocations(element, ICancelChecker.NOOP)
		}
	}

	/**
	 * Asserts a single location whose range covers [name], searching [text] from the first
	 * occurrence of [declAnchor]. Index arithmetic on the source text is exact because the file
	 * content is that text verbatim.
	 */
	private fun assertNavigatesTo(
		locations: List<Location>,
		text: String,
		declAnchor: String,
		name: String,
	) {
		val anchor = text.indexOf(declAnchor).also { check(it >= 0) { "anchor '$declAnchor' not in source" } }
		val nameStart = text.indexOf(name, anchor).also { check(it >= 0) { "name '$name' not after anchor" } }
		assertThat(locations).hasSize(1)
		assertThat(locations[0].range.start.index).isEqualTo(nameStart)
		assertThat(locations[0].range.end.index).isEqualTo(nameStart + name.length)
	}

	@Test
	fun `function call navigates to the function name`() {
		// Anchor on "{ target(", not "target()": the latter matches the declaration first, so indexOf
		// would put the caret on the declaration's own name and the call site would go untested.
		val text = "fun target() {}\nfun caller() { target() }"
		assertNavigatesTo(locationsAt("Fn.kt", text, "{ target()", 3), text, "fun target", "target")
	}

	@Test
	fun `caret one past an identifier navigates to the same declaration as inside it`() {
		// R2/AC 8's actual guarantee, asserted where it is observable. Inside the identifier the caret
		// resolves a name reference; on the following '(' it resolves the call expression instead, and
		// the resolved-call fallback still lands on the same function. The guarantee is about the
		// declaration reached, not the intermediate PSI node.
		val text = "fun target() {}\nfun caller() { target() }"
		val inside = locationsAt("Inside.kt", text, "{ target()", 3)
		val onParen = locationsAt("OnParen.kt", text, "{ target()", 8)

		assertNavigatesTo(inside, text, "fun target", "target")
		assertThat(onParen.map { it.range }).isEqualTo(inside.map { it.range })
	}

	@Test
	fun `local variable navigates to its declaration`() {
		val text = "fun caller() {\n\tval count = 1\n\tprintln(count)\n}"
		assertNavigatesTo(locationsAt("Local.kt", text, "println(count)", 8), text, "val count", "count")
	}

	@Test
	fun `parameter navigates to its declaration`() {
		val text = "fun caller(amount: Int) {\n\tprintln(amount)\n}"
		assertNavigatesTo(locationsAt("Param.kt", text, "println(amount)", 8), text, "amount: Int", "amount")
	}

	@Test
	fun `property read navigates to the property`() {
		val text = "class Holder {\n\tval label = \"x\"\n\tfun read() = label\n}"
		assertNavigatesTo(locationsAt("Prop.kt", text, "= label", 2), text, "val label", "label")
	}

	@Test
	fun `type reference navigates to the classifier`() {
		val text = "class Greeter\nfun make(): Greeter? = null"
		assertNavigatesTo(locationsAt("Type.kt", text, ": Greeter", 2), text, "class Greeter", "Greeter")
	}

	@Test
	fun `constructor call navigates to the class when there is no explicit constructor`() {
		val text = "class Greeter\nfun make() = Greeter()"
		assertNavigatesTo(locationsAt("Ctor.kt", text, "= Greeter()", 2), text, "class Greeter", "Greeter")
	}

	@Test
	fun `explicit constructor call navigates to that constructor`() {
		val text = "class Greeter constructor(val name: String)\nfun make() = Greeter(\"a\")"
		val locations = locationsAt("Ctor2.kt", text, "= Greeter(", 2)
		assertThat(locations).hasSize(1)
		// The primary constructor has no name identifier, so the range collapses to its start.
		val ctorStart = text.indexOf("constructor")
		assertThat(locations[0].range.start.index).isEqualTo(ctorStart)
		assertThat(locations[0].range.end.index).isEqualTo(ctorStart)
	}

	@Test
	fun `annotation navigates to the annotation class`() {
		val text = "annotation class Marker\n@Marker\nfun caller() {}"
		assertNavigatesTo(locationsAt("Anno.kt", text, "@Marker", 2), text, "class Marker", "Marker")
	}

	@Test
	fun `typealias navigates to the alias, not its expansion`() {
		val text = "class Greeter\ntypealias Salute = Greeter\nfun make(): Salute? = null"
		assertNavigatesTo(locationsAt("Alias.kt", text, ": Salute", 2), text, "typealias Salute", "Salute")
	}

	@Test
	fun `object reference navigates to the object`() {
		val text = "object Registry { val count = 0 }\nfun read() = Registry.count"
		assertNavigatesTo(locationsAt("Obj.kt", text, "= Registry", 2), text, "object Registry", "Registry")
	}

	@Test
	fun `named argument navigates to the parameter`() {
		val text = "fun target(amount: Int) {}\nfun caller() { target(amount = 1) }"
		assertNavigatesTo(locationsAt("Named.kt", text, "amount = 1", 1), text, "amount: Int", "amount")
	}

	@Test
	fun `import directive navigates to the imported declaration`() {
		createSourceFile("lib", "lib/Greeter.kt", "package lib\n\nclass Greeter")
		val text = "package app\n\nimport lib.Greeter\n\nfun make(): Greeter? = null"
		val locations = locationsAt("Import.kt", text, "import lib.Greeter", 12)
		assertThat(locations).hasSize(1)
		assertThat(locations[0].file.fileName.toString()).isEqualTo("Greeter.kt")
	}

	@Test
	fun `package reference yields nothing`() {
		val text = "package app\n\nfun caller() {}"
		assertThat(locationsAt("Pkg.kt", text, "package app", 9)).isEmpty()
	}

	@Test
	fun `label navigates to the labelled expression`() {
		val text = "fun caller() {\n\touter@ for (i in 1..2) { break@outer }\n}"
		assertThat(locationsAt("Label.kt", text, "break@outer", 7)).hasSize(1)
	}
}
