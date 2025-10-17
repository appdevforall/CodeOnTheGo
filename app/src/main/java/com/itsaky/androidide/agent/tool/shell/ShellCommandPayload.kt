package com.itsaky.androidide.agent.tool.shell

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ShellCommandPayload(
    val command: String,
    val argv: List<String>,
    @SerialName("parsed_command")
    val parsedCommand: ParsedCommand,
    val stdout: String,
    val stderr: String,
    @SerialName("exit_code")
    val exitCode: Int,
    @SerialName("working_directory")
    val workingDirectory: String? = null,
    @SerialName("formatted_output")
    val formattedOutput: String,
    @SerialName("truncated")
    val truncated: Boolean,
    @SerialName("sandbox_failure")
    val sandboxFailureMessage: String? = null,
    @SerialName("duration_millis")
    val durationMillis: Long = 0L
)
