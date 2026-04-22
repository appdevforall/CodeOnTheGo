package org.appdevforall.codeonthego.computervision.domain.grammar


import com.itsaky.androidide.fuzzysearch.FuzzySearch

interface AttributeValidator {
    fun validate(rawValue: String): String?
}

internal fun matchCategoricalValue(rawValue: String, allowedValues: List<String>, threshold: Int = 70): String? {
    val result = FuzzySearch.extractOne(rawValue, allowedValues)
    return if (result.score >= threshold) result.string else null
}

object PassThroughValidator : AttributeValidator {
    override fun validate(rawValue: String): String = rawValue.trim()
}

object DimensionValidator : AttributeValidator {
    private val dimensionValues = listOf("match_parent", "wrap_content")

    override fun validate(rawValue: String): String? {
        val trimmed = rawValue.trim()
        if (trimmed.endsWith("dp") || trimmed.endsWith("sp") || trimmed.endsWith("px")) {
            return trimmed
        }
        return matchCategoricalValue(trimmed, dimensionValues)
    }
}

class CategoricalValidator(private val allowedValues: List<String>) : AttributeValidator {
    override fun validate(rawValue: String): String? {
        return matchCategoricalValue(rawValue.trim(), allowedValues)
    }
}

object SliderStyleValidator : AttributeValidator {
    private val sliderStyles = listOf("continuous", "discrete", "material", "thick")
    private val styleResourceMapping = mapOf(
        "continuous" to "@style/Widget.MaterialComponents.Slider",
        "discrete" to "@style/Widget.MaterialComponents.Slider.Discrete",
        "material" to "@style/Widget.MaterialComponents.Slider",
        "thick" to "@style/Widget.App.Slider.Thick"
    )

    override fun validate(rawValue: String): String? {
        val matchedCategory = matchCategoricalValue(rawValue.trim(), sliderStyles)
        return matchedCategory?.let {
            styleResourceMapping[it] ?: "@style/Slider.${it.replaceFirstChar { c -> c.uppercase() }}"
        }
    }
}

object EntriesValidator : AttributeValidator {

    override fun validate(rawValue: String): String? {
        val trimmed = rawValue.trim()
        if (trimmed.startsWith("@")) return trimmed

        val hasBrackets = isEnclosedInBrackets(trimmed)
        val content = trimmed.removeSurrounding("[", "]")

        val cleanedItems = content.split(",").map { processItem(it) }

        val finalString = cleanedItems.joinToString(", ")
        return if (hasBrackets) "[$finalString]" else finalString
    }

    private fun isEnclosedInBrackets(text: String): Boolean {
        return text.startsWith("[") && text.endsWith("]")
    }

    private fun processItem(item: String): String {
        val cleanItem = item.trim()
        return if (isOcrGarbledNumber(cleanItem)) {
            cleanNumberArtifacts(cleanItem)
        } else {
            cleanTextArtifacts(cleanItem)
        }
    }

    private fun isOcrGarbledNumber(text: String): Boolean {
        val hasDigit = text.any { it.isDigit() }
        val onlyContainsNumbersAndCommonOcrNoise = text.matches(Regex("^[0-9oOlIzZsSbB\\s]+$"))

        return hasDigit && onlyContainsNumbersAndCommonOcrNoise
    }

    private fun cleanNumberArtifacts(text: String): String {
        return text
            .replace(Regex("[oO]"), "0")
            .replace(Regex("[lI]"), "1")
            .replace(Regex("[zZ]"), "2")
            .replace(Regex("[sS]"), "5")
            .replace(Regex("[bB]"), "6")
            .replace(" ", "")
    }

    private fun cleanTextArtifacts(text: String): String {
        return text.replace(Regex("\\s+"), " ")
    }
}
