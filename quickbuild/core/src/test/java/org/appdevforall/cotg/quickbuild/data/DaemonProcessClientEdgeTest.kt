package org.appdevforall.cotg.quickbuild.data

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.appdevforall.cotg.quickbuild.domain.reload.BuildDiagnostic
import org.appdevforall.cotg.quickbuild.protocol.ConfigureRequest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Edge and failure paths of [DaemonProcessClient] against scripted fake daemons, in the
 * style of [DaemonProcessClientTest]: a shell script stands in for the java binary and
 * plays back canned protocol lines (optionally capturing what the client wrote, so
 * tests can assert the wire contract).
 */
class DaemonProcessClientEdgeTest {
	@TempDir
	lateinit var tmp: File

	private class ScriptedPaths(
		base: File,
		override val javaBinary: File,
	) : QuickBuildPaths {
		override val daemonJar = File(base, "daemon/quickbuild-daemon.jar")
		override val runtimeAar = File(base, "quickbuild-runtime.aar")
		override val aapt2 = File(base, "aapt2")
		override val d8Jar = File(base, "d8.jar")
		override val composeCompilerPlugin = File(base, "compose-compiler-plugin.jar")
		override val androidJar = File(base, "android.jar")
		override val projectScratchRoot = File(base, "app-private/quickbuild-scratch")

		override fun daemonEnvironment(): Map<String, String> = mapOf("PATH" to "/usr/bin:/bin")
	}

	/** Writes a fake-java script with [body] as its full shell text and returns paths using it. */
	private fun scriptedPaths(body: String): ScriptedPaths {
		val script = File(tmp, "fake-java.sh")
		script.writeText("#!/bin/sh\n$body\n")
		script.setExecutable(true)
		File(tmp, "daemon").mkdirs()
		return ScriptedPaths(tmp, script)
	}

	/**
	 * @param pid a pid the fake daemon wrote for itself.
	 * @return true while that pid is still a live process. Uses the shell's own kill builtin so
	 *   it needs no /bin/kill and no java.lang.ProcessHandle (absent from the Android API).
	 */
	private fun isProcessAlive(pid: String): Boolean = ProcessBuilder("/bin/sh", "-c", "kill -0 $pid 2>/dev/null").start().waitFor() == 0

	/**
	 * Shell prelude defining `reply`, which answers one request line with an ok response
	 * carrying that request's own id - so a script can serve any number of requests without
	 * knowing where the client's id counter has got to.
	 */
	private val replyOk =
		"""
		reply() {
			id=${'$'}(printf '%s' "${'$'}1" | sed 's/.*"id":\([0-9]*\).*/\1/')
			printf '{"id":%s,"ok":true,"protocolVersion":%s}\n' \
				"${'$'}id" '${DaemonProcessClient.EXPECTED_PROTOCOL_VERSION}'
		}
		""".trimIndent()

	/** @return paths to a fake-java script that runs [body] with `reply` already defined. */
	private fun replyingPaths(body: String): ScriptedPaths = scriptedPaths("$replyOk\n$body")

	/** @return the client's per-spawn deliberate-stop marker, which has no public surface. */
	private fun DaemonProcessClient.stopMarker(): AtomicBoolean {
		val field = DaemonProcessClient::class.java.getDeclaredField("deliberateStop")
		field.isAccessible = true
		return field.get(this) as AtomicBoolean
	}

	private fun okConfigure(extra: String = "") =
		"""{"id":1,"ok":true,"protocolVersion":${DaemonProcessClient.EXPECTED_PROTOCOL_VERSION}$extra}"""

	private fun config(
		compilerPlugins: List<File> = emptyList(),
		minApi: Int = ConfigureRequest.DEFAULT_MIN_API,
	): DaemonConfig =
		DaemonConfig(
			projectRoot = tmp,
			classpath = emptyList(),
			outDir = File(tmp, "out"),
			aapt2 = File(tmp, "aapt2"),
			d8Jar = File(tmp, "d8.jar"),
			androidJar = File(tmp, "android.jar"),
			compilerPlugins = compilerPlugins,
			minApi = minApi,
		)

	private fun <T> withClient(
		paths: QuickBuildPaths,
		timeoutMillis: Long = 10_000,
		block: suspend (DaemonProcessClient) -> T,
	): T {
		val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
		val client = DaemonProcessClient(paths, scope, requestTimeoutMillis = timeoutMillis)
		return try {
			runBlocking { block(client) }
		} finally {
			runBlocking { client.shutdown() }
			scope.cancel()
		}
	}

	@Test
	fun `a java binary that cannot spawn fails with daemonDied`() {
		val paths = ScriptedPaths(tmp, File(tmp, "no-such-java"))
		File(tmp, "daemon").mkdirs()

		val reply = withClient(paths) { it.start(config()) }

		assertThat(reply).isInstanceOf(DaemonReply.Failed::class.java)
		val failed = reply as DaemonReply.Failed
		assertThat(failed.message).contains("Failed to spawn daemon")
		assertThat(failed.daemonDied).isTrue()
	}

