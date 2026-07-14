package app.payload

import android.app.Activity
import android.content.SharedPreferences
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lemonade Stand.
 *
 * Entry point contract the shell relies on (keep EXACTLY):
 *   object Main { @JvmStatic fun render(host: Activity): View }
 * All game state lives in host.getSharedPreferences("game", 0) and is re-read
 * at the top of render()/rebuild(), so a hot-reload never loses progress.
 */
object Main {

    private const val BG = 0xFFEAF7EF.toInt()
    private const val FG = 0xFF1F4037.toInt()
    private const val CARD_BG = 0xFFFFFFFF.toInt()
    private const val ACCENT = 0xFF2FB86B.toInt()
    private const val ACCENT_DARK = 0xFF1E9D57.toInt()
    private const val MUTED = 0xFF7A8F86.toInt()
    private const val POSITIVE = 0xFF2E9E6B.toInt()
    private const val NEGATIVE = 0xFFE0684F.toInt()
    private const val FIELD_BG = 0xFFF1F7F0.toInt()

    private val WEATHERS = listOf("Sunny", "Hot", "Scorcher", "Cloudy", "Rainy")

    private fun weatherMultiplier(weather: String): Double = when (weather) {
        "Sunny" -> 1.0
        "Hot" -> 1.4
        "Scorcher" -> 1.8
        "Cloudy" -> 0.7
        "Rainy" -> 0.35
        else -> 1.0
    }

    private fun weatherDesc(weather: String): String = when (weather) {
        "Sunny" -> "Sunny ☀️ - good day for lemonade"
        "Hot" -> "Hot 🔥 - thirsty crowds"
        "Scorcher" -> "Scorcher 🥵 - everyone wants a drink!"
        "Cloudy" -> "Cloudy ☁️ - fewer customers"
        "Rainy" -> "Rainy 🌧️ - nobody's out"
        else -> weather
    }

    @JvmStatic
    fun render(host: Activity): View {
        val prefs = host.getSharedPreferences("game", 0)

        val content = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
        }

        val scroll = ScrollView(host).apply {
            setBackgroundColor(BG)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            setPadding(40, 96, 40, 64)
            addView(content)
        }

