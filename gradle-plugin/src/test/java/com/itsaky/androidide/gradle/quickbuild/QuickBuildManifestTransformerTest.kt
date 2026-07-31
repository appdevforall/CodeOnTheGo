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
	private val proxyAppId = "com.example.app.quickbuild"

	private fun transformer() = QuickBuildManifestTransformer(proxyPackage, factory)

	private fun manifest(
		body: String,
		packageName: String = "com.example.app.quickbuild",
		applicationAttrs: String = "",
	) = """
		<?xml version="1.0" encoding="utf-8"?>
		<manifest xmlns:android="http://schemas.android.com/apk/res/android"
			package="$packageName">
			<uses-permission android:name="android.permission.INTERNET" />
			<application
				android:icon="@mipmap/ic_launcher"
				android:label="My App"
				$applicationAttrs>
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

	private fun componentNames(
		result: ManifestTransformResult,
		tag: String,
	): List<String> =
		result.document.getElementsByTagName(tag).let { nodes ->
			(0 until nodes.length).map {
				(nodes.item(it) as Element)
					.getAttributeNS(QuickBuildManifestTransformer.ANDROID_NS, "name")
			}
		}

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
				ProxiedComponent(
					ComponentType.ACTIVITY,
					"com.example.app.MainActivity",
					"$proxyPackage.Proxy0Activity",
					isLauncher = true,
				),
				ProxiedComponent(
					ComponentType.ACTIVITY,
					"com.example.app.SettingsActivity",
					"$proxyPackage.Proxy1Activity",
					isLauncher = false,
				),
			).inOrder()

		assertThat(componentNames(result, "activity"))
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
	fun `a MAIN LAUNCHER on an activity-alias leaves no launcher activity - relaunch uses the package intent`() {
		// Icon-switching apps put MAIN/LAUNCHER on an <activity-alias> whose target has no
		// filter (and is typically not exported), so no <activity> is a launcher. The
		// restart relaunch must fall back to the package launch intent (executor path),
		// NOT an explicit start of a possibly-unexported target - so entryActivity is null.
		val result =
			transformer().transform(
				manifest(
					"""<activity android:name="com.example.app.MainActivity" />""" + "\n" +
						"""
						<activity-alias android:name=".Launcher" android:targetActivity="com.example.app.MainActivity" android:exported="true">
							<intent-filter>
								<action android:name="android.intent.action.MAIN" />
								<category android:name="android.intent.category.LAUNCHER" />
							</intent-filter>
						</activity-alias>
						""".trimIndent(),
				).byteInputStream(),
			)

		assertThat(result.entryActivity).isNull()
		// The alias still follows its target to the proxy.
		val alias = result.document.getElementsByTagName("activity-alias").item(0) as Element
		assertThat(alias.getAttributeNS(QuickBuildManifestTransformer.ANDROID_NS, "targetActivity"))
			.isEqualTo("$proxyPackage.Proxy0Activity")
	}

	@Test
	fun `neutralizes auto-backup - forces allowBackup false and drops the backup hooks`() {
		val result =
			transformer().transform(
				manifest(
					launcherActivity,
					applicationAttrs =
						"""android:allowBackup="true" android:backupAgent=".MyBackupAgent" """ +
							"""android:fullBackupContent="@xml/backup_rules" android:dataExtractionRules="@xml/extraction" """,
				).byteInputStream(),
			)

		val application = result.document.getElementsByTagName("application").item(0) as Element
		val ns = QuickBuildManifestTransformer.ANDROID_NS
		assertThat(application.getAttributeNS(ns, "allowBackup")).isEqualTo("false")
		// backupAgent would point at a payload-dex-only class; the others are backup config
		// that only makes sense with backup enabled.
		assertThat(application.hasAttributeNS(ns, "backupAgent")).isFalse()
		assertThat(application.hasAttributeNS(ns, "fullBackupContent")).isFalse()
		assertThat(application.hasAttributeNS(ns, "dataExtractionRules")).isFalse()
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
		// The injected (library) service is proxied like any other - uniform rule.
		assertThat(result.components.single { it.type == ComponentType.SERVICE }.userClass)
			.isEqualTo("com.itsaky.androidide.logsender.LogSenderService")
	}

	@Test
	fun `rewrites service names to per-type proxies in manifest order`() {
		val result =
			transformer().transform(
				manifest(
					"""
					<service android:name="com.example.app.SyncService" />
					<service android:name="com.example.app.MusicService" />
					""".trimIndent(),
				).byteInputStream(),
			)

		val services = result.components.filter { it.type == ComponentType.SERVICE }
		assertThat(services)
			.containsExactly(
				ProxiedComponent(
					ComponentType.SERVICE,
					"com.example.app.SyncService",
					"$proxyPackage.Proxy0Service",
				),
				ProxiedComponent(
					ComponentType.SERVICE,
					"com.example.app.MusicService",
					"$proxyPackage.Proxy1Service",
				),
			).inOrder()
		assertThat(componentNames(result, "service"))
			.containsExactly("$proxyPackage.Proxy0Service", "$proxyPackage.Proxy1Service")
			.inOrder()
	}

	@Test
	fun `keeps service attributes and children verbatim, recording the fgs type`() {
		val result =
			transformer().transform(
				manifest(
					"""
					<service android:name="com.example.app.SyncService"
						android:exported="false"
						android:permission="com.example.app.BIND"
						android:directBootAware="true"
						android:foregroundServiceType="dataSync">
						<intent-filter>
							<action android:name="com.example.app.SYNC" />
						</intent-filter>
						<meta-data android:name="sync.key" android:value="v" />
					</service>
					""".trimIndent(),
				).byteInputStream(),
			)

		val service = result.document.getElementsByTagName("service").item(0) as Element
		val ns = QuickBuildManifestTransformer.ANDROID_NS
		assertThat(service.getAttributeNS(ns, "exported")).isEqualTo("false")
		assertThat(service.getAttributeNS(ns, "permission")).isEqualTo("com.example.app.BIND")
		assertThat(service.getAttributeNS(ns, "directBootAware")).isEqualTo("true")
		assertThat(service.getAttributeNS(ns, "foregroundServiceType")).isEqualTo("dataSync")
		assertThat(service.getElementsByTagName("intent-filter").length).isEqualTo(1)
		assertThat(service.getElementsByTagName("meta-data").length).isEqualTo(1)

		assertThat(result.components.single { it.type == ComponentType.SERVICE }.foregroundServiceType)
			.isEqualTo("dataSync")
	}

	@Test
	fun `rewrites receiver names and keeps their filters and permission`() {
		val result =
			transformer().transform(
				manifest(
					"""
					<receiver android:name=".BootReceiver" android:exported="true"
						android:permission="android.permission.RECEIVE_BOOT_COMPLETED">
						<intent-filter>
							<action android:name="android.intent.action.BOOT_COMPLETED" />
						</intent-filter>
					</receiver>
					""".trimIndent(),
				).byteInputStream(),
			)

		val receiver = result.components.single { it.type == ComponentType.RECEIVER }
		assertThat(receiver.userClass).isEqualTo("com.example.app.quickbuild.BootReceiver")
		assertThat(receiver.proxyClass).isEqualTo("$proxyPackage.Proxy0Receiver")

		val element = result.document.getElementsByTagName("receiver").item(0) as Element
		val ns = QuickBuildManifestTransformer.ANDROID_NS
		assertThat(element.getAttributeNS(ns, "name")).isEqualTo("$proxyPackage.Proxy0Receiver")
		assertThat(element.getAttributeNS(ns, "exported")).isEqualTo("true")
		assertThat(element.getAttributeNS(ns, "permission"))
			.isEqualTo("android.permission.RECEIVE_BOOT_COMPLETED")
		assertThat(element.getElementsByTagName("intent-filter").length).isEqualTo(1)
	}

	@Test
	fun `rewrites provider name to the proxy and passes authorities plus permissions verbatim`() {
		val result =
			transformer().transform(
				manifest(
					"""
					<provider android:name="com.example.app.DataProvider"
						android:authorities="com.example.app.data"
						android:exported="false"
						android:grantUriPermissions="true"
						android:readPermission="com.example.app.READ">
						<path-permission android:path="/private" android:permission="com.example.app.P" />
					</provider>
					""".trimIndent(),
				).byteInputStream(),
			)

		val provider = result.components.single { it.type == ComponentType.PROVIDER }
		assertThat(provider.proxyClass).isEqualTo("$proxyPackage.Proxy0Provider")
		assertThat(provider.authorities).containsExactly("com.example.app.data")

		val element = result.document.getElementsByTagName("provider").item(0) as Element
		val ns = QuickBuildManifestTransformer.ANDROID_NS
		assertThat(element.getAttributeNS(ns, "name")).isEqualTo("$proxyPackage.Proxy0Provider")
		// The transformer no longer sets the authorities attribute; the merged value stays.
		assertThat(element.getAttributeNS(ns, "authorities")).isEqualTo("com.example.app.data")
		assertThat(element.getAttributeNS(ns, "exported")).isEqualTo("false")
		assertThat(element.getAttributeNS(ns, "grantUriPermissions")).isEqualTo("true")
		assertThat(element.getAttributeNS(ns, "readPermission")).isEqualTo("com.example.app.READ")
		assertThat(element.getElementsByTagName("path-permission").length).isEqualTo(1)
	}

	@Test
	fun `passes a mix of app-id, third-party and prefix-sharing authorities verbatim, in order`() {
		// The proxy app installs under the project's REAL applicationId, so authorities are
		// already correct as merged - the transformer records them unchanged and never sets
		// the authorities attribute. App-owned, third-party, and merely-prefix-sharing
		// authorities all pass through identically.
		val result =
			transformer().transform(
				manifest(
					"""
					<provider android:name="com.example.app.DataProvider"
						android:authorities="com.example.app;org.thirdparty.search;com.example.app.files;com.example.appstore.data" />
					""".trimIndent(),
				).byteInputStream(),
			)

		assertThat(result.components.single { it.type == ComponentType.PROVIDER }.authorities)
			.containsExactly(
				"com.example.app",
				"org.thirdparty.search",
				"com.example.app.files",
				"com.example.appstore.data",
			).inOrder()
		// The merged authorities attribute is left in place, untouched.
		val element = result.document.getElementsByTagName("provider").item(0) as Element
		assertThat(element.getAttributeNS(QuickBuildManifestTransformer.ANDROID_NS, "authorities"))
			.isEqualTo("com.example.app;org.thirdparty.search;com.example.app.files;com.example.appstore.data")
	}

	@Test
	fun `leaves androidx startup InitializationProvider under its real name, unproxied`() {
		// AppInitializer looks ITSELF up by this exact component name at runtime
		// (PackageManager#getProviderInfo); a renamed proxy breaks that self-lookup and
		// crash-loops the proxy app on launch (ADFA-4128 Bug 4).
		val result =
			transformer().transform(
				manifest(
					launcherActivity + "\n" +
						"""
						<provider android:name="androidx.startup.InitializationProvider"
							android:authorities="$proxyAppId.androidx-startup"
							android:exported="false">
							<meta-data android:name="androidx.lifecycle.ProcessLifecycleInitializer"
								android:value="androidx.startup" />
						</provider>
						""".trimIndent(),
				).byteInputStream(),
			)

		assertThat(result.components.none { it.userClass == "androidx.startup.InitializationProvider" })
			.isTrue()

		val element = result.document.getElementsByTagName("provider").item(0) as Element
		val ns = QuickBuildManifestTransformer.ANDROID_NS
		assertThat(element.getAttributeNS(ns, "name")).isEqualTo("androidx.startup.InitializationProvider")
		assertThat(element.getElementsByTagName("meta-data").length).isEqualTo(1)
	}

	@Test
	fun `a normal provider alongside InitializationProvider still proxies, numbered from zero`() {
		// InitializationProvider must not consume a proxy index - the real provider's
		// proxy name is Proxy0Provider, not Proxy1Provider.
		val result =
			transformer().transform(
				manifest(
					launcherActivity + "\n" +
						"""
						<provider android:name="androidx.startup.InitializationProvider"
							android:authorities="$proxyAppId.androidx-startup" />
						<provider android:name="com.example.app.DataProvider"
							android:authorities="com.example.app.data" />
						""".trimIndent(),
				).byteInputStream(),
			)

		val providers = result.components.filter { it.type == ComponentType.PROVIDER }
		assertThat(providers).hasSize(1)
		assertThat(providers.single().userClass).isEqualTo("com.example.app.DataProvider")
		assertThat(providers.single().proxyClass).isEqualTo("$proxyPackage.Proxy0Provider")

		val elements = result.document.getElementsByTagName("provider")
		assertThat((elements.item(0) as Element).getAttributeNS(QuickBuildManifestTransformer.ANDROID_NS, "name"))
			.isEqualTo("androidx.startup.InitializationProvider")
		assertThat((elements.item(1) as Element).getAttributeNS(QuickBuildManifestTransformer.ANDROID_NS, "name"))
			.isEqualTo("$proxyPackage.Proxy0Provider")
	}

	@Test
	fun `leaves a final Compose PreviewActivity under its real name, unproxied`() {
		// androidx.compose.ui.tooling.PreviewActivity is final - a generated
		// `Proxy<N>Activity extends` it can't even compile ("cannot inherit from
		// final"), which broke every Compose template's setup build (ADFA-4128 Bug 7).
		val result =
			transformer().transform(
				manifest(
					launcherActivity + "\n" +
						"""<activity android:name="androidx.compose.ui.tooling.PreviewActivity" android:exported="true" />""",
				).byteInputStream(),
			)

		assertThat(result.components.none { it.userClass == "androidx.compose.ui.tooling.PreviewActivity" })
			.isTrue()
		// The real launcher activity still proxies normally, numbered from zero - the
		// excluded PreviewActivity must not consume a proxy-index slot.
		assertThat(result.activities.single().proxyClass).isEqualTo("$proxyPackage.Proxy0Activity")

		val elements = result.document.getElementsByTagName("activity")
		assertThat((elements.item(1) as Element).getAttributeNS(QuickBuildManifestTransformer.ANDROID_NS, "name"))
			.isEqualTo("androidx.compose.ui.tooling.PreviewActivity")
	}

	@Test
	fun `leaves androidx profileinstaller ProfileInstallReceiver under its real name, unproxied`() {
		// Not on every setup build's proxy-compile classpath (an AGP/transitively-injected
		// runtime-only dependency in some projects), so a generated Proxy<N>Receiver
		// extending it fails "cannot find symbol" (ADFA-4128 Bug 7).
		val result =
			transformer().transform(
				manifest(
					launcherActivity + "\n" +
						"""
						<receiver android:name="androidx.profileinstaller.ProfileInstallReceiver"
							android:exported="true" android:permission="android.permission.DUMP">
							<intent-filter>
								<action android:name="androidx.profileinstaller.action.INSTALL_PROFILE" />
							</intent-filter>
						</receiver>
						""".trimIndent(),
				).byteInputStream(),
			)

		assertThat(
			result.components.none { it.userClass == "androidx.profileinstaller.ProfileInstallReceiver" },
		).isTrue()

		val element = result.document.getElementsByTagName("receiver").item(0) as Element
		val ns = QuickBuildManifestTransformer.ANDROID_NS
		assertThat(element.getAttributeNS(ns, "name")).isEqualTo("androidx.profileinstaller.ProfileInstallReceiver")
		assertThat(element.getElementsByTagName("intent-filter").length).isEqualTo(1)
	}

	@Test
	fun `leaves Room's final MultiInstanceInvalidationService under its real name, unproxied`() {
		// final - a generated `Proxy<N>Service extends` it can't even compile ("cannot
		// inherit from final"), which broke a real project's setup build (ADFA-4128,
		// generalizing Bug 7's Compose PreviewActivity fix to any final library class -
		// see QuickBuildManifestTransformer.UNPROXIABLE_LIBRARY_COMPONENTS' KDoc for why
		// this is a named exception rather than an auto-detected one).
		val result =
			transformer().transform(
				manifest(
					launcherActivity + "\n" +
						"""<service android:name="androidx.room.MultiInstanceInvalidationService" />""",
				).byteInputStream(),
			)

		assertThat(result.components.none { it.userClass == "androidx.room.MultiInstanceInvalidationService" })
			.isTrue()
		// The real launcher activity still proxies normally, numbered from zero - the
		// excluded service must not consume a proxy-index slot.
		assertThat(result.activities.single().proxyClass).isEqualTo("$proxyPackage.Proxy0Activity")

		val element = result.document.getElementsByTagName("service").item(0) as Element
		assertThat(element.getAttributeNS(QuickBuildManifestTransformer.ANDROID_NS, "name"))
			.isEqualTo("androidx.room.MultiInstanceInvalidationService")
	}

	@Test
	fun `a normal receiver alongside ProfileInstallReceiver still proxies, numbered from zero`() {
		val result =
			transformer().transform(
				manifest(
					launcherActivity + "\n" +
						"""
						<receiver android:name="androidx.profileinstaller.ProfileInstallReceiver" />
						<receiver android:name="com.example.app.BootReceiver" />
						""".trimIndent(),
				).byteInputStream(),
			)

		val receivers = result.components.filter { it.type == ComponentType.RECEIVER }
		assertThat(receivers).hasSize(1)
		assertThat(receivers.single().userClass).isEqualTo("com.example.app.BootReceiver")
		assertThat(receivers.single().proxyClass).isEqualTo("$proxyPackage.Proxy0Receiver")
	}

	@Test
	fun `records the custom application class without proxying it`() {
		val result =
			transformer().transform(
				manifest(
					launcherActivity,
					applicationAttrs = """android:name="com.example.app.App"""",
				).byteInputStream(),
			)

		val app = result.components.single { it.type == ComponentType.APPLICATION }
		assertThat(app.userClass).isEqualTo("com.example.app.App")
		assertThat(app.proxyClass).isNull()

		// The manifest keeps the USER class: instantiateApplication routes it through the
		// payload loader, and nothing addresses the Application by manifest name.
		val application = result.document.getElementsByTagName("application").item(0) as Element
		assertThat(application.getAttributeNS(QuickBuildManifestTransformer.ANDROID_NS, "name"))
			.isEqualTo("com.example.app.App")
	}

	@Test
	fun `fully qualifies a shorthand application name in the manifest`() {
		val result =
			transformer().transform(
				manifest(
					launcherActivity,
					packageName = "com.example.app",
					applicationAttrs = """android:name=".App"""",
				).byteInputStream(),
			)

		// Shorthand must not survive: the test APK installs under the suffixed
		// .quickbuild id, so a relative name would re-resolve against the wrong package
		// at runtime. Manifest and recorded component must agree on the FQN.
		val application = result.document.getElementsByTagName("application").item(0) as Element
		assertThat(application.getAttributeNS(QuickBuildManifestTransformer.ANDROID_NS, "name"))
			.isEqualTo("com.example.app.App")
		assertThat(result.components.single { it.type == ComponentType.APPLICATION }.userClass)
			.isEqualTo("com.example.app.App")
	}

	@Test
	fun `fully qualifies a bare application name in the manifest`() {
		val result =
			transformer().transform(
				manifest(
					launcherActivity,
					packageName = "com.example.app",
					applicationAttrs = """android:name="App"""",
				).byteInputStream(),
			)

		val application = result.document.getElementsByTagName("application").item(0) as Element
		assertThat(application.getAttributeNS(QuickBuildManifestTransformer.ANDROID_NS, "name"))
			.isEqualTo("com.example.app.App")
		assertThat(result.components.single { it.type == ComponentType.APPLICATION }.userClass)
			.isEqualTo("com.example.app.App")
	}

	@Test
	fun `replaces a library-injected appComponentFactory with the quick build factory`() {
		// androidx-core merges android:appComponentFactory="androidx.core.app.CoreComponentFactory"
		// into every app manifest; if it survived, no component would route through the
		// payload loader and the custom Application carry-through would silently break.
		val result =
			transformer().transform(
				manifest(
					launcherActivity,
					applicationAttrs =
						"""android:name="com.example.app.App" """ +
							"""android:appComponentFactory="androidx.core.app.CoreComponentFactory"""",
				).byteInputStream(),
			)

		val application = result.document.getElementsByTagName("application").item(0) as Element
		assertThat(
			application.getAttributeNS(QuickBuildManifestTransformer.ANDROID_NS, "appComponentFactory"),
		).isEqualTo(factory)
		// The user Application still rides along un-proxied.
		assertThat(application.getAttributeNS(QuickBuildManifestTransformer.ANDROID_NS, "name"))
			.isEqualTo("com.example.app.App")
	}

	@Test
	fun `emits no application component when the application has no name`() {
		val result = transformer().transform(manifest(launcherActivity).byteInputStream())

		assertThat(result.components.filter { it.type == ComponentType.APPLICATION }).isEmpty()
	}

	@Test
	fun `lists all component types together`() {
		val result =
			transformer().transform(
				manifest(
					launcherActivity + "\n" +
						"""
						<service android:name=".SyncService" />
						<receiver android:name=".BootReceiver" />
						<provider android:name=".DataProvider" android:authorities="com.example.app.data" />
						""".trimIndent(),
					applicationAttrs = """android:name=".App"""",
				).byteInputStream(),
			)

		assertThat(result.components.map { it.type })
			.containsExactly(
				ComponentType.ACTIVITY,
				ComponentType.SERVICE,
				ComponentType.RECEIVER,
				ComponentType.PROVIDER,
				ComponentType.APPLICATION,
			).inOrder()
		// The activities view still only sees activities.
		assertThat(result.activities.map { it.userClass })
			.containsExactly("com.example.app.MainActivity")
	}

	@Test
	fun `fails on a service without a name`() {
		val error =
			assertThrows<IllegalArgumentException> {
				transformer().transform(manifest("<service />").byteInputStream())
			}
		assertThat(error).hasMessageThat().contains("<service>")
	}

	@Test
	fun `fails loud on android process for every component type`() {
		listOf(
			"""<activity android:name=".A" android:process=":ui" />""",
			"""<service android:name=".S" android:process=":bg" />""",
			"""<receiver android:name=".R" android:process=":r" />""",
			"""<provider android:name=".P" android:authorities="a" android:process=":p" />""",
		).forEach { component ->
			val error =
				assertThrows<IllegalArgumentException> {
					transformer().transform(manifest(component).byteInputStream())
				}
			assertThat(error).hasMessageThat().contains("android:process")
			assertThat(error).hasMessageThat().contains("Standard Run")
		}
	}

	@Test
	fun `fails loud on an isolated-process service, naming the component`() {
		val error =
			assertThrows<IllegalArgumentException> {
				transformer().transform(
					manifest(
						"""<service android:name="com.example.app.Scan" android:isolatedProcess="true" />""",
					).byteInputStream(),
				)
			}
		assertThat(error).hasMessageThat().contains("com.example.app.Scan")
		assertThat(error).hasMessageThat().contains("isolatedProcess")
	}

	@Test
	fun `accepts isolatedProcess=false and multiprocess=false`() {
		val result =
			transformer().transform(
				manifest(
					"""
					<service android:name=".S" android:isolatedProcess="false" />
					<provider android:name=".P" android:authorities="a" android:multiprocess="false" />
					""".trimIndent(),
				).byteInputStream(),
			)

		assertThat(result.components.filter { it.proxyClass != null }).hasSize(2)
	}

	@Test
	fun `fails loud on a multiprocess provider, naming the component`() {
		val error =
			assertThrows<IllegalArgumentException> {
				transformer().transform(
					manifest(
						"""<provider android:name="com.example.app.P" android:authorities="a"
							android:multiprocess="true" />""",
					).byteInputStream(),
				)
			}
		assertThat(error).hasMessageThat().contains("com.example.app.P")
		assertThat(error).hasMessageThat().contains("multiprocess")
	}
}
