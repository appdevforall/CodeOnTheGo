package com.itsaky.androidide.gradle.quickbuild

import com.google.common.truth.Truth.assertThat
import groovy.json.JsonSlurper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class QuickBuildJsonTest {
	private val activities =
		listOf(
			ProxiedActivity(
				userClass = "com.example.app.MainActivity",
				proxyClass = "com.example.app.quickbuild.proxies.Proxy0Activity",
				isLauncher = true,
			),
			ProxiedActivity(
				userClass = "com.example.app.SettingsActivity",
				proxyClass = "com.example.app.quickbuild.proxies.Proxy1Activity",
				isLauncher = false,
			),
		)

	private val info =
		ManifestInfo(
			testAppId = "com.example.app.quickbuild",
			entryActivity = "com.example.app.MainActivity",
			activities = listOf("com.example.app.MainActivity", "com.example.app.SettingsActivity"),
		)

	@Test
	fun `components json is a flat proxy-to-user map`() {
		val parsed = JsonSlurper().parseText(QuickBuildJson.componentsJson(activities)) as Map<*, *>

		// Keys are USER classes, values are proxies: the runtime translates a user
		// activity FQN into the manifest-declared proxy component it launches.
		assertThat(parsed).containsExactly(
			"com.example.app.MainActivity",
			"com.example.app.quickbuild.proxies.Proxy0Activity",
			"com.example.app.SettingsActivity",
			"com.example.app.quickbuild.proxies.Proxy1Activity",
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
	fun `parseManifestInfo rejects json without a testAppId`() {
		val error =
			assertThrows<IllegalArgumentException> {
				QuickBuildJson.parseManifestInfo("""{"entryActivity": "a.b.C"}""")
			}
		assertThat(error).hasMessageThat().contains("testAppId")
	}
}
