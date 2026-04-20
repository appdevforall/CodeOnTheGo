package org.appdevforall.codeonthego.computervision.domain.xml

class XmlContext(
    val builder: StringBuilder = StringBuilder(),
    private val counters: MutableMap<String, Int> = mutableMapOf()
) {
    fun nextId(label: String): String {
        val count = counters.getOrPut(label) { 0 } + 1
        counters[label] = count
        return "${label.replace(Regex("[^a-zA-Z0-9_]"), "_")}_$count"
    }

    fun appendLine(text: String = "") {
        builder.appendLine(text)
    }

    fun append(text: String) {
        builder.append(text)
    }

    override fun toString(): String = builder.toString()
}

fun String.escapeXmlAttr(): String = this.trim()
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&apos;")
