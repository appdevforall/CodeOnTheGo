package org.appdevforall.codeonthego.computervision.domain

import com.itsaky.androidide.fuzzysearch.FuzzySearch

object FuzzyAttributeParser {

    private const val FUZZY_VALUE_THRESHOLD = 75

    private fun fuzzyKeyThreshold(keyLength: Int): Int = when {
        keyLength <= 3 -> 65
        keyLength == 6 -> 75
        else -> 80
    }
    private const val PIPE_DELIMITER = "|"

    enum class AttributeKey(
        val xmlName: String,
        val aliases: List<String>,
        val valueType: ValueType = ValueType.RAW
    ) {
        WIDTH("android:layout_width", listOf("layout_width", "width"), ValueType.DIMENSION),
        HEIGHT("android:layout_height", listOf("layout_height", "height"), ValueType.DIMENSION),
        ID("android:id", listOf("id"), ValueType.ID),
        TEXT("android:text", listOf("text")),
        HINT("android:hint", listOf("hint")),
        BACKGROUND("android:background", listOf("background", "bg"), ValueType.COLOR),
        BACKGROUND_TINT("app:backgroundTint", listOf("backgroundtint", "background_tint"), ValueType.COLOR),
        SRC("android:src", listOf("src", "scr"), ValueType.DRAWABLE),
        CONTENT_DESCRIPTION("android:contentDescription", listOf("contentdescription", "content_description")),

        TEXT_SIZE("android:textSize", listOf("textsize", "text_size"), ValueType.SP_DIMENSION),
        TEXT_COLOR("android:textColor", listOf("textcolor", "text_color", "color", "text_colar", "textcolar"), ValueType.COLOR),
        TEXT_STYLE("android:textStyle", listOf("textstyle", "text_style", "style"), ValueType.RAW),
        TEXT_ALIGNMENT("android:textAlignment", listOf("textalignment", "text_alignment")),
        TEXT_ALL_CAPS("android:textAllCaps", listOf("textallcaps", "text_all_caps")),
        FONT_FAMILY("android:fontFamily", listOf("fontfamily", "font_family", "font")),
        MAX_LINES("android:maxLines", listOf("maxlines", "max_lines"), ValueType.INTEGER),
        MIN_LINES("android:minLines", listOf("minlines", "min_lines"), ValueType.INTEGER),
        LINES("android:lines", listOf("lines"), ValueType.INTEGER),
        SINGLE_LINE("android:singleLine", listOf("singleline", "single_line")),
        ELLIPSIZE("android:ellipsize", listOf("ellipsize")),
        LINE_SPACING_EXTRA("android:lineSpacingExtra", listOf("linespacingextra", "line_spacing_extra"), ValueType.SP_DIMENSION),
        LETTER_SPACING("android:letterSpacing", listOf("letterspacing", "letter_spacing")),
        HINT_TEXT_COLOR("android:textColorHint", listOf("hinttextcolor", "hint_text_color", "textcolorhint", "text_color_hint"), ValueType.COLOR),
        IME_OPTIONS("android:imeOptions", listOf("imeoptions", "ime_options")),

        INPUT_TYPE("android:inputType", listOf("inputtype", "input_type")),
        MAX_LENGTH("android:maxLength", listOf("maxlength", "max_length"), ValueType.INTEGER),

        VISIBILITY("android:visibility", listOf("visibility")),
        ENABLED("android:enabled", listOf("enabled")),
        CLICKABLE("android:clickable", listOf("clickable")),
        FOCUSABLE("android:focusable", listOf("focusable")),
        ALPHA("android:alpha", listOf("alpha")),
        ELEVATION("android:elevation", listOf("elevation"), ValueType.DIMENSION),
        ROTATION("android:rotation", listOf("rotation")),

        PADDING("android:padding", listOf("padding"), ValueType.DIMENSION),
        PADDING_TOP("android:paddingTop", listOf("paddingtop", "padding_top"), ValueType.DIMENSION),
        PADDING_BOTTOM("android:paddingBottom", listOf("paddingbottom", "padding_bottom"), ValueType.DIMENSION),
        PADDING_START("android:paddingStart", listOf("paddingstart", "padding_start"), ValueType.DIMENSION),
        PADDING_END("android:paddingEnd", listOf("paddingend", "padding_end"), ValueType.DIMENSION),
        PADDING_LEFT("android:paddingLeft", listOf("paddingleft", "padding_left"), ValueType.DIMENSION),
        PADDING_RIGHT("android:paddingRight", listOf("paddingright", "padding_right"), ValueType.DIMENSION),

        LAYOUT_MARGIN("android:layout_margin", listOf("layout_margin", "margin"), ValueType.DIMENSION),
        LAYOUT_MARGIN_TOP("android:layout_marginTop", listOf("layout_margintop", "layout_margin_top", "margin_top", "margintop"), ValueType.DIMENSION),
        LAYOUT_MARGIN_BOTTOM("android:layout_marginBottom", listOf("layout_marginbottom", "layout_margin_bottom", "margin_bottom", "marginbottom"), ValueType.DIMENSION),
        LAYOUT_MARGIN_START("android:layout_marginStart", listOf("layout_marginstart", "layout_margin_start", "margin_start", "marginstart"), ValueType.DIMENSION),
        LAYOUT_MARGIN_END("android:layout_marginEnd", listOf("layout_marginend", "layout_margin_end", "margin_end", "marginend"), ValueType.DIMENSION),
        LAYOUT_MARGIN_LEFT("android:layout_marginLeft", listOf("layout_marginleft", "layout_margin_left", "margin_left"), ValueType.DIMENSION),
        LAYOUT_MARGIN_RIGHT("android:layout_marginRight", listOf("layout_marginright", "layout_margin_right", "margin_right"), ValueType.DIMENSION),

        LAYOUT_WEIGHT("android:layout_weight", listOf("layout_weight", "weight"), ValueType.FLOAT),
        LAYOUT_GRAVITY("android:layout_gravity", listOf("layout_gravity")),
        GRAVITY("android:gravity", listOf("gravity")),
        ORIENTATION("android:orientation", listOf("orientation")),

        MIN_WIDTH("android:minWidth", listOf("minwidth", "min_width"), ValueType.DIMENSION),
        MIN_HEIGHT("android:minHeight", listOf("minheight", "min_height"), ValueType.DIMENSION),
        MAX_WIDTH("android:maxWidth", listOf("maxwidth", "max_width"), ValueType.DIMENSION),
        MAX_HEIGHT("android:maxHeight", listOf("maxheight", "max_height"), ValueType.DIMENSION),

        SCALE_TYPE("android:scaleType", listOf("scaletype", "scale_type")),
        ADJUST_VIEW_BOUNDS("android:adjustViewBounds", listOf("adjustviewbounds", "adjust_view_bounds")),
        TINT("android:tint", listOf("tint"), ValueType.COLOR),

        STYLE("style", listOf("style")),
        ENTRIES("tools:entries", listOf("entries")),
        CHECKED("android:checked", listOf("checked")),

        CARD_CORNER_RADIUS("app:cardCornerRadius", listOf("cardcornerradius", "card_corner_radius", "cornerradius", "corner_radius"), ValueType.DIMENSION),
        CARD_ELEVATION("app:cardElevation", listOf("cardelevation", "card_elevation"), ValueType.DIMENSION),
        CARD_BACKGROUND_COLOR("app:cardBackgroundColor", listOf("cardbackgroundcolor", "card_background_color"), ValueType.COLOR),
        STROKE_COLOR("app:strokeColor", listOf("strokecolor", "stroke_color"), ValueType.COLOR),
        STROKE_WIDTH("app:strokeWidth", listOf("strokewidth", "stroke_width"), ValueType.DIMENSION),

        PROGRESS("android:progress", listOf("progress"), ValueType.INTEGER),
        MAX("android:max", listOf("max"), ValueType.INTEGER),
        MIN("android:min", listOf("min"), ValueType.INTEGER),
        VALUE_FROM("app:valueFrom", listOf("valuefrom", "value_from")),
        VALUE_TO("app:valueTo", listOf("valueto", "value_to")),
        STEP_SIZE("app:stepSize", listOf("stepsize", "step_size")),
        TRACK_COLOR("app:trackColor", listOf("trackcolor", "track_color"), ValueType.COLOR),
        THUMB_COLOR("app:thumbTint", listOf("thumbcolor", "thumb_color", "thumbtint", "thumb_tint"), ValueType.COLOR),

        FOREGROUND("android:foreground", listOf("foreground"), ValueType.COLOR),
        SPINNER_MODE("android:spinnerMode", listOf("spinnermode", "spinner_mode")),
        DRAWABLE_START("android:drawableStart", listOf("drawablestart", "drawable_start"), ValueType.DRAWABLE),
        DRAWABLE_END("android:drawableEnd", listOf("drawableend", "drawable_end"), ValueType.DRAWABLE),
        DRAWABLE_PADDING("android:drawablePadding", listOf("drawablepadding", "drawable_padding"), ValueType.DIMENSION);

        companion object {
            val allAliases: List<String> by lazy { entries.flatMap { it.aliases } }

            fun findByAlias(alias: String): AttributeKey? =
                entries.firstOrNull { key -> key.aliases.any { it == alias } }
        }
    }

