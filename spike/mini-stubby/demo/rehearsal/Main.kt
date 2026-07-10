package app.payload

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Lemonade Stand — a classic day-by-day management game.
 *
 * Entry point contract the shell relies on (keep EXACTLY):
 *   object Main { @JvmStatic fun render(host: Activity): View }
 * render() returns the root View to display. All app state lives in
 * host.getSharedPreferences("game", 0) (see GameState.kt) and is re-read at the
 * top of render(), so a hot-reload never loses progress.
 */
object Main {
    private const val BG = "#FFFFFDF5"
    private const val CARD_BG = "#FFF3FBF6"
    private const val TEXT_DARK = "#FF1B4D3E"
    private const val TEXT_MUTED = "#FF5C8A76"
    private const val ACCENT = "#FF98E4C4"
    private const val WARN_BG = "#FFE3F5EA"
    private const val GOOD = "#FF2E7D32"
    private const val BAD = "#FFC62828"

    @JvmStatic
    fun render(host: Activity): View {
        val prefs = host.getSharedPreferences("game", 0)
        var state = GameState.load(prefs)

        // A day's weather is rolled once and persisted, so it survives a hot-reload
        // mid-forecast/decision instead of re-rolling every render().
        if (state.phase != Phase.RESULT && state.weather.isEmpty()) {
            state = state.copy(weather = Economy.rollWeather())
            state.save(prefs)
        }

        // Catch any cash total (including the very first load) up on the leaderboard.
        val trackedLeaderboard = GameState.maybeRecordHighScore(state.leaderboard, state.cashCents, state.day)
        if (trackedLeaderboard != state.leaderboard) {
            state = state.copy(leaderboard = trackedLeaderboard)
            state.save(prefs)
        }

        fun rerender() {
            host.setContentView(render(host))
        }

        val scroll = ScrollView(host).apply {
            setBackgroundColor(Color.parseColor(BG))
            layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }

        val content = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 72, 48, 96)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        scroll.addView(content)

        content.addView(title(host, "🍋 Lemonade Stand"))
        content.addView(subtitle(host, "Day ${state.day}  •  Cash on hand: ${Economy.formatCents(state.cashCents)}"))

        if (state.cashCents < Economy.BROKE_THRESHOLD_CENTS) {
            content.addView(banner(host, "You're running low on cash — a good sunny day could turn it around."))
        }

        when (state.phase) {
            Phase.FORECAST -> buildForecast(host, content, state, prefs, ::rerender)
            Phase.DECISIONS -> buildDecisions(host, content, state, prefs, ::rerender)
            Phase.RESULT -> buildResult(host, content, state, prefs, ::rerender)
            Phase.LEADERBOARD -> buildLeaderboard(host, content, state, prefs, ::rerender)
        }

