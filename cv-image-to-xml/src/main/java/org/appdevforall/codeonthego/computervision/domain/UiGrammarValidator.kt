package org.appdevforall.codeonthego.computervision.domain

import com.itsaky.androidide.fuzzysearch.FuzzySearch


private val allowedAttributesByWidget = mapOf(
    "Spinner" to setOf("android:layout_width", "android:layout_height", "android:id", "android:text", "tools:entries"),
    "ImageView" to setOf("android:layout_width", "android:layout_height", "android:id", "android:src", "android:layout_gravity"),
    "EditText" to setOf("android:layout_width", "android:layout_height", "android:id", "android:text", "android:inputType", "android:hint"),
    "Button" to setOf("android:layout_width", "android:layout_height", "android:id", "android:text", "app:backgroundTint", "android:background"),
    "CheckBox" to setOf("android:layout_width", "android:layout_height", "android:id", "android:text", "android:textColor"),
    "RadioButton" to setOf("android:layout_width", "android:layout_height", "android:id", "android:text", "android:textColor", "android:textSize"),
    "com.google.android.material.slider.Slider" to setOf("android:layout_width", "android:layout_height", "android:id", "style", "android:layout_weight"),
    "Switch" to setOf("android:layout_width", "android:layout_height", "android:id", "android:text", "android:textColor", "android:textSize", "android:layout_marginTop", "android:textStyle")
)

private val dimensionValues = listOf("match_parent", "wrap_content")
private val gravityValues = listOf("top", "bottom", "left", "right", "center", "center_vertical", "center_horizontal", "start", "end")
private val inputTypeValues = listOf("text", "textPassword", "number", "numberDecimal", "textEmailAddress", "textUri", "phone")
private val textStyleValues = listOf("normal", "bold", "italic")
private val sliderStyles = listOf("continuous", "discrete", "material", "thick")

fun enforceGrammar(rawParsedAttributes: Map<String, String>, tag: String): Map<String, String> {
    val allowedKeys = allowedAttributesByWidget[tag] ?: return emptyMap()
    val filteredMap = mutableMapOf<String, String>()

    for ((key, rawValue) in rawParsedAttributes) {
        if (key in allowedKeys) {
            val validValue = enforceValueGrammar(key, rawValue)
            if (validValue != null) {
                filteredMap[key] = validValue
            }
        }
    }
    return filteredMap
}

private fun enforceValueGrammar(key: String, rawValue: String): String? {
    val trimmed = rawValue.trim()
    return when (key) {
        "android:layout_width", "android:layout_height" -> {
            if (trimmed.endsWith("dp") || trimmed.endsWith("sp") || trimmed.endsWith("px")) trimmed
            else matchCategoricalValue(trimmed, dimensionValues)
        }
        "android:layout_gravity" -> matchCategoricalValue(trimmed, gravityValues)
        "android:inputType" -> matchCategoricalValue(trimmed, inputTypeValues)
        "android:textStyle" -> matchCategoricalValue(trimmed, textStyleValues)
        "style" -> matchCategoricalValue(trimmed, sliderStyles)
        else -> trimmed
    }
}

private fun matchCategoricalValue(rawValue: String, allowedValues: List<String>, threshold: Int = 70): String? {
    val result = FuzzySearch.extractOne(rawValue, allowedValues)
    return if (result.score >= threshold) result.string else null
}
