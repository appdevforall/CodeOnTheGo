package com.itsaky.androidide.compose.preview.domain

import com.itsaky.androidide.compose.preview.PreviewConfig
import com.itsaky.androidide.compose.preview.domain.model.ParsedPreviewSource
import org.slf4j.LoggerFactory
import kotlin.math.roundToInt

class PreviewSourceParser {

    fun parse(source: String): ParsedPreviewSource? {
        val packageName = extractPackageName(source) ?: return null
        val className = extractClassName(source)
        val previewConfigs = detectAllPreviewFunctions(source, packageName)
        return ParsedPreviewSource(packageName, className, previewConfigs)
    }

    fun extractPackageName(source: String): String? {
        return PACKAGE_PATTERN.find(source)?.groupValues?.get(1)
    }

    fun extractClassName(source: String): String? {
        CLASS_PATTERN.find(source)?.groupValues?.get(1)?.let { return it }
        OBJECT_PATTERN.find(source)?.groupValues?.get(1)?.let { return it }
        return null
    }

    fun detectAllPreviewFunctions(source: String, packageName: String): List<PreviewConfig> {
        val raws = mutableListOf<RawPreview>()
        PREVIEW_OCCURRENCE.findAll(source).forEach { match ->
            val params = match.groupValues[1]
            val function = functionAfter(source, match.range.last + 1) ?: return@forEach
            val parameterProvider = extractPreviewParameter(function.params, source, packageName)
            raws.add(RawPreview(function.name, params, parameterProvider))
        }

        MULTIPREVIEW_OCCURRENCE.findAll(source).forEach { match ->
            val annotation = match.groupValues[1]
            val function = functionAfter(source, match.range.last + 1) ?: return@forEach
            val parameterProvider = extractPreviewParameter(function.params, source, packageName)
            multipreviewParams(annotation).forEach { synthesized ->
                raws.add(RawPreview(function.name, synthesized, parameterProvider))
            }
        }

        if (raws.isEmpty()) {
            COMPOSABLE_FUNCTION_PATTERN.findAll(source).forEach { match ->
                raws.add(RawPreview(match.groupValues[1], "", null))
            }
        }

        val countByFunction = raws.groupingBy { it.functionName }.eachCount()
        val ordinals = mutableMapOf<String, Int>()

        val configs = raws.map { raw ->
            val ordinal = ordinals.getOrDefault(raw.functionName, 0)
            ordinals[raw.functionName] = ordinal + 1
            val hasSiblings = (countByFunction[raw.functionName] ?: 1) > 1
            val name = extractStringParam(raw.params, "name")

            val displayName = when {
                name != null -> name
                hasSiblings -> "${raw.functionName} #${ordinal + 1}"
                else -> raw.functionName
            }
            val key = if (hasSiblings) "${raw.functionName}#$ordinal" else raw.functionName

            PreviewConfig(
                functionName = raw.functionName,
                key = key,
                displayName = displayName,
                group = extractStringParam(raw.params, "group"),
                widthDp = extractIntParam(raw.params, "widthDp"),
                heightDp = extractIntParam(raw.params, "heightDp"),
                showBackground = extractBooleanParam(raw.params, "showBackground"),
                backgroundColor = extractLongParam(raw.params, "backgroundColor"),
                uiMode = extractUiMode(raw.params),
                fontScale = extractFloatParam(raw.params, "fontScale"),
                locale = extractStringParam(raw.params, "locale"),
                parameterProvider = raw.parameterProvider?.providerFqn,
                parameterLimit = raw.parameterProvider?.limit ?: Int.MAX_VALUE,
                parameterIndex = raw.parameterProvider?.parameterIndex ?: 0
            )
        }

        LOG.debug("Detected {} preview functions: {}", configs.size, configs.map { it.functionName })
        return configs
    }

    private fun functionAfter(source: String, startIndex: Int): FunctionHeader? {
        val match = FUNCTION_AFTER.find(source, startIndex) ?: return null
        val params = extractParamList(source, match.range.last + 1)
        return FunctionHeader(match.groupValues[1], params)
    }

