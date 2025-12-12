package appdevforall.codeonthego.computervision.utils

object TextCleaner {

    private val nonAlphanumericRegex = Regex("[^a-zA-Z0-9 ]")

    fun cleanText(text: String): String {
        return text.replace("\n", " ")
            .replace(nonAlphanumericRegex, "")
            .trim()
    }
}