        rebuild(content, host, prefs)
        return scroll
    }

    private fun loadLeaderboard(prefs: SharedPreferences): List<Pair<Int, Int>> {
        val raw = prefs.getString("leaderboard_json", "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                obj.getInt("day") to obj.getInt("cash")
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun recordLeaderboardScore(prefs: SharedPreferences, day: Int, cashCents: Int) {
        val entries = loadLeaderboard(prefs).toMutableList()
        entries.add(day to cashCents)
        val top = entries.sortedByDescending { it.second }.take(10)
        val arr = JSONArray()
        for ((d, c) in top) {
            arr.put(JSONObject().apply {
                put("day", d)
                put("cash", c)
            })
        }
        prefs.edit().putString("leaderboard_json", arr.toString()).apply()
    }

    private fun rebuild(root: LinearLayout, host: Activity, prefs: SharedPreferences) {
        root.removeAllViews()

        // --- read/init persisted state ---
        val day = prefs.getInt("day", 1)
        val cashCents = prefs.getInt("cash_cents", 2000)
        var weather = prefs.getString("weather", "") ?: ""
        val phase = prefs.getString("phase", "input") ?: "input"
        val viewingLeaderboard = prefs.getBoolean("viewing_leaderboard", false)

        if (weather.isEmpty()) {
            weather = WEATHERS[Random.nextInt(WEATHERS.size)]
            prefs.edit().putString("weather", weather).apply()
        }

        if (viewingLeaderboard) {
            root.addView(leaderboardCard(host, loadLeaderboard(prefs)))
            root.addView(backButton(host) {
                prefs.edit().putBoolean("viewing_leaderboard", false).apply()
                rebuild(root, host, prefs)
            })
            return
        }

        val glassesDefault = prefs.getString("glasses_input", "20") ?: "20"
        val priceDefault = prefs.getString("price_input", "25") ?: "25"
        val signsDefault = prefs.getString("signs_input", "0") ?: "0"

        // --- header (always shown) ---
        val profitHistory = (prefs.getString("profit_history", "") ?: "")
            .split(",")
            .mapNotNull { it.toIntOrNull() }
        root.addView(dashboardCard(host, day, cashCents, profitHistory))
        root.addView(headerCard(host, day, cashCents, weather))
        root.addView(leaderboardButton(host) {
            prefs.edit().putBoolean("viewing_leaderboard", true).apply()
            rebuild(root, host, prefs)
        })

        if (phase == "result") {
            root.addView(resultCard(host, prefs))
            root.addView(nextDayButton(host, prefs) {
                rebuild(root, host, prefs)
            })
        } else {
            val glassesInput = EditText(host)
            val priceInput = EditText(host)
            val signsInput = EditText(host)

            root.addView(inputsCard(host, glassesInput, priceInput, signsInput, glassesDefault, priceDefault, signsDefault))
            root.addView(sellButton(host, prefs, glassesInput, priceInput, signsInput, weather, cashCents) {
                rebuild(root, host, prefs)
            })
        }
    }

    private fun sectionCard(host: Activity): LinearLayout {
        return LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(CARD_BG)
            setPadding(32, 28, 32, 28)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = 24
            }
        }
    }

    private fun dashboardCard(host: Activity, day: Int, cashCents: Int, profitHistory: List<Int>): View {
        val card = sectionCard(host)

        card.addView(TextView(host).apply {
            text = "📊 Dashboard"
            textSize = 15f
            setTextColor(MUTED)
        })

        val statsRow = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = 12
            }
        }
        statsRow.addView(dashboardStat(host, "Day", "$day"))
        statsRow.addView(dashboardStat(host, "Cash", "$${"%.2f".format(cashCents / 100.0)}"))
        val yesterdayProfitCents = profitHistory.lastOrNull()
        statsRow.addView(
            dashboardStat(
                host,
                "Yesterday",
                if (yesterdayProfitCents == null) {
                    "—"
                } else {
                    (if (yesterdayProfitCents < 0) "-$" else "+$") + "%.2f".format(kotlin.math.abs(yesterdayProfitCents) / 100.0)
                },
                if (yesterdayProfitCents == null) MUTED else if (yesterdayProfitCents >= 0) POSITIVE else NEGATIVE,
            )
        )
        card.addView(statsRow)

        if (profitHistory.isNotEmpty()) {
            card.addView(TextView(host).apply {
                text = "Trend (last ${profitHistory.size} day${if (profitHistory.size == 1) "" else "s"})"
                textSize = 12f
                setTextColor(MUTED)
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    topMargin = 18
                }
            })
            card.addView(trendRow(host, profitHistory))
        }

        return card
    }

    private fun dashboardStat(host: Activity, label: String, value: String, valueColor: Int = FG): LinearLayout {
        val col = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        col.addView(TextView(host).apply {
            text = label
            textSize = 12f
            setTextColor(MUTED)
            gravity = Gravity.CENTER_HORIZONTAL
        })
        col.addView(TextView(host).apply {
            text = value
            textSize = 17f
            setTextColor(valueColor)
            gravity = Gravity.CENTER_HORIZONTAL
        })
        return col
    }

    private fun trendRow(host: Activity, profitHistory: List<Int>): LinearLayout {
        val row = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 100).apply {
                topMargin = 8
            }
        }
        val maxAbs = max(1, profitHistory.maxOf { kotlin.math.abs(it) })
        for (profitCents in profitHistory) {
            val barHeight = max(6, (kotlin.math.abs(profitCents).toDouble() / maxAbs * 90).roundToInt())
            val bar = View(host).apply {
                setBackgroundColor(if (profitCents >= 0) POSITIVE else NEGATIVE)
                layoutParams = LinearLayout.LayoutParams(0, barHeight, 1f).apply {
                    leftMargin = 4
                    rightMargin = 4
                }
            }
            row.addView(bar)
        }
        return row
    }

    private fun leaderboardButton(host: Activity, onClick: () -> Unit): View {
        return Button(host).apply {
            text = "🏆 Leaderboard"
            textSize = 15f
            setTextColor(BG)
            setBackgroundColor(MUTED)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = 16
            }
            setOnClickListener { onClick() }
        }
    }

    private fun backButton(host: Activity, onClick: () -> Unit): View {
        return Button(host).apply {
            text = "◀ BACK"
            textSize = 16f
            setTextColor(BG)
            setBackgroundColor(ACCENT_DARK)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = 24
            }
            setOnClickListener { onClick() }
        }
    }

    private fun leaderboardCard(host: Activity, entries: List<Pair<Int, Int>>): View {
        val card = sectionCard(host)

        card.addView(TextView(host).apply {
            text = "🏆 Leaderboard — Best Cash Ever"
            textSize = 18f
            setTextColor(ACCENT)
        })

        if (entries.isEmpty()) {
            card.addView(TextView(host).apply {
                text = "No scores yet — finish a day to get on the board!"
                textSize = 14f
                setTextColor(MUTED)
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    topMargin = 16
                }
            })
        } else {
            entries.forEachIndexed { index, (entryDay, entryCashCents) ->
                val row = LinearLayout(host).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                        topMargin = 14
                    }
                }
                row.addView(TextView(host).apply {
                    text = "#${index + 1}"
                    textSize = 15f
                    setTextColor(if (index == 0) ACCENT else MUTED)
                    layoutParams = LinearLayout.LayoutParams(80, WRAP_CONTENT)
                })
                row.addView(TextView(host).apply {
                    text = "Day $entryDay"
                    textSize = 15f
                    setTextColor(FG)
                    layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                })
                row.addView(TextView(host).apply {
                    text = "$${"%.2f".format(entryCashCents / 100.0)}"
                    textSize = 15f
                    setTextColor(POSITIVE)
                    gravity = Gravity.END
                    layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                })
                card.addView(row)
            }
        }

        return card
    }

    private fun headerCard(host: Activity, day: Int, cashCents: Int, weather: String): View {
        val card = sectionCard(host)

        card.addView(TextView(host).apply {
            text = "🍋 Lemonade Deluxe"
            textSize = 30f
            setTextColor(ACCENT)
            gravity = Gravity.CENTER
        })

        val row = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = 16
            }
        }
        row.addView(TextView(host).apply {
            text = "Day $day"
            textSize = 16f
            setTextColor(FG)
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        row.addView(TextView(host).apply {
            text = "$${"%.2f".format(cashCents / 100.0)}"
            textSize = 16f
            setTextColor(POSITIVE)
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        card.addView(row)

        card.addView(TextView(host).apply {
            text = "Weather: ${weatherDesc(weather)}"
            textSize = 14f
            setTextColor(MUTED)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = 8
            }
        })

        return card
    }

    private fun labeledInputRow(host: Activity, label: String, input: EditText, default: String): LinearLayout {
        val row = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = 16
            }
        }
        row.addView(TextView(host).apply {
            text = label
            textSize = 15f
            setTextColor(FG)
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        input.apply {
            setText(default)
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(FG)
            setHintTextColor(MUTED)
            setBackgroundColor(FIELD_BG)
            setPadding(20, 12, 20, 12)
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(220, WRAP_CONTENT)
        }
        row.addView(input)
        return row
    }

    private fun inputsCard(
        host: Activity,
        glassesInput: EditText,
        priceInput: EditText,
        signsInput: EditText,
        glassesDefault: String,
        priceDefault: String,
        signsDefault: String,
    ): View {
        val card = sectionCard(host)

        card.addView(TextView(host).apply {
            text = "Today's Plan"
            textSize = 17f
            setTextColor(ACCENT)
        })

        card.addView(labeledInputRow(host, "Glasses to make", glassesInput, glassesDefault))
        card.addView(labeledInputRow(host, "Price per glass (¢)", priceInput, priceDefault))
        card.addView(labeledInputRow(host, "Ad signs to buy", signsInput, signsDefault))

        return card
    }

    private fun sellButton(
        host: Activity,
        prefs: SharedPreferences,
        glassesInput: EditText,
        priceInput: EditText,
        signsInput: EditText,
        weather: String,
        cashCents: Int,
        onDone: () -> Unit,
    ): View {
        return Button(host).apply {
            text = "SELL"
            textSize = 18f
            setTextColor(BG)
            setBackgroundColor(ACCENT)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = 28
            }
            setOnClickListener {
                val glasses = glassesInput.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 0
                val priceCents = priceInput.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 0
                val signs = signsInput.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 0

                // save the player's chosen inputs so they persist across reloads
                prefs.edit()
                    .putString("glasses_input", glasses.toString())
                    .putString("price_input", priceCents.toString())
                    .putString("signs_input", signs.toString())
                    .apply()

                val weatherMult = weatherMultiplier(weather)
                val priceFactor = max(0.15, 1.5 - priceCents / 40.0)
                val signBoost = (1.0 + signs * 0.12).coerceAtMost(3.0)
                val randomFactor = 0.85 + Random.nextDouble() * 0.3
                val demand = (40.0 * weatherMult * priceFactor * signBoost * randomFactor)
                val sold = minOf(glasses, demand.roundToInt().coerceAtLeast(0))

                val revenueCents = sold * priceCents
                val ingredientCostCents = glasses * 5
                val signCostCents = signs * 15
                val costsCents = ingredientCostCents + signCostCents
                val profitCents = revenueCents - costsCents
                val newCashCents = cashCents + profitCents

                prefs.edit()
                    .putString("res_weather", weather)
                    .putInt("res_made", glasses)
                    .putInt("res_sold", sold)
                    .putInt("res_price_cents", priceCents)
                    .putInt("res_signs", signs)
                    .putInt("res_revenue_cents", revenueCents)
                    .putInt("res_costs_cents", costsCents)
                    .putInt("res_profit_cents", profitCents)
                    .putInt("res_new_cash_cents", newCashCents)
                    .putString("phase", "result")
                    .apply()

                onDone()
            }
        }
    }

    private fun moneyLine(host: Activity, label: String, cents: Int, colorOverride: Int? = null): LinearLayout {
        val row = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = 10
            }
        }
        row.addView(TextView(host).apply {
            text = label
            textSize = 15f
            setTextColor(FG)
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        val amount = cents / 100.0
        row.addView(TextView(host).apply {
            text = (if (amount < 0) "-$" else "$") + "%.2f".format(kotlin.math.abs(amount))
            textSize = 15f
            setTextColor(colorOverride ?: FG)
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        return row
    }

    private fun resultCard(host: Activity, prefs: SharedPreferences): View {
        val card = sectionCard(host)

        val weather = prefs.getString("res_weather", "") ?: ""
        val made = prefs.getInt("res_made", 0)
        val sold = prefs.getInt("res_sold", 0)
        val revenueCents = prefs.getInt("res_revenue_cents", 0)
        val costsCents = prefs.getInt("res_costs_cents", 0)
        val profitCents = prefs.getInt("res_profit_cents", 0)
        val newCashCents = prefs.getInt("res_new_cash_cents", 0)

        card.addView(TextView(host).apply {
            text = "End of Day Report"
            textSize = 17f
            setTextColor(ACCENT)
        })

        card.addView(TextView(host).apply {
            text = "Weather was: ${weatherDesc(weather)}"
            textSize = 14f
            setTextColor(MUTED)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = 12
            }
        })

        val statsRow = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = 8
            }
        }
        statsRow.addView(TextView(host).apply {
            text = "Made: $made"
            textSize = 15f
            setTextColor(FG)
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        statsRow.addView(TextView(host).apply {
            text = "Sold: $sold"
            textSize = 15f
            setTextColor(FG)
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        card.addView(statsRow)

        card.addView(moneyLine(host, "Revenue", revenueCents, POSITIVE))
        card.addView(moneyLine(host, "Costs", -costsCents, NEGATIVE))
        card.addView(moneyLine(host, "Profit", profitCents, if (profitCents >= 0) POSITIVE else NEGATIVE))
        card.addView(moneyLine(host, "New cash", newCashCents, ACCENT))

        return card
    }

    private fun nextDayButton(host: Activity, prefs: SharedPreferences, onDone: () -> Unit): View {
        return Button(host).apply {
            text = "NEXT DAY"
            textSize = 18f
            setTextColor(BG)
            setBackgroundColor(ACCENT_DARK)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = 24
            }
            setOnClickListener {
                val day = prefs.getInt("day", 1)
                val newCashCents = prefs.getInt("res_new_cash_cents", prefs.getInt("cash_cents", 2000))
                val completedDayProfitCents = prefs.getInt("res_profit_cents", 0)

                val history = (prefs.getString("profit_history", "") ?: "")
                    .split(",")
                    .mapNotNull { it.toIntOrNull() }
                    .toMutableList()
                history.add(completedDayProfitCents)
                while (history.size > 7) history.removeAt(0)

                recordLeaderboardScore(prefs, day, newCashCents)

                prefs.edit()
                    .putInt("day", day + 1)
                    .putInt("cash_cents", newCashCents)
                    .putString("weather", "")
                    .putString("phase", "input")
                    .putString("profit_history", history.joinToString(","))
                    .apply()

                onDone()
            }
        }
    }
}
