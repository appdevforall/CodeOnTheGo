package com.itsaky.androidide.utils

import androidx.annotation.StringRes
import com.itsaky.androidide.R
import com.itsaky.androidide.api.commands.AddStringArrayResourceCommand
import com.itsaky.androidide.projects.IProjectManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.w3c.dom.Element
import org.xml.sax.EntityResolver
import org.xml.sax.InputSource
import java.io.File
import java.io.FileNotFoundException
import java.io.StringReader
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException

/**
 * Parses generated string-array XML and delegates file updates to
 * [AddStringArrayResourceCommand].
 */
object StringsXmlInjector {
    private val log = LoggerFactory.getLogger(StringsXmlInjector::class.java)

    suspend fun inject(layoutFilePath: String, newStringsXml: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val stringsFile = findProjectStringsFile()
                    ?: return@withContext Result.failure(FileNotFoundException(
                        "Cannot resolve strings.xml path for layout: $layoutFilePath")
                    )

                parseStringArrays(newStringsXml).forEach { (arrayName, items) ->
                    val result = AddStringArrayResourceCommand(
                        stringsFilePath = stringsFile.path,
                        name = arrayName,
                        items = items
                    ).execute()

                    if (!result.success) {
                        return@withContext Result.failure(
                            IllegalStateException(result.error_details ?: result.message)
                        )
                    }
                }

                Result.success(Unit)
            } catch (e: Exception) {
                log.error("String-array injection failed", e)
                Result.failure(e.toUserFacingError())
            }
        }

    private suspend fun findProjectStringsFile(): File? {
        val projectRootPath = IProjectManager.getInstance().projectDirPath
        return ProjectStringsXmlResolver.find(projectRootPath)
    }

    private fun parseStringArrays(newStringsXml: String): List<Pair<String, List<String>>> {
        val builder = newDocumentBuilder()
        val document = builder.parse(InputSource(StringReader("<wrapper>$newStringsXml</wrapper>")))
        val arrays = document.getElementsByTagName("string-array")

        return List(arrays.length) { index ->
            val arrayElement = arrays.item(index) as Element
            val arrayName = arrayElement.getAttribute("name")
            val items = arrayElement.getElementsByTagName("item")
            arrayName to List(items.length) { itemIndex -> items.item(itemIndex).textContent }
        }
    }

    private fun newDocumentBuilder(): DocumentBuilder {
        return DocumentBuilderFactory.newInstance().apply {
            setFeatureIfSupported("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeatureIfSupported("http://xml.org/sax/features/external-general-entities", false)
            setFeatureIfSupported("http://xml.org/sax/features/external-parameter-entities", false)
            isExpandEntityReferences = false
        }.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> InputSource(StringReader("")) }
        }
    }

    private fun DocumentBuilderFactory.setFeatureIfSupported(name: String, value: Boolean) {
        try {
            setFeature(name, value)
        } catch (_: ParserConfigurationException) {
            log.warn("XML parser does not support feature '{}'; continuing without it.", name)
        }
    }

    private fun Exception.toUserFacingError(): StringsInjectionException {
        val messageRes = when (this) {
            is FileNotFoundException -> R.string.msg_strings_injection_file_not_found
            is IllegalStateException -> R.string.msg_strings_injection_invalid_xml
            else -> R.string.msg_strings_injection_failed
        }
        return StringsInjectionException(messageRes, this)
    }
}

class StringsInjectionException(
    @StringRes val messageRes: Int,
    cause: Throwable? = null
) : Exception(null, cause)
