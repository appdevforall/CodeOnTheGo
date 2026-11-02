package com.itsaky.androidide.lsp.kotlin.compiler.services

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.lsp.kotlin.compiler.read
import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.name.Name
import org.junit.Test

class PrivateTopLevelResolutionTest : KtLspTest() {
	private val file by lazy {
		createSourceFile(
			"p/Private.kt",
			"""
			package p

			private fun helper(): Int = 1

			fun use(): Int = helper()

			private val secret = 2

			fun read(): Int = secret
			""".trimIndent(),
		)
	}

	@Test
	fun privateTopLevelDeclarationsResolveWithinTheirFile() {
		val diagnostics =
			analyzeMaybeDanglingForTest(file) {
				file.collectDiagnostics(KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS).map { it.factoryName }
			}

		assertThat(diagnostics).isEmpty()
	}

	@Test
	fun privateTopLevelFunctionsAreListed() {
		val found = env.project.read { sequenceOf(file).topLevelFunctions(Name.identifier("helper")) }

		assertThat(found).hasSize(1)
	}
}
