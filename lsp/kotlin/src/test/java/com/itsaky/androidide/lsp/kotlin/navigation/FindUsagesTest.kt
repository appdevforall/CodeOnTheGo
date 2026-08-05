package com.itsaky.androidide.lsp.kotlin.navigation

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import com.itsaky.androidide.lsp.kotlin.fixtures.TestSourceModuleSpec
import com.itsaky.androidide.lsp.models.ReferenceParams
import com.itsaky.androidide.models.Position
import com.itsaky.androidide.progress.ICancelChecker
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.nio.file.Path

/**
 * The search itself: match set, visibility-derived scope, candidate selection and matching.
 *
 * Driven through `findUsagesAt`/`planAt` rather than the individual helpers, so each case exercises
 * the real request path.
 */
class FindUsagesTest : KtLspTest() {
	override val moduleSpecs =
		listOf(
			TestSourceModuleSpec("lib"),
			TestSourceModuleSpec("app", dependsOn = listOf("lib")),
		)

	private class Source(
		val path: Path,
		val text: String,
	)

	private fun source(
		module: String,
		name: String,
		text: String,
	): Source = Source(Path.of(createSourceFile(module, name, text).virtualFile.path), text)

	private fun paramsAt(
		source: Source,
		marker: String,
		delta: Int = 0,
		cancelChecker: ICancelChecker = ICancelChecker.NOOP,
	): ReferenceParams {
		val offset =
			source.text.indexOf(marker).also { check(it >= 0) { "marker '$marker' not in source" } } + delta
		return ReferenceParams(source.path, Position(0, 0, offset), true, cancelChecker)
	}

	/** Usages for a caret at `marker + delta` in [source], as `fileName:startOffset` pairs. */
	private fun usagesAt(
		source: Source,
		marker: String,
		delta: Int = 0,
		cancelChecker: ICancelChecker = ICancelChecker.NOOP,
	): List<String> =
		runBlocking {
			context(env) { findUsagesAt(paramsAt(source, marker, delta, cancelChecker)) }
				.locations
				.map { "${it.file.fileName}:${it.range.start.index}" }
		}

	private fun scopeAt(
		source: Source,
		marker: String,
		delta: Int = 0,
	): UsageSearchScope? =
		runBlocking {
			context(env) { planAt(paramsAt(source, marker, delta))?.scope }
		}

	private fun expected(
		source: Source,
		vararg markers: String,
	): List<String> =
		markers.map { marker ->
			val index = source.text.indexOf(marker).also { check(it >= 0) { "marker '$marker' not in source" } }
			"${source.path.fileName}:$index"
		}

	@Test
	fun `a same-file call is a usage`() {
		val file = source("app", "SameFile.kt", "fun target() {}\nfun caller() { target() }")

		assertThat(usagesAt(file, "fun target", delta = 5)).isEqualTo(expected(file, "target() }"))
	}

