package com.itsaky.androidide.agent.repository

import com.itsaky.androidide.agent.model.MyTool

object GeminiTools {
    val allMyTools: List<MyTool> = listOf(
        MyTool(
            name = "create_file",
            description = "Creates a new file at the specified relative path. Creates parent directories if they don't exist.",
            parameters = mapOf(
                "path" to "The relative path from the project root (e.g., 'app/src/main/res/values/strings.xml').",
                "content" to "The initial string content of the file."
            ),
        ),
        MyTool(
            name = "read_file",
            description = "Reads the entire content of a file.",
            parameters = mapOf(
                "path" to "The relative path of the file to read."
            )
        ),
        MyTool(
            name = "list_files",
            description = "Lists all files and directories within a given path.",
            parameters = mapOf(
                "path" to "The relative path of the directory to inspect.",
                "recursive" to "If true, lists files in all subdirectories as well."
            )
        ),

        MyTool(
            name = "run_app",
            description = "Installs and launches the successfully built application on the device. Should be called after a successful build.",
            parameters = emptyMap()

        ),
        MyTool(
            name = "add_dependency",
            description = "Adds a dependency string to the specified Gradle build file. This is the preferred way to add libraries to the project.",
            parameters = mapOf(
                "dependency_string" to "The full dependency line, including the configuration (e.g., 'implementation(\"io.coil-kt:coil-compose:2.6.0\")').",
                "build_file_path" to "The relative path to the build.gradle or build.gradle.kts file (e.g., 'app/build.gradle.kts'). If left empty, it defaults to 'app/build.gradle.kts'."
            )
        ),

        // User Interaction
        MyTool(
            name = "ask_user",
            description = "Creates a new Displays a native dialog to the user with a question and a set of buttons as options. Waits for the user's selection. at the specified relative path. Creates parent directories if they don't exist.",
            parameters = mapOf(
                "question" to "The text to display to the user.",
                "options" to "An array of button labels."
            )
        ),
        MyTool(
            name = "update_file",
            description = "Updates an existing file by completely overwriting its content. To use this, you should first read the file's current content, modify it as needed, and then provide the full, final content. This is suitable for changing single lines, multiple lines, or replacing the entire file.",
            parameters = mapOf(
                "path" to "The relative path from the project root for the file to be updated (e.g., 'app/src/main/java/com/example/MyClass.kt').",
                "content" to "The complete new content to write to the file."
            )
        ),
        MyTool(
            name = "get_build_output",
            description = "Get the latest build output logs useful for errors or warnings.",
            parameters = emptyMap()
        ),

        MyTool(
            name = "add_string_resource",
            description = "Adds a new string resource to the strings.xml file. This is the preferred way to handle strings, as it avoids escaping issues.",
            parameters = mapOf(
                "name" to "The name for the string resource (e.g., 'welcome_message'). This will be used as the resource ID.",
                "value" to "The actual string content to be added (e.g., 'Hello, World!')."
            )
        )
    )

    fun getToolDeclarationsAsJson(): String {
        return allMyTools.joinToString(prefix = "[", postfix = "]", separator = ",\n") { tool ->
            """
                {
                  "name": "${tool.name}",
                  "description": "${tool.description}"
                  "parameters": "${tool.parameters.map { it.key + ": " + it.value + "\n" }}"
                }
                """.trimIndent()
        }
    }
}