package org.appdevforall.cotg.quickbuild.daemon

import org.appdevforall.cotg.quickbuild.daemon.compile.IncrementalCompiler
import org.appdevforall.cotg.quickbuild.daemon.dex.DexTool
import org.appdevforall.cotg.quickbuild.daemon.protocol.DaemonHandlers
import org.appdevforall.cotg.quickbuild.daemon.res.Aapt2Link
import org.appdevforall.cotg.quickbuild.protocol.CompileRequest
import org.appdevforall.cotg.quickbuild.protocol.ConfigureRequest
import org.appdevforall.cotg.quickbuild.protocol.DaemonResponse
import org.appdevforall.cotg.quickbuild.protocol.DexRequest
import org.appdevforall.cotg.quickbuild.protocol.Diagnostic
import org.appdevforall.cotg.quickbuild.protocol.RelinkRequest
import org.appdevforall.cotg.quickbuild.protocol.RequestKeys
import org.appdevforall.cotg.quickbuild.protocol.ResponseKeys
import java.io.File
import java.nio.file.Files

/**
 * Implements the build ops, holding the warm state between them: `configure` builds the
 * session (classpath snapshots, tool wrappers) and `compile`/`dex`/`relink` reuse it.
 * Failures become ok:false responses; the backstop for anything that still throws is
 * [RequestRouter].
 *
 * @property log takes one already-formatted line of human-readable progress; defaults to stderr,
 *   never stdout, which is protocol-only.
 */
