package org.appdevforall.cotg.quickbuild.daemon

import org.appdevforall.cotg.quickbuild.daemon.compile.IncrementalCompiler
import org.appdevforall.cotg.quickbuild.daemon.dex.DexTool
import org.appdevforall.cotg.quickbuild.daemon.protocol.CompileRequest
import org.appdevforall.cotg.quickbuild.daemon.protocol.ConfigureRequest
import org.appdevforall.cotg.quickbuild.daemon.protocol.DaemonHandlers
import org.appdevforall.cotg.quickbuild.daemon.protocol.DaemonResponse
import org.appdevforall.cotg.quickbuild.daemon.protocol.DexRequest
import org.appdevforall.cotg.quickbuild.daemon.protocol.Diagnostic
import org.appdevforall.cotg.quickbuild.daemon.protocol.RelinkRequest
import org.appdevforall.cotg.quickbuild.daemon.res.Aapt2Link
import java.io.File
import java.nio.file.Files

/**
 * The stateful op implementations behind the protocol: `configure` builds the session
 * (classpath snapshots, tool wrappers), then `compile`/`dex`/`relink` reuse it - the
 * warm state is the whole point of a daemon (plan 2.3). All failures become ok:false
 * responses; the process-level backstop lives in [RequestRouter].
 */
class DaemonService(
	private val log: (String) -> Unit = { System.err.println(it) },
	private val toolchainDiscovery: ToolchainDiscovery = ToolchainDiscovery(),
) : DaemonHandlers {
	private class Session(
		val compiler: IncrementalCompiler,
		val dexTool: DexTool,
		val aapt2Link: Aapt2Link,
		val outDir: File,
	)

	private var session: Session? = null

	override fun configure(request: ConfigureRequest): DaemonResponse {
		val aapt2 = request.aapt2?.let { ToolchainDiscovery.Resolution.Found(it) } ?: toolchainDiscovery.resolveAapt2()
		val d8Jar = request.d8Jar?.let { ToolchainDiscovery.Resolution.Found(it) } ?: toolchainDiscovery.resolveD8Jar()
		val androidJar = request.androidJar?.let { ToolchainDiscovery.Resolution.Found(it) } ?: toolchainDiscovery.resolveAndroidJar()
		val unresolved = listOf(aapt2, d8Jar, androidJar).filterIsInstance<ToolchainDiscovery.Resolution.Missing>()
		if (unresolved.isNotEmpty()) {
			return DaemonResponse.failure(
				request.id,
				unresolved.map { Diagnostic(Diagnostic.Severity.ERROR, it.message) },
			)
		}
		val aapt2Path = (aapt2 as ToolchainDiscovery.Resolution.Found).path
		val d8JarPath = (d8Jar as ToolchainDiscovery.Resolution.Found).path
		val androidJarPath = (androidJar as ToolchainDiscovery.Resolution.Found).path

		val missing =
			(request.classpath + request.compilerPlugins + aapt2Path + d8JarPath + androidJarPath)
				.filter { !File(it).exists() }
		if (missing.isNotEmpty()) {
			return DaemonResponse.failure(request.id, "configure: missing files: ${missing.joinToString()}")
		}
		val outDir = File(request.outDir)
		Files.createDirectories(outDir.toPath())

		// Re-configure replaces the session (e.g. classpath changed -> new snapshots).
		session?.dexTool?.close()
		val startedAt = System.currentTimeMillis()
		session =
			Session(
				// androidJar rides on the compile classpath too: the variant compile
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
		log("configured: project=${request.projectRoot} classpath=${request.classpath.size} entries, snapshots in ${durationMillis}ms")
		return DaemonResponse.ok(
			request.id,
			mapOf("durationMillis" to durationMillis, "protocolVersion" to DaemonResponse.PROTOCOL_VERSION),
		)
	}

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
						"in ${durationMillis}ms (kotlin=${result.kotlinMillis}ms java=${result.javaMillis}ms)",
				)
				DaemonResponse(
					id = request.id,
					ok = true,
					values =
						mapOf(
							"classesDir" to result.classesDir.absolutePath,
							"durationMillis" to durationMillis,
							"kotlinMillis" to result.kotlinMillis,
							"javaMillis" to result.javaMillis,
							// Relative .class paths this run emitted; the CoGo-side
							// deploy policy intersects them with the component closure.
							"classesChanged" to result.changedClassFiles,
						),
					diagnostics = result.warnings,
				)
			}

			is IncrementalCompiler.Result.Failed -> {
				log("compile failed: ${result.diagnostics.size} diagnostics in ${durationMillis}ms")
				DaemonResponse.failure(request.id, result.diagnostics)
			}
		}
	}

	override fun dex(request: DexRequest): DaemonResponse {
		val session = session ?: return notConfigured(request.id)
		val startedAt = System.currentTimeMillis()
		val outDir = File(session.outDir, "dex")
		return when (val result = session.dexTool.dex(request.classesDirs.map(::File), outDir)) {
			is DexTool.Result.Success -> {
				val durationMillis = System.currentTimeMillis() - startedAt
				log("dex ok: ${result.dexFile} in ${durationMillis}ms (strip=${result.stripMillis}ms d8=${result.d8Millis}ms)")
				DaemonResponse.ok(
					request.id,
					mapOf(
						"dexFile" to result.dexFile.absolutePath,
						"durationMillis" to durationMillis,
						"stripMillis" to result.stripMillis,
						"d8Millis" to result.d8Millis,
					),
				)
			}

			is DexTool.Result.Failed -> {
				log("dex failed: ${result.message}")
				DaemonResponse.failure(request.id, result.message)
			}
		}
	}

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
				// Wire field name kept as "resourcesArsc" for protocol stability even
				// though the payload is now the full relinked apk, not a bare table -
				// see Aapt2Link's KDoc (ADFA-4128 Bug 5).
				DaemonResponse.ok(
					request.id,
					mapOf(
						"resourcesArsc" to result.resourceApk.absolutePath,
						"durationMillis" to durationMillis,
						"aapt2CompileMillis" to result.compileMillis,
						"aapt2LinkMillis" to result.linkMillis,
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
