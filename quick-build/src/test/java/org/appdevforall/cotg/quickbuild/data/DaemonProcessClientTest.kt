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
