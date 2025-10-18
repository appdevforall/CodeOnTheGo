package com.itsaky.androidide.agent.repository

// In a new file or alongside your other tool definitions
object LocalLlmTools {
    val allTools = listOf(
        LocalToolDeclaration(
            name = "create_file",
            description = "Creates a new file at the specified relative path. Creates parent directories if they don't exist.",
            parameters = mapOf(
                "path" to "The relative path from the project root (e.g., 'app/src/main/res/values/strings.xml').",
                "content" to "The initial string content of the file."
            )
        ),
        LocalToolDeclaration(
            name = "read_file",
            description = "Reads the content of a file. Use offset/limit to stream large files.",
            parameters = mapOf(
                "file_path" to "The relative path of the file to read.",
                "offset" to "Optional byte offset (defaults to 0).",
                "limit" to "Optional maximum number of bytes to read from the offset."
            )
        ),
        LocalToolDeclaration(
            name = "list_dir",
            description = "Lists files and directories within a given path.",
            parameters = mapOf(
                "path" to "The relative path of the directory to inspect.",
                "recursive" to "If true, lists files in all subdirectories as well."
            )
        ),
        LocalToolDeclaration(
            name = "search_project",
            description = "Searches the project files for a query and returns matching locations.",
            parameters = mapOf(
                "query" to "The literal text to search for.",
                "path" to "Optional relative directory to limit the search scope (defaults to project root).",
                "max_results" to "Optional maximum number of matches to return (defaults to 40).",
                "ignore_case" to "Set to false for case-sensitive search (defaults to true)."
            )
        ),
        LocalToolDeclaration(
            name = "run_app",
            description = "Installs and launches the successfully built application on the device. Should be called after a successful build.",
            parameters = emptyMap()
        ),
        LocalToolDeclaration(
            name = "add_dependency",
            description = "Adds a dependency string to the specified Gradle build file.",
            parameters = mapOf(
                "dependency_string" to "The full dependency line, including the configuration (e.g., 'implementation(\"io.coil-kt:coil-compose:2.6.0\")').",
                "build_file_path" to "The relative path to the build.gradle or build.gradle.kts file (e.g., 'app/build.gradle.kts')."
            )
        ),
        LocalToolDeclaration(
            name = "ask_user",
            description = "Displays a native dialog to the user with a question and a set of buttons as options.",
            parameters = mapOf(
                "question" to "The text to display to the user.",
                "options" to "A JSON array of strings, where each string is a button label."
            )
        ),
        LocalToolDeclaration(
            name = "update_file",
            description = "Updates an existing file by completely overwriting its content.",
            parameters = mapOf(
                "path" to "The relative path from the project root for the file to be updated.",
                "content" to "The complete new content to write to the file."
            )
        ),
        LocalToolDeclaration(
            name = "get_build_output",
            description = "Get the latest build output logs useful for errors or warnings.",
            parameters = emptyMap()
        ),
        LocalToolDeclaration(
            name = "add_string_resource",
            description = "Adds a new string resource to the strings.xml file.",
            parameters = mapOf(
                "name" to "The name for the string resource (e.g., 'welcome_message').",
                "value" to "The actual string content to be added (e.g., 'Hello, World!')."
            )
        )
    )
}
