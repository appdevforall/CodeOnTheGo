package com.itsaky.androidide.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Handles the injection and updating of <string-array> elements
 * within the project's strings.xml resource file.
 */
object StringsXmlInjector {
    private val log = LoggerFactory.getLogger(StringsXmlInjector::class.java)
    private val ARRAY_REGEX = Regex("""<string-array\s+name="([^"]+)".*?>.*?</string-array>""", RegexOption.DOT_MATCHES_ALL)

    suspend fun inject(layoutFilePath: String, newStringsXml: String) =
        withContext(Dispatchers.IO) {
            try {
                val stringsFile = resolveStringsFile(layoutFilePath) ?: return@withContext
                val currentContent =
                    if (stringsFile.exists()) stringsFile.readText() else createEmptyResources()

                val updatedContent = mergeContent(currentContent, newStringsXml)

                stringsFile.writeText(updatedContent)
            } catch (e: Exception) {
                log.error("Failed to inject arrays into strings.xml", e)
            }
        }

    private fun resolveStringsFile(layoutFilePath: String): File? {
        val resDir = File(layoutFilePath).parentFile?.parentFile ?: return null
        if (resDir.name != "res") return null

        val valuesDir = File(resDir, "values").apply { if (!exists()) mkdirs() }
        return File(valuesDir, "strings.xml")
    }

    private fun createEmptyResources(): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <resources>
        </resources>
    """.trimIndent()

    private fun mergeContent(currentContent: String, newStringsXml: String): String {
        var updatedContent = currentContent
        val newArrays = ARRAY_REGEX.findAll(newStringsXml)

        for (match in newArrays) {
            val arrayName = match.groupValues[1]
            val fullArrayBlock = match.value

            val existingRegex = Regex("""<string-array\s+name="$arrayName".*?>.*?</string-array>""", RegexOption.DOT_MATCHES_ALL)

            updatedContent = if (existingRegex.containsMatchIn(updatedContent)) {
                updatedContent.replace(existingRegex, fullArrayBlock)
            } else {
                insertBeforeResourcesEnd(updatedContent, fullArrayBlock)
            }
        }
        return updatedContent
    }

    private fun insertBeforeResourcesEnd(content: String, blockToInsert: String): String {
        val insertPos = content.lastIndexOf("</resources>")
        return if (insertPos != -1) {
            content.take(insertPos) + "    $blockToInsert\n" + content.substring(insertPos)
        } else {
            "$content\n$blockToInsert\n"
        }
    }
}