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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Lemonade Stand — classic economic sim.
 *
 * Entry point contract the shell relies on (keep EXACTLY):
 *   object Main { @JvmStatic fun render(host: Activity): View }
 *
 * ALL game state lives in host.getSharedPreferences("game", 0) and is re-read at
 * the top of render(), so a hot-reload never loses progress.
 */
object Main {

    // ---- palette ---------------------------------------------------------
    private const val BG = 0xFFFFFDF3.toInt()        // lemon cream
    private const val CARD = 0xFFFFFFFF.toInt()       // white card
    private const val ACCENT = 0xFFF5C518.toInt()     // lemonade yellow
    private const val ACCENT_DK = 0xFFB8860B.toInt()  // deep amber
    private const val INK = 0xFF2E2A1E.toInt()        // near-black text
    private const val MUTED = 0xFF7A7466.toInt()      // muted label
    private const val GOOD = 0xFF2E7D32.toInt()       // profit green
    private const val BAD = 0xFFC62828.toInt()        // loss red
    private const val LINE = 0xFFEDE7D3.toInt()       // hairline

    // ---- weather model ---------------------------------------------------
    private val WEATHER_NAME = arrayOf("Sunny", "Hot & Dry", "Cloudy", "Rainy")
    private val WEATHER_ICON = arrayOf("☀️", "🔥", "☁️", "🌧️")
    // base number of thirsty passers-by for each weather
    private val WEATHER_POTENTIAL = intArrayOf(90, 130, 55, 25)
    private val WEATHER_BLURB = arrayOf(
        "Clear skies — decent thirst.",
        "Scorching! Everyone wants lemonade.",
        "Grey and mild — few takers.",
        "Wet and cold — hardly anyone out.",
    )

    private const val COST_PER_GLASS = 15  // cents
    private const val COST_PER_AD = 20     // cents
    private const val START_CASH = 2000    // cents

    // ---- state keys ------------------------------------------------------
    private const val K_CASH = "cash"
    private const val K_DAY = "day"
    private const val K_PHASE = "phase"           // "decide" | "result"
    private const val K_W_DAY = "weatherDay"       // which day the stored weather is for
    private const val K_W_IDX = "weatherIdx"
    private const val K_IN_MADE = "inMade"
    private const val K_IN_PRICE = "inPrice"
    private const val K_IN_ADS = "inAds"
    // result snapshot
    private const val K_R_W = "rW"
    private const val K_R_MADE = "rMade"
    private const val K_R_SOLD = "rSold"
    private const val K_R_PRICE = "rPrice"
    private const val K_R_REV = "rRev"
    private const val K_R_COST = "rCost"
    private const val K_R_PROFIT = "rProfit"
    private const val K_R_CASH = "rCash"

