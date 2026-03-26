package com.itsaky.androidide.plugins.templates

import com.itsaky.androidide.plugins.PluginContext
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CgtTemplateBuilder(private val templateName: String) {

    private var description: String = ""
    private var tooltipTag: String = ""
    private var version: String = "1.0"
    private var thumbnail: ByteArray? = null
    private var showLanguage: Boolean = false
    private var showMinSdk: Boolean = false
    private var showPackageName: Boolean = false

    private val textParameters = mutableListOf<TextParam>()
    private val checkboxParameters = mutableListOf<CheckboxParam>()
    private val templateFiles = mutableListOf<FileEntry>()
    private val staticFiles = mutableListOf<FileEntry>()
    private val binaryFiles = mutableListOf<BinaryFileEntry>()

    fun description(desc: String) = apply { this.description = desc }

    fun tooltipTag(tag: String) = apply { this.tooltipTag = tag }

    fun version(ver: String) = apply { this.version = ver }

    fun thumbnail(bytes: ByteArray) = apply { this.thumbnail = bytes }

    fun showLanguageOption() = apply { this.showLanguage = true }

    fun showMinSdkOption() = apply { this.showMinSdk = true }

    fun showPackageNameOption() = apply { this.showPackageName = true }

    fun thumbnailFromAssets(assetPath: String, context: PluginContext) = apply {
        this.thumbnail = context.androidContext.assets.open(assetPath).use { it.readBytes() }
    }

    fun addTextParameter(label: String, identifier: String, default: String? = null) = apply {
        textParameters.add(TextParam(label, identifier, default))
    }

    fun addCheckboxParameter(label: String, identifier: String, default: Boolean = false) = apply {
        checkboxParameters.add(CheckboxParam(label, identifier, default))
    }

    fun addTemplateFile(path: String, content: String) = apply {
        templateFiles.add(FileEntry(path, toPebbleSyntax(content)))
    }

    fun addTemplateFromAssets(path: String, assetPath: String, context: PluginContext) = apply {
        val content = context.androidContext.assets.open(assetPath)
            .bufferedReader()
            .use { it.readText() }
        templateFiles.add(FileEntry(path, content))
    }

    fun addStaticFile(path: String, content: String) = apply {
        staticFiles.add(FileEntry(path, content))
    }

    fun addStaticFile(path: String, bytes: ByteArray) = apply {
        binaryFiles.add(BinaryFileEntry(path, bytes))
    }

    fun addStaticFile(path: String, inputStream: InputStream) = apply {
        binaryFiles.add(BinaryFileEntry(path, inputStream.use { it.readBytes() }))
    }

    fun addStaticFromAssets(path: String, assetPath: String, context: PluginContext) = apply {
        val bytes = context.androidContext.assets.open(assetPath)
            .use { it.readBytes() }
        binaryFiles.add(BinaryFileEntry(path, bytes))
    }

    fun build(outputDir: File): File {
        outputDir.mkdirs()
        val dirName = templateName.replace(Regex("[^a-zA-Z0-9]"), "")
        val outputFile = File(outputDir, "$dirName.cgt")

        ZipOutputStream(outputFile.outputStream()).use { zip ->
            writeIndex(zip, dirName)
            writeMetadata(zip, dirName)
            writeThumbnail(zip, dirName)
            writeTemplateFiles(zip, dirName)
            writeStaticFiles(zip, dirName)
            writeBinaryFiles(zip, dirName)
        }

        return outputFile
    }

    private fun toPebbleSyntax(content: String): String {
        return content
            .replace("{{", "\${{")
            .replace("{%", "\${%")
            .replace("{#", "\${#")
            .replace("\$\${{", "\${{")
            .replace("\$\${%", "\${%")
            .replace("\$\${#", "\${#")
    }

    private fun writeIndex(zip: ZipOutputStream, dirName: String) {
        val json = """
            {
              "templates": [
                { "path": "$dirName" }
              ]
            }
        """.trimIndent()
        addEntry(zip, "templates.json", json)
    }

    private fun writeMetadata(zip: ZipOutputStream, dirName: String) {
        val optionalBlock = buildString {
            val parts = mutableListOf<String>()
            if (showLanguage) parts.add(""""language": {"identifier": "LANGUAGE"}""")
            if (showMinSdk) parts.add(""""minsdk": {"identifier": "MIN_SDK"}""")
            if (parts.isNotEmpty()) {
                append("""
                    "optional": {
                        ${parts.joinToString(",\n            ")}
                    },
                """.trimIndent())
            }
        }

        val userBlock = buildString {
            if (textParameters.isEmpty() && checkboxParameters.isEmpty()) return@buildString

            val textJson = textParameters.joinToString(",\n                ") { p ->
                val defaultPart = if (p.default != null) """, "default": "${p.default}"""" else ""
                """{"label": "${p.label}", "identifier": "${p.identifier}"$defaultPart}"""
            }

            val checkboxJson = checkboxParameters.joinToString(",\n                ") { p ->
                """{"label": "${p.label}", "identifier": "${p.identifier}", "default": ${p.default}}"""
            }

            append(""""user": {""")
            if (textParameters.isNotEmpty()) {
                append("""
                    "text": [$textJson]""")
            }
            if (checkboxParameters.isNotEmpty()) {
                if (textParameters.isNotEmpty()) append(",")
                append("""
                    "checkbox": [$checkboxJson]""")
            }
            append("""
                },""")
        }

        val json = """
            {
                "name": "$templateName",
                "description": "$description",
                "tooltipTag": "$tooltipTag",
                "version": "$version",
                "parameters": {
                    "required": {
                        "appName": {"identifier": "APP_NAME"},
                        ${if (showPackageName) """"packageName": {"identifier": "PACKAGE_NAME"},""" else ""}
                        "saveLocation": {"identifier": "SAVE_LOCATION"}
                    },
                    $optionalBlock
                    $userBlock
                    "placeholder": null
                },
                "system": {
                    "agpVersion": {"identifier": "AGP_VERSION"},
                    "kotlinVersion": {"identifier": "KOTLIN_VERSION"},
                    "gradleVersion": {"identifier": "GRADLE_VERSION"},
                    "compileSdk": {"identifier": "COMPILE_SDK"},
                    "targetSdk": {"identifier": "TARGET_SDK"},
                    "javaSourceCompat": {"identifier": "JAVA_SOURCE_COMPAT"},
                    "javaTargetCompat": {"identifier": "JAVA_TARGET_COMPAT"},
                    "javaTarget": {"identifier": "JAVA_TARGET"}
                }
            }
        """.trimIndent()

        addEntry(zip, "$dirName/template/template.json", json)
    }

    private fun writeThumbnail(zip: ZipOutputStream, dirName: String) {
        val thumbBytes = thumbnail ?: return
        zip.putNextEntry(ZipEntry("$dirName/template/thumb.png"))
        zip.write(thumbBytes)
        zip.closeEntry()
    }

    private fun writeTemplateFiles(zip: ZipOutputStream, dirName: String) {
        for (file in templateFiles) {
            addEntry(zip, "$dirName/${file.path}.peb", file.content)
        }
    }

    private fun writeStaticFiles(zip: ZipOutputStream, dirName: String) {
        for (file in staticFiles) {
            addEntry(zip, "$dirName/${file.path}", file.content)
        }
    }

    private fun writeBinaryFiles(zip: ZipOutputStream, dirName: String) {
        for (file in binaryFiles) {
            zip.putNextEntry(ZipEntry("$dirName/${file.path}"))
            zip.write(file.bytes)
            zip.closeEntry()
        }
    }

    private fun addEntry(zip: ZipOutputStream, path: String, content: String) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private data class TextParam(val label: String, val identifier: String, val default: String?)
    private data class CheckboxParam(val label: String, val identifier: String, val default: Boolean)
    private data class FileEntry(val path: String, val content: String)
    private data class BinaryFileEntry(val path: String, val bytes: ByteArray)

    companion object

}