	@Test
	fun `a daemon that rejects configure fails without claiming death`() {
		val paths =
			scriptedPaths(
				"""
				read line
				printf '%s\n' '{"id":1,"ok":false,"diagnostics":[]}'
				read line
				printf '%s\n' '{"id":2,"ok":true}'
				""".trimIndent(),
			)

		val reply = withClient(paths) { it.start(config()) }

		assertThat(reply).isInstanceOf(DaemonReply.Failed::class.java)
		val failed = reply as DaemonReply.Failed
		assertThat(failed.message).contains("Daemon rejected configuration")
		assertThat(failed.daemonDied).isFalse()
	}

	@Test
	fun `a non-integer protocol version reads as no protocolVersion`() {
		val paths =
			scriptedPaths(
				"""
				read line
				printf '%s\n' '{"id":1,"ok":true,"protocolVersion":"vintage"}'
				read line
				printf '%s\n' '{"id":2,"ok":true}'
				""".trimIndent(),
			)

		val reply = withClient(paths) { it.start(config()) }

		assertThat(reply).isInstanceOf(DaemonReply.Failed::class.java)
		assertThat((reply as DaemonReply.Failed).message).contains("no protocolVersion")
	}

	@Test
	fun `a non-primitive protocol version reads as no protocolVersion`() {
		val paths =
			scriptedPaths(
				"""
				read line
				printf '%s\n' '{"id":1,"ok":true,"protocolVersion":{"v":3}}'
				read line
				printf '%s\n' '{"id":2,"ok":true}'
				""".trimIndent(),
			)

		val reply = withClient(paths) { it.start(config()) }

		assertThat(reply).isInstanceOf(DaemonReply.Failed::class.java)
		assertThat((reply as DaemonReply.Failed).message).contains("no protocolVersion")
	}

	@Test
	fun `a non-primitive scratchFsType stays null instead of crashing configure`() {
		val paths =
			scriptedPaths(
				"""
				read line
				printf '%s\n' '${okConfigure(""","scratchFsType":["fuse"]""")}'
				read line
				printf '%s\n' '{"id":2,"ok":true}'
				""".trimIndent(),
			)

		withClient(paths) { client ->
			assertThat(client.start(config())).isEqualTo(DaemonReply.Ok(Unit))
			assertThat(client.scratchFsType).isNull()
		}
	}

	@Test
	fun `isRunning tracks configure and shutdown`() {
		val paths =
			scriptedPaths(
				"""
				read line
				printf '%s\n' '${okConfigure()}'
				read line
				printf '%s\n' '{"id":2,"ok":true}'
				""".trimIndent(),
			)
		val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
		val client = DaemonProcessClient(paths, scope)
		try {
			assertThat(client.isRunning).isFalse()
			runBlocking { client.start(config()) }
			assertThat(client.isRunning).isTrue()
			runBlocking { client.shutdown() }
			assertThat(client.isRunning).isFalse()
		} finally {
			runBlocking { client.shutdown() }
			scope.cancel()
		}
	}

	@Test
	fun `a request before start fails as not running`() {
		val paths = scriptedPaths("read line")

		val reply = withClient(paths) { it.ping() }

		// ping maps the Failed reply to false - and the client must not have spawned.
		assertThat(reply).isFalse()
	}

	@Test
	fun `a request after shutdown fails as not running with daemonDied`() {
		val paths =
			scriptedPaths(
				"""
				read line
				printf '%s\n' '${okConfigure()}'
				read line
				printf '%s\n' '{"id":2,"ok":true}'
				""".trimIndent(),
			)

		val reply =
			withClient(paths) { client ->
				check(client.start(config()) is DaemonReply.Ok)
				client.shutdown()
				client.compile(emptyList(), emptyList())
			}

		assertThat(reply).isInstanceOf(DaemonReply.Failed::class.java)
		val failed = reply as DaemonReply.Failed
		assertThat(failed.message).contains("Daemon is not running")
		assertThat(failed.daemonDied).isTrue()
	}

	@Test
	fun `an unanswered request times out naming the op with the daemon still alive`() {
		// Configure is answered; the compile request is swallowed while the script sleeps.
		val paths =
			scriptedPaths(
				"""
				read line
				printf '%s\n' '${okConfigure()}'
				read line
				sleep 30
				""".trimIndent(),
			)

		val reply =
			withClient(paths, timeoutMillis = 300) { client ->
				check(client.start(config()) is DaemonReply.Ok)
				client.compile(emptyList(), emptyList())
			}

		assertThat(reply).isInstanceOf(DaemonReply.Failed::class.java)
		val failed = reply as DaemonReply.Failed
		assertThat(failed.message).contains("did not answer 'compile'")
		assertThat(failed.daemonDied).isFalse()
	}

