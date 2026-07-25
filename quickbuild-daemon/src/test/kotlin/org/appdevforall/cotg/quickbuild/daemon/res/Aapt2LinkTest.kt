package org.appdevforall.cotg.quickbuild.daemon.res

import com.google.common.truth.Truth.assertThat
import org.appdevforall.cotg.quickbuild.daemon.TestSdk
import org.appdevforall.cotg.quickbuild.daemon.protocol.Diagnostic
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipFile

@EnabledIf("org.appdevforall.cotg.quickbuild.daemon.TestSdk#aapt2ToolchainAvailable")
class Aapt2LinkTest {
	@TempDir
	lateinit var tempDir: File

	private lateinit var resDir: File
	private lateinit var manifest: File
	private lateinit var workDir: File

	@BeforeEach
	fun setUp() {
		resDir = File(tempDir, "res/values").apply { mkdirs() }.parentFile
		workDir = File(tempDir, "work").apply { mkdirs() }
		manifest =
			File(tempDir, "AndroidManifest.xml").apply {
				writeText(
					"""
					<?xml version="1.0" encoding="utf-8"?>
					<manifest xmlns:android="http://schemas.android.com/apk/res/android"
						package="demo.quickbuild">
						<application android:label="@string/app_name" />
					</manifest>
					""".trimIndent(),
				)
			}
	}

	private fun writeStrings(content: String) {
		File(resDir, "values/strings.xml").writeText(content)
	}

	@Test
	fun `relink produces a resources arsc from a valid res tree`() {
		writeStrings(
			"""
			<?xml version="1.0" encoding="utf-8"?>
			<resources>
				<string name="app_name">Quick Build Demo</string>
			</resources>
			""".trimIndent(),
		)
		val link = Aapt2Link(TestSdk.aapt2()!!, TestSdk.androidJar()!!)

		val result = link.relink(listOf(resDir), manifest, workDir)

		assertThat(result).isInstanceOf(Aapt2Link.Result.Success::class.java)
		val apk = (result as Aapt2Link.Result.Success).resourceApk
		assertThat(apk.length()).isGreaterThan(0)
		ZipFile(apk).use { zip -> assertThat(zip.getEntry("resources.arsc")).isNotNull() }
	}

	@Test
	fun `relinked apk carries file-backed resources, not just the arsc table`() {
		// A drawable XML has no useful value inside resources.arsc alone - the runtime
		// needs the actual zip entry to resolve it. ADFA-4128 Bug 5: the old arsc-only
		// relink shipped just the table, so ANY file-backed resource (even one the edit
		// never touched, e.g. an adaptive-icon mipmap XML) failed to resolve on the next
		// activity recreate. This asserts the fix: the shipped apk contains the file
		// entry, not just the table declaring it exists.
		File(resDir, "drawable").mkdirs()
		File(resDir, "drawable/plain_shape.xml").writeText(
			"""
			<?xml version="1.0" encoding="utf-8"?>
			<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="oval" />
			""".trimIndent(),
		)
		writeStrings(
			"""
			<?xml version="1.0" encoding="utf-8"?>
			<resources>
				<string name="app_name">Quick Build Demo</string>
			</resources>
			""".trimIndent(),
		)
		val link = Aapt2Link(TestSdk.aapt2()!!, TestSdk.androidJar()!!)

		val result = link.relink(listOf(resDir), manifest, workDir)

		assertThat(result).isInstanceOf(Aapt2Link.Result.Success::class.java)
		val apk = (result as Aapt2Link.Result.Success).resourceApk
		ZipFile(apk).use { zip ->
			assertThat(zip.getEntry("resources.arsc")).isNotNull()
			assertThat(zip.getEntry("res/drawable/plain_shape.xml")).isNotNull()
		}
	}

	@Test
	fun `relink twice in the same work dir succeeds (full recompile each time)`() {
		writeStrings(
			"""
			<?xml version="1.0" encoding="utf-8"?>
			<resources>
				<string name="app_name">First</string>
			</resources>
			""".trimIndent(),
		)
		val link = Aapt2Link(TestSdk.aapt2()!!, TestSdk.androidJar()!!)
		assertThat(link.relink(listOf(resDir), manifest, workDir))
			.isInstanceOf(Aapt2Link.Result.Success::class.java)

		writeStrings(
			"""
			<?xml version="1.0" encoding="utf-8"?>
			<resources>
				<string name="app_name">Second</string>
			</resources>
			""".trimIndent(),
		)
		val result = link.relink(listOf(resDir), manifest, workDir)

		assertThat(result).isInstanceOf(Aapt2Link.Result.Success::class.java)
	}

