package com.itsaky.androidide.data.repository

import com.google.firebase.ai.type.FunctionDeclaration
import com.google.firebase.ai.type.Schema

object GeminiTools {
    val allTools = listOf(
        FunctionDeclaration(
            name = "create_file",
            description = "Creates a new file at the specified relative path. Creates parent directories if they don't exist.",
            parameters = mapOf(
                "path" to Schema.string("The relative path from the project root (e.g., 'app/src/main/res/values/strings.xml')."),
                "content" to Schema.string("The initial string content of the file.")
            )
        ),
        FunctionDeclaration(
            name = "read_file",
            description = "Reads the entire content of a file.",
            parameters = mapOf(
                "path" to Schema.string("The relative path of the file to read.")
            )
        ),
        FunctionDeclaration(
            name = "list_files",
            description = "Lists all files and directories within a given path.",
            parameters = mapOf(
                "path" to Schema.string("The relative path of the directory to inspect."),
                "recursive" to Schema.boolean("If true, lists files in all subdirectories as well.")
            )
        ),

        FunctionDeclaration(
            name = "run_app",
            description = "Installs and launches the successfully built application on the device. Should be called after a successful build.",
            parameters = emptyMap()
        ),
        FunctionDeclaration(
            name = "add_dependency",
            description = "Adds a dependency string to the specified Gradle build file.",
            parameters = mapOf(
                "dependency_string" to Schema.string("The full dependency line (e.g., 'implementation(\"io.coil-kt:coil-compose:2.5.0\")')."),
                "build_file_path" to Schema.string("The path to the build.gradle.kts file (e.g., 'app/build.gradle.kts').")
            )
        ),

        // User Interaction
        FunctionDeclaration(
            name = "ask_user",
            description = "Displays a native dialog to the user with a question and a set of buttons as options. Waits for the user's selection.",
            parameters = mapOf(
                "question" to Schema.string("The text to display to the user."),
                "options" to Schema.array(Schema.string("A button label."))
            )
        ),
        FunctionDeclaration(
            name = "update_file",
            description = "Updates an existing file by completely overwriting its content. To use this, you should first read the file's current content, modify it as needed, and then provide the full, final content. This is suitable for changing single lines, multiple lines, or replacing the entire file.",
            parameters = mapOf(
                "path" to Schema.string("The relative path from the project root for the file to be updated (e.g., 'app/src/main/java/com/example/MyClass.kt')."),
                "content" to Schema.string("The complete new content to write to the file.")
            )
        ),
        FunctionDeclaration(
            name = "get_build_output",
            description = "Get the latest build output logs useful for errors or warnings.",
            parameters = emptyMap()
        )
    )
}