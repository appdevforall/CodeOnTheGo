package org.appdevforall.codeonthego.computervision.domain.parser.sanitizer


class CompositeOcrSanitizer(
    private val sanitizers: List<OcrSanitizer>
) : OcrSanitizer {
    override fun sanitize(input: String): String {
        return sanitizers.fold(input) { acc, sanitizer ->
            sanitizer.sanitize(acc)
        }
    }
}
