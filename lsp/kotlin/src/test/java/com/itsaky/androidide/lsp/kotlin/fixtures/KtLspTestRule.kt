package com.itsaky.androidide.lsp.kotlin.fixtures

import com.itsaky.androidide.lsp.kotlin.compiler.write
import org.junit.rules.TemporaryFolder
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * @param moduleSpecs Supplied as a lambda, not a value: JUnit evaluates rules after the test class
 *   is fully constructed, so this defers reading a subclass's `moduleSpecs` override until it has
 *   actually been initialized.
 */
internal class KtLspTestRule(
	private val moduleSpecs: () -> List<TestSourceModuleSpec> = {
		listOf(TestSourceModuleSpec("src"))
	},
) : TestRule {
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
						env = KtLspTestEnvironment(tempDir.root.toPath(), moduleSpecs())

						statement?.evaluate()
					} finally {
						if (::env.isInitialized) {
							env.project.write {
								// TODO: This fails in test cases, ignored for now
								// env.close()
							}
						}
					}
				}
			},
			description,
		)
}
