package com.itsaky.androidide.lsp.kotlin.fixtures

import com.itsaky.androidide.lsp.kotlin.compiler.write
import org.junit.rules.TemporaryFolder
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

internal class KtLspTestRule : TestRule {
	val tempDir = TemporaryFolder()
	lateinit var env: KtLspTestEnvironment
		private set

	override fun apply(
		statement: Statement?,
		description: Description?,
	): Statement =
		tempDir.apply(
			object : Statement() {
				override fun evaluate() {
					try {
						val sourceRoot = tempDir.newFolder("src").toPath()
						env = KtLspTestEnvironment(listOf(sourceRoot))

						statement?.evaluate()
					} finally {
						if (::env.isInitialized) {
							// Dispose each test's heavy Analysis-API environment (IntelliJ project,
							// application env, background index workers). Without this the per-method
							// environments accumulate for the JVM's lifetime and the suite eventually
							// exhausts the heap (ADFA-4936). close() manages its own threading/locking,
							// so it must NOT run inside project.write { } -- that nesting was the
							// original "fails in test cases" failure.
							env.close()
						}
					}
				}
			},
			description,
		)
}
