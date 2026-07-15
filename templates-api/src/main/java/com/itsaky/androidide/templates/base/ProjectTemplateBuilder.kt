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

import com.itsaky.androidide.templates.ModuleTemplate
import com.itsaky.androidide.templates.ModuleTemplateData
import com.itsaky.androidide.templates.ProjectTemplate
import com.itsaky.androidide.templates.ProjectTemplateData
import com.itsaky.androidide.templates.ProjectTemplateRecipeResult


/**
 * Builder for building project templates.
 *
 * @author Akash Yadav
 */
class ProjectTemplateBuilder : ExecutorDataTemplateBuilder<ProjectTemplateRecipeResult, ProjectTemplateData>() {
	private var _defModule: ModuleTemplateData? = null

	@PublishedApi
	internal val modules = mutableListOf<ModuleTemplate>()

	val defModule: ModuleTemplateData
		get() = checkNotNull(_defModule) { "Module template data not set" }

	/**
	 * Set the template data that will be used to create the default application module in the project.
	 *
	 * @param data The module template data to use.
	 */
	fun setDefaultModuleData(data: ModuleTemplateData) {
		_defModule = data
	}

	override fun buildInternal(): ProjectTemplate =
		ProjectTemplate(
			modules,
			templateName!!,
			thumb!!,
			tooltipTag,
			widgets!!,
			recipe!!,
            templateNameStr!!,
            thumbData
		)
}
