package com.itsaky.androidide.agent.tool.shell

data class ShellCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val workingDirectory: String? = null,
    val sandboxFailureMessage: String? = null,
    val durationMillis: Long = 0L
) {
    val isSandboxFailure: Boolean
        get() = !sandboxFailureMessage.isNullOrBlank()

    val isSuccess: Boolean
        get() = !isSandboxFailure && exitCode == 0
}