    enum class ValueType {
        RAW,
        DIMENSION,
        SP_DIMENSION,
        COLOR,
        ID,
        DRAWABLE,
        INTEGER,
        FLOAT
    }

    internal val colorMap = mapOf(
        "red" to "#FF0000", "rel" to "#FF0000", "green" to "#00FF00", "blue" to "#0000FF",
        "black" to "#000000", "white" to "#FFFFFF", "gray" to "#808080",
        "grey" to "#808080", "dark_gray" to "#A9A9A9", "yellow" to "#FFFF00",
        "cyan" to "#00FFFF", "magenta" to "#FF00FF", "purple" to "#800080",
        "orange" to "#FFA500", "brown" to "#A52A2A", "pink" to "#FFC0CB",
        "light_gray" to "#D3D3D3", "dark_blue" to "#00008B", "dark_green" to "#006400",
        "dark_red" to "#8B0000", "teal" to "#008080", "navy" to "#000080",
        "transparent" to "@android:color/transparent"
    )

    private val nonAlphanumericRegex = Regex("[^a-z0-9_]")
    private val multipleUnderscoresRegex = Regex("_+")

    private val ocrLetterOToZeroRegex = Regex("[oO]")
    private val ocrLetterIToOneRegex = Regex("[lI]")
    private val ocrLetterZToTwoRegex = Regex("[zZ]")
    private val ocrLetterSToFiveRegex = Regex("[sS]")
    private val ocrLetterBToSixRegex = Regex("[bB]")

