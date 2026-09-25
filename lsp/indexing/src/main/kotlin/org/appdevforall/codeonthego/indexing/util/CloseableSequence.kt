package org.appdevforall.codeonthego.indexing.util

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A [Sequence] that holds a resource open while it is iterated, and releases it on [close].
 *
 * A `sequence {}` builder that is abandoned part-way, because its consumer failed or was cancelled,
 * is never resumed, so a `use` or `finally` inside it never runs. The consumer closes this instead.
 * [close] runs [onClose] at most once and is safe after the sequence was fully iterated.
 */
class CloseableSequence<T>(
	private val delegate: Sequence<T>,
	private val onClose: () -> Unit,
) : Sequence<T> by delegate,
	Closeable {
	private val closed = AtomicBoolean(false)

	override fun close() {
		if (closed.compareAndSet(false, true)) onClose()
	}
}
