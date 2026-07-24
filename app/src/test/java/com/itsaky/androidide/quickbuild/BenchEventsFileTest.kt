package com.itsaky.androidide.quickbuild

import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * [BenchEventsFile] round-trips through the real Android `org.json` (Robolectric provides
 * it; a plain-JVM unit test only has the throwing android.jar stub), so these assertions
 * exercise the same serializer that runs on device.
 */
@RunWith(RobolectricTestRunner::class)
class BenchEventsFileTest {
	@get:Rule
	val tempDir = TemporaryFolder()

	private var clock = 1_000L

	private fun fileAt() = File(tempDir.root, "sub/bench-events.jsonl")

	private fun writer(f: File) = BenchEventsFile(f) { clock }

	@Test
	fun `append writes one JSON line per event, each carrying v and wallMs`() {
		val f = fileAt()
		val w = writer(f)

		w.append("session_started")
		clock = 2_000L
		w.append("state") {
			put("state", "Ready")
			put("generation", 3)
		}

		val lines = f.readLines()
		assertThat(lines).hasSize(2)

		val first = JSONObject(lines[0])
		assertThat(first.getInt("v")).isEqualTo(1)
		assertThat(first.getLong("wallMs")).isEqualTo(1_000)
		assertThat(first.getString("event")).isEqualTo("session_started")

		val second = JSONObject(lines[1])
		assertThat(second.getLong("wallMs")).isEqualTo(2_000)
		assertThat(second.getString("event")).isEqualTo("state")
		assertThat(second.getString("state")).isEqualTo("Ready")
		assertThat(second.getLong("generation")).isEqualTo(3)
	}

	@Test
	fun `string values with quotes, backslashes and newlines stay on one escaped line`() {
		val f = fileAt()
		writer(f).append("state") { put("state", "a\"b\\c\nd") }

		val lines = f.readLines()
		// The embedded newline must be escaped, not split the JSON across two lines.
		assertThat(lines).hasSize(1)
		assertThat(JSONObject(lines[0]).getString("state")).isEqualTo("a\"b\\c\nd")
	}

	@Test
	fun `recreates the file and its dir after a between-apps truncation`() {
		val f = fileAt()
		val w = writer(f)

		w.append("session_started")
		assertThat(f.exists()).isTrue()

		// The harness truncates by deleting the file (and, here, its parent dir) via run-as.
		f.parentFile!!.deleteRecursively()
		assertThat(f.exists()).isFalse()

		w.append("build_started") { put("buildId", 1) }
		val lines = f.readLines()
		assertThat(lines).hasSize(1)
		assertThat(JSONObject(lines[0]).getString("event")).isEqualTo("build_started")
	}

	@Test
	fun `never throws when the path is unwritable`() {
		// A regular file used as a parent directory: mkdirs fails and the append throws
		// internally; the writer must swallow it.
		val blocker = tempDir.newFile("blocker")
		val f = File(blocker, "cannot.jsonl")

		writer(f).append("session_started")

		assertThat(f.exists()).isFalse()
	}
}
