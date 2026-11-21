package com.itsaky.androidide.assets

import android.content.Context
import com.aayushatharva.brotli4j.Brotli4jLoader
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.app.configuration.CpuArch
import com.itsaky.androidide.app.configuration.IDEBuildConfigProvider
import com.itsaky.androidide.assets.addons.BundledAddonKind
import com.itsaky.androidide.assets.addons.BundledAddons
import com.itsaky.androidide.assets.addons.BundledAddonsRecord
import com.itsaky.androidide.assets.addons.BundledAddonsState
import com.itsaky.androidide.assets.addons.BundledEntry
import com.itsaky.androidide.assets.addons.InstalledAddon
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * The bundle record is written last and only for a complete installation. Like the other tests of
 * the helper, this relies on AssetsInstaller.CURRENT_INSTALLER being SplitAssetsInstaller, which
 * only holds for debug builds: run it via :app:testV8DebugUnitTest.
 */
class AssetsInstallationHelperBundleTest {
	@get:Rule
	val tempFolder = TemporaryFolder()

	private val ctx: Context = mockk(relaxed = true)
	private val helper = AssetsInstallationHelper
	private lateinit var filesDir: File

	@Before
	fun setUp() {
		filesDir = tempFolder.newFolder("files")
		every { ctx.filesDir } returns filesDir

		mockkObject(helper)
		every {
			helper["checkStorageAccessibility"](any<Context>(), any<AssetsInstallerProgressConsumer>())
		} returns null

		mockkObject(IDEBuildConfigProvider.Companion)
		mockkStatic(Brotli4jLoader::class)
		val provider = mockk<IDEBuildConfigProvider>(relaxed = true)
		every { provider.cpuArch } returns CpuArch.AARCH64
		every { IDEBuildConfigProvider.getInstance() } returns provider
		every { Brotli4jLoader.ensureAvailability() } just Runs

		mockkObject(SplitAssetsInstaller)
		coEvery { SplitAssetsInstaller.preInstall(any(), any()) } just Runs
		coEvery { SplitAssetsInstaller.postInstall(any(), any()) } just Runs
	}

	@After
	fun tearDown() {
		unmockkAll()
	}

	private val record get() = BundledAddonsRecord.inFilesDir(filesDir)

	private val bundle =
		listOf(
			BundledEntry(BundledAddonKind.PLUGIN, "a.cgp", 10L),
			BundledEntry(BundledAddonKind.PLUGIN, "b.cgp", 20L),
			BundledEntry(BundledAddonKind.TEMPLATE, "t.cgt", 30L),
		)

	private fun installed(name: String) =
		when {
			name.endsWith(".cgt") -> InstalledAddon(BundledAddonKind.TEMPLATE, File(filesDir, name), null)
			else -> InstalledAddon(BundledAddonKind.PLUGIN, File(filesDir, "$name-hash.cgp"), "id.${name.removeSuffix(".cgp")}")
		}

	@Test
	fun `every entry is installed, then the record is written`() =
		runBlocking {
			val seen =
				java.util.concurrent.ConcurrentHashMap
					.newKeySet<String>()
			every { SplitAssetsInstaller.listBundledAddons(any()) } returns bundle
			every { SplitAssetsInstaller.installedAddons() } returns bundle.map { installed(it.fileName) }
			coEvery { SplitAssetsInstaller.doInstall(any(), any(), any(), any()) } coAnswers {
				assertThat(record.read()).isNull()
				seen += arg<String>(3)
			}

			val result = helper.install(ctx)

			assertThat(result).isEqualTo(AssetsInstallationHelper.Result.Success)
			assertThat(seen).containsExactlyElementsIn(AssetsInstallationHelper.entriesToInstall(bundle))
			assertThat(record.read())
				.isEqualTo(
					BundledAddonsState(
						fingerprint = BundledAddons.fingerprint(bundle),
						pluginIds = listOf("id.a", "id.b"),
						templateFiles = listOf("t.cgt"),
					),
				)
		}

	@Test
	fun `a thin build installs the eight entries and records an empty bundle`() =
		runBlocking {
			val seen =
				java.util.concurrent.ConcurrentHashMap
					.newKeySet<String>()
			every { SplitAssetsInstaller.listBundledAddons(any()) } returns emptyList()
			every { SplitAssetsInstaller.installedAddons() } returns emptyList()
			coEvery { SplitAssetsInstaller.doInstall(any(), any(), any(), any()) } coAnswers { seen += arg<String>(3) }

			helper.install(ctx)

			assertThat(seen).containsExactlyElementsIn(AssetsInstallationHelper.expectedEntries)
			assertThat(seen).hasSize(8)
			assertThat(record.read()).isEqualTo(BundledAddonsState(fingerprint = BundledAddons.fingerprint(emptyList())))
		}

	@Test
	fun `no record is written when one bundled copy fails`() =
		runBlocking {
			every { SplitAssetsInstaller.listBundledAddons(any()) } returns bundle
			every { SplitAssetsInstaller.installedAddons() } returns bundle.map { installed(it.fileName) }
			coEvery { SplitAssetsInstaller.doInstall(any(), any(), any(), any()) } coAnswers {
				if (arg<String>(3) == "plugins/b.cgp") throw IOException("disk full")
			}

			val result = helper.install(ctx)

			assertThat(result).isInstanceOf(AssetsInstallationHelper.Result.Failure::class.java)
			assertThat(record.read()).isNull()
			assertThat(File(filesDir, BundledAddonsRecord.FILE_NAME).exists()).isFalse()
		}

	@Test
	fun `no record is written when a fixed entry fails`() =
		runBlocking {
			every { SplitAssetsInstaller.listBundledAddons(any()) } returns bundle
			every { SplitAssetsInstaller.installedAddons() } returns bundle.map { installed(it.fileName) }
			coEvery { SplitAssetsInstaller.doInstall(any(), any(), any(), any()) } coAnswers {
				if (arg<String>(3) == "bootstrap.zip") throw IOException("disk full")
			}

			helper.install(ctx)

			assertThat(record.read()).isNull()
		}
}
