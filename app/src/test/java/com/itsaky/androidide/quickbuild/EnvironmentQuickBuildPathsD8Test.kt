package com.itsaky.androidide.quickbuild

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.utils.Environment
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Pins [EnvironmentQuickBuildPaths.d8Jar]'s two-source resolution, and nothing else on the class.
 *
 * d8 comes from the installed build-tools when the SDK ships one and from a jar staged beside
 * the daemon otherwise. Both branches fail the same way when the choice is wrong - a dex step
 * that cannot start - so a fallback that is never taken and one that is always taken are
 * indistinguishable from the error, and only the two directory states here separate them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class EnvironmentQuickBuildPathsD8Test {
	@get:Rule
	val temp = TemporaryFolder()

	private val context: Context = ApplicationProvider.getApplicationContext()

	private var originalBuildToolsDir: File? = null
	private var originalIdeHome: File? = null

	@Before
	fun captureEnvironment() {
		originalBuildToolsDir = Environment.BUILD_TOOLS_DIR
		originalIdeHome = Environment.ANDROIDIDE_HOME
	}

	@After
	fun restoreEnvironment() {
		Environment.BUILD_TOOLS_DIR = originalBuildToolsDir
		Environment.ANDROIDIDE_HOME = originalIdeHome
	}

	private fun paths(
		buildToolsDir: File,
		ideHome: File,
	): EnvironmentQuickBuildPaths {
		Environment.BUILD_TOOLS_DIR = buildToolsDir
		Environment.ANDROIDIDE_HOME = ideHome
		return EnvironmentQuickBuildPaths(context)
	}

	@Test
	fun `d8 comes from the installed build-tools when the SDK ships one`() {
		val buildTools = temp.newFolder("build-tools")
		File(buildTools, "lib").mkdirs()
		val shipped = File(buildTools, "lib/d8.jar")
		shipped.writeText("d8")

		val d8 = paths(buildTools, temp.newFolder("home")).d8Jar

		assertThat(d8).isEqualTo(shipped)
	}

	@Test
	fun `d8 falls back to the jar staged with the daemon when build-tools ships none`() {
		val ideHome = temp.newFolder("home")

		val d8 = paths(temp.newFolder("build-tools-without-d8"), ideHome).d8Jar

		assertThat(d8).isEqualTo(File(ideHome, "quickbuild/daemon/d8.jar"))
	}

	@Test
	fun `a directory sitting at the build-tools path is not a jar, so the daemon copy still wins`() {
		val buildTools = temp.newFolder("build-tools")
		// An interrupted SDK unpack leaves the name in place with nothing readable behind it;
		// handing that path to d8 fails at exec time with no hint of where it came from.
		File(buildTools, "lib/d8.jar").mkdirs()
		val ideHome = temp.newFolder("home")

		val d8 = paths(buildTools, ideHome).d8Jar

		assertThat(d8).isEqualTo(File(ideHome, "quickbuild/daemon/d8.jar"))
	}
}
