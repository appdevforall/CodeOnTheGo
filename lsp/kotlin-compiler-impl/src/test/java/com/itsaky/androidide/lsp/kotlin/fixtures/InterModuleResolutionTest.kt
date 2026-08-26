package com.itsaky.androidide.lsp.kotlin.fixtures

import com.google.common.truth.Truth.assertThat
import org.jetbrains.kotlin.analysis.api.symbols.KaClassLikeSymbol
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.junit.Test

/**
 * The fixture must be able to express "module app depends on module lib". Without it, no
 * inter-module navigation test is possible: every test source module would see only the JDK,
 * the stdlib and extra jars.
 */
class InterModuleResolutionTest : KtLspTest() {
	override val moduleSpecs =
		listOf(
			TestSourceModuleSpec("lib"),
			TestSourceModuleSpec("app", dependsOn = listOf("lib")),
		)

	@Test
	fun `a reference in app resolves to a class declared in lib`() {
		createSourceFile("lib", "lib/Greeter.kt", "package lib\n\nclass Greeter")
		val appFile =
			createSourceFile(
				"app",
				"app/Main.kt",
				"package app\n\nimport lib.Greeter\n\nfun make(): Greeter? = null",
			)

		// Return a String, not the symbol: a KaSymbol must not escape the analyze block.
		val resolvedClassId =
			analyze(appFile) {
				appFile
					.collectDescendantsOfType<KtNameReferenceExpression>()
					.last { it.getReferencedName() == "Greeter" }
					.mainReference
					.resolveToSymbols()
					.firstNotNullOfOrNull { (it as? KaClassLikeSymbol)?.classId?.asString() }
			}

		assertThat(resolvedClassId).isEqualTo("lib/Greeter")
	}

	@Test
	fun `each module gets its own source root`() {
		assertThat(env.sourceRoots).hasSize(2)
		assertThat(env.sourceRoots[0].fileName.toString()).isEqualTo("lib")
		assertThat(env.sourceRoots[1].fileName.toString()).isEqualTo("app")
	}
}
