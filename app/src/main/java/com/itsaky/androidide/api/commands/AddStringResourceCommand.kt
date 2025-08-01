package com.itsaky.androidide.api.commands

import com.blankj.utilcode.util.FileIOUtils
import com.itsaky.androidide.data.model.ToolResult
import com.itsaky.androidide.projects.IProjectManager
import org.apache.commons.text.StringEscapeUtils
import java.io.File

/**
 * A command to add or update a string resource in the project's strings.xml file.
 */
class AddStringResourceCommand(
    private val name: String,
    private val value: String
) : Command<Unit> {
    override fun execute(): ToolResult {
        return try {
            val baseDir = IProjectManager.getInstance().projectDir
            // Standard path for the default strings.xml file.
            val stringsFile = File(baseDir, "app/src/main/res/values/strings.xml")

            if (!stringsFile.exists()) {
                return ToolResult.failure(
                    message = "strings.xml not found at the standard path: app/src/main/res/values/strings.xml"
                )
            }

            val content = FileIOUtils.readFile2String(stringsFile)

            // 1. Unescape Java-style sequences like \n from the AI's input into actual characters.**
            val unescapedValue = StringEscapeUtils.unescapeJava(value)

            // 2. Escape characters that are special within an XML value.
            val escapedValueForXml = unescapedValue
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("'", "\\'")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")

            val newStringElement = "\n    <string name=\"$name\">$escapedValueForXml</string>"

            // Regex to find an existing string resource with the same name, including surrounding whitespace.
            val searchRegex = Regex("""\s*<string name="$name">.*?</string>""")

            val newContent: String
            val wasUpdated: Boolean

            if (content.contains(searchRegex)) {
                // Key exists, so we replace the entire line.
                newContent = content.replace(searchRegex, newStringElement)
                wasUpdated = true
            } else {
                // Key doesn't exist, so we add it before </resources>.
                val closingTag = "</resources>"
                val closingTagIndex = content.lastIndexOf(closingTag)

                if (closingTagIndex == -1) {
                    return ToolResult.failure("Invalid strings.xml format: missing </resources> tag.")
                }

                // Add the new element with a newline for proper formatting.
                val elementToInsert = "$newStringElement\n"
                newContent =
                    StringBuilder(content).insert(closingTagIndex, elementToInsert).toString()
                wasUpdated = false
            }

            if (FileIOUtils.writeFileFromString(stringsFile, newContent)) {
                val message = if (wasUpdated) {
                    "Successfully updated string resource '$name'."
                } else {
                    "Successfully added string resource '$name'."
                }
                ToolResult.success(
                    message = message,
                    data = "R.string.$name" // Provide the resource reference back to the model.
                )
            } else {
                ToolResult.failure("Failed to write to strings.xml.")
            }
        } catch (e: Exception) {
            ToolResult.failure(
                message = "An error occurred while adding or updating the string resource.",
                error_details = e.message
            )
        }
    }
}