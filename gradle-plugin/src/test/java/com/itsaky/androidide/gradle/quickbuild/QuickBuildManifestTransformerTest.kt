package com.itsaky.androidide.gradle.quickbuild

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.w3c.dom.Element
import java.io.File

class QuickBuildManifestTransformerTest {
	private val proxyPackage = "com.example.app.quickbuild.proxies"
	private val factory = "com.itsaky.androidide.quickbuild.runtime.QuickBuildAppComponentFactory"
	private val proxyAppId = "com.example.app.quickbuild"

	private fun transformer() = QuickBuildManifestTransformer(proxyPackage, factory)

	/**
	 * A transformer whose dependency classpath reports exactly [finalClasses] as `final`
	 * library classes - the shape the real task builds from the variant's dependency
	 * artifacts. Everything else is "not found", i.e. assumed project-owned.
	 */
	private fun transformerSeeingFinal(vararg finalClasses: String): QuickBuildManifestTransformer {
		val byName =
			finalClasses.associateWith { name ->
				ClassWriter(0)
					.apply {
						visit(Opcodes.V11, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, name.replace('.', '/'), null, "java/lang/Object", null)
						visitEnd()
					}.toByteArray()
			}
		return QuickBuildManifestTransformer(
			proxyPackage,
			factory,
			proxiability = ComponentProxiabilityResolver { byName[it] },
		)
	}

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