        return scroll
    }

    // ---- Phase screens -----------------------------------------------------

    private fun buildForecast(
        host: Activity,
        content: LinearLayout,
        state: GameState,
        prefs: android.content.SharedPreferences,
        rerender: () -> Unit,
    ) {
        content.addView(card(host) { card ->
            card.addView(sectionHeader(host, "Daily Dashboard"))
            card.addView(resultRow(host, "Cash on hand", Economy.formatCents(state.cashCents)))
            card.addView(resultRow(host, "Day", "${state.day}"))
            card.addView(resultRow(host, "Yesterday", yesterdayLabel(state), yesterdayColor(state)))
            card.addView(resultRow(host, "Trend", trendLabel(state.profitHistoryCents)))
        })

        content.addView(spacer(host))
        content.addView(card(host) { card ->
            card.addView(sectionHeader(host, "Today's Forecast"))
            card.addView(bigLine(host, weatherEmoji(state.weather) + " " + state.weather))
            card.addView(bodyText(host, weatherHint(state.weather)))
        })

        content.addView(spacer(host))
        content.addView(primaryButton(host, "Open the stand →") {
            state.copy(phase = Phase.DECISIONS).save(prefs)
            rerender()
        })

        content.addView(spacer(host))
        content.addView(secondaryButton(host, "🏆 Leaderboard") {
            state.copy(phase = Phase.LEADERBOARD).save(prefs)
            rerender()
        })
    }

    private fun buildDecisions(
        host: Activity,
        content: LinearLayout,
        state: GameState,
        prefs: android.content.SharedPreferences,
        rerender: () -> Unit,
    ) {
        content.addView(card(host) { card ->
            card.addView(sectionHeader(host, "Today's Forecast"))
            card.addView(bodyText(host, weatherEmoji(state.weather) + " " + state.weather + " — " + weatherHint(state.weather)))
        })

        content.addView(spacer(host))
        content.addView(sectionHeader(host, "Make Your Plan"))
        content.addView(bodyText(host, "Ingredients cost ${Economy.formatCents(Economy.INGREDIENT_COST_CENTS)}/glass. Ad signs cost ${Economy.formatCents(Economy.SIGN_COST_CENTS)} each and boost demand."))

        val madeField = labeledNumberField(host, content, "Glasses to make", state.made.toString())
        val priceField = labeledNumberField(host, content, "Price per glass (cents)", state.priceCents.toString())
        val signsField = labeledNumberField(host, content, "Ad signs to buy", state.signs.toString())

        content.addView(spacer(host))
        content.addView(primaryButton(host, "Sell Lemonade! 🍋") {
            val made = madeField.text.toString().toIntOrNull()?.coerceIn(0, 500) ?: 0
            val priceCents = priceField.text.toString().toIntOrNull()?.coerceIn(1, 500) ?: Economy.IDEAL_PRICE_CENTS
            val signs = signsField.text.toString().toIntOrNull()?.coerceIn(0, 20) ?: 0

            val demand = Economy.computeDemand(state.weather, priceCents, signs)
            val sold = minOf(made, demand)
            val revenueCents = sold * priceCents
            val costsCents = made * Economy.INGREDIENT_COST_CENTS + signs * Economy.SIGN_COST_CENTS
            val profitCents = revenueCents - costsCents
            val newCashCents = state.cashCents + profitCents

            state.copy(
                phase = Phase.RESULT,
                cashCents = newCashCents,
                made = made,
                priceCents = priceCents,
                signs = signs,
                resMade = made,
                resSold = sold,
                resRevenueCents = revenueCents,
                resCostsCents = costsCents,
                resProfitCents = profitCents,
                resWeather = state.weather,
                leaderboard = GameState.maybeRecordHighScore(state.leaderboard, newCashCents, state.day),
            ).save(prefs)
            rerender()
        })
    }

    private fun buildResult(
        host: Activity,
        content: LinearLayout,
        state: GameState,
        prefs: android.content.SharedPreferences,
        rerender: () -> Unit,
    ) {
        val profitColor = if (state.resProfitCents >= 0) GOOD else BAD
        val profitWord = if (state.resProfitCents >= 0) "Profit" else "Loss"

        content.addView(card(host) { card ->
            card.addView(sectionHeader(host, "Day ${state.day} Results"))
            card.addView(bodyText(host, weatherEmoji(state.resWeather) + " " + state.resWeather))
            card.addView(resultRow(host, "Glasses made", "${state.resMade}"))
            card.addView(resultRow(host, "Glasses sold", "${state.resSold}"))
            card.addView(resultRow(host, "Revenue", Economy.formatCents(state.resRevenueCents)))
            card.addView(resultRow(host, "Costs", "-" + Economy.formatCents(state.resCostsCents)))
            card.addView(resultRow(host, profitWord, Economy.formatCents(state.resProfitCents), profitColor))
            card.addView(resultRow(host, "New cash balance", Economy.formatCents(state.cashCents)))
        })

        content.addView(spacer(host))
        content.addView(primaryButton(host, "Next day →") {
            state.copy(
                phase = Phase.FORECAST,
                day = state.day + 1,
                weather = "",
                profitHistoryCents = GameState.appendProfitHistory(state.profitHistoryCents, state.resProfitCents),
            ).save(prefs)
            rerender()
        })
    }

    private fun buildLeaderboard(
        host: Activity,
        content: LinearLayout,
        state: GameState,
        prefs: android.content.SharedPreferences,
        rerender: () -> Unit,
    ) {
        content.addView(card(host) { card ->
            card.addView(sectionHeader(host, "🏆 Best Cash Ever Reached"))
            if (state.leaderboard.isEmpty()) {
                card.addView(bodyText(host, "No scores yet — keep playing!"))
            } else {
                state.leaderboard.forEachIndexed { index, entry ->
                    card.addView(
                        resultRow(
                            host,
                            "#${index + 1}  •  Day ${entry.day}",
                            Economy.formatCents(entry.scoreCents),
                        )
                    )
                }
            }
        })

        content.addView(spacer(host))
        content.addView(primaryButton(host, "Back") {
            state.copy(phase = Phase.FORECAST).save(prefs)
            rerender()
        })
    }

    private fun yesterdayLabel(state: GameState): String {
        val last = state.profitHistoryCents.lastOrNull() ?: return "First day"
        val word = if (last >= 0) "Profit" else "Loss"
        return "$word: ${Economy.formatCents(last)}"
    }

    private fun yesterdayColor(state: GameState): String {
        val last = state.profitHistoryCents.lastOrNull() ?: return TEXT_DARK
        return if (last >= 0) GOOD else BAD
    }

    private fun trendLabel(history: List<Int>): String {
        if (history.isEmpty()) return "No data yet"
        return history.joinToString(" → ") { cents ->
            (if (cents >= 0) "+" else "") + Economy.formatCents(cents)
        }
    }

    // ---- Small view builders -------------------------------------------------

    private fun weatherEmoji(weather: String): String = when (weather) {
        "Hot & Dry" -> "☀️🔥"
        "Sunny" -> "☀️"
        "Cloudy" -> "☁️"
        "Rainy" -> "🌧️"
        else -> "🌤️"
    }

    private fun weatherHint(weather: String): String = when (weather) {
        "Hot & Dry" -> "Scorching heat — thirsty crowds, demand is at its peak."
        "Sunny" -> "Pleasant sunshine — a solid day for lemonade."
        "Cloudy" -> "Overcast skies — fewer people out and about."
        "Rainy" -> "Rain keeps everyone indoors — demand will be low."
        else -> "Checking the sky..."
    }

    private fun title(host: Activity, text: String): TextView = TextView(host).apply {
        this.text = text
        textSize = 34f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.parseColor(TEXT_DARK))
        gravity = Gravity.CENTER
    }

    private fun subtitle(host: Activity, text: String): TextView = TextView(host).apply {
        this.text = text
        textSize = 15f
        setTextColor(Color.parseColor(TEXT_MUTED))
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            topMargin = 12
            bottomMargin = 24
            gravity = Gravity.CENTER_HORIZONTAL
        }
    }

    private fun banner(host: Activity, text: String): TextView = TextView(host).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.parseColor(TEXT_DARK))
        setBackgroundColor(Color.parseColor(WARN_BG))
        setPadding(24, 20, 24, 20)
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            bottomMargin = 20
        }
    }

    private fun sectionHeader(host: Activity, text: String): TextView = TextView(host).apply {
        this.text = text
        textSize = 17f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.parseColor(TEXT_DARK))
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            bottomMargin = 8
        }
    }

    private fun bigLine(host: Activity, text: String): TextView = TextView(host).apply {
        this.text = text
        textSize = 22f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.parseColor(TEXT_DARK))
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            bottomMargin = 6
        }
    }

    private fun bodyText(host: Activity, text: String): TextView = TextView(host).apply {
        this.text = text
        textSize = 14f
        setTextColor(Color.parseColor(TEXT_MUTED))
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }

    private fun resultRow(host: Activity, label: String, value: String, valueColor: String = TEXT_DARK): LinearLayout =
        LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = 6
            }
            addView(TextView(host).apply {
                text = label
                textSize = 14f
                setTextColor(Color.parseColor(TEXT_MUTED))
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            })
            addView(TextView(host).apply {
                text = value
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor(valueColor))
                gravity = Gravity.END
            })
        }

    private fun card(host: Activity, build: (LinearLayout) -> Unit): LinearLayout {
        val card = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(CARD_BG))
            setPadding(32, 32, 32, 32)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        build(card)
        return card
    }

    private fun spacer(host: Activity): View = View(host).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 24)
    }

    private fun labeledNumberField(
        host: Activity,
        content: LinearLayout,
        label: String,
        initial: String,
    ): EditText {
        content.addView(TextView(host).apply {
            text = label
            textSize = 13f
            setTextColor(Color.parseColor(TEXT_MUTED))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = 16
            }
        })
        val field = EditText(host).apply {
            setText(initial)
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 16f
            setTextColor(Color.parseColor(TEXT_DARK))
            setBackgroundColor(Color.parseColor(CARD_BG))
            setPadding(24, 16, 24, 16)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = 4
            }
        }
        content.addView(field)
        return field
    }

    private fun primaryButton(host: Activity, label: String, onClick: () -> Unit): Button = Button(host).apply {
        text = label
        textSize = 16f
        setTextColor(Color.parseColor(TEXT_DARK))
        setBackgroundColor(Color.parseColor(ACCENT))
        setPadding(24, 28, 24, 28)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        setOnClickListener { onClick() }
    }

    private fun secondaryButton(host: Activity, label: String, onClick: () -> Unit): Button = Button(host).apply {
        text = label
        textSize = 15f
        setTextColor(Color.parseColor(TEXT_DARK))
        setBackgroundColor(Color.parseColor(CARD_BG))
        setPadding(24, 24, 24, 24)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        setOnClickListener { onClick() }
    }
}