	@Test
	fun `malformed resource xml fails with error diagnostics, not a throw`() {
		writeStrings("<resources><string name=\"app_name\">unclosed")
		val link = Aapt2Link(TestSdk.aapt2()!!, TestSdk.androidJar()!!)

		val result = link.relink(listOf(resDir), manifest, workDir)

		assertThat(result).isInstanceOf(Aapt2Link.Result.Failed::class.java)
		val diagnostics = (result as Aapt2Link.Result.Failed).diagnostics
		assertThat(diagnostics).isNotEmpty()
		assertThat(diagnostics.any { it.severity == Diagnostic.Severity.ERROR }).isTrue()
	}

	@Test
	fun `a missing aapt2 binary fails with a message, not a throw`() {
		writeStrings("<resources />")
		val link = Aapt2Link(File(tempDir, "no-such-aapt2"), TestSdk.androidJar()!!)

		val result = link.relink(listOf(resDir), manifest, workDir)

		assertThat(result).isInstanceOf(Aapt2Link.Result.Failed::class.java)
	}

	// ADFA-4128 Bug 6: aapt2's declaration-order type-index assignment shifts when a whole
	// resource TYPE the real setup build produced (e.g. a library-injected `bool`) is
	// absent from a relink's narrower res tree - the test app's manifest, compiled once
	// against the baseline table, then decodes its numeric resource ids against the WRONG
	// type in the reattached table. `--stable-ids` pins ids to the baseline regardless.
	// These tests don't need a real aapt2 toolchain: `buildLinkArguments` is pure argument
	// assembly, unlike `relink` itself.

	@Test
	fun `link arguments carry --stable-ids when the file exists`() {
		val stableIds = File(tempDir, "stableIds.txt").apply { writeText("mipmap:ic_launcher = 0x7f040000") }
		val link = Aapt2Link(File(tempDir, "aapt2"), File(tempDir, "android.jar"))

		val arguments =
			link.buildLinkArguments(
				linkedApk = File(workDir, "linked-res.apk"),
				manifest = manifest,
				flatFiles = emptyList(),
				stableIds = stableIds,
			)

		assertThat(arguments).containsAtLeast("--stable-ids", stableIds.absolutePath).inOrder()
	}

	@Test
	fun `link arguments omit --stable-ids when the file is null`() {
		val link = Aapt2Link(File(tempDir, "aapt2"), File(tempDir, "android.jar"))

		val arguments =
			link.buildLinkArguments(
				linkedApk = File(workDir, "linked-res.apk"),
				manifest = manifest,
				flatFiles = emptyList(),
				stableIds = null,
			)

		assertThat(arguments).doesNotContain("--stable-ids")
	}

	@Test
	fun `link arguments omit --stable-ids when the file does not exist`() {
		val link = Aapt2Link(File(tempDir, "aapt2"), File(tempDir, "android.jar"))

		val arguments =
			link.buildLinkArguments(
				linkedApk = File(workDir, "linked-res.apk"),
				manifest = manifest,
				flatFiles = emptyList(),
				stableIds = File(tempDir, "no-such-stableIds.txt"),
			)

		assertThat(arguments).doesNotContain("--stable-ids")
	}

	@Test
	fun `relink with a stable-ids mapping keeps a pinned resource at its baseline id`() {
		writeStrings(
			"""
			<?xml version="1.0" encoding="utf-8"?>
			<resources>
				<string name="app_name">Quick Build Demo</string>
			</resources>
			""".trimIndent(),
		)
		val link = Aapt2Link(TestSdk.aapt2()!!, TestSdk.androidJar()!!)

		// Baseline link (no stable-ids): discover the real id aapt2 assigns app_name so this
		// test pins it to something ELSE, proving --stable-ids actually overrides the
		// default assignment rather than merely matching it by coincidence.
		val baselineResult = link.relink(listOf(resDir), manifest, File(workDir, "baseline").apply { mkdirs() })
		assertThat(baselineResult).isInstanceOf(Aapt2Link.Result.Success::class.java)
		val baselineId = dumpResourceId((baselineResult as Aapt2Link.Result.Success).resourceApk, "string/app_name")
		assertThat(baselineId).isNotNull()

		val pinnedId = "0x7f0199fe"
		assertThat(pinnedId).isNotEqualTo(baselineId)
		val stableIds = File(tempDir, "stableIds.txt").apply { writeText("demo.quickbuild:string/app_name = $pinnedId") }

		val pinnedWorkDir = File(workDir, "pinned").apply { mkdirs() }
		val pinnedResult = link.relink(listOf(resDir), manifest, pinnedWorkDir, stableIds = stableIds)

		assertThat(pinnedResult).isInstanceOf(Aapt2Link.Result.Success::class.java)
		val apk = (pinnedResult as Aapt2Link.Result.Success).resourceApk
		assertThat(dumpResourceId(apk, "string/app_name")).isEqualTo(pinnedId)
	}

