package org.appdevforall.codeonthego.computervision.utils

object MetadataDetector {
    private val metadataSnippets = listOf(
        "<?xml",
        "xmlns:",
        "<linearlayout",
        "<scrollview",
        "<button",
        "<switch",
        "<view",
        "android:",
        "app:",
        "tools:",
        "/>"
    )

    private val metadataKeywords = listOf(
        "layout_width",
        "layout_height",
        "layout_margin",
        "layout_gravity",
        "textstyle",
        "textcolor",
        "textsize",
        "padding",
        "orientation",
        "baselinealigned",
        "match_parent",
        "wrap_content"
    )

    private val xmlAttributeRegex = Regex("""\b(?:android|app|tools):[a-zA-Z_]+\b""")
    private val assignmentRegex = Regex("""\b[a-zA-Z_]+(?:[:=])[^\s]+""")

    fun isCanvasMetadata(text: String): Boolean {
        val lowerText = text.lowercase()
        if (lowerText.isBlank()) return false
        if (metadataSnippets.any { snippet -> lowerText.contains(snippet) }) return true
        if (xmlAttributeRegex.containsMatchIn(lowerText)) return true
        if (metadataKeywords.any { keyword -> lowerText.contains(keyword) }) return true

        val assignmentCount = assignmentRegex.findAll(lowerText).count()
        return assignmentCount >= 2
    }

    fun isMetadataLabel(label: String): Boolean {
        val normalized = label.trim().lowercase()
        return normalized == "margin_metadata" || normalized.contains("metadata")
    }

    fun isMetadataDetection(label: String, text: String): Boolean {
        return isMetadataLabel(label) || isCanvasMetadata(text)
    }
}
