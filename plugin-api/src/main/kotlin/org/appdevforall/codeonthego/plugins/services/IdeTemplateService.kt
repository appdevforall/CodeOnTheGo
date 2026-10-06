package org.appdevforall.codeonthego.plugins.services

import org.appdevforall.codeonthego.plugins.templates.CgtTemplateBuilder
import java.io.File

interface IdeTemplateService {

    fun createTemplateBuilder(name: String): CgtTemplateBuilder

    fun registerTemplate(cgtFile: File): Boolean

    fun unregisterTemplate(templateFileName: String): Boolean

    fun isTemplateRegistered(templateFileName: String): Boolean

    fun getRegisteredTemplates(): List<String>

    fun reloadTemplates()
}
