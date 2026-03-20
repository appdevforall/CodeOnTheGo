package com.itsaky.androidide.templates.impl.zip

import com.itsaky.androidide.templates.Language
import java.io.File
import java.io.StringWriter
import java.util.zip.ZipFile

import org.slf4j.LoggerFactory
import io.pebbletemplates.pebble.PebbleEngine
import io.pebbletemplates.pebble.loader.StringLoader
import io.pebbletemplates.pebble.lexer.Syntax

import com.itsaky.androidide.templates.ModuleTemplateData
import com.itsaky.androidide.templates.Parameter
import com.itsaky.androidide.templates.ProjectTemplateData
import com.itsaky.androidide.templates.ProjectTemplateRecipeResult
import com.itsaky.androidide.templates.RecipeExecutor
import com.itsaky.androidide.templates.TemplateRecipe
import com.itsaky.androidide.templates.impl.base.ProjectTemplateRecipeResultImpl
import com.itsaky.androidide.utils.Environment

import org.adfa.constants.ANDROID_GRADLE_PLUGIN_VERSION
import org.adfa.constants.KOTLIN_VERSION
import org.adfa.constants.Sdk

class ZipRecipeExecutor(
  private val zipProvider: () -> ZipFile,
  private val metaJson: TemplateJson,
  private val params: MutableMap<String, Parameter<*>>,
  private val basePath: String,
  private val data: ProjectTemplateData,
  private val defModule: ModuleTemplateData,
) : TemplateRecipe<ProjectTemplateRecipeResult> {

  companion object {
    private val log = LoggerFactory.getLogger(ZipRecipeExecutor::class.java)
  }

  override fun execute(
    executor: RecipeExecutor
  ): ProjectTemplateRecipeResult {

    log.debug("executor called!!")

    val projectDir = data.projectDir
    if (projectDir.exists()) {
      return ProjectTemplateRecipeResultImpl(data)
    }

    val projectRoot = projectDir.canonicalFile

    val flags: Map<String, Boolean> =
      params.mapNotNull { (identifier, param) ->
        (param.value as? Boolean)?.let { identifier to it }
      }.toMap()

    zipProvider().use { zip ->

      val customSyntax = Syntax.Builder()
        .setPrintOpenDelimiter(DELIM_PRINT_OPEN)
        .setPrintCloseDelimiter(DELIM_PRINT_CLOSE)
        .setExecuteOpenDelimiter(DELIM_EXECUTE_OPEN)
        .setExecuteCloseDelimiter(DELIM_EXECUTE_CLOSE)
        .setCommentOpenDelimiter(DELIM_COMMENT_OPEN)
        .setCommentCloseDelimiter(DELIM_COMMENT_CLOSE)
        .build()

      val pebbleEngine = PebbleEngine.Builder()
        .loader(StringLoader())
        .syntax(customSyntax)
        .build()

      val (identifiers, warnings) = metaJson.pebbleParams(data, defModule, params)
      log.debug("identifiers warnings: ${warnings.joinToString(System.lineSeparator())}")

      val packageName =
        resolveString(metaJson.parameters?.required?.packageName?.identifier, KEY_PACKAGE_NAME)

      for (entry in zip.entries()) {
        if (!entry.name.startsWith("$basePath/")) continue
        if (entry.name == "$basePath/") continue
        if (entry.name.startsWith("$basePath/$META_FOLDER/")) continue

        if ((metaJson.parameters?.optional?.language != null) &&
          (data.language != null) &&
          shouldSkipFile(
            entry.name.removeSuffix(TEMPLATE_EXTENSION),
            safeLanguageName(data.language)
          )
        ) continue

        val normalized = filterAndNormalizeZipEntry(entry.name, flags) ?: continue

        val relativePath = normalized.removePrefix("$basePath/")
          .replace(packageName.value, defModule.packageName.replace(".", "/"))

        val outFile = File(projectDir, relativePath.removeSuffix(TEMPLATE_EXTENSION)).canonicalFile

        if (!outFile.toPath().startsWith(projectRoot.toPath())) {
          log.warn("Skipping suspicious ZIP entry outside project dir: {}", entry.name)
          continue
        }

        if (entry.isDirectory) {
          outFile.mkdirs()
        } else {
          outFile.parentFile?.mkdirs()

          if (entry.name.endsWith(TEMPLATE_EXTENSION)) {
            log.debug("template processing ${entry.name}")
            val content = zip.getInputStream(entry).bufferedReader().use { it.readText() }
            val template = pebbleEngine.getTemplate(content)
            val writer = StringWriter()
            template.evaluate(writer, identifiers)
            outFile.writeText(writer.toString(), Charsets.UTF_8)
          } else {
            zip.getInputStream(entry).use { input ->
              outFile.outputStream().use { output ->
                input.copyTo(output)
              }
            }
          }
        }
      }
    }

    keystore(executor)

    return ProjectTemplateRecipeResultImpl(data)
  }

  private fun keystore(executor: RecipeExecutor) {
    val storeSrc = Environment.KEYSTORE_RELEASE
    val storeDest = File(data.projectDir, Environment.KEYSTORE_RELEASE_NAME)
    if (storeSrc.exists()) {
      executor.copy(storeSrc, storeDest)
    }


    val propsSrc = Environment.KEYSTORE_PROPERTIES
    val propsDest = File(data.projectDir, Environment.KEYSTORE_PROPERTIES_NAME)
    if (propsSrc.exists()) {
      executor.copy(propsSrc, propsDest)
    }
  }

  private fun shouldSkipFile(name: String, language: String): Boolean {
    // If language is Kotlin, skip .java files
    // If language is Java, skip .kt files
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (language.lowercase()) {
      LANGUAGE_KOTLIN -> ext == FILE_EXT_JAVA
      LANGUAGE_JAVA -> ext == FILE_EXT_KOTLIN
      else -> false
    }
  }

  private fun safeLanguageName(language: Language?): String =
    language?.name?.lowercase() ?: ""

  private fun safeMinSdkApi(minSdk: Sdk?): String =
    minSdk?.api?.toString() ?: ""

  private fun TemplateJson.pebbleParams(
    data: ProjectTemplateData,
    defModule: ModuleTemplateData,
    params: MutableMap<String, Parameter<*>>
  ): Pair<Map<String, Any>, List<String>> {

    val warnings = mutableListOf<String>()

    val appName = resolveString(parameters?.required?.appName?.identifier, KEY_APP_NAME)
    if (appName.usedDefault) warnings += "Missing 'appName', defaulted to $KEY_APP_NAME"

    val packageName = resolveString(parameters?.required?.packageName?.identifier, KEY_PACKAGE_NAME)
    if (packageName.usedDefault) warnings += "Missing 'packageName', defaulted to $KEY_PACKAGE_NAME"

    val saveLocation = resolveString(parameters?.required?.saveLocation?.identifier, KEY_SAVE_LOCATION)
    if (saveLocation.usedDefault) warnings += "Missing 'saveLocation', defaulted to $KEY_SAVE_LOCATION"

    val language = resolveString(parameters?.optional?.language?.identifier, KEY_LANGUAGE)
    if (language.usedDefault) warnings += "Missing 'language', defaulted to $KEY_LANGUAGE"

    val minSdk = resolveString(parameters?.optional?.minsdk?.identifier, KEY_MIN_SDK)
    if (minSdk.usedDefault) warnings += "Missing 'minsdk', defaulted to $KEY_MIN_SDK"

    val agpVersion = resolveString(system?.agpVersion?.identifier, KEY_AGP_VERSION)
    if (agpVersion.usedDefault) warnings += "Missing 'agpVersion', defaulted to $KEY_AGP_VERSION"

    val kotlinVersion = resolveString(system?.kotlinVersion?.identifier, KEY_KOTLIN_VERSION)
    if (kotlinVersion.usedDefault) warnings += "Missing 'kotlinVersion', defaulted to $KEY_KOTLIN_VERSION"

    val gradleVersion = resolveString(system?.gradleVersion?.identifier, KEY_GRADLE_VERSION)
    if (gradleVersion.usedDefault) warnings += "Missing 'gradleVersion', defaulted to $KEY_GRADLE_VERSION"

    val compileSdk = resolveString(system?.compileSdk?.identifier, KEY_COMPILE_SDK)
    if (compileSdk.usedDefault) warnings += "Missing 'compileSdk', defaulted to $KEY_COMPILE_SDK"

    val targetSdk = resolveString(system?.targetSdk?.identifier, KEY_TARGET_SDK)
    if (targetSdk.usedDefault) warnings += "Missing 'targetSdk', defaulted to $KEY_TARGET_SDK"

    val javaSourceCompat = resolveString(system?.javaSourceCompat?.identifier, KEY_JAVA_SOURCE_COMPAT)
    if (javaSourceCompat.usedDefault) warnings += "Missing 'javaSourceCompat', defaulted to $KEY_JAVA_SOURCE_COMPAT"

    val javaTargetCompat = resolveString(system?.javaTargetCompat?.identifier, KEY_JAVA_TARGET_COMPAT)
    if (javaTargetCompat.usedDefault) warnings += "Missing 'javaTargetCompat', defaulted to $KEY_JAVA_TARGET_COMPAT"

    val javaTarget = resolveString(system?.javaTarget?.identifier, KEY_JAVA_TARGET)
    if (javaTarget.usedDefault) warnings += "Missing 'javaTarget', defaulted to $KEY_JAVA_TARGET"

    val baseMap = mapOf(
      appName.value to data.name,
      packageName.value to defModule.packageName,
      saveLocation.value to data.projectDir.toString(),
      language.value to safeLanguageName(data.language),
      minSdk.value to safeMinSdkApi(defModule.versions.minSdk),
      agpVersion.value to ANDROID_GRADLE_PLUGIN_VERSION,
      kotlinVersion.value to KOTLIN_VERSION,
      gradleVersion.value to data.version.gradle,
      compileSdk.value to defModule.versions.compileSdk.api.toString(),
      targetSdk.value to defModule.versions.targetSdk.api.toString(),
      javaSourceCompat.value to defModule.versions.javaSource(),
      javaTargetCompat.value to defModule.versions.javaTarget(),
      javaTarget.value to defModule.versions.javaTarget
    )

    val map = baseMap + params.mapValues { (_, param) ->
      param.value ?: ""
    }

    return map to warnings
  }

  data class ResolvedParam<T>(
    val value: T,
    val usedDefault: Boolean
  )

  private fun resolveString(value: String?, default: String): ResolvedParam<String> {
    return if (value.isNullOrBlank()) ResolvedParam(default, true)
    else ResolvedParam(value, false)
  }

  private fun resolveBoolean(raw: Boolean?, default: Boolean): ResolvedParam<Boolean> {
    return if (raw == null) ResolvedParam(default, true)
    else ResolvedParam(raw, false)
  }

  private fun filterAndNormalizeZipEntry(
    entryName: String,
    flags: Map<String, Boolean>
  ): String? {
    val parts = entryName.split(File.separator).filter { it.isNotEmpty() }
    if (parts.isEmpty()) return null

    val normalizedParts = mutableListOf<String>()

    for (part in parts) {
      when (flags[part]) {
        null -> normalizedParts.add(part)
        true -> { }
        false -> return null
      }
    }

    return normalizedParts.joinToString(File.separator)
  }

}
