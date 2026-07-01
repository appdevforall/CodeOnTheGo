package com.itsaky.androidide.lsp.kotlin.compiler.index

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select

internal class WorkerQueue<T> {

	private val scanChannel = Channel<T>(capacity = 100)
	private val editChannel = Channel<T>(capacity = 20)
	private val indexChannel = Channel<T>(capacity = 100)

	// Single-slot pushback for an index-queue item that was polled (to coalesce
	// removals) but turned out not to be batchable. It is returned ahead of the
	// channels by the next [take], preserving command order.
	private var pushedBack: T? = null

	suspend fun putScanQueue(item: T) = scanChannel.send(item)
	suspend fun putEditQueue(item: T) = editChannel.send(item)
	suspend fun putIndexQueue(item: T) = indexChannel.send(item)

	/**
	 * Non-blocking poll of the index queue. Returns the next already-available
	 * index-queue item, or `null` if none is immediately ready.
	 *
	 * Used to coalesce a run of consecutive removal commands into a single
	 * batched index operation (see [IndexWorker]) instead of issuing one
	 * transaction per command. A polled item that is not batchable must be
	 * returned via [pushBackIndexQueue] so it is not dropped.
	 */
	fun pollIndexQueue(): T? = indexChannel.tryReceive().getOrNull()

	/**
	 * Return an item previously obtained from [pollIndexQueue] to the front of
	 * the queue so the next [take] yields it before any channel item. At most
	 * one item may be pushed back at a time.
	 */
	fun pushBackIndexQueue(item: T) {
		check(pushedBack == null) { "pushBack slot already occupied" }
		pushedBack = item
	}

	suspend fun take(): T {
		pushedBack?.let { pushedBack = null; return it }

		scanChannel.tryReceive().getOrNull()?.let { return it }
		editChannel.tryReceive().getOrNull()?.let { return it }
		indexChannel.tryReceive().getOrNull()?.let { return it }

		return select {
			scanChannel.onReceive { it }
			editChannel.onReceive { it }
			indexChannel.onReceive { it }
		}
	}
}