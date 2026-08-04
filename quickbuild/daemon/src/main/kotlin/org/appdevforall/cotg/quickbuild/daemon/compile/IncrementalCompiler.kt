package org.appdevforall.cotg.quickbuild.daemon.compile

import org.appdevforall.cotg.quickbuild.daemon.protocol.CompileStats
import org.appdevforall.cotg.quickbuild.daemon.protocol.Diagnostic
import org.jetbrains.kotlin.buildtools.api.CompilationResult
import org.jetbrains.kotlin.buildtools.api.CompilationService
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.KotlinLogger
import org.jetbrains.kotlin.buildtools.api.ProjectId
import org.jetbrains.kotlin.buildtools.api.SourcesChanges
import org.jetbrains.kotlin.buildtools.api.jvm.ClassSnapshotGranularity
import org.jetbrains.kotlin.buildtools.api.jvm.ClasspathSnapshotBasedIncrementalCompilationApproachParameters
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Compiles a module's Kotlin and Java sources incrementally, so a one-line edit recompiles
 * about one file instead of the whole app.
 *
 * Constraints the Kotlin Build Tools API imposes, none of them visible from the calls below
 * (more in quickbuild/core/README.md):
 * - Changes must be passed as [SourcesChanges.Known]; `ToBeCalculated` silently degrades to
 *   a full compile.
 * - The shrunk snapshot path is derived from `setRootProjectDir`, so it must be exactly
 *   `<rootProjectDir>/shrunk-classpath-snapshot.bin`; a mismatch still compiles correctly
 *   but silently degrades to non-incremental. rootProjectDir is the daemon work dir, so the
 *   engine's artifacts stay out of the user's project.
 * - The caller must pass ALL sources as changed on the first compile, to seed the IC caches.
 * - `assureNoClasspathSnapshotsChanges(true)` is only safe once the shrunk snapshot exists;
 *   before that the engine needs the full classpath comparison to seed.
 *
 * Java sources take two passes: kotlinc reads them for symbol resolution only, then javac
 * compiles them after Kotlin against the same classpath plus the Kotlin output, into the
 * same output dir. That compiles Kotlin<->Java cycles. javac's pass is not incremental.
 * [JavaSourceAbi] decides when a `.java` edit forces a Kotlin recompile - see
 * [kotlinFilesToCompile].
 *
 * Kotlin 2.3 deprecates this [CompilationService] entry point in favor of `KotlinToolchains`;
 * v1 stays on it, and migrating is contained to this class.
 *
 * @param classpathJars the module's whole compile classpath, boot jar included; snapshotted once
 *   in `init`, so changing it means a new instance, never an in-place edit.
 * @property workDir the daemon-owned scratch root. Also the BTA `rootProjectDir`, which fixes
 *   where the shrunk snapshot lands - it must not be the user's project dir.
 * @param compilerPluginJars kotlinc plugin jars, each passed as one `-Xplugin`; session-fixed
 *   like the classpath.
 */
