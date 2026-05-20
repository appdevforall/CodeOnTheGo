package org.appdevforall.codeonthego.computervision.domain.parser

import com.itsaky.androidide.fuzzysearch.FuzzySearch

internal object TextContentCleaner : ValueCleaner {
    private val trailingWidgetTagRegex = Regex(
        "\\s+(?:[A-Z]{1,2}\\s+)?(?:B|P|D|T|C|R|SW|S)\\s*-\\s*[A-Z0-9_]+\\s*$",
        RegexOption.IGNORE_CASE
    )
    private val trailingRepeatedPrefixRegex = Regex(
        "\\s+(?:B|P|D|T|C|R|SW|S)\\s+(?=(?:B|P|D|T|C|R|SW|S)\\s*-\\s*[A-Z0-9_]+\\s*$)",
        RegexOption.IGNORE_CASE
    )
    private val multipleWhitespaceRegex = Regex("\\s+")

    override fun clean(rawValue: String): String {
        return rawValue
            .replace(trailingRepeatedPrefixRegex, " ")
            .replace(trailingWidgetTagRegex, "")
            .replace(multipleWhitespaceRegex, " ")
            .trim()
    }
}


internal object NumberCleaner : ValueCleaner {
    private val ocrLetterOToZeroRegex = Regex("[oO]")
    private val ocrLetterIToOneRegex = Regex("[lI]")
    private val ocrLetterZToTwoRegex = Regex("[zZ]")
    private val ocrLetterSToFiveRegex = Regex("[sS]")
    private val ocrLetterBToSixRegex = Regex("[bB]")

    override fun clean(rawValue: String): String {
        val match = Regex("-?[\\doOlIzZsSbB]+").find(rawValue) ?: return rawValue
        return match.value
            .replace(ocrLetterOToZeroRegex, "0")
            .replace(ocrLetterIToOneRegex, "1")
            .replace(ocrLetterZToTwoRegex, "2")
            .replace(ocrLetterSToFiveRegex, "5")
            .replace(ocrLetterBToSixRegex, "6")
    }
}

internal object DimensionCleaner : ValueCleaner {
    private val matchKeywords = setOf("match", "parent")
    private val wrapKeywords = setOf("wrap", "content", "wrapcan")
    private val DIMENSION_CONSTANTS = listOf("wrap_content", "match_parent")
    private val explicitDimensionRegex = Regex("^(-?\\d+)(dp|sp|px|dip)$")

    override fun clean(rawValue: String): String {
        val trimmedValue = rawValue.trim()
        val normalized = trimmedValue.lowercase().replace(" ", "_")

        if (matchKeywords.any { it in normalized }) return "match_parent"
        if (wrapKeywords.any { it in normalized }) return "wrap_content"

        val fuzzyResult = FuzzySearch.extractOne(normalized, DIMENSION_CONSTANTS)
        if (fuzzyResult.score >= 60) return fuzzyResult.string

        val fixedUnit = normalized.replace(Regex("0p$|op$|olp$"), "dp")
        explicitDimensionRegex.matchEntire(fixedUnit)?.let { match ->
            val normalizedNumber = normalizeOcrDimensionNumber(match.groupValues[1])
            return normalizedNumber + match.groupValues[2]
        }

        val numericPart = NumberCleaner.clean(fixedUnit.replace("_", ""))
        val normalizedNumericPart = normalizeOcrDimensionNumber(numericPart)

        return if (numericPart != fixedUnit) "${normalizedNumericPart}dp" else trimmedValue
    }

    private fun normalizeOcrDimensionNumber(numericPart: String): String {
        if (!numericPart.matches(Regex("-?\\d+"))) return numericPart

        val isNegative = numericPart.startsWith("-")
        val numericValue = numericPart.toLongOrNull() ?: return numericPart
        val canonical = numericValue.toString()
        val unsignedCanonical = canonical.removePrefix("-")

        // OCR sometimes reads the trailing "dp" as a single zero, turning 150dp into 1500.
        if (unsignedCanonical.endsWith('0') && unsignedCanonical.toLong() >= 1000L) {
            val normalizedValue = numericValue / 10L
            return normalizedValue.toString()
        }

        return if (isNegative && numericValue == 0L) "0" else canonical
    }
}

internal object SpDimensionCleaner : ValueCleaner {
    override fun clean(rawValue: String): String {
        val normalized = rawValue.lowercase().replace(" ", "").replace(Regex("(sp|5p)$"), "")
        val numericPart = NumberCleaner.clean(normalized.replace("_", ""))
        return if (numericPart != normalized) "${numericPart}sp" else rawValue
    }
}

