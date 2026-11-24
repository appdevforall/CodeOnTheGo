package com.itsaky.androidide.assets.addons

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BundledPluginStartupTest {
	@get:Rule
	val tempFolder = TemporaryFolder()

	private lateinit var record: BundledAddonsRecord
	private var reloads = 0

	@Before
	fun setUp() {
		record = BundledAddonsRecord(File(tempFolder.root, "bundled_addons.properties"))
		reloads = 0
	}

	private fun startup(
		reload: suspend () -> Unit = { reloads++ },
		loadError: (String) -> String? = { null },
	) = BundledPluginStartup(record, reload, loadError)

	private fun writeBundle(vararg pluginIds: String) = record.write(BundledAddonsState("fp", pluginIds.toList()))

	@Test
	fun `no record or a thin one starts nothing`() {
		runBlocking {
			startup().start()
			record.write(BundledAddonsState("fp"))
			startup().start()

			assertThat(reloads).isEqualTo(0)
		}
	}

	@Test
	fun `a bundle with plugins reloads once and records nothing when all load`() {
		runBlocking {
			writeBundle("a", "b")

			startup().start()

			assertThat(reloads).isEqualTo(1)
			assertThat(record.unreportedFailures()).isEmpty()
		}
	}

	@Test
	fun `only bundled plugins that report a load error are recorded, unreported`() {
		runBlocking {
			writeBundle("a", "b")
			val errors = mapOf("b" to "needs a newer IDE", "other" to "not ours")

			startup(loadError = errors::get).start()

			assertThat(record.unreportedFailures()).containsExactly(BundledPluginFailure("b", "needs a newer IDE", reported = false))
		}
	}

	@Test
	fun `a reload that throws is recorded for every bundled plugin and does not escape`() {
		runBlocking {
			writeBundle("a", "b")

			startup(reload = { throw IllegalStateException("registry broken") }).start()

			assertThat(record.unreportedFailures().map { it.pluginId to it.message })
				.containsExactly("a" to "registry broken", "b" to "registry broken")
		}
	}

	@Test
	fun `cancellation is not swallowed`() {
		writeBundle("a")

		assertThrows(CancellationException::class.java) {
			runBlocking { startup(reload = { throw CancellationException("stop") }).start() }
		}
		assertThat(record.unreportedFailures()).isEmpty()
	}

	@Test
	fun `a failure is reported once`() {
		runBlocking {
			writeBundle("a")
			startup(loadError = { "boom" }).start()

			val told = record.unreportedFailures()
			val first = record.markReported(told)
			val second = record.markReported(told)

			assertThat(first.map { it.pluginId }).containsExactly("a")
			assertThat(second).isEmpty()
		}
	}
}
