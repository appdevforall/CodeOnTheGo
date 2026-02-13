

package com.itsaky.androidide.utils

import com.itsaky.androidide.lsp.models.DiagnosticItem
import com.itsaky.androidide.lsp.models.DiagnosticSeverity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DiagnosticsFormatter {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun format(diagnostics: Map<File, List<DiagnosticItem>>): String {
        if (diagnostics.isEmpty()) return "No diagnostics"

        val builder = StringBuilder()
        builder.appendLine("=== Diagnostics Report ===")
        builder.appendLine("Generated: ${dateFormat.format(Date())}")
        builder.appendLine()

        var totalErrors = 0
        var totalWarnings = 0

        for ((file, items) in diagnostics.entries.sortedBy { it.key.name }) {
            if (items.isEmpty()) continue

            val errors = items.count { it.severity == DiagnosticSeverity.ERROR }
            val warnings = items.count { it.severity == DiagnosticSeverity.WARNING }
            totalErrors += errors
            totalWarnings += warnings

            builder.appendLine("--- ${file.name} ($errors errors, $warnings warnings) ---")
            builder.appendLine("Path: ${file.absolutePath}")
            builder.appendLine()

            for (item in items.sortedBy { it.range.start.line }) {
                val severity = when (item.severity) {
                    DiagnosticSeverity.ERROR -> "ERROR"
                    DiagnosticSeverity.WARNING -> "WARNING"
                    DiagnosticSeverity.INFO -> "INFO"
                    DiagnosticSeverity.HINT -> "HINT"
                }

                val line = item.range.start.line + 1
                val column = item.range.start.column + 1

                builder.appendLine("  [$severity] Line $line:$column")
                builder.appendLine("    ${item.message}")
                if (item.code.isNotEmpty()) {
                    builder.appendLine("    Code: ${item.code}")
                }
                builder.appendLine()
            }
        }

        builder.appendLine("=== Summary ===")
        builder.appendLine("Total: $totalErrors errors, $totalWarnings warnings")
        builder.appendLine("Files: ${diagnostics.count { it.value.isNotEmpty() }}")

        return builder.toString()
    }
}
