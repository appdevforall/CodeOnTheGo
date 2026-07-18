package org.appdevforall.cotg.quickbuild.daemon

import org.appdevforall.cotg.quickbuild.daemon.compile.IncrementalCompiler
import org.appdevforall.cotg.quickbuild.daemon.dex.DexTool
import org.appdevforall.cotg.quickbuild.daemon.protocol.CompileRequest
import org.appdevforall.cotg.quickbuild.daemon.protocol.ConfigureRequest
import org.appdevforall.cotg.quickbuild.daemon.protocol.DaemonHandlers
import org.appdevforall.cotg.quickbuild.daemon.protocol.DaemonResponse
import org.appdevforall.cotg.quickbuild.daemon.protocol.DexRequest
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
) : DaemonHandlers {
	private class Session(
		val compiler: IncrementalCompiler,
		val dexTool: DexTool,
		val aapt2Link: Aapt2Link,
		val outDir: File,
	)

	private var session: Session? = null

	override fun configure(request: ConfigureRequest): DaemonResponse {
		val missing =
			(request.classpath + request.compilerPlugins + request.aapt2 + request.d8Jar + request.androidJar)
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
						(request.classpath + request.androidJar).map(::File),
						outDir.toPath(),
						compilerPluginJars = request.compilerPlugins.map(::File),
					),
				dexTool = DexTool(File(request.d8Jar), File(request.androidJar), request.minApi),
				aapt2Link = Aapt2Link(File(request.aapt2), File(request.androidJar)),
				outDir = outDir,
			)
		val durationMillis = System.currentTimeMillis() - startedAt
		log("configured: project=${request.projectRoot} classpath=${request.classpath.size} entries, snapshots in ${durationMillis}ms")
		return DaemonResponse.ok(request.id, mapOf("durationMillis" to durationMillis))
	}

	override fun compile(request: CompileRequest): DaemonResponse {
		val session = session ?: return notConfigured(request.id)
		val startedAt = System.currentTimeMillis()
		val result = session.compiler.compile(request.allSources.map(::File), request.changedFiles.map(::File))
		val durationMillis = System.currentTimeMillis() - startedAt
		return when (result) {
			is IncrementalCompiler.Result.Success -> {
				log("compile ok: ${request.changedFiles.size} changed of ${request.allSources.size} in ${durationMillis}ms")
				DaemonResponse(
					id = request.id,
					ok = true,
					values =
						mapOf(
							"classesDir" to result.classesDir.absolutePath,
							"durationMillis" to durationMillis,
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
				log("dex ok: ${result.dexFile} in ${durationMillis}ms")
				DaemonResponse.ok(
					request.id,
					mapOf("dexFile" to result.dexFile.absolutePath, "durationMillis" to durationMillis),
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
		val result = session.aapt2Link.relink(request.resDirs.map(::File), File(request.manifest), workDir)
		val durationMillis = System.currentTimeMillis() - startedAt
		return when (result) {
			is Aapt2Link.Result.Success -> {
				log("relink ok: ${result.resourcesArsc} in ${durationMillis}ms")
				DaemonResponse.ok(
					request.id,
					mapOf("resourcesArsc" to result.resourcesArsc.absolutePath, "durationMillis" to durationMillis),
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
