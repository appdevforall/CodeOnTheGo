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
