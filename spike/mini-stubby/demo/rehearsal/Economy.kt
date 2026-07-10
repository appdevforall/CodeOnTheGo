package app.payload

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

/** Game economy constants and the demand model. */
object Economy {
    const val START_CASH_CENTS = 2000
    const val INGREDIENT_COST_CENTS = 15
    const val SIGN_COST_CENTS = 20
    const val IDEAL_PRICE_CENTS = 25
    const val BROKE_THRESHOLD_CENTS = INGREDIENT_COST_CENTS * 5

    val WEATHERS = listOf("Sunny", "Hot & Dry", "Cloudy", "Rainy")

    fun weatherMultiplier(weather: String): Double = when (weather) {
        "Hot & Dry" -> 1.5
        "Sunny" -> 1.1
        "Cloudy" -> 0.75
        "Rainy" -> 0.4
        else -> 1.0
    }

    fun rollWeather(): String = WEATHERS[Random.nextInt(WEATHERS.size)]

    /**
     * Base demand from weather, shaped by a price-sensitivity curve (cheaper than
     * ideal sells more, pricier sells sharply less) and boosted by advertising.
     */
    fun computeDemand(weather: String, priceCents: Int, signs: Int): Int {
        val base = 30.0 * weatherMultiplier(weather)
        val safePrice = max(priceCents, 1).toDouble()
        val priceFactor = (IDEAL_PRICE_CENTS.toDouble() / safePrice).pow(1.6).coerceIn(0.15, 2.5)
        val adBoost = 1.0 + (min(signs, 10) * 0.12)
        return max(0, (base * priceFactor * adBoost).roundToInt())
    }

    fun formatCents(cents: Int): String {
        val sign = if (cents < 0) "-" else ""
        val whole = abs(cents) / 100
        val frac = (abs(cents) % 100).toString().padStart(2, '0')
        return "$sign\$$whole.$frac"
    }
}
