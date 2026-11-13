package com.itsaky.androidide.tasks

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Job
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
}
