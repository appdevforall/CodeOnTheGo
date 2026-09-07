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

package com.itsaky.androidide.handlers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.utils.MemoryUsageWatcher
import com.itsaky.androidide.utils.MetricsAnnotationStore
import com.itsaky.androidide.utils.MetricsCsvFile
import com.itsaky.androidide.utils.MetricsScratch
import com.itsaky.androidide.utils.MetricsSource
import com.itsaky.androidide.utils.NetworkUsageWatcher
import com.itsaky.androidide.utils.PowerUsageWatcher
import io.sentry.Hint
import io.sentry.SentryEvent
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * What a report carries about the machine that produced it (ADFA-5526).
 *
 * The failure modes matter more than the happy path here: this runs while the process is dying, so
 * anything it throws costs the whole report rather than just the attachment.
 */
@RunWith(RobolectricTestRunner::class)
class MetricsCrashAttachmentTest {
	private val context = ApplicationProvider.getApplicationContext<Context>()

	private val watchers = mutableListOf<MemoryUsageWatcher>()

	@After
	fun tearDown() {
		watchers.forEach { it.stopWatching() }
		watchers.clear()
		MetricsSource.current?.let(MetricsSource::unregister)
		MetricsScratch.resetForTesting()
	}

	private class FakeMetrics(
		override val memoryUsageWatcher: MemoryUsageWatcher,
		override val networkUsageWatcher: NetworkUsageWatcher = NetworkUsageWatcher(uid = 0),
		override val powerUsageWatcher: PowerUsageWatcher =
			PowerUsageWatcher(
				source = {
					PowerUsageWatcher.PowerReading(
						temperatureMilliCelsius = 29_700L,
						powerMicroWatts = -3_400_000L,
						thermalStatus = 0,
						battery = PowerUsageWatcher.BatteryState.UNKNOWN,
					)
				},
			),
		override val annotations: MetricsAnnotationStore = MetricsAnnotationStore(),
	) : MetricsSource.Metrics

	private fun sampledWatcher(): MemoryUsageWatcher =
		MemoryUsageWatcher().also { watcher ->
			watchers += watcher
			watcher.watchProcess(android.os.Process.myPid(), "IDE")
			watcher.readUsages()
		}

	private fun process(): Hint {
		val hint = Hint()
		MetricsCrashAttachment(context).process(SentryEvent(), hint)
		return hint
	}

	@Test
	fun `a report from a session with history carries it, gzipped`() {
		MetricsSource.register(FakeMetrics(sampledWatcher()))

		val attachments = process().attachments

		assertThat(attachments).hasSize(1)
		val attachment = attachments.single()
		assertThat(attachment.contentType).isEqualTo(MetricsCsvFile.COMPRESSED_MIME_TYPE)
		assertThat(attachment.filename).endsWith(".csv.gz")
		// A named file that does not unzip is worse than none: it looks like context and is not.
		val unzipped = GZIPInputStream(File(attachment.pathname!!).inputStream()).bufferedReader().use { it.readText() }
		assertThat(unzipped.lineSequence().first()).startsWith("\"timestamp\"")
		assertThat(unzipped.lineSequence().count()).isAtLeast(2)
	}

	@Test
	fun `a crash before the editor ran attaches nothing`() {
		// Onboarding, the project chooser, direct boot: no watchers exist, and direct boot has no
		// credential-protected cache to write to either.
		assertThat(MetricsSource.current).isNull()

		assertThat(process().attachments).isEmpty()
	}

	@Test
	fun `a session that has sampled nothing attaches nothing`() {
		// A header-only file on every early crash would be noise in the reports, not context.
		MetricsSource.register(FakeMetrics(MemoryUsageWatcher().also(watchers::add)))

		assertThat(process().attachments).isEmpty()
	}

	@Test
	fun `a burst of reports pays for one file, not one each`() {
		MetricsSource.register(FakeMetrics(sampledWatcher()))
		var writes = 0
		var clock = 1_000L
		val processor =
			MetricsCrashAttachment(
				context = context,
				nowMillis = { clock },
				writeFile = { snapshot ->
					writes++
					MetricsCsvFile.writeForReport(context, snapshot)
				},
			)

		// This is registered for every event, not only crashes, and the IDE captures non-fatals
		// deliberately -- in bursts, on whatever thread noticed, the main one included. A full
		// buffer formats and gzips in 10-15ms on a desktop JVM and more on a phone, so paid per
		// event that is a visible stutter per event.
		val filenames =
			(1..5).map {
				clock += 100L
				val hint = Hint()
				processor.process(SentryEvent(), hint)
				hint.attachments.single().filename
			}

		assertThat(writes).isEqualTo(1)
		assertThat(filenames.toSet()).hasSize(1)
	}

	@Test
	fun `a report after the window gets a file of its own`() {
		MetricsSource.register(FakeMetrics(sampledWatcher()))
		var writes = 0
		var clock = 1_000L
		val processor =
			MetricsCrashAttachment(
				context = context,
				nowMillis = { clock },
				writeFile = { snapshot ->
					writes++
					MetricsCsvFile.writeForReport(context, snapshot)
				},
			)

		processor.process(SentryEvent(), Hint())
		// Freshness is what matters at a crash, so the reuse has to expire rather than latch.
		clock += MetricsCrashAttachment.REUSE_WINDOW_MS
		processor.process(SentryEvent(), Hint())

		assertThat(writes).isEqualTo(2)
	}

	@Test
	fun `a reused file that has been pruned away is written again`() {
		MetricsSource.register(FakeMetrics(sampledWatcher()))
		var writes = 0
		val clock = 1_000L
		val processor =
			MetricsCrashAttachment(
				context = context,
				nowMillis = { clock },
				writeFile = { snapshot ->
					writes++
					MetricsCsvFile.writeForReport(context, snapshot)
				},
			)

		val first = Hint()
		processor.process(SentryEvent(), first)
		// MetricsCsvFile keeps only the few most recent, so a file handed out here can be deleted
		// by a later write. An attachment naming a file that is gone is worse than none.
		File(first.attachments.single().pathname!!).delete()

		val second = Hint()
		processor.process(SentryEvent(), second)

		assertThat(writes).isEqualTo(2)
		assertThat(File(second.attachments.single().pathname!!).exists()).isTrue()
	}

	@Test
	fun `the event is returned unchanged even when the attachment fails`() {
		// The whole point of the guard: a report with no metrics beats no report. A watcher whose
		// buffers are a different length than the scratch makes copyInto throw, which is the
		// closest stand-in for the crash-time failures this has to survive.
		MetricsScratch.install(entries = MemoryUsageWatcher.MAX_USAGE_ENTRIES + 1, memorySeries = 3)
		MetricsSource.register(FakeMetrics(sampledWatcher()))

		val event = SentryEvent()
		val returned = MetricsCrashAttachment(context).process(event, Hint())

		assertThat(returned).isSameInstanceAs(event)
	}
}
