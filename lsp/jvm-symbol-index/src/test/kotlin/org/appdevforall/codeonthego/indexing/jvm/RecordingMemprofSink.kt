package org.appdevforall.codeonthego.indexing.jvm

import com.itsaky.androidide.memprof.MemprofSink
import com.itsaky.androidide.memprof.MemprofSpan
import java.util.concurrent.CopyOnWriteArrayList

/** A [MemprofSink] that keeps every phase it was asked to begin. */
class RecordingMemprofSink : MemprofSink {
	/** A phase begun on this sink, with the details put on it and how it finished. */
	class Phase(
		val title: String,
		val marker: String,
	) : MemprofSpan {
		val details = mutableMapOf<String, Long>()
		var ended = false
		var abandoned = false

		override val isRecording = true

		override fun put(
			key: String,
			value: Long,
		) {
			details[key] = value
		}

		override fun end() {
			if (!abandoned) ended = true
		}

		override fun abandon() {
			if (!ended) abandoned = true
		}
	}

	val phases = CopyOnWriteArrayList<Phase>()

	override fun beginPhase(
		title: String,
		marker: String,
	): MemprofSpan = Phase(title, marker).also { phases += it }

	override fun beginSection(
		name: String,
		detail: String?,
	): MemprofSpan = MemprofSpan.None

	override fun mark(marker: String) = Unit
}
