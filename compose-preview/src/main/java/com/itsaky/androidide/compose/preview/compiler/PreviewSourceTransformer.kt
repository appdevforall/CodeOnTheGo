package com.itsaky.androidide.compose.preview.compiler

import org.slf4j.LoggerFactory

object PreviewSourceTransformer {

    private val LOG = LoggerFactory.getLogger(PreviewSourceTransformer::class.java)

    private val CUSTOM_THEME_IMPORT = Regex(
        """import\s+[\w.]+\.theme\.\w*Theme"""
    )

    private val CUSTOM_THEME_CALL = Regex(
        """(\w+Theme)\s*(\([^)]*\))?\s*\{"""
    )

    private const val MATERIAL_THEME_IMPORT =
        "import androidx.compose.material3.MaterialTheme"

    fun transform(source: String): String {
        var result = source
        var transformationsMade = 0

        result = CUSTOM_THEME_IMPORT.replace(result) { match ->
            transformationsMade++
            LOG.debug("Stripped custom theme import: {}", match.value)
            "// ${match.value}  // Stripped for preview"
        }

        result = CUSTOM_THEME_CALL.replace(result) { match ->
            val themeName = match.groupValues[1]
            if (themeName == "MaterialTheme") {
                match.value
            } else {
                transformationsMade++
                LOG.debug("Replaced {} with MaterialTheme", themeName)
                "MaterialTheme {"
            }
        }

        if (!result.contains("import androidx.compose.material3.MaterialTheme") &&
            !result.contains("import androidx.compose.material.MaterialTheme")) {
            result = addImport(result, MATERIAL_THEME_IMPORT)
            transformationsMade++
            LOG.debug("Added MaterialTheme import")
        }

        if (transformationsMade > 0) {
            LOG.info("Transformed source: {} modifications made", transformationsMade)
        }

        return result
    }

    private fun addImport(source: String, importStatement: String): String {
        val packageIndex = source.indexOf("package ")
        if (packageIndex < 0) {
            return "$importStatement\n$source"
        }

        val packageEnd = source.indexOf('\n', packageIndex)
        if (packageEnd < 0) {
            return "$source\n$importStatement"
        }

        return source.substring(0, packageEnd + 1) +
            "\n$importStatement\n" +
            source.substring(packageEnd + 1)
    }
}
