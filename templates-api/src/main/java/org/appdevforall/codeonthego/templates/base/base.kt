/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.appdevforall.codeonthego.templates.base

import org.appdevforall.codeonthego.templates.BooleanParameter
import org.appdevforall.codeonthego.templates.CheckBoxWidget
import org.appdevforall.codeonthego.templates.EnumParameter
import org.appdevforall.codeonthego.templates.Language
import org.appdevforall.codeonthego.templates.ModuleTemplateData
import org.appdevforall.codeonthego.templates.ModuleType.AndroidApp
import org.appdevforall.codeonthego.templates.ParameterConstraint.DIRECTORY
import org.appdevforall.codeonthego.templates.ParameterConstraint.EXISTS
import org.appdevforall.codeonthego.templates.ParameterConstraint.NONEMPTY
import org.appdevforall.codeonthego.templates.ProjectTemplate
import org.appdevforall.codeonthego.templates.ProjectTemplateData
import org.appdevforall.codeonthego.templates.ProjectVersionData
import com.itsaky.androidide.templates.R
import org.appdevforall.codeonthego.templates.SpinnerWidget
import org.appdevforall.codeonthego.templates.StringParameter
import org.appdevforall.codeonthego.templates.TextFieldWidget
import org.appdevforall.codeonthego.templates.base.util.getNewProjectName
import org.appdevforall.codeonthego.templates.base.util.moduleNameToDir
import org.appdevforall.codeonthego.templates.initGitParameter
import org.appdevforall.codeonthego.templates.minSdkParameter
import org.appdevforall.codeonthego.templates.packageNameParameter
import org.appdevforall.codeonthego.templates.projectLanguageParameter
import org.appdevforall.codeonthego.templates.projectNameParameter
import org.appdevforall.codeonthego.templates.stringParameter
import org.appdevforall.codeonthego.templates.useKtsParameter
import org.appdevforall.codeonthego.utils.AndroidUtils
import org.appdevforall.codeonthego.utils.Environment
import org.adfa.constants.Sdk
import java.io.File


/**
 * Setup base files for zip project templates.
 *
 * @param block Function to configure the template.
 */
inline fun baseZipProject(
	projectName: StringParameter = projectNameParameter(),
	packageName: StringParameter = packageNameParameter(),
	useKts: BooleanParameter = useKtsParameter(),
	initGit: BooleanParameter = initGitParameter(),
	minSdk: EnumParameter<Sdk> = minSdkParameter(),
	language: EnumParameter<Language> = projectLanguageParameter(),
	projectVersionData: ProjectVersionData = ProjectVersionData(),
	isToml: Boolean = false,
	showUseKts: Boolean = false,
	showInitGit: Boolean = true,
	showMinSdk: Boolean = true,
	showLanguage: Boolean = true,
	showPackageName: Boolean = true,
	defaultSaveLocation: String? = null,
	crossinline block: ProjectTemplateBuilder.() -> Unit
): ProjectTemplate {
	return ProjectTemplateBuilder().apply {

		if (showPackageName) {
			projectName.observe { name ->
				val newPackage = AndroidUtils.appNameToPackageName(name.value, packageName.value)
				packageName.setValue(newPackage)
			}
		}

		val saveDir = if (defaultSaveLocation != null) {
			File(defaultSaveLocation).also { Environment.mkdirIfNotExists(it) }
		} else {
			Environment.mkdirIfNotExists(Environment.PROJECTS_DIR)
			Environment.PROJECTS_DIR
		}

		val saveLocation = stringParameter {
			name = R.string.wizard_save_location
			default = saveDir.absolutePath
			endIcon = { R.drawable.ic_folder }
			constraints = listOf(NONEMPTY, DIRECTORY, EXISTS)
			inputType =
			android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
			imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
			maxLines = 1
			tooltipTag = "setup.save.location"
		}

		projectName.doBeforeCreateView {
			it.setValue(getNewProjectName(saveLocation.value, projectName.value))
		}

		widgets(TextFieldWidget(projectName))
		if (showPackageName) widgets(TextFieldWidget(packageName))
		widgets(TextFieldWidget(saveLocation))

		if (showLanguage) {
			widgets(SpinnerWidget(language))
		}

		if (showMinSdk) {
			widgets(SpinnerWidget(minSdk))
		}

		if (showUseKts) {
			widgets(CheckBoxWidget(useKts))
		}

		if (showInitGit) {
			widgets(CheckBoxWidget(initGit))
		}

		// Setup the required properties before executing the recipe
		preRecipe = {
			this@apply._executor = this

			if (!showUseKts) {
				useKts.setValue(true, notify = false)
			}

			if (!showInitGit) {
				initGit.setValue(false, notify = false)
			}

			this@apply._data = ProjectTemplateData(
				projectName.value,
				File(saveLocation.value, projectName.value),
				projectVersionData,
				language = if (showLanguage) language.value else null,
				useKts = useKts.value,
				initGit = initGit.value,
				useToml = isToml
			)

			if (data.projectDir.exists() && data.projectDir.listFiles()
				?.isNotEmpty() == true
			) {
				throw IllegalArgumentException("Project directory already exists")
			}

			setDefaultModuleData(
				ModuleTemplateData(
					":app", appName = data.name, packageName.value,
					data.moduleNameToDir(":app"), type = AndroidApp,
					language = if (showLanguage) language.value else null,
					minSdk = if (showMinSdk) minSdk.value else null,
					useKts = data.useKts, useToml = isToml,
					initGit = initGit.value
				)
			)
		}

		block()

	}.build() as ProjectTemplate
}
