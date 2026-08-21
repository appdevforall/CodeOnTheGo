package org.appdevforall.cotg.quickbuild.data

import org.appdevforall.cotg.quickbuild.domain.reload.BuildDiagnostic
import org.appdevforall.cotg.quickbuild.protocol.CompileStats
import org.appdevforall.cotg.quickbuild.protocol.ConfigureRequest
import org.appdevforall.cotg.quickbuild.protocol.DexStats
import java.io.File

/**
 * Typed facade over the warm compile daemon (protocol: quickbuild/README.md).
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
	 * Filesystem type of the daemon's scratch tree (`ext4`, `f2fs`, `fuse`, ...) as reported at
	 * `configure`; null before a successful configure or from a daemon predating the field.
	 * Session-constant, so it is read once per build rather than carried on every reply.
	 * Recorded alongside build timings because it predicts them: per-file work costs ~52x more
	 * on FUSE-backed emulated storage than on the app's own filesystem (measured for ADFA-4128).
	 */
	val scratchFsType: String?
		get() = null

	/**
	 * Spawns (or respawns) the daemon process and sends `configure`. A running daemon is
	 * shut down first, so this is also the respawn path after a death.
	 *
	 * @param config the session-fixed settings; the implementation may retain it for the
	 *   lifetime of the process, so callers must not mutate the files it names mid-session.
	 * @return [DaemonReply.Ok] once the daemon is configured and ready for ops, else
	 *   [DaemonReply.Failed] - a spawn or configure problem is infrastructure, never a
	 *   [DaemonReply.BuildFailed].
	 */
	suspend fun start(config: DaemonConfig): DaemonReply<Unit>

	/**
	 * Compiles the project incrementally. [changedFiles] must be the known changed set;
	 * pass all sources as changed to seed the incremental caches.
	 *
	 * @param allSources every `.kt`/`.java` in scope this session, not just the dirty ones -
	 *   the daemon needs the full set to resolve references and to prune its caches.
	 * @param changedFiles the sources to treat as dirty; a subset of [allSources].
	 * @param removedFiles sources deleted since the last build, so their outputs are removed and
	 *   dependents recompiled (a removed `.java`'s stale `.class` is deleted explicitly, since
	 *   javac has no incremental removed-files API); may be empty.
	 * @return the compiled classes dir plus the .class files this run emitted.
	 */
	suspend fun compile(
		allSources: List<File>,
		changedFiles: List<File>,
		removedFiles: List<File> = emptyList(),
	): DaemonReply<CompileOutput>

	/**
	 * Dexes [classesDirs] into one `classes.dex`, with the daemon's step timings.
	 *
	 * @param classesDirs class-output directories to merge into the single dex, in the order
	 *   they should be read; typically the compile output plus the proxy classes.
	 * @return the produced dex plus timings, or the failure arm the op ended in.
	 */
	suspend fun dex(classesDirs: List<File>): DaemonReply<DexOutput>

	/**
	 * Relinks the project resources with aapt2; see [RelinkInputs] for the input contract.
	 *
	 * @param inputs the res dirs, manifest, and optional baseline pinning inputs for this
	 *   relink, bundled so the signature stops growing.
	 * @return the full relinked resource apk (resources.arsc plus every compiled resource
	 *   file), not a bare extracted table - a bare table cannot back a file-typed resource.
	 */
	suspend fun relink(inputs: RelinkInputs): DaemonReply<RelinkOutput>

	/**
	 * Liveness probe; false when the daemon is missing or unresponsive.
	 *
	 * @return true only on an answered `ping`, which takes the same one-at-a-time request slot as
	 *   a build op and so can queue behind an in-flight compile rather than answering at once.
	 */
	suspend fun ping(): Boolean

	/** Graceful stop; a subsequent exit is deliberate, not a death. */
	suspend fun shutdown()

	/**
	 * Registers a callback for the daemon exiting without a shutdown request. The session
	 * manager routes it into [org.appdevforall.cotg.quickbuild.domain.session.SessionEvent.DaemonDied].
	 *
	 * @param listener receives the process exit code on the implementation's own thread, never
	 *   for an exit [shutdown] asked for; null clears the single listener held.
	 */
	fun setDeathListener(listener: ((exitCode: Int) -> Unit)?)
}

