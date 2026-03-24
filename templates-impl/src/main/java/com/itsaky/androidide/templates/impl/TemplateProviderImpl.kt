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

package com.itsaky.androidide.templates.impl

import com.google.auto.service.AutoService
import com.google.common.collect.ImmutableList
import com.itsaky.androidide.templates.ITemplateProvider
import com.itsaky.androidide.templates.Template
import com.itsaky.androidide.templates.impl.zip.ZipRecipeExecutor
import com.itsaky.androidide.templates.impl.zip.ZipTemplateReader

import org.adfa.constants.TEMPLATE_ARCHIVE_EXTENSION
import com.itsaky.androidide.utils.Environment.TEMPLATES_DIR

import org.slf4j.LoggerFactory
import java.util.zip.ZipFile

/**
 * Default implementation of the [ITemplateProvider].
 *
 * @author Akash Yadav
 */
@Suppress("unused")
@AutoService(ITemplateProvider::class)
class TemplateProviderImpl : ITemplateProvider {

    companion object {
        private val log = LoggerFactory.getLogger(TemplateProviderImpl::class.java)
    }

    private val templates = mutableMapOf<String, Template<*>>()

    init {
        reload()
    }

    private fun initializeTemplates() {
        val folder = TEMPLATES_DIR
        log.debug("Template listing archives in: ${folder.toString()} with extension: ${TEMPLATE_ARCHIVE_EXTENSION}")
        val list = folder.listFiles { file -> file.extension == TEMPLATE_ARCHIVE_EXTENSION } ?: return

        for (zipFile in list) {
            log.debug("Template archive: $zipFile")
            try {
                val zipTemplates = ZipTemplateReader.read(zipFile) { json, params, path, data, defModule ->
                    ZipRecipeExecutor({ ZipFile(zipFile) }, json, params, path, data, defModule)
                }

                for (t in zipTemplates) {
                    log.debug("template: $t")
                    templates[t.templateId] = t
                }

                log.debug("templates: $templates")
            } catch (e: Exception) {
                log.error("Failed to load template from archive: $zipFile", e)
            }
        }
    }

    override fun getTemplates(): List<Template<*>> {
        return ImmutableList.copyOf(templates.values)
    }

    override fun getTemplate(templateId: String): Template<*>? {
        return templates[templateId]
    }

    override fun reload() {
        release()
        initializeTemplates()
    }

    override fun release() {
        templates.forEach { it.value.release() }
        templates.clear()
    }
}