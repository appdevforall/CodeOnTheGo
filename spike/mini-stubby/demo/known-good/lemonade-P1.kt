package app.payload

import android.app.Activity
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import org.json.JSONArray
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Classic Lemonade Stand game, connected to the live-reload system.
 *
 * Entry point contract the shell relies on (keep EXACTLY):
 *   object Main { @JvmStatic fun render(host: Activity): View }
 */
object Main {

    // ---- palette (no R.color.* allowed) ----
    private val BG = Color.parseColor("#FF0B3D2E")
    private val FG = Color.parseColor("#FFE8F5E9")
    private val CARD_BG = Color.parseColor("#FF12503C")
    private val ACCENT = Color.parseColor("#FFF6C445")
    private val ACCENT_DARK = Color.parseColor("#FFD9A62A")
    private val MUTED = Color.parseColor("#FFAFCFC0")
    private val POSITIVE = Color.parseColor("#FF6FCF8F")
    private val NEGATIVE = Color.parseColor("#FFEF7A7A")
    private val FIELD_BG = Color.parseColor("#FF0E3A2C")

    private const val PREFS = "game"
    private const val MAX_DAYS = 30
    private const val INGREDIENT_COST_PER_GLASS = 0.02
    private const val SIGN_COST = 0.15

    private data class Weather(val name: String, val emoji: String, val mult: Double, val note: String)

    private val WEATHERS = listOf(
        Weather("Sunny", "☀️", 1.0, "Good day for lemonade."),
        Weather("Partly Cloudy", "⛅", 0.8, "Decent foot traffic."),
        Weather("Cloudy", "☁️", 0.55, "Fewer thirsty customers."),
        Weather("Scorching Heat Wave", "🔥", 1.4, "Everyone wants a cold drink!"),
        Weather("Rainy", "🌧️", 0.3, "Almost nobody is outside."),
    )

    @JvmStatic
    fun render(host: Activity): View {
        val prefs = host.getSharedPreferences(PREFS, 0)

        val scroll = ScrollView(host).apply {
            setBackgroundColor(BG)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            isFillViewport = true
        }

        val root = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            setPadding(40, 100, 40, 64)
        }
        scroll.addView(root)

