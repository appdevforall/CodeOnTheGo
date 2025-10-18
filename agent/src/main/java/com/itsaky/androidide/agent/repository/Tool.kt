package com.itsaky.androidide.agent.repository

import android.content.Context
import android.os.BatteryManager
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A generic interface for a tool the model can use.
 */
interface Tool {
    val name: String
    val description: String

    fun execute(context: Context, args: Map<String, String>): String
}

/**
 * An implementation of a tool that gets the device's battery level.
 * It doesn't use arguments, but conforms to the new interface.
 */
class BatteryTool : Tool {
    override val name: String = "get_device_battery"
    override val description: String = "Returns the current battery percentage of the device."

    override fun execute(context: Context, args: Map<String, String>): String {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val batteryPct =
            batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        return if (batteryPct in 0..100) {
            "[Tool Result for $name]: Device battery is at $batteryPct%."
        } else {
            "[Tool Result for $name]: Unable to determine battery level."
        }
    }
}

/**
 * NEW TOOL: Gets the current date and time.
 * This is a perfect example of a tool the agent can decide to call.
 */
class GetDateTimeTool : Tool {
    override val name: String = "get_current_datetime"
    override val description: String = "Returns the current date and time in Quito, Ecuador."

    override fun execute(context: Context, args: Map<String, String>): String {
        val zoneId = ZoneId.of("America/Guayaquil")
        val formatter = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy h:mm a", Locale.US)
        val formatted = ZonedDateTime.now(zoneId).format(formatter)
        return "[Tool Result for $name]: The current date and time is $formatted."
    }
}

/**
 * Simple weather example tool.
 */
class GetWeatherTool : Tool {
    override val name: String = "get_weather"
    override val description: String =
        "Gets the current weather for a specified city. Arguments: { \"city\": \"string\" }"

    override fun execute(context: Context, args: Map<String, String>): String {
        val city = args["city"].orEmpty().trim()
        return if (city.isEmpty()) {
            "[Tool Result for $name]: Error - City not specified."
        } else {
            "[Tool Result for $name]: The weather in $city is sunny and 25 C."
        }
    }
}
