package org.appdevforall.codeonthego.computervision.domain.xml

class XmlContext(
    val builder: StringBuilder = StringBuilder(),
    private val counters: MutableMap<String, Int> = mutableMapOf()
) {
    private val usedIds = mutableSetOf<String>()

    fun nextId(label: String, initialIndex: Int = 0): String {
        val safeLabel = label.replace(Regex("[^a-zA-Z0-9_]"), "_")

        var count = counters.getOrDefault(safeLabel, initialIndex - 1)
        var newId: String

        do {
            count++
            newId = "${safeLabel}_$count"
        } while (usedIds.contains(newId))

        counters[safeLabel] = count
        usedIds.add(newId)

        return newId
    }

    fun registerId(id: String) {
        usedIds.add(id)
    }

    fun resolveId(requestedId: String?, fallbackLabel: String): String {
        return if (requestedId != null) {
            registerId(requestedId)
            requestedId
        } else {
            nextId(fallbackLabel)
        }
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
