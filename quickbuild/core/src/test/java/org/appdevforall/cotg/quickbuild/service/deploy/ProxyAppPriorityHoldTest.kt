package org.appdevforall.cotg.quickbuild.service.deploy

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * [BoundServicePriorityHold]'s bind bookkeeping, against recorded bind/unbind calls.
 *
 * What is being pinned is that CoGo holds exactly one binding into the proxy app at a time
 * and always clears the framework's `ServiceConnection` registration - a stacked or leaked
 * binding is how a "keep the app unfrozen" fix turns into a process CoGo can never let go of.
 */
class ProxyAppPriorityHoldTest {
	private val bound = mutableListOf<String>()
	private var unbinds = 0
	private var bindResult = true

	private fun hold() =
		BoundServicePriorityHold(
			bind = { packageName ->
				bound += packageName
				bindResult
			},
			unbind = { unbinds++ },
		)

	@Test
	fun `holding binds the named package once`() {
		hold().hold("com.example.app")

		assertThat(bound).containsExactly("com.example.app")
		assertThat(unbinds).isEqualTo(0)
	}

	@Test
	fun `re-holding the same package does not stack a second binding`() {
		val hold = hold()

		hold.hold("com.example.app")
		hold.hold("com.example.app")
		hold.hold("com.example.app")

		assertThat(bound).containsExactly("com.example.app")
		assertThat(unbinds).isEqualTo(0)
	}

	@Test
	fun `holding a different package releases the previous one first`() {
		val hold = hold()

		hold.hold("com.example.first")
		hold.hold("com.example.second")

		assertThat(bound).containsExactly("com.example.first", "com.example.second").inOrder()
		assertThat(unbinds).isEqualTo(1)
	}

	@Test
	fun `releasing unbinds exactly once, however often it is called`() {
		val hold = hold()
		hold.hold("com.example.app")

		hold.release()
		hold.release()

		assertThat(unbinds).isEqualTo(1)
	}

	@Test
	fun `releasing without a hold does not unbind`() {
		hold().release()

		assertThat(unbinds).isEqualTo(0)
	}

	@Test
	fun `a refused bind still unbinds, so the framework registration cannot leak`() {
		bindResult = false

		hold().hold("com.example.app")

		assertThat(bound).containsExactly("com.example.app")
		assertThat(unbinds).isEqualTo(1)
	}

	@Test
	fun `a refused bind leaves nothing held, so the next hold retries`() {
		val hold = hold()
		bindResult = false
		hold.hold("com.example.app")
		bindResult = true

		hold.hold("com.example.app")

		assertThat(bound).containsExactly("com.example.app", "com.example.app")
		// Only the failed attempt's cleanup; the successful hold is still live.
		assertThat(unbinds).isEqualTo(1)
	}

	@Test
	fun `a released hold can be retaken`() {
		val hold = hold()

		hold.hold("com.example.app")
		hold.release()
		hold.hold("com.example.app")

		assertThat(bound).containsExactly("com.example.app", "com.example.app")
		assertThat(unbinds).isEqualTo(1)
	}
}
