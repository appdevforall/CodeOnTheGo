package app.payload

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.random.Random

/**
 * Classic Lemonade Stand: each day shows weather, lets the player set glasses made,
 * price per glass, and ad signs bought, then resolves the day's sales and shows
 * profit + running cash. All state persists in SharedPreferences so a hot-reload
 * never loses an in-progress game.
 */
object Main {
    private const val PREFS = "game"

    // View-only toggle (not persisted game state) — which screen to show.
    private var showLeaderboard = false

    // Colors — mint green & cream
    private const val COL_BG = 0xFFFAF6EC.toInt()
    private const val COL_CARD = 0xFFFFFFFB.toInt()
    private const val COL_PRIMARY = 0xFF3EB489.toInt()
    private const val COL_PRIMARY_DARK = 0xFF1F7A5C.toInt()
    private const val COL_ACCENT_GREEN = 0xFF2E7D32.toInt()
    private const val COL_ACCENT_RED = 0xFFC62828.toInt()
    private const val COL_TEXT = 0xFF2F3E36.toInt()
    private const val COL_TEXT_MUTED = 0xFF6B7C72.toInt()
    private const val COL_BORDER = 0xFFCDEEDD.toInt()

    private data class Weather(val name: String, val emoji: String, val demandMult: Double, val blurb: String)

    private val WEATHERS = listOf(
        Weather("Sunny", "☀️", 1.4, "Perfect lemonade weather!"),
        Weather("Hot", "🥵", 1.8, "Scorching hot — everyone's thirsty!"),
        Weather("Cloudy", "☁️", 0.9, "Mild day, so-so demand."),
        Weather("Rainy", "🌧️", 0.4, "Rain keeps customers away."),
        Weather("Windy", "💨", 0.7, "Blustery — stand's a bit shaky."),
    )

    // ---- Persistence ----

    private fun prefs(host: Activity) = host.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private class State(
        val day: Int,
        val cash: Double,
        val signs: Int,
        val weatherIdx: Int,
        val lastResult: String?,
        val history: List<String>,
        val bestCash: Double,
        val leaderboard: List<Double>,
    )

    private fun loadState(host: Activity): State {
        val p = prefs(host)
        val day = if (p.contains("day")) p.getInt("day", 1) else 1
        val cash = if (p.contains("cash")) p.getFloat("cash", 20f).toDouble() else 20.0
        val signs = p.getInt("signs", 0)
        val weatherIdx = if (p.contains("weatherIdx")) p.getInt("weatherIdx", 0)
        else Random.nextInt(WEATHERS.size)
        val lastResult = if (p.contains("lastResult")) p.getString("lastResult", null) else null
        val historyRaw = p.getString("history", "") ?: ""
        val history = if (historyRaw.isBlank()) emptyList() else historyRaw.split("||")
        val bestCash = if (p.contains("bestCash")) p.getFloat("bestCash", cash.toFloat()).toDouble() else cash
        val leaderboardRaw = p.getString("leaderboard", "") ?: ""
        val leaderboard = if (leaderboardRaw.isBlank()) emptyList()
        else leaderboardRaw.split(",").mapNotNull { it.toDoubleOrNull() }
        return State(day, cash, signs, weatherIdx, lastResult, history, bestCash, leaderboard)
    }

    private fun saveState(host: Activity, s: State) {
        prefs(host).edit()
            .putInt("day", s.day)
            .putFloat("cash", s.cash.toFloat())
            .putInt("signs", s.signs)
            .putInt("weatherIdx", s.weatherIdx)
            .apply { if (s.lastResult != null) putString("lastResult", s.lastResult) else remove("lastResult") }
            .putString("history", s.history.joinToString("||"))
            .putFloat("bestCash", s.bestCash.toFloat())
            .putString("leaderboard", s.leaderboard.joinToString(",") { it.toString() })
            .apply()
    }

    // ---- UI helpers ----

    private fun money(v: Double): String = "$" + String.format("%.2f", v)

    /** Pulls the trailing signed profit amount out of a "Day N: sold X/Y, +$Z.ZZ" history entry. */
    private fun parseProfitFromHistory(entry: String): Double? {
        val idx = entry.lastIndexOf('$')
        if (idx == -1) return null
        return entry.substring(idx + 1).toDoubleOrNull()
    }

