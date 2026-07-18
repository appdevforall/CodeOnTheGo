package com.itsaky.androidide.gradle.quickbuild

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.w3c.dom.Element
import java.io.File

class QuickBuildManifestTransformerTest {
	private val proxyPackage = "com.example.app.quickbuild.proxies"
	private val factory = "com.itsaky.androidide.quickbuild.runtime.QuickBuildAppComponentFactory"

	private fun transformer() = QuickBuildManifestTransformer(proxyPackage, factory)

	private fun manifest(
		body: String,
		packageName: String = "com.example.app.quickbuild",
	) = """
		<?xml version="1.0" encoding="utf-8"?>
		<manifest xmlns:android="http://schemas.android.com/apk/res/android"
			package="$packageName">
			<uses-permission android:name="android.permission.INTERNET" />
			<application
				android:icon="@mipmap/ic_launcher"
				android:label="My App">
				$body
			</application>
		</manifest>
		""".trimIndent().trim()

	private val launcherActivity =
		"""
		<activity android:name="com.example.app.MainActivity" android:exported="true">
			<intent-filter>
				<action android:name="android.intent.action.MAIN" />
				<category android:name="android.intent.category.LAUNCHER" />
			</intent-filter>
		</activity>
		""".trimIndent()

	@Test
	fun `rewrites activity names to proxies in document order`() {
		val result =
			transformer().transform(
				manifest(
					launcherActivity + "\n" + """<activity android:name="com.example.app.SettingsActivity" />""",
				).byteInputStream(),
			)

		assertThat(result.activities)
			.containsExactly(
				ProxiedActivity("com.example.app.MainActivity", "$proxyPackage.Proxy0Activity", true),
				ProxiedActivity("com.example.app.SettingsActivity", "$proxyPackage.Proxy1Activity", false),
			).inOrder()

		val names =
			result.document.getElementsByTagName("activity").let { nodes ->
				(0 until nodes.length).map {
					(nodes.item(it) as Element)
						.getAttributeNS(QuickBuildManifestTransformer.ANDROID_NS, "name")
				}
			}
		assertThat(names)
			.containsExactly(
				"$proxyPackage.Proxy0Activity",
				"$proxyPackage.Proxy1Activity",
			).inOrder()
	}

	@Test
	fun `detects the launcher activity as entry activity`() {
		val result =
			transformer().transform(
				manifest(
					"""<activity android:name="com.example.app.OtherActivity" />""" + "\n" + launcherActivity,
				).byteInputStream(),
			)

		assertThat(result.entryActivity).isEqualTo("com.example.app.MainActivity")
	}

	@Test
	fun `returns null entry activity when no launcher is declared`() {
		val result =
			transformer().transform(
				manifest("""<activity android:name="com.example.app.OtherActivity" />""").byteInputStream(),
			)

		assertThat(result.entryActivity).isNull()
	}

	@Test
	fun `resolves dot-shorthand names against the manifest package`() {
		val result =
			transformer().transform(
				manifest("""<activity android:name=".MainActivity" />""").byteInputStream(),
			)

		assertThat(result.activities.single().userClass)
			.isEqualTo("com.example.app.quickbuild.MainActivity")
	}

	@Test
	fun `adds the appComponentFactory and keeps application attributes`() {
		val result = transformer().transform(manifest(launcherActivity).byteInputStream())

		val application = result.document.getElementsByTagName("application").item(0) as Element
		assertThat(
			application.getAttributeNS(QuickBuildManifestTransformer.ANDROID_NS, "appComponentFactory"),
		).isEqualTo(factory)
		assertThat(application.getAttributeNS(QuickBuildManifestTransformer.ANDROID_NS, "icon"))
			.isEqualTo("@mipmap/ic_launcher")
		assertThat(application.getAttributeNS(QuickBuildManifestTransformer.ANDROID_NS, "label"))
			.isEqualTo("My App")
	}

	@Test
	fun `keeps permissions and intent filters`() {
		val result = transformer().transform(manifest(launcherActivity).byteInputStream())

		val permission = result.document.getElementsByTagName("uses-permission").item(0) as Element
		assertThat(permission.getAttributeNS(QuickBuildManifestTransformer.ANDROID_NS, "name"))
			.isEqualTo("android.permission.INTERNET")

		val activity = result.document.getElementsByTagName("activity").item(0) as Element
		assertThat(activity.getElementsByTagName("intent-filter").length).isEqualTo(1)
		assertThat(activity.getAttributeNS(QuickBuildManifestTransformer.ANDROID_NS, "exported"))
			.isEqualTo("true")
	}

	@Test
	fun `rewrites activity-alias targets to the proxy`() {
		val result =
			transformer().transform(
				manifest(
					launcherActivity + "\n" +
						"""<activity-alias android:name=".Alias" android:targetActivity="com.example.app.MainActivity" />""",
				).byteInputStream(),
			)

		val alias = result.document.getElementsByTagName("activity-alias").item(0) as Element
		assertThat(alias.getAttributeNS(QuickBuildManifestTransformer.ANDROID_NS, "targetActivity"))
			.isEqualTo("$proxyPackage.Proxy0Activity")
	}

	@Test
	fun `fails on a manifest without an application element`() {
		val xml =
			"""
			<?xml version="1.0" encoding="utf-8"?>
			<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="a.b" />
			""".trimIndent().trim()

		val error =
			assertThrows<IllegalArgumentException> {
				transformer().transform(xml.byteInputStream())
			}
		assertThat(error).hasMessageThat().contains("<application>")
	}

	@Test
	fun `fails on an activity without a name`() {
		val error =
			assertThrows<IllegalArgumentException> {
				transformer().transform(manifest("<activity />").byteInputStream())
			}
		assertThat(error).hasMessageThat().contains("android:name")
	}

	@Test
	fun `round-trips through writeTo`(
		@TempDir tempDir: File,
	) {
		val transformer = transformer()
		val result = transformer.transform(manifest(launcherActivity).byteInputStream())
		val out = File(tempDir, "AndroidManifest.xml")
		transformer.writeTo(result.document, out)

		val written = out.readText()
		assertThat(written).contains("$proxyPackage.Proxy0Activity")
		assertThat(written).doesNotContain(""""com.example.app.MainActivity"""")
		assertThat(written).contains(factory)
	}

	@Test
	fun `inlines the injected logsender bool so on-device relinks resolve`(
		@TempDir tempDir: File,
	) {
		// LogSenderPlugin injects android:enabled="@bool/logsender_enabled" (a LIBRARY
		// resource) into every debug manifest; the on-device relink links only the app's
		// own res/, so the reference must be inlined or every resource hot reload fails.
		val transformer = transformer()
		val result =
			transformer.transform(
				manifest(
					launcherActivity + "\n" +
						"""<service android:name="com.itsaky.androidide.logsender.LogSenderService"
						android:enabled="@bool/logsender_enabled" />""",
				).byteInputStream(),
			)
		val out = File(tempDir, "AndroidManifest.xml")
		transformer.writeTo(result.document, out)

		val written = out.readText()
		assertThat(written).doesNotContain("@bool/logsender_enabled")
		assertThat(written).contains("""android:enabled="true"""")
		// Ordinary app-local resource refs are untouched.
		assertThat(written).contains("@mipmap/ic_launcher")
	}
}