/**
 * A successful `compile` op's output.
 *
 * @property classesDir directory containing the compiled classes.
 * @property changedClassFiles the .class files this run emitted or rewrote, '/'-separated
 *   relative to [classesDir] - the deploy policy's recompiled-set signal, null when the daemon
 *   did not report it, which makes the policy decide conservatively (restart over stale).
 * @property kotlinMillis wall time of the daemon's Kotlin pass; null when unreported, as for
 *   every step-timing field below.
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
 * @property dexFile the single `classes.dex` this op produced, ready to stage into a payload.
 * @property stripMillis wall time of the daemon's class-stripping pass; null when unreported.
 * @property d8Millis wall time of the d8 invocation itself; null when unreported.
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
 *   ([QuickBuildProjectLayout.stableIdsFile]), pinning ids so relinking the project's own res/ -
 *   a strict subset of what the real build merged - cannot shift an id out from under the
 *   already-compiled manifest; null relinks unpinned.
 * @property libraryResources pre-compiled `.flat` resource units from the proxy app build
 *   ([QuickBuildProjectLayout.libraryResourceFlats]), letting a relink resolve resources the
 *   project's own res/ never declares (Material3's `Theme.Material3.DayNight.NoActionBar` and
 *   kin); empty relinks against the project's own res/ alone.
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
 *
 * @property resourceApk the relinked apk - resources.arsc plus every compiled resource file,
 *   not a bare table, since a bare table cannot back a file-typed resource.
 * @property aapt2CompileMillis wall time of the aapt2 compile pass; null when unreported.
 * @property aapt2LinkMillis wall time of the aapt2 link pass; null when unreported.
 */
data class RelinkOutput(
	val resourceApk: File,
	val aapt2CompileMillis: Long? = null,
	val aapt2LinkMillis: Long? = null,
)

/**
 * Everything the daemon needs to know once per session (`configure` op).
 *
 * @property projectRoot the user project's root directory, which anchors the daemon's own
 *   relative bookkeeping.
 * @property classpath compile classpath: the variant's library jars/AARs plus the proxy app
 *   build's generated jars (R.jar and kin), which hot compiles reference.
 * @property outDir directory the daemon writes classes, dex, and relinked resources under; it
 *   is also the base for the conventional output paths a reply may omit.
 * @property aapt2 on-device aapt2 binary used for resource compile and link.
 * @property d8Jar d8/r8 jar the daemon dexes with, in-process.
 * @property androidJar `android.jar` of the bundled compile SDK, the bootclasspath for compiles.
 * @property compilerPlugins session-fixed Kotlin compiler plugin jars (-Xplugin), such as Compose.
 * @property minApi API level the daemon dexes at, taken from the proxy app build's setup.json so
 *   increments are desugared exactly like the baseline they patch. Defaults to the protocol floor,
 *   which is what an older setup.json (carrying no such field) means.
 */
data class DaemonConfig(
	val projectRoot: File,
	val classpath: List<File>,
	val outDir: File,
	val aapt2: File,
	val d8Jar: File,
	val androidJar: File,
	val compilerPlugins: List<File> = emptyList(),
	val minApi: Int = ConfigureRequest.DEFAULT_MIN_API,
)

/**
 * Result of one daemon op. [BuildFailed] is the user's code failing to build (maps to
 * [org.appdevforall.cotg.quickbuild.domain.reload.BuildOutcome.CompileError]); [Failed] is
 * the pipeline itself breaking (daemon dead, protocol I/O error) and maps to
 * [org.appdevforall.cotg.quickbuild.domain.reload.BuildOutcome.InfrastructureFailure].
 */
sealed interface DaemonReply<out T> {
	/**
	 * The op succeeded.
	 *
	 * @property value the op's output; [Unit] for ops that only report success.
	 */
	data class Ok<T>(
		val value: T,
	) : DaemonReply<T>

	/**
	 * The user's code failed to build - the pipeline itself is healthy and the daemon stays up.
	 *
	 * @property diagnostics compiler errors and warnings to show the user, in the order the
	 *   daemon reported them; empty when it failed without saying why.
	 * @property stats the failing compile's counts, or null when the op was not a compile or the
	 *   daemon answered without them. A failing build is the one whose counts matter most:
	 *   `kotlinToCompile` says whether the edit reached the dirty set the engine was handed.
	 */
	data class BuildFailed(
		val diagnostics: List<BuildDiagnostic>,
		val stats: CompileStats? = null,
	) : DaemonReply<Nothing>

	/**
	 * The pipeline itself broke; nothing can be said about the user's code.
	 *
	 * @property message operator-facing reason, safe to log but not written for end users.
	 * @property daemonDied true when the child process is gone or presumed gone, which is the
	 *   session manager's signal to respawn rather than retry.
	 */
	data class Failed(
		val message: String,
		val daemonDied: Boolean = false,
	) : DaemonReply<Nothing>
}
