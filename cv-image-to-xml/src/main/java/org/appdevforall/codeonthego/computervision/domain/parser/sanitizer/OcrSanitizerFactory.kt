package org.appdevforall.codeonthego.computervision.domain.parser.sanitizer


object OcrSanitizerFactory {
    fun createDefaultSanitizer(): OcrSanitizer {
        return CompositeOcrSanitizer(
            listOf(
                ColorSanitizer(),
                TextAttributeSanitizer(),
                DimensionSanitizer(),
                MarginPaddingSanitizer(),
                StructureSanitizer()
            )
        )
    }
}
