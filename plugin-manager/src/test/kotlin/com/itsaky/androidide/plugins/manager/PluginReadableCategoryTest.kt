package com.itsaky.androidide.plugins.manager

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class PluginReadableCategoryTest {
	private val pluginId = "org.appdevforall.codeonthego.layouteditor"

	@Test
	fun aPluginReadsTheIdesOwnDocumentationCategories() {
		for (category in listOf("ide", "java", "kotlin", "xml")) {
			assertWithMessage(category).that(pluginReadableCategory(pluginId, category)).isEqualTo(category)
		}
	}

	@Test
	fun anyOtherCategoryReadsThePluginsOwnEntries() {
		val own = "plugin_$pluginId"
		for (category in listOf(own, "plugin_keystore_generator", "plugin_com.example.other", "Java", "", "layouteditor")) {
			assertWithMessage(category).that(pluginReadableCategory(pluginId, category)).isEqualTo(own)
		}
	}
}
