package com.itsaky.androidide.lsp.kotlin.fixtures

import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager
import org.junit.rules.TemporaryFolder
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * @param moduleSpecs Supplied as a lambda, not a value: JUnit evaluates rules after the test class
 *   is fully constructed, so this defers reading a subclass's `moduleSpecs` override until it has
 *   actually been initialized.
 * @param enableParserEventSystem See [KtLspTestEnvironment]'s parameter of the same name. Also a
 *   lambda, for the same construction-order reason as [moduleSpecs].
 */
internal class KtLspTestRule(
	private val moduleSpecs: () -> List<TestSourceModuleSpec> = {
		listOf(TestSourceModuleSpec("src"))
	},
	private val enableParserEventSystem: () -> Boolean = { false },
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
						env =
							KtLspTestEnvironment(
								tempDir.root.toPath(),
								moduleSpecs(),
								enableParserEventSystem = enableParserEventSystem(),
							)

						statement?.evaluate()
					} finally {
						if (::env.isInitialized && !env.project.isDisposed) {
							// Disposing the project model requires an IntelliJ write action; our own
							// project.write lock does not supply one, which is why this used to fail.
							// Without disposal every test leaks a whole KotlinCoreApplicationEnvironment
							// (refcounted, process-wide static) and the suite OOMs part-way through.
							ApplicationManager.getApplication().runWriteAction { env.close() }
						}
					}
				}
			},
			description,
		)
}
