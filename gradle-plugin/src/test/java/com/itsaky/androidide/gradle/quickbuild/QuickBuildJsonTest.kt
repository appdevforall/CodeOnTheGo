package com.itsaky.androidide.gradle.quickbuild

import com.google.common.truth.Truth.assertThat
import groovy.json.JsonSlurper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class QuickBuildJsonTest {
	private val components =
		listOf(
			ProxiedComponent(
				type = ComponentType.ACTIVITY,
				userClass = "com.example.app.MainActivity",
				proxyClass = "com.example.app.quickbuild.proxies.Proxy0Activity",
				isLauncher = true,
			),
			ProxiedComponent(
				type = ComponentType.ACTIVITY,
				userClass = "com.example.app.SettingsActivity",
				proxyClass = "com.example.app.quickbuild.proxies.Proxy1Activity",
				isLauncher = false,
			),
			ProxiedComponent(
				type = ComponentType.SERVICE,
				userClass = "com.example.app.SyncService",
				proxyClass = "com.example.app.quickbuild.proxies.Proxy0Service",
			),
			ProxiedComponent(
				type = ComponentType.RECEIVER,
				userClass = "com.example.app.BootReceiver",
				proxyClass = "com.example.app.quickbuild.proxies.Proxy0Receiver",
			),
			ProxiedComponent(
				type = ComponentType.PROVIDER,
				userClass = "com.example.app.DataProvider",
				proxyClass = "com.example.app.quickbuild.proxies.Proxy0Provider",
			),
			ProxiedComponent(
				type = ComponentType.APPLICATION,
				userClass = "com.example.app.App",
				proxyClass = null,
			),
		)

	private val info =
		ManifestInfo(
			proxyAppId = "com.example.app.quickbuild",
			entryActivity = "com.example.app.MainActivity",
			activities = listOf("com.example.app.MainActivity", "com.example.app.SettingsActivity"),
			components = components,
		)

	@Test
	fun `manifest info round-trips through json`() {
		val roundTripped = QuickBuildJson.parseManifestInfo(QuickBuildJson.manifestInfoJson(info))

		assertThat(roundTripped).isEqualTo(info)
	}

	@Test
	fun `manifest info round-trips a null entry activity`() {
		val noLauncher = info.copy(entryActivity = null)

		val roundTripped = QuickBuildJson.parseManifestInfo(QuickBuildJson.manifestInfoJson(noLauncher))

		assertThat(roundTripped.entryActivity).isNull()
		assertThat(roundTripped.activities).isEqualTo(noLauncher.activities)
	}

	@Test
	fun `parseManifestInfo accepts pre-v2 json without components`() {
		val parsed =
			QuickBuildJson.parseManifestInfo(
				"""{"proxyAppId": "a.b.quickbuild", "entryActivity": "a.b.C", "activities": ["a.b.C"]}""",
			)

		assertThat(parsed.components).isEmpty()
	}

	@Test
	fun `parseManifestInfo rejects a component entry with an unknown type`() {
		val error =
			assertThrows<IllegalArgumentException> {
				QuickBuildJson.parseManifestInfo(
					"""{"proxyAppId": "a.b", "components": [{"type": "widget", "userClass": "a.b.W"}]}""",
				)
			}
		assertThat(error).hasMessageThat().contains("widget")
	}

	@Test
	fun `parseManifestInfo rejects json that is not an object`() {
		// A truncated or half-written intermediate parses to something that is not a map;
		// reading fields off it must fail here rather than surface as a null app id later.
		val error =
			assertThrows<IllegalArgumentException> {
				QuickBuildJson.parseManifestInfo("""["a.b.quickbuild"]""")
			}
		assertThat(error).hasMessageThat().contains("not a JSON object")
	}

	@Test
	fun `parseManifestInfo rejects a component entry without a type`() {
		val error =
			assertThrows<IllegalArgumentException> {
				QuickBuildJson.parseManifestInfo(
					"""{"proxyAppId": "a.b", "components": [{"userClass": "a.b.C"}]}""",
				)
			}
		assertThat(error).hasMessageThat().contains("'type'")
	}

	@Test
	fun `parseManifestInfo rejects a component entry without a userClass`() {
		// 'type' is present and valid here, so only the userClass check can fire.
		val error =
			assertThrows<IllegalArgumentException> {
				QuickBuildJson.parseManifestInfo(
					"""{"proxyAppId": "a.b", "components": [{"type": "activity"}]}""",
				)
			}
		assertThat(error).hasMessageThat().contains("'userClass'")
	}

	@Test
	fun `setup json carries manifest info plus the apk path`() {
		val json =
			QuickBuildJson.proxyAppReportJson(
				info,
				"/data/project/app/build/outputs/apk/debug/app-debug.apk",
				classpath = listOf("/sdk/android.jar", "/libs/kotlin-stdlib.jar"),
				proxyClassesDir = "/data/project/app/build/quickbuild/debug/proxy-classes",
				manifestPath = "/data/project/app/build/quickbuild/debug/AndroidManifest.xml",
				composeEnabled = true,
			)

		val parsed = JsonSlurper().parseText(json) as Map<*, *>
		assertThat(parsed["schema"]).isEqualTo(QuickBuildJson.SCHEMA_VERSION)
		assertThat(parsed["proxyAppId"]).isEqualTo("com.example.app.quickbuild")
		assertThat(parsed["entryActivity"]).isEqualTo("com.example.app.MainActivity")
		assertThat(parsed["activities"]).isEqualTo(info.activities)
		assertThat(parsed["apkPath"])
			.isEqualTo("/data/project/app/build/outputs/apk/debug/app-debug.apk")
		assertThat(parsed["classpath"]).isEqualTo(listOf("/sdk/android.jar", "/libs/kotlin-stdlib.jar"))
		assertThat(parsed["proxyClassesDir"])
			.isEqualTo("/data/project/app/build/quickbuild/debug/proxy-classes")
		assertThat(parsed["manifestPath"])
			.isEqualTo("/data/project/app/build/quickbuild/debug/AndroidManifest.xml")
		assertThat(parsed["composeEnabled"]).isEqualTo(true)
	}

	@Test
	fun `setup json components carry per-type fields and merged supertypes`() {
		val json =
			QuickBuildJson.proxyAppReportJson(
				info,
				"/apk/app-debug.apk",
				supertypes =
					mapOf(
						"com.example.app.SyncService" to listOf("com.example.app.BaseService"),
						"com.example.app.MainActivity" to listOf("com.example.app.BaseActivity"),
					),
			)

		val parsed = JsonSlurper().parseText(json) as Map<*, *>
		val entries = (parsed["components"] as List<*>).filterIsInstance<Map<*, *>>()
		assertThat(entries).hasSize(components.size)

		val activity = entries.single { it["userClass"] == "com.example.app.MainActivity" }
		assertThat(activity["type"]).isEqualTo("activity")
		assertThat(activity["proxyClass"]).isEqualTo("com.example.app.quickbuild.proxies.Proxy0Activity")
		assertThat(activity["launcher"]).isEqualTo(true)
		assertThat(activity["supertypes"]).isEqualTo(listOf("com.example.app.BaseActivity"))

		val service = entries.single { it["type"] == "service" }
		assertThat(service["userClass"]).isEqualTo("com.example.app.SyncService")
		assertThat(service["supertypes"]).isEqualTo(listOf("com.example.app.BaseService"))

		val provider = entries.single { it["type"] == "provider" }
		assertThat(provider["userClass"]).isEqualTo("com.example.app.DataProvider")
		assertThat(provider["supertypes"]).isEqualTo(emptyList<String>())

		val application = entries.single { it["type"] == "application" }
		assertThat(application["userClass"]).isEqualTo("com.example.app.App")
		assertThat(application.containsKey("proxyClass")).isFalse()
		assertThat(application["supertypes"]).isEqualTo(emptyList<String>())

		// Intent filters / exported / permission are manifest-only by design.
		entries.forEach { entry ->
			assertThat(entry.containsKey("exported")).isFalse()
			assertThat(entry.containsKey("permission")).isFalse()
			assertThat(entry.containsKey("intentFilters")).isFalse()
		}
	}

	@Test
	fun `proxyAppReportJson defaults composeEnabled to false`() {
		val info =
			ManifestInfo(
				proxyAppId = "com.example.app.quickbuild",
				entryActivity = "com.example.app.MainActivity",
				activities = listOf("com.example.app.MainActivity"),
			)

		val json = QuickBuildJson.proxyAppReportJson(info, "/apk/app-debug.apk")

		val parsed = JsonSlurper().parseText(json) as Map<*, *>
		assertThat(parsed["composeEnabled"]).isEqualTo(false)
	}

	@Test
	fun `parseManifestInfo rejects json without a proxyAppId`() {
		val error =
			assertThrows<IllegalArgumentException> {
				QuickBuildJson.parseManifestInfo("""{"entryActivity": "a.b.C"}""")
			}
		assertThat(error).hasMessageThat().contains("proxyAppId")
	}

	@Test
	fun `parseManifestInfo accepts the legacy testAppId key - an intermediate on device may predate the rename`() {
		val info = QuickBuildJson.parseManifestInfo("""{"testAppId": "a.b.quickbuild"}""")
		assertThat(info.proxyAppId).isEqualTo("a.b.quickbuild")
	}

	@Test
	fun `setup json carries annotation processors and source roots`() {
		val json =
			QuickBuildJson.proxyAppReportJson(
				info,
				"/apk/app-debug.apk",
				annotationProcessors = listOf("androidx.room:room-compiler:2.6.1"),
				sourceRoots =
					listOf(
						"/project/app/src/main/java",
						"/project/app/build/generated/ksp/v8Debug/kotlin",
					),
			)

		val parsed = JsonSlurper().parseText(json) as Map<*, *>
		assertThat(parsed["annotationProcessors"]).isEqualTo(listOf("androidx.room:room-compiler:2.6.1"))
		assertThat(parsed["sourceRoots"])
			.isEqualTo(
				listOf(
					"/project/app/src/main/java",
					"/project/app/build/generated/ksp/v8Debug/kotlin",
				),
			)
	}

	@Test
	fun `setup json reports no processors for a project without any`() {
		val parsed =
			JsonSlurper().parseText(QuickBuildJson.proxyAppReportJson(info, "/apk/app-debug.apk")) as Map<*, *>

		assertThat(parsed["annotationProcessors"]).isEqualTo(emptyList<String>())
	}

	@Test
	fun `setup json carries the stable-ids path when the proxy app build found one`() {
		val json =
			QuickBuildJson.proxyAppReportJson(
				info,
				"/apk/app-debug.apk",
				stableIdsPath = "/project/app/build/intermediates/stable_resource_ids_file/debug/processDebugResources/stableIds.txt",
			)

		val parsed = JsonSlurper().parseText(json) as Map<*, *>
		assertThat(parsed["stableIdsPath"])
			.isEqualTo("/project/app/build/intermediates/stable_resource_ids_file/debug/processDebugResources/stableIds.txt")
	}

	@Test
	fun `setup json reports a null stable-ids path when the proxy app build found none`() {
		val parsed =
			JsonSlurper().parseText(QuickBuildJson.proxyAppReportJson(info, "/apk/app-debug.apk")) as Map<*, *>

		assertThat(parsed.containsKey("stableIdsPath")).isTrue()
		assertThat(parsed["stableIdsPath"]).isNull()
	}

	@Test
	fun `setup json carries library resource paths when the proxy app build found any`() {
		val json =
			QuickBuildJson.proxyAppReportJson(
				info,
				"/apk/app-debug.apk",
				libraryResourcePaths =
					listOf(
						"/project/app/build/intermediates/merged_res/debug/values_values.arsc.flat",
						"/root/.gradle/caches/.../transformed/com.google.android.material/drawable_ic_x.xml.flat",
					),
			)

		val parsed = JsonSlurper().parseText(json) as Map<*, *>
		assertThat(parsed["libraryResourcePaths"])
			.isEqualTo(
				listOf(
					"/project/app/build/intermediates/merged_res/debug/values_values.arsc.flat",
					"/root/.gradle/caches/.../transformed/com.google.android.material/drawable_ic_x.xml.flat",
				),
			)
	}

	@Test
	fun `setup json reports an empty library resource paths list by default`() {
		val parsed =
			JsonSlurper().parseText(QuickBuildJson.proxyAppReportJson(info, "/apk/app-debug.apk")) as Map<*, *>

		assertThat(parsed["libraryResourcePaths"]).isEqualTo(emptyList<String>())
	}

	@Test
	fun `setup json publishes the API level the seed payload was dexed at`() {
		// A project whose effective level is above the daemon's own floor: without this key
		// the daemon dexes its increments at 30 while this build dexed the baseline at 33.
		val json = QuickBuildJson.proxyAppReportJson(info, "/apk/app-debug.apk", minApi = 33)

		val parsed = JsonSlurper().parseText(json) as Map<*, *>
		assertThat(parsed["minApi"]).isEqualTo(33)
	}

	@Test
	fun `setup json reports a null min API when the build did not publish one`() {
		val parsed =
			JsonSlurper().parseText(QuickBuildJson.proxyAppReportJson(info, "/apk/app-debug.apk")) as Map<*, *>

		assertThat(parsed.containsKey("minApi")).isTrue()
		assertThat(parsed["minApi"]).isNull()
	}
}
