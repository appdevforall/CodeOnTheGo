package org.appdevforall.cotg.quickbuild.service

import org.appdevforall.cotg.quickbuild.domain.reload.GenerationStore
import org.appdevforall.cotg.quickbuild.service.deploy.DeployResult
import org.appdevforall.cotg.quickbuild.service.deploy.DeploySender
import java.io.File

/** Recording [DeploySender] with a scripted result. */
class FakeDeploy : DeploySender {
	data class Call(
		val generation: Long,
		val dexFile: File?,
		val arscFile: File?,
		val assetsZip: File?,
		val metadataJson: String,
	)

	val calls = mutableListOf<Call>()
	val statusCalls = mutableListOf<String>()
	val awaitDisconnectCalls = mutableListOf<Long>()
	val awaitReconnectCalls = mutableListOf<Long>()
	var result: DeployResult = DeployResult.Reloaded(40)

	/** When non-empty, each deploy consumes the next entry instead of [result]. */
	val resultQueue = ArrayDeque<DeployResult>()
	var disconnects: Boolean = true

	/**
	 * Generation the fake "relaunched app" reconnects at, given the last deployed
	 * generation; return null for a relaunch that never reconnects. Defaults to a
	 * clean restart (reconnects at the deployed generation).
	 */
	var reconnectGeneration: (deployedGeneration: Long?) -> Long? = { it }

	override suspend fun deploy(
		generation: Long,
		dexFile: File?,
		arscFile: File?,
		assetsZip: File?,
		metadataJson: String,
	): DeployResult {
		calls += Call(generation, dexFile, arscFile, assetsZip, metadataJson)
		return resultQueue.removeFirstOrNull() ?: result
	}

	override fun notifyBuildStatus(statusJson: String) {
		statusCalls += statusJson
	}

	override suspend fun awaitDisconnect(timeoutMillis: Long): Boolean {
		awaitDisconnectCalls += timeoutMillis
		return disconnects
	}

	override suspend fun awaitReconnect(timeoutMillis: Long): Long? {
		awaitReconnectCalls += timeoutMillis
		return reconnectGeneration(calls.lastOrNull()?.generation)
	}
}

class MemoryGenerationStore : GenerationStore {
	var value: Long? = null

	override fun load(): Long? = value

	override fun save(generation: Long) {
		value = generation
	}
}
