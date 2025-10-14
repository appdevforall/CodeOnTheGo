package com.itsaky.androidide.agent.repository

import android.content.Context
import android.os.BatteryManager
import kotlinx.serialization.json.JsonElement
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * A generic interface for a tool the model can use.
 */
interface Tool {
    val name: String
    val description: String

    fun execute(context: Context, args: Map<String, JsonElement>): String
}

/**
 * An implementation of a tool that gets the device's battery level.
 * It doesn't use arguments, but conforms to the new interface.
 */
class BatteryTool : Tool {
    override val name: String = "get_device_battery"
    override val description: String = "Returns the current battery percentage of the device."

    override fun execute(context: Context, args: Map<String, JsonElement>): String {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryPct = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return "[Tool Result for $name]: Device battery is at $batteryPct%."
    }
}

/**
 * NEW TOOL: Gets the current date and time.
 * This is a perfect example of a tool the agent can decide to call.
 */
class GetDateTimeTool : Tool {
    override val name: String = "get_current_datetime"
    override val description: String = "Returns the current date and time in Quito, Ecuador."

    override fun execute(context: Context, args: Map<String, JsonElement>): String {
        val currentDateTime = LocalDateTime.now()
        val formatter = DateTimeFormatter.ofPattern("eeee, MMMM d, yyyy h:mm a")
        val formatted = currentDateTime.format(formatter)
        return "[Tool Result for $name]: The current date and time is $formatted."
    }
}
