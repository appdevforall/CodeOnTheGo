package org.appdevforall.cotg.quickbuild.data

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.io.File

class ProxyAppInfoTest {
	private val baseDir = File("/project")

	private fun json(extra: String = "") =
		"""
		{
			"proxyAppId": "com.example.app.quickbuild",
			"entryActivity": "com.example.app.MainActivity",
			"apkPath": "/apk/app-debug.apk"
			$extra
		}
		""".trimIndent()

	@Test
	fun `composeEnabled true parses through`() {
		val info = ProxyAppInfo.parse(json(""","composeEnabled": true"""), baseDir)

		assertThat(info).isNotNull()
		assertThat(info!!.composeEnabled).isTrue()
	}

	@Test
	fun `composeEnabled defaults to false when absent`() {
		val info = ProxyAppInfo.parse(json(), baseDir)

		assertThat(info).isNotNull()
		assertThat(info!!.composeEnabled).isFalse()
	}

	@Test
	fun `composeEnabled tolerates a non-boolean value`() {
		val info = ProxyAppInfo.parse(json(""","composeEnabled": "yes""""), baseDir)

		assertThat(info).isNotNull()
		assertThat(info!!.composeEnabled).isFalse()
	}

	@Test
	fun `pre-v2 setup json parses with schema 0 and no components`() {
		val info = ProxyAppInfo.parse(json(), baseDir)

		assertThat(info).isNotNull()
		assertThat(info!!.schema).isEqualTo(0)
		assertThat(info.components).isEmpty()
	}

	@Test
	fun `v2 components parse with kind, proxy, launcher and supertypes`() {
		val info =
			ProxyAppInfo.parse(
				json(
					""",
					"schema": 2,
					"components": [
						{"type": "activity", "userClass": "com.example.app.MainActivity",
						"proxyClass": "com.example.app.quickbuild.proxies.Proxy0Activity",
						"launcher": true, "supertypes": ["com.example.app.BaseActivity"]},
						{"type": "service", "userClass": "com.example.app.SyncService",
						"proxyClass": "com.example.app.quickbuild.proxies.Proxy0Service",
						"foregroundServiceType": "dataSync", "supertypes": []},
						{"type": "application", "userClass": "com.example.app.App"}
					]
					""",
				),
				baseDir,
			)

		assertThat(info).isNotNull()
		assertThat(info!!.schema).isEqualTo(2)
		assertThat(info.components).hasSize(3)

		val (activity, service, application) = info.components
		assertThat(activity.kind).isEqualTo(org.appdevforall.cotg.quickbuild.domain.reload.ComponentKind.ACTIVITY)
		assertThat(activity.className).isEqualTo("com.example.app.MainActivity")
		assertThat(activity.proxyClass).isEqualTo("com.example.app.quickbuild.proxies.Proxy0Activity")
		assertThat(activity.launcher).isTrue()
		assertThat(activity.supertypes).containsExactly("com.example.app.BaseActivity")

		assertThat(service.kind).isEqualTo(org.appdevforall.cotg.quickbuild.domain.reload.ComponentKind.SERVICE)
		assertThat(service.launcher).isFalse()

		assertThat(application.kind)
			.isEqualTo(org.appdevforall.cotg.quickbuild.domain.reload.ComponentKind.APPLICATION)
		assertThat(application.proxyClass).isNull()
	}

	@Test
	fun `unknown component type is skipped, not fatal`() {
		val info =
			ProxyAppInfo.parse(
				json(
					""",
					"schema": 2,
					"components": [
						{"type": "hologram", "userClass": "com.example.app.Future"},
						{"type": "service", "userClass": "com.example.app.SyncService"}
					]
					""",
				),
				baseDir,
			)

		assertThat(info).isNotNull()
		assertThat(info!!.components).hasSize(1)
		assertThat(info.components.single().className).isEqualTo("com.example.app.SyncService")
	}

	@Test
	fun `malformed component entries are skipped`() {
		val info =
			ProxyAppInfo.parse(
				json(
					""",
					"schema": 2,
					"components": [
						{"type": "service"},
						"not-an-object",
						{"userClass": "com.example.app.NoType"}
					]
					""",
				),
				baseDir,
			)

		assertThat(info).isNotNull()
		assertThat(info!!.components).isEmpty()
	}

	@Test
	fun `annotation processors and source roots parse through`() {
		val info =
			ProxyAppInfo.parse(
				json(
					"""
					,
					"annotationProcessors": ["androidx.room:room-compiler:2.6.1", "  "],
					"sourceRoots": ["app/src/main/java", "/abs/build/generated/ksp/debug/kotlin"]
					""".trimIndent(),
				),
				baseDir,
			)

		assertThat(info).isNotNull()
		assertThat(info!!.annotationProcessors).containsExactly("androidx.room:room-compiler:2.6.1")
		assertThat(info.sourceRoots)
			.containsExactly(
				File("/project/app/src/main/java"),
				File("/abs/build/generated/ksp/debug/kotlin"),
			).inOrder()
	}

	@Test
	fun `annotation processors and source roots default to empty`() {
		val info = ProxyAppInfo.parse(json(), baseDir)

		assertThat(info).isNotNull()
		assertThat(info!!.annotationProcessors).isEmpty()
		assertThat(info.sourceRoots).isEmpty()
	}

	@Test
	fun `stableIdsPath parses to an absolute file resolved against the base dir`() {
		val info =
			ProxyAppInfo.parse(
				json(""", "stableIdsPath": "app/build/intermediates/stable_resource_ids_file/debug/processDebugResources/stableIds.txt""""),
				baseDir,
			)

		assertThat(info).isNotNull()
		assertThat(info!!.stableIdsFile)
			.isEqualTo(File("/project/app/build/intermediates/stable_resource_ids_file/debug/processDebugResources/stableIds.txt"))
	}

	@Test
	fun `stableIdsPath is null when the proxy app build reported none`() {
		val info = ProxyAppInfo.parse(json(), baseDir)

		assertThat(info).isNotNull()
		assertThat(info!!.stableIdsFile).isNull()
	}

	@Test
	fun `libraryResourcePaths parse to absolute files resolved against the base dir`() {
		val info =
			ProxyAppInfo.parse(
				json(
					""", "libraryResourcePaths": ["app/build/intermediates/merged_res/debug/values_values.arsc.flat",
					"/root/.gradle/caches/8.14.3/transforms/abc/transformed/com.google.android.material/drawable_x.xml.flat"]""".replace(
						"\n",
						"",
					),
				),
				baseDir,
			)

		assertThat(info).isNotNull()
		assertThat(info!!.libraryResourceFlats)
			.containsExactly(
				File("/project/app/build/intermediates/merged_res/debug/values_values.arsc.flat"),
				File("/root/.gradle/caches/8.14.3/transforms/abc/transformed/com.google.android.material/drawable_x.xml.flat"),
			).inOrder()
	}

	@Test
	fun `libraryResourcePaths defaults to empty when the proxy app build reported none`() {
		val info = ProxyAppInfo.parse(json(), baseDir)

		assertThat(info).isNotNull()
		assertThat(info!!.libraryResourceFlats).isEmpty()
	}

	@Test
	fun `a null entryActivity parses successfully - a successful build with no launchable Activity is not a parse failure`() {
		// The plugin writes a literal JSON null for entryActivity when the project has
		// no launchable Activity (e.g. the No-Activity template), so entryActivity is
		// optional. Treating it as required makes parse() return null on a build that
		// succeeded, which the provisioner reports as "Quick Build proxy app build
		// failed".
		val info =
			ProxyAppInfo.parse(
				"""
				{
					"proxyAppId": "com.example.app.quickbuild",
					"entryActivity": null,
					"apkPath": "/apk/app-debug.apk"
				}
				""".trimIndent(),
				baseDir,
			)

		assertThat(info).isNotNull()
		assertThat(info!!.entryActivity).isNull()
	}

	@Test
	fun `an absent entryActivity key parses successfully as null too`() {
		val info =
			ProxyAppInfo.parse(
				"""
				{
					"proxyAppId": "com.example.app.quickbuild",
					"apkPath": "/apk/app-debug.apk"
				}
				""".trimIndent(),
				baseDir,
			)

		assertThat(info).isNotNull()
		assertThat(info!!.entryActivity).isNull()
	}

	@Test
	fun `legacy testAppId key still parses - a setup json on device may predate the rename`() {
		val info =
			ProxyAppInfo.parse(
				"""
				{
					"testAppId": "com.example.app.quickbuild",
					"apkPath": "/apk/app-debug.apk"
				}
				""".trimIndent(),
				baseDir,
			)

		assertThat(info).isNotNull()
		assertThat(info!!.proxyAppPackage).isEqualTo("com.example.app.quickbuild")
	}
}