    private fun extractParamList(text: String, afterOpenParen: Int): String {
        var depth = 1
        var i = afterOpenParen
        while (i < text.length) {
            when (val c = text[i]) {
                '"', '\'' -> { i = skipLiteral(text, i, c); continue }
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return text.substring(afterOpenParen, i)
                }
            }
            i++
        }
        return text.substring(afterOpenParen, i)
    }

    private fun skipLiteral(text: String, start: Int, quote: Char): Int {
        var i = start + 1
        while (i < text.length) {
            when (text[i]) {
                '\\' -> { i += 2; continue }
                quote -> return i + 1
            }
            i++
        }
        return i
    }

    private fun extractPreviewParameter(functionParams: String, source: String, packageName: String): ParameterProvider? {
        if (functionParams.isBlank()) return null
        val match = PREVIEW_PARAMETER.find(functionParams) ?: return null
        val content = match.groupValues[1]
        val providerRef = PROVIDER_CLASS.find(content)?.groupValues?.get(1) ?: return null
        val limit = LIMIT_PATTERN.find(content)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE
        val index = parameterIndexAt(functionParams, match.range.first)
        return ParameterProvider(resolveProviderFqn(providerRef, source, packageName), limit, index)
    }

    private fun parameterIndexAt(params: String, position: Int): Int {
        var depth = 0
        var commas = 0
        var i = 0
        while (i < position && i < params.length) {
            when (val c = params[i]) {
                '"', '\'' -> { i = skipLiteral(params, i, c); continue }
                '(', '[', '{', '<' -> depth++
                ')', ']', '}' -> if (depth > 0) depth--
                '>' -> if (depth > 0 && i > 0 && params[i - 1] != '-') depth--
                ',' -> if (depth == 0) commas++
            }
            i++
        }
        return commas
    }

    private fun resolveProviderFqn(reference: String, source: String, packageName: String): String {
        if (reference.contains('.')) return reference
        val simpleName = reference
        Regex("""^\s*import\s+([\w.]+\.$simpleName)\s*$""", RegexOption.MULTILINE)
            .find(source)?.groupValues?.get(1)?.let { return it }
        return "$packageName.$simpleName"
    }

    private fun multipreviewParams(annotation: String): List<String> = when (annotation) {
        "PreviewLightDark" -> listOf(
            "name=\"Light\", uiMode=16",
            "name=\"Dark\", uiMode=32"
        )
        "PreviewFontScale" -> FONT_SCALES.map { scale ->
            "name=\"${(scale * 100).roundToInt()}%\", fontScale=${scale}f"
        }
        "PreviewScreenSizes" -> SCREEN_SIZES.map { (label, width, height) ->
            "name=\"$label\", widthDp=$width, heightDp=$height"
        }
        else -> emptyList()
    }

    private fun extractIntParam(params: String, name: String): Int? {
        if (params.isBlank()) return null
        return Regex("""\b$name\s*=\s*(\d+)""").find(params)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractStringParam(params: String, name: String): String? {
        if (params.isBlank()) return null
        return Regex("\\b$name\\s*=\\s*\"([^\"]*)\"").find(params)?.groupValues?.get(1)
    }

    private fun extractBooleanParam(params: String, name: String): Boolean {
        if (params.isBlank()) return false
        return Regex("""\b$name\s*=\s*(true|false)""").find(params)?.groupValues?.get(1) == "true"
    }

    private fun extractFloatParam(params: String, name: String): Float? {
        if (params.isBlank()) return null
        return Regex("""\b$name\s*=\s*([\d.]+)f?""").find(params)?.groupValues?.get(1)?.toFloatOrNull()
    }

    private fun extractUiMode(params: String): Int? {
        if (params.isBlank()) return null
        val raw = Regex("""\buiMode\s*=\s*([^,)]+)""").find(params)?.groupValues?.get(1)?.trim()
            ?: return null

        var result = 0
        var matched = false
        Regex("""0[xX][0-9a-fA-F]+|\d+""").findAll(raw).forEach { token ->
            val value = token.value
            val parsed = if (value.startsWith("0x", ignoreCase = true)) {
                value.substring(2).toIntOrNull(16)
            } else {
                value.toIntOrNull()
            }
            if (parsed != null) {
                result = result or parsed
                matched = true
            }
        }
        UI_MODE_CONSTANTS.forEach { (name, value) ->
            if (raw.contains(name)) {
                result = result or value
                matched = true
            }
        }
        return if (matched) result else null
    }

    private fun extractLongParam(params: String, name: String): Long? {
        if (params.isBlank()) return null
        val raw = Regex("""\b$name\s*=\s*(0[xX][0-9a-fA-F]+|\d+)""")
            .find(params)?.groupValues?.get(1) ?: return null
        return try {
            if (raw.startsWith("0x", ignoreCase = true)) raw.substring(2).toLong(16) else raw.toLong()
        } catch (e: NumberFormatException) {
            null
        }
    }

    private data class RawPreview(
        val functionName: String,
        val params: String,
        val parameterProvider: ParameterProvider?
    )

    private data class FunctionHeader(val name: String, val params: String)

    private data class ParameterProvider(val providerFqn: String, val limit: Int, val parameterIndex: Int)

    companion object {

        private val LOG = LoggerFactory.getLogger(PreviewSourceParser::class.java)

        private val PACKAGE_PATTERN = Regex("""^\s*package\s+([\w.]+)""", RegexOption.MULTILINE)
        private val CLASS_PATTERN = Regex("""^\s*class\s+(\w+)""", RegexOption.MULTILINE)
        private val OBJECT_PATTERN = Regex("""^\s*object\s+(\w+)""", RegexOption.MULTILINE)

        private val PREVIEW_OCCURRENCE = Regex("""@Preview\b\s*(?:\(([^)]*)\))?""")

        private val FUNCTION_AFTER = Regex("""fun\s+(\w+)\s*\(""")

        private val PREVIEW_PARAMETER = Regex("""@PreviewParameter\s*\(([^)]*)""")
        private val PROVIDER_CLASS = Regex("""([\w.]+)::class""")
        private val LIMIT_PATTERN = Regex("""\blimit\s*=\s*(\d+)""")

        private val MULTIPREVIEW_OCCURRENCE =
            Regex("""@(PreviewLightDark|PreviewFontScale|PreviewScreenSizes)\b""")

        private val FONT_SCALES = listOf(0.85f, 1.0f, 1.15f, 1.3f, 1.5f, 1.8f, 2.0f)
        private val SCREEN_SIZES = listOf(
            Triple("Phone", 411, 891),
            Triple("Foldable", 673, 841),
            Triple("Tablet", 1280, 800),
            Triple("Desktop", 1920, 1080)
        )

        private val UI_MODE_CONSTANTS = mapOf(
            "UI_MODE_NIGHT_YES" to 0x20,
            "UI_MODE_NIGHT_NO" to 0x10,
            "UI_MODE_TYPE_NORMAL" to 0x01,
            "UI_MODE_TYPE_DESK" to 0x02,
            "UI_MODE_TYPE_CAR" to 0x03,
            "UI_MODE_TYPE_TELEVISION" to 0x04,
            "UI_MODE_TYPE_WATCH" to 0x06
        )

        private val COMPOSABLE_FUNCTION_PATTERN = Regex("""@Composable\s+fun\s+(\w+)""")
    }
}
