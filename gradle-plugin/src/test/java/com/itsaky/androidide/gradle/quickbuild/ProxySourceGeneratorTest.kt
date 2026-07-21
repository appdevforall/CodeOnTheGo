package com.itsaky.androidide.gradle.quickbuild

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ProxySourceGeneratorTest {
	@Test
	fun `generates a subclass in the proxy package`() {
		val source =
			ProxySourceGenerator.generateSource(
				proxyClass = "com.example.app.quickbuild.proxies.Proxy0Activity",
				userClass = "com.example.app.MainActivity",
			)

		assertThat(source).contains("package com.example.app.quickbuild.proxies;")
		assertThat(source)
			.contains("public class Proxy0Activity extends com.example.app.MainActivity {")
	}

	@Test
	fun `proxy observes touches for the app-switcher gesture but never consumes them`() {
		val source =
			ProxySourceGenerator.generateSource(
				proxyClass = "com.example.app.quickbuild.proxies.Proxy0Activity",
				userClass = "com.example.app.MainActivity",
			)

		assertThat(source)
			.contains("public boolean dispatchTouchEvent(android.view.MotionEvent ev)")
		assertThat(source).contains(
			"com.itsaky.androidide.quickbuild.runtime.QuickBuildGestures" +
				".onDispatchTouchEvent(this, ev);",
		)
		// The guard that keeps normal 1-2 finger input intact: ALWAYS delegate to super.
		assertThat(source).contains("return super.dispatchTouchEvent(ev);")
	}

	@Test
	fun `service proxy registers with the live-service census around super calls`() {
		val source =
			ProxySourceGenerator.generateSource(
				ProxiedComponent(
					ComponentType.SERVICE,
					"com.example.app.SyncService",
					"com.example.app.quickbuild.proxies.Proxy0Service",
				),
			)

		assertThat(source)
			.contains("public class Proxy0Service extends com.example.app.SyncService {")
		// onCreate: super FIRST, then register - the census only lists services that
		// actually finished their own onCreate.
		assertThat(source).contains(
			"super.onCreate();\n\t\t" +
				"com.itsaky.androidide.quickbuild.runtime.ServiceTracker.onServiceCreated(this);",
		)
		// onDestroy: unregister FIRST, then super - mirror order.
		assertThat(source).contains(
			"com.itsaky.androidide.quickbuild.runtime.ServiceTracker.onServiceDestroyed(this);\n\t\t" +
				"super.onDestroy();",
		)
		assertThat(source).doesNotContain("dispatchTouchEvent")
	}

	@Test
	fun `receiver and provider proxies are empty subclasses`() {
		listOf(
			ProxiedComponent(
				ComponentType.RECEIVER,
				"com.example.app.BootReceiver",
				"com.example.app.quickbuild.proxies.Proxy0Receiver",
			),
			ProxiedComponent(
				ComponentType.PROVIDER,
				"com.example.app.DataProvider",
				"com.example.app.quickbuild.proxies.Proxy0Provider",
			),
		).forEach { component ->
			val source = ProxySourceGenerator.generateSource(component)

			assertThat(source).contains(
				"public class ${component.proxyClass!!.substringAfterLast('.')} " +
					"extends ${component.userClass} {",
			)
			assertThat(source).doesNotContain("@Override")
		}
	}

	@Test
	fun `nested user class binary name becomes a canonical name in the extends clause`() {
		// A receiver declared as an inner class (e.g. WorkManager's
		// ConstraintProxy$BatteryChargingProxy) arrives as a binary name; javac resolves
		// only the canonical Outer.Inner form.
		val source =
			ProxySourceGenerator.generateSource(
				ProxiedComponent(
					ComponentType.RECEIVER,
					"com.example.app.Outer\$Inner",
					"com.example.app.quickbuild.proxies.Proxy0Receiver",
				),
			)

		assertThat(source).contains("extends com.example.app.Outer.Inner {")
		assertThat(source).doesNotContain("Outer\$Inner")
	}

	@Test
	fun `fails on a proxy class without a package`() {
		val error =
			assertThrows<IllegalArgumentException> {
				ProxySourceGenerator.generateSource("Proxy0Activity", "com.example.app.MainActivity")
			}
		assertThat(error).hasMessageThat().contains("no package")
	}

	@Test
	fun `fails on the application component - it has no proxy`() {
		val error =
			assertThrows<IllegalArgumentException> {
				ProxySourceGenerator.generateSource(
					ProxiedComponent(ComponentType.APPLICATION, "com.example.app.App", null),
				)
			}
		assertThat(error).hasMessageThat().contains("no proxy")
	}
}
