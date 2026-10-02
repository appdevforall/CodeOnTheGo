package com.itsaky.androidide.quickbuild

import com.google.common.truth.Truth.assertThat
import org.adfa.constants.GRADLE_DISTRIBUTION_NAME
import org.adfa.constants.KOTLIN_VERSION
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Covers two invariants: extraction never writes outside the daemon dir, and an already-staged
 * directory is left alone for the same install.
 */
class QuickBuildArtifactStagerTest {
	@get:Rule
	val tmp = TemporaryFolder()

	private fun zipOf(vararg entries: Pair<String, ByteArray?>): ByteArrayInputStream {
		val bytes = ByteArrayOutputStream()
		ZipOutputStream(bytes).use { zip ->
			for ((name, content) in entries) {
				zip.putNextEntry(ZipEntry(name))
				content?.let(zip::write)
				zip.closeEntry()
			}
		}
		return ByteArrayInputStream(bytes.toByteArray())
	}

	@Test
	fun `a well-formed zip extracts its files under the daemon dir`() {
		val daemonDir = tmp.newFolder("daemon")

		val count =
			QuickBuildArtifactStager.extractDaemonZip(
				zipOf(
					"daemon.jar" to byteArrayOf(1, 2, 3),
					"lib/" to null,
					"lib/runtime.jar" to byteArrayOf(4, 5),
				),
				daemonDir,
			)

		assertThat(count).isEqualTo(2)
		assertThat(File(daemonDir, "daemon.jar").readBytes()).isEqualTo(byteArrayOf(1, 2, 3))
		assertThat(File(daemonDir, "lib/runtime.jar").readBytes()).isEqualTo(byteArrayOf(4, 5))
	}

	@Test
	fun `a zip entry escaping the daemon dir throws and writes nothing outside it`() {
		val root = tmp.newFolder("root")
		val daemonDir = File(root, "daemon").also { it.mkdirs() }

		val thrown =
			runCatching {
				QuickBuildArtifactStager.extractDaemonZip(
					zipOf("../evil.txt" to byteArrayOf(7)),
					daemonDir,
				)
			}.exceptionOrNull()

		assertThat(thrown).isInstanceOf(IOException::class.java)
		assertThat(thrown).hasMessageThat().contains("evil.txt")
		assertThat(File(root, "evil.txt").exists()).isFalse()
	}

	@Test
	fun `the guard rejects an escaping entry even after well-formed ones`() {
		val root = tmp.newFolder("root2")
		val daemonDir = File(root, "daemon").also { it.mkdirs() }

		val thrown =
			runCatching {
				QuickBuildArtifactStager.extractDaemonZip(
					zipOf(
						"ok.jar" to byteArrayOf(1),
						"nested/../../evil.txt" to byteArrayOf(7),
					),
					daemonDir,
				)
			}.exceptionOrNull()

		assertThat(thrown).isInstanceOf(IOException::class.java)
		assertThat(File(root, "evil.txt").exists()).isFalse()
	}

	@Test
	fun `a zip with no files throws instead of reporting a staged daemon`() {
		val daemonDir = tmp.newFolder("empty-daemon")

		val thrown =
			runCatching {
				QuickBuildArtifactStager.extractDaemonZip(zipOf("lib/" to null), daemonDir)
			}.exceptionOrNull()

		assertThat(thrown).isInstanceOf(FileNotFoundException::class.java)
	}

	/** The one jar [daemonZip]'s linked-jars list names. */
	private val linkedJar = "kotlin-compiler-embeddable-$KOTLIN_VERSION.jar"

	/** A fake `gradle-dists/` for the link to point at, since the real one is unset off-device. */
	private val gradleDists: File by lazy {
		val dists = tmp.newFolder("gradle-dists")
		val lib = File(dists, "$GRADLE_DISTRIBUTION_NAME/lib")
		assertThat(lib.mkdirs()).isTrue()
		File(lib, linkedJar).writeText("linked-jar-bytes")
		dists
	}

