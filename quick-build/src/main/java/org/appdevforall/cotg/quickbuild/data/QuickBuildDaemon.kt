package org.appdevforall.cotg.quickbuild.data

import org.appdevforall.cotg.quickbuild.domain.BuildDiagnostic
import java.io.File

/**
 * Typed facade over the warm compile daemon (protocol: quick-build/README.md).
 * The interface exists so the executor and session manager are testable against
 * scripted fakes; [DaemonProcessClient] is the real child-JVM implementation.
 *
 * Contract mirrors the daemon protocol: one request in flight at a time (the
 * orchestrator serializes builds; [DaemonProcessClient] additionally enforces it),
 * and NO method throws for build problems - everything is a [DaemonReply].
 */
interface QuickBuildDaemon {
	/** True while the daemon process is alive and configured. */
	val isRunning: Boolean

	/**
	 * Spawn (or respawn) the daemon process and send `configure`. A running daemon is
	 * shut down first, so this is also the respawn path after a death.
	 */
	suspend fun start(config: DaemonConfig): DaemonReply<Unit>

	/**
	 * Incremental compile. Per the BTA gotchas in the README, [changedFiles] must be
	 * the KNOWN changed set; pass ALL sources as changed to seed IC caches.
	 *
	 * @return the directory containing compiled classes.
	 */
	suspend fun compile(
		allSources: List<File>,
		changedFiles: List<File>,
	): DaemonReply<File>

	/** Dex the given class dirs. @return the produced `classes.dex`. */
	suspend fun dex(classesDirs: List<File>): DaemonReply<File>

	/** aapt2 relink of the project resources. @return the produced `resources.arsc`. */
	suspend fun relink(
		resDirs: List<File>,
		manifest: File,
	): DaemonReply<File>

	/** Liveness probe; false when the daemon is missing or unresponsive. */
	suspend fun ping(): Boolean

	/** Graceful stop; a subsequent exit is deliberate, not a death. */
	suspend fun shutdown()

	/**
	 * Called when the daemon process exits WITHOUT a shutdown request (exit != by our
	 * hand). The session manager routes this into [org.appdevforall.cotg.quickbuild.domain.SessionEvent.DaemonDied].
	 */
	fun setDeathListener(listener: ((exitCode: Int) -> Unit)?)
}

/** Everything the daemon needs to know once per session (`configure` op). */
data class DaemonConfig(
	val projectRoot: File,
	val classpath: List<File>,
	val outDir: File,
	val aapt2: File,
	val d8Jar: File,
	val androidJar: File,
	/** Kotlin compiler plugin jars (-Xplugin), e.g. Compose; session-fixed. */
	val compilerPlugins: List<File> = emptyList(),
)

/**
 * Result of one daemon op. [BuildFailed] is the user's code failing to build (maps to
 * [org.appdevforall.cotg.quickbuild.domain.BuildOutcome.CompileError]); [Failed] is
 * the pipeline itself breaking (daemon dead, protocol I/O error) and maps to
 * [org.appdevforall.cotg.quickbuild.domain.BuildOutcome.InfrastructureFailure].
 */
sealed interface DaemonReply<out T> {
	data class Ok<T>(
		val value: T,
	) : DaemonReply<T>

	data class BuildFailed(
		val diagnostics: List<BuildDiagnostic>,
	) : DaemonReply<Nothing>

	data class Failed(
		val message: String,
		val daemonDied: Boolean = false,
	) : DaemonReply<Nothing>
}
