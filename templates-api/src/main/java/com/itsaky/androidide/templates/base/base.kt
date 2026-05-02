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

package com.itsaky.androidide.templates.base

import com.itsaky.androidide.templates.BooleanParameter
import com.itsaky.androidide.templates.CheckBoxWidget
import com.itsaky.androidide.templates.EnumParameter
import com.itsaky.androidide.templates.Language
import com.itsaky.androidide.templates.ModuleTemplateData
import com.itsaky.androidide.templates.ModuleType.AndroidApp
import com.itsaky.androidide.templates.ParameterConstraint.DIRECTORY
import com.itsaky.androidide.templates.ParameterConstraint.EXISTS
import com.itsaky.androidide.templates.ParameterConstraint.NONEMPTY
import com.itsaky.androidide.templates.ProjectTemplate
import com.itsaky.androidide.templates.ProjectTemplateData
import com.itsaky.androidide.templates.ProjectVersionData
import com.itsaky.androidide.templates.R
import com.itsaky.androidide.templates.SpinnerWidget
import com.itsaky.androidide.templates.StringParameter
import com.itsaky.androidide.templates.TextFieldWidget
import com.itsaky.androidide.templates.base.util.getNewProjectName
import com.itsaky.androidide.templates.base.util.moduleNameToDir
import com.itsaky.androidide.templates.minSdkParameter
import com.itsaky.androidide.templates.packageNameParameter
import com.itsaky.androidide.templates.projectLanguageParameter
import com.itsaky.androidide.templates.projectNameParameter
import com.itsaky.androidide.templates.stringParameter
import com.itsaky.androidide.templates.useKtsParameter
import com.itsaky.androidide.utils.AndroidUtils
import com.itsaky.androidide.utils.Environment
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
    minSdk: EnumParameter<Sdk> = minSdkParameter(),
    language: EnumParameter<Language> = projectLanguageParameter(),
    projectVersionData: ProjectVersionData = ProjectVersionData(),
    isToml: Boolean = false,
    showUseKts: Boolean = false,
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

        // Setup the required properties before executing the recipe
        preRecipe = {
            this@apply._executor = this

            if (!showUseKts) {
                useKts.setValue(true, notify = false)
            }

            this@apply._data = ProjectTemplateData(
                projectName.value,
                File(saveLocation.value, projectName.value),
                projectVersionData,
                language = if (showLanguage) language.value else null,
                useKts = useKts.value,
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
                    useKts = data.useKts, useToml = isToml
                )
            )
        }

        block()

    }.build() as ProjectTemplate
}