        rebuild(root, host, prefs)
        return scroll
    }

    // ---- state read/write helpers ----

    private fun day(prefs: SharedPreferences) = prefs.getInt("day", 1)
    private fun cashCents(prefs: SharedPreferences) = prefs.getInt("cashCents", 2000)
    private fun phase(prefs: SharedPreferences) = prefs.getString("phase", "setup") ?: "setup"

    private fun weatherIndexForDay(prefs: SharedPreferences, day: Int): Int {
        val storedDay = prefs.getInt("weatherDay", -1)
        if (storedDay == day) return prefs.getInt("weatherIndex", 0)
        // new day: roll fresh weather and persist it so it stays stable across reloads
        val idx = Random.Default.nextInt(WEATHERS.size)
        prefs.edit().putInt("weatherDay", day).putInt("weatherIndex", idx).apply()
        return idx
    }

    private fun history(prefs: SharedPreferences): JSONArray =
        JSONArray(prefs.getString("history", "[]"))

    // ---- UI ----

    private fun rebuild(root: LinearLayout, host: Activity, prefs: SharedPreferences) {
        root.removeAllViews()

        val day = day(prefs)
        val cashCents = cashCents(prefs)
        val phase = phase(prefs)

        root.addView(TextView(host).apply {
            text = "🍋 Lemonade Stand"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ACCENT)
            gravity = Gravity.CENTER
        })

        if (day > MAX_DAYS && phase == "setup") {
            addGameOver(root, host, prefs, cashCents)
            return
        }

        root.addView(statRow(host, day, cashCents))
        root.addView(spacer(host, 20))

        val weather = WEATHERS[weatherIndexForDay(prefs, day)]
        root.addView(weatherCard(host, weather))
        root.addView(spacer(host, 24))

        if (phase == "result") {
            addResultCard(root, host, prefs)
        } else {
            addSetupCard(root, host, prefs, weather)
        }

        root.addView(spacer(host, 28))
        addHistory(root, host, prefs)
    }

    private fun statRow(host: Activity, day: Int, cashCents: Int): View {
        val row = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = 16
            }
        }
        row.addView(pill(host, "Day ${minOf(day, MAX_DAYS)} / $MAX_DAYS", MUTED))
        row.addView(spacerH(host, 12))
        row.addView(pill(host, "\$" + centsToStr(cashCents), ACCENT))
        return row
    }

    private fun pill(host: Activity, text: String, color: Int): View {
        return TextView(host).apply {
            this.text = text
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(BG)
            gravity = Gravity.CENTER
            background = rounded(color, 999f)
            setPadding(28, 14, 28, 14)
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        }
    }

    private fun weatherCard(host: Activity, weather: Weather): View {
        val card = cardContainer(host)
        card.addView(TextView(host).apply {
            text = "${weather.emoji}  ${weather.name}"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(FG)
        })
        card.addView(TextView(host).apply {
            text = weather.note
            textSize = 14f
            setTextColor(MUTED)
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 6 }
        })
        return card
    }

    private fun addSetupCard(root: LinearLayout, host: Activity, prefs: SharedPreferences, weather: Weather) {
        val card = cardContainer(host)

        card.addView(sectionTitle(host, "Set up the stand"))

        val glassesInput = labeledInput(
            host, card, "Glasses to make", prefs.getString("inputGlasses", "20") ?: "20",
            InputType.TYPE_CLASS_NUMBER,
        )
        val priceInput = labeledInput(
            host, card, "Price per glass (cents)",
            prefs.getString("inputPriceCents", "35") ?: "35",
            InputType.TYPE_CLASS_NUMBER,
        )
        val signsInput = labeledInput(
            host, card, "Ad signs to buy",
            prefs.getString("inputSigns", "1") ?: "1",
            InputType.TYPE_CLASS_NUMBER,
        )

        card.addView(TextView(host).apply {
            text = "Ingredients cost \$${fmt(INGREDIENT_COST_PER_GLASS)} per glass made; signs cost \$${fmt(SIGN_COST)} each."
            textSize = 12f
            setTextColor(MUTED)
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 10 }
        })

        val errorText = TextView(host).apply {
            textSize = 13f
            setTextColor(NEGATIVE)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 8 }
        }
        card.addView(errorText)

        card.addView(primaryButton(host, "Sell!") {
            val glasses = glassesInput.text.toString().toIntOrNull()
            val priceCents = priceInput.text.toString().toIntOrNull()
            val signs = signsInput.text.toString().toIntOrNull()

            if (glasses == null || glasses < 0 || priceCents == null || priceCents < 0 || signs == null || signs < 0) {
                errorText.text = "Please enter valid non-negative numbers."
                errorText.visibility = View.VISIBLE
                return@primaryButton
            }

            prefs.edit()
                .putString("inputGlasses", glasses.toString())
                .putString("inputPriceCents", priceCents.toString())
                .putString("inputSigns", signs.toString())
                .apply()

            sellDay(host, prefs, glasses, priceCents / 100.0, signs, weather)
            rebuild(root, host, prefs)
        })

        root.addView(card)
    }

    private fun sellDay(
        host: Activity,
        prefs: SharedPreferences,
        glasses: Int,
        price: Double,
        signs: Int,
        weather: Weather,
    ) {
        val ingredientCost = glasses * INGREDIENT_COST_PER_GLASS
        val signCost = signs * SIGN_COST
        val totalCost = ingredientCost + signCost

        val idealPrice = 0.35
        val priceFactor = max(0.0, 1.0 - abs(price - idealPrice) / idealPrice * 1.15)
        val baseCustomers = 55.0 * weather.mult
        val adBoost = signs * 5.0
        val randomFactor = 0.85 + Random.Default.nextDouble() * 0.3 // 0.85..1.15
        val demand = ((baseCustomers * priceFactor + adBoost) * randomFactor).roundToInt().coerceAtLeast(0)

        val sales = minOf(glasses, demand)
        val revenue = sales * price
        val profit = revenue - totalCost
        val cashCents = cashCents(prefs)
        val newCashCents = cashCents + (profit * 100).roundToInt()

        val entry = org.json.JSONObject().apply {
            put("day", day(prefs))
            put("weather", weather.emoji + " " + weather.name)
            put("sales", sales)
            put("glasses", glasses)
            put("profitCents", (profit * 100).roundToInt())
        }
        val hist = history(prefs)
        hist.put(entry)

        prefs.edit()
            .putInt("cashCents", newCashCents)
            .putString("history", hist.toString())
            .putString("phase", "result")
            .putInt("lastSales", sales)
            .putInt("lastDemand", demand)
            .putInt("lastGlasses", glasses)
            .putInt("lastRevenueCents", (revenue * 100).roundToInt())
            .putInt("lastCostCents", (totalCost * 100).roundToInt())
            .putInt("lastProfitCents", (profit * 100).roundToInt())
            .putInt("lastSigns", signs)
            .putString("lastWeather", "${weather.emoji} ${weather.name}")
            .putInt("lastNewCashCents", newCashCents)
            .apply()
    }

    private fun addResultCard(root: LinearLayout, host: Activity, prefs: SharedPreferences) {
        val card = cardContainer(host)
        val day = day(prefs)

        card.addView(sectionTitle(host, "Day $day results"))

        val sales = prefs.getInt("lastSales", 0)
        val glasses = prefs.getInt("lastGlasses", 0)
        val revenueCents = prefs.getInt("lastRevenueCents", 0)
        val costCents = prefs.getInt("lastCostCents", 0)
        val profitCents = prefs.getInt("lastProfitCents", 0)
        val newCashCents = prefs.getInt("lastNewCashCents", cashCents(prefs))
        val weatherText = prefs.getString("lastWeather", "") ?: ""

        card.addView(resultLine(host, "Weather", weatherText))
        card.addView(resultLine(host, "Made", "$glasses glasses"))
        card.addView(resultLine(host, "Sold", "$sales glasses"))
        card.addView(resultLine(host, "Revenue", "+\$" + centsToStr(revenueCents)))
        card.addView(resultLine(host, "Costs", "-\$" + centsToStr(costCents)))

        card.addView(TextView(host).apply {
            text = (if (profitCents >= 0) "Profit: +\$" else "Loss: -\$") + centsToStr(abs(profitCents))
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (profitCents >= 0) POSITIVE else NEGATIVE)
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 14 }
        })

        card.addView(resultLine(host, "New cash", "\$" + centsToStr(newCashCents)))

        card.addView(primaryButton(host, if (day >= MAX_DAYS) "See Final Results" else "Next day") {
            prefs.edit()
                .putInt("day", day + 1)
                .putString("phase", "setup")
                .apply()
            rebuild(root, host, prefs)
        })
    }

    private fun addGameOver(root: LinearLayout, host: Activity, prefs: SharedPreferences, cashCents: Int) {
        root.addView(spacer(host, 24))
        val card = cardContainer(host)
        card.addView(TextView(host).apply {
            text = "🏁 Summer's over!"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ACCENT)
        })
        card.addView(TextView(host).apply {
            text = "Final cash: \$" + centsToStr(cashCents)
            textSize = 18f
            setTextColor(FG)
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 10 }
        })
        card.addView(primaryButton(host, "Start a New Summer") {
            prefs.edit().clear().apply()
            rebuild(root, host, prefs)
        })
        root.addView(card)
        root.addView(spacer(host, 28))
        addHistory(root, host, prefs)
    }

    private fun addHistory(root: LinearLayout, host: Activity, prefs: SharedPreferences) {
        val hist = history(prefs)
        if (hist.length() == 0) return

        root.addView(sectionTitle(host, "History"))
        val card = cardContainer(host)
        for (i in max(0, hist.length() - 7) until hist.length()) {
            val e = hist.getJSONObject(i)
            val profitCents = e.getInt("profitCents")
            val row = LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    if (i > max(0, hist.length() - 7)) topMargin = 8
                }
            }
            row.addView(TextView(host).apply {
                text = "Day ${e.getInt("day")} ${e.getString("weather")}"
                textSize = 13f
                setTextColor(MUTED)
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            })
            row.addView(TextView(host).apply {
                text = (if (profitCents >= 0) "+\$" else "-\$") + centsToStr(abs(profitCents))
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (profitCents >= 0) POSITIVE else NEGATIVE)
                gravity = Gravity.END
            })
            card.addView(row)
        }
        root.addView(card)
    }

    // ---- small widget builders ----

    private fun cardContainer(host: Activity): LinearLayout {
        return LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD_BG, 24f)
            setPadding(32, 28, 32, 28)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = 4
            }
        }
    }

    private fun sectionTitle(host: Activity, text: String): View {
        return TextView(host).apply {
            this.text = text
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(MUTED)
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                bottomMargin = 12
                topMargin = 4
            }
        }
    }

    private fun labeledInput(
        host: Activity,
        parent: LinearLayout,
        label: String,
        initial: String,
        inputType: Int,
    ): EditText {
        parent.addView(TextView(host).apply {
            text = label
            textSize = 13f
            setTextColor(MUTED)
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 14 }
        })
        val edit = EditText(host).apply {
            setText(initial)
            this.inputType = inputType
            textSize = 18f
            setTextColor(FG)
            setHintTextColor(MUTED)
            background = rounded(FIELD_BG, 14f)
            setPadding(24, 18, 24, 18)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 6 }
        }
        parent.addView(edit)
        return edit
    }

    private fun primaryButton(host: Activity, text: String, onClick: () -> Unit): Button {
        return Button(host).apply {
            this.text = text
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(BG)
            background = rounded(ACCENT, 16f)
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 20 }
            setOnClickListener { onClick() }
        }
    }

    private fun resultLine(host: Activity, label: String, value: String): View {
        val row = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 8 }
        }
        row.addView(TextView(host).apply {
            text = label
            textSize = 14f
            setTextColor(MUTED)
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        row.addView(TextView(host).apply {
            text = value
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(FG)
            gravity = Gravity.END
        })
        return row
    }

    private fun spacer(host: Activity, heightPx: Int): View {
        return View(host).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, heightPx)
        }
    }

    private fun spacerH(host: Activity, widthPx: Int): View {
        return View(host).apply {
            layoutParams = LinearLayout.LayoutParams(widthPx, 1)
        }
    }

    private fun rounded(color: Int, radius: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
        }
    }

    // ---- formatting ----

    private fun centsToStr(cents: Int): String {
        val neg = cents < 0
        val c = abs(cents)
        val s = "${c / 100}.${(c % 100).toString().padStart(2, '0')}"
        return if (neg) "-$s" else s
    }

    private fun fmt(d: Double): String = "%.2f".format(d)
}
