package com.itsaky.androidide.agent.repository

import com.google.genai.types.FunctionDeclaration
import com.google.genai.types.Schema
import com.google.genai.types.Tool
import com.google.genai.types.Type

/**
 * Contains all FunctionDeclarations and the final Tool list for the agent,
 * defined using a clean, map-based Kotlin style.
 */

private val createFile = FunctionDeclaration.builder()
    .name("create_file")
    .description("Creates a new file at the specified relative path. Creates parent directories if they don't exist.")
    .parameters(
        Schema.builder()
            .type(Type.Known.OBJECT)
            .properties(
                mapOf(
                    "path" to Schema.builder()
                        .type(Type.Known.STRING)
                        .description("The relative path from the project root (e.g., 'app/src/main/res/values/strings.xml').")
                        .build(),
                    "content" to Schema.builder()
                        .type(Type.Known.STRING)
                        .description("The initial string content of the file.")
                        .build()
                )
            )
            .required(listOf("path", "content"))
            .build()
    )
    .build()

private val readFile = FunctionDeclaration.builder()
    .name("read_file")
    .description("Reads the entire content of a file.")
    .parameters(
        Schema.builder()
            .type(Type.Known.OBJECT)
            .properties(
                mapOf(
                    "path" to Schema.builder()
                        .type(Type.Known.STRING)
                        .description("The relative path of the file to read.")
                        .build()
                )
            )
            .required(listOf("path"))
            .build()
    )
    .build()

private val updateFile = FunctionDeclaration.builder()
    .name("update_file")
    .description("Overwrites the content of an existing file. Creates the file if it doesn't exist.")
    .parameters(
        Schema.builder()
            .type(Type.Known.OBJECT)
            .properties(
                mapOf(
                    "path" to Schema.builder()
                        .type(Type.Known.STRING)
                        .description("The relative path of the file to update.")
                        .build(),
                    "content" to Schema.builder()
                        .type(Type.Known.STRING)
                        .description("The new content to write to the file.")
                        .build()
                )
            )
            .required(listOf("path", "content"))
            .build()
    )
    .build()

private val deleteFile = FunctionDeclaration.builder()
    .name("delete_file")
    .description("Deletes a file or an empty directory.")
    .parameters(
        Schema.builder()
            .type(Type.Known.OBJECT)
            .properties(
                mapOf(
                    "path" to Schema.builder()
                        .type(Type.Known.STRING)
                        .description("The relative path of the file or directory to delete.")
                        .build()
                )
            )
            .required(listOf("path"))
            .build()
    )
    .build()

private val listFiles = FunctionDeclaration.builder()
    .name("list_files")
    .description("Lists all files and directories within a given path.")
    .parameters(
        Schema.builder()
            .type(Type.Known.OBJECT)
            .properties(
                mapOf(
                    "path" to Schema.builder()
                        .type(Type.Known.STRING)
                        .description("The relative path of the directory to list. Use '.' for the project root.")
                        .build(),
                    "recursive" to Schema.builder()
                        .type(Type.Known.BOOLEAN)
                        .description("Set to true to list files in all subdirectories.")
                        .build()
                )
            )
            .required(listOf("path"))
            .build()
    )
    .build()

private val readMultipleFiles = FunctionDeclaration.builder()
    .name("read_multiple_files")
    .description("Reads the content of multiple files at once.")
    .parameters(
        Schema.builder()
            .type(Type.Known.OBJECT)
            .properties(
                mapOf(
                    "paths" to Schema.builder()
                        .type(Type.Known.ARRAY)
                        .items(Schema.builder().type(Type.Known.STRING).build())
                        .description("A list of relative file paths to read.")
                        .build()
                )
            )
            .required(listOf("paths"))
            .build()
    )
    .build()

private val addDependency = FunctionDeclaration.builder()
    .name("add_dependency")
    .description("Adds a new dependency to a Gradle build file.")
    .parameters(
        Schema.builder()
            .type(Type.Known.OBJECT)
            .properties(
                mapOf(
                    "dependency" to Schema.builder()
                        .type(Type.Known.STRING)
                        .description("The full dependency string (e.g., 'androidx.core:core-ktx:1.9.0').")
                        .build(),
                    "build_file_path" to Schema.builder()
                        .type(Type.Known.STRING)
                        .description("The relative path to the build file (e.g., 'app/build.gradle.kts').")
                        .build()
                )
            )
            .required(listOf("dependency", "build_file_path"))
            .build()
    )
    .build()

private val addStringResource = FunctionDeclaration.builder()
    .name("add_string_resource")
    .description("Adds a new string resource to the app's strings.xml file.")
    .parameters(
        Schema.builder()
            .type(Type.Known.OBJECT)
            .properties(
                mapOf(
                    "name" to Schema.builder()
                        .type(Type.Known.STRING)
                        .description("The name of the string resource (e.g., 'welcome_message').")
                        .build(),
                    "value" to Schema.builder()
                        .type(Type.Known.STRING)
                        .description("The content of the string (e.g., 'Hello World!').")
                        .build()
                )
            )
            .required(listOf("name", "value"))
            .build()
    )
    .build()

private val runApp = FunctionDeclaration.builder()
    .name("run_app")
    .description("Builds the application and runs it on a connected device or emulator. Use this as the final step to verify changes.")
    .build()

private val triggerGradleSync = FunctionDeclaration.builder()
    .name("trigger_gradle_sync")
    .description("Triggers a Gradle Sync for the project. This is required after modifying build files like adding dependencies.")
    .build()

private val getBuildOutput = FunctionDeclaration.builder()
    .name("get_build_output")
    .description("Retrieves the latest logs from the build system output. Useful for debugging build failures.")
    .build()

// Create the final list of Tool objects that the Gemini client will use
val allAgentTools: List<Tool> = listOf(
    Tool.builder().functionDeclarations(createFile).build(),
    Tool.builder().functionDeclarations(readFile).build(),
    Tool.builder().functionDeclarations(updateFile).build(),
    Tool.builder().functionDeclarations(deleteFile).build(),
    Tool.builder().functionDeclarations(listFiles).build(),
    Tool.builder().functionDeclarations(readMultipleFiles).build(),
    Tool.builder().functionDeclarations(addDependency).build(),
    Tool.builder().functionDeclarations(addStringResource).build(),
    Tool.builder().functionDeclarations(runApp).build(),
    Tool.builder().functionDeclarations(triggerGradleSync).build(),
    Tool.builder().functionDeclarations(getBuildOutput).build()
)

val agentToolRequiredArgs: Map<String, List<String>> = mapOf(
    "create_file" to listOf("path", "content"),
    "read_file" to listOf("path"),
    "update_file" to listOf("path", "content"),
    "delete_file" to listOf("path"),
    "list_files" to listOf("path"),
    "read_multiple_files" to listOf("paths"),
    "add_dependency" to listOf("dependency", "build_file_path"),
    "add_string_resource" to listOf("name", "value"),
    "run_app" to emptyList(),
    "trigger_gradle_sync" to emptyList(),
    "get_build_output" to emptyList()
)