	@Test
	fun `a daemon that dies mid-request fails the pending request as dead`() {
		val paths =
			scriptedPaths(
				"""
				read line
				printf '%s\n' '${okConfigure()}'
				read line
				exit 3
				""".trimIndent(),
			)

		val reply =
			withClient(paths) { client ->
				check(client.start(config()) is DaemonReply.Ok)
				client.compile(emptyList(), emptyList())
			}

		assertThat(reply).isInstanceOf(DaemonReply.Failed::class.java)
		val failed = reply as DaemonReply.Failed
		assertThat(failed.message).contains("did not answer 'compile'")
		assertThat(failed.daemonDied).isTrue()
	}

	@Test
	fun `an unexpected daemon exit fires the death listener with the exit code`() {
		val paths =
			scriptedPaths(
				"""
				read line
				printf '%s\n' '${okConfigure()}'
				exit 7
				""".trimIndent(),
			)
		val latch = CountDownLatch(1)
		var reportedCode = Int.MIN_VALUE

		withClient(paths) { client ->
			client.setDeathListener { code ->
				reportedCode = code
				latch.countDown()
			}
			check(client.start(config()) is DaemonReply.Ok)
			assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue()
		}

		assertThat(reportedCode).isEqualTo(7)
	}

	@Test
	fun `a requested shutdown does not fire the death listener`() {
		val paths =
			scriptedPaths(
				"""
				read line
				printf '%s\n' '${okConfigure()}'
				read line
				printf '%s\n' '{"id":2,"ok":true}'
				""".trimIndent(),
			)
		var died = false
		// Own scope rather than withClient's, so the test can join the client's coroutines
		// before they are cancelled.
		val supervisor = SupervisorJob()
		val scope = CoroutineScope(supervisor + Dispatchers.IO)
		val client = DaemonProcessClient(paths, scope)

		try {
			runBlocking {
				client.setDeathListener { died = true }
				check(client.start(config()) is DaemonReply.Ok)
				client.shutdown()
				// The death watcher is a child of this scope and ends only after proc.waitFor()
				// returned and it decided whether to fire, so joining it is the real signal a
				// fixed sleep was standing in for: a listener firing late cannot escape the
				// join, because the coroutine that would call it has completed.
				withTimeout(30_000) { supervisor.children.toList().forEach { it.join() } }
			}
			assertThat(died).isFalse()
		} finally {
			runBlocking { client.shutdown() }
			scope.cancel()
		}
	}

	@Test
	fun `noise on stdout and stderr does not derail response matching`() {
		// Garbage line, a JSON line without id, an unknown-id response - then the real reply.
		val paths =
			scriptedPaths(
				"""
				read line
				echo 'not json at all'
				printf '%s\n' '{"progress":"still warming"}'
				printf '%s\n' '{"id":999,"ok":true}'
				echo 'daemon stderr chatter' >&2
				printf '%s\n' '${okConfigure()}'
				read line
				printf '%s\n' '{"id":2,"ok":true}'
				""".trimIndent(),
			)

		val reply = withClient(paths) { it.start(config()) }

		assertThat(reply).isEqualTo(DaemonReply.Ok(Unit))
	}

	@Test
	fun `a response without ok true is a build failure with parsed diagnostics`() {
		val diagnostics =
			"""[
				{"severity":"warning","message":"shadowed","file":"A.kt","line":3,"column":9},
				{"severity":"ERROR","message":"broken"},
				{"message":"defaults to error"},
				{"severity":"ERROR"},
				"not an object",
				{"severity":"ERROR","message":"odd shapes","file":{"x":1},"line":"3","column":[1]}
			]""".replace(Regex("\\s+"), "")
		val paths =
			scriptedPaths(
				"""
				read line
				printf '%s\n' '${okConfigure()}'
				read line
				printf '%s\n' '{"id":2,"ok":false,"diagnostics":$diagnostics}'
				read line
				printf '%s\n' '{"id":3,"ok":true}'
				""".trimIndent(),
			)

		val reply =
			withClient(paths) { client ->
				check(client.start(config()) is DaemonReply.Ok)
				client.compile(emptyList(), emptyList())
			}

		val failure = reply as DaemonReply.BuildFailed
		assertThat(failure.diagnostics).hasSize(5)
		val (warning, error, defaulted, noMessage, oddShapes) = failure.diagnostics
		assertThat(warning.severity).isEqualTo(BuildDiagnostic.Severity.WARNING)
		assertThat(warning.file).isEqualTo("A.kt")
		assertThat(warning.line).isEqualTo(3)
		assertThat(warning.column).isEqualTo(9)
		assertThat(error.severity).isEqualTo(BuildDiagnostic.Severity.ERROR)
		assertThat(defaulted.severity).isEqualTo(BuildDiagnostic.Severity.ERROR)
		assertThat(noMessage.message).isEqualTo("unknown error")
		assertThat(oddShapes.file).isNull()
		// "3" is a JSON primitive; gson coerces it - the guard is about non-primitives.
		assertThat(oddShapes.line).isEqualTo(3)
		assertThat(oddShapes.column).isNull()
	}

