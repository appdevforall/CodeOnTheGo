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
import com.itsaky.androidide.templates.projectNameParameter
import com.itsaky.androidide.templates.stringParameter
import com.itsaky.androidide.utils.FeatureFlags
import org.slf4j.LoggerFactory
import java.io.File
import java.util.zip.ZipFile

object ZipTemplateReader {
    private val log = LoggerFactory.getLogger(ZipTemplateReader::class.java)

    private val gson = Gson()

    fun read(
        zipFile: File,
        warnings: MutableList<String>,
        recipeFactory: (TemplateJson, MutableMap<String, Parameter<*>>, String, ProjectTemplateData, ModuleTemplateData) -> TemplateRecipe<ProjectTemplateRecipeResult>
    ): List<ProjectTemplate> {

        val templates = mutableListOf<ProjectTemplate>()

        try {
            ZipFile(zipFile).use { zip ->

                val indexEntry = requireNotNull(zip.getEntry(ARCHIVE_JSON)) {
                    "${zip.name} does not contain $ARCHIVE_JSON"
                }

                val indexJson = zip.getInputStream(indexEntry).bufferedReader().use {
                    gson.fromJson(it, TemplatesIndex::class.java)
                }

                for (templateRef in indexJson.templates) {
                    try {

                        val basePath = templateRef.path
                        log.debug("basePath: $basePath")

                        if (templateRef.experimental && !FeatureFlags.isExperimentsEnabled) {
                            log.debug("Skipping experimental template: $basePath")
                            continue
                        }

                        val metaEntry = zip.getEntry("$basePath/$META_FOLDER/$META_JSON") ?: continue

                        val metaJsonString = zip.getInputStream(metaEntry).bufferedReader().use { reader ->
                            reader.readText()
                        }

                        val metaJson = gson.fromJson(metaJsonString, TemplateJson::class.java)

                        val thumbEntry = zip.getEntry("$basePath/$META_FOLDER/$META_THUMBNAIL")
                        val thumbData = thumbEntry?.let { zip.getInputStream(it).use { s -> s.readBytes() } }

                        val userWidgets = mutableListOf<Widget<*>>()
                        val params = mutableMapOf<String, Parameter<*>>()

                        metaJson.parameters?.user?.text?.forEach { textParam ->
                            val param = stringParameter {
                                name = 0
                                nameStr = textParam.label ?: ""
                                default = textParam.default ?: ""
                            }
                            userWidgets.add(TextFieldWidget(param))
                            params[textParam.identifier] = param
                        }

                        metaJson.parameters?.user?.checkbox?.forEach { checkboxParam ->
                            val param = booleanParameter {
                                name = 0
                                nameStr = checkboxParam.label ?: ""
                                default = checkboxParam.default ?: false
                            }
                            userWidgets.add(CheckBoxWidget(param))
                            params[checkboxParam.identifier] = param
                        }

                        val project = baseZipProject(
                            projectName = projectNameParameter {
                                metaJson.defaultAppName?.let { default = it }
                            },
                            showLanguage = (metaJson.parameters?.optional?.language != null),
                            showMinSdk = (metaJson.parameters?.optional?.minsdk != null),
                            showPackageName = (metaJson.parameters?.required?.packageName != null),
                            defaultSaveLocation = metaJson.defaultSaveLocation
                        ) {

                            this.templateNameStr = metaJson.name
                            this.tooltipTag = metaJson.tooltipTag
                            this.thumbData = thumbData

                            this.templateName = 0
                            this.thumb = R.drawable.template_no_activity

                            for (widget in userWidgets) {
                                widgets(widget)
                            }

                            this.recipe = TemplateRecipe { executor ->
                                val innerRecipe = recipeFactory(metaJson, params, basePath, data, defModule)
                                innerRecipe.execute(executor)
                            }
                        }

                        templates.add(project)
                    } catch (e: Exception) {
                        warnings.add("Failed to load template at ${templateRef.path} error: ${e.message}")
                        log.error("Failed to load template at ${templateRef.path}", e)
                    }
                }
            }
        } catch (e: Exception) {
            warnings.add("Failed to load template archive $zipFile error: ${e.message}")
            log.error("Failed to read zip file $zipFile", e)
        }

        return templates
    }
}
