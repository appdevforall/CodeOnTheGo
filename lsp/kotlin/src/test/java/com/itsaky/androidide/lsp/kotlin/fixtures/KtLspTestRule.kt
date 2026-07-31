package com.itsaky.androidide.lsp.kotlin.fixtures

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
					// Built before the try: a constructor throw closes itself (see KtLspTestEnvironment's
					// init), so there is nothing here for a finally to clean up.
					val environment =
						KtLspTestEnvironment(
							tempDir.root.toPath(),
							moduleSpecs(),
							enableParserEventSystem = enableParserEventSystem(),
						)
					env = environment

					var failure: Throwable? = null
					try {
						statement?.evaluate()
					} catch (e: Throwable) {
						failure = e
						throw e
					} finally {
						// Without disposal every test leaks a whole KotlinCoreApplicationEnvironment
						// (refcounted, process-wide static) and the suite OOMs part-way through. A throw
						// from disposal must not replace the test's own failure, though - JUnit would then
						// report the fixture instead of the assertion that actually broke.
						val disposal =
							runCatching {
								if (!environment.project.isDisposed) {
									environment.closeInWriteAction()
								}
							}.exceptionOrNull()
						val pending = failure
						if (disposal != null) {
							if (pending == null) throw disposal
							pending.addSuppressed(disposal)
						}
					}
				}
			},
			description,
		)
}
