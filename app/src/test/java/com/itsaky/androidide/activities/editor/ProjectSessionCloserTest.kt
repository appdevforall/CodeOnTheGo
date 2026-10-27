package com.itsaky.androidide.activities.editor

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProjectSessionCloserTest {
	private val first = Any()
	private val second = Any()
	private var current: Any? = null
	private var teardowns = 0
	private val closer = ProjectSessionCloser({ current }, { teardowns++ })

	@Test
	fun `teardown runs once across repeated calls`() {
		recordCurrent(first)

		closer.closeOnPause()
		closer.closeOnPause()
		closer.closeOnDestroy()

		assertThat(teardowns).isEqualTo(1)
	}

	@Test
	fun `pause with nothing recorded tears down as today`() {
		closer.closeOnPause()

		assertThat(teardowns).isEqualTo(1)
	}

	@Test
	fun `destroy with nothing recorded skips`() {
		closer.closeOnDestroy()

		assertThat(teardowns).isEqualTo(0)
	}

	@Test
	fun `skips when the current manager is not the recorded one`() {
		recordCurrent(first)
		current = second

		closer.closeOnPause()
		closer.closeOnDestroy()

		assertThat(teardowns).isEqualTo(0)
	}

	@Test
	fun `destroy fallback runs when pause never closed`() {
		recordCurrent(first)

		closer.closeOnDestroy()

		assertThat(teardowns).isEqualTo(1)
	}

	@Test
	fun `a closer that never ran its teardown does nothing on recreation`() {
		recordCurrent(first)

		assertThat(teardowns).isEqualTo(0)
	}

	@Test
	fun `record after teardown is ignored`() {
		closer.closeOnPause()
		recordCurrent(second)

		closer.closeOnDestroy()

		assertThat(teardowns).isEqualTo(1)
	}

	private fun recordCurrent(session: Any) {
		current = session
		closer.record(session)
	}
}
