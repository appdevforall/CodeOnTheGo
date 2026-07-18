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
	fun `fails on a proxy class without a package`() {
		val error =
			assertThrows<IllegalArgumentException> {
				ProxySourceGenerator.generateSource("Proxy0Activity", "com.example.app.MainActivity")
			}
		assertThat(error).hasMessageThat().contains("no package")
	}
}
