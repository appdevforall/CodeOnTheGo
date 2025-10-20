package com.itsaky.androidide.agent.tool

import com.itsaky.androidide.agent.api.AgentDependencies
import com.itsaky.androidide.agent.model.ToolResult

/**
 * Reports the current device battery percentage.
 */
class GetDeviceBatteryHandler : ToolHandler {
    override val name: String = "get_device_battery"

    override suspend fun invoke(args: Map<String, Any?>): ToolResult {
        return AgentDependencies.requireToolingApi().getDeviceBattery()
    }
}