    private fun card(host: Activity): LinearLayout {
        val bg = GradientDrawable().apply {
            setColor(COL_CARD)
            cornerRadius = 24f
            setStroke(2, COL_BORDER)
        }
        return LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            background = bg
            setPadding(36, 32, 36, 32)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 28) }
        }
    }

    private fun label(host: Activity, text: String, size: Float = 16f, color: Int = COL_TEXT, bold: Boolean = false): TextView =
        TextView(host).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun primaryButton(host: Activity, text: String): Button =
        Button(host).apply {
            this.text = text
            setTextColor(Color.WHITE)
            val bg = GradientDrawable().apply {
                setColor(COL_PRIMARY)
                cornerRadius = 18f
            }
            background = bg
            setPadding(24, 20, 24, 20)
            isAllCaps = false
            textSize = 17f
        }

    private fun numberField(host: Activity, hint: String, initial: String = ""): EditText =
        EditText(host).apply {
            this.hint = hint
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(initial)
            setTextColor(COL_TEXT)
            setHintTextColor(COL_TEXT_MUTED)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

    // ---- Main entry ----

    @JvmStatic
    fun render(host: Activity): View {
        val scroll = ScrollView(host).apply {
            setBackgroundColor(COL_BG)
        }
        val root = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 96, 40, 48)
        }
        scroll.addView(
            root,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        rebuild(root, host)
        return scroll
    }

    private fun rebuild(root: LinearLayout, host: Activity) {
        root.removeAllViews()
        val s = loadState(host)

        if (showLeaderboard) {
            rebuildLeaderboard(root, host, s)
            return
        }

        val weather = WEATHERS[s.weatherIdx]

        // Title
        root.addView(label(host, "🍋 Lemonade Stand", 30f, COL_PRIMARY_DARK, bold = true).apply {
            gravity = Gravity.CENTER
        })
        root.addView(label(host, "Day $${'$'}s.day".replace("$${'$'}s.day", "Day ${s.day}"), 15f, COL_TEXT_MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, 4, 0, 28)
        })

        val leaderboardButton = Button(host).apply {
            text = "🏆 Leaderboard"
            setTextColor(COL_PRIMARY_DARK)
            val bg = GradientDrawable().apply {
                setColor(COL_CARD)
                cornerRadius = 18f
                setStroke(2, COL_BORDER)
            }
            background = bg
            isAllCaps = false
            textSize = 15f
            setPadding(20, 16, 20, 16)
            setOnClickListener {
                showLeaderboard = true
                rebuild(root, host)
            }
        }
        root.addView(leaderboardButton.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, 20)
            }
        })

        // Daily dashboard: day, cash, yesterday's profit, short trend
        val dashCard = card(host)
        val dashRow = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }

        val dayCol = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        dayCol.addView(label(host, "Day", 12f, COL_TEXT_MUTED))
        dayCol.addView(label(host, "${s.day}", 22f, COL_TEXT, bold = true))
        dashRow.addView(dayCol)

        val cashCol = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        cashCol.addView(label(host, "Cash", 12f, COL_TEXT_MUTED))
        cashCol.addView(label(host, money(s.cash), 22f, COL_ACCENT_GREEN, bold = true))
        dashRow.addView(cashCol)

        val yesterdayProfit = s.history.lastOrNull()?.let { parseProfitFromHistory(it) }
        val profitCol = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        profitCol.addView(label(host, "Yesterday", 12f, COL_TEXT_MUTED))
        profitCol.addView(
            if (yesterdayProfit == null) {
                label(host, "—", 22f, COL_TEXT_MUTED, bold = true)
            } else {
                label(
                    host,
                    (if (yesterdayProfit >= 0) "+" else "") + money(yesterdayProfit),
                    22f,
                    if (yesterdayProfit >= 0) COL_ACCENT_GREEN else COL_ACCENT_RED,
                    bold = true
                )
            }
        )
        dashRow.addView(profitCol)

        dashCard.addView(dashRow)

        val trendProfits = s.history.takeLast(6).mapNotNull { parseProfitFromHistory(it) }
        if (trendProfits.isNotEmpty()) {
            val trendDivider = View(host).apply {
                setBackgroundColor(COL_BORDER)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 2).apply {
                    setMargins(0, 16, 0, 12)
                }
            }
            dashCard.addView(trendDivider)
            dashCard.addView(label(host, "Profit trend", 12f, COL_TEXT_MUTED).apply { setPadding(0, 0, 0, 6) })
            val trendText = trendProfits.joinToString("  ") { p ->
                (if (p >= 0) "▲" else "▼") + money(kotlin.math.abs(p))
            }
            val trendColor = if (trendProfits.last() >= 0) COL_ACCENT_GREEN else COL_ACCENT_RED
            dashCard.addView(label(host, trendText, 14f, trendColor))
        }
        root.addView(dashCard)

        // Status card: weather + cash
        val statusCard = card(host)
        val weatherRow = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        weatherRow.addView(label(host, weather.emoji, 40f))
        val weatherText = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 0, 0, 0)
        }
        weatherText.addView(label(host, weather.name, 20f, COL_TEXT, bold = true))
        weatherText.addView(label(host, weather.blurb, 14f, COL_TEXT_MUTED))
        weatherRow.addView(weatherText)
        statusCard.addView(weatherRow)

        val divider = View(host).apply {
            setBackgroundColor(COL_BORDER)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 2).apply {
                setMargins(0, 20, 0, 20)
            }
        }
        statusCard.addView(divider)

        val cashRow = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
        cashRow.addView(label(host, "💰 Cash on hand", 16f, COL_TEXT_MUTED).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        cashRow.addView(label(host, money(s.cash), 20f, COL_ACCENT_GREEN, bold = true))
        statusCard.addView(cashRow)

        val signsRow = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
        signsRow.addView(label(host, "📋 Ad signs owned", 14f, COL_TEXT_MUTED).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        signsRow.addView(label(host, "${s.signs}", 14f, COL_TEXT_MUTED))
        statusCard.addView(signsRow.apply { setPadding(0, 8, 0, 0) })

        root.addView(statusCard)

        // Last result card (if any)
        if (s.lastResult != null) {
            val resultCard = card(host)
            resultCard.addView(label(host, "Yesterday's Results", 16f, COL_TEXT, bold = true).apply {
                setPadding(0, 0, 0, 12)
            })
            resultCard.addView(label(host, s.lastResult, 14f, COL_TEXT))
            root.addView(resultCard)
        }

        // Decision card
        val decisionCard = card(host)
        decisionCard.addView(label(host, "Today's Plan", 18f, COL_TEXT, bold = true).apply {
            setPadding(0, 0, 0, 16)
        })

        decisionCard.addView(label(host, "Cups of lemonade to make", 13f, COL_TEXT_MUTED))
        val glassesInput = numberField(host, "e.g. 20").apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) }
        decisionCard.addView(glassesInput.apply { setPadding(0, 0, 0, 16) })

        decisionCard.addView(label(host, "Price per cup ($)", 13f, COL_TEXT_MUTED))
        val priceInput = numberField(host, "e.g. 0.25").apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) }
        decisionCard.addView(priceInput.apply { setPadding(0, 0, 0, 16) })

        decisionCard.addView(label(host, "Ad signs to buy today ($1.00 each)", 13f, COL_TEXT_MUTED))
        val signsInput = numberField(host, "e.g. 1").apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) }
        decisionCard.addView(signsInput.apply { setPadding(0, 0, 0, 20) })

        val costHint = label(host, "Ingredients cost ~$0.05/cup. Sell what you can!", 12f, COL_TEXT_MUTED)
        decisionCard.addView(costHint.apply { setPadding(0, 0, 0, 16) })

        val sellButton = primaryButton(host, "☀️ Open the stand!")
        sellButton.setOnClickListener {
            val glasses = glassesInput.text.toString().toIntOrNull() ?: 0
            val price = priceInput.text.toString().toDoubleOrNull() ?: 0.0
            val newSigns = signsInput.text.toString().toIntOrNull() ?: 0

            if (glasses <= 0 || price <= 0.0) {
                AlertDialog.Builder(host)
                    .setTitle("Hold on")
                    .setMessage("Enter at least 1 cup and a price above $0.")
                    .setPositiveButton("OK", null)
                    .show()
                return@setOnClickListener
            }

            val signCost = newSigns * 1.0
            val ingredientCost = glasses * 0.05
            val totalCost = signCost + ingredientCost

            if (totalCost > s.cash) {
                AlertDialog.Builder(host)
                    .setTitle("Not enough cash")
                    .setMessage("Making $glasses cups + $newSigns signs costs ${money(totalCost)}, but you only have ${money(s.cash)}.")
                    .setPositiveButton("OK", null)
                    .show()
                return@setOnClickListener
            }

            // Demand model: base interest scaled by weather, price sensitivity, and ad signs.
            val totalSigns = s.signs + newSigns
            val priceFactor = (0.55 / price).coerceIn(0.15, 3.0)
            val adBoost = 1.0 + (totalSigns * 0.12)
            val baseDemand = 20.0 * weather.demandMult * priceFactor * adBoost
            val noisyDemand = (baseDemand * (0.85 + Random.nextDouble() * 0.3)).toInt()
            val cupsSold = minOf(glasses, maxOf(0, noisyDemand))

            val revenue = cupsSold * price
            val profit = revenue - totalCost
            val newCash = s.cash - totalCost + revenue

            val resultText = buildString {
                append("Made $glasses cups at ${money(price)} each, sold $cupsSold.\n")
                append("Revenue: ${money(revenue)}  •  Costs: ${money(totalCost)}\n")
                append(if (profit >= 0) "Profit: ${money(profit)} 🎉" else "Loss: ${money(-profit)} 😬")
            }

            val historyEntry = "Day ${s.day}: sold $cupsSold/$glasses, ${if (profit >= 0) "+" else ""}${money(profit)}"
            val newHistory = (s.history + historyEntry).takeLast(30)

            val newBestCash = maxOf(s.bestCash, newCash)
            val newLeaderboard = (s.leaderboard + newCash).sortedDescending().take(10)

            val newState = State(
                day = s.day + 1,
                cash = newCash,
                signs = totalSigns,
                weatherIdx = Random.nextInt(WEATHERS.size),
                lastResult = resultText,
                history = newHistory,
                bestCash = newBestCash,
                leaderboard = newLeaderboard,
            )
            saveState(host, newState)

            if (newCash <= 0) {
                AlertDialog.Builder(host)
                    .setTitle("Game Over")
                    .setMessage("You're out of cash! Final day: ${s.day}.")
                    .setPositiveButton("New Game") { _, _ ->
                        prefs(host).edit().clear().apply()
                        rebuild(root, host)
                    }
                    .setCancelable(false)
                    .show()
            } else {
                rebuild(root, host)
            }
        }
        decisionCard.addView(sellButton)
        root.addView(decisionCard)

        // History card
        if (s.history.isNotEmpty()) {
            val historyCard = card(host)
            historyCard.addView(label(host, "History", 16f, COL_TEXT, bold = true).apply {
                setPadding(0, 0, 0, 12)
            })
            s.history.asReversed().take(10).forEach { entry ->
                historyCard.addView(label(host, entry, 13f, COL_TEXT_MUTED).apply {
                    setPadding(0, 0, 0, 6)
                })
            }
            root.addView(historyCard)
        }
    }

    private fun rebuildLeaderboard(root: LinearLayout, host: Activity, s: State) {
        root.addView(label(host, "🏆 Leaderboard", 30f, COL_PRIMARY_DARK, bold = true).apply {
            gravity = Gravity.CENTER
        })
        root.addView(label(host, "Best cash ever reached", 15f, COL_TEXT_MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, 4, 0, 28)
        })

        val bestCard = card(host)
        bestCard.addView(label(host, "Personal Best", 13f, COL_TEXT_MUTED))
        bestCard.addView(label(host, money(s.bestCash), 28f, COL_ACCENT_GREEN, bold = true))
        root.addView(bestCard)

        val listCard = card(host)
        listCard.addView(label(host, "Top 10", 16f, COL_TEXT, bold = true).apply {
            setPadding(0, 0, 0, 12)
        })
        if (s.leaderboard.isEmpty()) {
            listCard.addView(label(host, "No days finished yet — open the stand!", 14f, COL_TEXT_MUTED))
        } else {
            s.leaderboard.take(10).forEachIndexed { i, cashValue ->
                val row = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
                row.addView(label(host, "#${i + 1}", 15f, COL_TEXT_MUTED, bold = true).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(label(host, money(cashValue), 15f, COL_TEXT, bold = true))
                listCard.addView(row.apply { setPadding(0, 0, 0, 10) })
            }
        }
        root.addView(listCard)

        val backButton = primaryButton(host, "⬅ Back")
        backButton.setOnClickListener {
            showLeaderboard = false
            rebuild(root, host)
        }
        root.addView(backButton)
    }
}