	private fun daemonZip(): ByteArrayInputStream =
		zipOf(
			"quickbuild-daemon.jar" to byteArrayOf(1, 2, 3),
			"lib/runtime.jar" to byteArrayOf(4, 5),
			QuickBuildArtifactStager.LINKED_JARS_LIST to "$linkedJar\n".toByteArray(),
		)

	@Test
	fun `the first stage extracts and stamps the install`() {
		val daemonDir = File(tmp.newFolder("home"), "daemon")
		val jar = File(daemonDir, "quickbuild-daemon.jar")
		var opened = 0

		val ran =
			QuickBuildArtifactStager.stageDaemonIfNeeded("7:1000", daemonDir, jar, gradleDists) {
				opened++
				daemonZip()
			}

		assertThat(ran).isTrue()
		assertThat(opened).isEqualTo(1)
		assertThat(jar.readBytes()).isEqualTo(byteArrayOf(1, 2, 3))
		assertThat(File(daemonDir, QuickBuildArtifactStager.DAEMON_STAMP_FILE).readText()).isEqualTo("7:1000")
	}

	/**
	 * Checks that staging actually calls the link step, which a rebase once left unreachable
	 * behind a `return` while the direct tests stayed green.
	 */
	@Test
	fun `staging links the distribution's jars into the daemon dir`() {
		val daemonDir = File(tmp.newFolder("home"), "daemon")
		val jar = File(daemonDir, "quickbuild-daemon.jar")

		QuickBuildArtifactStager.stageDaemonIfNeeded("7:1000", daemonDir, jar, gradleDists) { daemonZip() }

		// Asserts content, not existence, because a symlink and a copy are both acceptable.
		assertThat(File(daemonDir, linkedJar).readText()).isEqualTo("linked-jar-bytes")
	}

	@Test
	fun `a second stage for the same install leaves the directory untouched`() {
		val daemonDir = File(tmp.newFolder("home"), "daemon")
		val jar = File(daemonDir, "quickbuild-daemon.jar")
		QuickBuildArtifactStager.stageDaemonIfNeeded("7:1000", daemonDir, jar, gradleDists) { daemonZip() }
		// A file the running daemon could depend on: gone means the directory was wiped.
		val planted = File(daemonDir, "opened-by-a-live-daemon.jar").apply { writeBytes(byteArrayOf(9)) }
		var opened = 0

		val ran =
			QuickBuildArtifactStager.stageDaemonIfNeeded("7:1000", daemonDir, jar, gradleDists) {
				opened++
				daemonZip()
			}

		assertThat(ran).isFalse()
		assertThat(opened).isEqualTo(0)
		assertThat(planted.exists()).isTrue()
	}

	@Test
	fun `a new install re-stages from scratch`() {
		val daemonDir = File(tmp.newFolder("home"), "daemon")
		val jar = File(daemonDir, "quickbuild-daemon.jar")
		QuickBuildArtifactStager.stageDaemonIfNeeded("7:1000", daemonDir, jar, gradleDists) { daemonZip() }
		val stale = File(daemonDir, "from-the-old-install.jar").apply { writeBytes(byteArrayOf(9)) }

		val ran = QuickBuildArtifactStager.stageDaemonIfNeeded("7:2000", daemonDir, jar, gradleDists) { daemonZip() }

		assertThat(ran).isTrue()
		assertThat(stale.exists()).isFalse()
		assertThat(File(daemonDir, QuickBuildArtifactStager.DAEMON_STAMP_FILE).readText()).isEqualTo("7:2000")
	}

	@Test
	fun `a matching stamp without the daemon jar re-stages`() {
		val daemonDir = File(tmp.newFolder("home"), "daemon")
		val jar = File(daemonDir, "quickbuild-daemon.jar")
		QuickBuildArtifactStager.stageDaemonIfNeeded("7:1000", daemonDir, jar, gradleDists) { daemonZip() }
		assertThat(jar.delete()).isTrue()

		val ran = QuickBuildArtifactStager.stageDaemonIfNeeded("7:1000", daemonDir, jar, gradleDists) { daemonZip() }

		assertThat(ran).isTrue()
		assertThat(jar.exists()).isTrue()
	}

