package com.itsaky.androidide.agent.repository

import android.content.Context

class GetWeatherTool : Tool {
    override val name: String = "get_weather"

    override val description: String =
        "Gets the current weather for a specified city. Arguments: { \"city\": \"string\" }"

    override fun execute(context: Context, args: Map<String, String>): String {
        // Safely extract the 'city' argument from the map.
        val city = args["city"] as? String

        return if (city.isNullOrBlank()) {
            "[Tool Result for $name]: Error - City not specified."
        } else {
            // In a real app, you would call a weather API here.
            // For this example, we'll just return a hardcoded, friendly string.
            "[Tool Result for $name]: The weather in $city is sunny and 25°C."
        }
    }
}