	@Test
	fun `a build failure without a diagnostics array reports none`() {
		val paths =
			scriptedPaths(
				"""
				read line
				printf '%s\n' '${okConfigure()}'
				read line
				printf '%s\n' '{"id":2,"ok":false}'
				read line
				printf '%s\n' '{"id":3,"ok":true}'
				""".trimIndent(),
			)

		val reply =
			withClient(paths) { client ->
				check(client.start(config()) is DaemonReply.Ok)
				client.dex(emptyList())
			}

		assertThat(reply).isInstanceOf(DaemonReply.BuildFailed::class.java)
		assertThat((reply as DaemonReply.BuildFailed).diagnostics).isEmpty()
	}

	@Test
	fun `ping is false when the daemon answers not-ok`() {
		val paths =
			scriptedPaths(
				"""
				read line
				printf '%s\n' '${okConfigure()}'
				read line
				printf '%s\n' '{"id":2,"ok":false}'
				read line
				printf '%s\n' '{"id":3,"ok":true}'
				""".trimIndent(),
			)

		val alive =
			withClient(paths) { client ->
				check(client.start(config()) is DaemonReply.Ok)
				client.ping()
			}

		assertThat(alive).isFalse()
	}

	@Test
	fun `compile reply without classesDir fails naming the key instead of guessing a path`() {
		// The conventional guess would have been <outDir>/classes - the daemon's real classes
		// tree, still holding the PREVIOUS build's output. Deploying that reports success with
		// the user's edit missing, so an absent key has to fail.
		val paths =
			scriptedPaths(
				"""
				read line
				printf '%s\n' '${okConfigure()}'
				read line
				printf '%s\n' '{"id":2,"ok":true}'
				read line
				printf '%s\n' '{"id":3,"ok":true}'
				""".trimIndent(),
			)

		val reply =
			withClient(paths) { client ->
				check(client.start(config()) is DaemonReply.Ok)
				client.compile(emptyList(), emptyList())
			}

		assertThat(reply).isInstanceOf(DaemonReply.Failed::class.java)
		assertThat((reply as DaemonReply.Failed).message).contains("missing 'classesDir'")
	}

	@Test
	fun `a non-primitive classesDir fails naming the key`() {
		val paths =
			scriptedPaths(
				"""
				read line
				printf '%s\n' '${okConfigure()}'
				read line
				printf '%s\n' '{"id":2,"ok":true,"classesDir":["/out/classes"]}'
				read line
				printf '%s\n' '{"id":3,"ok":true}'
				""".trimIndent(),
			)

		val reply =
			withClient(paths) { client ->
				check(client.start(config()) is DaemonReply.Ok)
				client.compile(emptyList(), emptyList())
			}

		assertThat(reply).isInstanceOf(DaemonReply.Failed::class.java)
		assertThat((reply as DaemonReply.Failed).message).contains("missing 'classesDir'")
	}

	@Test
	fun `compile reply keeps only primitive classesChanged entries`() {
		val paths =
			scriptedPaths(
				"""
				read line
				printf '%s\n' '${okConfigure()}'
				read line
				printf '%s\n' '{"id":2,"ok":true,"classesDir":"/out/classes","classesChanged":["com/a/A",{"weird":1},"com/a/B"]}'
				read line
				printf '%s\n' '{"id":3,"ok":true}'
				""".trimIndent(),
			)

		val reply =
			withClient(paths) { client ->
				check(client.start(config()) is DaemonReply.Ok)
				client.compile(emptyList(), emptyList())
			}

		val output = (reply as DaemonReply.Ok).value
		assertThat(output.classesDir).isEqualTo(File("/out/classes"))
		assertThat(output.changedClassFiles).containsExactly("com/a/A", "com/a/B").inOrder()
	}

	@Test
	fun `a non-numeric timing field reads as not measured`() {
		val paths =
			scriptedPaths(
				"""
				read line
				printf '%s\n' '${okConfigure()}'
				read line
				printf '%s\n' '{"id":2,"ok":true,"classesDir":"/out/classes","kotlinMillis":"fast","javaMillis":[1]}'
				read line
				printf '%s\n' '{"id":3,"ok":true}'
				""".trimIndent(),
			)

		val reply =
			withClient(paths) { client ->
				check(client.start(config()) is DaemonReply.Ok)
				client.compile(emptyList(), emptyList())
			}

		val output = (reply as DaemonReply.Ok).value
		assertThat(output.kotlinMillis).isNull()
		assertThat(output.javaMillis).isNull()
	}

	@Test
	fun `dex reply without dexFile fails naming the key instead of guessing a path`() {
		// Guessing <outDir>/classes.dex is not even where the daemon writes (it writes
		// <outDir>/dex/classes.dex), so it would resolve nothing or an unrelated leftover.
		// Either way the reply has to fail, not guess.
		val paths =
			scriptedPaths(
				"""
				read line
				printf '%s\n' '${okConfigure()}'
				read line
				printf '%s\n' '{"id":2,"ok":true}'
				read line
				printf '%s\n' '{"id":3,"ok":true}'
				""".trimIndent(),
			)

		val reply =
			withClient(paths) { client ->
				check(client.start(config()) is DaemonReply.Ok)
				client.dex(emptyList())
			}

		assertThat(reply).isInstanceOf(DaemonReply.Failed::class.java)
		assertThat((reply as DaemonReply.Failed).message).contains("missing 'dexFile'")
	}