	// ADFA-4128 Bug 8: a relink of the project's own res/ alone can't resolve a resource a
	// dependency AAR provides (e.g. Material3's Theme.Material3.DayNight.NoActionBar). The
	// daemon now feeds pre-compiled library-resource units back in as `-R` overlays.

	@Test
	fun `link arguments carry library resources as -R overlays, ordered before the project's own compile`() {
		val libraryResource = File(tempDir, "merged_res/values_values.arsc.flat")
		val link = Aapt2Link(File(tempDir, "aapt2"), File(tempDir, "android.jar"))
		val projectFlat = File(tempDir, "compiled/values_strings.arsc.flat")

		val arguments =
			link.buildLinkArguments(
				linkedApk = File(workDir, "linked-res.apk"),
				manifest = manifest,
				flatFiles = listOf(projectFlat),
				stableIds = null,
				libraryResources = listOf(libraryResource),
			)

		// Every resource input is `-R` (no bare positional) - see Aapt2Link's KDoc for why
		// bare positional would silently lose to any `-R`, regardless of order.
		val rIndices = arguments.withIndex().filter { it.value == "-R" }.map { it.index }
		assertThat(rIndices).hasSize(2)
		assertThat(arguments[rIndices[0] + 1]).isEqualTo(libraryResource.absolutePath)
		assertThat(arguments[rIndices[1] + 1]).isEqualTo(projectFlat.absolutePath)
		// The project's own fresh compile must be the LAST -R so it wins on conflict.
		assertThat(rIndices[1]).isGreaterThan(rIndices[0])
	}

	@Test
	fun `link arguments omit -R for an empty library resources list`() {
		val link = Aapt2Link(File(tempDir, "aapt2"), File(tempDir, "android.jar"))

		val arguments =
			link.buildLinkArguments(
				linkedApk = File(workDir, "linked-res.apk"),
				manifest = manifest,
				flatFiles = emptyList(),
				stableIds = null,
				libraryResources = emptyList(),
			)

		assertThat(arguments).doesNotContain("-R")
	}

	@Test
	fun `relink resolves a dependency-AAR-only style reference via libraryResources`() {
		// The project's OWN theme extends a style that ONLY a "library" declares - the
		// project's res/ never defines it, reproducing the exact BasicJ failure
		// (`style/Theme.Material3.DayNight.NoActionBar ... not found`).
		File(tempDir, "AndroidManifestTheme.xml").writeText(
			"""
			<?xml version="1.0" encoding="utf-8"?>
			<manifest xmlns:android="http://schemas.android.com/apk/res/android"
				package="demo.quickbuild">
				<application android:label="@string/app_name" android:theme="@style/AppTheme" />
			</manifest>
			""".trimIndent(),
		)
		writeStrings(
			"""
			<?xml version="1.0" encoding="utf-8"?>
			<resources>
				<string name="app_name">Quick Build Demo</string>
				<style name="AppTheme" parent="Theme.FakeLibrary.Base" />
			</resources>
			""".trimIndent(),
		)
		val link = Aapt2Link(TestSdk.aapt2()!!, TestSdk.androidJar()!!)

		// Baseline: without any library resources, the parent style doesn't exist -
		// reproduces Bug 8.
		val unfixed =
			link.relink(
				listOf(resDir),
				File(tempDir, "AndroidManifestTheme.xml"),
				File(workDir, "unfixed").apply { mkdirs() },
			)
		assertThat(unfixed).isInstanceOf(Aapt2Link.Result.Failed::class.java)
		assertThat((unfixed as Aapt2Link.Result.Failed).diagnostics.any { it.message.contains("Theme.FakeLibrary.Base") }).isTrue()

		// A separate "library" res tree, compiled independently (simulating merged_res /
		// a compiled-dependency-resources unit) and fed back in via libraryResources.
		val libraryRes = File(tempDir, "library-res/values").apply { mkdirs() }.parentFile
		File(libraryRes, "values/lib_styles.xml").writeText(
			"""
			<?xml version="1.0" encoding="utf-8"?>
			<resources>
				<style name="Theme.FakeLibrary.Base" parent="android:Theme.Material.Light" />
			</resources>
			""".trimIndent(),
		)
		val libraryCompileDir = File(tempDir, "library-compiled").apply { mkdirs() }
		val compileResult =
			ProcessBuilder(
				TestSdk.aapt2()!!.absolutePath,
				"compile",
				"--dir",
				libraryRes.absolutePath,
				"-o",
				libraryCompileDir.absolutePath,
			).redirectErrorStream(true)
				.start()
		val compileOutput = compileResult.inputStream.bufferedReader().readText()
		assertThat(compileResult.waitFor()).isEqualTo(0)
		val libraryFlat = libraryCompileDir.listFiles { file -> file.name.endsWith(".flat") }?.singleOrNull()
		assertThat(libraryFlat).isNotNull()
		assertThat(compileOutput).isNotNull() // keep the diagnostic text reachable for debugging a failed assertion above

		val fixed =
			link.relink(
				listOf(resDir),
				File(tempDir, "AndroidManifestTheme.xml"),
				File(workDir, "fixed").apply { mkdirs() },
				libraryResources = listOf(libraryFlat!!),
			)

		assertThat(fixed).isInstanceOf(Aapt2Link.Result.Success::class.java)
	}

