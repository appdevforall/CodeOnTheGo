package org.appdevforall.codeonthego.computervision.domain.parser.sanitizer


interface OcrSanitizer {
    fun sanitize(input: String): String
}

abstract class DictionaryRegexSanitizer : OcrSanitizer {
    protected abstract val rawRules: Map<String, String>

    private val compiledRules: List<Pair<Regex, String>> by lazy {
        rawRules.map { (pattern, replacement) ->
            Regex(pattern, RegexOption.IGNORE_CASE) to replacement
        }
    }

    override fun sanitize(input: String): String {
        return compiledRules.fold(input) { acc, (regex, replacement) ->
            acc.replace(regex, replacement)
        }
    }
}
