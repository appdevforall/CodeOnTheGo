package com.itsaky.androidide.lsp.kotlin.compiler

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.lsp.kotlin.compiler.modules.AnalysisPriority
import com.itsaky.androidide.lsp.kotlin.compiler.modules.ScheduledCancelChecker
import com.itsaky.androidide.lsp.kotlin.compiler.modules.UnpinnedAnalysis
import com.itsaky.androidide.lsp.kotlin.compiler.modules.analyzeMaybeDangling
import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import com.itsaky.androidide.lsp.kotlin.fixtures.closeInWriteAction
import com.itsaky.androidide.progress.ICancelChecker
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

@OptIn(UnpinnedAnalysis::class)
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

		closeWhileBlocked(release)
		reader.join(5_000)

		assertThat(disposedDuringRead.get()).isFalse()
		assertThat(env.project.isDisposed).isTrue()
	}

	@Test
	fun `close waits for a running analysis before disposing the project`() {
		val file = createSourceFile("Running.kt", "fun running() = 0")
		val entered = CountDownLatch(1)
		val release = CountDownLatch(1)
		val disposedDuringAnalysis = AtomicBoolean()

		val analysis =
			thread {
				analyzeMaybeDangling(file, AnalysisPriority.INTERACTIVE, ScheduledCancelChecker(ICancelChecker.NOOP)) {
					entered.countDown()
					release.await(5, TimeUnit.SECONDS)
					disposedDuringAnalysis.set(env.project.isDisposed)
				}
			}
		entered.await(5, TimeUnit.SECONDS)

		closeWhileBlocked(release)
		analysis.join(5_000)

		assertThat(disposedDuringAnalysis.get()).isFalse()
		assertThat(env.project.isDisposed).isTrue()
	}

	@Test
	fun `analysis of a closed environment is cancelled`() {
		val file = createSourceFile("Closed.kt", "fun closed() = 0")
		env.closeInWriteAction()

		assertThrows(CancellationException::class.java) {
			analyzeMaybeDangling(file, AnalysisPriority.INTERACTIVE, ScheduledCancelChecker(ICancelChecker.NOOP)) { }
		}
	}

	@Test
	fun `evicting a file of a closed environment does not touch the disposed project`() {
		val path = createSourceFile("Evicted.kt", "fun evicted() = 0").virtualFile.toNioPath()
		env.closeInWriteAction()

		env.ktSymbolIndex.evictDiskCopy(path)

		assertThat(env.project.isDisposed).isTrue()
	}

	// Starts the close, gives it time to dispose if it is going to, then lets the blocker finish.
	private fun closeWhileBlocked(release: CountDownLatch) {
		val closer = thread { env.closeInWriteAction() }
		closer.join(500)
		release.countDown()
		closer.join(5_000)
	}
}
