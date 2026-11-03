package com.itsaky.androidide.plugins.extensions

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PluginTooltipButtonBinaryCompatTest {
	@Test
	fun pluginsBuiltBeforeDirectPathCanCallTheThreeArgumentConstructor() {
		val button =
			PluginTooltipButton::class.java
				.getConstructor(String::class.java, String::class.java, Int::class.javaPrimitiveType)
				.newInstance("Docs", "index.html", 2)

		assertThat(button).isEqualTo(PluginTooltipButton("Docs", "index.html", order = 2, directPath = false))
	}

	@Test
	fun pluginsBuiltBeforeDirectPathCanOmitTheOrder() {
		val defaultConstructorMarker = Class.forName("kotlin.jvm.internal.DefaultConstructorMarker")
		val orderIsDefaulted = 0b100

		val button =
			PluginTooltipButton::class.java
				.getConstructor(
					String::class.java,
					String::class.java,
					Int::class.javaPrimitiveType,
					Int::class.javaPrimitiveType,
					defaultConstructorMarker,
				).newInstance("Docs", "index.html", 7, orderIsDefaulted, null)

		assertThat(button).isEqualTo(PluginTooltipButton("Docs", "index.html", order = 0, directPath = false))
	}

	@Test
	fun kotlinCallersWithThreeArgumentsStillResolveToThePrimaryConstructor() {
		assertThat(PluginTooltipButton("Docs", "index.html", 3).directPath).isFalse()
	}
}
