package com.itsaky.androidide.lsp.kotlin.compiler.index

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select

internal class WorkerQueue<T> {

	private val scanChannel = Channel<T>(capacity = 100)
	private val editChannel = Channel<T>(capacity = 20)
	private val indexChannel = Channel<T>(capacity = 100)

	suspend fun putScanQueue(item: T) = scanChannel.send(item)
	suspend fun putEditQueue(item: T) = editChannel.send(item)
	suspend fun putIndexQueue(item: T) = indexChannel.send(item)

	suspend fun take(): T {
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