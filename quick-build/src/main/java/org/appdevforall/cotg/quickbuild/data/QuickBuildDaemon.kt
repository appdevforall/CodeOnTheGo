package org.appdevforall.cotg.quickbuild.data

import org.appdevforall.cotg.quickbuild.daemon.protocol.CompileStats
import org.appdevforall.cotg.quickbuild.daemon.protocol.DexStats
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
	 * Filesystem type of the daemon's scratch tree (`ext4`, `f2fs`, `fuse`, ...) as the
	 * daemon reported it at `configure`; null before a successful configure, or from a
	 * daemon predating the field. Session-constant, so it is read once per build rather
	 * than threaded through every reply.
	 *
	 * It travels with the build timing because it is the strongest single predictor of
	 * that timing - the daemon's per-file work costs ~52x more on Android's FUSE-backed
	 * emulated storage than on the app's own filesystem (ADFA-4128 deep-dive).
	 */
	val scratchFsType: String?
		get() = null

	/**
	 * Spawn (or respawn) the daemon process and send `configure`. A running daemon is
	 * shut down first, so this is also the respawn path after a death.
	 */
	suspend fun start(config: DaemonConfig): DaemonReply<Unit>

	/**
	 * Incremental compile. Per the BTA gotchas in the README, [changedFiles] must be
	 * the KNOWN changed set; pass ALL sources as changed to seed IC caches.
	 *
	 * @param removedFiles sources DELETED since the last build. Threaded into the
	 *   incremental compiler's removed-sources slot so their outputs are deleted and
	 *   dependents recompiled (a removed `.java`'s stale `.class` is deleted explicitly,
	 *   since javac has no incremental removed-files API). Optional and backward-compatible:
	 *   empty is the pre-Bug-12 behavior.
	 * @return the compiled classes dir plus the .class files this run emitted.
	 */
	suspend fun compile(
		allSources: List<File>,
		changedFiles: List<File>,
		removedFiles: List<File> = emptyList(),
	): DaemonReply<CompileOutput>

	/** Dex the given class dirs. @return the produced `classes.dex` plus step timings. */
	suspend fun dex(classesDirs: List<File>): DaemonReply<DexOutput>

	/**
	 * aapt2 relink of the project resources; see [RelinkInputs] for the input contract.
	 *
	 * @return the full relinked resource apk (resources.arsc plus every compiled
	 *   resource file - layouts, drawable XMLs, adaptive-icon XMLs, ...), not a bare
	 *   extracted table. A bare table cannot back a file-typed resource; see
	 *   `Aapt2Link`'s KDoc (ADFA-4128 Bug 5).
	 */
	suspend fun relink(inputs: RelinkInputs): DaemonReply<RelinkOutput>

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

/**
 * A successful `compile` op's output.
 *
 * @property classesDir directory containing the compiled classes.
 * @property changedClassFiles the .class files this run emitted or rewrote,
 *   '/'-separated relative to [classesDir] — the deploy policy's recompiled-set
 *   signal. Null when the daemon did not report the field (a pre-signal daemon);
 *   the policy then decides conservatively (restart over stale).
 * @property kotlinMillis wall time of the daemon's Kotlin pass; null when unreported
 *   (a pre-timing daemon). Same null convention for every step-timing field below.
 * @property javaMillis wall time of the daemon's javac pass.
 * @property stats the phases [kotlinMillis]/[javaMillis] do not cover (output-tree
 *   snapshots, the Java-ABI re-parse) plus this build's counts; null from a daemon that
 *   predates them.
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
 * The `relink` op's inputs, bundled into one value so the executor -> facade -> client
 * chain stops accreting positional parameters (07-23 ARCH-REVIEW). Pure carrier: the
 * wire format is unchanged - [DaemonProcessClient] still serializes each field as its
 * own protocol key.
 *
 * @property resDirs the project's own `res/` directories to recompile + relink.
 * @property manifest the manifest to link against - the proxy app build's TRANSFORMED
 *   manifest when available, else the project's raw one.
 * @property stableIdsFile AGP's stable-ids mapping from the proxy app build's real resource
 *   processing ([QuickBuildProjectLayout.stableIdsFile]), if any. Pins the relink's
 *   resource ids to the baseline's so a relink of the project's own res/ (a strict
 *   subset of what the real build merged in) can't shift a resource's numeric id out
 *   from under the manifest the proxy app build already compiled (ADFA-4128 Bug 6). Null
 *   is a supported, backward-compatible fallback to the pre-fix (unstable) relink.
 * @property libraryResources pre-compiled `.flat` resource units from the proxy app build's
 *   real AGP resource processing ([QuickBuildProjectLayout.libraryResourceFlats]) -
 *   the project's own merged_res closure plus every resource-providing AAR's
 *   compiled file resources. Lets a relink resolve a resource a dependency AAR
 *   provides (e.g. Material3's `Theme.Material3.DayNight.NoActionBar`), which the
 *   project's own res/ never declares (ADFA-4128 Bug 8). Empty is a supported,
 *   backward-compatible fallback to the pre-fix behavior (project res/ only).
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
