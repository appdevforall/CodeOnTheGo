package app.payload

import android.content.SharedPreferences

/** Which of the per-day screens (or the leaderboard) is currently shown. */
enum class Phase { FORECAST, DECISIONS, RESULT, LEADERBOARD }

private object Keys {
    const val DAY = "day"
    const val CASH_CENTS = "cash_cents"
    const val PHASE = "phase"
    const val WEATHER = "weather"
    const val MADE = "made"
    const val PRICE_CENTS = "price_cents"
    const val SIGNS = "signs"
    const val RES_SOLD = "res_sold"
    const val RES_REVENUE = "res_revenue_cents"
    const val RES_COSTS = "res_costs_cents"
    const val RES_PROFIT = "res_profit_cents"
    const val RES_WEATHER = "res_weather"
    const val RES_MADE = "res_made"
    const val PROFIT_HISTORY = "profit_history_cents"
    const val LEADERBOARD = "leaderboard"
}

/** One leaderboard entry: a peak cash total, and the day it was reached on. */
data class LeaderboardEntry(val scoreCents: Int, val day: Int) {
    fun encode(): String = "$scoreCents:$day"

    companion object {
        fun decode(raw: String): LeaderboardEntry? {
            val parts = raw.split(":")
            if (parts.size != 2) return null
            val score = parts[0].toIntOrNull() ?: return null
            val day = parts[1].toIntOrNull() ?: return null
            return LeaderboardEntry(score, day)
        }
    }
}

/**
 * The entire game's persisted state. All fields are written to SharedPreferences
 * on every transition and re-read at the top of Main.render(), so a hot-reload
 * (which rebuilds the whole UI) never loses progress.
 */
data class GameState(
    val day: Int,
    val cashCents: Int,
    val phase: Phase,
    val weather: String,
    val made: Int,
    val priceCents: Int,
    val signs: Int,
    val resMade: Int,
    val resSold: Int,
    val resRevenueCents: Int,
    val resCostsCents: Int,
    val resProfitCents: Int,
    val resWeather: String,
    /** Profit (in cents) from the last few completed days, oldest first; capped at 3 entries. */
    val profitHistoryCents: List<Int> = emptyList(),
    /** All-time best cash-on-hand totals reached, highest first; capped at [LEADERBOARD_MAX]. */
    val leaderboard: List<LeaderboardEntry> = emptyList(),
) {
    fun save(prefs: SharedPreferences) {
        prefs.edit()
            .putInt(Keys.DAY, day)
            .putInt(Keys.CASH_CENTS, cashCents)
            .putString(Keys.PHASE, phase.name)
            .putString(Keys.WEATHER, weather)
            .putInt(Keys.MADE, made)
            .putInt(Keys.PRICE_CENTS, priceCents)
            .putInt(Keys.SIGNS, signs)
            .putInt(Keys.RES_MADE, resMade)
            .putInt(Keys.RES_SOLD, resSold)
            .putInt(Keys.RES_REVENUE, resRevenueCents)
            .putInt(Keys.RES_COSTS, resCostsCents)
            .putInt(Keys.RES_PROFIT, resProfitCents)
            .putString(Keys.RES_WEATHER, resWeather)
            .putString(Keys.PROFIT_HISTORY, profitHistoryCents.joinToString(","))
            .putString(Keys.LEADERBOARD, leaderboard.joinToString(",") { it.encode() })
            .apply()
    }

    companion object {
        private const val PROFIT_HISTORY_MAX = 3
        private const val LEADERBOARD_MAX = 10

        /** Appends a completed day's profit, keeping only the most recent [PROFIT_HISTORY_MAX]. */
        fun appendProfitHistory(history: List<Int>, profitCents: Int): List<Int> =
            (history + profitCents).takeLast(PROFIT_HISTORY_MAX)

        /**
         * Records [cashCents] on the leaderboard if it's a new high score (or the board isn't
         * full yet), keeping only the top [LEADERBOARD_MAX] entries, highest score first.
         */
        fun maybeRecordHighScore(
            leaderboard: List<LeaderboardEntry>,
            cashCents: Int,
            day: Int,
        ): List<LeaderboardEntry> {
            val currentBest = leaderboard.maxOfOrNull { it.scoreCents }
            if (leaderboard.size >= LEADERBOARD_MAX && currentBest != null && cashCents <= currentBest) {
                return leaderboard
            }
            return (leaderboard + LeaderboardEntry(cashCents, day))
                .sortedByDescending { it.scoreCents }
                .take(LEADERBOARD_MAX)
        }

        fun load(prefs: SharedPreferences): GameState {
            val phaseStr = prefs.getString(Keys.PHASE, Phase.FORECAST.name) ?: Phase.FORECAST.name
            val phase = try {
                Phase.valueOf(phaseStr)
            } catch (e: IllegalArgumentException) {
                Phase.FORECAST
            }
            val historyStr = prefs.getString(Keys.PROFIT_HISTORY, "") ?: ""
            val profitHistoryCents = if (historyStr.isBlank()) {
                emptyList()
            } else {
                historyStr.split(",").mapNotNull { it.toIntOrNull() }
            }
            val leaderboardStr = prefs.getString(Keys.LEADERBOARD, "") ?: ""
            val leaderboard = if (leaderboardStr.isBlank()) {
                emptyList()
            } else {
                leaderboardStr.split(",").mapNotNull { LeaderboardEntry.decode(it) }
            }
            return GameState(
                day = prefs.getInt(Keys.DAY, 1),
                cashCents = prefs.getInt(Keys.CASH_CENTS, Economy.START_CASH_CENTS),
                phase = phase,
                weather = prefs.getString(Keys.WEATHER, "") ?: "",
                made = prefs.getInt(Keys.MADE, 20),
                priceCents = prefs.getInt(Keys.PRICE_CENTS, Economy.IDEAL_PRICE_CENTS),
                signs = prefs.getInt(Keys.SIGNS, 0),
                resMade = prefs.getInt(Keys.RES_MADE, 0),
                resSold = prefs.getInt(Keys.RES_SOLD, 0),
                resRevenueCents = prefs.getInt(Keys.RES_REVENUE, 0),
                resCostsCents = prefs.getInt(Keys.RES_COSTS, 0),
                resProfitCents = prefs.getInt(Keys.RES_PROFIT, 0),
                resWeather = prefs.getString(Keys.RES_WEATHER, "") ?: "",
                profitHistoryCents = profitHistoryCents,
                leaderboard = leaderboard,
            )
        }
    }
}
