package com.itsaky.androidide.assets

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.itsaky.androidide.app.configuration.CpuArch
import com.itsaky.androidide.utils.Environment
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.adfa.constants.GRADLE_DISTRIBUTION_NAME
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * [BaseAssetsInstaller.postInstall] is wiring, and wiring is where a silent no-op hides: every
 * failure here looks like a successful install. A dedup pointed at the wrong directory links
 * nothing and logs "nothing to deduplicate"; a build-tools binary missed by the list stays
 * non-executable until a build fails a long way downstream. [DistJarDeduplicatorTest] pins the
 * dedup algorithm against paths it is handed, so what is left to pin - and all this file pins -
 * is that postInstall hands it the paths the extractor actually wrote to, and that the binary
 * list is the one the toolchain needs.
 *
 * The `Environment` statics are unset off-device, so each test sets them at temp directories and
 * restores them afterwards, the same way [com.itsaky.androidide.quickbuild.EnvironmentQuickBuildPathsD8Test]
 * does.
 */
class BaseAssetsInstallerPostInstallTest {
	@get:Rule
	val temp = TemporaryFolder()

	/**
	 * postInstall is inherited, so any concrete installer exercises it. The other three members of
	 * the contract are not under test and are stubbed out rather than faked.
	 */
	private class PostInstallOnly : BaseAssetsInstaller() {
		override suspend fun preInstall(
			context: Context,
			stagingDir: Path,
		) = Unit

		override suspend fun doInstall(
			context: Context,
			stagingDir: Path,
			cpuArch: CpuArch,
			entryName: String,
		) = Unit

		override fun expectedSize(entryName: String): Long = 0L
	}

	/** postInstall never reads the context; it is here only to satisfy the signature. */
	private val context: Context = mockk(relaxed = true)

	private var originalBuildToolsDir: File? = null
	private var originalMavenDir: File? = null
	private var originalGradleDists: File? = null

	private lateinit var buildTools: File
	private lateinit var mavenRepo: File
	private lateinit var gradleDists: File

	@Before
	fun redirectEnvironmentAtTempTrees() {
		originalBuildToolsDir = Environment.BUILD_TOOLS_DIR
		originalMavenDir = Environment.LOCAL_MAVEN_DIR
		originalGradleDists = Environment.GRADLE_DISTS

		buildTools = temp.newFolder("build-tools")
		mavenRepo = temp.newFolder("localMvnRepository")
		gradleDists = temp.newFolder("gradle-dists")

		Environment.BUILD_TOOLS_DIR = buildTools
		Environment.LOCAL_MAVEN_DIR = mavenRepo
		Environment.GRADLE_DISTS = gradleDists
	}

	@After
	fun restoreEnvironment() {
		Environment.BUILD_TOOLS_DIR = originalBuildToolsDir
		Environment.LOCAL_MAVEN_DIR = originalMavenDir
		Environment.GRADLE_DISTS = originalGradleDists
	}

	private fun runPostInstall() =
		runBlocking {
			PostInstallOnly().postInstall(context, temp.root.toPath())
		}

	/** A binary as the extractor leaves it: present and readable, but not yet executable. */
	private fun buildToolsFile(name: String): File =
		File(buildTools, name).apply {
			writeText(name)
			setExecutable(false, false)
		}

	/** A jar at the exact path the distribution extractor writes to, which is what is under test. */
	private fun distLibJar(
		name: String,
		content: String,
	): File {
		val lib = File(gradleDists, "$GRADLE_DISTRIBUTION_NAME/lib").apply { mkdirs() }
		return File(lib, name).apply { writeText(content) }
	}

	private fun mavenJar(
		coordinate: String,
		name: String,
		content: String,
	): File {
		val dir = File(mavenRepo, coordinate).apply { mkdirs() }
		return File(dir, name).apply { writeText(content) }
	}

	@Test
	fun `every build tool the toolchain invokes is executable afterwards`() {
		val tools =
			listOf(
				"aapt",
				"aapt2",
				"aidl",
				"apksigner",
				"d8",
				"dexdump",
				"split-select",
				"zipalign",
			).associateWith(::buildToolsFile)
		// Deliberately not in the list: the loop has to name its binaries rather than chmod the
		// directory, because build-tools also holds plain data files.
		val notABinary = buildToolsFile("NOTICE.txt")

		runPostInstall()

		for ((name, file) in tools) {
			assertWithMessage("$name is invoked by the build and must be executable")
				.that(file.canExecute())
				.isTrue()
		}
		assertThat(notABinary.canExecute()).isFalse()
	}

	@Test
	fun `a maven jar duplicating the bundled distribution is collapsed onto it`() {
		val duplicate =
			mavenJar(
				"org/jetbrains/kotlin/kotlin-compiler-embeddable/2.3.21",
				"kotlin-compiler-embeddable-2.3.21.jar",
				"shared-bytes",
			)
		val inDistribution = distLibJar("kotlin-compiler-embeddable-2.3.21.jar", "shared-bytes")

		runPostInstall()

		// The whole of the wiring: GRADLE_DISTS/<distribution>/lib is where the extractor puts the
		// distribution's jars. A dedup aimed one directory off links nothing, reclaims nothing, and
		// still completes the install - so only an actual link proves the path was composed right.
		assertThat(Files.isSymbolicLink(duplicate.toPath())).isTrue()
		assertThat(Files.readSymbolicLink(duplicate.toPath()).toFile().canonicalFile)
			.isEqualTo(inDistribution.canonicalFile)
		// Gradle resolves through the Maven coordinate and must not be able to tell the difference.
		assertThat(duplicate.readText()).isEqualTo("shared-bytes")
	}

	@Test
	fun `a half-extracted tree leaves the install alone rather than failing it`() {
		// postInstall also runs from the caller's `finally` after a FAILED install, so neither the
		// distribution nor the build tools are guaranteed to be on disk. Returning normally here is
		// the assertion: a space optimisation must not convert a failed install into a crash.
		val untouched = mavenJar("com/example/thing/1.0", "thing-1.0.jar", "bytes")

		runPostInstall()

		assertThat(Files.isSymbolicLink(untouched.toPath())).isFalse()
		assertThat(untouched.readText()).isEqualTo("bytes")
	}
}
