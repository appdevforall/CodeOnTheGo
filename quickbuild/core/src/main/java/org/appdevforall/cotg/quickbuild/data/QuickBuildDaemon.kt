package org.appdevforall.cotg.quickbuild.data

import org.appdevforall.cotg.quickbuild.daemon.protocol.CompileStats
import org.appdevforall.cotg.quickbuild.daemon.protocol.DexStats
import org.appdevforall.cotg.quickbuild.domain.BuildDiagnostic
import java.io.File

/**
 * Typed facade over the warm compile daemon (protocol: quickbuild/core/README.md).
 *
 * An interface so the executor and session manager can be tested against scripted fakes;
 * [DaemonProcessClient] is the real child-JVM implementation. Mirrors the daemon protocol:
 * one request in flight at a time, and no method throws for build problems - every outcome
 * is a [DaemonReply].
 */
interface QuickBuildDaemon {
	/** True while the daemon process is alive and configured. */
	val isRunning: Boolean

	/**
	 * Filesystem type of the daemon's scratch tree (`ext4`, `f2fs`, `fuse`, ...) as reported
	 * at `configure`; null before a successful configure or from a daemon predating the
	 * field. Session-constant, so it is read once per build rather than carried on every
	 * reply. Recorded alongside build timings because it predicts them: per-file work costs
	 * ~52x more on FUSE-backed emulated storage than on the app's own filesystem
	 * (ADFA-4128 deep-dive).
	 */
	val scratchFsType: String?
		get() = null

	/**
	 * Spawns (or respawns) the daemon process and sends `configure`. A running daemon is
	 * shut down first, so this is also the respawn path after a death.
	 */
	suspend fun start(config: DaemonConfig): DaemonReply<Unit>

	/**
	 * Compiles the project incrementally. [changedFiles] must be the known changed set;
	 * pass all sources as changed to seed the incremental caches.
	 *
	 * @param removedFiles sources deleted since the last build, so their outputs are removed
	 *   and dependents recompiled. A removed `.java`'s stale `.class` is deleted explicitly -
	 *   javac has no incremental removed-files API. Empty is supported.
	 * @return the compiled classes dir plus the .class files this run emitted.
	 */
	suspend fun compile(
		allSources: List<File>,
		changedFiles: List<File>,
		removedFiles: List<File> = emptyList(),
	): DaemonReply<CompileOutput>

	/** Dexes [classesDirs] into one `classes.dex`, with the daemon's step timings. */
	suspend fun dex(classesDirs: List<File>): DaemonReply<DexOutput>

	/**
	 * Relinks the project resources with aapt2; see [RelinkInputs] for the input contract.
	 *
	 * @return the full relinked resource apk (resources.arsc plus every compiled resource
	 *   file), not a bare extracted table - a bare table cannot back a file-typed resource.
	 */
	suspend fun relink(inputs: RelinkInputs): DaemonReply<RelinkOutput>

	/** Liveness probe; false when the daemon is missing or unresponsive. */
	suspend fun ping(): Boolean

	/** Graceful stop; a subsequent exit is deliberate, not a death. */
	suspend fun shutdown()

	/**
	 * Registers a callback for the daemon exiting without a shutdown request. The session
	 * manager routes it into [org.appdevforall.cotg.quickbuild.domain.SessionEvent.DaemonDied].
	 */
	fun setDeathListener(listener: ((exitCode: Int) -> Unit)?)
}

/**
 * A successful `compile` op's output.
 *
 * @property classesDir directory containing the compiled classes.
 * @property changedClassFiles the .class files this run emitted or rewrote, '/'-separated
 *   relative to [classesDir] - the deploy policy's recompiled-set signal. Null when the
 *   daemon did not report it; the policy then decides conservatively (restart over stale).
 * @property kotlinMillis wall time of the daemon's Kotlin pass; null when unreported (a
 *   pre-timing daemon). Same null convention for every step-timing field below.
 * @property javaMillis wall time of the daemon's javac pass.
 * @property stats the phases [kotlinMillis]/[javaMillis] do not cover (output-tree
 *   snapshots, the Java-ABI re-parse) plus this build's counts.
 */
data class CompileOutput(
	val classesDir: File,
	val changedClassFiles: List<String>?,
	val kotlinMillis: Long? = null,
	val javaMillis: Long? = null,
	val stats: CompileStats? = null,
)

/**
 * A successful `dex` op's output: the produced `classes.dex` plus the daemon's step
 * timings (null when unreported by a pre-timing daemon).
 *
 * @property stats how many classes / bytes the pass moved; null when unreported.
 */
data class DexOutput(
	val dexFile: File,
	val stripMillis: Long? = null,
	val d8Millis: Long? = null,
	val stats: DexStats? = null,
)

/**
 * The `relink` op's inputs, bundled into one value so the executor -> facade -> client chain
 * stops accreting positional parameters. Pure carrier: [DaemonProcessClient] still
 * serializes each field as its own protocol key.
 *
 * @property resDirs the project's own `res/` directories to recompile and relink.
 * @property manifest the manifest to link against - the proxy app build's transformed
 *   manifest when available, else the project's raw one.
 * @property stableIdsFile AGP's stable-ids mapping from the proxy app build
 *   ([QuickBuildProjectLayout.stableIdsFile]). Pins resource ids to the baseline's, so
 *   relinking the project's own res/ - a strict subset of what the real build merged -
 *   cannot shift an id out from under the already-compiled manifest. Null relinks unpinned.
 * @property libraryResources pre-compiled `.flat` resource units from the proxy app build
 *   ([QuickBuildProjectLayout.libraryResourceFlats]), covering the merged_res closure and
 *   every resource-providing AAR. Lets a relink resolve resources the project's own res/
 *   never declares (e.g. Material3's `Theme.Material3.DayNight.NoActionBar`). Empty falls
 *   back to relinking against the project's own res/ alone.
 */
data class RelinkInputs(
	val resDirs: List<File>,
	val manifest: File,
	val stableIdsFile: File? = null,
	val libraryResources: List<File> = emptyList(),
)

/**
 * A successful `relink` op's output: the full relinked resource apk plus the daemon's
 * step timings (null when unreported by a pre-timing daemon).
 */
data class RelinkOutput(
	val resourceApk: File,
	val aapt2CompileMillis: Long? = null,
	val aapt2LinkMillis: Long? = null,
)

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