	@Test
	fun `a matching stamp whose linked jar is gone re-stages`() {
		val daemonDir = File(tmp.newFolder("home"), "daemon")
		val jar = File(daemonDir, "quickbuild-daemon.jar")
		QuickBuildArtifactStager.stageDaemonIfNeeded("7:1000", daemonDir, jar, gradleDists) { daemonZip() }
		assertThat(File(daemonDir, linkedJar).delete()).isTrue()

		val ran = QuickBuildArtifactStager.stageDaemonIfNeeded("7:1000", daemonDir, jar, gradleDists) { daemonZip() }

		assertThat(ran).isTrue()
		assertThat(File(daemonDir, linkedJar).readText()).isEqualTo("linked-jar-bytes")
	}

	@Test
	fun `a matching stamp over a vanished distribution fails at staging, not mid-compile`() {
		val daemonDir = File(tmp.newFolder("home"), "daemon")
		val jar = File(daemonDir, "quickbuild-daemon.jar")
		QuickBuildArtifactStager.stageDaemonIfNeeded("7:1000", daemonDir, jar, gradleDists) { daemonZip() }
		// What an assets reinstall leaves for a moment: the link survives, its target does not.
		assertThat(File(gradleDists, "$GRADLE_DISTRIBUTION_NAME/lib/$linkedJar").delete()).isTrue()

		val thrown =
			runCatching {
				QuickBuildArtifactStager.stageDaemonIfNeeded("7:1000", daemonDir, jar, gradleDists) { daemonZip() }
			}.exceptionOrNull()

		assertThat(thrown).isInstanceOf(FileNotFoundException::class.java)
		assertThat(thrown).hasMessageThat().contains(linkedJar)
	}

	@Test
	fun `a failed link leaves no stamp so the next stage retries`() {
		val daemonDir = File(tmp.newFolder("home-link-fail"), "daemon")
		val jar = File(daemonDir, "quickbuild-daemon.jar")
		val emptyDists = tmp.newFolder("gradle-dists-empty")

		// Stamping a failed link would serve a daemon whose classpath names jars that are not
		// there, and the early return would never stage again.
		val thrown =
			runCatching {
				QuickBuildArtifactStager.stageDaemonIfNeeded("7:1000", daemonDir, jar, emptyDists) { daemonZip() }
			}.exceptionOrNull()
		assertThat(thrown).isInstanceOf(FileNotFoundException::class.java)
		assertThat(File(daemonDir, QuickBuildArtifactStager.DAEMON_STAMP_FILE).exists()).isFalse()

		val ran = QuickBuildArtifactStager.stageDaemonIfNeeded("7:1000", daemonDir, jar, gradleDists) { daemonZip() }
		assertThat(ran).isTrue()
	}

	@Test
	fun `a failed extraction leaves no stamp so the next stage retries`() {
		val daemonDir = File(tmp.newFolder("home"), "daemon")
		val jar = File(daemonDir, "quickbuild-daemon.jar")

		val thrown =
			runCatching {
				QuickBuildArtifactStager.stageDaemonIfNeeded("7:1000", daemonDir, jar, gradleDists) { zipOf("lib/" to null) }
			}.exceptionOrNull()
		assertThat(thrown).isInstanceOf(FileNotFoundException::class.java)
		assertThat(File(daemonDir, QuickBuildArtifactStager.DAEMON_STAMP_FILE).exists()).isFalse()

		val ran = QuickBuildArtifactStager.stageDaemonIfNeeded("7:1000", daemonDir, jar, gradleDists) { daemonZip() }
		assertThat(ran).isTrue()
	}
}
