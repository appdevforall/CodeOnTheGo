package com.itsaky.androidide.tasks

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertThrows
import org.junit.Test

class JobCancelCheckerTest {
	@Test
	fun `cancelling the constructor job cancels the checker and fires listeners`() {
		val job = Job()
		val checker = JobCancelChecker(job)
		var fired = false
		checker.invokeOnCancel { fired = true }

		job.cancel()

		assertThat(checker.isCancelled()).isTrue()
		assertThat(fired).isTrue()
	}

	@Test
	fun `cancelling a job assigned after construction cancels the checker`() {
		val checker = JobCancelChecker()
		val job = Job()
		checker.job = job

		job.cancel()

		assertThat(checker.isCancelled()).isTrue()
	}

	@Test
	fun `a job that completes normally does not cancel the checker`() {
		val job = Job()
		val checker = JobCancelChecker(job)

		job.complete()

		assertThat(checker.isCancelled()).isFalse()
	}

	@Test
	fun `a cancelled job that is still running reports cancelled before it completes`() =
		runBlocking<Unit> {
			val parent = Job()
			val gate = runUncancellableChild(parent)
			val checker = JobCancelChecker(parent)
			try {
				var fired = false
				checker.invokeOnCancel { fired = true }

				parent.cancel()

				assertThat(parent.isCompleted).isFalse()
				assertThat(checker.isCancelled()).isTrue()
				assertThat(fired).isTrue()
			} finally {
				gate.complete(Unit)
				parent.join()
			}
		}

	@Test
	fun `a job assigned later that is cancelled while still running reports cancelled before it completes`() =
		runBlocking<Unit> {
			val parent = Job()
			val gate = runUncancellableChild(parent)
			val checker = JobCancelChecker()
			try {
				var fired = false
				checker.invokeOnCancel { fired = true }
				checker.job = parent

				parent.cancel()

				assertThat(parent.isCompleted).isFalse()
				assertThat(checker.isCancelled()).isTrue()
				assertThat(fired).isTrue()
			} finally {
				gate.complete(Unit)
				parent.join()
			}
		}

	@Test
	fun `abortIfCancelled throws after the job is cancelled`() {
		val job = Job()
		val checker = JobCancelChecker(job)

		job.cancel()

		assertThrows(CancellationException::class.java) { checker.abortIfCancelled() }
	}

	@Test
	fun `abortIfCancelled throws after the checker is cancelled`() {
		val checker = JobCancelChecker(Job())

		checker.cancel()

		assertThrows(CancellationException::class.java) { checker.abortIfCancelled() }
	}

	/** A plain child Job completes as soon as its parent is cancelled, so use a body that ignores cancellation. */
	private suspend fun runUncancellableChild(parent: Job): CompletableDeferred<Unit> {
		val gate = CompletableDeferred<Unit>()
		val started = CompletableDeferred<Unit>()
		CoroutineScope(parent + Dispatchers.Default).launch {
			withContext(NonCancellable) {
				started.complete(Unit)
				gate.await()
			}
		}
		started.await()
		return gate
	}
}
