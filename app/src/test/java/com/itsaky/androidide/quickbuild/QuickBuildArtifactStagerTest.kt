package com.itsaky.androidide.quickbuild

import com.google.common.truth.Truth.assertThat
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
 * The zip-slip guard is a security control: the daemon zip is a bundled asset today, but the
 * extraction must never write outside the daemon dir no matter what the archive says. These
 * tests watch the guard go red - a `../` entry must throw BEFORE any byte lands outside.
 *
 * The staging tests pin the other invariant: an already-staged daemon directory is left alone
 * for the same install, because a rebaseline stages while the compile daemon is running off it.
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

	private fun daemonZip(): ByteArrayInputStream =
		zipOf(
			"quickbuild-daemon.jar" to byteArrayOf(1, 2, 3),
			"lib/runtime.jar" to byteArrayOf(4, 5),
		)

	@Test
	fun `the first stage extracts and stamps the install`() {
		val daemonDir = File(tmp.newFolder("home"), "daemon")
		val jar = File(daemonDir, "quickbuild-daemon.jar")
		var opened = 0

		val ran =
			QuickBuildArtifactStager.stageDaemonIfNeeded("7:1000", daemonDir, jar) {
				opened++
				daemonZip()
			}

		assertThat(ran).isTrue()
		assertThat(opened).isEqualTo(1)
		assertThat(jar.readBytes()).isEqualTo(byteArrayOf(1, 2, 3))
		assertThat(File(daemonDir, QuickBuildArtifactStager.DAEMON_STAMP_FILE).readText()).isEqualTo("7:1000")
	}

	@Test
	fun `a second stage for the same install leaves the directory untouched`() {
		val daemonDir = File(tmp.newFolder("home"), "daemon")
		val jar = File(daemonDir, "quickbuild-daemon.jar")
		QuickBuildArtifactStager.stageDaemonIfNeeded("7:1000", daemonDir, jar) { daemonZip() }
		// A file the running daemon could depend on: gone means the directory was wiped.
		val planted = File(daemonDir, "opened-by-a-live-daemon.jar").apply { writeBytes(byteArrayOf(9)) }
		var opened = 0

		val ran =
			QuickBuildArtifactStager.stageDaemonIfNeeded("7:1000", daemonDir, jar) {
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
		QuickBuildArtifactStager.stageDaemonIfNeeded("7:1000", daemonDir, jar) { daemonZip() }
		val stale = File(daemonDir, "from-the-old-install.jar").apply { writeBytes(byteArrayOf(9)) }

		val ran = QuickBuildArtifactStager.stageDaemonIfNeeded("7:2000", daemonDir, jar) { daemonZip() }

		assertThat(ran).isTrue()
		assertThat(stale.exists()).isFalse()
		assertThat(File(daemonDir, QuickBuildArtifactStager.DAEMON_STAMP_FILE).readText()).isEqualTo("7:2000")
	}

	@Test
	fun `a matching stamp without the daemon jar re-stages`() {
		val daemonDir = File(tmp.newFolder("home"), "daemon")
		val jar = File(daemonDir, "quickbuild-daemon.jar")
		QuickBuildArtifactStager.stageDaemonIfNeeded("7:1000", daemonDir, jar) { daemonZip() }
		assertThat(jar.delete()).isTrue()

		val ran = QuickBuildArtifactStager.stageDaemonIfNeeded("7:1000", daemonDir, jar) { daemonZip() }

		assertThat(ran).isTrue()
		assertThat(jar.exists()).isTrue()
	}

	@Test
	fun `a failed extraction leaves no stamp so the next stage retries`() {
		val daemonDir = File(tmp.newFolder("home"), "daemon")
		val jar = File(daemonDir, "quickbuild-daemon.jar")

		val thrown =
			runCatching {
				QuickBuildArtifactStager.stageDaemonIfNeeded("7:1000", daemonDir, jar) { zipOf("lib/" to null) }
			}.exceptionOrNull()
		assertThat(thrown).isInstanceOf(FileNotFoundException::class.java)
		assertThat(File(daemonDir, QuickBuildArtifactStager.DAEMON_STAMP_FILE).exists()).isFalse()

		val ran = QuickBuildArtifactStager.stageDaemonIfNeeded("7:1000", daemonDir, jar) { daemonZip() }
		assertThat(ran).isTrue()
	}
}
