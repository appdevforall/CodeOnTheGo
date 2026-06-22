package com.itsaky.androidide.utils

import androidx.annotation.StringRes
import com.blankj.utilcode.util.FileIOUtils
import com.itsaky.androidide.R
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
 * Parses generated string-array XML and directly updates strings.xml file.
 * Previously delegated to [AddStringArrayResourceCommand] which has been removed.
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
                    try {
                        addStringArrayToFile(stringsFile, arrayName, items)
                    } catch (e: Exception) {
                        return@withContext Result.failure(
                            IllegalStateException("Failed to add string array: ${e.message}", e)
                        )
                    }
                }

                Result.success(Unit)
            } catch (e: Exception) {
                log.error("String-array injection failed", e)
                Result.failure(e.toUserFacingError())
            }
        }

    private fun addStringArrayToFile(file: File, arrayName: String, items: List<String>) {
        val content = FileIOUtils.readFile2String(file)
        val builder = newDocumentBuilder()
        val document = builder.parse(InputSource(StringReader(content)))
        val root = document.documentElement

        // Create the string-array element
        val newArrayElement = document.createElement("string-array")
        newArrayElement.setAttribute("name", arrayName)

        items.forEach { item ->
            val itemElement = document.createElement("item")
            itemElement.textContent = item
            newArrayElement.appendChild(itemElement)
        }

        root.appendChild(newArrayElement)

        // Convert back to string and write
        val transformer = javax.xml.transform.TransformerFactory.newInstance().newTransformer()
        transformer.setOutputProperty(javax.xml.transform.OutputKeys.INDENT, "yes")
        val stringWriter = java.io.StringWriter()
        transformer.transform(javax.xml.transform.dom.DOMSource(document), javax.xml.transform.stream.StreamResult(stringWriter))

        FileIOUtils.writeFileFromString(file, stringWriter.toString())
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
