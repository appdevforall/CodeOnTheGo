package org.appdevforall.codeonthego.computervision.domain.grammar

class UiGrammarValidator {
    private val registry: Map<String, WidgetGrammar> = listOf(
        SpinnerGrammar,
        ImageViewGrammar,
        EditTextGrammar,
        SliderGrammar
    ).associateBy { it.tag }

    fun enforceGrammar(rawParsedAttributes: Map<String, String>, tag: String): Map<String, String> {
        val grammar = registry[tag] ?: return rawParsedAttributes
        val filteredMap = mutableMapOf<String, String>()

        for ((key, rawValue) in rawParsedAttributes) {
            val validator = grammar.attributes[key]
            if (validator != null) {
                val validValue = validator.validate(rawValue)
                if (validValue != null) {
                    filteredMap[key] = validValue
                }
            }
        }
        return filteredMap
    }
}
