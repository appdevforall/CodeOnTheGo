package com.itsaky.androidide.gradle

import com.android.build.api.variant.ApplicationVariant
import com.google.common.truth.Truth.assertThat
import org.gradle.api.GradleException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.reflect.Proxy

/**
 * JVM-pure coverage for [QuickBuildPlugin]'s AGP-internal seams; the full plugin runs only
 * inside a real Gradle build ([QuickBuildProxyAppBuildTest]).
 */
class QuickBuildPluginTest {
	/**
	 * An [ApplicationVariant] that is neither of the AGP variant impls the plugin knows -
	 * the shape a future AGP hands over when its internals change. A JDK dynamic proxy
	 * rather than a stub class, so no AGP-internal type is subclassed here either.
	 */
	private fun unknownVariantType(): ApplicationVariant =
		Proxy.newProxyInstance(
			ApplicationVariant::class.java.classLoader,
			arrayOf(ApplicationVariant::class.java),
		) { _, method, _ ->
			when (method.name) {
				"getName" -> "demoDebug"
				else -> throw UnsupportedOperationException(method.name)
			}
		} as ApplicationVariant

	@Test
	fun `requireRuntimeConfiguration fails the build loudly on an unrecognized variant type`() {
		// The injection path must never fail quiet: a proxy APK built without the runtime
		// AAR still names QuickBuildAppComponentFactory in its manifest, so it dies at
		// launch with ClassNotFoundException on device, far from the cause.
		val error =
			assertThrows<GradleException> {
				QuickBuildPlugin.requireRuntimeConfiguration(unknownVariantType())
			}

		assertThat(error).hasMessageThat().contains("demoDebug")
		assertThat(error).hasMessageThat().contains("runtime")
		assertThat(error).hasMessageThat().contains("Standard Run")
	}

	@Test
	fun `runtimeConfigurationOrNull degrades to null for the resources overlay path`() {
		// The .flat-overlay caller may stay graceful - missing overlays only degrade
		// resource relinks - which is exactly why the injection path above must not.
		assertThat(QuickBuildPlugin.runtimeConfigurationOrNull(unknownVariantType())).isNull()
	}

	@Test
	fun `the reflective quick build plugin name resolves to the real class`() {
		// AndroidIDEGradlePlugin applies QuickBuildPlugin by name so the minAgpCheck guard
		// can compile it without the Quick Build sources; this pins the string to the class.
		assertThat(Class.forName(AndroidIDEGradlePlugin.QUICK_BUILD_PLUGIN_CLASS))
			.isEqualTo(QuickBuildPlugin::class.java)
	}
}
