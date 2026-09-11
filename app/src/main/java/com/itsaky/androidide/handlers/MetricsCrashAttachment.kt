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
import android.os.SystemClock
import com.itsaky.androidide.utils.MetricsCsv
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
	private val nowMillis: () -> Long = SystemClock::elapsedRealtime,
	private val writeFile: (MetricsCsv.Snapshot) -> File? = { snapshot ->
		MetricsCsvFile.writeForReport(context, snapshot)
	},
) : EventProcessor {
	/**
	 * The last file written, and when. Reused rather than rewritten for a moment afterwards.
	 *
	 * Not synchronised: two events racing here write two files and one of them wins the field,
	 * which costs a write and loses nothing. A lock would be the more expensive mistake, since this
	 * runs on the thread of whatever is being reported.
	 */
	@Volatile
	private var recent: Recent? = null

	private class Recent(
		val atMillis: Long,
		val file: File,
	)

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

	/**
	 * The file to attach, writing one if the last is too old to stand in.
	 *
	 * This runs on the thread of whatever is being reported, and it is not cheap: a full buffer is
	 * 3600 rows, which format and gzip in 10-15ms on a desktop JVM and a good deal more on a phone.
	 * A crash pays that once and it does not matter. But this processor is deliberately registered
	 * for *every* event, including the non-fatal `Sentry.captureException` calls the IDE makes on
	 * purpose -- and those arrive in bursts, on whatever thread noticed, the main one included. Paid
	 * per event that is a visible stutter per event.
	 *
	 * So a file written moments ago is handed out again instead. The window is short because
	 * freshness matters most at exactly the moment this is for: a crash gets at most
	 * [REUSE_WINDOW_MS] less of its own tail, while a burst of non-fatals collapses to one write.
	 * Every event still gets an attachment, which distinguishing crashes from non-fatals would not
	 * manage here -- the IDE reports its own crashes through a plain `captureException`, so
	 * `SentryEvent.isCrashed` is false for them and there is nothing at this level to tell the two
	 * apart.
	 *
	 * The existence check is not belt and braces: [MetricsCsvFile] prunes its directory to the few
	 * most recent, so a file handed out here can be deleted by a later write.
	 */
	private fun writeSnapshot(metrics: MetricsSource.Metrics): File? {
		val now = nowMillis()
		recent?.let { last ->
			if (now - last.atMillis < REUSE_WINDOW_MS && last.file.exists()) {
				return last.file
			}
		}

		val file =
			MetricsSnapshotAssembler.withSnapshot(
				context = context,
				memory = metrics.memoryUsageWatcher,
				network = metrics.networkUsageWatcher,
				power = metrics.powerUsageWatcher,
				annotations = metrics.annotations,
			) { snapshot ->
				// Nothing sampled yet is nothing to say. A header-only attachment on every early
				// crash would be noise in the reports rather than context.
				if (!snapshot.hasRows) null else writeFile(snapshot)
			}
		if (file != null) {
			recent = Recent(now, file)
		}
		return file
	}

	companion object {
		private val log = LoggerFactory.getLogger(MetricsCrashAttachment::class.java)

		/**
		 * How long a written file stands in for the next one.
		 *
		 * Short deliberately: the cost this bounds is a burst of non-fatals, which arrive far
		 * faster than this, and the thing it risks is the tail of a crash, which is the part worth
		 * having.
		 */
		const val REUSE_WINDOW_MS = 5_000L

		/** Registers this processor. Call once, from within `SentryAndroid.init`. */
		fun install(
			options: SentryOptions,
			context: Context,
		) {
			options.addEventProcessor(MetricsCrashAttachment(context))
		}
	}
}
