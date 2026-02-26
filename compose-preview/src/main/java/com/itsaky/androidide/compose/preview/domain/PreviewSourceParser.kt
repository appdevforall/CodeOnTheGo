package com.itsaky.androidide.compose.preview.domain

import com.itsaky.androidide.compose.preview.PreviewConfig
import com.itsaky.androidide.compose.preview.domain.model.ParsedPreviewSource
import org.slf4j.LoggerFactory

class PreviewSourceParser {

    fun parse(source: String): ParsedPreviewSource? {
        val packageName = extractPackageName(source) ?: return null
        val className = extractClassName(source)
        val previewConfigs = detectAllPreviewFunctions(source)
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

    fun detectAllPreviewFunctions(source: String): List<PreviewConfig> {
        val previews = mutableListOf<PreviewConfig>()
        val seenFunctions = mutableSetOf<String>()

        PREVIEW_ANNOTATION_PATTERN.findAll(source).forEach { match ->
            val params = match.groupValues[1]
            val functionName = match.groupValues[2]
            if (seenFunctions.add(functionName)) {
                previews.add(PreviewConfig(
                    functionName = functionName,
                    heightDp = extractIntParam(params, "heightDp"),
                    widthDp = extractIntParam(params, "widthDp")
                ))
            }
        }

        COMPOSABLE_PREVIEW_PATTERN.findAll(source).forEach { match ->
            val params = match.groupValues[1]
            val functionName = match.groupValues[2]
            if (seenFunctions.add(functionName)) {
                previews.add(PreviewConfig(
                    functionName = functionName,
                    heightDp = extractIntParam(params, "heightDp"),
                    widthDp = extractIntParam(params, "widthDp")
                ))
            }
        }

        if (previews.isEmpty()) {
            COMPOSABLE_FUNCTION_PATTERN.findAll(source).forEach { match ->
                val functionName = match.groupValues[1]
                if (seenFunctions.add(functionName)) {
                    previews.add(PreviewConfig(functionName = functionName))
                }
            }
        }

        LOG.debug("Detected {} preview functions: {}", previews.size, previews.map { it.functionName })
        return previews
    }

    private fun extractIntParam(params: String, name: String): Int? {
        if (params.isBlank()) return null
        return Regex("""$name\s*=\s*(\d+)""").find(params)?.groupValues?.get(1)?.toIntOrNull()
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(PreviewSourceParser::class.java)

        // Matches: package com.example.app
        private val PACKAGE_PATTERN = Regex("""^\s*package\s+([\w.]+)""", RegexOption.MULTILINE)

        // Matches: class ClassName
        private val CLASS_PATTERN = Regex("""^\s*class\s+(\w+)""", RegexOption.MULTILINE)

        // Matches: object ObjectName
        private val OBJECT_PATTERN = Regex("""^\s*object\s+(\w+)""", RegexOption.MULTILINE)

        // Matches: @Preview(...) fun FunctionName
        private val PREVIEW_ANNOTATION_PATTERN = Regex(
            """@Preview\s*(?:\(([^)]*)\))?\s*(?:@\w+(?:\s*\([^)]*\))?[\s\n]*)*fun\s+(\w+)""",
            setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL)
        )

        // Matches: @Composable @Preview(...) fun FunctionName
        private val COMPOSABLE_PREVIEW_PATTERN = Regex(
            """@Composable\s*(?:@\w+(?:\s*\([^)]*\))?[\s\n]*)*@Preview\s*(?:\(([^)]*)\))?[\s\n]*(?:@\w+(?:\s*\([^)]*\))?[\s\n]*)*fun\s+(\w+)""",
            setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL)
        )

        // Matches: @Composable fun FunctionName (fallback when no @Preview found)
        private val COMPOSABLE_FUNCTION_PATTERN = Regex("""@Composable\s+fun\s+(\w+)""")
    }
}
