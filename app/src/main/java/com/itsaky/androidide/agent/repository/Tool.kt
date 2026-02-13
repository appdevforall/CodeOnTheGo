package com.itsaky.androidide.agent.repository

import android.content.Context
import android.os.BatteryManager
import com.itsaky.androidide.api.commands.ListFilesCommand
import com.itsaky.androidide.projects.IProjectManager
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * A generic interface for a tool the model can use.
 */
interface Tool {
    val name: String
    val description: String

    fun execute(context: Context, args: Map<String, String>): String
}

/**
 * An implementation of a tool that gets the device's battery level.
 * It doesn't use arguments, but conforms to the new interface.
 */
class BatteryTool : Tool {
    override val name: String = "get_device_battery"
    override val description: String = "Returns the current battery percentage of the device."

    override fun execute(context: Context, args: Map<String, String>): String {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryPct = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return "[Tool Result for $name]: Device battery is at $batteryPct%."
    }
}

/**
 * NEW TOOL: Gets the current date and time.
 * This is a perfect example of a tool the agent can decide to call.
 */
class GetDateTimeTool : Tool {
    override val name: String = "get_current_datetime"
    override val description: String = "Returns the current date and time in Quito, Ecuador."

    override fun execute(context: Context, args: Map<String, String>): String {
        val currentDateTime = LocalDateTime.now()
        val formatter = DateTimeFormatter.ofPattern("eeee, MMMM d, yyyy h:mm a")
        val formatted = currentDateTime.format(formatter)
        return "[Tool Result for $name]: The current date and time is $formatted."
    }
}

class ListFilesTool : Tool {
    override val name: String = "list_files"
    override val description: String =
        "Lists files and directories in the current project. Args: path (optional), recursive (optional)."

    override fun execute(context: Context, args: Map<String, String>): String {
        val path = args["path"].orEmpty()
        val recursive = args["recursive"]?.toBoolean() ?: false
        val result = try {
            val baseDir = IProjectManager.getInstance().projectDir.canonicalFile
            val targetDir = when (val sanitizedPath = path.trim()) {
                "", ".", "./" -> baseDir
                else -> File(baseDir, sanitizedPath).canonicalFile
            }
            val basePath = baseDir.path
            val targetPath = targetDir.path
            val isInside =
                targetPath == basePath || targetPath.startsWith(basePath + File.separator)
            if (!isInside) {
                return "Access denied: path outside project directory"
            }
            ListFilesCommand(path, recursive).execute()
        } catch (e: Exception) {
            return "Failed to list files: ${e.message}"
        }
        return if (result.success) {
            result.data?.ifBlank { result.message ?: "" } ?: result.message ?: ""
        } else {
            listOfNotNull(result.message, result.error_details).joinToString("\n")
        }
    }
}
