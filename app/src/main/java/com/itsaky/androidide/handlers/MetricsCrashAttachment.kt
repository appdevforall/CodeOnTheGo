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
import com.itsaky.androidide.utils.MetricsCsvFile
import com.itsaky.androidide.utils.MetricsSnapshotAssembler
import com.itsaky.androidide.utils.MetricsSource
import io.sentry.Attachment
import io.sentry.EventProcessor
import io.sentry.Hint
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Attaches the carousel's metrics to every report the IDE sends (ADFA-5526).
 *
 * A crash arrives with a stack and no idea what the machine was doing. The minutes of memory,
 * network, temperature and power leading up to it are what turn "it died" into a diagnosis -- and
 * for an out-of-memory kill they are most of the answer.
 *
 * Registered as a Sentry [EventProcessor] beside [GlitchTipDiagnosticsContext], not on the uncaught
 * exception handler, so it also covers the non-fatal `Sentry.captureException` calls the IDE makes
 * deliberately.
 *
 * ADFA-5494 will keep this history across process death; this does not need it. A crash is the one
 * loss cause with a hookable moment, which is exactly why it can be served on its own. The kill that
 * 5494 exists for produces no report at all -- nothing runs on a SIGKILL -- so it was never this
 * ticket's case.
 */
class MetricsCrashAttachment(
	private val context: Context,
) : EventProcessor {
	override fun process(
		event: SentryEvent,
		hint: Hint,
	): SentryEvent {
		// Everything, including Errors. This runs while the process is dying, and an OutOfMemoryError
		// raised in here would replace a useful report with no report -- losing the attachment is the
		// right way to fail. runCatching is what makes that true: it catches Throwable.
		runCatching { attach(hint) }
			.onFailure { failure -> log.warn("Could not attach the metrics file to the report", failure) }
		return event
	}

	private fun attach(hint: Hint) {
		// No source before the editor has run: a crash in onboarding, in the project chooser or in
		// direct boot has no history to report, and direct boot has no credential-protected cache to
		// write it to either.
		val metrics = MetricsSource.current ?: return
		val file = writeSnapshot(metrics) ?: return
		hint.addAttachment(Attachment(file.absolutePath, file.name, MetricsCsvFile.COMPRESSED_MIME_TYPE))
	}

	private fun writeSnapshot(metrics: MetricsSource.Metrics): File? =
		MetricsSnapshotAssembler.withSnapshot(
			context = context,
			memory = metrics.memoryUsageWatcher,
			network = metrics.networkUsageWatcher,
			power = metrics.powerUsageWatcher,
			annotations = metrics.annotations,
		) { snapshot ->
			// Nothing sampled yet is nothing to say. A header-only attachment on every early crash
			// would be noise in the reports rather than context.
			if (!snapshot.hasRows) null else MetricsCsvFile.writeForReport(context, snapshot)
		}

	companion object {
		private val log = LoggerFactory.getLogger(MetricsCrashAttachment::class.java)

		/** Registers this processor. Call once, from within `SentryAndroid.init`. */
		fun install(
			options: SentryOptions,
			context: Context,
		) {
			options.addEventProcessor(MetricsCrashAttachment(context))
		}
	}
}
