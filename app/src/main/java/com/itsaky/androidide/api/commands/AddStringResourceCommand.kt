package com.itsaky.androidide.api.commands

import com.blankj.utilcode.util.FileIOUtils
import com.itsaky.androidide.data.model.ToolResult
import com.itsaky.androidide.projects.IProjectManager
import java.io.File

/**
 * A command to add a new string resource to the project's strings.xml file.
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
            val closingTag = "</resources>"
            val closingTagIndex = content.lastIndexOf(closingTag)

            if (closingTagIndex == -1) {
                return ToolResult.failure("Invalid strings.xml format: missing </resources> tag.")
            }

            // Escape characters that need escaping inside an XML string tag.
            val escapedValue = value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("'", "\\'")
                .replace("\"", "\\\"")

            val newStringElement = "    <string name=\"$name\">$escapedValue</string>\n"

            val newContent =
                StringBuilder(content).insert(closingTagIndex, newStringElement).toString()

            if (FileIOUtils.writeFileFromString(stringsFile, newContent)) {
                ToolResult.success(
                    message = "Successfully added string resource '$name'.",
                    data = "R.string.$name" // Provide the resource reference back to the model.
                )
            } else {
                ToolResult.failure("Failed to write to strings.xml.")
            }
        } catch (e: Exception) {
            ToolResult.failure(
                message = "An error occurred while adding the string resource.",
                error_details = e.message
            )
        }
    }
}