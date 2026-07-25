package org.appdevforall.cotg.quickbuild.service

import org.appdevforall.cotg.quickbuild.data.CompileOutput
import org.appdevforall.cotg.quickbuild.data.DaemonConfig
import org.appdevforall.cotg.quickbuild.data.DaemonReply
import org.appdevforall.cotg.quickbuild.data.DexOutput
import org.appdevforall.cotg.quickbuild.data.QuickBuildDaemon
import org.appdevforall.cotg.quickbuild.data.QuickBuildPaths
import org.appdevforall.cotg.quickbuild.data.RelinkOutput
import org.appdevforall.cotg.quickbuild.domain.GenerationStore
import java.io.File

/** Scripted [QuickBuildDaemon]: every op records its arguments and replies per script. */
class FakeDaemon : QuickBuildDaemon {
	val startConfigs = mutableListOf<DaemonConfig>()
	val compileCalls = mutableListOf<Pair<List<File>, List<File>>>()

	/** Removed-sources arg of each `compile`, recorded separately for Bug-12 assertions. */
	val compileRemovedFiles = mutableListOf<List<File>>()
	val dexCalls = mutableListOf<List<File>>()
	val relinkCalls = mutableListOf<RelinkCall>()
	var shutdownCount = 0

	/** One recorded `relink` call - a data class (not a Triple) so it can carry libraryResources too. */
	data class RelinkCall(
		val resDirs: List<File>,
		val manifest: File,
		val stableIdsFile: File?,
		val libraryResources: List<File>,
	)

	var startReply: DaemonReply<Unit> = DaemonReply.Ok(Unit)
	var compileReply: DaemonReply<CompileOutput> =
		DaemonReply.Ok(CompileOutput(File("/fake/classes"), changedClassFiles = emptyList()))
	var dexReply: DaemonReply<DexOutput> = DaemonReply.Ok(DexOutput(File("/fake/classes.dex")))
	var relinkReply: DaemonReply<RelinkOutput> = DaemonReply.Ok(RelinkOutput(File("/fake/resources.arsc")))

	var deathListener: ((Int) -> Unit)? = null
		private set

	override var isRunning: Boolean = false

	override suspend fun start(config: DaemonConfig): DaemonReply<Unit> {
		startConfigs += config
		if (startReply is DaemonReply.Ok) isRunning = true
		return startReply
	}

	override suspend fun compile(
		allSources: List<File>,
		changedFiles: List<File>,
		removedFiles: List<File>,
	): DaemonReply<CompileOutput> {
		compileCalls += allSources to changedFiles
		compileRemovedFiles += removedFiles
		return compileReply
	}

	override suspend fun dex(classesDirs: List<File>): DaemonReply<DexOutput> {
		dexCalls += classesDirs
		return dexReply
	}

	override suspend fun relink(
		resDirs: List<File>,
		manifest: File,
		stableIdsFile: File?,
		libraryResources: List<File>,
	): DaemonReply<RelinkOutput> {
		relinkCalls += RelinkCall(resDirs, manifest, stableIdsFile, libraryResources)
		return relinkReply
	}

	override suspend fun ping(): Boolean = isRunning

	override suspend fun shutdown() {
		shutdownCount++
		isRunning = false
	}

	override fun setDeathListener(listener: ((Int) -> Unit)?) {
		deathListener = listener
	}

	fun die(exitCode: Int) {
		isRunning = false
		deathListener?.invoke(exitCode)
	}
}

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
		return result
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

class FakePaths(
	baseDir: File,
) : QuickBuildPaths {
	override val javaBinary = File(baseDir, "jdk/bin/java")
	override val daemonJar = File(baseDir, "quickbuild/daemon/quickbuild-daemon.jar")
	override val runtimeAar = File(baseDir, "quickbuild/quickbuild-runtime.aar")
	override val aapt2 = File(baseDir, "sdk/aapt2")
	override val d8Jar = File(baseDir, "sdk/d8.jar")
	override val composeCompilerPlugin = File(baseDir, "quickbuild/daemon/compose-compiler-plugin.jar")
	override val androidJar = File(baseDir, "sdk/android.jar")

	override fun daemonEnvironment(): Map<String, String> = emptyMap()
}

/**
 * In-memory [QuickBuildHistoryStore]. Defaults to `hasUsedQuickBuild = true` (the "warm
 * path") so the many [QuickBuildSessionManagerTest] cases exercising prewarm/tap
 * mechanics don't need to touch the gate; tests of the gate itself flip it to false.
 */
class FakeQuickBuildHistoryStore : QuickBuildHistoryStore {
	private var used = true

	override fun hasUsedQuickBuild(): Boolean = used

	override fun setHasUsedQuickBuild(used: Boolean) {
		this.used = used
	}
}