@OptIn(ExperimentalBuildToolsApi::class)
class IncrementalCompiler(
	classpathJars: List<File>,
	private val workDir: Path,
	compilerPluginJars: List<File> = emptyList(),
) {
	/** Outcome of one compile. */
	sealed interface Result {
		/**
		 * Both passes succeeded, with the outputs they touched and what each phase cost.
		 *
		 * @property classesDir single merged output dir for Kotlin and Java classes.
		 * @property warnings kotlinc's and javac's warnings, already parsed into the protocol
		 *   shape; a successful compile can still carry them.
		 * @property changedClassFiles the .class files this compile emitted or rewrote, as
		 *   '/'-separated paths relative to [classesDir]. The CoGo deploy policy picks
		 *   restart vs recreate from this, so it must never under-report: computed by diffing
		 *   a pre/post snapshot of the output tree on size and nanosecond mtime.
		 * @property kotlinMillis wall time of the Kotlin pass (0 when there are no Kotlin sources).
		 * @property javaMillis wall time of the javac pass (0 when there are no Java sources).
		 * @property stats the phases [kotlinMillis]/[javaMillis] do not cover - the two
		 *   output-tree walks and the Java-ABI re-parse - plus this build's source and output
		 *   counts.
		 */
		data class Success(
			val classesDir: File,
			val warnings: List<Diagnostic>,
			val changedClassFiles: List<String>,
			val kotlinMillis: Long = 0,
			val javaMillis: Long = 0,
			val stats: CompileStats = CompileStats(),
		) : Result

		/**
		 * A pass failed; nothing in the output dir should be deployed.
		 *
		 * @property diagnostics the errors that stopped the compile, plus any warnings collected
		 *   before it. Never empty - an unexplained failure is reported as one synthetic error.
		 */
		data class Failed(
			val diagnostics: List<Diagnostic>,
		) : Result
	}

	private val service = CompilationService.loadImplementation(IncrementalCompiler::class.java.classLoader)
	private val projectId = ProjectId.ProjectUUID(UUID.randomUUID())
	private val icCachesDir = workDir.resolve("ic")
	private val classesDir = workDir.resolve("classes")
	private val shrunkSnapshot = workDir.resolve("shrunk-classpath-snapshot.bin").toFile()
	private val classpathSnapshots: List<File>
	private val classpathString = classpathJars.joinToString(File.pathSeparator) { it.absolutePath }
	private val classpathFiles = classpathJars

	// Compiler plugins are passed as free-form kotlinc args, one -Xplugin per jar, the same
	// way a CLI invocation would. Session-fixed, like the classpath.
	private val pluginArguments = compilerPluginJars.map { "-Xplugin=${it.absolutePath}" }

	/** Raw logger lines from the last compile, level-tagged; test/debug visibility only. */
	var lastCompileLog: List<String> = emptyList()
		private set

	/**
	 * Java type names whose ABI moved in the last compile, forcing a full Kotlin recompile
	 * (see [kotlinFilesToCompile]). Empty when the Java side stayed ABI-stable, which is what
	 * explains an otherwise surprising slow compile.
	 */
	var lastJavaAbiChange: Set<String> = emptySet()
		private set

	// Phase timings/counts measured by compileKotlin and kotlinFilesToCompile on the way past;
	// compile() folds them into the returned CompileStats. Safe as fields because the compiler
	// runs one compile at a time by contract.
	private var javaAbiSnapMillis: Long = 0
	private var kotlinToCompileCount: Int = 0

	/** Compiles served since construction; a `configure` builds a fresh compiler. */
	private var compileCount: Long = 0

	/** Last successful compile's `.java` ABI; null when unknown and Kotlin must be recompiled whole. */
	private var javaAbi: Map<File, JavaSourceAbi.FileAbi>? = null

	/** This compile's `.java` ABI, promoted to [javaAbi] only once the compile succeeds. */
	private var pendingJavaAbi: Map<File, JavaSourceAbi.FileAbi>? = null

	init {
		Files.createDirectories(icCachesDir)
		Files.createDirectories(classesDir)
		val snapshotDir = workDir.resolve("cp-snap")
		Files.createDirectories(snapshotDir)
		// Snapshot the fixed session classpath once; a classpath change is a session
		// invalidation (new configure), never an in-place mutation.
		classpathSnapshots =
			classpathJars.map { jar ->
				val snapshot = snapshotDir.resolve(jar.name + ".snap").toFile()
				service
					.calculateClasspathSnapshot(jar, ClassSnapshotGranularity.CLASS_MEMBER_LEVEL)
					.saveSnapshot(snapshot)
				snapshot
			}
	}

	/**
	 * Runs one compile: the incremental Kotlin pass, then javac over any `.java` sources.
	 *
	 * @param allSources every source in the module, not just the edited ones.
	 * @param changedFiles sources edited since the last compile; pass all of [allSources] on
	 *   the first compile of a session.
	 * @param removedFiles sources deleted since the last compile, no longer in [allSources];
	 *   their stale `.class` outputs are cleaned before anything is compiled.
	 * @return [Result.Failed] on any compile error, and also when a removed source's stale
	 *   `.class` could not be deleted.
	 */
	fun compile(
		allSources: List<File>,
		changedFiles: List<File>,
		removedFiles: List<File> = emptyList(),
	): Result {
		// javac never deletes outputs for sources it is no longer given, so a removed .java's
		// stale .class must go before the pre-snapshot - otherwise it survives into the dex,
		// or is reported as a changed output. Removed .kt outputs are the engine's job, via
		// SourcesChanges.Known below.
		val undeleted = deleteRemovedJavaOutputs(removedFiles)
		if (undeleted.isNotEmpty()) {
			// Proceeding would dex the stale classes of a deleted source, the exact thing the
			// delete exists to prevent.
			return Result.Failed(
				undeleted.map { stale ->
					Diagnostic(
						Diagnostic.Severity.ERROR,
						"failed to delete stale class output of a removed Java source: ${stale.absolutePath}",
					)
				},
			)
		}
		compileCount++
		javaAbiSnapMillis = 0
		kotlinToCompileCount = 0
		val preSnapStartedAt = System.currentTimeMillis()
		val before = snapshotClassOutputs()
		val preSnapMillis = System.currentTimeMillis() - preSnapStartedAt
		val logger = CollectingLogger()
		val kotlinStartedAt = System.currentTimeMillis()
		val kotlinResult = compileKotlin(allSources, changedFiles, removedFiles, logger)
		val kotlinMillis = System.currentTimeMillis() - kotlinStartedAt
		lastCompileLog = logger.lines
		if (kotlinResult != CompilationResult.COMPILATION_SUCCESS) {
			val diagnostics = logger.errors.map { KotlincDiagnosticsParser.parse(it, Diagnostic.Severity.ERROR) }
			return Result.Failed(
				diagnostics.ifEmpty {
					listOf(Diagnostic(Diagnostic.Severity.ERROR, "Kotlin compilation failed: $kotlinResult"))
				},
			)
		}

		val javaSources = allSources.filter { it.extension == "java" }
		val javaStartedAt = System.currentTimeMillis()
		val javaDiagnostics =
			if (javaSources.isEmpty()) {
				JavaCompileStep.Result(success = true, diagnostics = emptyList())
			} else {
				JavaCompileStep.compile(
					javaSources = javaSources,
					classpath = classpathFiles + classesDir.toFile(),
					outputDir = classesDir.toFile(),
				)
			}
		val javaMillis = if (javaSources.isEmpty()) 0 else System.currentTimeMillis() - javaStartedAt
		val warnings = logger.warnings.map { KotlincDiagnosticsParser.parse(it, Diagnostic.Severity.WARNING) }
		if (!javaDiagnostics.success) {
			return Result.Failed(javaDiagnostics.diagnostics + warnings)
		}
		// Only a fully successful compile may become the ABI baseline: a failed compile leaves
		// output the caller never deployed, so the next compile must still see the Java side
		// as changed relative to the last good state. Hence committing here, not where the
		// snapshot is taken.
		javaAbi = pendingJavaAbi
		val postSnapStartedAt = System.currentTimeMillis()
		val changedClassFiles = changedClassOutputs(before)
		val postSnapMillis = System.currentTimeMillis() - postSnapStartedAt
		return Result.Success(
			classesDir = classesDir.toFile(),
			warnings = warnings + javaDiagnostics.diagnostics,
			changedClassFiles = changedClassFiles,
			kotlinMillis = kotlinMillis,
			javaMillis = javaMillis,
			stats =
				CompileStats(
					preSnapMillis = preSnapMillis,
					postSnapMillis = postSnapMillis,
					javaAbiSnapMillis = javaAbiSnapMillis,
					allSources = allSources.size,
					kotlinToCompile = kotlinToCompileCount,
					javaSources = javaSources.size,
					changedClasses = changedClassFiles.size,
					compileOrdinal = compileCount,
				),
		)
	}

	/**
	 * Snapshots every .class under [classesDir] as relative path -> (size, mtime). Nanosecond
	 * [java.nio.file.attribute.FileTime], not millis, so a rewrite inside the same millisecond
	 * still diffs; missing one would let a changed component class skip the restart policy.
	 *
	 * @return '/'-separated relative path -> (size, mtime), empty when the output dir does not
	 *   exist yet.
	 */
	private fun snapshotClassOutputs(): Map<String, Pair<Long, java.nio.file.attribute.FileTime>> {
		val root = classesDir
		if (!Files.isDirectory(root)) return emptyMap()
		val snapshot = HashMap<String, Pair<Long, java.nio.file.attribute.FileTime>>()
		Files.walk(root).use { paths ->
			paths.forEach { path ->
				if (Files.isRegularFile(path) && path.toString().endsWith(".class")) {
					val rel = root.relativize(path).toString().replace(java.io.File.separatorChar, '/')
					snapshot[rel] = Files.size(path) to Files.getLastModifiedTime(path)
				}
			}
		}
		return snapshot
	}

	private fun changedClassOutputs(before: Map<String, Pair<Long, java.nio.file.attribute.FileTime>>): List<String> =
		snapshotClassOutputs()
			.filter { (rel, stamp) -> before[rel] != stamp }
			.keys
			.sorted()

	/**
	 * Deletes the `.class` outputs of removed `.java` sources, which javac never cleans up
	 * itself - without this a deleted class stays in [classesDir] and rides into every later
	 * dex as stale bytecode. The source is gone, so its package comes from the path (see
	 * [javaClassStem]); the primary class and any nested `Outer$Inner.class` beside it go too.
	 *
	 * @param removedFiles this compile's removals; non-`.java` entries are ignored here, since
	 *   the IC engine owns Kotlin output deletion.
	 * @return the `.class` files that could not be deleted; [compile] must fail on a non-empty
	 *   result rather than dex a survivor.
	 */
	private fun deleteRemovedJavaOutputs(removedFiles: List<File>): List<File> {
		val classesRoot = classesDir.toFile()
		if (!classesRoot.isDirectory) return emptyList()
		val undeleted = mutableListOf<File>()
		removedFiles.filter { it.extension == "java" }.forEach { javaFile ->
			val relStem = javaClassStem(javaFile) ?: return@forEach
			val pkgDir = File(classesRoot, relStem).parentFile ?: return@forEach
			val stem = File(relStem).name
			pkgDir.listFiles()?.forEach { candidate ->
				val name = candidate.name
				if (name == "$stem.class" || (name.startsWith("$stem\$") && name.endsWith(".class"))) {
					if (!candidate.delete() && candidate.exists()) {
						undeleted += candidate
					}
				}
			}
		}
		return undeleted
	}

	/**
	 * The output-relative class stem (`com/foo/Bar`) for a `.java` source path, or null when no
	 * source root is found. Path-only, since the file is gone. Prefers a `main/java` or
	 * `main/kotlin` root so a package segment named `java`/`kotlin` deeper in the path isn't
	 * mistaken for the root; otherwise falls back to the last such segment.
	 *
	 * @param javaFile the removed source's path; it need not still exist on disk.
	 * @return the '/'-separated stem without the `.java` suffix, or null when the path has no
	 *   `java`/`kotlin` source root or nothing follows it.
	 */
	private fun javaClassStem(javaFile: File): String? {
		val parts = javaFile.invariantSeparatorsPath.split('/')
		val isMarker = { i: Int -> parts[i] == "java" || parts[i] == "kotlin" }
		val rootIdx =
			parts.indices.lastOrNull { i -> isMarker(i) && i > 0 && parts[i - 1] == "main" }
				?: parts.indices.lastOrNull(isMarker)
				?: return null
		if (rootIdx >= parts.lastIndex) return null
		return parts.subList(rootIdx + 1, parts.size).joinToString("/").removeSuffix(".java")
	}

	/**
	 * Runs the incremental Kotlin pass; a module with no Kotlin sources succeeds immediately.
	 *
	 * @param allSources every module source; the `.java` ones go to kotlinc for resolution only.
	 * @param changedFiles this edit's changes, narrowed by [kotlinFilesToCompile] before the
	 *   engine sees them.
	 * @param removedFiles this edit's removals; only the non-`.java` ones are passed on.
	 * @param logger collects the compiler's messages, which are the only source of diagnostics.
	 * @return the raw BTA result; anything but `COMPILATION_SUCCESS` fails the compile.
	 */
	private fun compileKotlin(
		allSources: List<File>,
		changedFiles: List<File>,
		removedFiles: List<File>,
		logger: CollectingLogger,
	): CompilationResult {
		val kotlinSources = allSources.filter { it.extension != "java" }
		val javaSources = allSources.filter { it.extension == "java" }
		if (kotlinSources.isEmpty()) {
			// Nothing for a Java ABI change to invalidate; keep no baseline for it either.
			pendingJavaAbi = null
			return CompilationResult.COMPILATION_SUCCESS
		}

		// kotlinc needs the .java sources in compileJvm's source list to resolve a Kotlin file
		// that calls a same-module Java class; the `-Xjava-source-roots` flag is silently
		// ignored by this entry point. It emits no bytecode for them (JavaCompileStep does
		// that). The engine tracks no ABI over those sources, so being told a .java file
		// changed tells it nothing - kotlinFilesToCompile has to decide instead, or callers
		// keep .class files compiled against the old Java signature.
		val kotlinChanged = kotlinFilesToCompile(kotlinSources, javaSources, changedFiles)

		val strategy = service.makeCompilerExecutionStrategyConfiguration().useInProcessStrategy()
		val config = service.makeJvmCompilationConfiguration().useLogger(logger)
		val icConfig = config.makeClasspathSnapshotBasedIncrementalCompilationConfiguration()
		icConfig.setRootProjectDir(workDir.toFile())
		icConfig.setBuildDir(classesDir.toFile())
		if (shrunkSnapshot.exists()) {
			icConfig.assureNoClasspathSnapshotsChanges(true)
		}
		val parameters =
			ClasspathSnapshotBasedIncrementalCompilationApproachParameters(classpathSnapshots, shrunkSnapshot)
		// Removed Kotlin sources go in SourcesChanges.Known's removed slot: the engine deletes
		// their outputs and recompiles dependents, so a dangling reference surfaces as an
		// ordinary compile error. The engine tracks only Kotlin outputs, so `.java` removals
		// are handled separately in deleteRemovedJavaOutputs.
		val kotlinRemoved = removedFiles.filter { it.extension != "java" }
		val changes = SourcesChanges.Known(kotlinChanged, kotlinRemoved)
		config.useIncrementalCompilation(icCachesDir.toFile(), changes, parameters, icConfig)

		val arguments =
			listOf(
				"-classpath",
				classpathString,
				"-d",
				classesDir.toString(),
				"-jvm-target",
				JVM_TARGET,
				"-module-name",
				"quickbuild-payload",
				"-no-stdlib",
				"-no-reflect",
				"-nowarn",
			) + pluginArguments
		return service.compileJvm(projectId, strategy, config, kotlinSources + javaSources, arguments)
	}

	/**
	 * Decides which Kotlin sources this compile must treat as changed, given the engine
	 * tracks no dependencies over the `.java` sources it resolves against.
	 *
	 * A stable Java ABI means exactly the caller's Kotlin changes suffice; any ABI move, or an
	 * ABI that is unknown (first compile, no javac, an unparseable source), recompiles every
	 * Kotlin source - bluntly, since BTA cannot be told of a non-classpath ABI change.
	 *
	 * @param kotlinSources every Kotlin source in the module - the fallback answer.
	 * @param javaSources every `.java` source, fingerprinted here and compared against the last
	 *   successful compile's baseline.
	 * @param changedFiles the caller's changes; the `.java` entries are dropped, since the
	 *   fingerprint, not the caller, decides what a Java edit costs.
	 * @return the Kotlin sources to hand the engine as changed; also updates [lastJavaAbiChange]
	 *   and stages the new baseline, which only a successful compile promotes.
	 */
	private fun kotlinFilesToCompile(
		kotlinSources: List<File>,
		javaSources: List<File>,
		changedFiles: List<File>,
	): List<File> {
		lastJavaAbiChange = emptySet()
		val kotlinChanged = changedFiles.filter { it.extension != "java" }
		val previous = javaAbi
		val snapshotStartedAt = System.currentTimeMillis()
		val current = JavaSourceAbi.snapshot(javaSources)
		javaAbiSnapMillis = System.currentTimeMillis() - snapshotStartedAt
		pendingJavaAbi = current
		val toCompile =
			when {
				previous == null || current == null -> {
					kotlinSources
				}

				else -> {
					val changedTypes = JavaSourceAbi.changedTypeNames(previous, current)
					lastJavaAbiChange = changedTypes
					if (changedTypes.isEmpty()) kotlinChanged else kotlinSources
				}
			}
		kotlinToCompileCount = toCompile.size
		return toCompile
	}

	/**
	 * Collects compiler output per channel; the error channel feeds structured diagnostics.
	 * `internal` rather than private so severity routing is unit-testable - the daemon passes
	 * `-nowarn`, so no real compile can drive the warn channel from a test.
	 */
	internal class CollectingLogger : KotlinLogger {
		val errors = mutableListOf<String>()
		val warnings = mutableListOf<String>()
		val lines = mutableListOf<String>()

		override val isDebugEnabled: Boolean = true

		override fun error(
			msg: String,
			throwable: Throwable?,
		) {
			errors += msg
			lines += "e: $msg"
		}

		override fun warn(
			msg: String,
			throwable: Throwable?,
		) {
			warnings += msg
			lines += "w: $msg"
		}

		override fun info(msg: String) {
			lines += "i: $msg"
		}

		override fun debug(msg: String) {
			lines += "d: $msg"
		}

		override fun lifecycle(msg: String) {
			lines += "l: $msg"
		}
	}

	companion object {
		// ART (via d8 desugaring) handles Java-17 bytecode; matches the bundled JDK.
		private const val JVM_TARGET = "17"
	}
}
