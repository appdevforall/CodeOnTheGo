package com.itsaky.androidide.api.commands

import com.blankj.utilcode.util.FileIOUtils
import com.itsaky.androidide.agent.model.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.EntityResolver
import org.xml.sax.InputSource
import java.io.File
import java.io.StringReader
import java.util.concurrent.ConcurrentHashMap
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import java.io.StringWriter

class AddStringArrayResourceCommand(
    private val stringsFilePath: String,
    private val name: String,
    private val items: List<String>
) : SuspendCommand<Unit> {

    companion object {
        private val log = LoggerFactory.getLogger(AddStringArrayResourceCommand::class.java)
        private val fileMutexes = ConcurrentHashMap<String, Mutex>()
    }

    override suspend fun execute(): ToolResult {
        if (name.isBlank()) {
            return ToolResult.failure("String-array name cannot be blank.")
        }

        val stringsFile = withContext(Dispatchers.IO) { File(stringsFilePath).canonicalFile }
        if (!stringsFile.exists() || !stringsFile.isFile) {
            return ToolResult.failure("strings.xml file not found at '$stringsFilePath'.")
        }

        val fileMutex = fileMutexes.computeIfAbsent(stringsFile.path) { Mutex() }

        return fileMutex.withLock {
            try {
                withContext(Dispatchers.IO) {
                    val currentContent = FileIOUtils.readFile2String(stringsFile)
                    val updatedContent = upsertStringArray(currentContent, name, items)

                    if (FileIOUtils.writeFileFromString(stringsFile, updatedContent)) {
                        ToolResult.success(
                            message = "Successfully added or updated string-array '$name'.",
                            data = "R.array.$name"
                        )
                    } else {
                        ToolResult.failure("Failed to write to strings.xml.")
                    }
                }
            } catch (e: Exception) {
                ToolResult.failure(
                    message = "An error occurred while adding or updating the string-array resource.",
                    error_details = e.message
                )
            }
        }
    }

    private fun upsertStringArray(currentContent: String, name: String, items: List<String>): String {
        val document = newDocumentBuilder()
            .parse(InputSource(StringReader(currentContent)))

        val resources = document.getElementsByTagName("resources").item(0) as? Element
            ?: throw IllegalStateException("The strings.xml file does not contain the <resources> tag")

        val newNode = document.createElement("string-array").apply {
            setAttribute("name", name)
            items.forEach { itemValue ->
                appendChild(document.createElement("item").apply {
                    appendChild(document.createTextNode(itemValue))
                })
            }
        }

        val existingNode = List(document.getElementsByTagName("string-array").length) { index ->
            document.getElementsByTagName("string-array").item(index) as Element
        }.firstOrNull { it.getAttribute("name") == name }

        if (existingNode != null) {
            existingNode.parentNode.replaceChild(newNode, existingNode)
        } else {
            appendWithIndentation(document, resources, newNode)
        }

        return serializeDocument(document)
    }

    private fun appendWithIndentation(document: Document, parent: Element, child: Element) {
        val closingIndentation = parent.lastChild
        val childIndentation = document.createTextNode("\n    ")

        if (closingIndentation != null && closingIndentation.isResourcesClosingIndentation()) {
            parent.insertBefore(childIndentation, closingIndentation)
            parent.insertBefore(child, closingIndentation)
        } else {
            parent.appendChild(childIndentation)
            parent.appendChild(child)
            parent.appendChild(document.createTextNode("\n"))
        }
    }

    private fun serializeDocument(document: Document): String {
        val transformer = TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.INDENT, "yes")
            setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4")
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
        }
        return StringWriter().also { writer ->
            transformer.transform(DOMSource(document), StreamResult(writer))
        }.toString()
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

    private fun Node.isResourcesClosingIndentation(): Boolean {
        return nodeType == Node.TEXT_NODE && textContent.contains('\n')
    }
}
