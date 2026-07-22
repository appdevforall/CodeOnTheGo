package org.appdevforall.cotg.quickbuild.service

import org.appdevforall.cotg.quickbuild.data.CompileOutput
import org.appdevforall.cotg.quickbuild.data.DaemonConfig
import org.appdevforall.cotg.quickbuild.data.DaemonReply
import org.appdevforall.cotg.quickbuild.data.QuickBuildDaemon
import org.appdevforall.cotg.quickbuild.data.QuickBuildPaths
import org.appdevforall.cotg.quickbuild.domain.GenerationStore
import java.io.File

/** Scripted [QuickBuildDaemon]: every op records its arguments and replies per script. */
class FakeDaemon : QuickBuildDaemon {
	val startConfigs = mutableListOf<DaemonConfig>()
	val compileCalls = mutableListOf<Pair<List<File>, List<File>>>()
	val dexCalls = mutableListOf<List<File>>()
	val relinkCalls = mutableListOf<Pair<List<File>, File>>()
	var shutdownCount = 0

	var startReply: DaemonReply<Unit> = DaemonReply.Ok(Unit)
	var compileReply: DaemonReply<CompileOutput> =
		DaemonReply.Ok(CompileOutput(File("/fake/classes"), changedClassFiles = emptyList()))
	var dexReply: DaemonReply<File> = DaemonReply.Ok(File("/fake/classes.dex"))
	var relinkReply: DaemonReply<File> = DaemonReply.Ok(File("/fake/resources.arsc"))

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
	): DaemonReply<CompileOutput> {
		compileCalls += allSources to changedFiles
		return compileReply
	}

	override suspend fun dex(classesDirs: List<File>): DaemonReply<File> {
		dexCalls += classesDirs
		return dexReply
	}

	override suspend fun relink(
		resDirs: List<File>,
		manifest: File,
	): DaemonReply<File> {
		relinkCalls += resDirs to manifest
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
 * In-memory [QuickBuildModeStore]. Defaults to `hasUsedQuickBuild = true` (the "warm
 * path") so the many [QuickBuildSessionManagerTest] cases exercising prewarm/tap
 * mechanics don't need to touch the gate; tests of the gate itself flip it to false.
 */
class FakeQuickBuildModeStore : QuickBuildModeStore {
	private var enabled = false
	private var pinned: Int? = null
	private var confirmed = false
	private var realAppId: String? = null
	private var restorePending = false
	private var used = true

	override fun isSameAppIdEnabled(): Boolean = enabled

	override fun setSameAppIdEnabled(enabled: Boolean) {
		this.enabled = enabled
	}

	override fun pinnedVersionCode(): Int? = pinned

	override fun setPinnedVersionCode(versionCode: Int?) {
		pinned = versionCode
	}

	override fun isClobberConfirmed(): Boolean = confirmed

	override fun setClobberConfirmed(confirmed: Boolean) {
		this.confirmed = confirmed
	}

	override fun episodeRealApplicationId(): String? = realAppId

	override fun setEpisodeRealApplicationId(applicationId: String?) {
		realAppId = applicationId
	}

	override fun isRestoreDowngradePending(): Boolean = restorePending

	override fun setRestoreDowngradePending(pending: Boolean) {
		restorePending = pending
	}

	override fun hasUsedQuickBuild(): Boolean = used

	override fun setHasUsedQuickBuild(used: Boolean) {
		this.used = used
	}
}