	@Test
	fun `relink reply maps the resources apk and its timings`() {
		val paths =
			scriptedPaths(
				"""
				read line
				printf '%s\n' '${okConfigure()}'
				read line
				printf '%s\n' '{"id":2,"ok":true,"resourcesArsc":"/out/res/linked-res.apk","aapt2CompileMillis":40,"aapt2LinkMillis":140}'
				read line
				printf '%s\n' '{"id":3,"ok":true}'
				""".trimIndent(),
			)

		val reply =
			withClient(paths) { client ->
				check(client.start(config()) is DaemonReply.Ok)
				client.relink(
					RelinkInputs(
						resDirs = listOf(File(tmp, "res")),
						manifest = File(tmp, "AndroidManifest.xml"),
					),
				)
			}

		val output = (reply as DaemonReply.Ok).value
		assertThat(output.resourceApk).isEqualTo(File("/out/res/linked-res.apk"))
		assertThat(output.aapt2CompileMillis).isEqualTo(40)
		assertThat(output.aapt2LinkMillis).isEqualTo(140)
	}

	@Test
	fun `relink reply without a path fails naming the key instead of guessing a path`() {
		val paths =
			scriptedPaths(
				"""
				read line
				printf '%s\n' '${okConfigure()}'
				read line
				printf '%s\n' '{"id":2,"ok":true}'
				read line
				printf '%s\n' '{"id":3,"ok":true}'
				""".trimIndent(),
			)

		val reply =
			withClient(paths) { client ->
				check(client.start(config()) is DaemonReply.Ok)
				client.relink(
					RelinkInputs(
						resDirs = listOf(File(tmp, "res")),
						manifest = File(tmp, "AndroidManifest.xml"),
					),
				)
			}

		assertThat(reply).isInstanceOf(DaemonReply.Failed::class.java)
		assertThat((reply as DaemonReply.Failed).message).contains("missing 'resourcesArsc'")
	}

	@Test
	fun `the wire carries optional fields only when present`() {
		// The script captures every request line so the test can assert the JSON contract:
		// omitted-when-empty fields stay off the wire, present ones make it on.
		val paths =
			scriptedPaths(
				"""
				read line
				printf '%s' "${'$'}line" > '$tmp/configure-request.txt'
				printf '%s\n' '${okConfigure()}'
				read line
				printf '%s' "${'$'}line" > '$tmp/compile-request.txt'
				printf '%s\n' '{"id":2,"ok":true,"classesDir":"/out/classes"}'
				read line
				printf '%s' "${'$'}line" > '$tmp/relink-request.txt'
				printf '%s\n' '{"id":3,"ok":true,"resourcesArsc":"/out/res/linked-res.apk"}'
				read line
				printf '%s\n' '{"id":4,"ok":true}'
				""".trimIndent(),
			)

		withClient(paths) { client ->
			val plugin = File(tmp, "compose-plugin.jar")
			check(client.start(config(compilerPlugins = listOf(plugin))) is DaemonReply.Ok)
			check(
				client.compile(
					allSources = listOf(File(tmp, "A.kt")),
					changedFiles = listOf(File(tmp, "A.kt")),
					removedFiles = listOf(File(tmp, "Gone.kt")),
				) is DaemonReply.Ok,
			)
			check(
				client.relink(
					RelinkInputs(
						resDirs = listOf(File(tmp, "res")),
						manifest = File(tmp, "AndroidManifest.xml"),
						stableIdsFile = File(tmp, "stableIds.txt"),
						libraryResources = listOf(File(tmp, "lib.flat")),
					),
				) is DaemonReply.Ok,
			)
		}

		val configureRequest = File(tmp, "configure-request.txt").readText()
		assertThat(configureRequest).contains("compilerPlugins")
		assertThat(configureRequest).contains("compose-plugin.jar")
		val compileRequest = File(tmp, "compile-request.txt").readText()
		assertThat(compileRequest).contains("removedFiles")
		assertThat(compileRequest).contains("Gone.kt")
		val relinkRequest = File(tmp, "relink-request.txt").readText()
		assertThat(relinkRequest).contains("stableIds")
		assertThat(relinkRequest).contains("libraryResources")
	}

