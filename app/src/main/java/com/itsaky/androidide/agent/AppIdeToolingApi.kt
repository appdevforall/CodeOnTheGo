package com.itsaky.androidide.agent

import android.content.Context
import android.os.BatteryManager
import com.itsaky.androidide.agent.api.IdeToolingApi
import com.itsaky.androidide.agent.model.ToolResult
import com.itsaky.androidide.agent.tool.ShellCommandRunner
import com.itsaky.androidide.agent.tool.TermuxShellCommandRunner
import com.itsaky.androidide.agent.tool.shell.ShellCommandResult
import com.itsaky.androidide.api.BuildOutputProvider
import com.itsaky.androidide.api.IDEApiFacade
import com.itsaky.androidide.api.commands.ReadFileCommand
import com.itsaky.androidide.app.IDEApplication
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

object AppIdeToolingApi : IdeToolingApi {
    private val shellRunner: ShellCommandRunner = TermuxShellCommandRunner()
    override fun createFile(path: String, content: String): ToolResult =
        IDEApiFacade.createFile(path, content)

    override fun readFile(path: String, offset: Int?, limit: Int?): ToolResult =
        IDEApiFacade.readFile(path, offset, limit)

    override fun readFileContent(path: String, offset: Int?, limit: Int?): Result<String> =
        ReadFileCommand(path, offset, limit).execute()

    override fun updateFile(path: String, content: String): ToolResult =
        IDEApiFacade.updateFile(path, content)

    override fun deleteFile(path: String): ToolResult =
        IDEApiFacade.deleteFile(path)

    override fun listFiles(path: String, recursive: Boolean): ToolResult =
        IDEApiFacade.listFiles(path, recursive)

    override fun searchProject(
        query: String,
        path: String?,
        maxResults: Int,
        ignoreCase: Boolean
    ): ToolResult = IDEApiFacade.searchProject(query, path, maxResults, ignoreCase)

    override fun addDependency(dependencyString: String, buildFilePath: String): ToolResult =
        IDEApiFacade.addDependency(dependencyString, buildFilePath)

    override fun addStringResource(name: String, value: String): ToolResult =
        IDEApiFacade.addStringResource(name, value)

    override suspend fun runApp(): ToolResult = IDEApiFacade.runApp()

    override suspend fun triggerGradleSync(): ToolResult = IDEApiFacade.triggerGradleSync()

    override fun getBuildOutput(): ToolResult = IDEApiFacade.getBuildOutput()

    override fun getBuildOutputContent(): String? = BuildOutputProvider.getBuildOutputContent()

    override fun getDeviceBattery(): ToolResult {
        val context = IDEApplication.instance
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val percentage =
            batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        return if (percentage in 0..100) {
            ToolResult.success(
                message = "Device battery is at $percentage%.",
                data = percentage.toString()
            )
        } else {
            ToolResult.failure("Unable to determine device battery level.")
        }
    }

    override fun getCurrentDateTime(): ToolResult {
        val zoneId = ZoneId.of("America/Guayaquil")
        val formatter = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy h:mm a", Locale.US)
        val formatted = ZonedDateTime.now(zoneId).format(formatter)
        return ToolResult.success(
            message = "The current date and time in Quito, Ecuador is $formatted.",
            data = formatted
        )
    }

    override fun getWeather(city: String?): ToolResult {
        val rawCity = city?.trim().orEmpty()
        if (rawCity.isEmpty()) {
            return ToolResult.failure("City must be provided to retrieve weather information.")
        }
        val displayName = rawCity.split(" ")
            .filter { it.isNotBlank() }
            .joinToString(" ") { part ->
                part.lowercase(Locale.getDefault()).replaceFirstChar { ch ->
                    if (ch.isLowerCase()) ch.titlecase(Locale.getDefault()) else ch.toString()
                }
            }
        val message = "The weather in $displayName is sunny and 25 C."
        return ToolResult.success(message = message)
    }

    override suspend fun executeShellCommand(command: String): ShellCommandResult =
        shellRunner.run(command)
}
