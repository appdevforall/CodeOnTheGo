package com.itsaky.androidide.repositories

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.plugins.manager.core.PluginBuildFiles
import com.itsaky.androidide.plugins.manager.core.PluginManager
import com.itsaky.androidide.plugins.manager.core.PluginValidation
import com.itsaky.androidide.plugins.manager.loaders.PluginManifest
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Each plugin build is installed under a name of its own. Android caches a plugin's resources and
 * assets by path, so a build installed at the previous build's path read the previous build's
 * assets until the app restarted (ADFA-7160).
 */
@RunWith(RobolectricTestRunner::class)
class PluginRepositoryImplInstallTest {
	@get:Rule
	val temp = TemporaryFolder()

	@Test
	fun aBuildIsInstalledUnderItsContentHash() =
		runTest {
			val pluginsDir = temp.newFolder("plugins")
			val build = temp.newFile("incoming.cgp").apply { writeText("build A") }
			val expected = "${PluginBuildFiles.fileStem(ID, build)}.cgp"
			var loaded = false
			val manager =
				mockk<PluginManager>(relaxed = true) {
					every { getPluginValidation(any()) } returns
						Result.success(
							PluginValidation(
								manifest = PluginManifest(ID, "Example", "1.0", "", "", "Main", "1.0"),
								isDebug = false,
								iconDayEntryExists = false,
								iconNightEntryExists = false,
							),
						)
					// The install swaps the file inside the load lock, and refuses while the old build is loaded.
					coEvery { whileNotLoading<Any?>(any()) } coAnswers { firstArg<suspend () -> Any?>()() }
					coEvery { loadPlugins() } answers { loaded = true }
					every { getPlugin(ID) } answers { if (loaded) mockk() else null }
				}

			PluginRepositoryImpl({ manager }, pluginsDir).installPluginFromFile(build).getOrThrow()

			assertThat(pluginsDir.list()!!.toList()).containsExactly(expected)
		}

	private companion object {
		const val ID = "org.example.plugin"
	}
}