	@Test
	fun `empty optional fields stay off the wire`() {
		val paths =
			scriptedPaths(
				"""
				read line
				printf '%s' "${'$'}line" > '$tmp/configure-request.txt'
				printf '%s\n' '${okConfigure()}'
				read line
				printf '%s' "${'$'}line" > '$tmp/compile-request.txt'
				printf '%s\n' '{"id":2,"ok":true,"classesDir":"/out/classes"}'
				read line
				printf '%s' "${'$'}line" > '$tmp/relink-request.txt'
				printf '%s\n' '{"id":3,"ok":true,"resourcesArsc":"/out/res/linked-res.apk"}'
				read line
				printf '%s\n' '{"id":4,"ok":true}'
				""".trimIndent(),
			)

		withClient(paths) { client ->
			check(client.start(config()) is DaemonReply.Ok)
			check(client.compile(emptyList(), emptyList()) is DaemonReply.Ok)
			check(
				client.relink(
					RelinkInputs(
						resDirs = listOf(File(tmp, "res")),
						manifest = File(tmp, "AndroidManifest.xml"),
					),
				) is DaemonReply.Ok,
			)
		}

		assertThat(File(tmp, "configure-request.txt").readText()).doesNotContain("compilerPlugins")
		assertThat(File(tmp, "compile-request.txt").readText()).doesNotContain("removedFiles")
		val relinkRequest = File(tmp, "relink-request.txt").readText()
		assertThat(relinkRequest).doesNotContain("stableIds")
		assertThat(relinkRequest).doesNotContain("libraryResources")
	}

	@Test
	fun `a protocol-mismatch start shuts the child down instead of orphaning it`() {
		// Nothing downstream cleans up after a failed start - the controller's and the
		// provisioner's failure arms only report - so the child would survive holding its heap
		// and later fire the death listener for a session that never had a daemon.
		val paths =
			scriptedPaths(
				"""
				printf '%s' "${'$'}${'$'}" > '$tmp/daemon.pid'
				read line
				printf '%s\n' '{"id":1,"ok":true,"protocolVersion":99}'
				read line
				printf '%s\n' '{"id":2,"ok":true}'
				""".trimIndent(),
			)
		val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
		val client = DaemonProcessClient(paths, scope, requestTimeoutMillis = 2_000)
		try {
			val reply = runBlocking { client.start(config()) }
			assertThat(reply).isInstanceOf(DaemonReply.Failed::class.java)
			assertThat(client.isRunning).isFalse()
			assertThat(isProcessAlive(File(tmp, "daemon.pid").readText().trim())).isFalse()
		} finally {
			runBlocking { client.shutdown() }
			scope.cancel()
		}
	}

	@Test
	fun `a daemon that rejects configure is shut down too`() {
		val paths =
			scriptedPaths(
				"""
				printf '%s' "${'$'}${'$'}" > '$tmp/daemon.pid'
				read line
				printf '%s\n' '{"id":1,"ok":false,"diagnostics":[]}'
				read line
				printf '%s\n' '{"id":2,"ok":true}'
				""".trimIndent(),
			)
		val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
		val client = DaemonProcessClient(paths, scope, requestTimeoutMillis = 2_000)
		try {
			val reply = runBlocking { client.start(config()) }
			assertThat(reply).isInstanceOf(DaemonReply.Failed::class.java)
			assertThat(isProcessAlive(File(tmp, "daemon.pid").readText().trim())).isFalse()
		} finally {
			runBlocking { client.shutdown() }
			scope.cancel()
		}
	}

	@Test
	fun `a response with an unreadable id does not kill the response pump`() {
		// A non-numeric and a nested id both throw out of the pump's forEachLine, which the
		// surrounding IOException catch does not handle - unguarded, the pump dies and every
		// later request burns its full timeout while still reporting the daemon alive.
		val paths =
			scriptedPaths(
				"""
				read line
				printf '%s\n' '${okConfigure()}'
				read line
				printf '%s\n' '{"id":"two","ok":true}'
				printf '%s\n' '{"id":{"nested":2},"ok":true}'
				printf '%s\n' '{"id":2,"ok":true,"classesDir":"/out/classes"}'
				read line
				printf '%s\n' '{"id":3,"ok":true}'
				""".trimIndent(),
			)

		val reply =
			withClient(paths, timeoutMillis = 3_000) { client ->
				check(client.start(config()) is DaemonReply.Ok)
				client.compile(emptyList(), emptyList())
			}

		assertThat((reply as DaemonReply.Ok).value.classesDir).isEqualTo(File("/out/classes"))
	}

	@Test
	fun `a non-primitive ok is a build failure instead of an exception`() {
		// asBoolean on an object throws, and this facade promises never to throw for a build
		// problem - the exception escaped request() straight out of compile().
		val paths =
			scriptedPaths(
				"""
				read line
				printf '%s\n' '${okConfigure()}'
				read line
				printf '%s\n' '{"id":2,"ok":{"really":true}}'
				read line
				printf '%s\n' '{"id":3,"ok":true}'
				""".trimIndent(),
			)

		val reply =
			withClient(paths) { client ->
				check(client.start(config()) is DaemonReply.Ok)
				client.compile(emptyList(), emptyList())
			}

		assertThat(reply).isInstanceOf(DaemonReply.BuildFailed::class.java)
		assertThat((reply as DaemonReply.BuildFailed).diagnostics).isEmpty()
	}

