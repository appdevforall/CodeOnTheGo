package com.itsaky.androidide.deeplink

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.models.DeepLinkRequest
import org.junit.Test

/**
 * ADFA-5067: a redelivered Intent must not force its project open twice.
 *
 * The scenario that matters is the one a single stored request got wrong -- two links, then process
 * death, where the Intent the system hands back is the task's *launch* Intent rather than the last
 * one `setIntent` saw.
 */
class ConsumedDeepLinkRequestsTest {
	private fun request(name: String) = DeepLinkRequest(projectName = name)

	@Test
	fun `a consumed request is recognised`() {
		val consumed = ConsumedDeepLinkRequests()
		consumed.add(request("alpha"))

		assertThat(request("alpha") in consumed).isTrue()
		assertThat(request("beta") in consumed).isFalse()
	}

	// The single-slot bug: consuming B made A look unconsumed again, and A is what a post-process-death
	// recreate is handed, so A's project reopened over whatever the user was doing.
	@Test
	fun `consuming a second request does not un-consume the first`() {
		val consumed = ConsumedDeepLinkRequests()
		consumed.add(request("alpha"))
		consumed.add(request("beta"))

		assertThat(request("alpha") in consumed).isTrue()
		assertThat(request("beta") in consumed).isTrue()
	}

	@Test
	fun `the set survives a save and restore`() {
		val consumed = ConsumedDeepLinkRequests()
		consumed.add(request("alpha"))
		consumed.add(request("beta"))

		val restored = ConsumedDeepLinkRequests()
		restored.restore(consumed.toSavedList())

		assertThat(request("alpha") in restored).isTrue()
		assertThat(request("beta") in restored).isTrue()
	}

	@Test
	fun `restoring nothing leaves an empty set, not a stale one`() {
		val consumed = ConsumedDeepLinkRequests()
		consumed.add(request("alpha"))
		consumed.restore(null)

		assertThat(request("alpha") in consumed).isFalse()
	}

	@Test
	fun `a repeated request is remembered once`() {
		val consumed = ConsumedDeepLinkRequests()
		consumed.add(request("alpha"))
		consumed.add(request("alpha"))

		assertThat(consumed.toSavedList()).containsExactly(request("alpha"))
	}

	@Test
	fun `a null request is ignored`() {
		val consumed = ConsumedDeepLinkRequests()
		consumed.add(null)

		assertThat(consumed.toSavedList()).isEmpty()
	}

	// The cap bounds the saved Bundle; what it must not do is forget the most recent requests.
	@Test
	fun `past the cap the oldest is evicted and the newest kept`() {
		val consumed = ConsumedDeepLinkRequests()
		repeat(40) { consumed.add(request("project$it")) }

		assertThat(consumed.toSavedList()).hasSize(32)
		assertThat(request("project0") in consumed).isFalse()
		assertThat(request("project7") in consumed).isFalse()
		assertThat(request("project8") in consumed).isTrue()
		assertThat(request("project39") in consumed).isTrue()
	}
}
