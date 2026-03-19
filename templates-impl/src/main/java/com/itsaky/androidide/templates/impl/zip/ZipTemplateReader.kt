package com.itsaky.androidide.templates.impl.zip

import com.google.gson.Gson
import com.itsaky.androidide.templates.CheckBoxWidget
import com.itsaky.androidide.templates.ModuleTemplateData
import com.itsaky.androidide.templates.Parameter
import com.itsaky.androidide.templates.ProjectTemplate
import com.itsaky.androidide.templates.ProjectTemplateData
import com.itsaky.androidide.templates.ProjectTemplateRecipeResult
import com.itsaky.androidide.templates.R
import com.itsaky.androidide.templates.TemplateRecipe
import com.itsaky.androidide.templates.TextFieldWidget
import com.itsaky.androidide.templates.Widget
import com.itsaky.androidide.templates.base.baseZipProject
import com.itsaky.androidide.templates.booleanParameter
import com.itsaky.androidide.templates.stringParameter
import org.slf4j.LoggerFactory
import java.io.File
import java.util.zip.ZipFile

object ZipTemplateReader {
  private val log = LoggerFactory.getLogger(ZipTemplateReader::class.java)

  private val gson = Gson()

  fun read(
    zipFile: File,
    recipeFactory: (TemplateJson, MutableMap<String, Parameter<*>>, String, ProjectTemplateData, ModuleTemplateData) -> TemplateRecipe<ProjectTemplateRecipeResult>
  ): List<ProjectTemplate> {

    val templates = mutableListOf<ProjectTemplate>()

    try {
      ZipFile(zipFile).use { zip ->

        log.debug("zipFile: $zipFile")

        val indexEntry = zip.getEntry(ARCHIVE_JSON) ?: return emptyList()
        val indexJson = zip.getInputStream(indexEntry).bufferedReader().use {
          gson.fromJson(it, TemplatesIndex::class.java)
        }

        log.debug("indexJson: $indexJson")
        for (templateRef in indexJson.templates) {
          try {
            val basePath = templateRef.path
            log.debug("basePath: $basePath")
            val metaEntry = zip.getEntry("$basePath/$META_FOLDER/$META_JSON") ?: continue

            val metaJsonString = zip.getInputStream(metaEntry).bufferedReader().use { reader ->
              reader.readText()
            }

            val metaJson = gson.fromJson(metaJsonString, TemplateJson::class.java)

            log.debug("metaJson: $metaJson")

            val thumbEntry = zip.getEntry("$basePath/$META_FOLDER/$META_THUMBNAIL")
            val thumbData = thumbEntry?.let { zip.getInputStream(it).use { s -> s.readBytes() } }

            if (thumbData == null) log.error("template $basePath/$META_FOLDER/$META_THUMBNAIL not found or is invalid")
            log.debug("thumbData: $thumbData")

            val userWidgets = mutableListOf<Widget<*>>()
            val params = mutableMapOf<String, Parameter<*>>()

            metaJson.parameters?.user?.text?.forEach { textParam ->
              val param = stringParameter {
                // name = string.project_app_name
                name = 0
                nameStr = textParam.label ?: ""
                default = textParam.default ?: ""

              }
              userWidgets.add(TextFieldWidget(param))
              params[textParam.identifier] = param
            }

            metaJson.parameters?.user?.checkbox?.forEach { checkboxParam ->
              val param = booleanParameter {
                // name = string.project_app_name
                name = 0
                nameStr = checkboxParam.label ?: ""
                default = checkboxParam.default ?: false
              }
              userWidgets.add(CheckBoxWidget(param))
              params[checkboxParam.identifier] = param
            }

            val project = baseZipProject(
              showLanguage = (metaJson.parameters?.optional?.language != null),
              showMinSdk = (metaJson.parameters?.optional?.minsdk != null)
            ) {

              this.templateNameStr = metaJson.name
              this.tooltipTag = metaJson.tooltipTag
              this.thumbData = thumbData

              this.templateName = 0
              this.thumb = R.drawable.template_no_activity

              for (widget in userWidgets) {
                widgets(widget)
              }

              log.debug("this.name: ${this.templateNameStr}")
              this.recipe = TemplateRecipe { executor ->
                val innerRecipe = recipeFactory(metaJson, params, basePath, data, defModule)
                innerRecipe.execute(executor)
              }
            }

            log.debug("adding project ${metaJson.name}")
            templates.add(project)
          } catch (e: Exception) {
            log.error("Failed to load template at ${templateRef.path}", e)
          }
        }
      }
    } catch (e: Exception) {
      log.error("Failed to read zip file $zipFile", e)
    }

    return templates
  }

}