    private val matchKeywords = setOf("match", "parent")
    private val wrapKeywords = setOf("wrap", "content", "wrapcan")

    private val validInputTypes = listOf(
        "text", "textPassword", "number", "numberDecimal",
        "textEmailAddress", "textUri", "phone"
    )

    private val validGravities = listOf(
        "top", "bottom", "left", "right", "center",
        "center_vertical", "center_horizontal", "start", "end"
    )

    private val validTextStyles = listOf("normal", "bold", "italic")

    private fun normalizeOcrKey(raw: String): String =
        raw.lowercase()
            .replace("-", "_")
            .replace(".", "_")
            .replace(" ", "_")
            .replace(multipleUnderscoresRegex, "_")
            .replace(Regex("lay[ao0]ut"), "layout")
            .replace(Regex("(?<=^|_)[lt]d(?=$|_)"), "id")

    fun parse(annotation: String?, tag: String): Map<String, String> {
        if (annotation.isNullOrBlank()) return emptyMap()

        val normalizedSpacing = annotation.replace(Regex("\\s+:"), ":")

        return if (normalizedSpacing.contains(PIPE_DELIMITER)) {
            parseDelimited(normalizedSpacing, tag)
        } else {
            parseByColonScanning(normalizedSpacing, tag)
        }
    }