	@Test
	fun `a respawn does not fire the death listener for the child it replaced`() {
		// Every child answers the polite shutdown and then ignores stdin EOF, so shutdown() has
		// to destroyForcibly it - which returns before the exit. The replaced child's death
		// watcher therefore wakes around the moment start() installs the replacement, straddling
		// the two reads it makes: the identity guard, then the deliberate-stop decision. One
		// shared flag let start() reset it between those reads, so the watcher reported a death
		// for a daemon that was deliberately replaced (and cleared the new session's pending
		// configure with it). A per-spawn marker makes that unreadable rather than unlikely.
		//
		// The cycle repeats because losing that race is a scheduling accident - a single-cycle
		// test can pass by luck and certify a regression as fixed. Each pass is an independent
		// shot at the same interleaving; the client must be green on every one of them however
		// the threads land.
		val respawns = 4
		val paths =
			replyingPaths(
				"""
				read line
				reply "${'$'}line"
				read line
				reply "${'$'}line"
				exec sleep 60
				""".trimIndent(),
			)
		var died = false
		// Own scope so the test can join the client's coroutines - including every replaced
		// child's death watcher - instead of sleeping and hoping.
		val supervisor = SupervisorJob()
		val scope = CoroutineScope(supervisor + Dispatchers.IO)
		val client = DaemonProcessClient(paths, scope, requestTimeoutMillis = 2_000)

		try {
			runBlocking {
				client.setDeathListener { died = true }
				check(client.start(config()) is DaemonReply.Ok)
				repeat(respawns) {
					assertThat(client.start(config())).isEqualTo(DaemonReply.Ok(Unit))
				}
				client.shutdown()
				withTimeout(60_000) { supervisor.children.toList().forEach { it.join() } }
			}
			assertThat(died).isFalse()
		} finally {
			runBlocking { client.shutdown() }
			scope.cancel()
		}
	}

	@Test
	fun `each spawn gets its own deliberate-stop marker`() {
		// The respawn test above can only catch a shared flag when the threads interleave badly;
		// this pins the mechanism that removes the race, and does it on every run. shutdown()
		// must mark the child it is stopping, and start() must install a NEW marker rather than
		// clear that one - the replaced child's watcher goes on reading the old instance.
		val paths =
			replyingPaths(
				"""
				while read line; do
					reply "${'$'}line"
				done
				""".trimIndent(),
			)

		withClient(paths) { client ->
			check(client.start(config()) is DaemonReply.Ok)
			val first = client.stopMarker()
			check(client.start(config()) is DaemonReply.Ok)
			val second = client.stopMarker()

			assertThat(second).isNotSameInstanceAs(first)
			assertThat(first.get()).isTrue()
			assertThat(second.get()).isFalse()
		}
	}

	@Test
	fun `a daemon that dies after a restart still fires the death listener`() {
		// The mirror of the respawn test, and why start() installs a fresh marker instead of
		// leaving the stopped child's one in place: suppressing a later child's real death is
		// the failure mode a "never clear it" fix would introduce, and it is the worse one -
		// the session would sit on a dead daemon with nothing to trigger the respawn.
		//
		// The second child exits only after reading the ping, so its configure has certainly
		// been answered first: no interleaving decides what this test observes.
		val paths =
			replyingPaths(
				"""
				if [ -f '$tmp/first-spawn' ]; then
					read line
					reply "${'$'}line"
					read line
					exit 7
				fi
				: > '$tmp/first-spawn'
				while read line; do
					reply "${'$'}line"
				done
				""".trimIndent(),
			)
		val deaths = CopyOnWriteArrayList<Int>()
		val latch = CountDownLatch(1)
		val supervisor = SupervisorJob()
		val scope = CoroutineScope(supervisor + Dispatchers.IO)
		val client = DaemonProcessClient(paths, scope, requestTimeoutMillis = 2_000)

		try {
			runBlocking {
				client.setDeathListener { code ->
					deaths.add(code)
					latch.countDown()
				}
				check(client.start(config()) is DaemonReply.Ok)
				client.shutdown()
				// Join the stopped child's readers before spawning its successor: pending and
				// configured are shared across spawns, so a watcher still in flight could fail
				// the second configure and turn a listener assertion into a spawn failure.
				withTimeout(30_000) { supervisor.children.toList().forEach { it.join() } }
				check(client.start(config()) is DaemonReply.Ok)
				assertThat(client.ping()).isFalse()
				assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue()
			}
		} finally {
			runBlocking { client.shutdown() }
			scope.cancel()
		}

		// Exactly one death: the deliberate shutdown of the first child reported nothing.
		assertThat(deaths).containsExactly(7)
	}

	@Test
	fun `a response missing the ok field is a build failure, not a success`() {
		val paths =
			scriptedPaths(
				"""
				read line
				printf '%s\n' '${okConfigure()}'
				read line
				printf '%s\n' '{"id":2}'
				read line
				printf '%s\n' '{"id":3,"ok":true}'
				""".trimIndent(),
			)

		val reply =
			withClient(paths) { client ->
				check(client.start(config()) is DaemonReply.Ok)
				client.dex(emptyList())
			}

		assertThat(reply).isInstanceOf(DaemonReply.BuildFailed::class.java)
		assertThat((reply as DaemonReply.BuildFailed).diagnostics).isEmpty()
	}

