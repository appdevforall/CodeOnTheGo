package com.itsaky.androidide.plugins.manager.project

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class IsTemplateOfTest {
	@Test
	fun aTemplateOfThePluginMatches() {
		assertThat(isTemplateOf("com.example.my", "plugin_com.example.my_starter.cgt", emptyList())).isTrue()
	}

	@Test
	fun aTemplateOfAnotherLoadedPluginWhoseIdExtendsThisOneIsNotMatched() {
		val others = listOf("com.example.my_tools")

		assertThat(isTemplateOf("com.example.my", "plugin_com.example.my_tools_starter.cgt", others)).isFalse()
		assertThat(isTemplateOf("com.example.my", "plugin_com.example.my_starter.cgt", others)).isTrue()
	}

	@Test
	fun anExtendingIdThatIsNotLoadedDoesNotProtectItsName() {
		assertThat(isTemplateOf("com.example.my", "plugin_com.example.my_tools_starter.cgt", emptyList())).isTrue()
	}

	@Test
	fun anotherPluginsNameWithoutTheUnderscoreIsNotMatched() {
		assertThat(isTemplateOf("com.example.my", "plugin_com.example.my2_starter.cgt", listOf("com.example.my2"))).isFalse()
	}

	@Test
	fun theOwnTemplateThatLooksLikeAnotherPluginsIsKept() {
		assertThat(isTemplateOf("x", "plugin_x_tools_a.cgt", listOf("x_tools"))).isFalse()
	}

	@Test
	fun otherFilesAreNotMatched() {
		assertThat(isTemplateOf("a", "plugin_a_starter.zip", emptyList())).isFalse()
		assertThat(isTemplateOf("a", "plugin_a_starter", emptyList())).isFalse()
		assertThat(isTemplateOf("a", "plugin_b_starter.cgt", emptyList())).isFalse()
		assertThat(isTemplateOf("a", "core.cgt", emptyList())).isFalse()
	}
}
