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

package com.itsaky.androidide.memprof

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test

class MemprofTest {
	private val sink = RecordingSink()

	@After
	fun uninstallSink() {
		Memprof.sink = null
	}

	@Test
	fun `a phase without a sink runs its action with the inert span`() {
		val seen = Memprof.phase("Parse project model", "cache_parsed") { span -> span }

		assertThat(seen).isSameInstanceAs(MemprofSpan.None)
	}

	@Test
	fun `a phase returns what its action returns`() {
		Memprof.sink = sink

		val result = Memprof.phase("Parse project model", "cache_parsed") { 42 }

		assertThat(result).isEqualTo(42)
	}

	@Test
	fun `a phase ends its span once when the action returns`() {
		Memprof.sink = sink

		Memprof.phase("Parse project model", "cache_parsed") {}

		assertThat(sink.events).containsExactly("begin phase cache_parsed", "end phase cache_parsed").inOrder()
	}

	@Test
	fun `a phase is abandoned, not ended, when the action throws`() {
		Memprof.sink = sink

		assertThrows(IllegalStateException::class.java) {
			Memprof.phase("Parse project model", "cache_parsed") { error("parse failed") }
		}

		assertThat(sink.events).containsExactly("begin phase cache_parsed", "abandon cache_parsed").inOrder()
	}

	@Test
	fun `a section is abandoned when the action throws`() {
		Memprof.sink = sink

		assertThrows(IllegalStateException::class.java) {
			Memprof.section("Index Kotlin file", "Foo.kt") { error("index failed") }
		}

		assertThat(sink.events)
			.containsExactly("begin section Index Kotlin file Foo.kt", "abandon Index Kotlin file")
			.inOrder()
	}

	@Test
	fun `a non-local return out of a phase abandons it, not ends it`() {
		Memprof.sink = sink

		returnOutOfPhase()

		assertThat(sink.events).containsExactly("begin phase cache_parsed", "abandon cache_parsed").inOrder()
	}

	private fun returnOutOfPhase() {
		Memprof.phase("Parse project model", "cache_parsed") { return }
	}

	@Test
	fun `a break out of a loop inside a section abandons it, not ends it`() {
		Memprof.sink = sink

		for (i in 0 until 3) {
			Memprof.section("Index Kotlin file", "Foo.kt") { break }
		}

		assertThat(sink.events)
			.containsExactly("begin section Index Kotlin file Foo.kt", "abandon Index Kotlin file")
			.inOrder()
	}

	@Test
	fun `details put on a phase reach the sink before it ends`() {
		Memprof.sink = sink

		Memprof.phase("Parse project model", "cache_parsed") { span -> span.put("bytes", 7) }

		assertThat(sink.events)
			.containsExactly("begin phase cache_parsed", "put cache_parsed bytes=7", "end phase cache_parsed")
			.inOrder()
	}

	@Test
	fun `a manually started phase without a sink is the inert span`() {
		assertThat(Memprof.beginPhase("Scan Kotlin sources", "source_scan_complete"))
			.isSameInstanceAs(MemprofSpan.None)
	}

	@Test
	fun `a mark reaches an installed sink`() {
		Memprof.sink = sink

		Memprof.mark("sync_complete")

		assertThat(sink.events).containsExactly("mark sync_complete")
	}

	private class RecordingSink : MemprofSink {
		val events = mutableListOf<String>()

		override fun beginPhase(
			title: String,
			marker: String,
		): MemprofSpan {
			events += "begin phase $marker"
			return RecordingSpan(marker, "end phase $marker")
		}

		override fun beginSection(
			name: String,
			detail: String?,
		): MemprofSpan {
			events += "begin section $name $detail"
			return RecordingSpan(name, "end section $name")
		}

		override fun mark(marker: String) {
			events += "mark $marker"
		}

		private inner class RecordingSpan(
			private val label: String,
			private val endEvent: String,
		) : MemprofSpan {
			private var finished = false

			override val isRecording: Boolean = true

			override fun put(
				key: String,
				value: Long,
			) {
				events += "put $label $key=$value"
			}

			// Idempotent, matching MemprofSpan's documented contract: once ended or abandoned,
			// further calls do nothing.
			override fun end() {
				if (!finished) {
					finished = true
					events += endEvent
				}
			}

			override fun abandon() {
				if (!finished) {
					finished = true
					events += "abandon $label"
				}
			}
		}
	}
}
