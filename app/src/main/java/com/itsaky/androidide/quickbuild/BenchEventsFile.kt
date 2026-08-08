package com.itsaky.androidide.quickbuild

import org.json.JSONObject
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Append-only JSON-lines writer for the ADFA-4128 benchmark harness: one JSON object per
 * line. Every line carries the protocol version [V] and a wall-clock stamp so a consumer
 * can version-check and order events; callers add event-specific fields.
 *
 * Contract mirrors the metrics ports this backs: writes are cheap, synchronized, and never
 * throw out - any failure degrades to a logged warning, because instrumentation must never
 * affect a build. The harness truncates or deletes the file between apps (via run-as), so
 * every append recreates the parent directory and reopens in append mode; a vanished file
 * simply reappears on the next line.
 */
class BenchEventsFile(
	private val file: File,
	private val clock: () -> Long = System::currentTimeMillis,
) {
	/**
	 * Appends one event line: `{"v":1,"wallMs":<clock>,"event":<event>, ...[fields]}`.
	 * [fields] runs against the line's [JSONObject] to add event-specific keys. Any
	 * failure (bad path, I/O error) is swallowed with a warning - never propagated.
	 */
	fun append(
		event: String,
		fields: JSONObject.() -> Unit = {},
	) {
		runCatching {
			val obj =
				JSONObject()
					.put("v", V)
					.put("wallMs", clock())
					.put("event", event)
			obj.fields()
			write(obj.toString())
		}.onFailure { log.warn("Dropping bench event '{}'", event, it) }
	}

	@Synchronized
	private fun write(line: String) {
		// The harness may have removed the file (and its dir) since the last line; recreate
		// then append so a between-apps truncation just starts a fresh file.
		file.parentFile?.mkdirs()
		file.appendText(line + "\n")
	}

	companion object {
		/** Bench-events protocol version; bump on any incompatible line-shape change. */
		const val V = 1

		private val log = LoggerFactory.getLogger("QB-BenchEvents")
	}
}
