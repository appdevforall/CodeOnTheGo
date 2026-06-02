package com.itsaky.androidide.lsp.kotlin.fixtures

import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.psi.KtFile
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Base class for plain JVM unit tests that need a real Kotlin Analysis API environment.
 *
 * Each test method gets a fresh [KtLspTestEnvironment] (see [KtLspTestRule]) backed by a
 * temporary source directory.
 */
@RunWith(RobolectricTestRunner::class)
abstract class KtLspTest {

	@get:Rule
	@PublishedApi
	internal val lspTestRule = KtLspTestRule()

	internal val env: KtLspTestEnvironment
		get() = lspTestRule.env

	protected fun createSourceFile(relativePath: String, content: String): KtFile =
		env.createSourceFile(relativePath, content)

	protected fun <R> analyze(file: KtFile, action: KaSession.() -> R): R =
		env.analyze(file, action)
}
