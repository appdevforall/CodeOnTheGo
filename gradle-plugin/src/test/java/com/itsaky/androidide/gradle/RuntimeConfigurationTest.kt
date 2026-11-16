package com.itsaky.androidide.gradle

import com.android.build.api.variant.ApplicationVariant
import com.google.common.truth.Truth.assertThat
import org.gradle.api.GradleException
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

/**
 * A variant type the helper does not know cannot be given the SQLite runtime dependencies, and a
 * build that went on would crash on the device (NoClassDefFoundError). A TestKit build cannot produce
 * such a variant, so this tests the decision function with a stand-in variant.
 */
class RuntimeConfigurationTest {
	private val unknownVariant =
		Proxy.newProxyInstance(
			javaClass.classLoader,
			arrayOf(ApplicationVariant::class.java),
		) { _, method, _ ->
			if (method.name == "getName") "stagingDebug" else null
		} as ApplicationVariant

	@Test
	fun `requireRuntimeConfiguration fails naming the variant and its class`() {
		var ran = false
		val failure = runCatching { unknownVariant.requireRuntimeConfiguration { ran = true } }.exceptionOrNull()

		assertThat(failure).isInstanceOf(GradleException::class.java)
		assertThat(failure!!.message).contains("variant 'stagingDebug'")
		assertThat(failure.message).contains(unknownVariant.javaClass.name)
		assertThat(ran).isFalse()
	}

	@Test
	fun `withRuntimeConfiguration still skips an unknown variant for LogSender`() {
		var ran = false
		unknownVariant.withRuntimeConfiguration { ran = true }

		assertThat(ran).isFalse()
		assertThat(unknownVariant.runtimeConfigurationOrNull()).isNull()
	}
}