class DaemonService(
	private val log: (String) -> Unit = { System.err.println(it) },
) : DaemonHandlers {
	/**
	 * The warm state one `configure` builds and every later op reuses.
	 *
	 * @property compiler holds the IC caches and classpath snapshots, so it must outlive a
	 *   single compile.
	 * @property dexTool owns the r8 [java.net.URLClassLoader]; closed alongside the
	 *   compiler when the session is replaced or shut down, see [release].
	 * @property aapt2Link wraps the resolved aapt2 binary and android.jar.
	 * @property outDir the daemon's scratch root; the `dex` and `res` work dirs hang off it.
	 */
	private class Session(
		val compiler: IncrementalCompiler,
		val dexTool: DexTool,
		val aapt2Link: Aapt2Link,
		val outDir: File,
	)

	private var session: Session? = null

	/**
	 * Checks the toolchain, then builds the session that the later ops reuse. Any unsupplied
	 * tool or missing input file fails here rather than mid-build.
	 *
	 * @param request the session inputs; aapt2/d8Jar/androidJar are all required - the daemon
	 *   never guesses a tool path - and `outDir` is created if absent.
	 * @return ok with `durationMillis`, the protocol version and the scratch filesystem type;
	 *   ok:false with one diagnostic per unsupplied tool, or naming every input file missing
	 *   from disk.
	 */
	override fun configure(request: ConfigureRequest): DaemonResponse {
		// A guessed toolchain is worse than none: it would silently compile against some other
		// SDK's android.jar and only surface on device. Every path is the caller's to supply.
		val unsupplied =
			listOf(
				RequestKeys.AAPT2 to request.aapt2,
				RequestKeys.D8_JAR to request.d8Jar,
				RequestKeys.ANDROID_JAR to request.androidJar,
			).filter { (_, path) -> path.isNullOrBlank() }
				.map { (field, _) -> field }
		if (unsupplied.isNotEmpty()) {
			return DaemonResponse.failure(
				request.id,
				unsupplied.map {
					Diagnostic(
						Diagnostic.Severity.ERROR,
						"configure: $it path not supplied - the daemon does not discover tool paths",
					)
				},
			)
		}
		val aapt2Path = requireNotNull(request.aapt2)
		val d8JarPath = requireNotNull(request.d8Jar)
		val androidJarPath = requireNotNull(request.androidJar)

		val missing =
			(request.classpath + request.compilerPlugins + aapt2Path + d8JarPath + androidJarPath)
				.filter { !File(it).exists() }
		if (missing.isNotEmpty()) {
			return DaemonResponse.failure(request.id, "configure: missing files: ${missing.joinToString()}")
		}
		val outDir = File(request.outDir)
		Files.createDirectories(outDir.toPath())

		// Re-configure replaces the session (e.g. classpath changed -> new snapshots). Build the
		// replacement BEFORE releasing the old one's tools: this can throw, and closing first
		// would leave the still-installed old session holding a closed r8 class loader. That
		// damage is LATENT - a closed URLClassLoader still serves classes it already loaded - so
		// it surfaces later as a NoClassDefFoundError from inside d8.
		val startedAt = System.currentTimeMillis()
		val replacement =
			Session(
				// androidJar goes on the compile classpath too: the variant compile
				// classpath from setup.json carries libraries but not the boot jar.
				compiler =
					IncrementalCompiler(
						(request.classpath + androidJarPath).map(::File),
						outDir.toPath(),
						compilerPluginJars = request.compilerPlugins.map(::File),
					),
				dexTool = DexTool(File(d8JarPath), File(androidJarPath), request.minApi),
				aapt2Link = Aapt2Link(File(aapt2Path), File(androidJarPath)),
				outDir = outDir,
			)
		val durationMillis = System.currentTimeMillis() - startedAt
		session?.let(::release)
		session = replacement
		val fsType = scratchFilesystemType(outDir)
		log(
			"configured: project=${request.projectRoot} classpath=${request.classpath.size} entries, " +
				"snapshots in ${durationMillis}ms, scratch fs=$fsType",
		)
		return DaemonResponse.ok(
			request.id,
			mapOf(
				ResponseKeys.DURATION_MILLIS to durationMillis,
				ResponseKeys.PROTOCOL_VERSION to DaemonResponse.PROTOCOL_VERSION,
				ResponseKeys.SCRATCH_FS_TYPE to fsType,
			),
		)
	}

	/**
	 * Releases a superseded session's tools. Both closes run even if the first throws: each
	 * owns state that otherwise lives for the JVM's lifetime - r8's [java.net.URLClassLoader]
	 * and the Build Tools API engine's per-project caches - on a 2-4 GB phone.
	 *
	 * Per SESSION only. Closing the compiler per compile would discard the warm incremental
	 * state the whole feature rests on.
	 *
	 * @param previous the session being replaced or shut down; unusable afterwards, so it must
	 *   already have been detached from [session] or be on its way out.
	 */
	private fun release(previous: Session) {
		runCatching { previous.compiler.close() }
			.onFailure { log("failed to release the previous session's compiler: $it") }
		runCatching { previous.dexTool.close() }
			.onFailure { log("failed to release the previous session's dex tool: $it") }
		// Logged because WHEN a release happens is the whole correctness question here: a
		// release before its replacement exists strands the live session with closed tools.
		log("released the previous session's tools")
	}

	/**
	 * Releases the live session's tools on the way out of the process, after the request loop
	 * has stopped serving (`shutdown` op or stdin EOF). Idempotent, and a no-op when no
	 * `configure` ever ran.
	 */
	fun shutdown() {
		session?.let(::release)
		session = null
	}

	/**
	 * The work directory's filesystem type (`ext4`, `f2fs`, `fuse`, ...), reported once per
	 * session because it dominates every per-file step: rewriting the same class tree costs
	 * 52x more on Android's FUSE-backed emulated storage than on the app's own filesystem
	 * [measured on a56, ADFA-4128], so a timing row without it is hard to read. Any failure
	 * reports `unknown` rather than failing a configure over telemetry.
	 *
	 * @param outDir the scratch root, which must already exist for the file store to resolve.
	 * @return the filesystem type name, or `unknown` if it could not be read.
	 */
	private fun scratchFilesystemType(outDir: File): String =
		runCatching { Files.getFileStore(outDir.toPath()).type() }
			.getOrNull()
			?.takeIf { it.isNotBlank() }
			?: "unknown"

	/**
	 * Compiles the requested sources and reports the changed class outputs plus phase timings.
	 *
	 * @param request must list every module source in `allSources`, not only the edited ones,
	 *   and repeat them all in `changedFiles` on a session's first compile.
	 * @return ok with `classesDir`, the phase timings and the `classesChanged` path list, or
	 *   ok:false carrying the compiler diagnostics; ok:false if no `configure` ran first.
	 */
	override fun compile(request: CompileRequest): DaemonResponse {
		val session = session ?: return notConfigured(request.id)
		val startedAt = System.currentTimeMillis()
		val result =
			session.compiler.compile(
				request.allSources.map(::File),
				request.changedFiles.map(::File),
				request.removedFiles.map(::File),
			)
		val durationMillis = System.currentTimeMillis() - startedAt
		return when (result) {
			is IncrementalCompiler.Result.Success -> {
				log(
					"compile ok: ${request.changedFiles.size} changed of ${request.allSources.size} " +
						"in ${durationMillis}ms (kotlin=${result.kotlinMillis}ms java=${result.javaMillis}ms " +
						"preSnap=${result.stats.preSnapMillis}ms postSnap=${result.stats.postSnapMillis}ms " +
						"abiSnap=${result.stats.javaAbiSnapMillis}ms ktToCompile=${result.stats.kotlinToCompile} " +
						"ordinal=${result.stats.compileOrdinal})",
				)
				DaemonResponse(
					id = request.id,
					ok = true,
					values =
						mapOf(
							ResponseKeys.CLASSES_DIR to result.classesDir.absolutePath,
							ResponseKeys.DURATION_MILLIS to durationMillis,
							ResponseKeys.KOTLIN_MILLIS to result.kotlinMillis,
							ResponseKeys.JAVA_MILLIS to result.javaMillis,
							ResponseKeys.CLASSES_CHANGED to result.changedClassFiles,
						) + result.stats.toValues(),
					diagnostics = result.warnings,
				)
			}

			is IncrementalCompiler.Result.Failed -> {
				log("compile failed: ${result.diagnostics.size} diagnostics in ${durationMillis}ms")
				DaemonResponse.failure(request.id, result.diagnostics)
			}
		}
	}

	/**
	 * Dexes the requested class dirs into the session's `dex` output dir.
	 *
	 * @param request `classesDirs` are roots scanned recursively; later roots win a path
	 *   collision, so the compile output goes first and generated proxies after.
	 * @return ok with `dexFile` and the strip/d8 timings, or ok:false with the d8 failure text;
	 *   ok:false if no `configure` ran first.
	 */
	override fun dex(request: DexRequest): DaemonResponse {
		val session = session ?: return notConfigured(request.id)
		val startedAt = System.currentTimeMillis()
		val outDir = File(session.outDir, "dex")
		return when (val result = session.dexTool.dex(request.classesDirs.map(::File), outDir)) {
			is DexTool.Result.Success -> {
				val durationMillis = System.currentTimeMillis() - startedAt
				log(
					"dex ok: ${result.dexFile} in ${durationMillis}ms (strip=${result.stripMillis}ms " +
						"d8=${result.d8Millis}ms over ${result.stats.classFiles} classes / ${result.stats.classBytes} bytes)",
				)
				DaemonResponse.ok(
					request.id,
					mapOf(
						ResponseKeys.DEX_FILE to result.dexFile.absolutePath,
						ResponseKeys.DURATION_MILLIS to durationMillis,
						ResponseKeys.STRIP_MILLIS to result.stripMillis,
						ResponseKeys.D8_MILLIS to result.d8Millis,
					) + result.stats.toValues(),
				)
			}

			is DexTool.Result.Failed -> {
				log("dex failed: ${result.message}")
				DaemonResponse.failure(request.id, result.message)
			}
		}
	}

	/**
	 * Rebuilds the resource apk from the project's res dirs and the library resources.
	 *
	 * @param request `stableIds` and `libraryResources` are optional on the wire but omitting
	 *   either risks a wrong-id crash or an unresolvable reference - see [Aapt2Link]'s KDoc.
	 * @return ok with `resourcesArsc` (the full relinked apk) and the aapt2 timings, or ok:false
	 *   carrying the aapt2 diagnostics; ok:false if no `configure` ran first.
	 */
	override fun relink(request: RelinkRequest): DaemonResponse {
		val session = session ?: return notConfigured(request.id)
		val startedAt = System.currentTimeMillis()
		val workDir = File(session.outDir, "res")
		Files.createDirectories(workDir.toPath())
		val result =
			session.aapt2Link.relink(
				request.resDirs.map(::File),
				File(request.manifest),
				workDir,
				stableIds = request.stableIds?.let(::File),
				libraryResources = request.libraryResources.map(::File),
			)
		val durationMillis = System.currentTimeMillis() - startedAt
		return when (result) {
			is Aapt2Link.Result.Success -> {
				log(
					"relink ok: ${result.resourceApk} in ${durationMillis}ms " +
						"(aapt2compile=${result.compileMillis}ms link=${result.linkMillis}ms)",
				)
				// The wire field is named "resourcesArsc" for protocol stability, but the payload
				// is the full relinked apk rather than a bare table - see Aapt2Link's KDoc.
				DaemonResponse.ok(
					request.id,
					mapOf(
						ResponseKeys.RESOURCES_ARSC to result.resourceApk.absolutePath,
						ResponseKeys.DURATION_MILLIS to durationMillis,
						ResponseKeys.AAPT2_COMPILE_MILLIS to result.compileMillis,
						ResponseKeys.AAPT2_LINK_MILLIS to result.linkMillis,
					),
				)
			}

			is Aapt2Link.Result.Failed -> {
				log("relink failed: ${result.diagnostics.size} diagnostics")
				DaemonResponse.failure(request.id, result.diagnostics)
			}
		}
	}

	private fun notConfigured(id: Long): DaemonResponse =
		DaemonResponse.failure(id, "daemon is not configured: send a 'configure' request first")
}
