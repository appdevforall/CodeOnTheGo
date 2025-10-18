package com.itsaky.androidide.agent.tool.shell

import kotlin.math.max

object ShellCommandOutputFormatter {

    private const val MAX_OUTPUT_CHARS = 8_192
    private const val HEAD_CHARS = 3_072
    private const val TAIL_CHARS = 3_072

    data class FormattedOutput(
        val text: String,
        val truncated: Boolean
    )

    fun format(result: ShellCommandResult): FormattedOutput {
        result.sandboxFailureMessage?.let { message ->
            return FormattedOutput("failed in sandbox: $message", truncated = false)
        }

        val builder = StringBuilder()
        val stdout = result.stdout.trimEnd()
        val stderr = result.stderr.trimEnd()

        if (stdout.isNotEmpty()) {
            builder.append(stdout)
        }

        if (stderr.isNotEmpty()) {
            if (builder.isNotEmpty()) {
                builder.appendLine()
            }
            if (stdout.isNotEmpty()) {
                builder.append("stderr:\n")
            }
            builder.append(stderr)
        }

        if (builder.isEmpty()) {
            builder.append(
                if (result.exitCode == 0) {
                    "Command completed with no output."
                } else {
                    "Command exited with code ${result.exitCode}."
                }
            )
        }

        val combined = builder.toString()
        if (combined.length <= MAX_OUTPUT_CHARS) {
            return FormattedOutput(combined, truncated = false)
        }

        val head = combined.take(HEAD_CHARS)
        val tail = combined.takeLast(TAIL_CHARS)
        val omitted = max(0, combined.length - head.length - tail.length)
        val truncatedText = buildString {
            append(head.trimEnd())
            appendLine()
            appendLine("…")
            appendLine("[omitted $omitted characters]")
            append(tail.trimStart())
        }
        return FormattedOutput(truncatedText, truncated = true)
    }
}
