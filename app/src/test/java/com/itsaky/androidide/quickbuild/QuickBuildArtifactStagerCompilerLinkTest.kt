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
 * ADFA-4931 removed the Kotlin compiler from the APK, so the daemon now gets it from the
 * on-device Gradle distribution. That makes the link step load-bearing: if it silently does
 * nothing, the daemon's manifest Class-Path names a jar that is not there and the first
 * compile dies deep inside the Build Tools API.
 */
class QuickBuildArtifactStagerCompilerLinkTest {
	@get:Rule
	val temp = TemporaryFolder()

	private val jarName = "kotlin-compiler-embeddable-$KOTLIN_VERSION.jar"

	/** A fake `gradle-dists/` holding one distribution with a compiler jar of [content]. */
	private fun gradleDistsWithCompiler(content: String): File {
		val dists = temp.newFolder("gradle-dists")
		File(dists, "$GRADLE_DISTRIBUTION_NAME/lib").mkdirs()
		File(dists, "$GRADLE_DISTRIBUTION_NAME/lib/$jarName").writeText(content)
		return dists
	}

	@Test
	fun `compiler is readable from the daemon dir under the name the manifest expects`() {
		val dists = gradleDistsWithCompiler("compiler-bytes")
		val daemonDir = temp.newFolder("daemon")

		QuickBuildArtifactStager.linkKotlinCompiler(daemonDir, dists)

		// Asserting the CONTENT rather than mere existence: a symlink and a copy are both
		// acceptable outcomes, and both must leave the bytes reachable at this path.
		val staged = File(daemonDir, jarName)
		assertThat(staged.readText()).isEqualTo("compiler-bytes")
	}

	@Test
	fun `the staged name carries the pinned Kotlin version`() {
		val dists = gradleDistsWithCompiler("compiler-bytes")
		val daemonDir = temp.newFolder("daemon")

		QuickBuildArtifactStager.linkKotlinCompiler(daemonDir, dists)

		// The daemon jar's manifest Class-Path is generated from the same resolved version, so
		// the file name is a contract between the build and this staging step, not a detail.
		assertThat(daemonDir.list()!!.toList()).containsExactly(jarName)
	}

	@Test
	fun `a link rather than a copy is preferred so the bytes are not duplicated on device`() {
		val dists = gradleDistsWithCompiler("compiler-bytes")
		val daemonDir = temp.newFolder("daemon")

		QuickBuildArtifactStager.linkKotlinCompiler(daemonDir, dists)

		// The whole point of ADFA-4931 is to stop carrying two copies. A copy is a legitimate
		// fallback, but on any filesystem that supports links (every JVM this test runs on) we
		// should not be paying the bytes twice.
		val staged = File(daemonDir, jarName).toPath()
		assertThat(Files.isSymbolicLink(staged)).isTrue()
	}

	@Test
	fun `missing distribution fails loudly and names the path a human has to fix`() {
		val emptyDists = temp.newFolder("gradle-dists")
		val daemonDir = temp.newFolder("daemon")

		val thrown =
			runCatching { QuickBuildArtifactStager.linkKotlinCompiler(daemonDir, emptyDists) }
				.exceptionOrNull()

		// Failing here is deliberate: the alternative is a NoClassDefFoundError partway into the
		// user's first compile, a long way from the missing file that caused it.
		assertThat(thrown).isInstanceOf(FileNotFoundException::class.java)
		assertThat(thrown!!).hasMessageThat().contains(GRADLE_DISTRIBUTION_NAME)
		assertThat(thrown).hasMessageThat().contains(jarName)
		assertThat(daemonDir.list()!!.toList()).isEmpty()
	}

	@Test
	fun `a distribution directory for a different version does not satisfy the link`() {
		val dists = temp.newFolder("gradle-dists")
		File(dists, "gradle-0.0.1/lib").mkdirs()
		File(dists, "gradle-0.0.1/lib/$jarName").writeText("wrong distribution")
		val daemonDir = temp.newFolder("daemon")

		// Guards the version-derived path: the compiler must come from the distribution this
		// build actually bundles, not from whatever else happens to be extracted alongside it.
		val thrown =
			runCatching { QuickBuildArtifactStager.linkKotlinCompiler(daemonDir, dists) }
				.exceptionOrNull()

		assertThat(thrown).isInstanceOf(FileNotFoundException::class.java)
	}
}