    private fun matchCategoricalValue(rawValue: String, allowedValues: List<String>, threshold: Int = 70): String {
        val result = FuzzySearch.extractOne(rawValue, allowedValues)
        return if (result.score >= threshold) result.string else rawValue
    }

    private fun parseDelimited(annotation: String, tag: String): Map<String, String> {
        val result = mutableMapOf<String, String>()

        annotation.split(PIPE_DELIMITER)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { chunk ->
                parseChunk(chunk, tag)?.let { (attr, value) ->
                    result[attr] = value
                }
            }

        return result
    }

    private fun parseChunk(chunk: String, tag: String): Pair<String, String>? {
        val colonIndex = chunk.indexOf(':')

        val rawKey: String
        val rawValue: String

        if (colonIndex != -1) {
            rawKey = chunk.take(colonIndex).trim()
            rawValue = chunk.substring(colonIndex + 1).trim()
        } else {
            val splitResult = inferKeyValueBoundary(chunk) ?: return null
            rawKey = splitResult.first
            rawValue = splitResult.second
        }

        if (rawKey.isEmpty()) return null

        val matchedKey = fuzzyMatchKey(rawKey) ?: return null
        val cleanedValue = cleanValue(rawValue, matchedKey)

        if (cleanedValue.isEmpty()) return null

        return resolveXmlAttribute(matchedKey, cleanedValue, tag)
    }

    private fun parseByColonScanning(annotation: String, tag: String): Map<String, String> {
        val colonPositions = mutableListOf<Int>()
        for (i in annotation.indices) {
            if (annotation[i] == ':' || annotation[i] == ';') colonPositions.add(i)
        }

        if (colonPositions.isEmpty()) return emptyMap()

        data class MatchedKey(val key: AttributeKey, val keyStart: Int, val valueStart: Int)

        val matchedKeys = mutableListOf<MatchedKey>()

        for (colonPos in colonPositions) {
            val textBefore = annotation.take(colonPos)
            val words = textBefore.trimEnd().split(Regex("\\s+"))

            var bestMatch: Pair<AttributeKey, Int>? = null
            var bestScore = 0

            for (wordCount in minOf(3, words.size) downTo 1) {
                val candidateWords = words.subList(words.size - wordCount, words.size)
                val candidateKey = candidateWords.joinToString("_")
                val normalized = normalizeOcrKey(candidateKey)

                val exactMatch = AttributeKey.findByAlias(normalized)
                if (exactMatch != null) {
                    val keyStart = textBefore.length - candidateWords.joinToString(" ").length
                    bestMatch = exactMatch to keyStart
                    bestScore = 100
                    break
                }

                if (bestScore < 100 && normalized.length >= 2) {
                    val result = FuzzySearch.extractOne(normalized, AttributeKey.allAliases)
                    if (result.score >= fuzzyKeyThreshold(normalized.length) && result.score > bestScore) {
                        val foundKey = AttributeKey.findByAlias(result.string)
                        if (foundKey != null) {
                            val keyStart = textBefore.length - candidateWords.joinToString(" ").length
                            bestMatch = foundKey to keyStart
                            bestScore = result.score
                        }
                    }
                }
            }

            if (bestMatch != null) {
                val alreadyClaimed = matchedKeys.any { existing ->
                    bestMatch.second >= existing.keyStart && bestMatch.second < existing.valueStart
                }
                if (!alreadyClaimed) {
                    matchedKeys.add(MatchedKey(bestMatch.first, bestMatch.second, colonPos + 1))
                }
            }
        }

        if (matchedKeys.isEmpty()) return emptyMap()

        matchedKeys.sortBy { it.keyStart }

        val result = mutableMapOf<String, String>()

        for (i in matchedKeys.indices) {
            val current = matchedKeys[i]
            val valueEnd = if (i + 1 < matchedKeys.size) {
                matchedKeys[i + 1].keyStart
            } else {
                annotation.length
            }

            var rawValue = annotation.substring(current.valueStart, valueEnd).trim()

            if (rawValue.isNotEmpty()) {
                val (truncated, trailingAttrs) = extractTrailingAttributes(rawValue, tag)
                rawValue = truncated
                result.putAll(trailingAttrs)
            }

            if (rawValue.isNotEmpty()) {
                val cleanedValue = cleanValue(rawValue, current.key)
                if (cleanedValue.isNotEmpty()) {
                    val (attr, finalValue) = resolveXmlAttribute(current.key, cleanedValue, tag)
                    result[attr] = finalValue
                }
            }
        }

        return result
    }