	@Test
	fun `every call in the file is reported, ordered by offset`() {
		val text = "fun target() {}\nfun a() { target() }\nfun b() { target() }"
		val file = source("app", "Many.kt", text)

		val usages = usagesAt(file, "fun target", delta = 5)

		assertThat(usages).hasSize(2)
		assertThat(usages).isEqualTo(
			listOf(
				"Many.kt:${text.indexOf("target() }")}",
				"Many.kt:${text.lastIndexOf("target() }")}",
			),
		)
	}

	@Test
	fun `the declaration itself is never reported`() {
		// includeDeclaration is ignored (R7): a target with no usages must come back empty so the editor
		// flashes "no references" rather than silently selecting the declaration the caret is already on.
		val file = source("app", "Unused.kt", "fun unused() {}")

		assertThat(usagesAt(file, "fun unused", delta = 5)).isEmpty()
	}

	@Test
	fun `an inter-file call in the same module is a usage`() {
		val declaration = source("app", "Decl.kt", "fun shared() {}")
		val usage = source("app", "Use.kt", "fun caller() { shared() }")

		assertThat(usagesAt(declaration, "fun shared", delta = 5)).isEqualTo(expected(usage, "shared()"))
	}

	@Test
	fun `an inter-module call is a usage`() {
		val declaration = source("lib", "LibApi.kt", "fun libFun() {}")
		val usage = source("app", "AppUse.kt", "fun caller() { libFun() }")

		assertThat(usagesAt(declaration, "fun libFun", delta = 5)).isEqualTo(expected(usage, "libFun()"))
	}

	@Test
	fun `searching from a reference finds the same usages as from the declaration`() {
		val declaration = source("app", "FromRefDecl.kt", "fun shared() {}")
		val usage = source("app", "FromRefUse.kt", "fun caller() { shared() }")

		val fromDeclaration = usagesAt(declaration, "fun shared", delta = 5)
		val fromReference = usagesAt(usage, "shared()", delta = 1)

		assertThat(fromReference).isEqualTo(fromDeclaration)
		assertThat(fromReference).isNotEmpty()
	}

	@Test
	fun `a constructor call is a usage of the class`() {
		val declaration = source("app", "Widget.kt", "class Widget")
		val usage = source("app", "WidgetUse.kt", "fun caller() { Widget() }")

		assertThat(usagesAt(declaration, "class Widget", delta = 7)).isEqualTo(expected(usage, "Widget()"))
	}

	@Test
	fun `an import is a usage`() {
		val declaration = source("lib", "Imported.kt", "package lib\n\nclass Imported")
		val usage = source("app", "ImportUse.kt", "package app\n\nimport lib.Imported\n\nfun caller(p: Imported) {}")

		assertThat(usagesAt(declaration, "class Imported", delta = 7))
			.isEqualTo(expected(usage, "Imported\n", "Imported) {}"))
	}

	@Test
	fun `a same-named declaration elsewhere is not a usage`() {
		// Matching is by symbol, not by name: the decoy shares the name and nothing else. Separate
		// packages are load-bearing - two top-level `fun ambiguous()` in one package is a redeclaration,
		// and the decoy's call then legitimately binds to whichever the resolver picks first.
		val declaration = source("app", "Real.kt", "package real\n\nfun ambiguous() {}")
		source("app", "Decoy.kt", "package decoy\n\nfun ambiguous() {}\nfun decoyCaller() { ambiguous() }")

		assertThat(usagesAt(declaration, "fun ambiguous", delta = 5)).isEmpty()
	}

	@Test
	fun `a call dispatched through a workspace supertype is a usage of the override`() {
		val declaration =
			source(
				"app",
				"Hierarchy.kt",
				"""
				interface Base {
					fun render()
				}

				class Impl : Base {
					override fun render() {}
				}
				""".trimIndent(),
			)
		val usage = source("app", "HierarchyUse.kt", "fun caller(b: Base) { b.render() }")

		// The call statically resolves to Base.render, but may dispatch to Impl.render at runtime.
		assertThat(usagesAt(declaration, "override fun render", delta = 14))
			.isEqualTo(expected(usage, "render() }"))
	}

	@Test
	fun `a call dispatched through a supertype in a dependency module is a usage of the override`() {
		source("lib", "DepBase.kt", "package lib\n\nopen class DepBase {\n\topen fun paint() {}\n}")
		val call = source("lib", "DepBaseUse.kt", "package lib\n\nfun caller(b: DepBase) { b.paint() }")
		val override =
			source(
				"app",
				"DepDerived.kt",
				"package app\n\nimport lib.DepBase\n\nclass DepDerived : DepBase() {\n\toverride fun paint() {}\n}",
			)

		// The call is written in lib, a *dependency* of app rather than a dependent of it, so scoping to
		// the override's own module and its dependents would never look at it.
		assertThat(usagesAt(override, "override fun paint", delta = 14))
			.isEqualTo(expected(call, "paint() }"))
	}

	@Test
	fun `an override is scoped to its supertype's module as well as its own`() {
		source("lib", "ScopeBase.kt", "package lib\n\nopen class ScopeBase {\n\topen fun tick() {}\n}")
		val override =
			source(
				"app",
				"ScopeDerived.kt",
				"package app\n\nimport lib.ScopeBase\n\nclass ScopeDerived : ScopeBase() {\n\toverride fun tick() {}\n}",
			)

		val scope = scopeAt(override, "override fun tick", delta = 14)

		assertThat(scope).isInstanceOf(UsageSearchScope.Modules::class.java)
		// app, the override's own module, plus lib, its supertype's. lib's dependents re-add app.
		assertThat((scope as UsageSearchScope.Modules).modules.map { it.id }).containsExactly("app", "lib")
	}

	@Test
	fun `an override of a library member does not match unrelated calls to it`() {
		// The up-walk stops at the workspace boundary: with Any.toString in the match set this would
		// report every .toString() call in the workspace.
		val declaration =
			source(
				"app",
				"Renderer.kt",
				"class Renderer {\n\toverride fun toString(): String = \"r\"\n}",
			)
		source("app", "OtherToString.kt", "fun caller(value: Int) = value.toString()")

		assertThat(usagesAt(declaration, "override fun toString", delta = 14)).isEmpty()
	}

	@Test
	fun `a local declaration is scoped to its own file`() {
		val file = source("app", "LocalScope.kt", "fun caller() {\n\tval count = 1\n\tprintln(count)\n}")

		assertThat(scopeAt(file, "val count", delta = 4))
			.isEqualTo(UsageSearchScope.SingleFile(file.path))
		assertThat(usagesAt(file, "val count", delta = 4)).isEqualTo(expected(file, "count)"))
	}

	@Test
	fun `a private top-level declaration is scoped to its own file`() {
		val file = source("app", "PrivateScope.kt", "private fun hidden() {}\nfun caller() { hidden() }")

		assertThat(scopeAt(file, "fun hidden", delta = 5))
			.isEqualTo(UsageSearchScope.SingleFile(file.path))
	}

	@Test
	fun `an internal declaration is scoped to its own module`() {
		val file = source("lib", "InternalScope.kt", "internal fun shared() {}")

		val scope = scopeAt(file, "fun shared", delta = 5)

		assertThat(scope).isInstanceOf(UsageSearchScope.Modules::class.java)
		assertThat((scope as UsageSearchScope.Modules).modules.map { it.id }).hasSize(1)
	}

	@Test
	fun `a public declaration is scoped to its module and dependents`() {
		val file = source("lib", "PublicScope.kt", "fun exported() {}")

		val scope = scopeAt(file, "fun exported", delta = 5)

		assertThat(scope).isInstanceOf(UsageSearchScope.Modules::class.java)
		// lib plus app, which depends on it.
		assertThat((scope as UsageSearchScope.Modules).modules.map { it.id }).hasSize(2)
	}

	/**
	 * Direction 1 of the cross-language split: a Java-source *target* is in scope, because resolving a
	 * Kotlin reference to it already works. Searching `.java` files for usages is not - and falls out of
	 * this design rather than needing a special case, since `getKtFile` rejects a non-Kotlin path, so a
	 * `.java` candidate file is simply skipped.
	 */
	@Test
	fun `a workspace Java declaration is a valid target`() {
		env.createFile("lib", "lib/JavaGreeter.java", "package lib;\npublic class JavaGreeter {}")
		val usage =
			source(
				"app",
				"app/JavaUse.kt",
				"package app\n\nimport lib.JavaGreeter\n\nfun make(): JavaGreeter? = null",
			)

		// The caret is on the Kotlin reference; the target it resolves to is the Java class.
		assertThat(usagesAt(usage, ": JavaGreeter", delta = 2))
			.isEqualTo(expected(usage, "JavaGreeter\n", "JavaGreeter? = null"))
	}

	@Test
	fun `a reference to a stdlib symbol yields no usages`() {
		val file = source("app", "Stdlib.kt", "fun caller() { listOf(1) }")

		assertThat(usagesAt(file, "listOf", delta = 1)).isEmpty()
	}

	@Test
	fun `a caret that names nothing yields no usages`() {
		val file = source("app", "Nothing.kt", "fun caller() {   }")

		assertThat(usagesAt(file, "{   }", delta = 2)).isEmpty()
	}

	@Test
	fun `a cancelled request yields no usages rather than throwing`() {
		val file = source("app", "Cancelled.kt", "fun target() {}\nfun caller() { target() }")

		assertThat(usagesAt(file, "fun target", delta = 5, cancelChecker = ICancelChecker.CANCELLED)).isEmpty()
	}

	@Test
	fun `a property read and write are both usages`() {
		val text = "var counter = 0\nfun caller() {\n\tcounter = 1\n\tprintln(counter)\n}"
		val file = source("app", "Property.kt", text)

		assertThat(usagesAt(file, "var counter", delta = 5)).hasSize(2)
	}
}
