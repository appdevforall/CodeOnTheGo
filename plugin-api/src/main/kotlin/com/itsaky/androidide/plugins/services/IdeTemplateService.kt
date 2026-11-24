package com.itsaky.androidide.plugins.services

import com.itsaky.androidide.plugins.templates.CgtTemplateBuilder
import java.io.File

interface IdeTemplateService {
	fun createTemplateBuilder(name: String): CgtTemplateBuilder

	/**
	 * Copies [cgtFile] into the IDE's templates folder, so it is offered for new projects. Needs
	 * `filesystem.write`. A copy that already holds the same bytes is left untouched and no change
	 * is reported, so registering the same templates in every `activate()` costs only reading both
	 * files.
	 * Returns false if the permission is missing, [cgtFile] is missing or not a `.cgt`, or the copy
	 * fails.
	 */
	fun registerTemplate(cgtFile: File): Boolean

	fun unregisterTemplate(templateFileName: String): Boolean

	fun isTemplateRegistered(templateFileName: String): Boolean

	fun getRegisteredTemplates(): List<String>

	fun reloadTemplates()
}
