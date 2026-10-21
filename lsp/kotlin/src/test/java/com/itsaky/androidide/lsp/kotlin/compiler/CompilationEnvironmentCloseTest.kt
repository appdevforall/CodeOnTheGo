package com.itsaky.androidide.lsp.kotlin.compiler

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import com.itsaky.androidide.lsp.kotlin.fixtures.closeInWriteAction
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class CompilationEnvironmentCloseTest : KtLspTest() {
	@Test
	fun `close waits for an in-flight read before disposing the project`() {
		val entered = CountDownLatch(1)
		val release = CountDownLatch(1)
		val disposedDuringRead = AtomicBoolean()

		val reader =
			thread {
				env.project.read {
					entered.countDown()
					release.await(5, TimeUnit.SECONDS)
					disposedDuringRead.set(env.project.isDisposed)
				}
			}
		entered.await(5, TimeUnit.SECONDS)

		val closer = thread { env.closeInWriteAction() }
		closer.join(500)
		release.countDown()
		reader.join(5_000)
		closer.join(5_000)

		assertThat(disposedDuringRead.get()).isFalse()
		assertThat(env.project.isDisposed).isTrue()
	}
}
