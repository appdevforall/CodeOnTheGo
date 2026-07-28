package org.appdevforall.cotg.quickbuild.data

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Drives the real [DaemonProcessClient] against a scripted fake daemon: a shell script
 * stands in for the java binary, replies to the configure request (id 1, the client's
 * first request) with a canned line, answers the shutdown request (id 2), then exits.
 * Exercises the client's actual process + protocol plumbing, not a mock.
 */
class DaemonProcessClientTest {
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

		// The client clears the child env; give the script a PATH for its utilities.
		override fun daemonEnvironment(): Map<String, String> = mapOf("PATH" to "/usr/bin:/bin")
	}

	private fun pathsWithFakeDaemon(configureReplyJson: String): ScriptedPaths {
		val script = File(tmp, "fake-java.sh")
		script.writeText(
			"""
			#!/bin/sh
			read line
			printf '%s\n' '$configureReplyJson'
			read line
			printf '%s\n' '{"id":2,"ok":true}'
			""".trimIndent() + "\n",
		)
		script.setExecutable(true)
		File(tmp, "daemon").mkdirs()
		return ScriptedPaths(tmp, script)
	}

	private fun config(): DaemonConfig =
		DaemonConfig(
			projectRoot = tmp,
			classpath = emptyList(),
			outDir = File(tmp, "out"),
			aapt2 = File(tmp, "aapt2"),
			d8Jar = File(tmp, "d8.jar"),
			androidJar = File(tmp, "android.jar"),
		)

	private fun startAgainst(configureReplyJson: String): DaemonReply<Unit> {
		val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
		val client = DaemonProcessClient(pathsWithFakeDaemon(configureReplyJson), scope)
		return try {
			runBlocking { client.start(config()) }
		} finally {
			runBlocking { client.shutdown() }
			scope.cancel()
		}
	}

	@Test
	fun `matching protocol version configures ok`() {
		val reply =
			startAgainst(
				"""{"id":1,"ok":true,"protocolVersion":${DaemonProcessClient.EXPECTED_PROTOCOL_VERSION}}""",
			)

		assertThat(reply).isEqualTo(DaemonReply.Ok(Unit))
	}

	@Test
	fun `mismatched protocol version fails configure naming both versions`() {
		val reply = startAgainst("""{"id":1,"ok":true,"protocolVersion":99}""")

		assertThat(reply).isInstanceOf(DaemonReply.Failed::class.java)
		val message = (reply as DaemonReply.Failed).message
		assertThat(message).contains("99")
		assertThat(message).contains(DaemonProcessClient.EXPECTED_PROTOCOL_VERSION.toString())
	}

	/**
	 * Starts the client against a daemon scripted to answer configure (id 1), then one
	 * build op (id 2), then shutdown (id 3), and runs [op] against it.
	 */
	private fun <T> withScriptedOp(
		configureReplyJson: String,
		opReplyJson: String,
		op: suspend (DaemonProcessClient) -> DaemonReply<T>,
	): DaemonReply<T> {
		val script = File(tmp, "fake-java.sh")
		script.writeText(
			"""
			#!/bin/sh
			read line
			printf '%s\n' '$configureReplyJson'
			read line
			printf '%s\n' '$opReplyJson'
			read line
			printf '%s\n' '{"id":3,"ok":true}'
			""".trimIndent() + "\n",
		)
		script.setExecutable(true)
		File(tmp, "daemon").mkdirs()
		val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
		val client = DaemonProcessClient(ScriptedPaths(tmp, script), scope)
		return try {
			runBlocking {
				check(client.start(config()) is DaemonReply.Ok) { "scripted configure failed" }
				op(client)
			}
		} finally {
			runBlocking { client.shutdown() }
			scope.cancel()
		}
	}

	private fun okConfigure(extra: String = "") =
		"""{"id":1,"ok":true,"protocolVersion":${DaemonProcessClient.EXPECTED_PROTOCOL_VERSION}$extra}"""

	@Test
	fun `compile reply carries the daemon's phase stats`() {
		val reply =
			withScriptedOp(
				okConfigure(),
				"""{"id":2,"ok":true,"classesDir":"/out/classes","kotlinMillis":300,"javaMillis":2900,
				"preSnapMillis":120,"postSnapMillis":130,"javaAbiSnapMillis":540,"nAllSources":292,
				"nKotlinToCompile":0,"nJavaSources":218,"nChangedClasses":323,"compileOrdinal":4}""".replace("\n", "")
					.replace("\t", ""),
			) { it.compile(emptyList(), emptyList()) }

		val stats = (reply as DaemonReply.Ok).value.stats!!
		assertThat(stats.preSnapMillis).isEqualTo(120)
		assertThat(stats.postSnapMillis).isEqualTo(130)
		assertThat(stats.javaAbiSnapMillis).isEqualTo(540)
		assertThat(stats.allSources).isEqualTo(292)
		assertThat(stats.kotlinToCompile).isEqualTo(0)
		assertThat(stats.javaSources).isEqualTo(218)
		assertThat(stats.changedClasses).isEqualTo(323)
		assertThat(stats.compileOrdinal).isEqualTo(4)
	}

	@Test
	fun `dex reply carries the class counts the pass moved`() {
		val reply =
			withScriptedOp(
				okConfigure(),
				"""{"id":2,"ok":true,"dexFile":"/out/dex/classes.dex","stripMillis":5492,"d8Millis":3104,""" +
					""""nClassFiles":464,"classBytes":1530112}""",
			) { it.dex(emptyList()) }

		val output = (reply as DaemonReply.Ok).value
		assertThat(output.stripMillis).isEqualTo(5492)
		assertThat(output.stats!!.classFiles).isEqualTo(464)
		assertThat(output.stats!!.classBytes).isEqualTo(1_530_112)
	}

	@Test
	fun `a daemon predating the stats leaves them null rather than zero`() {
		// Version-safety in the direction that actually happens: a STAGED daemon jar older
		// than the client. Absent keys must read as "not measured" so the residual is not
		// computed against fabricated zeros.
		val compile =
			withScriptedOp(okConfigure(), """{"id":2,"ok":true,"classesDir":"/out/classes"}""") {
				it.compile(emptyList(), emptyList())
			}
		val dex =
			withScriptedOp(okConfigure(), """{"id":2,"ok":true,"dexFile":"/out/dex/classes.dex"}""") {
				it.dex(emptyList())
			}

		assertThat((compile as DaemonReply.Ok).value.stats).isNull()
		assertThat((dex as DaemonReply.Ok).value.stats).isNull()
	}

	@Test
	fun `configure captures the scratch filesystem for the session`() {
		val script = File(tmp, "fake-java.sh")
		script.writeText(
			"""
			#!/bin/sh
			read line
			printf '%s\n' '${okConfigure(""","scratchFsType":"fuse"""")}'
			read line
			printf '%s\n' '{"id":2,"ok":true}'
			""".trimIndent() + "\n",
		)
		script.setExecutable(true)
		File(tmp, "daemon").mkdirs()
		val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
		val client = DaemonProcessClient(ScriptedPaths(tmp, script), scope)
		try {
			runBlocking { client.start(config()) }
			assertThat(client.scratchFsType).isEqualTo("fuse")
		} finally {
			runBlocking { client.shutdown() }
			scope.cancel()
		}
	}

	@Test
	fun `a configure that never succeeds reports no scratch filesystem`() {
		val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
		val client =
			DaemonProcessClient(
				pathsWithFakeDaemon("""{"id":1,"ok":true,"protocolVersion":99,"scratchFsType":"fuse"}"""),
				scope,
			)
		try {
			runBlocking { client.start(config()) }
			// A rejected daemon's filesystem must not be stamped onto the next session's rows.
			assertThat(client.scratchFsType).isNull()
		} finally {
			runBlocking { client.shutdown() }
			scope.cancel()
		}
	}

	@Test
	fun `missing protocol version fails configure`() {
		// The daemon has stamped protocolVersion into configure responses since the
		// protocol existed; an absent field means an alien daemon, not an old one.
		val reply = startAgainst("""{"id":1,"ok":true}""")

		assertThat(reply).isInstanceOf(DaemonReply.Failed::class.java)
		val message = (reply as DaemonReply.Failed).message
		assertThat(message).contains(DaemonProcessClient.EXPECTED_PROTOCOL_VERSION.toString())
		assertThat(message).contains("no protocolVersion")
	}
}
