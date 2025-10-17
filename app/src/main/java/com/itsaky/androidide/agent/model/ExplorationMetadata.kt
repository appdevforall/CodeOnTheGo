package com.itsaky.androidide.agent.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ExplorationMetadata(
    val kind: ExplorationKind,
    val items: List<String> = emptyList(),
    val query: String? = null,
    val path: String? = null,
    @SerialName("match_count")
    val matchCount: Int? = null,
    @SerialName("entry_count")
    val entryCount: Int? = null
) {
    fun toMap(): Map<String, Any> {
        val result = mutableMapOf<String, Any>(
            "kind" to kind.name.lowercase()
        )
        if (items.isNotEmpty()) {
            result["items"] = items
        }
        query?.let { result["query"] = it }
        path?.let { result["path"] = it }
        matchCount?.let { result["match_count"] = it }
        entryCount?.let { result["entry_count"] = it }
        return result
    }

    companion object {
        fun fromMap(raw: Map<*, *>?): ExplorationMetadata? {
            if (raw == null) return null
            val kindValue = raw["kind"]?.toString()?.uppercase() ?: return null
            val kind = runCatching { ExplorationKind.valueOf(kindValue) }.getOrNull() ?: return null
            val items = (raw["items"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
            val query = raw["query"]?.toString()
            val path = raw["path"]?.toString()
            val matchCount = (raw["match_count"] as? Number)?.toInt()
            val entryCount = (raw["entry_count"] as? Number)?.toInt()
            return ExplorationMetadata(
                kind = kind,
                items = items,
                query = query,
                path = path,
                matchCount = matchCount,
                entryCount = entryCount
            )
        }
    }
}

@Serializable
enum class ExplorationKind {
    READ,
    LIST,
    SEARCH
}
