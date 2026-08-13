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
 * ADFA-4931 removed five jars from the APK - the Kotlin compiler and four smaller artifacts the
 * on-device Gradle distribution already ships - so the daemon now gets them from the
 * distribution. That makes the link step load-bearing: if it silently does nothing, the daemon's
 * manifest Class-Path names jars that are not there and the first compile dies deep inside the
 * Build Tools API.
 */
class QuickBuildArtifactStagerDistLinkTest {
	@get:Rule
	val temp = TemporaryFolder()

	/**
	 * Stand-ins for the real list, which the build generates. Two entries rather than one so a
	 * link step that handles only the first is visible; the compiler name is kept real because
	 * it is the one whose absence is most expensive.
	 */
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

		// Asserting the CONTENT rather than mere existence: a symlink and a copy are both
		// acceptable outcomes, and both must leave the bytes reachable at this path.
		jarNames.forEach { name ->
			assertThat(File(daemonDir, name).readText()).isEqualTo("$name-bytes")
		}
	}

	@Test
	fun `nothing beyond the listed jars is staged`() {
		val daemonDir = daemonDirListing()

		// The distribution carries far more than the daemon links; linking the whole lib/ would
		// bury the daemon's classpath in Gradle's own jars.
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

		// The whole point of ADFA-4931 is to stop carrying two copies. A copy is a legitimate
		// fallback, but on any filesystem that supports links (every JVM this test runs on) we
		// should not be paying the bytes twice.
		jarNames.forEach { name ->
			assertThat(Files.isSymbolicLink(File(daemonDir, name).toPath())).isTrue()
		}
	}

	@Test
	fun `missing distribution fails loudly and names the path a human has to fix`() {
		val daemonDir = daemonDirListing()
		val emptyDists = temp.newFolder("gradle-dists")

		val thrown =
			runCatching { QuickBuildArtifactStager.linkDistJars(daemonDir, emptyDists) }
				.exceptionOrNull()

		// Failing here is deliberate: the alternative is a NoClassDefFoundError partway into the
		// user's first compile, a long way from the missing file that caused it.
		assertThat(thrown).isInstanceOf(FileNotFoundException::class.java)
		assertThat(thrown!!).hasMessageThat().contains(GRADLE_DISTRIBUTION_NAME)
		assertThat(thrown).hasMessageThat().contains(jarNames.first())
		assertThat(stagedNames(daemonDir)).isEmpty()
	}

	@Test
	fun `one listed jar missing from the distribution fails the whole staging`() {
		val daemonDir = daemonDirListing()

		// A partial link is the worst outcome: the daemon starts, and dies on whichever class
		// happened to live in the jar that was skipped.
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

		// Guards the version-derived path: the jars must come from the distribution this build
		// actually bundles, not from whatever else happens to be extracted alongside it.
		val thrown =
			runCatching { QuickBuildArtifactStager.linkDistJars(daemonDir, dists) }
				.exceptionOrNull()

		assertThat(thrown).isInstanceOf(FileNotFoundException::class.java)
	}

	@Test
	fun `a daemon zip built without the list fails rather than linking nothing`() {
		val daemonDir = temp.newFolder("daemon")

		// The list is how the build tells the device which jars it left out. Without it there is
		// no way to tell "the build shipped everything" from "the build shipped nothing", and
		// guessing the friendly answer means a compile that dies much later.
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
