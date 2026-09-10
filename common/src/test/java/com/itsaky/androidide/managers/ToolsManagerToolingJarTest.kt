package com.itsaky.androidide.managers

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Pins the tooling-jar extraction contract in [ToolsManager]: the stamp-match skip, the
 * re-extract triggers (stamp mismatch, missing jar, null stamp), the atomic
 * temp-then-rename copy, and the stamp-only-after-successful-rename ordering that makes
 * the skip check safe against a killed process.
 */
class ToolsManagerToolingJarTest {
	@get:Rule
	val tempFolder = TemporaryFolder()

	private val stamp = "1.2.3:1723456789"

	private fun jarFile(): File = File(tempFolder.root, "tooling-api-all.jar")

	private fun stampFile(): File = File(tempFolder.root, "tooling-api-all.jar.stamp")

	@Test
	fun `matching stamp with an extracted jar skips re-extraction`() {
		jarFile().writeText("jar-bytes")
		stampFile().writeText(stamp)

		assertThat(ToolsManager.isToolingJarCurrent(jarFile(), stampFile(), stamp)).isTrue()
	}

	@Test
	fun `a stale stamp re-extracts`() {
		jarFile().writeText("jar-bytes")
		stampFile().writeText("1.2.2:1700000000")

		assertThat(ToolsManager.isToolingJarCurrent(jarFile(), stampFile(), stamp)).isFalse()
	}

	@Test
	fun `a missing jar re-extracts even when the stamp matches`() {
		stampFile().writeText(stamp)

		assertThat(ToolsManager.isToolingJarCurrent(jarFile(), stampFile(), stamp)).isFalse()
	}

	@Test
	fun `a missing stamp file re-extracts`() {
		jarFile().writeText("jar-bytes")

		assertThat(ToolsManager.isToolingJarCurrent(jarFile(), stampFile(), stamp)).isFalse()
	}

	@Test
	fun `a null stamp (package lookup failed) always re-extracts`() {
		jarFile().writeText("jar-bytes")
		stampFile().writeText(stamp)

		assertThat(ToolsManager.isToolingJarCurrent(jarFile(), stampFile(), null)).isFalse()
	}

	@Test
	fun `extraction lands the full content, leaves no temp file, and writes the stamp`() {
		val content = "the-tooling-jar-bytes".toByteArray()

		ToolsManager.extractToolingJar(ByteArrayInputStream(content), jarFile(), stampFile(), stamp)

		assertThat(jarFile().readBytes()).isEqualTo(content)
		assertThat(File(tempFolder.root, "tooling-api-all.jar.part").exists()).isFalse()
		assertThat(stampFile().readText()).isEqualTo(stamp)
		// The freshly-extracted state must satisfy the next launch's skip check.
		assertThat(ToolsManager.isToolingJarCurrent(jarFile(), stampFile(), stamp)).isTrue()
	}

	@Test
	fun `extraction replaces an existing jar in place`() {
		jarFile().writeText("old-install-bytes")
		val content = "new-install-bytes".toByteArray()

		ToolsManager.extractToolingJar(ByteArrayInputStream(content), jarFile(), stampFile(), stamp)

		assertThat(jarFile().readBytes()).isEqualTo(content)
	}

	@Test
	fun `a null stamp still extracts the jar but writes no stamp`() {
		val content = "jar-bytes".toByteArray()

		ToolsManager.extractToolingJar(ByteArrayInputStream(content), jarFile(), stampFile(), null)

		assertThat(jarFile().readBytes()).isEqualTo(content)
		assertThat(stampFile().exists()).isFalse()
	}

	@Test
	fun `a failed rename writes no stamp, so the next launch retries`() {
		// A non-empty directory at the jar's final path makes File.renameTo fail
		// deterministically on POSIX, standing in for EIO/permission oddities.
		val blockedTarget = jarFile()
		blockedTarget.mkdirs()
		File(blockedTarget, "occupant").writeText("x")

		ToolsManager.extractToolingJar(
			ByteArrayInputStream("jar-bytes".toByteArray()),
			blockedTarget,
			stampFile(),
			stamp,
		)

		// Stamp absent -> isToolingJarCurrent is false -> the next launch re-extracts
		// instead of trusting a jar that never made it into place.
		assertThat(stampFile().exists()).isFalse()
		assertThat(ToolsManager.isToolingJarCurrent(blockedTarget, stampFile(), stamp)).isFalse()
	}

	@Test
	fun `ensureToolingJar replaces a previous install's jar before reporting ready`() {
		// The tooling server launches `java -jar` against the final path. If it can reach
		// that path while the previous install's jar is still there, it runs the prior APK's
		// tooling server for the whole session - so the readiness check must do the
		// replacement itself, not merely observe it.
		jarFile().writeText("previous-install-bytes")
		stampFile().writeText("1.2.2:1700000000")

		val ready =
			ToolsManager.ensureToolingJar(jarFile(), stampFile(), stamp) {
				ByteArrayInputStream("this-install-bytes".toByteArray())
			}

		assertThat(ready).isTrue()
		assertThat(jarFile().readText()).isEqualTo("this-install-bytes")
		assertThat(stampFile().readText()).isEqualTo(stamp)
	}

	@Test
	fun `ensureToolingJar does not open the asset when the jar is already this install's`() {
		// Cheap enough to call again on the server's launch path, which is what lets the
		// launch be ordered after extraction without paying for it on every project open.
		jarFile().writeText("this-install-bytes")
		stampFile().writeText(stamp)

		val ready =
			ToolsManager.ensureToolingJar(jarFile(), stampFile(), stamp) {
				throw AssertionError("asset opened although the jar was already current")
			}

		assertThat(ready).isTrue()
		assertThat(jarFile().readText()).isEqualTo("this-install-bytes")
	}

	@Test
	fun `ensureToolingJar reports not-ready when the extraction could not land`() {
		val blockedTarget = jarFile()
		blockedTarget.mkdirs()
		File(blockedTarget, "occupant").writeText("x")

		val ready =
			ToolsManager.ensureToolingJar(blockedTarget, stampFile(), stamp) {
				ByteArrayInputStream("this-install-bytes".toByteArray())
			}

		// False is what lets the caller say so rather than silently run whatever is there.
		assertThat(ready).isFalse()
	}
}
