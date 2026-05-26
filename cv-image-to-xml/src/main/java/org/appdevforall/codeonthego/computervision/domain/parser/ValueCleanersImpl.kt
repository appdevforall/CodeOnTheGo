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
    private val ocrCharMap = mapOf(
        'O' to '0', 'A' to '0', '@' to '0', 'Q' to '0',
        'L' to '1', 'I' to '1', '|' to '1', '!' to '1', '/' to '1', '\\' to '1',
        '(' to '1', ')' to '1', '[' to '1', ']' to '1',
        'Z' to '2', 'S' to '5', 'B' to '6'
    )

    override fun clean(rawValue: String): String {
        val translated = rawValue.map { ocrCharMap[it.uppercaseChar()] ?: it }.joinToString("")
        return Regex("-?\\d+").find(translated)?.value ?: rawValue
    }
}

internal object DimensionCleaner : ValueCleaner {
    private val leadingNumberRegex = Regex("^-?\\d+")

    override fun clean(rawValue: String): String {
        val trimmedValue = rawValue.trim().lowercase()
        val normalized = trimmedValue.replace(" ", "_")

        if (DimensionValueSet.matchKeywords.any { it in normalized }) return DimensionValueSet.MATCH_PARENT
        if (DimensionValueSet.wrapKeywords.any { it in normalized }) return DimensionValueSet.WRAP_CONTENT

        val fuzzyResult = FuzzySearch.extractOne(normalized, DimensionValueSet.values)
        if (fuzzyResult.score >= 60) return fuzzyResult.string

        val unitMatch = Regex("(dp|sp|px|in|mm|pt)$").find(trimmedValue)
        val originalUnit = unitMatch?.value ?: "dp"

        val firstToken = trimmedValue.substringBefore(" ")
        val rawNumber = firstToken.removeSuffix(originalUnit).trim()
        val numericPart = NumberCleaner.clean(rawNumber)

        val numMatch = leadingNumberRegex.find(numericPart)?.value
            ?: return trimmedValue
        val correctedNum = removeOcrTrailingZero(numMatch)

        return "$correctedNum$originalUnit"
    }

    private fun removeOcrTrailingZero(num: String): String {
        val isOcrArtifact = num.endsWith("0") && (num.toLongOrNull() ?: 0L) >= 1000L
        return if (isOcrArtifact) num.dropLast(1) else num
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
    val colorMap = mapOf(
        "red" to "#FF0000", "rel" to "#FF0000", "rad" to "#FF0000", "reo" to "#FF0000",
        "green" to "#00FF00",
        "blue" to "#0000FF", "ine" to "#0000FF", "hne" to "#0000FF", "hlue" to "#0000FF", "ane" to "#0000FF", "lne" to "#0000FF",
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

        val normalizedValue = rawValue.lowercase().replace(Regex("[^a-z_]"), "").replace(" ", "_")

        val exactColor = colorMap[normalizedValue]
        if (exactColor != null) return exactColor

        val result = FuzzySearch.extractOne(normalizedValue, colorMap.keys.toList())
        return if (result.score >= 70) colorMap[result.string] ?: rawValue else rawValue
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