	@Test
	fun `relink sends each optional field independently`() {
		val paths =
			scriptedPaths(
				"""
				read line
				printf '%s\n' '${okConfigure()}'
				read line
				printf '%s' "${'$'}line" > '$tmp/relink-ids-only.txt'
				printf '%s\n' '{"id":2,"ok":true,"resourcesArsc":"/out/res/linked-res.apk"}'
				read line
				printf '%s' "${'$'}line" > '$tmp/relink-flats-only.txt'
				printf '%s\n' '{"id":3,"ok":true,"resourcesArsc":"/out/res/linked-res.apk"}'
				read line
				printf '%s\n' '{"id":4,"ok":true}'
				""".trimIndent(),
			)

		withClient(paths) { client ->
			check(client.start(config()) is DaemonReply.Ok)
			check(
				client.relink(
					RelinkInputs(
						resDirs = listOf(File(tmp, "res")),
						manifest = File(tmp, "AndroidManifest.xml"),
						stableIdsFile = File(tmp, "stableIds.txt"),
					),
				) is DaemonReply.Ok,
			)
			check(
				client.relink(
					RelinkInputs(
						resDirs = listOf(File(tmp, "res")),
						manifest = File(tmp, "AndroidManifest.xml"),
						libraryResources = listOf(File(tmp, "lib.flat")),
					),
				) is DaemonReply.Ok,
			)
		}

		val idsOnly = File(tmp, "relink-ids-only.txt").readText()
		assertThat(idsOnly).contains("stableIds")
		assertThat(idsOnly).doesNotContain("libraryResources")
		val flatsOnly = File(tmp, "relink-flats-only.txt").readText()
		assertThat(flatsOnly).doesNotContain("stableIds")
		assertThat(flatsOnly).contains("libraryResources")
	}

	@Test
	fun `shutdown before start is a no-op`() {
		val paths = scriptedPaths("read line")
		val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
		val client = DaemonProcessClient(paths, scope)
		try {
			runBlocking { client.shutdown() }
			assertThat(client.isRunning).isFalse()
		} finally {
			scope.cancel()
		}
	}

	@Test
	fun `shutdown force-kills a daemon that ignores the polite stop`() {
		// The script never reads the shutdown request and never exits on stdin EOF; the
		// client must escalate to destroyForcibly instead of hanging.
		val paths =
			scriptedPaths(
				"""
				trap '' TERM
				read line
				printf '%s\n' '${okConfigure()}'
				sleep 60
				""".trimIndent(),
			)
		val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
		val client = DaemonProcessClient(paths, scope, requestTimeoutMillis = 300)
		try {
			runBlocking {
				check(client.start(config()) is DaemonReply.Ok)
				val elapsed =
					kotlin.system.measureTimeMillis {
						client.shutdown()
					}
				// Polite request times out (3s cap) + 2s waitFor, then the hard kill; well
				// under the script's 60s sleep.
				assertThat(elapsed).isLessThan(30_000)
			}
			assertThat(client.isRunning).isFalse()
		} finally {
			runBlocking { client.shutdown() }
			scope.cancel()
		}
	}

	@Test
	fun `the configure request carries the project's dex min API`() {
		// The daemon defaults to its own floor when the key is absent, so a project whose
		// seed payload was dexed at another level needs the value actually on the wire -
		// a config field that never reaches the daemon leaves the baseline and its
		// increments desugared against different targets.
		val paths =
			scriptedPaths(
				"""
				read line
				printf '%s' "${'$'}line" > '$tmp/configure-request.txt'
				printf '%s\n' '${okConfigure()}'
				read line
				printf '%s\n' '{"id":2,"ok":true}'
				""".trimIndent(),
			)

		withClient(paths) { client ->
			check(client.start(config(minApi = 26)) is DaemonReply.Ok)
		}

		val configureRequest = File(tmp, "configure-request.txt").readText()
		assertThat(configureRequest).contains("\"minApi\":26")
	}

	@Test
	fun `the configure request states the min API even at the protocol default`() {
		// Sending it unconditionally is what makes the daemon's own fallback dead code
		// rather than a second, silently-diverging source of the level.
		val paths =
			scriptedPaths(
				"""
				read line
				printf '%s' "${'$'}line" > '$tmp/configure-request.txt'
				printf '%s\n' '${okConfigure()}'
				read line
				printf '%s\n' '{"id":2,"ok":true}'
				""".trimIndent(),
			)

		withClient(paths) { client ->
			check(client.start(config()) is DaemonReply.Ok)
		}

		assertThat(File(tmp, "configure-request.txt").readText())
			.contains("\"minApi\":${ConfigureRequest.DEFAULT_MIN_API}")
	}
}
