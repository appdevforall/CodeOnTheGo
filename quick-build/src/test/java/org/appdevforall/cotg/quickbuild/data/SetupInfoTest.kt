package org.appdevforall.cotg.quickbuild.data

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.io.File

class SetupInfoTest {
	private val baseDir = File("/project")

	private fun json(extra: String = "") =
		"""
		{
			"testAppId": "com.example.app.quickbuild",
			"entryActivity": "com.example.app.MainActivity",
			"apkPath": "/apk/app-debug.apk"
			$extra
		}
		""".trimIndent()

	@Test
	fun `composeEnabled true parses through`() {
		val info = SetupInfo.parse(json(""","composeEnabled": true"""), baseDir)

		assertThat(info).isNotNull()
		assertThat(info!!.composeEnabled).isTrue()
	}

	@Test
	fun `composeEnabled defaults to false when absent`() {
		val info = SetupInfo.parse(json(), baseDir)

		assertThat(info).isNotNull()
		assertThat(info!!.composeEnabled).isFalse()
	}

	@Test
	fun `composeEnabled tolerates a non-boolean value`() {
		val info = SetupInfo.parse(json(""","composeEnabled": "yes""""), baseDir)

		assertThat(info).isNotNull()
		assertThat(info!!.composeEnabled).isFalse()
	}

	@Test
	fun `pre-v2 setup json parses with schema 0 and no components`() {
		val info = SetupInfo.parse(json(), baseDir)

		assertThat(info).isNotNull()
		assertThat(info!!.schema).isEqualTo(0)
		assertThat(info.components).isEmpty()
	}

	@Test
	fun `v2 components parse with kind, proxy, launcher and supertypes`() {
		val info =
			SetupInfo.parse(
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
		assertThat(activity.kind).isEqualTo(org.appdevforall.cotg.quickbuild.domain.ComponentKind.ACTIVITY)
		assertThat(activity.className).isEqualTo("com.example.app.MainActivity")
		assertThat(activity.proxyClass).isEqualTo("com.example.app.quickbuild.proxies.Proxy0Activity")
		assertThat(activity.launcher).isTrue()
		assertThat(activity.supertypes).containsExactly("com.example.app.BaseActivity")

		assertThat(service.kind).isEqualTo(org.appdevforall.cotg.quickbuild.domain.ComponentKind.SERVICE)
		assertThat(service.launcher).isFalse()

		assertThat(application.kind)
			.isEqualTo(org.appdevforall.cotg.quickbuild.domain.ComponentKind.APPLICATION)
		assertThat(application.proxyClass).isNull()
	}

	@Test
	fun `unknown component type is skipped, not fatal`() {
		val info =
			SetupInfo.parse(
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
			SetupInfo.parse(
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
	fun `suffix-mode setup json defaults to sameAppId off with no versionCode`() {
		val info = SetupInfo.parse(json(), baseDir)

		assertThat(info).isNotNull()
		assertThat(info!!.sameAppId).isFalse()
		assertThat(info.versionCode).isNull()
	}

	@Test
	fun `same-app-id fields parse from the plugin's string values`() {
		// The plugin writes STRING values ("true", "12346") - the setup.json convention.
		val info =
			SetupInfo.parse(json(""","sameAppId": "true", "versionCode": "12346""""), baseDir)

		assertThat(info).isNotNull()
		assertThat(info!!.sameAppId).isTrue()
		assertThat(info.versionCode).isEqualTo(12346)
	}

	@Test
	fun `same-app-id fields also accept native JSON types`() {
		val info = SetupInfo.parse(json(""","sameAppId": true, "versionCode": 7"""), baseDir)

		assertThat(info).isNotNull()
		assertThat(info!!.sameAppId).isTrue()
		assertThat(info.versionCode).isEqualTo(7)
	}

	@Test
	fun `malformed same-app-id values degrade to mode off`() {
		val info =
			SetupInfo.parse(json(""","sameAppId": "yes", "versionCode": "soon""""), baseDir)

		assertThat(info).isNotNull()
		assertThat(info!!.sameAppId).isFalse()
		assertThat(info.versionCode).isNull()
	}
}