	/**
	 * A merged manifest with no `package` attribute at all - the AGP 8 shape, where the
	 * namespace lives in the build file and never reaches the merged output.
	 */
	private fun manifestWithoutPackage(body: String) =
		"""
		<?xml version="1.0" encoding="utf-8"?>
		<manifest xmlns:android="http://schemas.android.com/apk/res/android">
			<application android:label="My App">
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
	fun `every renamed activity leaves an alias under its real name, so explicit in-app navigation resolves`() {
		// The 2048 shape: SplashActivity explicitly starts TutorialActivity by class. With
		// only the rename, that startActivity throws ActivityNotFoundException - the rename
		// removed the manifest's only entry for the real name.
		val result =
			transformer().transform(
				manifest(
					launcherActivity + "\n" +
						"""<activity android:name="com.example.app.TutorialActivity" />""",
				).byteInputStream(),
			)

		val ns = QuickBuildManifestTransformer.ANDROID_NS
		val aliases =
			result.document.getElementsByTagName("activity-alias").let { nodes ->
				(0 until nodes.length).map { nodes.item(it) as Element }
			}
		val byName = aliases.associateBy { it.getAttributeNS(ns, "name") }
		assertThat(byName.keys)
			.containsExactly("com.example.app.MainActivity", "com.example.app.TutorialActivity")
		assertThat(byName["com.example.app.MainActivity"]!!.getAttributeNS(ns, "targetActivity"))
			.isEqualTo("$proxyPackage.Proxy0Activity")
		assertThat(byName["com.example.app.TutorialActivity"]!!.getAttributeNS(ns, "targetActivity"))
			.isEqualTo("$proxyPackage.Proxy1Activity")
		// Never a wider surface than before the rename: the outside world could not reach the
		// real name then, so the alias must not export it now.
		aliases.forEach { assertThat(it.getAttributeNS(ns, "exported")).isEqualTo("false") }
		// An alias must FOLLOW its target's declaration, so they are appended after every
		// <activity> child of <application>.
		val application = result.document.getElementsByTagName("application").item(0) as Element
		val childTags =
			(0 until application.childNodes.length)
				.mapNotNull { (application.childNodes.item(it) as? Element)?.tagName }
		assertThat(childTags.lastIndexOf("activity")).isLessThan(childTags.indexOf("activity-alias"))
	}

	@Test
	fun `a skipped activity keeps its real name and gets no synthetic alias`() {
		val result =
			transformerSeeingFinal("lib.widget.FinalPreviewActivity").transform(
				manifest(
					launcherActivity + "\n" +
						"""<activity android:name="lib.widget.FinalPreviewActivity" />""",
				).byteInputStream(),
			)

		val ns = QuickBuildManifestTransformer.ANDROID_NS
		val aliasNames =
			result.document.getElementsByTagName("activity-alias").let { nodes ->
				(0 until nodes.length).map { (nodes.item(it) as Element).getAttributeNS(ns, "name") }
			}
		// The skipped activity still holds its real name as an <activity>; an alias with the
		// same name would collide with it at install time.
		assertThat(aliasNames).containsExactly("com.example.app.MainActivity")
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
		// The real class name survives only as the navigation alias, never as an <activity>.
		assertThat(written).doesNotContain("""<activity android:name="com.example.app.MainActivity"""")
		assertThat(written).contains("""android:targetActivity="$proxyPackage.Proxy0Activity"""")
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
	fun `keeps service attributes and children verbatim`() {
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

		val element = result.document.getElementsByTagName("provider").item(0) as Element
		val ns = QuickBuildManifestTransformer.ANDROID_NS
		assertThat(element.getAttributeNS(ns, "name")).isEqualTo("$proxyPackage.Proxy0Provider")
		// The transformer does not set the authorities attribute; the merged value stays.
		assertThat(element.getAttributeNS(ns, "authorities")).isEqualTo("com.example.app.data")
		assertThat(element.getAttributeNS(ns, "exported")).isEqualTo("false")
		assertThat(element.getAttributeNS(ns, "grantUriPermissions")).isEqualTo("true")
		assertThat(element.getAttributeNS(ns, "readPermission")).isEqualTo("com.example.app.READ")
		assertThat(element.getElementsByTagName("path-permission").length).isEqualTo(1)
	}

	@Test
	fun `passes a mix of app-id, third-party and prefix-sharing authorities verbatim, in order`() {
		// The proxy app installs under the project's REAL applicationId, so authorities are
		// already correct as merged and the transformer never sets the attribute. App-owned,
		// third-party, and merely-prefix-sharing authorities all pass through identically.
		val result =
			transformer().transform(
				manifest(
					"""
					<provider android:name="com.example.app.DataProvider"
						android:authorities="com.example.app;org.thirdparty.search;com.example.app.files;com.example.appstore.data" />
					""".trimIndent(),
				).byteInputStream(),
			)

		// The merged authorities attribute is left in place, untouched.
		val element = result.document.getElementsByTagName("provider").item(0) as Element
		assertThat(element.getAttributeNS(QuickBuildManifestTransformer.ANDROID_NS, "authorities"))
			.isEqualTo("com.example.app;org.thirdparty.search;com.example.app.files;com.example.appstore.data")
	}

	@Test
	fun `leaves androidx startup InitializationProvider under its real name, unproxied`() {
		// AppInitializer looks ITSELF up by this exact component name at runtime
		// (PackageManager#getProviderInfo); a renamed proxy breaks that self-lookup and
		// crash-loops the proxy app on launch.
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
		// final"), which broke every Compose template's proxy app build. Detected from the
		// dependency artifact's class bytes, not from a hardcoded name.
		val result =
			transformerSeeingFinal("androidx.compose.ui.tooling.PreviewActivity").transform(
				manifest(
					launcherActivity + "\n" +
						"""<activity android:name="androidx.compose.ui.tooling.PreviewActivity" android:exported="true" />""",
				).byteInputStream(),
			)

		assertThat(result.components.none { it.userClass == "androidx.compose.ui.tooling.PreviewActivity" })
			.isTrue()
		assertThat(result.unproxied.map { it.userClass })
			.containsExactly("androidx.compose.ui.tooling.PreviewActivity")
		// The real launcher activity still proxies normally, numbered from zero - the
		// excluded PreviewActivity must not consume a proxy-index slot.
		assertThat(result.activities.single().proxyClass).isEqualTo("$proxyPackage.Proxy0Activity")

		val elements = result.document.getElementsByTagName("activity")
		assertThat((elements.item(1) as Element).getAttributeNS(QuickBuildManifestTransformer.ANDROID_NS, "name"))
			.isEqualTo("androidx.compose.ui.tooling.PreviewActivity")
	}

	@Test
	fun `leaves androidx profileinstaller ProfileInstallReceiver under its real name, unproxied`() {
		// Not on every proxy app build's proxy-compile classpath (an AGP/transitively-injected
		// runtime-only dependency in some projects), so a generated Proxy<N>Receiver
		// extending it fails "cannot find symbol".
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
		// inherit from final"), which broke a real project's proxy app build (ADFA-4128).
		val result =
			transformerSeeingFinal("androidx.room.MultiInstanceInvalidationService").transform(
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
	fun `leaves the runtime's freezer keep-alive service under its real name, unproxied`() {
		// CoGo binds this by explicit component name to keep the proxy app out of Android's
		// cached-app freezer. Renamed to a proxy, the bind resolves to nothing, the app is
		// frozen ~1 min after it leaves the foreground, and every save then fails the deploy
		// timeout - the whole edit loop dies a minute in.
		val keepAlive = "com.itsaky.androidide.quickbuild.runtime.QuickBuildKeepAliveService"
		val result =
			transformer().transform(
				manifest(
					launcherActivity + "\n" +
						"""<service android:name="$keepAlive" android:exported="true" />""" + "\n" +
						"""<service android:name="com.example.app.SyncService" />""",
				).byteInputStream(),
			)

		assertThat(result.components.none { it.userClass == keepAlive }).isTrue()
		assertThat(result.unproxied.single().userClass).isEqualTo(keepAlive)
		// The project's own service still proxies, numbered from zero: the keep-alive must not
		// consume a proxy-index slot or every later service of the user's shifts.
		val services = result.components.filter { it.type == ComponentType.SERVICE }
		assertThat(services.single().userClass).isEqualTo("com.example.app.SyncService")
		assertThat(services.single().proxyClass).isEqualTo("$proxyPackage.Proxy0Service")
		assertThat(componentNames(result, "service"))
			.containsExactly(keepAlive, "$proxyPackage.Proxy0Service")
	}

	@Test
	fun `a never-before-seen final library component is skipped without any code change`() {
		// The point of the whole mechanism: a dependency nobody has met yet ships a final
		// component, and the user's Quick Build keeps working - no CoGo release, no name
		// added anywhere. Without the skip, the same manifest produces a proxy that fails the
		// proxy compile with "cannot inherit from final".
		val unknown = "com.thirdparty.analytics.TrackingService"

		val result =
			transformerSeeingFinal(unknown).transform(
				manifest(
					launcherActivity + "\n" +
						"""<service android:name="$unknown" />""" + "\n" +
						"""<service android:name="com.example.app.SyncService" />""",
				).byteInputStream(),
			)

		assertThat(result.components.none { it.userClass == unknown }).isTrue()
		assertThat(result.unproxied.single().userClass).isEqualTo(unknown)
		assertThat(result.unproxied.single().reason).contains("final")
		// The project's own service still proxies, and the skipped one took no index slot.
		val services = result.components.filter { it.type == ComponentType.SERVICE }
		assertThat(services.single().userClass).isEqualTo("com.example.app.SyncService")
		assertThat(services.single().proxyClass).isEqualTo("$proxyPackage.Proxy0Service")
		assertThat(componentNames(result, "service"))
			.containsExactly(unknown, "$proxyPackage.Proxy0Service")
			.inOrder()
	}

	@Test
	fun `a non-final library component is proxied like any other`() {
		// The complement of the test above: the resolver finds the class and it is ordinary,
		// so nothing changes. Guards against a skip rule that fires on "found" rather than
		// "found and final".
		val libraryService = "com.thirdparty.sync.OrdinaryService"

		val result =
			transformerSeeingFinal("some.other.FinalThing").transform(
				manifest(launcherActivity + "\n" + """<service android:name="$libraryService" />""").byteInputStream(),
			)

		assertThat(result.unproxied).isEmpty()
		assertThat(result.components.single { it.type == ComponentType.SERVICE }.proxyClass)
			.isEqualTo("$proxyPackage.Proxy0Service")
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

		// Shorthand must not survive: the proxy app APK installs under the suffixed
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
	fun `fails loud on android process declared on the application itself`() {
		// The per-component check cannot catch this: android:process on <application> is the
		// default for components that do not name one, so every component element is clean
		// while the whole app runs off the default process.
		val error =
			assertThrows<IllegalArgumentException> {
				transformer().transform(
					manifest(
						launcherActivity,
						applicationAttrs = """android:process=":remote"""",
					).byteInputStream(),
				)
			}

		assertThat(error).hasMessageThat().contains("<application>")
		assertThat(error).hasMessageThat().contains("android:process")
		assertThat(error).hasMessageThat().contains("Standard Run")
	}

	@Test
	fun `an application with no android process is untouched by the check`() {
		val result = transformer().transform(manifest(launcherActivity).byteInputStream())

		assertThat(result.activities).hasSize(1)
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

	@Test
	fun `proxySimpleName numbers each type independently and capitalizes its suffix`() {
		// The manifest, the generated sources and the report all derive names here, so this
		// pins the scheme against drift in any one of them.
		assertThat(QuickBuildManifestTransformer.proxySimpleName(0, ComponentType.ACTIVITY))
			.isEqualTo("Proxy0Activity")
		assertThat(QuickBuildManifestTransformer.proxySimpleName(3, ComponentType.SERVICE))
			.isEqualTo("Proxy3Service")
		assertThat(QuickBuildManifestTransformer.proxySimpleName(1, ComponentType.RECEIVER))
			.isEqualTo("Proxy1Receiver")
		assertThat(QuickBuildManifestTransformer.proxySimpleName(2, ComponentType.PROVIDER))
			.isEqualTo("Proxy2Provider")
	}

	@Test
	fun `proxySimpleName rejects the application - it is the one type with no proxy`() {
		val error =
			assertThrows<IllegalArgumentException> {
				QuickBuildManifestTransformer.proxySimpleName(0, ComponentType.APPLICATION)
			}
		assertThat(error).hasMessageThat().contains("the Application gets no proxy")
	}

	@Test
	fun `leaves a bare component name alone when the manifest declares no package`() {
		// Nothing to expand a shorthand against, so the name has to pass through verbatim
		// rather than become ".MainActivity" - the recorded userClass is what the runtime
		// looks the class up by in the payload dex.
		val result =
			transformer().transform(
				manifestWithoutPackage("""<activity android:name="MainActivity" />""").byteInputStream(),
			)

		assertThat(result.activities.single().userClass).isEqualTo("MainActivity")
	}

	@Test
	fun `an activity-alias targeting a skipped activity keeps pointing at the real class`() {
		// An alias only follows its target when the target actually became a proxy. A skipped
		// component still stands under its real name, so repointing the alias would leave it
		// referencing a component the manifest never declares.
		val finalActivity = "com.thirdparty.ui.FinalActivity"

		val result =
			transformerSeeingFinal(finalActivity).transform(
				manifest(
					launcherActivity + "\n" +
						"""<activity android:name="$finalActivity" />""" + "\n" +
						"""<activity-alias android:name=".Alias" android:targetActivity="$finalActivity" />""",
				).byteInputStream(),
			)

		assertThat(result.unproxied.single().userClass).isEqualTo(finalActivity)
		val alias = result.document.getElementsByTagName("activity-alias").item(0) as Element
		assertThat(alias.getAttributeNS(QuickBuildManifestTransformer.ANDROID_NS, "targetActivity"))
			.isEqualTo(finalActivity)
	}

	@Test
	fun `leaves an activity-alias with no targetActivity untouched`() {
		val result =
			transformer().transform(
				manifest(
					launcherActivity + "\n" +
						"""<activity-alias android:name=".Alias" android:label="Alias" />""",
				).byteInputStream(),
			)

		val alias = result.document.getElementsByTagName("activity-alias").item(0) as Element
		val ns = QuickBuildManifestTransformer.ANDROID_NS
		assertThat(alias.hasAttributeNS(ns, "targetActivity")).isFalse()
		assertThat(alias.getAttributeNS(ns, "label")).isEqualTo("Alias")
		assertThat(result.activities.single().proxyClass).isEqualTo("$proxyPackage.Proxy0Activity")
	}

	@Test
	fun `MAIN and LAUNCHER split across two intent filters is not a launcher`() {
		// The framework only launches an activity that carries both in the SAME filter.
		// Matching them across filters would name a non-launchable activity as the entry
		// point, and every restart relaunch would then start the wrong screen.
		val result =
			transformer().transform(
				manifest(
					"""
					<activity android:name="com.example.app.SplitActivity" android:exported="true">
						<intent-filter>
							<action android:name="android.intent.action.MAIN" />
							<category android:name="android.intent.category.DEFAULT" />
						</intent-filter>
						<intent-filter>
							<action android:name="android.intent.action.VIEW" />
							<category android:name="android.intent.category.LAUNCHER" />
						</intent-filter>
					</activity>
					""".trimIndent(),
				).byteInputStream(),
			)

		assertThat(result.activities.single().isLauncher).isFalse()
		assertThat(result.entryActivity).isNull()
	}

	@Test
	fun `writeTo accepts a destination that has no parent directory`() {
		// A bare relative path has a null parentFile, so the directory-creating step must not
		// be what decides whether the manifest gets written at all. The premise REQUIRES writing
		// into the module's working directory rather than a @TempDir - a path under a temp
		// directory has a parent - so the cost is bought with a unique file name and an
		// unconditional delete in the finally below.
		val transformer = transformer()
		val result = transformer.transform(manifest(launcherActivity).byteInputStream())
		val out = File("quickbuild-manifest-no-parent.xml")
		assertThat(out.parent).isNull()

		try {
			transformer.writeTo(result.document, out)

			assertThat(out.readText()).contains("$proxyPackage.Proxy0Activity")
		} finally {
			out.delete()
		}
	}

	@Test
	fun `a result built without an unproxied list reports none, and still derives its views`() {
		val document = transformer().transform(manifest(launcherActivity).byteInputStream()).document

		val result =
			ManifestTransformResult(
				document,
				listOf(
					ProxiedComponent(
						ComponentType.SERVICE,
						"com.example.app.SyncService",
						"$proxyPackage.Proxy0Service",
					),
					ProxiedComponent(
						ComponentType.ACTIVITY,
						"com.example.app.OtherActivity",
						"$proxyPackage.Proxy0Activity",
					),
					ProxiedComponent(
						ComponentType.ACTIVITY,
						"com.example.app.MainActivity",
						"$proxyPackage.Proxy1Activity",
						isLauncher = true,
					),
				),
			)

		assertThat(result.unproxied).isEmpty()
		assertThat(result.activities.map { it.userClass })
			.containsExactly("com.example.app.OtherActivity", "com.example.app.MainActivity")
			.inOrder()
		assertThat(result.entryActivity).isEqualTo("com.example.app.MainActivity")
	}

	/**
	 * The benchmark corpus' `service-app`, component-for-component: the only corpus app whose edits
	 * take the restart route. Its services are Kotlin (so final in their own bytes) but
	 * project-owned, so they are absent from the dependency classpath the real task searches - and
	 * absence must read as proxiable. A service that lost its proxy would silently drop off the
	 * restart closure.
	 */
	@Test
	fun `project-owned services stay proxied when the dependency classpath does not hold them`() {
		val serviceApp =
			manifest(
				packageName = "org.appdevforall.cotg.corpus.serviceapp",
				body =
					"""
					<activity android:name=".MainActivity" android:exported="true">
						<intent-filter>
							<action android:name="android.intent.action.MAIN" />
							<category android:name="android.intent.category.LAUNCHER" />
						</intent-filter>
					</activity>
					<service android:name=".CounterService" android:exported="false"
						android:foregroundServiceType="dataSync" />
					<service android:name=".TickBinderService" android:exported="false" />
					""".trimIndent(),
			)

		// The real task's resolver shape: a classpath of dependency artifacts, none of which
		// carries a project class, so every lookup misses.
		val result =
			QuickBuildManifestTransformer(
				proxyPackage,
				factory,
				proxiability = ComponentProxiabilityResolver { null },
			).transform(serviceApp.byteInputStream())

		assertThat(result.unproxied).isEmpty()
		val services = result.components.filter { it.type == ComponentType.SERVICE }
		assertThat(services.map { it.userClass })
			.containsExactly(
				"org.appdevforall.cotg.corpus.serviceapp.CounterService",
				"org.appdevforall.cotg.corpus.serviceapp.TickBinderService",
			).inOrder()
		assertThat(services.map { it.proxyClass })
			.containsExactly("$proxyPackage.Proxy0Service", "$proxyPackage.Proxy1Service")
			.inOrder()
		assertThat(result.entryActivity).isEqualTo("org.appdevforall.cotg.corpus.serviceapp.MainActivity")
	}
}
