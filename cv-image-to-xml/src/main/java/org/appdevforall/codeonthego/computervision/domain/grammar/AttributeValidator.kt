package org.appdevforall.codeonthego.computervision.domain.grammar


import com.itsaky.androidide.fuzzysearch.FuzzySearch
import org.appdevforall.codeonthego.computervision.utils.extractOcrEntries

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

object BooleanValidator : AttributeValidator {
    private val allowedValues = listOf("true", "false")

    override fun validate(rawValue: String): String? {
        return matchCategoricalValue(rawValue.trim().lowercase(), allowedValues, threshold = 85)
    }
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

class SpDimensionRangeValidator(
    private val minSp: Int,
    private val maxSp: Int
) : AttributeValidator {
    private val spRegex = Regex("^(\\d+(?:\\.\\d+)?)sp$")

    override fun validate(rawValue: String): String? {
        val trimmed = rawValue.trim()

        val match = spRegex.matchEntire(trimmed) ?: return null
        val value = match.groupValues[1].toFloatOrNull() ?: return null

        return trimmed.takeIf { value >= minSp && value <= maxSp }
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

        val content = trimmed.removeSurrounding("[", "]")
        val rawItems = content.extractOcrEntries()

        val isNumericArray = isEntireArrayLikelyNumeric(rawItems)

        val cleanedItems = rawItems.map { item ->
            val cleanItem = item.trim()
            if (isNumericArray) {
                cleanNumberArtifacts(cleanItem)
            } else {
                cleanTextArtifacts(cleanItem)
            }
        }

        return cleanedItems.joinToString(",")
    }

    private fun isEntireArrayLikelyNumeric(items: List<String>): Boolean {
        if (items.isEmpty()) return false
        var hasAtLeastOneDigit = false

        for (item in items) {
            val cleanItem = item.trim()
            if (cleanItem.isEmpty()) continue

            if (!cleanItem.matches(Regex("^[0-9oOlIzZsSbB\\s]+$"))) {
                return false
            }
            if (cleanItem.any { it.isDigit() }) {
                hasAtLeastOneDigit = true
            }
        }

        return hasAtLeastOneDigit
    }

    private fun cleanNumberArtifacts(text: String): String {
        return text
            .replace(Regex("[oO]"), "0")
            .replace(Regex("[lI]"), "1")
            .replace(Regex("[zZ]"), "2")
            .replace(Regex("[sS]"), "5")
            .replace(Regex("[bB]"), "6")
            .replace(Regex("\\s+"), "")
    }

    private fun cleanTextArtifacts(text: String): String {
        return text.replace(Regex("\\s+"), " ")
    }
}

class FlagsCategoricalValidator(
    private val allowedValues: List<String>,
    private val separator: String = "|",
    private val threshold: Int = 70
) : AttributeValidator {
    override fun validate(rawValue: String): String? {
        val flags = rawValue.split(separator)

        val validFlags = flags.mapNotNull { flag ->
            val trimmedFlag = flag.trim()
            if (trimmedFlag.isEmpty()) {
                null
            } else {
                matchCategoricalValue(trimmedFlag, allowedValues, threshold)
            }
        }

        return if (validFlags.isNotEmpty()) {
            validFlags.distinct().joinToString(separator)
        } else {
            null
        }
    }
}
