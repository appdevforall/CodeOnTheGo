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

		closer.close()
		closer.close()
		closer.close()

		assertThat(teardowns).isEqualTo(1)
	}

	@Test
	fun `nothing recorded tears down as before`() {
		closer.close()

		assertThat(teardowns).isEqualTo(1)
	}

	@Test
	fun `skips when the current manager is not the recorded one`() {
		recordCurrent(first)
		current = second

		closer.close()
		closer.close()

		assertThat(teardowns).isEqualTo(0)
	}

	@Test
	fun `an editor that still owns its session tears it down`() {
		recordCurrent(first)

		closer.close()

		assertThat(teardowns).isEqualTo(1)
	}

	@Test
	fun `a closer that never ran its teardown does nothing on recreation`() {
		recordCurrent(first)

		assertThat(teardowns).isEqualTo(0)
	}

	@Test
	fun `record after teardown is ignored`() {
		closer.close()
		recordCurrent(second)

		closer.close()

		assertThat(teardowns).isEqualTo(1)
	}

	@Test
	fun `record after teardown reports that it was refused`() {
		closer.close()

		assertThat(closer.record { second }).isFalse()
	}

	@Test
	fun `record after teardown never resolves the session`() {
		var resolved = false
		closer.close()

		closer.record {
			resolved = true
			second
		}

		assertThat(resolved).isFalse()
	}

	@Test
	fun `record before teardown is accepted`() {
		assertThat(closer.record { first }).isTrue()
	}

	private fun recordCurrent(session: Any) {
		current = session
		closer.record { session }
	}
}
