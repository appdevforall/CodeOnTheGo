package com.itsaky.androidide.quickbuild

import com.google.common.truth.Truth.assertThat
import org.adfa.constants.GRADLE_DISTRIBUTION_NAME
import org.adfa.constants.KOTLIN_VERSION
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files

/**
 * The daemon now gets several jars from the Gradle distribution instead of the APK, so a link
 * step that silently does nothing leaves its classpath naming files that are not there.
 */
class QuickBuildArtifactStagerDistLinkTest {
	@get:Rule
	val temp = TemporaryFolder()

	/** Stand-ins for the build-generated list, with two entries so a partial link step shows up. */
	private val jarNames =
		listOf(
			"kotlin-compiler-embeddable-$KOTLIN_VERSION.jar",
			"kotlin-stdlib-$KOTLIN_VERSION.jar",
		)

	/** A staged daemon dir whose list names [names], as the build's list task writes it. */
	private fun daemonDirListing(names: List<String> = jarNames): File {
		val daemonDir = temp.newFolder("daemon")
		File(daemonDir, QuickBuildArtifactStager.LINKED_JARS_LIST)
			.writeText(names.joinToString("\n", postfix = "\n"))
		return daemonDir
	}

	/** A fake `gradle-dists/` holding one distribution whose lib/ carries [names]. */
	private fun gradleDistsWith(names: List<String> = jarNames): File {
		val dists = temp.newFolder("gradle-dists")
		val lib = File(dists, "$GRADLE_DISTRIBUTION_NAME/lib")
		lib.mkdirs()
		names.forEach { File(lib, it).writeText("$it-bytes") }
		return dists
	}

	private fun stagedNames(daemonDir: File) = daemonDir.list()!!.toList() - QuickBuildArtifactStager.LINKED_JARS_LIST

	@Test
	fun `every listed jar is readable from the daemon dir under the name the manifest expects`() {
		val daemonDir = daemonDirListing()

		QuickBuildArtifactStager.linkDistJars(daemonDir, gradleDistsWith())

		// Asserts content, not existence, because a symlink and a copy are both acceptable.
		jarNames.forEach { name ->
			assertThat(File(daemonDir, name).readText()).isEqualTo("$name-bytes")
		}
	}

	@Test
	fun `nothing beyond the listed jars is staged`() {
		val daemonDir = daemonDirListing()

		// Linking the whole lib/ would bury the daemon's classpath in Gradle's own jars.
		QuickBuildArtifactStager.linkDistJars(
			daemonDir,
			gradleDistsWith(jarNames + "gradle-core-api-9.6.1.jar"),
		)

		assertThat(stagedNames(daemonDir)).containsExactlyElementsIn(jarNames)
	}

	@Test
	fun `a link rather than a copy is preferred so the bytes are not duplicated on device`() {
		val daemonDir = daemonDirListing()

		QuickBuildArtifactStager.linkDistJars(daemonDir, gradleDistsWith())

		// Where links work, we must not be paying for the bytes twice.
		jarNames.forEach { name ->
			assertThat(Files.isSymbolicLink(File(daemonDir, name).toPath())).isTrue()
		}
	}

	@Test
	fun `a jar that cannot be linked is copied instead, so the daemon still has the bytes`() {
		val daemonDir = daemonDirListing()
		// However a link is refused, the current bytes still have to end up at this path.
		val occupied = File(daemonDir, jarNames.first()).apply { writeText("stale-bytes") }

		QuickBuildArtifactStager.linkDistJars(daemonDir, gradleDistsWith())

		assertThat(Files.isSymbolicLink(occupied.toPath())).isFalse()
		assertThat(occupied.readText()).isEqualTo("${jarNames.first()}-bytes")
		// The fallback is per jar, so one refused link must not cost the rest theirs.
		assertThat(Files.isSymbolicLink(File(daemonDir, jarNames.last()).toPath())).isTrue()
		assertThat(File(daemonDir, jarNames.last()).readText()).isEqualTo("${jarNames.last()}-bytes")
	}

	@Test
	fun `missing distribution fails loudly and names the path a human has to fix`() {
		val daemonDir = daemonDirListing()
		val emptyDists = temp.newFolder("gradle-dists")

		val thrown =
			runCatching { QuickBuildArtifactStager.linkDistJars(daemonDir, emptyDists) }
				.exceptionOrNull()

		// Failing here is deliberate, because the alternative surfaces mid-compile instead.
		assertThat(thrown).isInstanceOf(FileNotFoundException::class.java)
		assertThat(thrown!!).hasMessageThat().contains(GRADLE_DISTRIBUTION_NAME)
		assertThat(thrown).hasMessageThat().contains(jarNames.first())
		assertThat(stagedNames(daemonDir)).isEmpty()
	}

	@Test
	fun `one listed jar missing from the distribution fails the whole staging`() {
		val daemonDir = daemonDirListing()

		// A partial link is worse than none: the daemon starts and dies on the missing class.
		val thrown =
			runCatching {
				QuickBuildArtifactStager.linkDistJars(daemonDir, gradleDistsWith(jarNames.take(1)))
			}.exceptionOrNull()

		assertThat(thrown).isInstanceOf(FileNotFoundException::class.java)
		assertThat(thrown!!).hasMessageThat().contains(jarNames.last())
	}

	@Test
	fun `a distribution directory for a different version does not satisfy the link`() {
		val daemonDir = daemonDirListing()
		val dists = temp.newFolder("gradle-dists")
		File(dists, "gradle-0.0.1/lib").mkdirs()
		jarNames.forEach { File(dists, "gradle-0.0.1/lib/$it").writeText("wrong distribution") }

		// The jars must come from the distribution this build bundles, not a neighbouring one.
		val thrown =
			runCatching { QuickBuildArtifactStager.linkDistJars(daemonDir, dists) }
				.exceptionOrNull()

		assertThat(thrown).isInstanceOf(FileNotFoundException::class.java)
	}

	@Test
	fun `a daemon zip built without the list fails rather than linking nothing`() {
		val daemonDir = temp.newFolder("daemon")

		// Without the list there is no way to tell a complete build from an empty one.
		val thrown =
			runCatching { QuickBuildArtifactStager.linkDistJars(daemonDir, gradleDistsWith()) }
				.exceptionOrNull()

		assertThat(thrown).isInstanceOf(FileNotFoundException::class.java)
		assertThat(thrown!!).hasMessageThat().contains(QuickBuildArtifactStager.LINKED_JARS_LIST)
	}

	@Test
	fun `an empty list is treated as a broken build, not as nothing to do`() {
		val daemonDir = daemonDirListing(names = emptyList())

		val thrown =
			runCatching { QuickBuildArtifactStager.linkDistJars(daemonDir, gradleDistsWith()) }
				.exceptionOrNull()

		assertThat(thrown).isInstanceOf(FileNotFoundException::class.java)
	}
}
