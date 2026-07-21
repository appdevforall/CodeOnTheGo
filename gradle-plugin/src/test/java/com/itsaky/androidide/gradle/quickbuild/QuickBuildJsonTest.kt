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
				foregroundServiceType = "dataSync",
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
				authorities = listOf("com.example.app.quickbuild.data"),
			),
			ProxiedComponent(
				type = ComponentType.APPLICATION,
				userClass = "com.example.app.App",
				proxyClass = null,
			),
		)

	private val info =
		ManifestInfo(
			testAppId = "com.example.app.quickbuild",
			entryActivity = "com.example.app.MainActivity",
			activities = listOf("com.example.app.MainActivity", "com.example.app.SettingsActivity"),
			components = components,
		)

	@Test
	fun `components json is a flat map of every proxied component plus the schema marker`() {
		val parsed = JsonSlurper().parseText(QuickBuildJson.componentsJson(components)) as Map<*, *>

		// Keys are USER classes, values are proxies: the runtime translates a user
		// component FQN into the manifest-declared proxy component. The Application has
		// no proxy and stays out of the map; "schema" marks v2 as a plain string so the
		// runtime's string-values-only parser keeps it.
		assertThat(parsed).containsExactly(
			"schema",
			"2",
			"com.example.app.MainActivity",
			"com.example.app.quickbuild.proxies.Proxy0Activity",
			"com.example.app.SettingsActivity",
			"com.example.app.quickbuild.proxies.Proxy1Activity",
			"com.example.app.SyncService",
			"com.example.app.quickbuild.proxies.Proxy0Service",
			"com.example.app.BootReceiver",
			"com.example.app.quickbuild.proxies.Proxy0Receiver",
			"com.example.app.DataProvider",
			"com.example.app.quickbuild.proxies.Proxy0Provider",
		)
	}

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
				"""{"testAppId": "a.b.quickbuild", "entryActivity": "a.b.C", "activities": ["a.b.C"]}""",
			)

		assertThat(parsed.components).isEmpty()
	}

	@Test
	fun `parseManifestInfo rejects a component entry with an unknown type`() {
		val error =
			assertThrows<IllegalArgumentException> {
				QuickBuildJson.parseManifestInfo(
					"""{"testAppId": "a.b", "components": [{"type": "widget", "userClass": "a.b.W"}]}""",
				)
			}
		assertThat(error).hasMessageThat().contains("widget")
	}

	@Test
	fun `setup json carries manifest info plus the apk path`() {
		val json =
			QuickBuildJson.setupJson(
				info,
				"/data/project/app/build/outputs/apk/debug/app-debug.apk",
				classpath = listOf("/sdk/android.jar", "/libs/kotlin-stdlib.jar"),
				proxyClassesDir = "/data/project/app/build/quickbuild/debug/proxy-classes",
				manifestPath = "/data/project/app/build/quickbuild/debug/AndroidManifest.xml",
				composeEnabled = true,
			)

		val parsed = JsonSlurper().parseText(json) as Map<*, *>
		assertThat(parsed["schema"]).isEqualTo(QuickBuildJson.SCHEMA_VERSION)
		assertThat(parsed["testAppId"]).isEqualTo("com.example.app.quickbuild")
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
			QuickBuildJson.setupJson(
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
		assertThat(service["foregroundServiceType"]).isEqualTo("dataSync")
		assertThat(service["supertypes"]).isEqualTo(listOf("com.example.app.BaseService"))

		val provider = entries.single { it["type"] == "provider" }
		assertThat(provider["authorities"]).isEqualTo(listOf("com.example.app.quickbuild.data"))
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
	fun `setupJson defaults composeEnabled to false`() {
		val info =
			ManifestInfo(
				testAppId = "com.example.app.quickbuild",
				entryActivity = "com.example.app.MainActivity",
				activities = listOf("com.example.app.MainActivity"),
			)

		val json = QuickBuildJson.setupJson(info, "/apk/app-debug.apk")

		val parsed = JsonSlurper().parseText(json) as Map<*, *>
		assertThat(parsed["composeEnabled"]).isEqualTo(false)
	}

	@Test
	fun `setup json omits the same-app-id fields in suffix mode`() {
		// Additive contract: a suffix-mode setup.json must look exactly like before Path B
		// so old parsers (and the SetupInfo defaults) see nothing new.
		val json = QuickBuildJson.setupJson(info, "/apk/app-debug.apk")

		val parsed = JsonSlurper().parseText(json) as Map<*, *>
		assertThat(parsed.containsKey("sameAppId")).isFalse()
		assertThat(parsed.containsKey("versionCode")).isFalse()
		assertThat(parsed["schema"]).isEqualTo(QuickBuildJson.SCHEMA_VERSION)
	}

	@Test
	fun `setup json carries sameAppId and the pinned versionCode when set`() {
		val sameIdInfo = info.copy(testAppId = "com.example.app")

		val json =
			QuickBuildJson.setupJson(
				sameIdInfo,
				"/apk/app-debug.apk",
				sameAppId = true,
				versionCode = 12346,
			)

		val parsed = JsonSlurper().parseText(json) as Map<*, *>
		// String values per the design contract (same-app-id-design.md section 6); the
		// schema field stays at its current value - additive, no bump.
		assertThat(parsed["sameAppId"]).isEqualTo("true")
		assertThat(parsed["versionCode"]).isEqualTo("12346")
		assertThat(parsed["schema"]).isEqualTo(QuickBuildJson.SCHEMA_VERSION)
		assertThat(parsed["testAppId"]).isEqualTo("com.example.app")
	}

	@Test
	fun `parseManifestInfo rejects json without a testAppId`() {
		val error =
			assertThrows<IllegalArgumentException> {
				QuickBuildJson.parseManifestInfo("""{"entryActivity": "a.b.C"}""")
			}
		assertThat(error).hasMessageThat().contains("testAppId")
	}
}
