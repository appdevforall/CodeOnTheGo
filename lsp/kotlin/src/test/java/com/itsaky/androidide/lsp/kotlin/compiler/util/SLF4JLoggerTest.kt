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
}
