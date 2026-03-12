package com.itsaky.androidide.templates.impl.zip

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.itsaky.androidide.templates.ModuleTemplateData
import com.itsaky.androidide.templates.ProjectTemplate
import com.itsaky.androidide.templates.ProjectTemplateData
import com.itsaky.androidide.templates.ProjectTemplateRecipeResult
import com.itsaky.androidide.templates.R
import com.itsaky.androidide.templates.TemplateRecipe
import com.itsaky.androidide.templates.Widget
import com.itsaky.androidide.templates.base.baseZipProject
import org.slf4j.LoggerFactory
import java.io.File
import java.util.zip.ZipFile

object ZipTemplateReader {
  private val log = LoggerFactory.getLogger(ZipTemplateReader::class.java)

  private val gson = Gson()

  fun read(
    zipFile: File,
    recipeFactory: (TemplateJson, /* List<Parameter<*>>?, */ String, ProjectTemplateData, ModuleTemplateData) -> TemplateRecipe<ProjectTemplateRecipeResult>
  ): List<ProjectTemplate> {

    val templates = mutableListOf<ProjectTemplate>()

    try {
      val zip = ZipFile(zipFile)

      log.debug("zipFile: $zipFile zip: $zip")

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

  //    val widgets: List<Widget<*>> = metaJson.widgets.mapNotNull { it.toWidget() }
  //    val widgets: List<Widget<*>> = widgetsEntry?.let {
  //      val widgetsText = zip.getInputStream(it).bufferedReader().use { r -> r.readText() }
  //      parseWidgetsFromJson(widgetsText)
  //    } ?: metaJson.widgets.mapNotNull { it.toWidget() }

        val project =  baseZipProject (
          showLanguage = (metaJson.parameters?.optional?.language != null),
          showMinSdk = (metaJson.parameters?.optional?.minsdk != null)
        ) {

          this.templateNameStr = metaJson.name
          this.thumbData = thumbData

          this.templateName = 0
          this.thumb = R.drawable.template_no_activity

          log.debug("this.name: ${this.templateNameStr}")
          this.recipe = TemplateRecipe { executor ->
            val innerRecipe = recipeFactory(metaJson, basePath, /* params*/ data, defModule)
            innerRecipe.execute(executor)
          }
        }

        log.debug("adding project ${metaJson.name}")
        templates.add(project)
        } catch (e: Exception) {
          log.error("Failed to load template at ${templateRef.path}", e)
        }
      }
    } catch (e: Exception) {
      log.error("Failed to read zip file $zipFile", e)
      // return emptyList()
    }

    return templates
  }


  private fun parseWidgetsFromJson(jsonText: String): List<Widget<*>> {
    val type = object : TypeToken<List<WidgetJson>>() {}.type
    val widgetJsons: List<WidgetJson> = gson.fromJson(jsonText, type)
    return widgetJsons.mapNotNull { it.toWidget() }
  }

}
