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
import com.itsaky.androidide.utils.MetricsScratch
import com.itsaky.androidide.utils.MetricsSource
import com.itsaky.androidide.utils.NetworkUsageWatcher
import com.itsaky.androidide.utils.PowerUsageWatcher
import io.sentry.Hint
import io.sentry.ITransportFactory
import io.sentry.Sentry
import io.sentry.SentryEnvelope
import io.sentry.SentryItemType
import io.sentry.SentryOptions
import io.sentry.transport.ITransport
import io.sentry.transport.RateLimiter
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.zip.GZIPInputStream

/**
 * The last hop: that the attachment this handler puts on a [Hint] reaches the envelope Sentry sends.
 *
 * Everything up to the Hint is covered by [MetricsCrashAttachmentTest]. This runs the real SDK with
 * a transport that keeps what it is handed, because the hop itself is the SDK's to make and asserting
 * on our own call proves nothing about it.
 *
 * Why not on a device: a crash there does reach this processor -- verified, it writes its file -- but
 * the IDE's own uncaught handler calls exitProcess straight after capturing, so no event envelope
 * survives to disk to be read back. That is worth its own ticket and is not this hop.
 */
@RunWith(RobolectricTestRunner::class)
class MetricsCrashAttachmentEnvelopeTest {
	private val context = ApplicationProvider.getApplicationContext<Context>()

	private val sent = mutableListOf<SentryEnvelope>()

	private val watchers = mutableListOf<MemoryUsageWatcher>()

	@After
	fun tearDown() {
		Sentry.close()
		watchers.forEach { it.stopWatching() }
		watchers.clear()
		MetricsSource.current?.let(MetricsSource::unregister)
		MetricsScratch.resetForTesting()
		sent.clear()
	}

	private inner class RecordingTransport : ITransport {
		override fun send(
			envelope: SentryEnvelope,
			hint: Hint,
		) {
			sent += envelope
		}

		override fun flush(timeoutMillis: Long) = Unit

		override fun getRateLimiter(): RateLimiter? = null

		override fun close(isRestarting: Boolean) = Unit

		override fun close() = Unit
	}

	private fun sampledMetrics(): MetricsSource.Metrics {
		val memory =
			MemoryUsageWatcher().also { watcher ->
				watchers += watcher
				watcher.watchProcess(android.os.Process.myPid(), "IDE")
				watcher.readUsages()
			}
		return object : MetricsSource.Metrics {
			override val memoryUsageWatcher = memory
			override val networkUsageWatcher = NetworkUsageWatcher(uid = 0)
			override val powerUsageWatcher =
				PowerUsageWatcher(
					source = {
						PowerUsageWatcher.PowerReading(
							temperatureMilliCelsius = 29_700L,
							powerMicroWatts = -3_400_000L,
							thermalStatus = 0,
							battery = PowerUsageWatcher.BatteryState.UNKNOWN,
						)
					},
				)
			override val annotations = MetricsAnnotationStore()
		}
	}

	private fun startSentry() {
		Sentry.init { options: SentryOptions ->
			// A DSN that cannot resolve, and a transport that never touches the network anyway.
			options.dsn = "https://0123456789abcdef0123456789abcdef@sentry.invalid/1"
			options.isEnableUncaughtExceptionHandler = false
			options.setTransportFactory { _, _ -> RecordingTransport() }
			MetricsCrashAttachment.install(options, context)
		}
	}

	private fun attachmentsOf(envelope: SentryEnvelope) = envelope.items.filter { it.header.type == SentryItemType.Attachment }

	@Test
	fun `the metrics file arrives in the envelope Sentry sends`() {
		MetricsSource.register(sampledMetrics())
		startSentry()

		Sentry.captureException(RuntimeException("boom"))

		assertThat(sent).isNotEmpty()
		val attachments = sent.flatMap(::attachmentsOf)
		val metrics =
			attachments.single {
				it.header.fileName
					.orEmpty()
					.endsWith(".csv.gz")
			}
		assertThat(metrics.header.contentType).isEqualTo("application/gzip")
		// The bytes have to survive the trip, not just the filename: an envelope carrying a name and
		// no readable payload would look like context and be none.
		val csv = GZIPInputStream(metrics.data.inputStream()).bufferedReader().use { it.readText() }
		assertThat(csv.lineSequence().first()).startsWith("\"timestamp\"")
		assertThat(csv.lineSequence().count()).isAtLeast(2)
	}

	@Test
	fun `an envelope from a session with no samples carries no metrics attachment`() {
		MetricsSource.register(
			object : MetricsSource.Metrics {
				override val memoryUsageWatcher = MemoryUsageWatcher().also(watchers::add)
				override val networkUsageWatcher = NetworkUsageWatcher(uid = 0)
				override val powerUsageWatcher =
					PowerUsageWatcher(
						source = {
							PowerUsageWatcher.PowerReading(0L, 0L, 0, PowerUsageWatcher.BatteryState.UNKNOWN)
						},
					)
				override val annotations = MetricsAnnotationStore()
			},
		)
		startSentry()

		Sentry.captureException(RuntimeException("boom"))

		assertThat(sent).isNotEmpty()
		assertThat(
			sent.flatMap(::attachmentsOf).filter {
				it.header.fileName
					.orEmpty()
					.endsWith(".csv.gz")
			},
		).isEmpty()
	}
}
