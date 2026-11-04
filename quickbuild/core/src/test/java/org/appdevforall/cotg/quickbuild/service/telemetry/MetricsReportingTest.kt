package org.appdevforall.cotg.quickbuild.service.telemetry

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * What [report] may and may not swallow. A metrics sink must never break a build, but two
 * throwables are not the sink's failure to absorb: a [VirtualMachineError] means the whole
 * process is out of a resource, and a [CancellationException] belongs to the caller's
 * coroutine - [report] is `inline`, so a suspending call written inside the lambda compiles
 * with no warning that its cancellation would be eaten here.
 */
class MetricsReportingTest {
	@Test
	fun `an ordinary sink failure is swallowed`() {
		var ran = false

		report {
			ran = true
			throw IllegalStateException("sink is down")
		}

		// No throw: the build carries on, which is the whole point of the helper.
		assertThat(ran).isTrue()
	}

	@Test
	fun `a VirtualMachineError is not swallowed`() {
		val fatal = OutOfMemoryError("no heap left for the metrics buffer")

		val thrown = assertThrows<OutOfMemoryError> { report { throw fatal } }

		assertThat(thrown).isSameInstanceAs(fatal)
	}

	@Test
	fun `a CancellationException reaches the caller's coroutine`() {
		val cancelled = CancellationException("session torn down mid-report")

		val thrown = assertThrows<CancellationException> { report { throw cancelled } }

		assertThat(thrown).isSameInstanceAs(cancelled)
	}
}