internal object ColorCleaner : ValueCleaner {
    private val colorMap = mapOf(
        "red" to "#FF0000", "rel" to "#FF0000", "green" to "#00FF00", "blue" to "#0000FF",
        "black" to "#000000", "white" to "#FFFFFF", "gray" to "#808080",
        "grey" to "#808080", "dark_gray" to "#A9A9A9", "yellow" to "#FFFF00",
        "cyan" to "#00FFFF", "magenta" to "#FF00FF", "purple" to "#800080",
        "orange" to "#FFA500", "brown" to "#A52A2A", "pink" to "#FFC0CB",
        "light_gray" to "#D3D3D3", "dark_blue" to "#00008B", "dark_green" to "#006400",
        "dark_red" to "#8B0000", "teal" to "#008080", "navy" to "#000080",
        "transparent" to "@android:color/transparent"
    )

    override fun clean(rawValue: String): String {
        if (rawValue.startsWith("#") || rawValue.startsWith("@")) return rawValue
        val normalizedValue = rawValue.lowercase().replace(" ", "_")

        val exactColor = colorMap[normalizedValue]
        if (exactColor != null) return exactColor

        val result = FuzzySearch.extractOne(normalizedValue, colorMap.keys.toList())
        return if (result.score >= 75) colorMap[result.string] ?: rawValue else rawValue
    }
}

internal object IdCleaner : ValueCleaner {
    private val ID_VOCABULARY = listOf("cb", "rb", "group", "checkbox", "radio", "btn", "button", "text", "view", "img", "image", "input")
    private val nonAlphanumericRegex = Regex("[^a-z0-9_]")

    override fun clean(rawValue: String): String {
        val firstWord = rawValue.trim().split(Regex("\\s+")).firstOrNull() ?: rawValue

        val cleaned = firstWord.lowercase()
            .replace(Regex("inm|rn|wm|nm")) { m -> if (m.value == "inm") "im" else "m" }
            .replace(nonAlphanumericRegex, "_")
            .replace(Regex("_+"), "_")
            .trim('_')

        return normalizeKnownIdVocabulary(cleaned)
    }

    private fun normalizeKnownIdVocabulary(identifier: String): String {
        if (identifier.isBlank()) return identifier
        return identifier.split('_').filter { it.isNotBlank() }
            .flatMap(::normalizeIdToken).joinToString("_")
    }

    private fun normalizeIdToken(token: String): List<String> {
        if (token.isBlank()) return emptyList()
        if (token.all(Char::isDigit)) return listOf(token)

        val exactMatch = FuzzySearch.extractOne(token, ID_VOCABULARY)
        if (exactMatch.score >= 80 && kotlin.math.abs(token.length - exactMatch.string.length) <= 2) {
            return listOf(exactMatch.string)
        }
        return listOf(token)
    }
}

internal object DrawableCleaner : ValueCleaner {
    override fun clean(rawValue: String): String {
        if (rawValue.startsWith("@drawable/")) return rawValue

        val cleaned = rawValue.lowercase()
            .replace(Regex("\\.(png|jpg|jpeg|webp|xml|svg)$"), "")
            .replace(Regex("inm|rn|wm|nm")) { m -> if (m.value == "inm") "im" else "m" }
            .replace(Regex("[^a-z0-9_]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')

        val finalCleaned = cleaned
            .replace("im_age", "image")
            .replace(Regex("(^|_)im($|_)"), "$1image$2")
            .replace(Regex("_+"), "_")
            .trim('_')
        return if (finalCleaned.isEmpty()) rawValue else "@drawable/$finalCleaned"
    }
}

internal object TextStyleCleaner : ValueCleaner {
    private val TEXT_STYLE_VALUES = listOf("normal", "bold", "italic", "bold|italic")

    override fun clean(rawValue: String): String {
        val normalizedValue = rawValue.lowercase().replace(" ", "_")
        if (normalizedValue in TEXT_STYLE_VALUES) return normalizedValue

        val result = FuzzySearch.extractOne(normalizedValue, TEXT_STYLE_VALUES)
        return if (result.score >= 60) result.string else rawValue
    }
}

internal object FloatCleaner : ValueCleaner {
    override fun clean(rawValue: String): String {
        return Regex("-?\\d+\\.?\\d*").find(rawValue)?.value ?: rawValue
    }
}
