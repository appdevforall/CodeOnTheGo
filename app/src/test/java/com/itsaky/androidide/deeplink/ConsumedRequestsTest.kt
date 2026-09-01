/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

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
class ConsumedRequestsTest {
	private fun request(name: String) = DeepLinkRequest(projectName = name)

	@Test
	fun `a consumed request is recognised`() {
		val consumed = ConsumedRequests<DeepLinkRequest>()
		consumed.add(request("alpha"))

		assertThat(request("alpha") in consumed).isTrue()
		assertThat(request("beta") in consumed).isFalse()
	}

	// The single-slot bug: consuming B made A look unconsumed again, and A is what a post-process-death
	// recreate is handed, so A's project reopened over whatever the user was doing.
	@Test
	fun `consuming a second request does not un-consume the first`() {
		val consumed = ConsumedRequests<DeepLinkRequest>()
		consumed.add(request("alpha"))
		consumed.add(request("beta"))

		assertThat(request("alpha") in consumed).isTrue()
		assertThat(request("beta") in consumed).isTrue()
	}

	@Test
	fun `the set survives a save and restore`() {
		val consumed = ConsumedRequests<DeepLinkRequest>()
		consumed.add(request("alpha"))
		consumed.add(request("beta"))

		val restored = ConsumedRequests<DeepLinkRequest>()
		restored.restore(consumed.toSavedList())

		assertThat(request("alpha") in restored).isTrue()
		assertThat(request("beta") in restored).isTrue()
	}

	@Test
	fun `restoring nothing leaves an empty set, not a stale one`() {
		val consumed = ConsumedRequests<DeepLinkRequest>()
		consumed.add(request("alpha"))
		consumed.restore(null)

		assertThat(request("alpha") in consumed).isFalse()
	}

	@Test
	fun `a repeated request is remembered once`() {
		val consumed = ConsumedRequests<DeepLinkRequest>()
		consumed.add(request("alpha"))
		consumed.add(request("alpha"))

		assertThat(consumed.toSavedList()).containsExactly(request("alpha"))
	}

	// A deliberate re-arm (the same navigation requested a second time) must not be mistaken for
	// the already-consumed earlier request and silently skipped.
	@Test
	fun `removing a consumed request lets an equal one be acted on again`() {
		val consumed = ConsumedRequests<DeepLinkRequest>()
		consumed.add(request("alpha"))
		consumed.remove(request("alpha"))

		assertThat(request("alpha") in consumed).isFalse()
	}

	@Test
	fun `a null request is ignored`() {
		val consumed = ConsumedRequests<DeepLinkRequest>()
		consumed.add(null)

		assertThat(consumed.toSavedList()).isEmpty()
	}

	// The cap bounds the saved Bundle; what it must not do is forget the most recent requests.
	@Test
	fun `past the cap the oldest is evicted and the newest kept`() {
		val consumed = ConsumedRequests<DeepLinkRequest>()
		repeat(40) { consumed.add(request("project$it")) }

		assertThat(consumed.toSavedList()).hasSize(32)
		assertThat(request("project0") in consumed).isFalse()
		assertThat(request("project7") in consumed).isFalse()
		assertThat(request("project8") in consumed).isTrue()
		assertThat(request("project39") in consumed).isTrue()
	}
}
