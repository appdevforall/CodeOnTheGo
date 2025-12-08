

package com.itsaky.androidide.templates.impl.pluginProject

import com.itsaky.androidide.templates.BooleanParameter
import com.itsaky.androidide.templates.CheckBoxWidget
import com.itsaky.androidide.templates.ProjectTemplate
import com.itsaky.androidide.templates.ProjectTemplateRecipeResult
import com.itsaky.androidide.templates.StringParameter
import com.itsaky.androidide.templates.TemplateRecipe
import com.itsaky.androidide.templates.TextFieldWidget
import com.itsaky.androidide.templates.base.ProjectTemplateBuilder
import com.itsaky.androidide.templates.base.basePluginProject
import com.itsaky.androidide.templates.booleanParameter
import com.itsaky.androidide.templates.impl.R
import com.itsaky.androidide.templates.impl.base.ProjectTemplateRecipeResultImpl
import com.itsaky.androidide.templates.projectNameParameter
import com.itsaky.androidide.templates.packageNameParameter
import com.itsaky.androidide.templates.stringParameter
import com.itsaky.androidide.utils.Environment
import com.itsaky.androidide.utils.FeatureFlags
import java.io.File

fun pluginProjectTemplate(): ProjectTemplate? {
	if (!FeatureFlags.isExperimentsEnabled()) {
		return null
	}

	return pluginProject()
}

private fun pluginProject(): ProjectTemplate {
	val pluginName = projectNameParameter {
		default = "My Plugin"
	}
	val pluginId = packageNameParameter {
		default = "com.example.myplugin"
	}
	val description = pluginDescriptionParameter()
	val author = pluginAuthorParameter()
	val includeSampleCode = includeSampleCodeParameter()

	return basePluginProject(
		projectName = pluginName,
		packageName = pluginId
	) {
		templateName = R.string.template_plugin
		thumb = R.drawable.template_plugin

		widgets(
			TextFieldWidget(description),
			TextFieldWidget(author),
			CheckBoxWidget(includeSampleCode)
		)

		recipe = TemplateRecipe {
			executePluginRecipe(
				pluginName.value,
				pluginId.value,
				description.value,
				author.value,
				includeSampleCode.value
			)
		}
	}
}

private fun ProjectTemplateBuilder.executePluginRecipe(
	pluginName: String,
	pluginId: String,
	description: String,
	author: String,
	includeSampleCode: Boolean
): ProjectTemplateRecipeResult {
	if (!Environment.PLUGIN_API_JAR.exists()) {
		throw IllegalStateException(
			"plugin-api.jar not found. Please ensure you have the latest version of CodeOnTheGo installed."
		)
	}

	val permissions = setOf(
		PluginPermission.FILESYSTEM_READ,
		PluginPermission.FILESYSTEM_WRITE,
		PluginPermission.PROJECT_STRUCTURE
	)

	val extensions = if (includeSampleCode) {
		setOf(PluginExtension.UI, PluginExtension.EDITOR_TAB, PluginExtension.DOCUMENTATION)
	} else {
		emptySet()
	}

	val templateData = PluginTemplateData(
		pluginName = pluginName,
		pluginId = pluginId,
		description = description,
		author = author,
		permissions = permissions,
		extensions = extensions,
		includeSampleCode = includeSampleCode
	)

	val projectDir = data.projectDir
	projectDir.mkdirs()

	val srcDir = File(projectDir, "src/main/kotlin/${templateData.packagePath}")
	srcDir.mkdirs()

	val resDir = File(projectDir, "src/main/res")
	resDir.mkdirs()
	File(resDir, "layout").mkdirs()
	File(resDir, "values").mkdirs()

	val libsDir = File(projectDir, "libs")
	libsDir.mkdirs()

	File(projectDir, "build.gradle.kts").writeText(pluginBuildGradleKts(templateData))
	File(projectDir, "settings.gradle.kts").writeText(pluginSettingsGradleKts(templateData))
	File(projectDir, "gradle.properties").writeText(pluginGradleProperties())
	File(projectDir, "proguard-rules.pro").writeText(pluginProguardRules())
	File(projectDir, "src/main/AndroidManifest.xml").writeText(pluginAndroidManifest(templateData))

	File(srcDir, "${templateData.className}Plugin.kt").writeText(pluginMainClassKt(templateData))

	if (templateData.includeSampleCode && templateData.extensions.contains(PluginExtension.UI)) {
		val fragmentsDir = File(srcDir, "fragments")
		fragmentsDir.mkdirs()
		File(fragmentsDir, "${templateData.className}Fragment.kt").writeText(pluginFragmentKt(templateData))
		File(resDir, "layout/fragment_main.xml").writeText(pluginLayoutXml(templateData))
	}

	File(resDir, "values/strings.xml").writeText(pluginStringsXml(templateData))

	Environment.PLUGIN_API_JAR.copyTo(File(libsDir, "plugin-api.jar"), overwrite = true)

	return ProjectTemplateRecipeResultImpl(data)
}

private fun pluginDescriptionParameter(): StringParameter = stringParameter {
	name = R.string.wizard_plugin_description
	default = "A COGO plugin"
	constraints = emptyList()
	inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
	imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
	maxLines = 1
}

private fun pluginAuthorParameter(): StringParameter = stringParameter {
	name = R.string.wizard_plugin_author
	default = ""
	constraints = emptyList()
	inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
	imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
	maxLines = 1
}

private fun includeSampleCodeParameter(): BooleanParameter = booleanParameter {
	name = R.string.wizard_include_sample_code
	default = true
}