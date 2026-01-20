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
        val packagePattern = Regex("""^\s*package\s+([\w.]+)""", RegexOption.MULTILINE)
        return packagePattern.find(source)?.groupValues?.get(1)
    }

    fun extractClassName(source: String): String? {
        val classPattern = Regex("""^\s*class\s+(\w+)""", RegexOption.MULTILINE)
        classPattern.find(source)?.groupValues?.get(1)?.let { return it }

        val objectPattern = Regex("""^\s*object\s+(\w+)""", RegexOption.MULTILINE)
        objectPattern.find(source)?.groupValues?.get(1)?.let { return it }

        return null
    }

    fun detectAllPreviewFunctions(source: String): List<PreviewConfig> {
        val previews = mutableListOf<PreviewConfig>()
        val seenFunctions = mutableSetOf<String>()

        val previewPattern = Regex(
            """@Preview\s*(?:\(([^)]*)\))?\s*(?:@\w+(?:\s*\([^)]*\))?[\s\n]*)*fun\s+(\w+)""",
            setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL)
        )
        previewPattern.findAll(source).forEach { match ->
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

        val composablePreviewPattern = Regex(
            """@Composable\s*(?:@\w+(?:\s*\([^)]*\))?[\s\n]*)*@Preview\s*(?:\(([^)]*)\))?[\s\n]*(?:@\w+(?:\s*\([^)]*\))?[\s\n]*)*fun\s+(\w+)""",
            setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL)
        )
        composablePreviewPattern.findAll(source).forEach { match ->
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
            val composablePattern = Regex("""@Composable\s+fun\s+(\w+)""")
            composablePattern.findAll(source).forEach { match ->
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
        val pattern = Regex("""$name\s*=\s*(\d+)""")
        return pattern.find(params)?.groupValues?.get(1)?.toIntOrNull()
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(PreviewSourceParser::class.java)
    }
}