    private fun extractTrailingAttributes(value: String, tag: String): Pair<String, Map<String, String>> {
        val attrs = mutableMapOf<String, String>()
        var remaining = value

        while (true) {
            val words = remaining.split(Regex("\\s+"))
            if (words.size < 2) break

            var found = false
            for (keyStart in words.size - 2 downTo 1) {
                for (keyLen in minOf(3, words.size - keyStart - 1) downTo 1) {
                    val candidateKey = words.subList(keyStart, keyStart + keyLen).joinToString("_")
                    val matched = fuzzyMatchKey(candidateKey) ?: continue

                    val trailingValue = words.subList(keyStart + keyLen, words.size).joinToString(" ")
                    val cleanedValue = cleanValue(trailingValue, matched)
                    if (cleanedValue.isEmpty()) continue

                    val (attr, finalValue) = resolveXmlAttribute(matched, cleanedValue, tag)
                    attrs[attr] = finalValue
                    remaining = words.subList(0, keyStart).joinToString(" ")
                    found = true
                    break
                }
                if (found) break
            }

            if (!found) break
        }

        val finalWords = remaining.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (finalWords.size >= 2 && isLikelyColor(finalWords.last())) {
            val colorValue = cleanColor(finalWords.last())
            val (attr, finalValue) = resolveXmlAttribute(AttributeKey.BACKGROUND, colorValue, tag)
            attrs[attr] = finalValue
            remaining = finalWords.dropLast(1).joinToString(" ")
        }

        return remaining to attrs
    }

    private fun isLikelyColor(word: String): Boolean {
        val normalized = word.lowercase()
        if (normalized.startsWith("#") && normalized.length in 4..9) return true
        if (colorMap.containsKey(normalized)) return true
        val result = FuzzySearch.extractOne(normalized, colorMap.keys.toList())
        return result.score >= FUZZY_VALUE_THRESHOLD
    }

    private fun inferKeyValueBoundary(chunk: String): Pair<String, String>? {
        val words = chunk.split(Regex("\\s+"))
        if (words.size < 2) return null

        for (splitPoint in 1..minOf(3, words.size - 1)) {
            val candidateKey = words.subList(0, splitPoint).joinToString("_")
            val normalized = normalizeOcrKey(candidateKey)

            if (AttributeKey.findByAlias(normalized) != null) {
                val value = words.subList(splitPoint, words.size).joinToString(" ")
                return candidateKey to value
            }

            if (normalized.length >= 2) {
                val result = FuzzySearch.extractOne(normalized, AttributeKey.allAliases)
                if (result.score >= fuzzyKeyThreshold(normalized.length)) {
                    val value = words.subList(splitPoint, words.size).joinToString(" ")
                    return candidateKey to value
                }
            }
        }
        return null
    }

    private fun fuzzyMatchKey(rawKey: String): AttributeKey? {
        val normalizedKey = normalizeOcrKey(rawKey)

        val exactMatch = AttributeKey.findByAlias(normalizedKey)
        if (exactMatch != null) return exactMatch

        if (normalizedKey.length < 2) return null

        val result = FuzzySearch.extractOne(normalizedKey, AttributeKey.allAliases)
        if (result.score < fuzzyKeyThreshold(normalizedKey.length)) return null

        return AttributeKey.findByAlias(result.string)
    }