	@Test
	fun `relink resolves a fresh project edit over a stale libraryResources copy of the same resource`() {
		// The correctness bug this fix could easily have reintroduced: if the project's own
		// fresh compile were bare positional (as originally planned) instead of -R, a stale
		// merged_res copy of the SAME resource would win over today's live edit. Verified
		// empirically (real aapt2) that -R always beats positional regardless of order, so
		// both flatFiles and libraryResources must be -R with flatFiles LAST.
		writeStrings(
			"""
			<?xml version="1.0" encoding="utf-8"?>
			<resources>
				<string name="app_name">FRESH_EDIT</string>
			</resources>
			""".trimIndent(),
		)
		val staleRes = File(tempDir, "stale-res/values").apply { mkdirs() }.parentFile
		File(staleRes, "values/strings.xml").writeText(
			"""
			<?xml version="1.0" encoding="utf-8"?>
			<resources>
				<string name="app_name">STALE_BASELINE</string>
			</resources>
			""".trimIndent(),
		)
		val staleCompileDir = File(tempDir, "stale-compiled").apply { mkdirs() }
		val compileResult =
			ProcessBuilder(
				TestSdk.aapt2()!!.absolutePath,
				"compile",
				"--dir",
				staleRes.absolutePath,
				"-o",
				staleCompileDir.absolutePath,
			).redirectErrorStream(true)
				.start()
		assertThat(compileResult.waitFor()).isEqualTo(0)
		val staleFlat = staleCompileDir.listFiles { file -> file.name.endsWith(".flat") }!!.single()

		val link = Aapt2Link(TestSdk.aapt2()!!, TestSdk.androidJar()!!)
		val result = link.relink(listOf(resDir), manifest, workDir, libraryResources = listOf(staleFlat))

		assertThat(result).isInstanceOf(Aapt2Link.Result.Success::class.java)
		val apk = (result as Aapt2Link.Result.Success).resourceApk
		val dumped =
			ProcessBuilder(TestSdk.aapt2()!!.absolutePath, "dump", "resources", apk.absolutePath)
				.redirectErrorStream(true)
				.start()
				.let {
					it.inputStream
						.bufferedReader()
						.readText()
						.also { _ -> it.waitFor() }
				}
		assertThat(dumped).contains("FRESH_EDIT")
		assertThat(dumped).doesNotContain("STALE_BASELINE")
	}

	/** Runs `aapt2 dump resources` and pulls the `resId` hex value for one `type/name` entry. */
	private fun dumpResourceId(
		apk: File,
		typeSlashName: String,
	): String? {
		val process =
			ProcessBuilder(TestSdk.aapt2()!!.absolutePath, "dump", "resources", apk.absolutePath)
				.redirectErrorStream(true)
				.start()
		val output = process.inputStream.bufferedReader().readText()
		process.waitFor()
		// aapt2 dump resources prints e.g.: "resource 0x7f010000 string/app_name: ..."
		val line = output.lineSequence().firstOrNull { it.trim().endsWith(typeSlashName) || it.contains(" $typeSlashName:") }
		return Regex("""0x[0-9a-fA-F]{8}""").find(line ?: return null)?.value
	}
}
