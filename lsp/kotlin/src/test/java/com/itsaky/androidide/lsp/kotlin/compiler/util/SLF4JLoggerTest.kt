package com.itsaky.androidide.lsp.kotlin.compiler.util

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger
import org.junit.Test

class SLF4JLoggerTest : KtLspTest() {
	@Test
	fun `compiler logger factory routes to SLF4JLogger`() {
		assertThat(Logger.getInstance("ADFA-4238")).isInstanceOf(SLF4JLogger::class.java)
	}

	@Test
	fun `error does not throw - regression guard for ADFA-4238`() {
		// DefaultLogger.error() rethrows as AssertionError, escalating a recoverable FIR cache
		// inconsistency into a crash (Sentry APPDEVFORALL-13D). SLF4JLogger must log, not throw.
		val logger = Logger.getInstance("ADFA-4238")
		logger.error("Inconsistency in the cache. Someone without context put a null value in the cache")
		logger.error("with throwable", RuntimeException("boom"))
		logger.error("with details", RuntimeException(), "detail-1", "detail-2")
	}

	@Test
	fun `an error logs its attachments' sizes, not their contents`() {
		// IntelliJ attaches whole file texts to errors such as "Inconsistent tree"; inlined, one error
		// wrote thousands of log lines per keystroke.
		val fileText = "class Foo\n".repeat(1_000)

		val message = SLF4JLogger.errorMessage("Inconsistent kotlin.FILE tree", arrayOf("/src/Foo.kt", fileText, null))

		assertThat(message).startsWith("Inconsistent kotlin.FILE tree")
		assertThat(message).doesNotContain("class Foo")
		assertThat(message).contains("2 attachments")
		assertThat(message).contains("${fileText.length}")
	}
}
