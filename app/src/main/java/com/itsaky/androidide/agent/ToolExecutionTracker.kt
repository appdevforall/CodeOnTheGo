package com.itsaky.androidide.agent

import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * A helper class to track tool usage and generate a formatted report.
 */
class ToolExecutionTracker {
    private val toolsUsed = mutableListOf<ToolCallLog>()
    private var operationStartTime = 0L

    data class ToolCallLog(val name: String, val durationMillis: Long, val timestamp: Long)

    fun startTracking() {
        toolsUsed.clear()
        operationStartTime = System.currentTimeMillis()
    }

    fun logToolCall(name: String, durationMillis: Long) {
        val timestamp = System.currentTimeMillis() - operationStartTime
        toolsUsed.add(ToolCallLog(name, durationMillis, timestamp))
    }

    fun generateReport(): String {
        if (toolsUsed.isEmpty()) {
            return "✅ **Operation Complete**\n\nNo tools were needed for this request."
        }

        val totalDuration = System.currentTimeMillis() - operationStartTime
        return buildReport("✅ **Operation Complete**", totalDuration)
    }

    fun generatePartialReport(): String {
        if (toolsUsed.isEmpty()) {
            return "🛑 **Operation Cancelled**\n\nNo tools were executed before cancellation."
        }
        val totalDuration = System.currentTimeMillis() - operationStartTime
        return buildReport("🛑 **Operation Cancelled**", totalDuration)
    }

    private fun buildReport(title: String, totalDuration: Long): String {
        val toolCounts = toolsUsed.groupingBy { it.name }.eachCount()

        val reportBuilder = StringBuilder("$title (Total: ${formatTime(totalDuration)})\n\n")
        reportBuilder.append("**Tool Execution Report:**\n")
        reportBuilder.append("Sequence:\n")
        toolsUsed.forEachIndexed { index, log ->
            reportBuilder.append(
                "${index + 1}. `${log.name}` (took ${formatTime(log.durationMillis)} at +${
                    formatTime(
                        log.timestamp
                    )
                })\n"
            )
        }

        reportBuilder.append("\nSummary:\n")
        toolCounts.forEach { (name, count) ->
            val times = if (count == 1) "1 time" else "$count times"
            reportBuilder.append("- `$name`: called $times\n")
        }

        return reportBuilder.toString()
    }

    private fun formatTime(millis: Long): String {
        if (millis < 0) return "0.0s"
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) - TimeUnit.MINUTES.toSeconds(minutes)
        val remainingMillis = millis % 1000
        val totalSeconds = seconds + (remainingMillis / 1000.0)
        return if (minutes > 0) {
            String.format(Locale.US, "%dm %.1fs", minutes, totalSeconds)
        } else {
            String.format(Locale.US, "%.1fs", totalSeconds)
        }
    }
}