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
	fun `past the cap the launch entry is pinned and the second-oldest evicted`() {
		val consumed = ConsumedRequests<DeepLinkRequest>()
		repeat(40) { consumed.add(request("project$it")) }

		assertThat(consumed.toSavedList()).hasSize(32)
		// project0 is the first ever added, which is by construction the request on the task's launch
		// Intent -- the one Android replays verbatim after process death, and the only one whose loss
		// force-reopens a project over whatever the user was doing. It survives; eviction takes the
		// second-oldest instead. This used to assert the opposite.
		assertThat(request("project0") in consumed).isTrue()
		assertThat(request("project8") in consumed).isFalse()
		assertThat(request("project9") in consumed).isTrue()
		assertThat(request("project39") in consumed).isTrue()
	}

	@Test
	fun `re-adding a request refreshes its position rather than leaving it oldest`() {
		val consumed = ConsumedRequests<DeepLinkRequest>()
		repeat(32) { consumed.add(request("project$it")) }
		// Touch the second-oldest; it must no longer be the eviction candidate.
		consumed.add(request("project1"))
		consumed.add(request("fresh"))

		assertThat(request("project1") in consumed).isTrue()
		assertThat(request("project2") in consumed).isFalse()
	}

	// The pin used to be positional -- the eviction loop simply skipped slot 0 -- while add()
	// reordered every entry it saw. Re-tapping the same URL therefore slid the launch entry off
	// slot 0 and handed its protection to an unrelated request, so a sender firing links in a loop
	// (DeepLinkActivity is exported) could evict it and force its project open after process death.
	// Each of these needs a SECOND entry present before the launch entry is re-added. With the set
	// holding nothing else, remove-then-append puts the launch entry straight back on slot 0 and the
	// old positional pin still covered it -- so a version of these tests without "other" passed
	// against the unfixed code and pinned nothing.
	@Test
	fun `re-adding the launch entry does not surrender its pin`() {
		val consumed = ConsumedRequests<DeepLinkRequest>()
		consumed.add(request("project0"))
		consumed.add(request("other"))
		// The user taps the launch link a second time -- nothing gates a non-reforwarded repeat.
		// The positional pin moved project0 off slot 0 here, handing its protection to "other".
		consumed.add(request("project0"))
		repeat(40) { consumed.add(request("flood$it")) }

		assertThat(consumed.toSavedList()).hasSize(32)
		assertThat(request("project0") in consumed).isTrue()
	}

	@Test
	fun `an interleaved remove and re-add still leaves the launch entry pinned`() {
		val consumed = ConsumedRequests<DeepLinkRequest>()
		consumed.add(request("project0"))
		consumed.add(request("other"))
		// armPendingFileRequest's deliberate re-arm: forget it, then record it again.
		consumed.remove(request("project0"))
		consumed.add(request("project0"))
		repeat(40) { consumed.add(request("flood$it")) }

		assertThat(request("project0") in consumed).isTrue()
	}

	@Test
	fun `the pin survives a save and restore`() {
		val consumed = ConsumedRequests<DeepLinkRequest>()
		consumed.add(request("project0"))
		consumed.add(request("other"))
		consumed.add(request("project0"))

		val restored = ConsumedRequests<DeepLinkRequest>()
		restored.restore(consumed.toSavedList())
		repeat(40) { restored.add(request("flood$it")) }

		assertThat(request("project0") in restored).isTrue()
	}

	// restore() used to addAll() unchecked, so an over-long list came back oversized and went
	// straight into the next onSaveInstanceState -- the Bundle bound the cap exists to enforce.
	@Test
	fun `restore re-applies the cap`() {
		val oversized = (0 until 40).map { request("project$it") }

		val restored = ConsumedRequests<DeepLinkRequest>()
		restored.restore(oversized)

		assertThat(restored.toSavedList()).hasSize(32)
		assertThat(request("project0") in restored).isTrue()
	}
}