    @JvmStatic
    fun render(host: Activity): View {
        val prefs = host.getSharedPreferences("game", 0)

        val cash = prefs.getInt(K_CASH, START_CASH)
        val day = prefs.getInt(K_DAY, 1)
        val phase = prefs.getString(K_PHASE, "decide") ?: "decide"

        // Roll (and persist) today's weather once per day — stable across reloads.
        val weatherIdx = ensureWeather(prefs, day)

        val scroll = ScrollView(host).apply {
            setBackgroundColor(BG)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            isFillViewport = true
        }
        val page = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(host, 20), dp(host, 24), dp(host, 20), dp(host, 32))
        }
        scroll.addView(page)

        // header
        page.addView(TextView(host).apply {
            text = "🍋  Lemonade Stand"
            textSize = 26f
            setTextColor(INK)
            setTypeface(typeface, Typeface.BOLD)
        })
        page.addView(TextView(host).apply {
            text = "Turn sunshine into profit, one glass at a time."
            textSize = 13f
            setTextColor(MUTED)
            setPadding(0, dp(host, 2), 0, dp(host, 16))
        })

        if (phase == "result") {
            buildResultScreen(host, page, prefs, day)
        } else {
            buildDecideScreen(host, page, prefs, day, cash, weatherIdx)
        }

        return scroll
    }

    // ---------------------------------------------------------------------
    // DECIDE screen: forecast + decisions + Sell!
    // ---------------------------------------------------------------------
    private fun buildDecideScreen(
        host: Activity,
        page: LinearLayout,
        prefs: SharedPreferences,
        day: Int,
        cash: Int,
        weatherIdx: Int,
    ) {
        // ---- forecast card ----
        val forecast = card(host)
        forecast.addView(rowLabelValue(host, "Day", "#$day"))
        forecast.addView(rowLabelValue(host, "Cash on hand", money(cash), if (cash <= 0) BAD else GOOD))
        forecast.addView(hairline(host))
        forecast.addView(TextView(host).apply {
            text = "${WEATHER_ICON[weatherIdx]}  ${WEATHER_NAME[weatherIdx]}"
            textSize = 22f
            setTextColor(INK)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(host, 8), 0, 0)
        })
        forecast.addView(TextView(host).apply {
            text = WEATHER_BLURB[weatherIdx]
            textSize = 13f
            setTextColor(MUTED)
        })
        page.addView(forecast)

        // ---- broke notice ----
        if (cash < COST_PER_GLASS * 3) {
            page.addView(TextView(host).apply {
                text = if (cash <= 0)
                    "😔  You're broke. Set a small batch and a fair price to claw back — you can keep playing."
                else
                    "⚠️  Running low on cash. Keep the batch small today."
                textSize = 13f
                setTextColor(BAD)
                setPadding(dp(host, 4), dp(host, 4), dp(host, 4), dp(host, 12))
            })
        }

        // ---- decisions card ----
        val decisions = card(host)
        decisions.addView(sectionTitle(host, "Today's plan"))

        val madeField = numberField(host, prefs.getInt(K_IN_MADE, 20))
        val priceField = numberField(host, prefs.getInt(K_IN_PRICE, 15))
        val adsField = numberField(host, prefs.getInt(K_IN_ADS, 2))

        decisions.addView(fieldRow(host, "Glasses to make", "@ ${money(COST_PER_GLASS)} each", madeField))
        decisions.addView(fieldRow(host, "Price per glass (cents)", "you set it", priceField))
        decisions.addView(fieldRow(host, "Ad signs to buy", "@ ${money(COST_PER_AD)} each", adsField))

        // live cost preview
        val preview = TextView(host).apply {
            textSize = 13f
            setTextColor(ACCENT_DK)
            setPadding(0, dp(host, 6), 0, 0)
        }
        decisions.addView(preview)
        val refreshPreview = {
            val made = readInt(madeField)
            val ads = readInt(adsField)
            val upfront = made * COST_PER_GLASS + ads * COST_PER_AD
            preview.text = "Up-front cost: ${money(upfront)}  (ingredients ${money(made * COST_PER_GLASS)} + ads ${money(ads * COST_PER_AD)})"
        }
        refreshPreview()
        addWatcher(madeField, refreshPreview)
        addWatcher(priceField, refreshPreview)
        addWatcher(adsField, refreshPreview)

        page.addView(decisions)

        // ---- Sell! ----
        page.addView(primaryButton(host, "🍺  Sell!") {
            val made = max(0, readInt(madeField))
            val price = max(0, readInt(priceField))
            val ads = max(0, readInt(adsField))

            // remember inputs for next day's defaults
            prefs.edit()
                .putInt(K_IN_MADE, made)
                .putInt(K_IN_PRICE, price)
                .putInt(K_IN_ADS, ads)
                .apply()

            runSale(prefs, day, cash, weatherIdx, made, price, ads)
            reload(host)
        })
    }

    // ---------------------------------------------------------------------
    // Sale computation
    // ---------------------------------------------------------------------
    private fun runSale(
        prefs: SharedPreferences,
        day: Int,
        cash: Int,
        weatherIdx: Int,
        made: Int,
        price: Int,
        ads: Int,
    ) {
        val potential = WEATHER_POTENTIAL[weatherIdx]

        // Price sensitivity: cheap sells out, expensive kills demand.
        // Linear from 1.0 at <=5c down to 0.0 at 35c.
        val priceFactor = ((35.0 - price) / 30.0).coerceIn(0.0, 1.0)

        // Advertising: each sign lifts demand, with diminishing headroom.
        val adFactor = 1.0 + min(ads, 12) * 0.14

        // Random daily wobble ±15%.
        val wobble = 0.85 + Random.nextDouble() * 0.30

        val demand = max(0, (potential * priceFactor * adFactor * wobble).roundToInt())
        val sold = min(made, demand)

        val revenue = sold * price
        val costs = made * COST_PER_GLASS + ads * COST_PER_AD
        val profit = revenue - costs
        val cashAfter = cash + profit

        prefs.edit()
            .putInt(K_CASH, cashAfter)
            .putString(K_PHASE, "result")
            .putInt(K_R_W, weatherIdx)
            .putInt(K_R_MADE, made)
            .putInt(K_R_SOLD, sold)
            .putInt(K_R_PRICE, price)
            .putInt(K_R_REV, revenue)
            .putInt(K_R_COST, costs)
            .putInt(K_R_PROFIT, profit)
            .putInt(K_R_CASH, cashAfter)
            .apply()
    }

    // ---------------------------------------------------------------------
    // RESULT screen
    // ---------------------------------------------------------------------
    private fun buildResultScreen(
        host: Activity,
        page: LinearLayout,
        prefs: SharedPreferences,
        day: Int,
    ) {
        val w = prefs.getInt(K_R_W, 0)
        val made = prefs.getInt(K_R_MADE, 0)
        val sold = prefs.getInt(K_R_SOLD, 0)
        val price = prefs.getInt(K_R_PRICE, 0)
        val revenue = prefs.getInt(K_R_REV, 0)
        val costs = prefs.getInt(K_R_COST, 0)
        val profit = prefs.getInt(K_R_PROFIT, 0)
        val cashAfter = prefs.getInt(K_R_CASH, 0)

        val result = card(host)
        result.addView(TextView(host).apply {
            text = "Day $day results"
            textSize = 20f
            setTextColor(INK)
            setTypeface(typeface, Typeface.BOLD)
        })
        result.addView(TextView(host).apply {
            text = "${WEATHER_ICON[w]}  ${WEATHER_NAME[w]}"
            textSize = 14f
            setTextColor(MUTED)
            setPadding(0, dp(host, 2), 0, dp(host, 10))
        })

        // headline: sold X of Y
        val leftover = made - sold
        result.addView(TextView(host).apply {
            text = "Sold $sold of $made glasses" + if (leftover > 0) "  ($leftover unsold)" else "  — sold out! 🎉"
            textSize = 16f
            setTextColor(INK)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(host, 6))
        })

        result.addView(hairline(host))
        result.addView(rowLabelValue(host, "Revenue  ($sold @ ${money(price)})", money(revenue), GOOD))
        result.addView(rowLabelValue(host, "Costs  (${money(made * COST_PER_GLASS)} lemons + ${money(costs - made * COST_PER_GLASS)} ads)", "− ${money(costs)}", BAD))
        result.addView(hairline(host))
        result.addView(rowLabelValue(host, "Profit", (if (profit >= 0) "" else "−") + money(kotlin.math.abs(profit)), if (profit >= 0) GOOD else BAD, big = true))
        result.addView(rowLabelValue(host, "Cash now", money(cashAfter), if (cashAfter <= 0) BAD else INK, big = true))

        page.addView(result)

        // encouragement line
        page.addView(TextView(host).apply {
            text = when {
                profit > 300 -> "🤑  Booming business!"
                profit > 0 -> "👍  A tidy profit."
                profit == 0 -> "⚖️  Broke even."
                else -> "📉  Rough day — adjust price, ads, or batch size."
            }
            textSize = 14f
            setTextColor(ACCENT_DK)
            setPadding(dp(host, 4), dp(host, 8), dp(host, 4), dp(host, 12))
        })

        page.addView(primaryButton(host, "Next day  →") {
            prefs.edit()
                .putInt(K_DAY, day + 1)
                .putString(K_PHASE, "decide")
                .apply()
            reload(host)
        })
    }

    // ---------------------------------------------------------------------
    // weather roll (persisted per day)
    // ---------------------------------------------------------------------
    private fun ensureWeather(prefs: SharedPreferences, day: Int): Int {
        if (prefs.getInt(K_W_DAY, -1) == day) {
            return prefs.getInt(K_W_IDX, 0)
        }
        val idx = Random.nextInt(WEATHER_NAME.size)
        prefs.edit().putInt(K_W_DAY, day).putInt(K_W_IDX, idx).apply()
        return idx
    }

    // ---------------------------------------------------------------------
    // UI helpers
    // ---------------------------------------------------------------------
    private fun reload(host: Activity) {
        host.setContentView(render(host))
    }

    private fun dp(host: Activity, v: Int): Int =
        (v * host.resources.displayMetrics.density).roundToInt()

    private fun money(cents: Int): String {
        val neg = cents < 0
        val c = kotlin.math.abs(cents)
        return (if (neg) "-" else "") + "$" + (c / 100) + "." + String.format("%02d", c % 100)
    }

    private fun card(host: Activity): LinearLayout {
        val bg = GradientDrawable().apply {
            setColor(CARD)
            cornerRadius = dp(host, 16).toFloat()
            setStroke(dp(host, 1), LINE)
        }
        return LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            background = bg
            setPadding(dp(host, 18), dp(host, 16), dp(host, 18), dp(host, 16))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = dp(host, 16)
            }
        }
    }

    private fun sectionTitle(host: Activity, t: String): TextView = TextView(host).apply {
        text = t
        textSize = 12f
        setTextColor(MUTED)
        letterSpacing = 0.08f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, dp(host, 8))
    }

    private fun hairline(host: Activity): View = View(host).apply {
        setBackgroundColor(LINE)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(host, 1)).apply {
            topMargin = dp(host, 8)
            bottomMargin = dp(host, 8)
        }
    }

    private fun rowLabelValue(
        host: Activity,
        label: String,
        value: String,
        valueColor: Int = INK,
        big: Boolean = false,
    ): LinearLayout = LinearLayout(host).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(host, 3), 0, dp(host, 3))
        addView(TextView(host).apply {
            text = label
            textSize = if (big) 15f else 14f
            setTextColor(MUTED)
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        addView(TextView(host).apply {
            text = value
            textSize = if (big) 18f else 15f
            setTextColor(valueColor)
            setTypeface(typeface, if (big) Typeface.BOLD else Typeface.NORMAL)
            gravity = Gravity.END
        })
    }

    private fun fieldRow(host: Activity, label: String, hint: String, field: EditText): LinearLayout =
        LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(host, 6), 0, dp(host, 6))
            addView(LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                addView(TextView(host).apply {
                    text = label
                    textSize = 14f
                    setTextColor(INK)
                })
                addView(TextView(host).apply {
                    text = hint
                    textSize = 11f
                    setTextColor(MUTED)
                })
            })
            addView(field)
        }

    private fun numberField(host: Activity, initial: Int): EditText = EditText(host).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        setText(initial.toString())
        gravity = Gravity.CENTER
        textSize = 18f
        setTextColor(INK)
        setTypeface(typeface, Typeface.BOLD)
        val box = GradientDrawable().apply {
            setColor(0xFFFFFBEA.toInt())
            cornerRadius = dp(host, 10).toFloat()
            setStroke(dp(host, 2), ACCENT)
        }
        background = box
        setPadding(dp(host, 12), dp(host, 10), dp(host, 12), dp(host, 10))
        layoutParams = LinearLayout.LayoutParams(dp(host, 84), WRAP_CONTENT).apply {
            marginStart = dp(host, 12)
        }
    }

    private fun primaryButton(host: Activity, label: String, onClick: () -> Unit): Button =
        Button(host).apply {
            text = label
            textSize = 17f
            setTextColor(INK)
            setTypeface(typeface, Typeface.BOLD)
            isAllCaps = false
            val bg = GradientDrawable().apply {
                setColor(ACCENT)
                cornerRadius = dp(host, 14).toFloat()
            }
            background = bg
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dp(host, 4)
            }
            setPadding(0, dp(host, 16), 0, dp(host, 16))
            setOnClickListener { onClick() }
        }

    private fun readInt(field: EditText): Int =
        field.text.toString().trim().toIntOrNull() ?: 0

    private fun addWatcher(field: EditText, cb: () -> Unit) {
        field.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = cb()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }
}