    private fun cleanValue(rawValue: String, key: AttributeKey): String {
        val trimmed = rawValue.trim()

        when (key) {
            AttributeKey.INPUT_TYPE -> return matchCategoricalValue(trimmed, validInputTypes)
            AttributeKey.GRAVITY, AttributeKey.LAYOUT_GRAVITY -> return matchCategoricalValue(trimmed, validGravities)
            AttributeKey.TEXT_STYLE -> return matchCategoricalValue(trimmed, validTextStyles)
            else -> {}
        }

        return when (key.valueType) {
            ValueType.DIMENSION -> cleanDimension(trimmed)
            ValueType.SP_DIMENSION -> cleanSpDimension(trimmed)
            ValueType.COLOR -> cleanColor(trimmed)
            ValueType.ID -> cleanId(trimmed)
            ValueType.DRAWABLE -> cleanDrawable(trimmed)
            ValueType.INTEGER -> cleanInteger(trimmed)
            ValueType.FLOAT -> cleanFloat(trimmed)
            ValueType.RAW -> trimmed
        }
    }

    private fun cleanDimension(value: String): String {
        val normalized = value.lowercase().replace(" ", "_")

        if (matchKeywords.any { it in normalized }) return "match_parent"
        if (wrapKeywords.any { it in normalized }) return "wrap_content"

        val fixedUnit = normalized
            .replace(Regex("0p$"), "dp")
            .replace(Regex("op$"), "dp")
            .replace(Regex("olp$"), "dp")

        val numericString = fixedUnit.replace(Regex("[a-z]+$"), "")
        val numericPart = extractOcrNumber(numericString)

        if (numericPart != null) return "${numericPart}dp"

        return value
    }

    private fun cleanSpDimension(value: String): String {
        val fixedUnit = value.lowercase()
            .replace(" ", "")
            .replace(Regex("5p$"), "sp")

        val numericString = fixedUnit.replace(Regex("[a-z]+$"), "")
        val numericPart = extractOcrNumber(numericString)

        if (numericPart != null) return "${numericPart}sp"
        return value
    }

    private fun cleanColor(value: String): String {
        if (value.startsWith("#") || value.startsWith("@")) return value

        val normalizedValue = value.lowercase().replace(" ", "_")

        val exactColor = colorMap[normalizedValue]
        if (exactColor != null) return exactColor

        val colorNames = colorMap.keys.toList()
        val result = FuzzySearch.extractOne(normalizedValue, colorNames)
        if (result.score >= FUZZY_VALUE_THRESHOLD) {
            return colorMap[result.string] ?: value
        }

        return value
    }

    private fun cleanId(value: String): String {
        return value.lowercase()
            .replace(nonAlphanumericRegex, "_")
            .replace(multipleUnderscoresRegex, "_")
            .trimEnd('_')
            .trimStart('_')
    }

    private fun denoiseOcrIdentifier(value: String): String =
        value.replace("rn", "m")
            .replace("wm", "m")

    private fun cleanDrawable(value: String): String {
        if (value.startsWith("@drawable/")) return value
        val cleaned = value.replace(Regex("[^a-zA-Z0-9_.]"), "")
            .substringBeforeLast('.')
            .lowercase()
        return "@drawable/${denoiseOcrIdentifier(cleaned)}"
    }

    private fun cleanInteger(value: String): String {
        return extractOcrNumber(value) ?: value
    }

    private fun cleanFloat(value: String): String {
        return Regex("-?\\d+\\.?\\d*").find(value)?.value ?: value
    }

    private fun extractOcrNumber(value: String): String? {
        val numberCandidateRegex = Regex("-?[\\doOlIzZsSbB]+")
        val match = numberCandidateRegex.find(value) ?: return null

        return match.value
            .replace(ocrLetterOToZeroRegex, "0")
            .replace(ocrLetterIToOneRegex, "1")
            .replace(ocrLetterZToTwoRegex, "2")
            .replace(ocrLetterSToFiveRegex, "5")
            .replace(ocrLetterBToSixRegex, "6")
    }

    private fun resolveXmlAttribute(
        key: AttributeKey,
        value: String,
        tag: String
    ): Pair<String, String> {
        if (key == AttributeKey.BACKGROUND && tag == "Button") {
            return "app:backgroundTint" to value
        }

        if (key == AttributeKey.ID) {
            return key.xmlName to value.replace(" ", "_")
        }

        return key.xmlName to value
    }
}
