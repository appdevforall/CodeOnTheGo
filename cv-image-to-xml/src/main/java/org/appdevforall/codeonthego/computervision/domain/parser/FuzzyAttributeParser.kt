package org.appdevforall.codeonthego.computervision.domain.parser

import com.itsaky.androidide.fuzzysearch.FuzzySearch
import org.appdevforall.codeonthego.computervision.domain.grammar.UiGrammarValidator
import org.appdevforall.codeonthego.computervision.domain.parser.sanitizer.OcrSanitizerFactory
import java.lang.StringBuilder

object FuzzyAttributeParser {
    private val grammarValidator = UiGrammarValidator()
    private const val PIPE_DELIMITER = "|"
    private val multipleUnderscoresRegex = Regex("_+")
    private val inputTypeValues = InputTypeValueSet.values.map { it.lowercase() }.toSet()
    private val sanitizer = OcrSanitizerFactory.createDefaultSanitizer()

    private val cleaners = mapOf(
        ValueType.TEXT_CONTENT to TextContentCleaner,
        ValueType.DIMENSION to DimensionCleaner,
        ValueType.SP_DIMENSION to SpDimensionCleaner,
        ValueType.COLOR to ColorCleaner,
        ValueType.ID to IdCleaner,
        ValueType.DRAWABLE to DrawableCleaner,
        ValueType.INTEGER to NumberCleaner,
        ValueType.FLOAT to FloatCleaner,
        ValueType.TEXT_STYLE to TextStyleCleaner,
        ValueType.RAW to ValueCleaner { it }
    )

    fun parse(annotation: String?, tag: String): Map<String, String> {
        if (annotation.isNullOrBlank()) return emptyMap()

        val normalizedInput = annotation.replace(Regex("\\s+:"), ":")
        val tokens = tokenizeAnnotation(normalizedInput)

        val rawAttributes = mapTokensToAttributes(tokens, tag)
        val finalAttributes = grammarValidator.enforceGrammar(rawAttributes, tag)

        return finalAttributes
    }

    private fun tokenizeAnnotation(annotation: String): List<String> {
        val sanitized = sanitizer.sanitize(annotation)

        return if (sanitized.contains(PIPE_DELIMITER)) {
            sanitized.split(PIPE_DELIMITER).map { it.trim() }.filter { it.isNotEmpty() }
        } else {
            sanitized.split(Regex("[:;]|\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
        }
    }

    private fun mapTokensToAttributes(tokens: List<String>, tag: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var currentKey: AttributeKey? = null
        val currentValue = StringBuilder()

        for (token in tokens) {
            val matchedKey = if (shouldTreatTokenAsValue(token, currentKey)) {
                null
            } else {
                fuzzyMatchKey(token)
            }

            if (matchedKey != null) {
                flushAttribute(currentKey, currentValue.toString(), tag, result)
                currentKey = matchedKey
                currentValue.clear()
            } else {
                currentValue.append(token).append(" ")
            }
        }

        flushAttribute(currentKey, currentValue.toString(), tag, result)
        return result
    }

    private fun shouldTreatTokenAsValue(token: String, currentKey: AttributeKey?): Boolean {
        if (currentKey != AttributeKey.INPUT_TYPE) return false
        return token.trim().lowercase() in inputTypeValues
    }

    private fun flushAttribute(key: AttributeKey?, rawValue: String, tag: String, destination: MutableMap<String, String>) {
        if (key == null || rawValue.isBlank()) return

        val cleaner = cleaners[key.valueType] ?: ValueCleaner { it }
        val cleanedValue = cleaner.clean(rawValue.trim())

        if (cleanedValue.isNotEmpty()) {
            val (xmlAttr, finalValue) = resolveXmlAttribute(key, cleanedValue, tag)
            destination[xmlAttr] = finalValue
        }
    }

    private fun fuzzyMatchKey(rawKey: String): AttributeKey? {
        val normalizedKey = rawKey.lowercase()
            .replace("-", "_")
            .replace(".", "_")
            .replace(multipleUnderscoresRegex, "_")
            .replace(Regex("lay[ao0]ut"), "layout")
            .replace(Regex("(?<=^|_)[lt]d(?=$|_)"), "id")

        val exactMatch = AttributeKey.findByAlias(normalizedKey)
        if (exactMatch != null) return exactMatch

        if (normalizedKey.length < 2) return null

        val threshold = when {
            normalizedKey.length <= 3 -> 65
            normalizedKey.length <= 6 -> 75
            else -> 80
        }

        val result = FuzzySearch.extractOne(normalizedKey, AttributeKey.allAliases)

        return if (result.score >= threshold) AttributeKey.findByAlias(result.string) else null
    }

    private fun resolveXmlAttribute(key: AttributeKey, value: String, tag: String): Pair<String, String> {
        if (key == AttributeKey.BACKGROUND && tag == "Button") return "app:backgroundTint" to value
        if (key == AttributeKey.ID) return key.xmlName to value.replace(" ", "_")
        return key.xmlName to value
    }
}
