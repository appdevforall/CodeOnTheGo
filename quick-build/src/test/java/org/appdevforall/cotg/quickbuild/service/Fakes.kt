package org.appdevforall.cotg.quickbuild.service

import org.appdevforall.cotg.quickbuild.data.CompileOutput
import org.appdevforall.cotg.quickbuild.data.DaemonConfig
import org.appdevforall.cotg.quickbuild.data.DaemonReply
import org.appdevforall.cotg.quickbuild.data.DexOutput
import org.appdevforall.cotg.quickbuild.data.QuickBuildDaemon
import org.appdevforall.cotg.quickbuild.data.QuickBuildPaths
import org.appdevforall.cotg.quickbuild.data.RelinkInputs
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
	val relinkCalls = mutableListOf<RelinkInputs>()
	var shutdownCount = 0

	var startReply: DaemonReply<Unit> = DaemonReply.Ok(Unit)
	var compileReply: DaemonReply<CompileOutput> =
		DaemonReply.Ok(CompileOutput(File("/fake/classes"), changedClassFiles = emptyList()))
	var dexReply: DaemonReply<DexOutput> = DaemonReply.Ok(DexOutput(File("/fake/classes.dex")))
	var relinkReply: DaemonReply<RelinkOutput> = DaemonReply.Ok(RelinkOutput(File("/fake/resources.arsc")))

	var deathListener: ((Int) -> Unit)? = null
		private set

	override var isRunning: Boolean = false

	/** Null by default, matching a daemon that reports no filesystem for its scratch tree. */
	override var scratchFsType: String? = null

	/**
	 * When set, the NEXT [start] parks here after recording its config, consuming the
	 * gate - later starts pass through. Lets a race test hold a respawn mid-start while
	 * something else (a rebaseline, a teardown) takes the daemon down.
	 */
	var startGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

	override suspend fun start(config: DaemonConfig): DaemonReply<Unit> {
		startConfigs += config
		startGate?.let { gate ->
			startGate = null
			gate.await()
		}
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

	override suspend fun relink(inputs: RelinkInputs): DaemonReply<RelinkOutput> {
		relinkCalls += inputs
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

	/** Stands in for the app's noBackupFilesDir subtree; a temp dir in tests. */
	override val projectScratchRoot = File(baseDir, "app-private/quickbuild-scratch")

	override fun daemonEnvironment(): Map<String, String> = emptyMap()
}

/**
 * In-memory [QuickBuildHistoryStore]. Defaults to `hasUsedQuickBuild = true` (the "warm
 * path") so the many [QuickBuildSessionManagerTest] cases exercising prebuild/tap
 * mechanics don't need to touch the gate; tests of the gate itself flip it to false.
 */
class FakeQuickBuildHistoryStore : QuickBuildHistoryStore {
	private var used = true

	/**
	 * Thrown by [setHasUsedQuickBuild] when set. Stands in for any real store failure
	 * (no project open, unwritable preferences): recording history is bookkeeping and must
	 * never be able to swallow the tap that triggered it.
	 */
	var writeError: Throwable? = null

	/** Runs on every [setHasUsedQuickBuild], so a test can observe WHEN the write lands. */
	var onWrite: () -> Unit = {}

	override fun hasUsedQuickBuild(): Boolean = used

	override fun setHasUsedQuickBuild(used: Boolean) {
		onWrite()
		writeError?.let { throw it }
		this.used = used
	}
}
