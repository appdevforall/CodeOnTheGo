package com.itsaky.androidide.agent.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CreateFileArgs(
    val path: String = "",
    val content: String = ""
)

@Serializable
data class ReadFileArgs(
    val path: String = ""
)

@Serializable
data class UpdateFileArgs(
    val path: String = "",
    val content: String = ""
)

@Serializable
data class DeleteFileArgs(
    val path: String = ""
)

@Serializable
data class ListFilesArgs(
    val path: String = "",
    val recursive: Boolean = false
)

@Serializable
data class AddDependencyArgs(
    val dependency: String = "",
    @SerialName("build_file_path")
    val buildFilePath: String = ""
)

@Serializable
data class AddStringResourceArgs(
    val name: String = "",
    val value: String = ""
)

@Serializable
data class ReadMultipleFilesArgs(
    val paths: List<String> = emptyList()
)

@Serializable
data class ShellCommandArgs(
    val command: String = "",
    @SerialName("timeout_seconds")
    val timeoutSeconds: Long? = null
)

@Serializable
data class SearchProjectArgs(
    val query: String = "",
    val path: String? = null,
    @SerialName("max_results")
    val maxResults: Int = 40,
    @SerialName("ignore_case")
    val ignoreCase: Boolean = true
)
