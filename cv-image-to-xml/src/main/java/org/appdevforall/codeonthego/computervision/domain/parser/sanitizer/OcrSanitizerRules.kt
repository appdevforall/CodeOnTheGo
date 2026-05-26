package org.appdevforall.codeonthego.computervision.domain.parser.sanitizer


class ColorSanitizer : DictionaryRegexSanitizer() {
    override val rawRules = mapOf(
        "backgroundired" to "background: red",
        "backgroundred" to "background: red",
        "\\bback[a-z]*[-_.]?\\s*[:;]\\s*" to "background: "
    )
}

class TextAttributeSanitizer : DictionaryRegexSanitizer() {
    override val rawRules = mapOf(
        "text\\s*st[yj]l?e?" to "text_style"
    )
}

class DimensionSanitizer : DictionaryRegexSanitizer() {
    override val rawRules = mapOf(
        "[il]ay[a-z]*[-_.\\s]*w[a-z0-9]*\\.?\\s*[:;]\\s*" to "layout_width: ",
        "[il]ay[a-z]*[-_.\\s]*hei[a-z0-9]*\\.?\\s*[:;]\\s*" to "layout_height: ",
        "m?w?at[ce]h[-_\\s]?p[ar]+ent" to "match_parent"
    )
}

class MarginPaddingSanitizer : DictionaryRegexSanitizer() {
    override val rawRules = mapOf(
        "layout_margin\\s+(top|bottom|start|end|left|right)" to "layout_margin_$1",
        "padding\\s+(top|bottom|start|end|left|right)" to "padding_$1"
    )
}

class StructureSanitizer : DictionaryRegexSanitizer() {
    override val rawRules = mapOf(
        "horizontal\\s+gravity\\s*:\\s*center\\s+layout" to "layout_gravity: center_horizontal",
        "\\b[ilL][dl]\\b\\s*[:;]?" to "id: ",
        "\\bS[ec][rt]\\b\\s*[:;]?" to "src: "
    )
}
