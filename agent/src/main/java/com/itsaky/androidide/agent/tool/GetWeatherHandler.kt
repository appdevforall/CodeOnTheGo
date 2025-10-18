package com.itsaky.androidide.agent.tool

import com.itsaky.androidide.agent.api.AgentDependencies
import com.itsaky.androidide.agent.model.GetWeatherArgs
import com.itsaky.androidide.agent.model.ToolResult

/**
 * Provides friendly weather information for a requested city.
 */
class GetWeatherHandler : ToolHandler {
    override val name: String = "get_weather"

    override suspend fun invoke(args: Map<String, Any?>): ToolResult {
        val toolArgs = decodeArgs<GetWeatherArgs>(args)
        return AgentDependencies.requireToolingApi().getWeather(toolArgs.city)
    }
}

